package hermit.runtime;

import static hermit.answer.AnswerGate.*;
import static hermit.checkpoint.TurnReplay.*;
import static hermit.claims.ClaimsGate.*;
import static hermit.plan.PlanGate.*;
import static hermit.plan.PlanShaper.*;
import static hermit.plan.RepeatGuard.*;
import static hermit.review.ReviewPanel.*;
import static hermit.runtime.LoopSettings.*;
import static hermit.runtime.RepairCoordinator.*;
import static hermit.runtime.ResultSummaries.*;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hermit.analysis.graph.CodeGraph;
import hermit.answer.AnswerAnnotation;
import hermit.answer.AnswerGate;
import hermit.answer.FormatterRegistry;
import hermit.api.Action;
import hermit.api.Agent;
import hermit.api.AgentListener;
import hermit.checkpoint.Checkpoint;
import hermit.checkpoint.TurnSnapshot;
import hermit.claims.Claim;
import hermit.claims.ClaimInspector;
import hermit.claims.ClaimLedger;
import hermit.claims.ClaimSession;
import hermit.context.ActivityRecorder;
import hermit.context.FrameStack;
import hermit.context.ReadHandoff;
import hermit.context.TieredContext;
import hermit.evidence.CitationValidator;
import hermit.evidence.Evidence;
import hermit.memory.MemoryService;
import hermit.model.ChatClient;
import hermit.model.DecisionClient;
import hermit.model.DecisionConfig;
import hermit.model.HelperChain;
import hermit.model.HelperEndpoint;
import hermit.model.HostedBudget;
import hermit.model.HostedPlatforms;
import hermit.model.ModelEndpoint;
import hermit.model.ModelRouter;
import hermit.model.escalation.EscalationLadder;
import hermit.plan.Branch;
import hermit.plan.BranchSelector;
import hermit.plan.ParsedPlan;
import hermit.plan.PlanGate;
import hermit.plan.PlanNode;
import hermit.plan.PlanParser;
import hermit.plan.PlanPromptBuilder;
import hermit.plan.PlanService;
import hermit.plan.TaskPlan;
import hermit.review.DecisionReviewer;
import hermit.review.OptionalReviewer;
import hermit.review.PlanGuard;
import hermit.review.ReviewPanel;
import hermit.review.StructuredReviewer;
import hermit.text.CodeFence;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.api.OptionalTool;
import hermit.tool.api.ToolRegistry;
import hermit.tool.generate.SpecTestComposerTool;
import hermit.tool.graph.CodeGraphTool;
import hermit.tool.helper.HelperModelTool;
import hermit.tool.process.BashTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class AgentLoop implements Agent {

    private final ToolRegistry toolRegistry;
    private final ChatClient llmClient;
    private final PlanService coTReasoner;
    private final PlanPromptBuilder planPrompts = new PlanPromptBuilder();
    private final ToolExecutor toolExecutor;
    private final BranchSelector branchingEngine;
    private final PlanParser planParser;
    private final AgentListener listener;
    private final FormatterRegistry formatters = new FormatterRegistry();
    // Outer-LLM fallback chain (a local helper, then hosted ones), shared process-wide,
    // self-refreshing every 5 min. Used for plan refine/author/critique, answer refine,
    // and the program-failure consult - the first available link that answers wins.
    private final HelperChain helpers = HelperChain.shared();
    // Cap on the context text sent to the remote critic: the local helper's Qwen-14B runs at a
    // small (2048-token) context on 16GB, so keep the review prompt well under it.
    /** A success of one of these means the coached step was taken. */
    private static final Set<String> EDIT_TOOLS_FOR_HINT = Set.of(
            "SpecTestComposer", "TreeCodegen", "TreeEdit", "WriteProgram");

    private static final int REMOTE_CRITIC_CONTEXT_CAP = 3500;
    private static final int VERDICT_CRITIC_EVIDENCE_CAP = 14000;   // the second reviewer sees the reads themselves
    private static final int MAX_VERDICT_CRITICS = 2;              // bounded per turn
    private int verdictCritics;                                     // mirrored into TurnSnapshot.verdictCritics
    private final List<String> disputedClaims = new ArrayList<>();   // mirrored into TurnSnapshot.disputedClaims
    private String lastRefinerSource = "helper";                    // who rewrote the last final answer
    // The remote 14B can also REWRITE the plan (refiner), not just critique it. On by
    // default when reachable; -Dhermit.remote.refine=false disables it. The refine
    // prompt also carries a tool list, so its context budget is tighter than the critic.
    private final boolean remoteRefinerEnabled =
            Boolean.parseBoolean(System.getProperty("hermit.remote.refine", "true"));
    private static final int REMOTE_REFINE_CONTEXT_CAP = 2500;
    // Diagnostic: the exact planning prompt sent to the brain each iteration is
    // appended here so the context (incl. conversation memory) can be inspected.
    private final Path contextDump = initContextDump();
    // Semantic snippet memory (SNIPPETS.md). null unless -Dhermit.snippets.enabled=true.
    // At planning we inject a reusable-reference block for the request; on user approval
    // (/remember) we ingest the last program. Tests can inject one via setMemoryService.
    private MemoryService memoryService = MemoryService.defaultServiceOrNull();
    private final ActivityRecorder activity = ActivityRecorder.configured();
    // Optional (needs a Jev API key - see DecisionConfig): backs ActivityRecorder.semanticRepeatIssue,
    // the reworded-repeat check that catches what rejectedRepeatIssue's exact-signature match misses.
    private final StructuredReviewer jev = DecisionConfig.hasKey() ? new DecisionReviewer(new DecisionClient()) : null;
    private String activityContext = "";
    private String lastMemoryQuery;    // the current request, for ingest-on-approve
    private String lastMemoryProgram;  // code of the most recent program run this session

    public AgentLoop() {
        this(new AgentListener() {});
    }

    public AgentLoop(AgentListener listener) {
        this.toolRegistry = new ToolRegistry();
        if (activity != null) this.toolRegistry.getAllTools().put("ActivityRecall",activity.recallTool());
        this.llmClient = new ChatClient();
        this.coTReasoner = new PlanService(this.llmClient);
        this.toolExecutor = new ToolExecutor(this.toolRegistry);
        this.toolExecutor.setFollower(InstructionFollower.fromSystemProperties(this.toolRegistry));   // null: none
        if (activity != null) this.toolExecutor.setObserver(new ToolExecutor.Observer() {
            public void before(Action action) { activity.before(action); }
            public void after(Action action, Map<String,Object> result) { activity.after(action, result); }
        });
        this.branchingEngine = new BranchSelector();
        this.planParser = new PlanParser();
        this.listener = new LedgerListener(listener);   // every refusal is recorded next to the plan it refused
        listener.onInit(toolRegistry.toolSummaries());
        probeOptionalTools();
    }

    /**
     * Auto-detect optional tools (those depending on an external resource that may
     * be absent, e.g. the remote helper agent) on a background thread so startup never
     * blocks on a network probe. Each result is reported via onOptionalTool; a
     * front-end shows a yellow notice when a tool is unavailable.
     */
    private void probeOptionalTools() {
        Thread t = new Thread(() -> toolRegistry.getAllTools().forEach((name, tool) -> {
            if (tool instanceof OptionalTool opt) {
                boolean available = opt.isAvailable();
                listener.onOptionalTool(name, available, opt.endpointLabel());
            }
        }));
        t.setDaemon(true);
        t.start();
    }

    // Conversation memory across turns: prior "User:"/"Assistant:" lines so a
    // follow-up like "add this to the program" can resolve earlier references.
    private final List<String> history = new ArrayList<>();
    // Budget derived from the real context window (queried), capped so a small
    // model isn't swamped; override with -Dhermit.historyBudget=<chars>.
    private final int historyCharBudget = Integer.getInteger("hermit.historyBudget", defaultHistoryBudget());

    /** Inject a memory service (tests / custom wiring); null disables the memory. */
    public void setMemoryService(MemoryService m) {
        this.memoryService = m;
    }

    /** True if the snippet memory is active (opt-in via -Dhermit.snippets.enabled). */
    public boolean memoryEnabled() {
        return memoryService != null;
    }

    /**
     * User approved the last answer (/remember): store the last program this turn as a
     * recipe + snippets for future reuse. Returns true if something was stored.
     */
    public boolean approveLastProgram() {
        if (memoryService == null || lastMemoryProgram == null || lastMemoryProgram.isBlank()) {
            return false;
        }
        memoryService.remember(lastMemoryQuery, "python", lastMemoryProgram);
        return true;
    }

    /** Test seam: set the query/program that /remember would ingest. */
    void noteProgramForMemory(String query, String program) {
        this.lastMemoryQuery = query;
        this.lastMemoryProgram = program;
    }

    private static final Set<String> CODE_TOOLS =
            Set.of("WriteProgram", "GenerateCode", "TddCycle");

    /**
     * For each code-writing action in the selected branch, retrieve the closest snippets
     * for that step's intent and append them to the context (so the next authoring round
     * composes from known-good parts). Deduped within the turn.
     */
    private final Set<String> injectedStepIntents = new HashSet<>();

    private void injectStepReferences(Branch selected, StringBuilder contextLog) {
        if (selected.actions() == null) {
            return;
        }
        for (Action a : selected.actions()) {
            if (!CODE_TOOLS.contains(a.toolName())) {
                continue;
            }
            String intent = stepIntent(a, selected);
            if (intent.isBlank() || !injectedStepIntents.add(intent.toLowerCase())) {
                continue;   // nothing to search on, or already injected this intent
            }
            try {
                String refs = memoryService.snippetReferenceBlock(intent, null);
                if (!refs.isBlank()) {
                    contextLog.append("\n[snippet memory · for step \"").append(intent).append("\"]\n")
                            .append(refs);
                    listener.onPlanRefined(true, "snippet memory: injected reference(s) for a step");
                }
            } catch (RuntimeException memoryDown) {
                // best-effort
            }
        }
    }

    /** The natural-language intent of a code-writing action, for snippet lookup. */
    private static String stepIntent(Action a, Branch selected) {
        Map<String, Object> p = a.parameters() == null ? Map.of() : a.parameters();
        for (String k : new String[] {"task", "behavior", "description"}) {
            Object v = p.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        // Fall back to the branch's description/name.
        return selected.description() != null && !selected.description().isBlank()
                ? selected.description() : selected.name();
    }

    @Override
    public String processRequest(String userRequest) {
        return processRequest(userRequest, null);
    }

    /**
     * Resume a turn from a step directory written by {@link Checkpoint}: the loop's state is read back, the workbench
     * restored to the step's tree (when configured), and the turn continues at the step's iteration.
     */
    public String resume(Path stepDir) throws IOException {
        TurnSnapshot state = Checkpoint.read(stepDir);
        if (checkpoint.workbench() != null && state.workbenchTree != null) {
            Checkpoint.restore(checkpoint.workbench(), state.workbenchTree, state.workbenchHead);
        }
        turnCounter = state.turn;
        listener.onPlanRefined(false, "RESUMED from " + stepDir.getFileName() + " at iteration " + state.iteration
                + " (seam " + state.seam + ")");
        pendingAuthoredPlan = replayedPlan(stepDir, state.seam);
        return processRequest(state.request, replayState(state));
    }

    /** Re-run the iteration whose plan step is {@code planState} - workbench restored, same state. */
    private String retryFrom(TurnSnapshot planState, String userRequest) {
        if (planState == null) {
            return processRequest(userRequest, null);
        }
        if (checkpoint.workbench() != null && planState.workbenchTree != null) {
            Checkpoint.restore(checkpoint.workbench(), planState.workbenchTree, planState.workbenchHead);
        }
        listener.onPlanRefined(false, "RETRY of iteration " + planState.iteration + " requested by the step control");
        return processRequest(userRequest, planState.copy());
    }

    /** A turn that starts with a failure ledger already filled - the evals replay a situation after refusals. */
    public String processRequestWithLedger(String userRequest, List<String> failedSteps) {
        return processRequestWithLedger(userRequest, failedSteps, null);
    }

    /** The evals may also seed the calls already executed this turn ("Tool|{params}" → Success|Error). */
    public String processRequestWithLedger(String userRequest, List<String> failedSteps, Map<String, String> executed) {
        TurnSnapshot state = new TurnSnapshot();
        state.failedSteps = new ArrayList<>(failedSteps == null ? List.of() : failedSteps);
        if (executed != null) {
            state.executedSignatures.putAll(executed);
        }
        state.turn = ++turnCounter;
        return processRequest(userRequest, state);
    }

    String processRequest(String userRequest, TurnSnapshot resumeFrom) {
        final Turn t = beginTheTurn(userRequest, resumeFrom);
        for (int iteration = t.live.iteration; t.steppingMode || iteration <= t.MAX_ITERATIONS
                || (pendingAuthoredPlan != null && !t.graceUsed); iteration++) {
            Next next = startTheIteration(t, userRequest, iteration);
            if (next == Next.GO_ON) {
                next = runTheExpertFix(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                next = planThisIteration(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                next = parseThePlan(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                refineThePlan(t, userRequest);
                next = reviewThePlan(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = gateTheEnding(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                next = guardTheEnding(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = finishTheAnswer(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                next = handleTheClarification(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                next = selectTheBranch(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = refuseForbiddenSteps(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = refuseInventedSteps(t, iteration);
            }
            if (next == Next.GO_ON) {
                next = trimThePlan(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = gateTheRepeats(t, userRequest);
            }
            if (next == Next.GO_ON) {
                next = vetoActivityRepeats(t);
            }
            if (next == Next.GO_ON) {
                next = reviewTheSelection(t, userRequest, iteration);
            }
            if (next == Next.GO_ON) {
                Map<String, Object> executionResults = executeTheBranch(t, iteration);
                next = foldTheResults(t, userRequest, iteration, executionResults);
            }
            if (next == Next.STOP) {
                return t.result;
            }
            // GO_ON and NEXT_ITERATION both go on to the next iteration, as the end of the body and continue did
        }

        listener.onHalted(t.contextLog.toString());
        return "Agent halted: maximum iteration limit reached. Context so far:\n" + t.contextLog;
    }

    /** What a phase of processRequest's loop tells the loop: go on to the next phase, end the iteration, or return. */
    private enum Next { GO_ON, NEXT_ITERATION, STOP }

    /**
     * Agent loop port, plan B (2026-10-25): the state processRequest kept in locals, so its phases can be separate
     * methods.
     *
     * <p>A field whose local had a constant initial value keeps that initialiser here; {@link #beginTheTurn} sets
     * the others in the order the locals were declared. The plan fields are the iteration's own, set anew by each
     * iteration; {@code result} is what processRequest returns when a phase answers {@link Next#STOP}.
     */
    private static final class Turn {
        TurnSnapshot live;
        PlanNode currentNode;
        StringBuilder contextLog;
        // Configurable, with a modestly-raised default: a hard multi-step task (locate → read → trace →
        // test → fix) needs more than 10 plan/execute rounds on a small driver. Kept adjustable via
        // -Dhermit.maxIterations=<n> for heavier tasks; the default stays low so a genuinely stuck run
        // still terminates promptly rather than burning many calls.
        int MAX_ITERATIONS;
        boolean steppingMode;
        final int MAX_PLAN_REVIEWS = 2;   // bounded review re-plans across the turn
        final int MAX_JSON_REPAIRS = 2;   // bounded remote repairs of malformed plan JSON
        // Final-answer execution gate: once a tool has SUCCEEDED this turn, a terminal
        // answer that asks for / merely describes what the result already provides is
        // rejected and the brain must re-answer from the result. Bounded so a model
        // that cannot synthesise the result still terminates rather than looping.
        final int MAX_ANSWER_GATES = 2;
        String lastAnswerGateIssue = null;   // FM-014: the same answer refusal, repeated, is bounded on its own
        int sameAnswerGateIssue = 0;
        // the deterministic gates (recording, commit, real run, hash) are exact and cheap - they
        // keep refusing after the LLM gate's budget is spent, and when the planner will not plan the commit of a
        // generated test after being told twice, the agent commits it (local, signed-off) itself
        final int MAX_DETERMINISTIC_GATES = 5;
        // whether the last build this turn failed to compile - a commit is refused until a build runs green
        boolean[] buildBroken;
        final int MAX_CLARIFY_GATES = 2;   // discoverable-fact clarifications are rejected, bounded
        // an inconsistent check of the annotator refuses the ending at most twice a turn, when refusals are on
        final int MAX_ANNOTATION_REFUSALS = 2;
        int annotationRefusals = 0;
        final int MAX_REPEAT_GATES = 2;    // verbatim re-runs of this turn's calls are rejected, bounded
        final int MAX_FORBIDDEN_GATES = 3; // read-only mode refuses plans carrying a push/post step, bounded
        final int MAX_PLAN_SHAPED_GATES = 2;   // plan-shaped endings, own bound (not shared with the LLM gate)
        final int MAX_WORKING_NOTE_GATES = 2;  // answers that are working notes, own bound
        final int MAX_CITATION_GATES = 3;      // answers citing code objects that do not exist, own bound
        int MAX_CLAIM_GATES;   // claims ledger: answers resting on unmapped or refuted claims
        // No-tool hallucination guard: reject a final answer produced with NO successful
        // tool this turn when the request plainly needs one (a URL/file/PR to inspect),
        // pushing the brain to actually fetch it. Bounded so it still terminates.
        final int MAX_NOTOOL_GATES = 2;
        final int MAX_FABRICATION_GATES = 2;
        // Web-read guard: for a current-fact question (version/date/latest), a WebSearch
        // SEARCH gives only cached snippets - insist a page is actually READ before the
        // answer, so a stale snippet can't become the answer. Bounded.
        final int MAX_WEBREAD_GATES = 2;
        // Remote-authored re-plan: when the local brain can't produce a parseable plan,
        // let the stronger remote model author the corrective plan. Bounded.
        // the escalated author's plan budget per turn - 2 by default; a drive that leans on a cloud author for a
        // composition it must iterate (stage 4's spec ops) raises it with -Dhermit.plan.maxAuthored
        int MAX_AUTHORED_PLANS;
        // Outer-LLM consult when a generated program fails to compile/run. Bounded.
        final int MAX_PROGRAM_CONSULTS = 2;
        // A corrected program from a bigger model, to be RUN directly next iteration
        // (authoritative - the 7B does not get to revert to its failing version).
        String[] pendingExpertProgram;
        // Repeat-action guard: break out when the brain re-proposes the same action.
        final int MAX_REPEAT = 2;          // trigger on the 3rd identical action
        StringBuilder successfulResults;
        // Ground truth for the anti-confabulation guard: did a real commit / test-or-emulator outcome
        // actually happen this session? (Set from tool results; the final answer may claim neither
        // unless the corresponding flag is true.)
        boolean[] committedThisSession;
        boolean[] testOutcomeObserved;
        // The most recent successful raw result + its tool, for the deterministic
        // result-formatter (used as the answer-gate fallback for structured skills).
        String[] lastSuccessTool;
        String[] lastRefusalHint;   // the coaching of the latest FAILED tool result
        // the tools that really succeeded this turn, from the result maps (rendering-independent gate input)
        Set<String> succeededTools;
        Map<String, Object>[] lastSuccessResult;

        int failedModelCalls = 0;      // consecutive failed model calls (a dead endpoint halts the turn)
        int MAX_UNPARSED_PLANS;   // F329
        int MAX_FAILED_MODEL_CALLS;
        long iterationStartedAt;   // for the step's timing note
        Path stepPlan = null;          // this iteration's plan step directory (null when stepping is off)
        TurnSnapshot planState = null;    // the state at the top of this iteration, for a retry
        // at iteration 20 of 20 the repeat guard refused the local plan, the answer was
        // synthesized - "it goes through the answer gates next iteration" - and there was no next iteration: the turn
        // halted at the limit with the answer in hand and unread. A pending authored or synthesized plan earns one
        // iteration past the limit (once), so the answer meets the gates instead of the halt.
        boolean graceUsed = false;

        boolean refined;  // true once the remote model has shaped this iteration's plan
        String llmResponseText;
        CodeFence.Extracted program;
        ParsedPlan plan;
        Branch selected;

        String result;   // what processRequest returns when a phase answers STOP
    }

    /**
     * Sets up a turn: binds the request and the resumed state, restores what a resume carries, fills the
     * context's prefix and the snippet memory, and returns the turn's state with the loop's limits.
     */
    private Turn beginTheTurn(String userRequest, TurnSnapshot resumeFrom) {
        final Turn t = new Turn();
        t.live = resumeFrom != null ? resumeFrom : new TurnSnapshot();
        t.live.request = userRequest;
        if (activity != null) activity.request(userRequest);
        if (resumeFrom == null) {
            t.live.turn = ++turnCounter;
        }
        t.currentNode = resumeFrom != null
                ? new PlanNode(t.live.nodeName == null ? "Root" : t.live.nodeName, userRequest,
                        new HashMap<>(t.live.nodeContext))
                : new PlanNode("Root", userRequest, new HashMap<>());
        issueRounds.clear();   // the stuck-issue budget is per TURN
        verdictCritics = t.live.verdictCritics;   // per turn, carried across a resume
        disputedClaims.clear();
        disputedClaims.addAll(t.live.disputedClaims);   // F413
        if (resumeFrom != null) {
            if (t.live.generatedFile == null
                    && t.live.successfulResults != null) {   // a suite composed before the stop
                Matcher w = Pattern.compile(
                        "status=Success, action=compose, suiteFile=([^,}\\s]+)[^}]*?testName=([A-Za-z_][\\w]*)")
                        .matcher(t.live.successfulResults);
                if (w.find()) {
                    t.live.generatedFile = w.group(1);
                    t.live.generatedMethod = w.group(2);
                }
            }
            issueRounds.putAll(t.live.issueRounds);
            planEscalationProbed = t.live.planEscalationProbed;
            history.clear();
            history.addAll(t.live.history);
            lastMemoryProgram = t.live.lastMemoryProgram;
            TreeEditTool.restoreOriginals(t.live.editOriginals);
        }
        currentRequest = userRequest == null ? "" : userRequest;
        t.contextLog = new StringBuilder(resumeFrom != null ? t.live.contextLog : "");
        routerContext = t.contextLog;   // the router counts refused endings and plans in it
        if (resumeFrom == null) {
            EscalationLadder.shared().newTurn();   // every job starts on the ladder's lowest rung again
        }
        if (ClaimSession.enabled()) {
            ClaimSession.bind(t.live.claims);
            ClaimSession.inspector().context(claimsTextOf(userRequest));   // the request's own types
            if (t.live.claims.isEmpty()) {   // the request's claims: every one must be mapped to code before a verdict
                List<Claim> added = ClaimSession.inspector().extract(t.live.claims,
                        claimsTextOf(userRequest), Claim.Origin.REQUEST, 0, ClaimSession.maxClaims());
                listener.onPlanRefined(false, "claims: " + added.size() + " claims of the request are in the ledger");
                if (Boolean.getBoolean("hermit.claims.mapRequestFirst")) {
                    // every claim of the request is mapped before the first plan: its proof is in every prompt from then on
                    for (Claim c : added) {
                        ClaimSession.inspector().map(c);
                        Evidence p = c.decidingEvidence();
                        listener.onPlanRefined(false, "claims: " + c.id + " [" + c.status + "] " + c.text
                                + (p != null ? " — " + p.kind + ": " + p.summary : c.undecidedReason != null ? " — " + c.undecidedReason : ""));
                    }
                }
            }
        }
        lastMemoryQuery = userRequest;
        if (resumeFrom == null) {
        if (!history.isEmpty()) {
            t.contextLog.append("Conversation so far (earlier turns, most recent last):\n");
            history.forEach(h -> t.contextLog.append(h).append("\n"));
            t.contextLog.append("\n");
        }
        t.contextLog.append("Current request: ").append(userRequest)
                  .append("\n").append(NO_STEPS_YET);
        // SNIPPET MEMORY (opt-in): inject reusable references from past verified
        // solutions so the planner can adapt them instead of deriving from scratch.
        lastMemoryProgram = null;
        injectedStepIntents.clear();
        if (memoryService != null) {
            try {
                String refs = memoryService.referenceBlock(userRequest, null);
                if (!refs.isBlank()) {
                    t.contextLog.append("\n\n").append(refs);
                    listener.onPlanRefined(true, "snippet memory: injected reusable reference(s)");
                }
            } catch (RuntimeException memoryDown) {
                // memory is best-effort; never block a turn on it
            }
        }
        }   // resumeFrom == null: the context prefix and the memory injection are already in the resumed log
        t.MAX_ITERATIONS = Integer.getInteger("hermit.maxIterations", 12);
        t.steppingMode = checkpoint.enabled();
        // Stepping sessions are explicitly supervised through checkpoints. Keep the normal-run
        // safety cap for unattended turns, but let a stepped turn continue until it reaches an
        // answer (or the operator stops it at a checkpoint).
        routerMaxIterations = t.steppingMode ? -1 : t.MAX_ITERATIONS;
        t.buildBroken = new boolean[] {t.live.buildBroken};
        t.MAX_CLAIM_GATES = Integer.getInteger("hermit.claims.maxGates", 8);
        t.MAX_AUTHORED_PLANS = t.steppingMode ? Integer.MAX_VALUE
                : Integer.getInteger("hermit.plan.maxAuthored", 2);
        t.pendingExpertProgram = new String[] {t.live.pendingExpertProgram};
        t.successfulResults = new StringBuilder(t.live.successfulResults == null ? "" : t.live.successfulResults);
        t.committedThisSession = new boolean[] {t.live.committedThisSession};
        t.testOutcomeObserved = new boolean[] {t.live.testOutcomeObserved};
        t.lastSuccessTool = new String[] {t.live.lastSuccessTool};
        t.lastRefusalHint = new String[] {t.live.lastRefusalHint};
        t.succeededTools = new LinkedHashSet<>(t.live.succeededTools);
        @SuppressWarnings("unchecked")
        Map<String, Object>[] lastSuccessResult = new Map[]{t.live.lastSuccessResult};
        t.lastSuccessResult = lastSuccessResult;
        t.MAX_UNPARSED_PLANS = t.steppingMode ? Integer.MAX_VALUE
                : Integer.getInteger("hermit.plan.maxUnparsed", 3);
        t.MAX_FAILED_MODEL_CALLS = t.steppingMode ? Integer.MAX_VALUE
                : Integer.getInteger("hermit.model.maxFailedCalls", 2);
        t.iterationStartedAt = System.currentTimeMillis();
        return t;
    }

    /**
     * Starts an iteration: the one grace iteration past the limit, the interruption check, the task plan on the first
     * iteration, and the "plan" seam.
     */
    private Next startTheIteration(Turn t, String userRequest, int iteration) {
        routerIteration = iteration;
        if (!t.steppingMode && iteration > t.MAX_ITERATIONS) {
            t.graceUsed = true;
            listener.onPlanRefined(false, "iteration limit reached with an authored answer pending — one more iteration for the gates");
        }
        // Cooperative interruption (e.g. Esc in the TUI): checked each
        // iteration, so cancellation takes effect at the next loop boundary.
        if (Thread.currentThread().isInterrupted()) {
            listener.onHalted("interrupted by user");
            t.result = "Interrupted by user.";
            return Next.STOP;
        }
        listener.onIteration(iteration, t.steppingMode ? -1 : t.MAX_ITERATIONS);
        t.iterationStartedAt = System.currentTimeMillis();
        if (iteration == 1 && taskPlanEnabled() && !TaskPlan.active(t.live) && anyEscalation()) {
            // the task plan (user, 2026-10-21): the strongest model writes the task's steps before any command runs;
            // when stepping, the supervisor reviews them at the "composed" seam (a refusal's note goes to the rewrite)
            String refusal = null;
            while (true) {
                List<TurnSnapshot.TaskStep> steps = writeTaskPlan(userRequest, t.contextLog.toString(), refusal);
                if (steps == null) {
                    listener.onPlanRefined(false, "no hosted model wrote a task plan — the step planner plans without one");
                    break;
                }
                t.live.taskPlan = steps;
                t.live.currentStep = 0;
                t.live.stepIterations = 0;
                listener.onPlanRefined(true, "the task plan (" + steps.size() + " steps) was written by " + escalationLabel());
                if (!checkpoint.enabled()) {
                    break;
                }
                mirror(t.live, iteration, t.contextLog, t.successfulResults, t.succeededTools, t.committedThisSession,
                        t.testOutcomeObserved, t.buildBroken, t.pendingExpertProgram, t.lastSuccessTool,
                        t.lastRefusalHint, t.lastSuccessResult, t.currentNode);
                Path composed = checkpoint.write(t.live, "composed");
                checkpoint.note(composed, "taskplan.md", TaskPlan.markdown(t.live));
                Checkpoint.Decision d = checkpoint.decide(composed, "composed", iteration);
                if (d.control() == Checkpoint.Control.STOP) {
                    throw new Checkpoint.Stop(composed);
                }
                if (d.control() == Checkpoint.Control.REJECT || d.control() == Checkpoint.Control.RETRY) {
                    refusal = d.note() == null ? "write it again" : d.note();
                    t.live.taskPlan = new ArrayList<>();
                    continue;
                }
                break;
            }
        }
        if (checkpoint.enabled()) {   // seam "plan" - the state before this iteration plans
            mirror(t.live, iteration, t.contextLog, t.successfulResults, t.succeededTools, t.committedThisSession,
                    t.testOutcomeObserved, t.buildBroken, t.pendingExpertProgram, t.lastSuccessTool, t.lastRefusalHint,
                    t.lastSuccessResult, t.currentNode);
            t.stepPlan = checkpoint.write(t.live, "plan");
            t.planState = t.live.copy();
            listener.onPlanRefined(false, "checkpoint " + t.stepPlan.getFileName());
            // with no caps in stepping mode, a scripted drive bounds a turn that never reaches an executed step here
            if (checkpoint.stopsAt("plan", iteration)) {
                checkpoint.note(t.stepPlan, "stopped", "stopAt plan:" + iteration + " at " + Instant.now());
                throw new Checkpoint.Stop(t.stepPlan);
            }
        }
        t.refined = false;
        return Next.GO_ON;
    }

    /** Runs the corrected program a bigger model handed over, in place of this iteration's plan. */
    private Next runTheExpertFix(Turn t, String userRequest, int iteration) {
        // AUTHORITATIVE EXPERT FIX: if a bigger model handed us a corrected program,
        // RUN it directly this iteration - bypass the 7B entirely so it cannot revert
        // to its own failing code. A bigger model's answer/plan overrides the 7B's.
        if (t.pendingExpertProgram[0] != null) {
            String prog = t.pendingExpertProgram[0];
            t.pendingExpertProgram[0] = null;
            lastMemoryProgram = prog;
            String lang = prog.contains("public static void main") ? "java"
                    : (prog.startsWith("#!") && prog.contains("bash")) ? "bash" : "python";
            List<Action> expertActions = List.of(new Action("WriteProgram",
                    new HashMap<>(Map.of("language", lang, "run", true))));
            listener.onBranchSelected("expert-fix", 1.0, 1, expertActions);
            CodeFence.Extracted expertCode = new CodeFence.Extracted(lang, prog, "");
            Map<String, Object> execRes =
                    toolExecutor.executeActions(injectProgramCode(expertActions, expertCode));
            listener.onToolResults(execRes);
            execRes.forEach((name, value) -> {
                if (isSuccess(value)) {
                    t.successfulResults.append(name).append(" => ")
                            .append(summarizeResults(Map.of(name, value))).append("\n");
                    t.lastSuccessTool[0] = ToolExecutor.baseToolName(name);
                    if (!AnswerGate.EDIT_TOOLS.contains(t.lastSuccessTool[0]) || AnswerGate.isEditSuccess(t.lastSuccessTool[0], value)) {
                        t.succeededTools.add(t.lastSuccessTool[0]);   // F313
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) value;
                    t.lastSuccessResult[0] = m;
                }
            });
            if (t.successfulResults.length() > 0) {
                t.live.haveSuccess = true;
            }
            observeGroundTruth(execRes, t.committedThisSession, t.testOutcomeObserved);
            t.contextLog.append("\n[Iteration ").append(iteration)
                    .append("] Ran the EXPERT-fixed program. Tool results: ")
                    .append(summarizeResults(execRes))
                    .append("\nIf it produced the answer, respond with Shape B (final_answer) reporting it.");
            // A still-failing expert fix can be consulted again (bounded below).
            String stillFailing = programFailure(execRes);
            if (stillFailing != null && t.live.programConsults < t.MAX_PROGRAM_CONSULTS) {
                String fix = consultOnProgramFailure(userRequest, stillFailing);
                if (fix != null && !fix.isBlank()) {
                    t.live.programConsults++;
                    t.pendingExpertProgram[0] = fix;
                }
            }
            t.currentNode = new PlanNode("expert-fix", userRequest, new HashMap<>(execRes));
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /**
     * Asks for this iteration's plan: the prompt, then the plan of a pending author, of the hosted edit author or of
     * the planner; two failed model calls in a row halt the turn.
     */
    private Next planThisIteration(Turn t, String userRequest, int iteration) {
        // STEP A: ask the brain for a plan (branches or final answer)
        ledgerLive = t.live;
        if (activity != null) {
            activity.iteration(t.stepPlan);
            activityContext = activity.context(userRequest);
        }
        currentLedgerBlock = PlanPromptBuilder.executedBlock(t.live.executedSignatures)   // what already ran, then
                + PlanPromptBuilder.failureLedgerBlock(t.live.failedSteps) + activityContext
                + (ClaimSession.enabled() ? ClaimLedger.render(t.live.claims) : "")
                + TaskPlan.block(t.live);   // the task plan: the planner works its current step   // what was refused - the planner, the refiner and the author all see both
        // the owner, 2026-10-26: the prompt fills the planner's window less its reply; the history gets what the rest leaves
        int contextBudget = LoopSettings.historyChars(LoopSettings.promptChars(),
                planPrompts.generatePrompt(t.currentNode, "", currentLedgerBlock).length());
        String history = TieredContext.enabled()   // the owner, 2026-10-26: older history summarised, not dropped
                ? TieredContext.shared().fit(t.contextLog.toString(), contextBudget, FrameStack.enabled() ? t.live : null)
                : PlanPromptBuilder.fitContext(t.contextLog.toString(), contextBudget);
        String planningPrompt = planPrompts.generatePrompt(t.currentNode, history, currentLedgerBlock);
        dumpContext(userRequest, iteration, planningPrompt);
        hostedEditPlan = false;
        if (iteration == 1 && pendingAuthoredPlan == null && Boolean.getBoolean("hermit.plan.initial") && anyEscalation()
                && !TaskPlan.active(t.live)) {
            // the task's first plan comes from the hosted job INITIAL_PLAN (the strongest model first) and takes the
            // path of any authored plan, through the plan guards and seams; the step planner plans it when none answers
            String initial = hostedAuthorPlan(userRequest, t.contextLog.toString(), EscalationLadder.Job.INITIAL_PLAN);
            ParsedPlan first = initial == null ? null : planParser.parse(initial);
            if (first != null && (first.isFinal() || (first.branches() != null && !first.branches().isEmpty()))) {
                pendingAuthoredPlan = initial;
                listener.onPlanRefined(true, "the task's first plan was written by " + escalationLabel());
            } else {
                listener.onPlanRefined(false, "no hosted model wrote the task's first plan — the step planner plans it");
            }
        }
        if (pendingAuthoredPlan != null) {   // a plan a stronger author wrote for this iteration
            t.llmResponseText = pendingAuthoredPlan;
            pendingAuthoredPlan = null;
            t.refined = true;
        } else if (hostedEditStep(Boolean.getBoolean("hermit.plan.hostedEdits"), userRequest, t.contextLog.toString(),
                t.live.executedSignatures)) {
            // the hosted author writes the plan that carries the
            // edit items; the local model plans the reads and is the fallback when the hosted plan does not parse
            String authored = remoteAuthorPlan(userRequest, t.contextLog + HOSTED_EDIT_NOTE);
            ParsedPlan hosted = authored == null ? null : planParser.parse(authored);
            if (hosted != null && hosted.branches() != null && !hosted.branches().isEmpty()) {
                t.llmResponseText = authored;
                t.refined = true;
                hostedEditPlan = true;
                listener.onPlanRefined(true, "the edit step's plan was written by " + escalationLabel());
            } else {
                t.llmResponseText = planningCall(planningPrompt);
            }
        } else {
            t.llmResponseText = planningCall(planningPrompt);
        }
        checkpoint.note(t.stepPlan, "prompt.txt", planningPrompt);
        checkpoint.note(t.stepPlan, "llm-raw.txt", t.llmResponseText);
        // the model endpoint had unloaded its model, every plan call failed at once and the
        // loop spent all nineteen remaining iterations in twelve seconds. Two failed calls in a row is a dead
        // endpoint, not a bad plan: halt (the plan step is on disk, so the turn resumes once the model is back).
        if (ChatClient.LLM_CALL_FAILED.equals(t.llmResponseText)) {
            if (++t.failedModelCalls >= t.MAX_FAILED_MODEL_CALLS) {
                String halt = "Agent halted: the model endpoint failed " + t.failedModelCalls + " times in a row ("
                        + ModelEndpoint.baseUrl() + ", model " + ModelEndpoint.model() + "). Load the model and resume"
                        + (t.stepPlan == null ? "." : " from " + t.stepPlan + ".")
                    + (PlanService.lastLlmError == null ? "" : " Last error: " + PlanService.lastLlmError);   // F387
                listener.onHalted(halt);
                t.result = halt;
                return Next.STOP;
            }
        } else {
            t.failedModelCalls = 0;
        }
        return Next.GO_ON;
    }

    /**
     * Parses the plan; malformed JSON is repaired or the plan authored by a stronger model, and a plan that still does
     * not parse is fed back for a re-plan, or halts the turn after too many in a row.
     */
    private Next parseThePlan(Turn t, String userRequest, int iteration) {
        // Detect an out-of-band fenced PROGRAM (python/bash/…) to inject into the
        // executing action below. The plan itself is parsed from the RAW response:
        // balanced JSON extraction stops at the plan object's closing brace, so a
        // trailing program fence is ignored - no stripping, no mangling.
        t.program = CodeFence.first(t.llmResponseText);

        // STEP B: parse the plan
        t.plan = planParser.parse(t.llmResponseText);
        // at iteration 19 the planner wrote a complete, correct ruling in prose;
        // the parser saw no JSON, the "JSON repair" helper was asked to fix it, and the repair came back as
        // `WriteProgram {language: python}` - a finished answer destroyed by a path meant for broken braces.
        // A text that reads as a ruling IS the final answer; it is never sent for repair.
        if (t.plan == null && isProseRuling(t.llmResponseText)) {
            t.plan = new ParsedPlan(t.llmResponseText.strip(), null, null);
            listener.onPlanRefined(false, "a prose ruling is a final answer — not sent for JSON repair");
        }
        if (activity != null) activity.proposed(t.plan, t.llmResponseText);
        currentPlanSummary = t.plan == null ? "an unparseable plan" : summarizePlan(t.plan);   // F374

        // REMOTE JSON REPAIR: a small model often emits malformed plan JSON
        // (unescaped quotes/newlines around embedded code). When it won't parse
        // and the stronger remote model is reachable, have it rewrite the raw
        // response into valid JSON, then re-parse. Bounded so it still terminates.
        if (t.plan == null && helpers.anyAvailable() && t.live.jsonRepairs < t.MAX_JSON_REPAIRS) {
            String repaired = remoteRepairJson(t.llmResponseText);
            ParsedPlan rp = repaired == null ? null : planParser.parse(repaired);
            CodeFence.Extracted repairedProgram = repaired == null ? null : CodeFence.first(repaired);
            if (rp != null && programlessRun(rp, repairedProgram == null ? null : repairedProgram.code())) {
                // supervised rewrite drive, FM-015: an analysis in prose was "repaired" into a program run with no
                // program twice; a repair that asks to run a program it does not carry is no repair
                listener.onPlanRefined(false, "the JSON repair returned a program run with no program — discarded");
                rp = null;
            }
            if (rp != null) {
                t.live.jsonRepairs++;
                t.plan = rp;
                t.llmResponseText = repaired;   // the plan the review panel reads is the one that runs
                t.program = CodeFence.first(repaired);   // code may have moved into a fenced block
                listener.onPlanRefined(true, "remote repaired the malformed plan JSON");
            }
        }

        // REMOTE-AUTHORED RE-PLAN: if the local brain still can't produce a
        // parseable plan (typically after a critic rejection it can't recover from),
        // let the stronger remote model AUTHOR the corrective plan from the goal +
        // context (which holds the prior results and the rejection reason). Bounded.
        // sixteen unparseable plans in a row burned the budget - an unparseable streak is the
        // planner's own failing and escalates like a review rejection (helpers, else the plan escalation
        // author); with nobody to ask, the third one halts the turn instead of spending the iterations
        if (t.plan == null) {
            t.live.unparsedPlans++;
        } else {
            t.live.unparsedPlans = 0;
        }
        if (t.plan == null && (helpers.anyAvailable() || anyEscalation())
                && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
            String authored = remoteAuthorPlan(userRequest, t.contextLog.toString());
            ParsedPlan ap = authored == null ? null : planParser.parse(authored);
            if (ap != null && !ap.isFinal() && !ap.isClarify()) {
                t.live.authoredPlans++;
                t.plan = ap;
                // the planner's call had failed, and the review panel read LLM_CALL_FAILED as the plan
                t.llmResponseText = authored;
                t.program = CodeFence.first(authored);
                t.refined = true;   // remote shaped this plan → skip the remote critic this round
                listener.onPlanRefined(true, "remote AUTHORED a corrective plan (local brain could not)");
            }
        }
        if (t.plan == null && t.live.unparsedPlans >= t.MAX_UNPARSED_PLANS) {
            String halt = "Agent halted: the planner produced " + t.live.unparsedPlans + " unparseable plans in a row and "
                    + "no stronger model could author one" + (t.stepPlan == null ? "." : " — resume from " + t.stepPlan + ".");
            listener.onHalted(halt);
            t.result = halt;
            return Next.STOP;
        }
        if (t.plan == null) {
            // Feed the unparseable response back so the brain can self-correct next round.
            listener.onPlanUnparsed(iteration, t.llmResponseText);
            t.contextLog.append("\n[Iteration ").append(iteration)
                      .append("] Your previous response was NOT valid JSON and could not be parsed. It was:\n")
                      .append(t.llmResponseText)
                      .append("\nRespond with exactly ONE JSON object per the contract. If you embed a "
                              + "program/code in a string value, you MUST escape every double-quote as \\\" and "
                              + "every newline as \\n so the JSON stays valid — or avoid embedding code by "
                              + "putting only a short shell command in the action and keeping the program out "
                              + "of the JSON.");
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /**
     * Has the remote refiner rewrite the plan; a rewrite that is no refinement is discarded and the local plan stands.
     */
    private void refineThePlan(Turn t, String userRequest) {
        // STEP B1: PLAN REFINER - when the stronger remote 14B is reachable, have
        // it REWRITE the local brain's branch-plan into a better one (fix gaps,
        // drop steps, make actions concrete). Falls back to the original plan if
        // the refiner is off, unavailable, or returns anything unusable. When it
        // does refine, we skip the remote CRITIC this iteration (the 14B already
        // shaped the plan - no point having it also critique its own output).
        // the 16B refiner's rewrites were discarded four times in one turn (an edit in
        // an analysis-only task, empty parameters, thirteen shotgun lookups, a placeholder symbol) at 30-55 s each -
        // a refiner that keeps degrading the plan is switched off for the rest of the turn; its critic role stays.
        if (!t.refined && remoteRefinerEnabled && helpers.anyAvailable()
                && !t.plan.isFinal() && !t.plan.isClarify() && !refinerExhausted(t.live.discardedRewrites)) {
            String refinedJson = refinePlan(capTail(t.contextLog.toString(), helperContextChars(REMOTE_REFINE_CONTEXT_CAP)),
                    t.llmResponseText);
            if (refinedJson != null) {
                int discardedBefore = t.live.discardedRewrites;
                ParsedPlan rp = planParser.parse(refinedJson);
                // the local refiner rewrote a locate plan INTO a TreeEdit of the source
                // twice in an analysis-only task ("do NOT edit any source file"); the critics caught both, at the
                // cost of two review rounds. A rewrite that adds an editing tool to an analysis-only request, or in
                // read-only mode, is not a refinement - the local plan stands.
                if (rp != null && introducesEdits(t.plan, rp)
                        && (BashTool.readOnly() || analysisOnly(userRequest))) {
                    listener.onPlanRefined(false, "remote refiner's rewrite added an editing step to an analysis-only task — kept the local plan");
                    rp = null;
                    t.live.discardedRewrites++;
                }
                // the refiner's rewrite was five tool names with EMPTY parameters
                // ("Enhance InventoryLedger": CodeQL {}, LocateFile {}, TreeEdit {} …) - nothing that can run
                if (rp != null && parameterlessActionIssue(rp) != null) {
                    listener.onPlanRefined(false, "remote refiner's rewrite has actions with no parameters — kept the local plan");
                    rp = null;
                    t.live.discardedRewrites++;
                }
                // the refiner rewrote a one-action plan into THIRTEEN lookups - the same
                // locate and callers that had already run, six of them on a method the previous step had shown does
                // not exist. A rewrite that re-runs calls this turn already made, or shotguns more than six actions,
                // is not a refinement.
                String shotgun = rp == null ? null : rewriteNotARefinementIssue(rp, t.live.executedSignatures);
                if (shotgun != null) {
                    listener.onPlanRefined(false, "remote refiner's rewrite " + shotgun + " — kept the local plan");
                    rp = null;
                    t.live.discardedRewrites++;
                }
                if (t.live.discardedRewrites > discardedBefore && refinerExhausted(t.live.discardedRewrites)) {
                    listener.onPlanRefined(false, "remote refiner switched off for the rest of this turn after "
                            + t.live.discardedRewrites + " discarded rewrites — it stays a critic");
                }
                if (rp != null && !rp.isFinal() && !rp.isClarify()
                        && rp.branches() != null && !rp.branches().isEmpty()) {
                    t.plan = rp;
                    t.llmResponseText = refinedJson;
                    t.refined = true;
                    listener.onPlanRefined(true, "remote refiner rewrote the plan into "
                            + rp.branches().size() + " branch(es)");
                }
            }
        }
    }

    /**
     * Puts the plan before the review panel; a rejected plan is re-planned or, at the last strike,
     * authored by a stronger model.
     */
    private Next reviewThePlan(Turn t, String userRequest) {
        // STEP B2: plan review - a panel checks the plan against
        // the context (missed a point? invented one? asking what's already
        // answered?). On concrete issues, feed them back and re-plan (bounded).
        // a plan made only of verbatim repeats of succeeded calls was sent to the critics -
        // four to six model calls, two review rounds, ninety seconds - before the repeat guard could refuse it in
        // milliseconds; the critics' own complaints ("stops after locating and tracing…") then cost another round. The
        // repeat guard sees such a plan first; the critics are skipped for it.
        boolean verbatimRepeatPlan = t.plan != null && t.plan.branches() != null && !t.plan.branches().isEmpty()
                && t.plan.branches().stream().allMatch(b -> allSucceededRepeats(b.actions(), t.live.executedSignatures));
        if (verbatimRepeatPlan) {
            listener.onPlanRefined(false, "every action repeats a call that already succeeded — critics skipped, the repeat guard decides");
        }
        // the SAME proposal (by action signature) can also be rejected
        // by the PLAN REVIEW below - not just at the later per-step guards F427 covers - and the review is
        // an expensive LLM call whose wording varies enough each time that F161's stuck-issue detector never
        // recognizes it as a repeat. Checked here, before any review call, against ALL of the plan's proposed
        // actions (branch selection has not happened yet); a hit reuses the exact prior rejection reason instead
        // of asking the reviewer the same question again, and still counts toward the same escalation budget.
        List<Action> proposedPlanActions = t.plan != null && t.plan.branches() != null
                ? t.plan.branches().stream()
                        .flatMap(b -> b.actions() == null ? Stream.<Action>empty() : b.actions().stream())
                        .toList()
                : List.of();
        String rejectedRepeatPlan = activity == null ? null : activity.rejectedRepeatIssue(proposedPlanActions);
        if (rejectedRepeatPlan == null && activity != null) {
            rejectedRepeatPlan = activity.semanticRepeatIssue(proposedPlanActions, jev);
        }
        if (rejectedRepeatPlan != null) {
            listener.onPlanRefined(false, "Activity veto (rejected repeat, pre-review): critics skipped — " + rejectedRepeatPlan);
        }
        if (rejectedRepeatPlan != null
                || ((t.live.planReviews < t.MAX_PLAN_REVIEWS || !activityContext.isBlank()) && !verbatimRepeatPlan)) {
            List<String> issues;
            if (rejectedRepeatPlan != null) {
                issues = List.of(rejectedRepeatPlan);
            } else {
                String recalled = activity == null ? "" : activity.context(t.llmResponseText);
                String reviewContext = t.contextLog + recalled + taskPlanReviewNote(t.live);
                // Optional (needs a Jev API key): one cheap call that can only SKIP the expensive multi-lens
                // panel below (up to 5 lenses + a remote critic) when confident the plan needs no review - it
                // never blocks a plan itself, so it adds no new veto path. A no-op without a configured key.
                if (PlanGuard.canSkipReview(jev, reviewContext, t.llmResponseText, currentRequest)) {
                    listener.onPlanRefined(false, "Jev guardrail: plan looks clean — critic panel skipped");
                    issues = List.of();
                } else if (hostedEditPlan && Boolean.getBoolean("hermit.step.pause")) {
                    // the hosted edit plan goes to the supervisor's seam; the local critics would only slow it
                    listener.onPlanRefined(false, "a hosted edit plan in step mode — critic panel skipped for the seam's review");
                    issues = List.of();
                } else if (onlyNamedReads(t.plan, currentRequest)) {
                    listener.onPlanRefined(false, "the plan only reads, as the request's 'Read first' asks — critic panel skipped");
                    issues = List.of();
                } else {
                    issues = reviewPlan(reviewContext, t.llmResponseText, !t.refined);
                }
                // a reviewer that raises the SAME issue round after round is stuck - no phrasing filter can
                // cover every wording, so a repeated issue loses its veto after STUCK_ISSUE_LIMIT rounds. The
                // planner keeps the feedback in context; it just stops blocking the step.
                issues = dropStuckIssues(issues, t.contextLog);
            }
            listener.onPlanReview(issues.isEmpty(), issues);
            if (!issues.isEmpty()) {
                t.live.planReviews++;
                // Last strike: once local re-planning is exhausted, don't just loop
                // into a bad plan/clarify - let the remote model AUTHOR the corrective
                // plan from the context + the reviewer's issues (bounded).
                boolean authoredNow = false;
                if (t.live.planReviews >= t.MAX_PLAN_REVIEWS && (helpers.anyAvailable() || anyEscalation())
                        && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                    String authored = remoteAuthorPlan(userRequest, t.contextLog
                            + "\n[Reviewer rejected the local plan; fix exactly this] "
                            + String.join(" | ", issues));
                    ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                    if (ap != null && !ap.isFinal() && !ap.isClarify()
                            && ap.branches() != null && !ap.branches().isEmpty()) {
                        t.live.authoredPlans++;
                        t.plan = ap;
                        t.program = CodeFence.first(authored);
                        t.refined = true;
                        authoredNow = true;
                        listener.onPlanRefined(true,
                                "remote AUTHORED a corrective plan after local review failed");
                        // fall through to execute this authored plan (no re-review)
                    }
                }
                if (!authoredNow) {
                    t.contextLog.append("\n[Plan review REJECTED your last plan] Fix ALL of these, "
                            + "using CONTEXT SO FAR (do not ask what is already answered, do not miss a point, "
                            + "do not invent one): ").append(String.join(" | ", issues))
                            .append("\nRe-plan now.");
                    return Next.NEXT_ITERATION;
                }
            }
        }
        return Next.GO_ON;
    }

    /**
     * Gates an ending: the deterministic ending checks, the citation and claims checks, the surrender check and the
     * answer gate, which may commit the generated test or answer through a deterministic formatter.
     */
    private Next gateTheEnding(Turn t, String userRequest, int iteration) {
        // STEP C: termination - answer or clarifying question. But first the
        // FINAL-ANSWER EXECUTION GATE: if a tool already SUCCEEDED this turn, a
        // terminal response that ignores it (asks for / describes what the result
        // already provides) is rejected and re-planned (bounded).
        boolean terminal = t.plan.isFinal() || t.plan.isClarify();
        if (t.plan.isFinal()) {
            noteRuling(t.plan.finalAnswer());   // the router's verdict-flip signal
        }
        // DETERMINISTIC ENDING CHECKS run first and on their OWN bound (F93 follow-up, drive 2I): the LLM
        // gate's two rejections used to exhaust the shared budget before the plan-shaped check ever ran.
        if (t.plan.isFinal() && t.live.haveSuccess && t.live.planShapedGates < t.MAX_PLAN_SHAPED_GATES) {
            String shaped = AnswerGate.planShapedAnswerIssue(t.plan.finalAnswer());
            if (shaped != null) {
                t.live.planShapedGates++;
                listener.onAnswerGate(false, List.of(shaped));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(shaped)
                        .append(" Report the findings from CONTEXT SO FAR as a Shape B final_answer, or propose "
                                + "the missing tool actions as Shape A.");
                return Next.NEXT_ITERATION;
            }
        }
        if (t.plan.isFinal() && t.live.haveSuccess && t.live.workingNoteGates < t.MAX_WORKING_NOTE_GATES) {
            String notes = AnswerGate.workingNotesIssue(t.plan.finalAnswer());   // F434
            if (notes != null) {
                t.live.workingNoteGates++;
                listener.onAnswerGate(false, List.of(notes));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(notes);
                return Next.NEXT_ITERATION;
            }
        }
        // every file, line, type and member an ending cites must exist in the repository - checked each time, for
        // every citation, before any reviewer spends a call on claims that rest on nothing
        if (terminal && t.live.citationGates < t.MAX_CITATION_GATES) {
            String cited = CitationValidator.issue(t.plan.isFinal() ? t.plan.finalAnswer() : t.plan.clarification(),
                    workbenchRoot(), userRequest);
            if (cited != null) {
                t.live.citationGates++;
                listener.onAnswerGate(false, List.of(cited));
                t.contextLog.append("\n[Answer gate REJECTED your ending — CITED CODE DOES NOT EXIST] ").append(cited);
                return Next.NEXT_ITERATION;
            }
        }
        // Claims ledger: every claim the answer makes is mapped to code and decided on evidence (CodeGraph, a Hindsight
        // recording, then a review of the lines); an answer resting on an unmapped or refuted claim does not stand
        if (t.plan.isFinal() && ClaimSession.enabled() && t.live.claimGates < t.MAX_CLAIM_GATES) {
            String claimIssue = claimsIssue(t.plan.finalAnswer(), iteration, t.contextLog, t.live);
            if (claimIssue != null) {
                t.live.claimGates++;
                listener.onAnswerGate(false, List.of(claimIssue));
                t.contextLog.append("\n[Answer gate REJECTED your ending — CLAIMS] ").append(claimIssue);
                return Next.NEXT_ITERATION;
            }
        }
        // a final answer that gives up while turns remain and the last refusal named the
        // next step is re-planned with that step, on its own small bound
        if (t.plan.isFinal() && t.live.surrenderGates < 2) {
            String surrender = AnswerGate.surrenderIssue(t.plan.finalAnswer(), t.lastRefusalHint[0],
                    t.steppingMode ? Integer.MAX_VALUE : t.MAX_ITERATIONS - iteration);
            if (surrender != null) {
                t.live.surrenderGates++;
                listener.onAnswerGate(false, List.of(surrender));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(surrender);
                return Next.NEXT_ITERATION;
            }
        }
        if (terminal && t.live.haveSuccess
                && (t.live.answerGates < t.MAX_ANSWER_GATES || t.live.deterministicGates < t.MAX_DETERMINISTIC_GATES)) {
            String answerText = t.plan.isFinal() ? t.plan.finalAnswer() : t.plan.clarification();
            String issue = answerGate(t.successfulResults.toString(), answerText, t.plan.isClarify(), t.succeededTools,
                    t.live.answerGates < t.MAX_ANSWER_GATES);
            if (issue != null && lastGateDeterministic && issue.startsWith("The request asks you to COMMIT")
                    && ++t.live.uncommittedRefusals >= 2 && !t.live.autoCommitted && t.live.generatedFile != null
                    && autoCommitAllowed(t.live.generatedFile, checkpoint.enabled())) {
                t.live.autoCommitted = true;
                Path repo = repoRootOf(Path.of(t.live.generatedFile));
                String cmd = autoCommitCommand(t.live.generatedFile, t.live.generatedMethod, repo);
                List<Action> commitActions = List.of(new Action("Bash", new HashMap<>(Map.of("command", cmd))));
                listener.onAnswerGate(false, List.of(issue + " — committing the generated test deterministically: " + cmd));
                listener.onBranchSelected("auto-commit", 1.0, 1, commitActions);
                Map<String, Object> commitRes = toolExecutor.executeActions(commitActions);
                listener.onToolResults(commitRes);
                commitRes.forEach((name, value) -> {
                    if (isSuccess(value)) {
                        t.successfulResults.append(name).append(" => ")
                                .append(summarizeResults(Map.of(name, value))).append("\n");
                        if (!AnswerGate.EDIT_TOOLS.contains(ToolExecutor.baseToolName(name)) || AnswerGate.isEditSuccess(ToolExecutor.baseToolName(name), value)) {
                            t.succeededTools.add(ToolExecutor.baseToolName(name));   // F313
                        }
                    }
                });
                observeGroundTruth(commitRes, t.committedThisSession, t.testOutcomeObserved);
                t.contextLog.append("\n[Iteration ").append(iteration).append("] The generated test was committed for you: ")
                        .append(summarizeResults(commitRes))
                        .append("\nAnswer now with the REAL commit hash from this output.");
                return Next.NEXT_ITERATION;
            }
            if (issue != null && issue.equals(t.lastAnswerGateIssue) && ++t.sameAnswerGateIssue >= 2) {
                // supervised rewrite drive, FM-014: one refusal fired 27 times in a row to the iteration limit. The same
                // refusal a third time in a row is not applied again: the ending goes on to the next gates and the seam
                listener.onAnswerGate(false, List.of("the same refusal a third time in a row — no longer applied this turn: " + issue));
                issue = null;
            } else if (issue != null && !issue.equals(t.lastAnswerGateIssue)) {
                t.lastAnswerGateIssue = issue;
                t.sameAnswerGateIssue = 0;
            }
            if (issue != null) {
                // Deterministic result-formatter: if the ignored result belongs to a
                // structured skill we can render, ANSWER from it directly (guarantees
                // the real values reach the user regardless of model synthesis quality)
                // instead of re-planning a model that just declined to report it.
                Optional<String> formatted = formatters.format(t.lastSuccessTool[0], t.lastSuccessResult[0]);
                if (formatted.isPresent()) {
                    listener.onAnswerGate(false, List.of(issue + " — answering via deterministic formatter"));
                    remember(userRequest, "Assistant: " + formatted.get());
                    listener.onFinalAnswer(formatted.get(), iteration - 1);
                    t.result = formatted.get();
                    return Next.STOP;
                }
                if (lastGateDeterministic || t.live.answerGates >= t.MAX_ANSWER_GATES) {
                    t.live.deterministicGates++;   // FM-014: a refusal that runs on the deterministic budget counts there
                } else {
                    t.live.answerGates++;
                }
                listener.onAnswerGate(false, List.of(issue));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(issue)
                        .append(issue.contains("your next plan is ONE Bash action")
                                ? ""   // the refusal names the next action; an answer now would invent its output
                                : " You ALREADY have successful tool results in CONTEXT SO FAR above; write a "
                                + "Shape B final_answer that REPORTS their substance to the user. Re-answer now.");
                return Next.NEXT_ITERATION;
            }
            listener.onAnswerGate(true, List.of());
        }
        return Next.GO_ON;
    }

    /** Guards an ending further: the web-read, discoverable-clarification, no-tool and fabrication guards. */
    private Next guardTheEnding(Turn t, String userRequest) {
        // WEB-READ GUARD: a current-fact question answered after only a WebSearch
        // SEARCH (cached snippets, often stale) and no READ - force a page read first.
        if (t.plan.isFinal() && t.live.webReadGates < t.MAX_WEBREAD_GATES) {
            String issue = AnswerGate.webReadRequiredIssue(userRequest, t.live.webSearchRan, t.live.webReadRan);
            if (issue != null) {
                t.live.webReadGates++;
                listener.onAnswerGate(false, List.of(issue));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(issue)
                        .append(" Propose a WebSearch action=read on the most authoritative result URL "
                                + "(Shape A), then answer STRICTLY from that page's text.");
                return Next.NEXT_ITERATION;
            }
        }
        // DISCOVERABLE-CLARIFY GUARD: asking the user for a file path / location / which class
        // is never necessary - LocateFile / CodeGraph locate answer it. Unattended drives lose the whole
        // stage to such a question, so reject it deterministically and make the brain locate instead.
        if (t.plan.isClarify() && t.live.clarifyGates < t.MAX_CLARIFY_GATES) {
            String issue = AnswerGate.discoverableClarifyIssue(t.plan.clarification(), userRequest);
            if (issue != null) {
                t.live.clarifyGates++;
                listener.onAnswerGate(false, List.of(issue));
                t.contextLog.append("\n[Answer gate REJECTED your clarifying question] ").append(issue)
                        .append(" Propose a Shape A plan now that LOCATES it and proceeds.");
                return Next.NEXT_ITERATION;
            }
        }
        // NO-TOOL HALLUCINATION GUARD: a final answer with NO successful tool this
        // turn, to a request that plainly needs one (URL/file/PR/inspect), is almost
        // certainly answered from memory - reject and make the brain fetch it first.
        if (t.plan.isFinal() && !t.live.haveSuccess && t.live.noToolGates < t.MAX_NOTOOL_GATES) {
            String issue = AnswerGate.noToolAnswerIssue(userRequest, t.contextLog.toString());
            if (issue != null) {
                t.live.noToolGates++;
                listener.onAnswerGate(false, List.of(issue));
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(issue)
                        .append(" Do NOT answer from memory — propose a tool action (Shape A) to obtain the "
                                + "real information, then answer from the result.");
                return Next.NEXT_ITERATION;
            }
        }
        // ANTI-CONFABULATION GUARD: a final answer that PRESENTS commit hashes or a build/test
        // verdict it never actually produced (the model's failure-mode when it stalls) is rejected
        // and re-planned toward doing the real thing or admitting incompleteness.
        if (t.plan.isFinal() && t.live.fabricationGates < t.MAX_FABRICATION_GATES) {
            String issue = AnswerGate.fabricationIssue(t.plan.finalAnswer(),
                    t.committedThisSession[0], t.testOutcomeObserved[0], t.successfulResults + "\n" + userRequest);
            if (issue == null) {
                // findings must trace to a tool result (identifiers, reach claims)
                issue = AnswerGate.fabricatedIdentifiersIssue(t.plan.finalAnswer(),
                        withReadFiles(t.successfulResults.toString(), t.live.executedSignatures), userRequest);   // F437
            }
            if (issue != null) {
                t.live.fabricationGates++;
                listener.onAnswerGate(false, List.of(issue));
                t.contextLog.append("\n[Answer gate REJECTED your ending — FABRICATION] ").append(issue)
                        .append(" Take the real action now, or write a final_answer that honestly says "
                                + "the task is INCOMPLETE and lists exactly what remains.");
                return Next.NEXT_ITERATION;
            }
        }
        return Next.GO_ON;
    }

    /**
     * Finishes a final answer: the honesty note, the answer refiner and the "answer" seam, then the answer is returned.
     */
    private Next finishTheAnswer(Turn t, String userRequest, int iteration) {
        if (t.plan.isFinal()) {
            // HONESTY BACKSTOP: the fabrication gate is bounded (it must not loop forever), so a
            // model that keeps claiming a commit/verdict it never produced gets its answer through on the
            // third try - but never UNMARKED. The deterministic note states what did not happen.
            String answer = withHonestyNote(t.plan.finalAnswer(), t.committedThisSession[0], t.testOutcomeObserved[0],
                    withReadFiles(t.successfulResults.toString(), t.live.executedSignatures), userRequest);
            // TERMINAL-ANSWER REFINER: when there is result data and the stronger 14B
            // is reachable, have it rewrite the final answer to be accurate and
            // grounded in the actual results (better synthesis than the small brain).
            if (remoteRefinerEnabled && helpers.anyAvailable() && t.live.haveSuccess) {
                String improved = refineAnswer(userRequest,
                        capTail(t.contextLog.toString(), helperContextChars(REMOTE_REFINE_CONTEXT_CAP)), answer);
                // the refiner's rewrite was cut off mid-sentence by its token limit
                // and replaced a complete answer; and it had silently flipped the ruling (TRUE → FALSE POSITIVE)
                // after the same-model gate approved the original. A cut-off rewrite is not used; a rewrite that
                // rules the opposite is a second reviewer's disagreement the planner must resolve, not a swap.
                if (improved != null && AnswerGate.endsMidSentence(improved)) {
                    listener.onPlanRefined(false, "remote refiner's rewrite was cut off mid-sentence — kept the original");
                    improved = null;
                }
                if (improved != null && CitationValidator.missingCount(improved, workbenchRoot())
                        > CitationValidator.missingCount(answer, workbenchRoot())) {   // F436
                    listener.onPlanRefined(false, "remote refiner's rewrite cites code objects that do not exist — kept the original");
                    improved = null;
                }
                String ruledBefore = AnswerGate.verdictOf(answer), ruledAfter = AnswerGate.verdictOf(improved);
                // the local rewrite flipped a ruling to FALSE POSITIVE on a claim the read lines
                // contradict ("the key INCLUDES the shelf") and the disagreement note handed that claim to the planner
                // as something to resolve. A flip is a hint like any other: it is read against the evidence first - by the
                // escalation model when one is up, else the helper chain - and a flip whose claims are disputed is dropped.
                if (improved != null && ruledBefore != null && ruledAfter != null && !ruledBefore.equals(ruledAfter)) {
                    String flipReview = reviewFlippedRuling(t.successfulResults.toString(), improved);
                    if (flipReview != null) {
                        listener.onPlanRefined(false, "the refiner's opposite ruling was itself disputed — dropped: " + excerpt(flipReview, 160));
                        improved = null;
                        ruledAfter = null;
                    }
                }
                if (improved != null && ruledBefore != null && ruledAfter != null && !ruledBefore.equals(ruledAfter)
                        && verdictCritics < MAX_VERDICT_CRITICS) {
                    verdictCritics++;
                    String disagreement = "A second reviewer (" + lastRefinerSource + ") read the same evidence and rules "
                            + ruledAfter + " where you ruled " + ruledBefore + ": " + capTail(improved, 1200)
                            + " — resolve the disagreement from the evidence: answer each of its points with a line, "
                            + "then re-decide.";
                    listener.onAnswerGate(false, List.of(disagreement));
                    t.contextLog.append("\n[Answer gate REJECTED your ending — a second reviewer disagrees] ").append(disagreement);
                    return Next.NEXT_ITERATION;
                }
                if (improved != null) {
                    // the refiner rewrote the answer and DROPPED the honesty note that had
                    // marked a fabricated commit hash - the note is applied to what is actually returned
                    answer = withHonestyNote(improved, t.committedThisSession[0], t.testOutcomeObserved[0],
                            withReadFiles(t.successfulResults.toString(), t.live.executedSignatures), userRequest);
                    listener.onPlanRefined(true, "remote refiner improved the final answer");
                }
            }
            // the annotator reads every final answer (section 7 of the annotation plan) and its document goes beside
            // the answer step; an inconsistent check refuses the ending only once refusals are turned on
            AnswerAnnotation.Result annotated = annotation.annotate(answer,
                    Arrays.asList(userRequest, t.successfulResults.toString()), Instant.now(), ZoneId.systemDefault());
            List<String> refusals = annotation.refusals(annotated);
            if (!refusals.isEmpty() && t.annotationRefusals < t.MAX_ANNOTATION_REFUSALS) {
                t.annotationRefusals++;
                listener.onAnswerGate(false, refusals);
                t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(String.join(" ", refusals))
                        .append(" Correct it and re-answer now.");
                return Next.NEXT_ITERATION;
            }
            if (checkpoint.enabled()) {   // seam "answer"
                mirror(t.live, iteration, t.contextLog, t.successfulResults, t.succeededTools, t.committedThisSession,
                        t.testOutcomeObserved, t.buildBroken, t.pendingExpertProgram, t.lastSuccessTool,
                        t.lastRefusalHint, t.lastSuccessResult, t.currentNode);
                Path stepAnswer = checkpoint.write(t.live, "answer");
                checkpoint.note(stepAnswer, "answer.txt", answer);
                annotation.record(stepAnswer, annotated);
                // the shadow critic scores the answer while the seam waits and records it after the decision; never read here
                OptionalReviewer.Pending shadowCall = stepAnswer == null ? null : shadow.start(OptionalReviewer.state(userRequest,
                        t.contextLog.toString(), ClaimSession.enabled() ? ClaimLedger.render(t.live.claims) : "",
                        answer));
                Checkpoint.Control c = checkpoint.decide(stepAnswer, "answer", iteration).control();
                shadow.record(stepAnswer, shadowCall, checkpoint.lastDecision());
                if (c == Checkpoint.Control.STOP) {
                    throw new Checkpoint.Stop(stepAnswer);
                }
                if (c == Checkpoint.Control.RETRY) {
                    t.result = retryFrom(t.planState, userRequest);
                    return Next.STOP;
                }
                if (c == Checkpoint.Control.REJECT) {   // the supervisor's note is an answer-gate rejection
                    String note = checkpoint.lastDecision() == null ? "the answer does not report what the tools returned"
                            : checkpoint.lastDecision().note();
                    listener.onAnswerGate(false, List.of(note));
                    t.contextLog.append("\n[Answer gate REJECTED your ending] ").append(note)
                            .append(" Re-answer now from the tool results in CONTEXT SO FAR.");
                    // three refusals of the verdict named the one read that would ground it
                    // (`sed -n … RateTable.java`); the planner repeated a locate each time and the synthesis
                    // re-asserted the unread claim. A refusal that names a concrete read runs it: the evidence enters the
                    // context before the next plan or synthesis.
                    List<Action> named = readsNamedIn(note, workbenchRoot());   // every read it names
                    if (!named.isEmpty()) {
                        runNamedRead(named, note, iteration, t.live, t.contextLog, t.successfulResults,
                                t.succeededTools);
                    }
                    return Next.NEXT_ITERATION;
                }
            }
            remember(userRequest, "Assistant: " + answer);
            listener.onFinalAnswer(answer, iteration - 1);
            t.result = answer;
            return Next.STOP;
        }
        return Next.GO_ON;
    }

    /**
     * Handles a clarifying question: for an actionable task a remote-authored plan runs instead, else the question is
     * returned.
     */
    private Next handleTheClarification(Turn t, String userRequest, int iteration) {
        if (t.plan.isClarify()) {
            // If the task is plainly ACTIONABLE (needs a tool, e.g. "write a program
            // and run it" / a current-fact fetch) the brain should NOT be asking the
            // user - let the remote model AUTHOR a concrete plan with sensible defaults
            // instead of stalling on a clarification. Bounded.
            if (AnswerGate.noToolAnswerIssue(userRequest, t.contextLog.toString()) != null
                    && helpers.anyAvailable() && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                String authored = remoteAuthorPlan(userRequest, t.contextLog
                        + "\n[The local brain tried to ASK the user: " + t.plan.clarification()
                        + " — do NOT ask; pick concrete sensible defaults and act.]");
                ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                if (ap != null && !ap.isFinal() && !ap.isClarify()
                        && ap.branches() != null && !ap.branches().isEmpty()) {
                    t.live.authoredPlans++;
                    t.plan = ap;
                    t.program = CodeFence.first(authored);
                    listener.onPlanRefined(true, "remote AUTHORED a plan instead of clarifying");
                    t.currentNode.setBranches(t.plan.branches());
                    listener.onPlanProposed(t.plan.branches());
                    Branch sel = branchingEngine.selectBest(t.plan.branches());
                    t.live.triedBranches.add(sel.name());
                    listener.onBranchSelected(sel.name(), sel.confidence(),
                            t.plan.branches().size(), sel.actions());
                    Map<String, Object> execRes =
                            toolExecutor.executeInstructions(injectProgramCode(sel.actions(), t.program));
                    listener.onToolResults(execRes);
                    execRes.forEach((name, value) -> {
                        if (isSuccess(value)) {
                            t.successfulResults.append(name).append(" => ")
                                    .append(summarizeResults(Map.of(name, value))).append("\n");
                            t.lastSuccessTool[0] = ToolExecutor.baseToolName(name);
                            if (!AnswerGate.EDIT_TOOLS.contains(t.lastSuccessTool[0]) || AnswerGate.isEditSuccess(t.lastSuccessTool[0], value)) {
                        t.succeededTools.add(t.lastSuccessTool[0]);   // F313
                    }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) value;
                            t.lastSuccessResult[0] = m;
                        }
                    });
                    if (t.successfulResults.length() > 0) {
                        t.live.haveSuccess = true;
                    }
                    t.contextLog.append("\n[Iteration ").append(iteration)
                            .append("] Executed remote-authored branch '").append(sel.name())
                            .append("'. Tool results: ").append(summarizeResults(execRes))
                            .append("\nIf the goal is now achieved, respond with Shape B (final_answer).");
                    t.currentNode = new PlanNode(sel.name(), userRequest, new HashMap<>(execRes));
                    return Next.NEXT_ITERATION;
                }
            }
            remember(userRequest, "Assistant (needs clarification): " + t.plan.clarification());
            listener.onClarify(t.plan.clarification());
            t.result = "I need a clarification: " + t.plan.clarification();
            return Next.STOP;
        }
        return Next.GO_ON;
    }

    /** Selects the best branch and applies the repeat-action guard. */
    private Next selectTheBranch(Turn t, String userRequest) {
        // STEP D: decide
        t.currentNode.setBranches(t.plan.branches());
        listener.onPlanProposed(t.plan.branches());
        t.selected = branchingEngine.selectBest(t.plan.branches());
        currentPlanSummary = summarizeBranch(t.selected);   // F374

        // REPEAT-ACTION GUARD: if the brain keeps choosing the SAME action that isn't
        // making progress (e.g. re-issuing an identical WebSearch that returns nothing),
        // break the loop instead of spinning to the cap - escalate to a remote-authored
        // plan if possible, else push a "do something different" note and re-plan.
        String sig = actionSignature(t.selected);
        if (sig.equals(t.live.lastActionSig)) {
            t.live.repeatCount++;
        } else {
            t.live.repeatCount = 0;
            t.live.lastActionSig = sig;
        }
        if (t.live.repeatCount >= t.MAX_REPEAT) {
            t.live.repeatCount = 0;
            listener.onPlanReview(false, List.of("loop guard: repeated action " + sig));
            t.contextLog.append("\n[LOOP GUARD] You proposed the SAME action (").append(sig)
                    .append(") repeatedly with no progress. Do something DIFFERENT now: if a search "
                    + "returned nothing useful, run action=read on a specific result URL, or try a "
                    + "different query, or a different tool, or answer from CONTEXT SO FAR. Do NOT "
                    + "repeat that action again.");
            boolean replaced = false;
            if (helpers.anyAvailable() && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                String authored = remoteAuthorPlan(userRequest, t.contextLog.toString());
                ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                if (ap != null && !ap.isFinal() && !ap.isClarify()
                        && ap.branches() != null && !ap.branches().isEmpty()) {
                    t.live.authoredPlans++;
                    t.plan = ap;
                    t.program = CodeFence.first(authored);
                    t.currentNode.setBranches(t.plan.branches());
                    t.selected = branchingEngine.selectBest(t.plan.branches());
                    t.live.lastActionSig = actionSignature(t.selected);
                    listener.onPlanRefined(true, "loop guard: remote authored a different plan");
                    replaced = true;
                }
            }
            if (!replaced) {
                // No remote plan could break the loop (chain down/disabled). If a SUCCESSFUL result
                // already exists, stop re-running it and synthesize the answer from context now
                // (local, single-model) instead of spinning to the iteration cap - the small driver
                // otherwise keeps re-proposing the same already-successful action and never converges.
                if (t.live.haveSuccess) {
                    String synthesized = synthesizeFromResults(userRequest, t.contextLog.toString());
                    if (synthesized != null && !synthesized.isBlank()) {
                        // the synthesized answer must pass the same deterministic gates as a planned one;
                        // when it does not, the gate's instruction goes into context and the loop continues
                        String gate = AnswerGate.deterministicAnswerIssue(currentRequest,
                                t.successfulResults.toString(), synthesized, t.succeededTools);
                        if (gate != null) {
                            t.contextLog.append("\n[Loop guard: the work is NOT done — do it] ").append(gate);
                            listener.onAnswerGate(false, List.of(gate));
                        } else {
                            // this path RETURNED the synthesized verdict at once - past the
                            // second reviewer, the citation and quotation gates, the disputed-claims check and the answer
                            // seam, where the supervisor had refused the same claims twice. Like F347's synthesis, it now
                            // enters the next iteration as a final plan and takes the same path as any other answer.
                            pendingAuthoredPlan = TurnSnapshot.toJson(Map.of("final_answer", synthesized.strip()));
                            listener.onPlanRefined(true, "loop guard: the answer was synthesized from the results — it goes"
                                    + " through the answer gates and the answer seam next iteration");
                        }
                    }
                }
                return Next.NEXT_ITERATION;   // re-plan with the guard note in context
            }
        }
        return Next.GO_ON;
    }

    /**
     * Refuses a selection with an unknown tool, a forbidden step, a compose the composer would refuse, or a commit
     * while the build is broken.
     */
    private Next refuseForbiddenSteps(Turn t, String userRequest) {
        // a multi-part request planned as SEVERAL branches loses parts 2..n (branches are alternatives).
        // Sibling branches made only of READ-ONLY analysis actions are cheap and side-effect free - fold them
        // into the selected branch so the whole checklist runs this iteration.
        t.selected = mergeReadOnlySiblings(t.plan.branches(), t.selected);
        // the planner wrote `git push` into two plans running despite the hard rule
        // in the request; the LLM reviewer caught both, but a reviewer is not a guarantee, and a drive harness
        // aborts the whole run when such a step is SELECTED. In read-only mode a plan carrying a destructive or
        // outward-facing git/gh step is refused deterministically, before the selection is announced, naming
        // the step so the planner re-plans without it (local add/commit stay allowed).
        String forbidden = BashTool.readOnly() ? forbiddenStepIssue(t.selected.actions()) : null;
        // the planner named tools that do not exist (ReadFile, exec,
        // print_tree) and, once the forbidden-step budget was spent, such a plan ran to "Tool not found". An
        // unregistered tool can never execute, so it is refused without a budget; the third one asks the remote
        // author for a plan, the fifth halts the turn - that many invented tools is the planner's own failing.
        String unknownTool = unknownToolIssue(t.selected.actions(), toolRegistry.getAllTools().keySet());
        if (unknownTool != null) {
            t.live.unknownTools++;
            listener.onAnswerGate(false, List.of(unknownTool));
            t.contextLog.append("\n[Plan REJECTED — unknown tool] ").append(unknownTool);
            if (!t.steppingMode && t.live.unknownTools >= 5) {
                String halt = "Agent halted: the planner named " + t.live.unknownTools + " tools that do not exist in this agent"
                        + (t.stepPlan == null ? "." : " — resume from " + t.stepPlan + ".");
                listener.onHalted(halt);
                t.result = halt;
                return Next.STOP;
            }
            if (t.live.unknownTools >= 3 && (helpers.anyAvailable() || anyEscalation())
                    && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                String authored = remoteAuthorPlan(userRequest, t.contextLog
                        + "\n[Reviewer rejected the local plan; fix exactly this] " + unknownTool);
                ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                if (ap != null && !ap.isFinal() && !ap.isClarify() && ap.branches() != null && !ap.branches().isEmpty()) {
                    t.live.authoredPlans++;
                    pendingAuthoredPlan = authored;   // run the authored plan next iteration
                    listener.onPlanRefined(true, "remote AUTHORED a plan after repeated unknown tools");
                }
            }
            return Next.NEXT_ITERATION;
        }
        if (forbidden == null) {
            // a compose the composer would refuse (placeholder, numeric id, undeclared local, no suite) is refused
            // at plan time with the same words, so the iteration is not spent on it
            for (Action act : t.selected.actions()) {
                if ("SpecTestComposer".equals(act.toolName())) {
                    String pre = SpecTestComposerTool.preflight(act.parameters());
                    if (pre != null) {
                        forbidden = pre;
                        break;
                    }
                }
            }
        }
        if (forbidden == null) {
            // the plan ran `git commit -a` right after its own build had failed to
            // compile, and a broken handler landed on the branch. No commit while the module does not compile.
            forbidden = commitWhileBrokenIssue(t.selected.actions(), t.buildBroken[0]);
        }
        if (forbidden != null && t.live.forbiddenGates < t.MAX_FORBIDDEN_GATES) {
            t.live.forbiddenGates++;
            listener.onAnswerGate(false, List.of(forbidden));
            t.contextLog.append("\n[Plan REJECTED — forbidden step] ").append(forbidden);
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /**
     * Announces the selection and injects step snippets, then refuses invented line addresses and regenerations,
     * committing the generated test itself when the commit is what is missing.
     */
    private Next refuseInventedSteps(Turn t, int iteration) {
        t.live.triedBranches.add(t.selected.name());
        listener.onBranchSelected(t.selected.name(), t.selected.confidence(),
                t.plan.branches().size(), t.selected.actions());
        // PER-STEP SNIPPET RETRIEVAL: for each code-writing action in the chosen
        // branch, pull the closest snippets for that step's intent into the context
        // so the next authoring round can compose from known-good parts.
        if (memoryService != null) {
            injectStepReferences(t.selected, t.contextLog);
        }

        // REPEAT GUARD: a plan whose every action already ran this turn - verbatim - re-spends
        // minutes re-learning what CONTEXT already holds (successes) or re-failing identically (failures).
        // an invented File.java:LINE address (no tool has reported it) is refused before it runs
        String invented = null;
        for (Action a : t.selected.actions()) {
            invented = AnswerGate.inventedLineAddressIssue(String.valueOf(a.parameters()), t.contextLog.toString());
            if (invented != null && groundedByARead(String.valueOf(a.parameters()), t.live.executedSignatures.keySet())) {
                invented = null;   // the line lies inside a range this turn read - sed prints no numbers
            }
            if (invented != null) {
                break;
            }
            // generating AGAIN a method the context shows as already generated - the commit is what is missing
            if ("TreeCodegen".equals(a.toolName())) {
                invented = AnswerGate.regenerationIssue("TreeCodegen " + a.parameters(), t.contextLog.toString());
                if (invented != null) {
                    t.live.regenerationRefusals++;
                    break;
                }
            }
        }
        // after the composer wrote the suite the planner located the file four plans running
        // instead of committing - a plan made only of lookups for the written file is the "regenerate" of a composed
        // test: refused with the commit named, and on the second refusal the agent commits it itself
        if (invented == null && t.live.generatedFile != null
                && locatesOnly(t.selected.actions(), t.live.generatedFile)) {
            invented = "The file " + t.live.generatedFile + " was already written and reported by the tool that wrote it — "
                    + "locating it again proves nothing. Commit it (git add <path> && git commit -s -m '<message>') and then answer.";
            t.live.regenerationRefusals++;
        }
        // the planner re-planned the generation three plans running after being told each
        // time that only the commit was missing - the answer-gate auto-commit never got its turn because no
        // answer was attempted. On the second such plan the agent commits the generated test itself.
        if (invented != null && t.live.regenerationRefusals >= 2 && !t.live.autoCommitted && !t.committedThisSession[0]
                && t.live.generatedFile != null
                && autoCommitAllowed(t.live.generatedFile, checkpoint.enabled())
                && currentRequest != null && currentRequest.toLowerCase(Locale.ROOT).contains("commit")) {
            t.live.autoCommitted = true;
            Path repo = repoRootOf(Path.of(t.live.generatedFile));
            String cmd = autoCommitCommand(t.live.generatedFile, t.live.generatedMethod, repo);
            List<Action> commitActions = List.of(new Action("Bash", new HashMap<>(Map.of("command", cmd))));
            listener.onAnswerGate(false, List.of(invented + " — committing the generated test deterministically: " + cmd));
            listener.onBranchSelected("auto-commit", 1.0, 1, commitActions);
            Map<String, Object> commitRes = toolExecutor.executeActions(commitActions);
            listener.onToolResults(commitRes);
            commitRes.forEach((name, value) -> {
                if (isSuccess(value)) {
                    t.successfulResults.append(name).append(" => ")
                            .append(summarizeResults(Map.of(name, value))).append("\n");
                    if (!AnswerGate.EDIT_TOOLS.contains(ToolExecutor.baseToolName(name)) || AnswerGate.isEditSuccess(ToolExecutor.baseToolName(name), value)) {
                            t.succeededTools.add(ToolExecutor.baseToolName(name));   // F313
                        }
                }
            });
            observeGroundTruth(commitRes, t.committedThisSession, t.testOutcomeObserved);
            t.contextLog.append("\n[Iteration ").append(iteration).append("] The generated test was committed for you: ")
                    .append(summarizeResults(commitRes))
                    .append("\nDo NOT generate again. Answer now (Shape B) with the REAL commit hash from this output.");
            return Next.NEXT_ITERATION;
        }
        if (invented != null && t.live.repeatGates < t.MAX_REPEAT_GATES) {
            t.live.repeatGates++;
            listener.onAnswerGate(false, List.of(invented));
            t.contextLog.append("\n[Plan REJECTED — invented address] ").append(invented);
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /**
     * Trims the selection (duplicate and already-succeeded steps, a write after its reads) and completes it with the
     * request's task and commands; an edit that reads nothing first is refused.
     */
    private Next trimThePlan(Turn t, String userRequest) {
        // the stronger author's eight-step plan - locate, read, callers, reach, tests,
        // locate the tests, grep, record - was refused WHOLE because its LAST step named a test class no result had
        // shown; seven sound steps and the author's call were lost. The guards run on the plan as before (gatePlan);
        // when a later step fails them, the plan is cut to the clean steps before it and those run.
        // the author's plan re-ran `LocateFile InventoryLedger.java` and the
        // `CodeGraph locate` already made (both Success, both in the context) in front of the one new step, the read;
        // the verbatim guard refuses only a plan made ENTIRELY of repeats, so both repeats ran again. A succeeded call
        // is dropped from a plan that also holds new steps; a plan of nothing but repeats is refused as before.
        // the author's plan held `callers CustomShippingCharging#chargeShippingForAbortedOrder`
        // TWICE - the same call in the same plan; the repeat guards compare against calls already run, not against each other
        List<Action> distinct = withoutIntraPlanDuplicates(t.selected.actions());
        if (distinct.size() < t.selected.actions().size()) {
            int dropped = t.selected.actions().size() - distinct.size();
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), distinct);
            listener.onPlanRefined(true, dropped + " duplicate step(s) within the plan dropped — the same call listed twice runs once");
        }
        List<Action> fresh = withoutSucceededRepeats(t.selected.actions(), t.live.executedSignatures);
        if (fresh.size() < t.selected.actions().size() && !fresh.isEmpty()) {
            int dropped = t.selected.actions().size() - fresh.size();
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), fresh);
            listener.onPlanRefined(true, dropped + " step(s) already executed this turn with Success dropped from the plan"
                    + " — the " + fresh.size() + " new one(s) run");
            t.contextLog.append("\n[Plan PRUNED] ").append(dropped).append(" step(s) that had already succeeded this turn were dropped;")
                    .append(" their results are above.");
        }
        // a plan that reads files and then writes one writes before the reads came back: it runs its reads, and the
        // write is planned again with their output in front of the planner (supervised rewrite drive, FM-012)
        int write = writeAfterRead(t.selected.actions());
        if (write > 0) {
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(),
                    new ArrayList<>(t.selected.actions().subList(0, write)));
            listener.onPlanRefined(true, "plan cut to its first " + write + " step(s) — step " + (write + 1)
                    + " writes a file in the same plan as the reads it is based on");
            t.contextLog.append("\n[Plan CUT] step ").append(write + 1).append(" was dropped — it wrote a file in the same plan")
                    .append(" as the reads before it, so it could not use them. The reads ran; write the file now from their output.");
        }
        // the request named the files to read first; the plan read nothing and
        // wrote code against an invented API (a two-argument constructor, cursor.advance, instance calls of static
        // helpers). Before anything has run in the turn, a plan that edits without reading is refused, twice at most.
        String readFirst = readFirstIssue(userRequest, t.selected.actions(), !t.succeededTools.isEmpty());
        if (readFirst != null && occurrences(t.contextLog.toString(), READ_FIRST_MARK) < 2) {
            listener.onPlanReview(false, List.of(readFirst));
            t.contextLog.append("\n").append(READ_FIRST_MARK).append(' ').append(readFirst);
            return Next.NEXT_ITERATION;
        }
        List<Action> withTask = withDefaultTask(t.selected.actions(), userRequest);   // rewrite drive, task 040a2
        if (withTask != t.selected.actions()) {
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), withTask);
            listener.onPlanRefined(true, "a TreeCodegen call without a task sentence was given the request's first line");
        }
        List<Action> withCommand = withRequestCommand(t.selected.actions(), userRequest);   // tasks 044f and 044g2
        if (withCommand != t.selected.actions()) {
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), withCommand);
            listener.onPlanRefined(true, "the request's command after TreeCodegen was added to the plan, verbatim");
        }
        List<Action> verbatim = withRequestCommandsVerbatim(t.selected.actions(), userRequest);   // tasks 053b and 053j
        if (verbatim != t.selected.actions()) {
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), verbatim);
            listener.onPlanRefined(true, "a near copy of a command the request gives was replaced by that command, verbatim");
        }
        // the reads located the methods; the edits rewrote their bodies from memory
        String turnContext = t.contextLog.substring(Math.max(0, t.contextLog.lastIndexOf("Current request:")));
        String unreadRewrite = unreadRewriteIssue(t.selected.actions(), turnContext, workbenchRoot());
        if (unreadRewrite != null && occurrences(t.contextLog.toString(), UNREAD_REWRITE_MARK) < 2) {
            listener.onPlanReview(false, List.of(unreadRewrite));
            t.contextLog.append("\n").append(UNREAD_REWRITE_MARK).append(' ').append(unreadRewrite);
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /**
     * Runs the plan guards on the selection, cuts it to its clean steps, and refuses a repeat, escalating to the
     * author, a synthesized answer or a halt.
     */
    private Next gateTheRepeats(Turn t, String userRequest) {
        PlanGate gate = gatePlan(t.selected.actions(), t.live, t.contextLog.toString(), userRequest);
        if (gate.resolvedPlaceholder()) {
            t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(),
                    gate.actions());
            listener.onPlanRefined(true, "placeholder in a shell command resolved to the one file this turn located");
        }
        String repeat = gate.issue();
        boolean coveredRead = gate.coveredRead();
        if (repeat != null && t.selected.actions().size() > 1) {
            final TurnSnapshot gateState = t.live;
            final String gateContext = t.contextLog.toString();
            List<Action> prefix = cleanPrefix(t.selected.actions(),
                    acts -> gatePlan(acts, gateState, gateContext, userRequest).issue());
            if (!prefix.isEmpty()) {
                String why = repeat;
                t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), prefix);
                listener.onPlanRefined(true, "plan cut to its first " + prefix.size() + " step(s) — step "
                        + (prefix.size() + 1) + " did not pass the plan guards: " + why);
                t.contextLog.append("\n[Plan CUT] step ").append(prefix.size() + 1).append(" was dropped — ").append(why)
                        .append(" — the steps before it ran.");
                repeat = null;
                coveredRead = false;
            }
        }
        if (repeat != null) {
            t.live.repeatGates++;
            t.live.lastRefusalWasCoveredRead = coveredRead;   // F354
            listener.onAnswerGate(false, List.of(repeat));
            t.contextLog.append("\n[Plan REJECTED — repeat] ").append(repeat);
            // past the budget of two the third covered lookup RAN, and so did the
            // author's corrective plan - itself a fifth lookup of the same file. A repeat never runs: past the
            // budget the stronger author is asked (its plan takes the same path next iteration), and a planner
            // that keeps repeating with no author left halts.
            // after a verbatim repeat of a SUCCEEDED call was refused, the local planner
            // repeated it again EVERY time (J: three times, L: twice) - the second local re-plan never paid off and cost
            // ninety seconds each. A verbatim repeat of succeeded calls goes to the author at the first refusal.
            boolean atOnce = !t.refined && !coveredRead
                    && allSucceededRepeats(t.selected.actions(), t.live.executedSignatures)
                    && Boolean.parseBoolean(System.getProperty("hermit.repeat.escalateAtOnce", "true"));   // a LOCAL plan only
            if (t.live.repeatGates > t.MAX_REPEAT_GATES || atOnce) {
                boolean authoredOne = false;
                if ((helpers.anyAvailable() || anyEscalation()) && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                    String authored = remoteAuthorPlan(userRequest, t.contextLog
                            + "\n[Reviewer rejected the local plan; fix exactly this] " + repeat);
                    ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                    if (ap != null && (ap.isFinal() || (ap.branches() != null && !ap.branches().isEmpty()))) {
                        t.live.authoredPlans++;
                        pendingAuthoredPlan = authored;
                        authoredOne = true;
                        listener.onPlanRefined(true, "remote AUTHORED a plan after repeated covered lookups"
                                + " — it goes through the plan guards next iteration");
                    }
                }
                if (!authoredOne && t.live.repeatGates >= t.MAX_REPEAT_GATES + 3) {
                    // with every lookup covered and no author left the turn HALTED,
                    // although the evidence for the answer had sat in the context for six iterations. A planner
                    // that will not answer is asked to do only that: the answer is synthesized from the results
                    // and enters the next iteration as a final plan - through every answer gate, the second
                    // reviewer and the answer seam. The turn halts only when even that yields nothing.
                    // the synthesis was bounded by the repeat COUNT (<= 7), and the
                    // author's own refused plans had driven the count to 9 before the author ran out - the window was
                    // missed and the turn halted with everything read. The bound is how many times the answer has
                    // been synthesized this turn, not how many lookups were refused.
                    String synthesized = t.live.haveSuccess && t.live.synthesizedAnswers < 2
                            ? synthesizeFromResults(userRequest, t.contextLog.toString()) : null;
                    if (synthesized != null && !synthesized.isBlank() && !ChatClient.LLM_CALL_FAILED.equals(synthesized)) {
                        t.live.synthesizedAnswers++;
                        pendingAuthoredPlan = TurnSnapshot.toJson(Map.of("final_answer", synthesized.strip()));
                        listener.onPlanRefined(true, "covered lookups exhausted — the answer was synthesized from the"
                                + " results; it goes through the answer gates next iteration");
                        return Next.NEXT_ITERATION;
                    }
                    if (t.steppingMode) {   // the synthesis still answers; only the halt is lifted
                        return Next.NEXT_ITERATION;
                    }
                    String halt = "Agent halted: the planner repeated lookups already made this turn " + t.live.repeatGates
                            + " times and no author is left to ask" + (t.stepPlan == null ? "." : " — resume from " + t.stepPlan + ".");
                    listener.onHalted(halt);
                    t.result = halt;
                    return Next.STOP;
                }
            }
            return Next.NEXT_ITERATION;
        }
        return Next.GO_ON;
    }

    /** Vetoes repeats the activity log knows: completed work when enforced, rejected proposals always. */
    private Next vetoActivityRepeats(Turn t) {
        if (activity != null) {
            String activityRepeat = activity.repeatIssue(t.selected.actions());
            if (activityRepeat != null) {
                boolean enforce = Boolean.getBoolean("hermit.activity.enforce");
                listener.onPlanRefined(false, (enforce ? "Activity veto: " : "Activity shadow: ") + activityRepeat);
                if (enforce) {
                    listener.onPlanReview(false, List.of(activityRepeat));
                    t.contextLog.append("\n[Activity repeat rejected] ").append(activityRepeat);
                    return Next.NEXT_ITERATION;
                }
            }
            // a rejected proposal never ran, so vetoing its exact repeat carries none of the completed-work
            // risk the shadow/enforce split above exists for - always enforced, not gated by hermit.activity.enforce.
            String rejectedRepeat = activity.rejectedRepeatIssue(t.selected.actions());
            if (rejectedRepeat == null) {
                rejectedRepeat = activity.semanticRepeatIssue(t.selected.actions(), jev);
            }
            if (rejectedRepeat != null) {
                listener.onPlanRefined(false, "Activity veto (rejected repeat): " + rejectedRepeat);
                listener.onPlanReview(false, List.of(rejectedRepeat));
                t.contextLog.append("\n[Activity rejected-repeat rejected] ").append(rejectedRepeat);
                return Next.NEXT_ITERATION;
            }
        }
        return Next.GO_ON;
    }

    /** The "selected" seam: the supervisor approves, stops, retries, cuts or refuses the selection before it runs. */
    private Next reviewTheSelection(Turn t, String userRequest, int iteration) {
        if (checkpoint.enabled()) {   // seam "selected" - the decision is on disk BEFORE it runs, AFTER the deterministic plan guards
            mirror(t.live, iteration, t.contextLog, t.successfulResults, t.succeededTools, t.committedThisSession,
                    t.testOutcomeObserved, t.buildBroken, t.pendingExpertProgram, t.lastSuccessTool, t.lastRefusalHint,
                    t.lastSuccessResult, t.currentNode);
            Path stepSel = checkpoint.write(t.live, "selected");
            checkpoint.note(stepSel, "plan.json", TurnSnapshot.toJson(TurnSnapshot.jsonSafe(planAsData(t.plan))));
            checkpoint.note(stepSel, "selected.json", TurnSnapshot.toJson(TurnSnapshot.jsonSafe(branchAsData(t.selected))));
            // a WriteProgram's program is the fenced block after the plan's JSON and
            // joins the action only when it runs, so the seam showed a WriteProgram with no program at all. The
            // program that will run is recorded with the step.
            String seamProgram = programAtSeam(t.selected.actions(), t.program);
            if (seamProgram != null) {
                checkpoint.note(stepSel, "program.txt", seamProgram);
            }
            checkpoint.note(stepSel, "timing.txt", "planning took " + (System.currentTimeMillis() - t.iterationStartedAt) + " ms");
            Checkpoint.Decision decision = checkpoint.decide(stepSel, "selected", iteration);
            if (decision.control() == Checkpoint.Control.STOP) {
                throw new Checkpoint.Stop(stepSel);
            }
            if (decision.control() == Checkpoint.Control.RETRY) {
                t.result = retryFrom(t.planState, userRequest);
                return Next.STOP;
            }
            int refusedStep = decision.control() == Checkpoint.Control.REJECT
                    ? refusedStepIndex(decision.note(), t.selected.actions().size()) : -1;
            List<Action> namedReads = decision.control() == Checkpoint.Control.REJECT && refusedStep < 2
                    ? readsNamedIn(decision.note(), workbenchRoot()) : List.of();   // F435
            if (!namedReads.isEmpty()) {
                runNamedRead(namedReads, decision.note(), iteration, t.live, t.contextLog, t.successfulResults,
                        t.succeededTools);
                listener.onPlanReview(false, List.of(decision.note()));
                t.live.planReviews++;
                return Next.NEXT_ITERATION;
            }
            if (refusedStep >= 2) {
                // every whole-plan refusal ("steps 4-6 are guesses…") also dropped the
                // sound steps in front of the refused one - the calculator's read was planned four times and never ran,
                // and three synthesized answers were refused for quoting code nobody had read. A refusal that names a
                // step cuts the plan there: the steps before it, the planner's own, run; the note stays in the context.
                List<Action> kept = List.copyOf(t.selected.actions().subList(0, refusedStep - 1));
                t.selected = new Branch(t.selected.name(), t.selected.description(), t.selected.confidence(), kept);
                listener.onPlanRefined(true, "the refusal names step " + refusedStep + " — the plan is cut to its first "
                        + kept.size() + " step(s), which run: " + decision.note());
                t.contextLog.append("\n[Plan CUT at step ").append(refusedStep).append("] ").append(decision.note())
                        .append(" — the steps before it ran.");
                checkpoint.note(stepSel, "cut.txt", "cut at step " + refusedStep + ": " + decision.note());
            } else if (decision.control() == Checkpoint.Control.REJECT) {
                // the note enters the loop exactly as a critic's issue would: same wording, same budget, same
                // escalation to the remote author once the local planner has been told twice
                List<String> issues = List.of(decision.note());
                listener.onPlanReview(false, issues);
                t.live.planReviews++;
                if (t.live.planReviews >= t.MAX_PLAN_REVIEWS && (helpers.anyAvailable() || anyEscalation())
                        && t.live.authoredPlans < t.MAX_AUTHORED_PLANS) {
                    String authored = remoteAuthorPlan(userRequest, t.contextLog
                            + "\n[Reviewer rejected the local plan; fix exactly this] " + decision.note());
                    // The note used to reach only this one author call, and the failure ledger closes at the next
                    // successful plan; when the author planned reads, the write after them was planned without the
                    // note (rewrite drive, parser slice 030b). It stays in the context for the rest of the turn.
                    t.contextLog.append("\n[Supervisor refused the last plan; this still applies to the next plans] ")
                            .append(decision.note());
                    ParsedPlan ap = authored == null ? null : planParser.parse(authored);
                    if (ap != null && ap.isFinal() && ap.finalAnswer() != null && !ap.finalAnswer().isBlank()) {
                        // after the local planner's plan was rejected the stronger author
                        // may conclude the analysis itself - its answer goes through the same gates as any other
                        t.live.authoredPlans++;
                        listener.onPlanRefined(true, "remote AUTHORED the answer after the local plan was rejected");
                        pendingAuthoredPlan = authored;
                        return Next.NEXT_ITERATION;
                    }
                    if (ap != null && !ap.isFinal() && !ap.isClarify()
                            && ap.branches() != null && !ap.branches().isEmpty()) {
                        // from here the authored plan used to run STRAIGHT into
                        // STEP E - past the invented-address, repeat, covered-read and unknown-tool guards and past
                        // the "selected" seam - and re-read lines 300-380 that the turn had already read. It is a
                        // plan like any other: it enters the next iteration as the planner's output and
                        // takes the same path through the guards and the seam.
                        t.live.authoredPlans++;
                        pendingAuthoredPlan = authored;
                        listener.onPlanRefined(true, "remote AUTHORED a corrective plan after local review failed"
                                + " — it goes through the plan guards next iteration");
                        return Next.NEXT_ITERATION;
                    } else {
                        t.contextLog.append("\n[Plan review REJECTED your last plan] Fix ALL of these, "
                                + "using CONTEXT SO FAR (do not ask what is already answered, do not miss a point, "
                                + "do not invent one): ").append(decision.note()).append("\nRe-plan now.");
                        return Next.NEXT_ITERATION;
                    }
                } else {
                    t.contextLog.append("\n[Plan review REJECTED your last plan] Fix ALL of these, "
                            + "using CONTEXT SO FAR (do not ask what is already answered, do not miss a point, "
                            + "do not invent one): ").append(decision.note()).append("\nRe-plan now.");
                    return Next.NEXT_ITERATION;
                }
            }
        }
        return Next.GO_ON;
    }

    /**
     * Runs the selected branch and records what it did: the build after edits, reverts, the generated file and the
     * turn's evidence.
     */
    private Map<String, Object> executeTheBranch(Turn t, int iteration) {
        // STEP E: execute the selected branch's actions, injecting any out-of-band
        // program code into the code-bearing action (WriteProgram / SyntaxCheck).
        Map<String, Object> executionResults = handoffOversizedReads(t.selected.actions(),
                toolExecutor.executeInstructions(injectProgramCode(t.selected.actions(), t.program)));
        listener.onToolResults(executionResults);
        recordSignatures(t.selected.actions(), executionResults, t.live.executedSignatures);
        t.live.lastRefusalWasCoveredRead = false;   // a plan ran - the next read is judged on its own
        if (executionResults.values().stream().anyMatch(ResultSummaries::isSuccess)) {
            succeeded(t.live);   // F374 ledger reset; F407 synthesis budget restored; F418 disputed claims reset
            disputedClaims.clear();   // the mirrored field too
        }
        // the escalated plan applied the whole fix and stopped; the local planner then
        // spent its turns re-inventing a cache instead of building. After successful edits with no build step, the
        // module's test task named by the request runs deterministically, so the next plan sees GREEN or the error.
        String buildCmd = buildAfterEditsCommand(t.selected.actions(), executionResults, currentRequest);
        if (buildCmd != null) {
            List<Action> buildActions = List.of(new Action("Bash", new HashMap<>(Map.of("command", buildCmd))));
            listener.onBranchSelected("build-after-edits", 1.0, 1, buildActions);
            Map<String, Object> buildRes = toolExecutor.executeActions(buildActions);
            listener.onToolResults(buildRes);
            buildRes.forEach((name, value) -> {
                String key = "Bash#build";
                executionResults.put(key, value);
                if (isSuccess(value)) {
                    t.successfulResults.append(key).append(" => ").append(summarizeResults(Map.of(key, value))).append("\n");
                    t.succeededTools.add("Bash");
                }
            });
            t.contextLog.append("\n[Iteration ").append(iteration).append("] The module's test ran after your edits: ")
                    .append(summarizeResults(buildRes));
        }
        observeBuildState(executionResults, t.buildBroken);
        // an edit this plan made to a file that the plan's own build then failed to compile is reverted
        String reverted = revertEditsThatBrokeTheBuild(t.selected.actions(), executionResults);
        if (reverted != null) {
            t.buildBroken[0] = false;   // the offending edit is gone; the next build decides
        }
        if (reverted != null) {
            listener.onAnswerGate(false, List.of(reverted));
            t.contextLog.append("\n[Deterministic revert] ").append(reverted);
        }
        for (Action a : t.selected.actions()) {
            if ("TreeCodegen".equals(a.toolName()) && a.parameters() != null && a.parameters().get("file") != null) {
                boolean generated = executionResults.entrySet().stream()
                        .anyMatch(e -> "TreeCodegen".equals(ToolExecutor.baseToolName(e.getKey())) && isSuccess(e.getValue()));
                if (generated) {
                    t.live.generatedFile = String.valueOf(a.parameters().get("file"));
                    t.live.generatedMethod = a.parameters().get("method") == null ? null : String.valueOf(a.parameters().get("method"));
                }
            }
        }
        // after the composer WROTE the suite, the planner located the file three plans
        // running instead of committing it - the written suite is a generated test like TreeCodegen's, so the
        // commit gates and the deterministic auto-commit apply to it too
        for (Map.Entry<String, Object> e : executionResults.entrySet()) {
            if ("SpecTestComposer".equals(ToolExecutor.baseToolName(e.getKey())) && isSuccess(e.getValue())
                    && e.getValue() instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("written")) && m.get("suiteFile") != null) {
                t.live.generatedFile = String.valueOf(m.get("suiteFile"));
                t.live.generatedMethod = m.get("testName") == null ? null : String.valueOf(m.get("testName"));
            }
        }
        if (t.program != null && t.program.code() != null && !t.program.code().isBlank()) {
            lastMemoryProgram = t.program.code();   // remember for ingest-on-approve
        }

        // Record any SUCCESSFUL results so the answer gate can insist the final
        // answer actually reports them (keep only the latest per tool, capped).
        executionResults.forEach((name, value) -> {
            if (isSuccess(value)) {
                t.successfulResults.append(name).append(" => ")
                        .append(summarizeResults(Map.of(name, value))).append("\n");
                t.lastSuccessTool[0] = ToolExecutor.baseToolName(name);
                    if (!AnswerGate.EDIT_TOOLS.contains(t.lastSuccessTool[0]) || AnswerGate.isEditSuccess(t.lastSuccessTool[0], value)) {
                        t.succeededTools.add(t.lastSuccessTool[0]);   // F313
                    }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) value;
                t.lastSuccessResult[0] = m;
                if (EDIT_TOOLS_FOR_HINT.contains(t.lastSuccessTool[0])) {
                    t.lastRefusalHint[0] = null;   // the step was taken
                }
            } else {
                String hint = AnswerGate.refusalHint(value);   // F312
                if (hint != null) {
                    t.lastRefusalHint[0] = hint;
                }
            }
        });
        if (t.successfulResults.length() > 0) {
            t.live.haveSuccess = true;
        }
        observeGroundTruth(executionResults, t.committedThisSession, t.testOutcomeObserved);
        // Track WebSearch usage so the web-read guard can tell a bare snippet search
        // apart from an actual page read.
        Object web = executionResults.get("WebSearch");
        if (web instanceof Map<?, ?> wm && isSuccess(web)) {
            if ("read".equals(wm.get("action"))) {
                t.live.webReadRan = true;
            } else if ("search".equals(wm.get("action"))) {
                t.live.webSearchRan = true;
            }
        }
        return executionResults;
    }

    /**
     * Folds the results into the context, consults on a failing program, judges the task step, and writes the
     * "executed" seam.
     */
    private Next foldTheResults(Turn t, String userRequest, int iteration, Map<String, Object> executionResults) {
        // STEP F: fold results into the context for the next planning round.
        // Cap the folded text: a verbose tool result (e.g. AssembleContext's
        // file contents) must not balloon the planning prompt and overwhelm
        // the model's ability to emit a clean JSON plan.
        markStepsExecuted(t.contextLog, userRequest);
        int iterationStart = t.contextLog.length();
        t.contextLog.append(executedBranchLine(iteration, t.selected))
                  .append(" Tool results: ").append(summarizeResults(executionResults))
                  .append("\nBranches already tried: ").append(t.live.triedBranches)
                  .append("\nIf the goal is now achieved, respond with Shape B (final_answer).");
        if (TieredContext.enabled()) {
            TieredContext.shared().prepare(t.contextLog.substring(iterationStart));   // summarised while the planner thinks
        }
        if (FrameStack.enabled()) {   // before the step judge, which may move the turn to its next step
            FrameStack.record(t.live, userRequest, iteration, t.selected == null ? null : t.selected.name(),
                    t.contextLog.toString(), t.contextLog.substring(iterationStart));
        }

        // PROGRAM-FAILURE CONSULT: a generated program that won't compile or exits
        // non-zero is exactly where the small brain stalls. Ask an outer expert model
        // (BigBrother cloud, else the remote 14B) for a corrected program and feed it
        // back so the next WriteProgram uses it. Bounded so it still terminates.
        // a nonsense `RunProgram java -cp … InventoryLedger` failed and the
        // consult wrote two hallucinated Python programs in an analysis-only task - the "failing program" was never
        // a program of this turn. Only a program this turn WROTE is repaired, and never in an analysis-only task.
        String progFailure = analysisOnly(userRequest) ? null : programFailure(executionResults);
        if (progFailure != null && t.live.programConsults < t.MAX_PROGRAM_CONSULTS) {
            String fix = consultOnProgramFailure(userRequest, progFailure);
            if (fix != null && !fix.isBlank()) {
                t.live.programConsults++;
                // Authoritative: RUN the expert's corrected program directly next
                // iteration (loop-top interceptor), so the 7B can't revert to its
                // failing version. The bigger model's fix overrides the 7B's.
                t.pendingExpertProgram[0] = fix;
                listener.onPlanRefined(true, "consulted an outer model to fix the failing program");
            }
        }

        if (TaskPlan.active(t.live) && !TaskPlan.finished(t.live)) {
            TaskPlan.recordExactRounds(userRequest, t.selected == null ? null : t.selected.actions(), executionResults,
                    t.live.exactRoundsRun);
            judgeTaskStep(userRequest, t.contextLog, t.live);
        }
        Map<String, Object> instructionResults = new LinkedHashMap<>(executionResults);
        instructionResults.remove("Bash#build");   // Hermit's own build after the edits is no instruction of the plan
        String failedInstruction = toolExecutor.hasFollower() && pendingAuthoredPlan == null
                ? InstructionFollower.failure(instructionResults) : null;
        if (failedInstruction != null) {
            String help = followerHelp(userRequest, t.contextLog.toString(), failedInstruction);
            if (help != null) {
                pendingAuthoredPlan = help;
                listener.onPlanRefined(true, "the follower's helper (" + EscalationLadder.shared().lastLabel()
                        + ") wrote the corrective plan — it goes through the plan guards next iteration");
            }
        }
        t.currentNode = new PlanNode(t.selected.name(), userRequest, new HashMap<>(executionResults));
        if (checkpoint.enabled()) {   // seam "executed" - the iteration's results are folded; next is iteration + 1
            mirror(t.live, iteration + 1, t.contextLog, t.successfulResults, t.succeededTools, t.committedThisSession,
                    t.testOutcomeObserved, t.buildBroken, t.pendingExpertProgram, t.lastSuccessTool, t.lastRefusalHint,
                    t.lastSuccessResult, t.currentNode);
            Path stepExec = checkpoint.write(t.live, "executed");
            checkpoint.note(stepExec, "plan.json", TurnSnapshot.toJson(TurnSnapshot.jsonSafe(planAsData(t.plan))));
            checkpoint.note(stepExec, "timing.txt", "iteration took " + (System.currentTimeMillis() - t.iterationStartedAt) + " ms");
            checkpoint.note(stepExec, "results.json", TurnSnapshot.toJson(TurnSnapshot.jsonSafe(executionResults)));
            Checkpoint.Control c = checkpoint.control(stepExec, "executed", iteration);
            if (c == Checkpoint.Control.STOP) {
                throw new Checkpoint.Stop(stepExec);
            }
            if (c == Checkpoint.Control.RETRY) {
                t.result = retryFrom(t.planState, userRequest);
                return Next.STOP;
            }
        }
        return Next.GO_ON;
    }

    // Lenient like PlanParser's: critic/gate verdicts are LLM-authored JSON and can
    // carry the same quirks (\' escapes, single quotes, literal newlines in issue text).
    private final ObjectMapper mapper =
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                    .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                    .build();

    /**
     * Run the review panel over a plan; return the concrete issues
     * raised (empty = all critics approved). Each critic reviews through one
     * lens against the context; a critic that can't be parsed is treated as a
     * silent approval (never blocks progress spuriously).
     */
    /** The current turn's request - reviewers may not contradict it. */
    private String currentRequest = "";

    private List<String> reviewPlan(String contextSoFar, String planJson, boolean allowRemoteCritic) {
        List<String> issues = new ArrayList<>();
        for (String lens : PlanPromptBuilder.REVIEW_LENSES) {
            String prompt = planPrompts.reviewPrompt(lens, contextSoFar, planJson);
            EscalationLadder.Reply hosted = EscalationLadder.shared().askInstead(EscalationLadder.Job.LENS_REVIEW, 0, prompt, 1500);
            String verdict = hosted != null ? hosted.text() : coTReasoner.getRealLLMResponse(prompt);
            addIssuesFromVerdict(verdict, lens, issues);
        }
        // AUTOMATIC REMOTE CRITIC: when the stronger local helper model is reachable, add its
        // holistic verdict to the same panel. It runs at a small context, so the
        // review context is capped; a failure/drop just contributes no issues.
        // Skipped when the 14B already refined this plan (no self-critique).
        if (allowRemoteCritic && helpers.anyAvailable()) {
            String remoteVerdict = remoteCritique(capTail(contextSoFar, helperContextChars(REMOTE_CRITIC_CONTEXT_CAP)), planJson);
            if (remoteVerdict != null) {
                addIssuesFromVerdict(remoteVerdict, "local-14b", issues);
            }
        }
        // a complex plan is also read by the hosted critic of -Dhermit.ladder.job.plan_review (Luna, in the rewrite drive)
        if (planIsComplex(planJson) && EscalationLadder.shared().hasOwnRungs(EscalationLadder.Job.PLAN_REVIEW)) {
            String hostedVerdict = escalationComplete(EscalationLadder.Job.PLAN_REVIEW, planPrompts.reviewPrompt("holistic",
                    capTail(contextSoFar, HostedBudget.charsFor(contextSoFar)), planJson), 1500);
            if (hostedVerdict != null) {
                addIssuesFromVerdict(hostedVerdict, "hosted critic", issues);
            }
        }
        // drop issues that demand a parameter no tool in the plan declares (critic inventions)
        Set<String> vocab = planVocabulary(planJson);
        issues.removeIf(i -> ReviewPanel.fabricatedParameterIssue(i, vocab));
        // a documented action/parameter value called "incorrect"/"invalid" by a reviewer who sees no tool docs
        issues.removeIf(i -> ReviewPanel.disputesDocumentedValue(i, vocab));
        // a REGISTERED tool called "not a valid tool" / "not documented"
        issues.removeIf(i -> ReviewPanel.disputesRegisteredTool(i, toolRegistry.getAllTools().keySet()));
        // swapping one CONTEXT-grounded symbol for another is a preference, not a defect
        issues.removeIf(i -> ReviewPanel.prefersAnotherGroundedSymbol(i, planJson, contextSoFar));
        // the plan's JSON schema is the contract's, not the reviewer's
        issues.removeIf(ReviewPanel::disputesThePlanSchema);
        // a registered tool "is not a standard CLI tool and cannot be assumed to be installed" - it is the agent's own
        issues.removeIf(i -> ReviewPanel.doubtsARegisteredTool(i, toolRegistry.names()));
        // "the plan repeats a call that already succeeded (Run CodeGraph)" - checked against the signatures: a plan none
        // of whose actions ran this turn is not a repeat, whatever the branch is called
        issues.removeIf(i -> ReviewPanel.claimsARepeat(i) && !anyActionRanThisTurn(planJson)
                && !ActivityRecorder.hasCitedEvidence(i, contextSoFar));
        // "the agent did not perform the first step of locating … (CodeGraph action=locate)" - the plan IS that step
        issues.removeIf(i -> ReviewPanel.complainsThePlanIsNotYetDone(i, planJson));
        // "Invalid tool 'tests'" - a documented CodeGraph action, or a registered tool, called invalid
        issues.removeIf(i -> ReviewPanel.callsAValidToolOrActionInvalid(i, planJson, toolRegistry.names(), CodeGraphTool.ACTIONS));
        // the plan's confidence value is a schema field, never a defect
        issues.removeIf(ReviewPanel::disputesPlanConfidence);
        // a "placeholder" claim is checked against the plan text - no marker, no issue
        issues.removeIf(i -> ReviewPanel.claimsPlaceholderWhereNoneIs(i, planJson));
        // a demand to re-verify what a tool result in context already establishes
        issues.removeIf(i -> ReviewPanel.demandsReVerificationOfContext(i, contextSoFar));
        // a plan is one step of the method; "it omits the later steps" is not a defect
        issues.removeIf(ReviewPanel::demandsLaterSteps);
        // the request's "When TreeCodegen succeeds, run ..." step comes after this plan
        issues.removeIf(i -> ReviewPanel.demandsTheStepAfterTheTool(i, currentRequest, planJson));
        // a plan that only reads is the first step of a write task: "it does not write or commit yet" is a later step
        // (supervised rewrite drive, FM-010)
        if (readsOnly(planJson)) {
            // the whole-task critics (completeness, the holistic remote critic) judge a read-only step against the whole
            // task, and their wordings vary without end: for a plan that only reads, their issues are dropped
            issues.removeIf(i -> ReviewPanel.demandsTheWriteStep(i) || i.startsWith("[completeness]") || i.startsWith("[local-14b]"));
        }
        // a critic's claim that a shell step is malformed, when bash parses every shell step (FM-019)
        if (issues.stream().anyMatch(ReviewPanel::claimsShellSyntaxError)) {
            ParsedPlan parsedForSyntax = planParser.parse(planJson);
            if (parsedForSyntax != null && parsedForSyntax.branches() != null && !parsedForSyntax.branches().isEmpty()
                    && parsedForSyntax.branches().stream().allMatch(b -> shellSyntaxIssue(b.actions()) == null)) {
                issues.removeIf(ReviewPanel::claimsShellSyntaxError);
            }
        }
        // an issue that forbids what the CURRENT request asks for (an earlier turn's constraint)
        issues.removeIf(i -> ReviewPanel.contradictsTheCurrentRequest(i, currentRequest));
        // a path the request names is not "unlikely to exist"
        issues.removeIf(i -> ReviewPanel.doubtsAPathTheRequestNames(i, currentRequest));
        // a LOCAL commit is not a push; tool-derived parameters are not missing
        issues.removeIf(i -> ReviewPanel.confusesLocalCommitWithPush(i, currentRequest));
        issues.removeIf(ReviewPanel::demandsAutoDerivedParameter);
        // a quoted value made of the request's own words is not "invented"
        issues.removeIf(i -> ReviewPanel.claimsRequestWordsAreInvented(i, currentRequest));
        // a spec-level demand against a unit-test request is a stage confusion
        issues.removeIf(i -> ReviewPanel.demandsSpecTestForAUnitTestRequest(i, currentRequest));
        // an issue calling a value ungrounded when the context contains it
        issues.removeIf(i -> ReviewPanel.claimsAGroundedValueIsUngrounded(i, contextSoFar));
        // with a code-authoring tool in the plan, "the plan does not specify the exact content of the test" is a
        // misreading of the tool (the tool writes the code from task + templateMethod), not a gap
        if (planJson != null && (planJson.contains("\"TreeCodegen\"") || planJson.contains("\"SpecTestComposer\""))) {
            issues.removeIf(ReviewPanel::demandsGeneratedCode);
        }
        // a plan is ONE step - demands for the emulator verdict / gradle outcome / recording / commit / report
        // that come AFTER this step's tool result are not gaps of this plan
        if (planJson != null && planJson.contains("\"tool\"")) {
            issues.removeIf(ReviewPanel::demandsLaterSteps);
        }
        // an absolute path "contradicting" the relative path it ends with is the same location
        issues.removeIf(ReviewPanel::falsePathContradiction);
        // an issue that rests on a file, line, type or member that does not exist is dismissed
        issues.removeIf(i -> CitationValidator.citesMissing(i, workbenchRoot(), planJson + "\n" + currentRequest));
        // "assumes the test method 'X' exists" where X is the NEW method a TreeCodegen call creates
        if (planJson != null && planJson.contains("\"TreeCodegen\"")) {
            issues.removeIf(i -> ReviewPanel.disputesNewMethodExistence(i, planJson));
        }
        // Dedupe near-identical issues (multiple lenses + the remote critic often raise
        // the SAME point) so a weak planner gets a concise, distinct list to act on
        // instead of being flooded with restatements.
        return dedupeIssues(issues);
    }

    /** Lower-cased words of the plan's tools' documentation plus every parameter key the plan uses - the vocabulary a
     *  legitimate "missing parameter" complaint must draw its parameter name from. */
    Set<String> planVocabulary(String planJson) {
        Set<String> vocab = new HashSet<>();
        try {
            var root = mapper.readTree(planJson.substring(planJson.indexOf('{')));
            List<JsonNode> actions = new ArrayList<>();
            for (var b : root.path("branches")) {
                for (var a : b.path("actions")) {
                    actions.add(a);
                }
            }
            for (var a : root.path("actions")) {
                actions.add(a);
            }
            for (var a : actions) {
                a.path("parameters").fieldNames().forEachRemaining(k -> vocab.add(k.toLowerCase(Locale.ROOT)));
                String tool = a.path("tool").asText("");
                var t = toolRegistry.getTool(tool);
                if (t != null) {
                    addWords(vocab, t.getDocumentation());
                }
            }
        } catch (Exception ignored) {
            // an unparseable plan yields an empty vocabulary → no issue is dropped
        }
        return vocab;
    }

    /** How many rounds a reviewer may raise the same issue before it stops blocking the plan. */
    static final int STUCK_ISSUE_LIMIT = 2;

    private final Map<String, Integer> issueRounds = new HashMap<>();

    /** Drop the issues already raised {@link #STUCK_ISSUE_LIMIT} times this turn (they no longer veto). */
    private List<String> dropStuckIssues(List<String> issues, StringBuilder contextLog) {
        List<String> kept = new ArrayList<>();
        for (String issue : issues) {
            String key = normalizeIssue(issue);
            int seen = issueRounds.merge(key, 1, Integer::sum);
            if (seen <= STUCK_ISSUE_LIMIT) {
                kept.add(issue);
            } else {
                contextLog.append("\n[Reviewer issue raised ").append(seen)
                        .append(" times — proceeding despite it] ").append(issue);
            }
        }
        return kept;
    }

    /** Parse a critic's {"approved":..,"issues":[..]} verdict, appending any issues (tagged by lens). */
    private void addIssuesFromVerdict(String verdict, String lens, List<String> issues) {
        try {
            int a = verdict.indexOf('{');
            int b = verdict.lastIndexOf('}');
            if (a < 0 || b <= a) {
                return;
            }
            var node = mapper.readTree(verdict.substring(a, b + 1));
            if (node.has("approved") && !node.get("approved").asBoolean()) {
                List<String> texts = new ArrayList<>();
                for (var i : node.path("issues")) {
                    String text = i.asText().trim();
                    if (!text.isEmpty()) {
                        texts.add(text);
                    }
                }
                // a "rejection" whose issues state findings and complain about nothing is the reviewer
                // answering with content instead of a verdict - not a rejection
                if (texts.stream().anyMatch(ReviewPanel::looksLikeAComplaint)) {
                    for (String text : texts) {
                        issues.add("[" + lens + "] " + text);
                    }
                }
            }
        } catch (Exception ignored) {
            // unparseable critic -> treat as approval
        }
    }

    private String remoteCritique(String contextSoFar, String planJson) {
        HelperChain.Answer a = helpers.consult(
                planPrompts.reviewPrompt("holistic", contextSoFar, planJson), "answer", null);
        return a == null ? null : a.text();
    }

    /**
     * Ask a helper (the helper chain: a local helper, then hosted ones) to REWRITE the plan into an
     * improved one, returned as the same Shape-A JSON, or null if none could.
     */
    private String refinePlan(String contextSoFar, String planJson) {
        HelperChain.Answer a = helpers.consult(
                planPrompts.refinePrompt(toolRegistry.compactToolList(), contextSoFar + currentLedgerBlock, planJson), "answer", null);
        return a == null ? null : a.text();
    }

    /** Ask a helper to rewrite a malformed plan response into valid JSON, or null. */
    private String remoteRepairJson(String rawResponse) {
        HelperChain.Answer a = helpers.consult(
                planPrompts.repairJsonPrompt(capTail(rawResponse, helperContextChars(REMOTE_REFINE_CONTEXT_CAP))), "answer", null);
        return a == null ? null : a.text();
    }

    /**
     * Ask a helper to AUTHOR a fresh Shape-A plan from the goal + context when the local
     * brain can't produce a parseable/adequate one. Returns the plan JSON, or null.
     */
    private String remoteAuthorPlan(String userRequest, String contextSoFar) {
        // the author's plan repeated two lookups that stood on the failure ledger -
        // the author had never seen the ledger; it got a 2500-character context tail. The ledger goes to every
        // model that writes a plan for this turn.
        String prompt = planPrompts.authorPlanPrompt(toolRegistry.compactToolList(), userRequest,
                capTail(contextSoFar, helperContextChars(REMOTE_REFINE_CONTEXT_CAP)) + currentLedgerBlock);
        // with the local helper first in the helper chain, the corrective plans after a rejection
        // came from the 16B - "Fix stockKeyOf Method": a TreeEdit with no parameters, in an analysis-only task -
        // and the strong author was never asked. Authoring belongs to the strongest model: when a plan-escalation model
        // is configured it authors; the helper chain authors only when there is none.
        // Model router: Hermit's rules (and Jev, by mode) choose the local helper or the hosted author for this plan.
        boolean local = authorViaHelpersFirst(anyEscalation()) || route(ModelRouter.Point.PLAN_AUTHOR) == ModelRouter.Route.LOCAL;
        HelperChain.Answer a = local ? helpers.consult(prompt, "answer", null) : null;
        if (a != null) {
            // the helper (a local 16B) answered with prose, the caller found no plan
            // in it and gave up - the stronger author was never asked. An unparseable helper plan falls through.
            ParsedPlan parsed = planParser.parse(a.text());
            if (parsed != null && !parsed.isFinal() && !parsed.isClarify() && parsed.branches() != null
                    && !parsed.branches().isEmpty()) {
                return a.text();
            }
            listener.onPlanRefined(false, "helper's authored plan did not parse — escalating");
        }
        // ESCALATION (user, 6 Sep - drive 2N/317, stage 3): the local planner could not design the fix (a memo keyed so
        // that a changed file is re-hashed) and, with the helper chain disabled, nobody else ever authored a plan. A
        // bigger cloud model named by -Dhermit.plan.baseUrl/model authors the corrective plan after the review has
        // rejected two local plans - a last resort, bounded by MAX_AUTHORED_PLANS per turn and paced.
        if (!anyEscalation()) {
            return null;
        }
        return hostedAuthorPlan(userRequest, contextSoFar, EscalationLadder.Job.PLAN_AUTHOR);
    }

    /** A plan written by the hosted escalation for this job: a corrective plan (PLAN_AUTHOR) or the task's first one. */
    private String hostedAuthorPlan(String userRequest, String contextSoFar, EscalationLadder.Job job) {
        String richPrompt = authorPrompt(userRequest, contextSoFar, job);
        HostedBudget.note(job.name(), contextSoFar, HostedBudget.charsFor(contextSoFar));
        // the model is named once it is chosen (the router's and the ladder's lines); the last label is the previous call's
        listener.onPlanRefined(false, "plan escalation: asking for " + job);
        return escalationComplete(job, richPrompt, 6000);
    }

    /** The prompt a hosted model writes a plan from: the tools' full contracts, the code named and the context. */
    private String authorPrompt(String userRequest, String contextSoFar, EscalationLadder.Job job) {
        // the escalated author gets the FULL contract of every tool the request or the context tail names -
        // it designed the right fix and then invented `edits`/`position` parameters from 60-character glosses
        String tail = capTail(contextSoFar, 6000);
        String fullDocs = toolRegistry.documentationFor(userRequest + "\n" + tail, 2500);
        // the escalated author wrote `final var fileID = ...; // original extraction` and "pre-checks
        // omitted for brevity" - a SKETCH, because it had never seen the method it was rewriting. The source of the
        // production methods the request and the context name (Type#method) goes into its prompt.
        String sources = codeUnderTestSources(userRequest + "\n" + tail);
        String tools = fullDocs.isBlank() && sources.isBlank() ? toolRegistry.compactToolList()
                : toolRegistry.compactToolList() + "\nFULL CONTRACTS of the tools this step needs — use EXACTLY these "
                + "parameter names (a TreeEdit is ONE edit: op + method/anchor + code; several edits are several actions; "
                + "a replaceBody code is the COMPLETE new body with every statement written out — no '...', no 'omitted'):"
                + fullDocs + sources;
        String context = capTail(contextSoFar, HostedBudget.charsFor(contextSoFar)) + currentLedgerBlock;   // F381
        return job == EscalationLadder.Job.INITIAL_PLAN ? planPrompts.initialPlanPrompt(tools, userRequest, context)
                : planPrompts.authorPlanPrompt(tools, userRequest, context);
    }

    /**
     * The instruction follower's helper (the user, 2026-10-25): an instruction of the executed plan failed, and in half
     * the cases DeepSeek Flash (the ladder job FOLLOWER_HELP, -Dhermit.ladder.job.follower_help) writes the corrective
     * plan instead of the step planner. Its plan takes the path of any authored plan, through the plan guards and the
     * seams. Null when the step planner corrects the plan: no helper configured, the coin, the helper set aside, or no
     * plan in its reply. A helper out of funds is set aside at once, until its marker file is removed; one that times
     * out or fails otherwise leaves this plan to the step planner and is asked again next time.
     */
    private String followerHelp(String userRequest, String contextSoFar, String failure) {
        EscalationLadder ladder = EscalationLadder.shared();
        if (!ladder.enabled() || !ladder.hasOwnRungs(EscalationLadder.Job.FOLLOWER_HELP)) {
            return null;
        }
        String off = InstructionFollower.helperOff();
        if (off != null) {
            listener.onPlanRefined(false, "the follower's helper is set aside (" + excerpt(off, 120)
                    + "): the step planner corrects the plan");
            return null;
        }
        if (ThreadLocalRandom.current().nextDouble() >= InstructionFollower.helpChance()) {
            listener.onPlanRefined(false, "an instruction failed; the step planner corrects the plan (the helper's turn is the other half)");
            return null;
        }
        String note = "\n[The instruction follower could not carry out an instruction of your last plan] " + failure
                + "\nWrite the corrective plan for the CURRENT step only. If the failed instruction was wrong, replace it; "
                + "if it was right, carry it out another way from what the attempt shows. Do not redo what succeeded.";
        listener.onPlanRefined(false, "an instruction failed; asking the follower's helper (FOLLOWER_HELP) for the corrective plan");
        EscalationLadder.Reply reply = ladder.ask(EscalationLadder.Job.FOLLOWER_HELP, 0, 0,
                authorPrompt(userRequest, contextSoFar + note, EscalationLadder.Job.PLAN_AUTHOR), 6000);
        if (reply == null) {
            boolean broke = InstructionFollower.outOfFunds(ladder.lastFailure());
            if (broke) {
                InstructionFollower.setHelperOff(ladder.lastFailure());
            }
            listener.onPlanRefined(false, "the follower's helper did not answer (" + excerpt(ladder.lastFailure(), 120)
                    + (broke ? "): out of funds, set aside until its marker is removed" : ")")
                    + "; the step planner corrects the plan");
            return null;
        }
        ParsedPlan plan = planParser.parse(reply.text());
        return plan != null && !plan.isFinal() && !plan.isClarify() && plan.branches() != null && !plan.branches().isEmpty()
                ? reply.text() : null;
    }

    /** The source of the production methods named in {@code text}, for the escalated plan author; "" when none. */
    private String codeUnderTestSources(String text) {
        List<String> specs = specsMentioned(text);
        if (specs.isEmpty()) {
            return "";
        }
        try {
            String projectDir = firstNonBlank(System.getProperty("hermit.bash.defaultDir"), System.getProperty("hermit.projectDir"));
            if (projectDir == null) {
                return "";
            }
            CodeGraph graph = CodeGraphTool.graphFor(projectDir, System.getProperty("hermit.classesDir"));
            StringBuilder sb = new StringBuilder();
            for (String spec : specs) {
                var refs = graph.locate(spec);
                if (refs.isEmpty() || refs.get(0).decl() == null) {
                    continue;
                }
                String src = refs.get(0).decl().toString().strip();
                sb.append("\n--- SOURCE OF THE CODE UNDER TEST: ").append(spec).append(" (").append(refs.get(0).file().getFileName())
                        .append(") — edit from THESE names, and copy anchors verbatim from these statements ---\n")
                        .append(src.length() > 3500 ? src.substring(0, 3500) + "\n…" : src).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The plan-escalation endpoint (-Dhermit.plan.*), probed once per agent; null when none is configured or reachable. */
    private HelperModelTool planEscalationTool;
    private boolean planEscalationProbed;

    // ---- F376: the fallback author (the user's rule, 12 September) - Groq first, DeepSeek when Groq's day is spent ----
    private HelperModelTool planEscalationFallback;
    private boolean fallbackProbed;
    private long primaryExhaustedUntil;
    private String lastEscalationLabel = "plan escalation";

    /**
     * -Dhermit.plan2.baseUrl/model; with -Dhermit.plan2.enabled=true and no URL, DeepSeek's chat model whenever its key
     * is configured. OPT-IN: the fallback bills per token, so nothing reaches it unless a drive asks for it - a unit test
     * with the key on disk must never spend money (the first run of this code did, twice).
     */
    private HelperModelTool planEscalationFallback() {
        if (!fallbackProbed) {
            fallbackProbed = true;
            boolean enabled = Boolean.getBoolean("hermit.plan2.enabled") || System.getProperty("hermit.plan2.baseUrl") != null;
            if (!enabled) {
                return null;
            }
            String defaultUrl = HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK) ? HostedPlatforms.DEEPSEEK_BASE_URL : null;
            HelperEndpoint cfg = HelperEndpoint.named("plan2", "DeepSeek-plan", defaultUrl, "deepseek-chat");
            if (cfg != null && cfg.model() != null) {
                HelperModelTool tool = new HelperModelTool(cfg);
                planEscalationFallback = tool.isAvailable() ? tool : null;
            }
        }
        return planEscalationFallback;
    }

    private boolean anyEscalation() {
        return EscalationLadder.shared().enabled() || planEscalation() != null || planEscalationFallback() != null;
    }

    // ---- model router: local helper or hosted BigBrother at each point Hermit asks another model ----
    private final ModelRouter router = new ModelRouter();
    private StringBuilder routerContext;
    private int routerIteration;
    private int routerMaxIterations = 12;
    private String lastRuling;
    private int rulingFlips;

    /** The router's verdict-flip signal: every final answer's ruling, counted when it differs from the previous one. */
    private void noteRuling(String answer) {
        String ruling = AnswerGate.verdictOf(answer);
        if (ruling == null) {
            return;
        }
        if (lastRuling != null && !lastRuling.equals(ruling)) {
            rulingFlips++;
        }
        lastRuling = ruling;
        ModelRouter.noteTurn(routerSignals(ModelRouter.Point.BIGBROTHER_CALL));
    }

    /** The task plan the first model of the TASK_PLAN order writes; null when none answers with steps. */
    private List<TurnSnapshot.TaskStep> writeTaskPlan(String userRequest, String contextSoFar, String refusal) {
        String context = capTail(contextSoFar, HostedBudget.charsFor(contextSoFar))
                + (refusal == null ? "" : "\n[Supervisor refused the task plan; write it again] " + refusal);
        HostedBudget.note(EscalationLadder.Job.TASK_PLAN.name(), contextSoFar, HostedBudget.charsFor(contextSoFar));
        listener.onPlanRefined(false, "plan escalation: asking for " + EscalationLadder.Job.TASK_PLAN);
        return TaskPlan.parseSteps(escalationComplete(EscalationLadder.Job.TASK_PLAN,
                planPrompts.taskPlanPrompt(toolRegistry.compactToolList(), userRequest, context), 3000));
    }

    /**
     * The task plan's judge (GPT in the rewrite drive) reads what the iteration did for the current step and what came
     * back: a done step gives way to the next, and the steps ahead are checked again; a step not done after
     * {@link #reviseAfter()} iterations has it and the later steps rewritten.
     */
    private void judgeTaskStep(String userRequest, StringBuilder contextLog, TurnSnapshot live) {
        TurnSnapshot.TaskStep step = TaskPlan.current(live);
        int at = contextLog.lastIndexOf("[Iteration ");
        String results = capTail(at < 0 ? contextLog.toString() : contextLog.substring(at), 12_000);
        live.stepIterations++;
        // a step that names one round of exact items is done when those items ran, whatever a judge reads
        TaskPlan.Verdict v = TaskPlan.exactRoundVerdict(userRequest, step.goal, live.exactRoundsRun);
        if (v == null) {
            v = TaskPlan.parseVerdict(escalationComplete(EscalationLadder.Job.STEP_DONE,
                    planPrompts.stepDonePrompt(userRequest, TaskPlan.block(live), step.goal, results), 1000));
        }
        int n = live.currentStep + 1;
        int size = live.taskPlan.size();
        String tools = toolRegistry.compactToolList();
        String context = capTail(contextLog.toString(), HostedBudget.charsFor(contextLog.toString()));
        if (v != null && v.done()) {
            TaskPlan.markCurrentDone(live, v.why());
            contextLog.append("\n[Task plan] step ").append(n).append(" done: ").append(v.why());
            listener.onPlanRefined(false, "task plan: step " + n + " of " + size + " done (" + escalationLabel() + "): " + v.why());
            // the rounds an earlier plan already ran with this one (two rounds in one plan) are done too
            for (TurnSnapshot.TaskStep next = TaskPlan.current(live); next != null; next = TaskPlan.current(live)) {
                TaskPlan.Verdict also = TaskPlan.exactRoundVerdict(userRequest, next.goal, live.exactRoundsRun);
                if (also == null || !also.done()) {
                    break;
                }
                TaskPlan.markCurrentDone(live, also.why());
                contextLog.append("\n[Task plan] step ").append(live.currentStep).append(" done: ").append(also.why());
                listener.onPlanRefined(false, "task plan: step " + live.currentStep + " of " + size + " done too: " + also.why());
            }
            if (!TaskPlan.finished(live)) {
                List<TurnSnapshot.TaskStep> ahead = TaskPlan.parseSteps(escalationComplete(EscalationLadder.Job.PLAN_AHEAD,
                        planPrompts.planAheadPrompt(tools, userRequest, TaskPlan.block(live), context), 3000));
                List<TurnSnapshot.TaskStep> before = List.copyOf(live.taskPlan.subList(live.currentStep, live.taskPlan.size()));
                if (ahead != null && !ahead.stream().map(t -> t.goal).toList().equals(before.stream().map(t -> t.goal).toList())) {
                    if (!TaskPlan.keepsTheWork(before, ahead)) {
                        listener.onPlanRefined(false, "task plan: a look-ahead by " + escalationLabel() + " that dropped the work "
                                + "ahead was not applied: " + TaskPlan.goals(ahead, n + 1));
                    } else {
                        TaskPlan.replaceFromCurrent(live, ahead);
                        contextLog.append("\n[Task plan] the steps ahead were rewritten by ").append(escalationLabel());
                        listener.onPlanRefined(true, "task plan: the steps ahead were rewritten by " + escalationLabel() + ": "
                                + TaskPlan.goals(ahead, n + 1));
                    }
                }
            }
            return;
        }
        String why = v == null ? "the judge gave no verdict" : v.why();
        listener.onPlanRefined(false, "task plan: step " + n + " not done after " + live.stepIterations + " iteration(s): " + why);
        if (live.stepIterations >= reviseAfter()) {
            List<TurnSnapshot.TaskStep> revised = TaskPlan.parseSteps(escalationComplete(EscalationLadder.Job.PLAN_REVISE,
                    planPrompts.revisePlanPrompt(tools, userRequest, TaskPlan.block(live), context, why), 3000));
            if (revised != null && !TaskPlan.keepsTheRounds(live.taskPlan.subList(live.currentStep, size), revised)) {
                listener.onPlanRefined(false, "task plan: a revision by " + escalationLabel() + " that dropped a round of the "
                        + "steps was not applied: " + TaskPlan.goals(revised, n));
            } else if (revised != null) {
                live.planRevisions++;
                TaskPlan.replaceFromCurrent(live, revised);
                contextLog.append("\n[Task plan] the current and later steps were rewritten by ").append(escalationLabel())
                        .append(" after ").append(reviseAfter()).append(" iteration(s) on step ").append(n).append(": ").append(why);
                listener.onPlanRefined(true, "task plan: the current and later steps were rewritten by " + escalationLabel() + ": "
                        + TaskPlan.goals(revised, n));
            }
        }
    }

    /**
     * The iteration's planning call. With two planner models (served at the run's endpoint) the work is picked for one:
     * the lighter model for a routine step and the stronger one after a plan or an ending was refused, until a plan runs,
     * and one time in ten the other one (ModelRouter#choose). Otherwise the run's model.
     */
    private String planningCall(String prompt) {
        EscalationLadder.Reply hosted = EscalationLadder.shared().askInstead(EscalationLadder.Job.STEP_PLAN,
                routerSignals(null).planRefusals(), prompt, Integer.getInteger("hermit.maxTokens", 4096));
        if (hosted != null) {
            listener.onPlanRefined(false, "planner model: " + hosted.model() + " by the ladder — the step plan's own rungs");
            return hosted.text();
        }
        List<String> models = plannerModels();
        if (models.size() < 2) {
            return coTReasoner.getRealLLMResponse(prompt);
        }
        ModelRouter.Signals signals = routerSignals(null);
        boolean refused = refusedSinceLastRun(routerContext == null ? "" : routerContext.toString());
        ModelRouter.ModelChoice choice = router.choose(ModelRouter.Pick.STEP, signals, models,
                refused ? models.get(1) : models.get(0),
                refused ? "a plan or an ending was refused since the last plan ran" : "a routine step");
        listener.onPlanRefined(false, "planner model: " + choice.model() + " by " + choice.by() + " — " + choice.why());
        return coTReasoner.getRealLLMResponse(prompt, choice.model());
    }

    private ModelRouter.Signals routerSignals(ModelRouter.Point point) {
        String ctx = routerContext == null ? "" : routerContext.toString();
        int answerRefusals = countOccurrences(ctx, "[Answer gate REJECTED");
        int planRefusals = (ledgerLive == null ? 0 : ledgerLive.planReviews) + countOccurrences(ctx, "[Plan REJECTED");
        return new ModelRouter.Signals(point, routerIteration, routerMaxIterations, answerRefusals, planRefusals, rulingFlips,
                ModelRouter.cloudCalls(), ModelRouter.maxCloudCalls(), helpers.anyAvailable(), anyEscalation());
    }

    private ModelRouter.Route route(ModelRouter.Point point) {
        ModelRouter.Signals signals = routerSignals(point);
        ModelRouter.noteTurn(signals);
        ModelRouter.Decision d = router.decide(signals);
        listener.onPlanRefined(false, "model router (" + point + "): " + d.route() + " by " + d.by() + " — " + d.why());
        return d.route();
    }

    /** The ruling of the last verdict a second reviewer disputed. */
    private String lastReviewedRuling;

    /**
     * The claims gate: the answer's claims join the ledger; every OPEN claim the answer or the request rests on is inspected
     * now (a few per gate: CodeGraph, a recording, the lines), and the answer stands only when it asserts no claim that is
     * open or refuted. Returns the refusal, or null.
     */
    private String claimsIssue(String answer, int iteration, StringBuilder contextLog, TurnSnapshot live) {
        List<Claim> ledger = live.claims;
        ClaimSession.bind(ledger);
        ClaimInspector inspector = ClaimSession.inspector();
        List<Claim> answerClaims = new ArrayList<>();
        int before = ledger.size();
        inspector.extract(ledger, answer, Claim.Origin.ANSWER, iteration, ClaimSession.maxClaims());
        // the answer's own claims: the new ones and the ledger claims it restates
        Map<Claim, String> restatedBy = new HashMap<>();   // the answer's sentence that restates it
        for (String sentence : answer.split("(?<=[.!?])\\s+|\\n+")) {
            Claim same = ClaimLedger.match(ledger, sentence);
            if (same != null && !answerClaims.contains(same)) {
                answerClaims.add(same);
                restatedBy.put(same, sentence.strip());
            }
        }
        answerClaims.addAll(ledger.subList(before, ledger.size()));
        for (Claim c : ledger) {   // the claims it cites by id ("Claim C17")
            if (!answerClaims.contains(c) && Pattern.compile("\\b" + c.id + "\\b").matcher(answer).find()) {
                answerClaims.add(c);
            }
        }
        // inspect the open ones: the answer's first, then the request's
        List<Claim> open = new ArrayList<>();
        for (Claim c : answerClaims) {
            if (c.status == Claim.Status.OPEN && !open.contains(c)) {
                open.add(c);
            }
        }
        for (Claim c : ClaimLedger.open(ledger)) {
            if (c.origin == Claim.Origin.REQUEST && !open.contains(c)) {
                open.add(c);
            }
        }
        StringBuilder inspected = new StringBuilder();
        int n = 0;
        for (Claim c : open) {
            if (n++ >= ClaimSession.perGate()) {
                break;
            }
            listener.onPlanRefined(false, "claims: inspecting " + c.id + " — " + excerpt(c.text, 120));
            inspector.map(c);
            Evidence proof = c.decidingEvidence();
            inspected.append("\n").append(c.id).append(" [").append(c.status).append("] ").append(excerpt(c.text, 160))
                    .append(proof != null ? " — " + proof.kind + ": " + excerpt(proof.summary, 220)
                            : c.undecidedReason != null ? " — " + excerpt(c.undecidedReason, 200) : "");
        }
        if (inspected.length() > 0) {
            contextLog.append("\n[Iteration ").append(iteration).append("] Claims inspected (ClaimInspector):").append(inspected);
        }
        List<String> openIds = new ArrayList<>(), refuted = new ArrayList<>();
        for (Claim c : answerClaims) {
            if (c.status == Claim.Status.OPEN) {
                openIds.add(c.id);
            } else if (c.status == Claim.Status.REFUTED) {
                // quote the sentence that restated it: told only the claim's id, a model keeps the same wording
                refuted.add(refutedNote(c, restatedBy.get(c)));
            }
        }
        List<String> overstated = undecidedStatedAsFact(answer, answerClaims);
        boolean verdict = ReviewPanel.isVerdictAnswer(answer);
        boolean requestOpen = !ClaimLedger.requestClaimsMapped(ledger);
        if (openIds.isEmpty() && refuted.isEmpty() && overstated.isEmpty() && !(verdict && requestOpen)) {
            return null;
        }
        StringBuilder sb = new StringBuilder("The answer rests on claims that are not decided in its favour.");
        if (!refuted.isEmpty()) {
            sb.append(" It asserts REFUTED claims: ").append(String.join("; ", refuted)).append(".");
        }
        if (!openIds.isEmpty()) {
            sb.append(" Still OPEN: ").append(String.join(", ", openIds)).append(".");
        }
        if (!overstated.isEmpty()) {
            sb.append(" It states UNDECIDED claims as established: ").append(String.join(", ", overstated))
                    .append(" — nothing proved them; say they are undecided, or prove them first.");
        }
        if (verdict && requestOpen) {
            sb.append(" A verdict needs every claim of the request mapped first; open: ").append(String.join(", ",
                    ClaimLedger.open(ledger).stream().filter(c -> c.origin == Claim.Origin.REQUEST)
                            .map(c -> c.id).toList())).append(".");
        }
        sb.append(" Write the answer from the CLAIMS block only: state SUPPORTED claims with their proof, REFUTED claims only as "
                + "refuted, UNDECIDED ones as undecided; inspect an OPEN claim with ClaimInspector action=map first.");
        return sb.toString();
    }

    /**
     * The hosted model completes the job: with the escalation ladder on, the rung its rules pick (a plan by the number of
     * plans refused this turn, the others by the endings refused); otherwise the primary escalation model, and on a rate
     * limit the fallback, while the primary rests for a while.
     */
    private String escalationComplete(EscalationLadder.Job job, String prompt, int maxTokens) {
        EscalationLadder ladder = EscalationLadder.shared();
        if (ladder.enabled()) {
            ModelRouter.Signals s = routerSignals(ModelRouter.Point.PLAN_AUTHOR);
            int refusals = job == EscalationLadder.Job.PLAN_AUTHOR || job == EscalationLadder.Job.INITIAL_PLAN
                    ? s.planRefusals() : s.answerRefusals();
            int floor = job == EscalationLadder.Job.PLAN_AUTHOR ? ladder.planFloor(refusals) : 0;
            ModelRouter.Pick pick = job == EscalationLadder.Job.PLAN_AUTHOR || job == EscalationLadder.Job.PLAN_REVISE
                    ? ModelRouter.Pick.REVISE
                    : job == EscalationLadder.Job.INITIAL_PLAN || job == EscalationLadder.Job.TASK_PLAN ? ModelRouter.Pick.INITIAL
                    : job == EscalationLadder.Job.PLAN_AHEAD ? ModelRouter.Pick.AHEAD : null;
            if (job == EscalationLadder.Job.PLAN_REVISE && ledgerLive != null) {
                floor = ledgerLive.planRevisions;   // each revision of the task plan starts one model further up its order
            }
            if (pick != null && ladder.hasOwnRungs(job)) {
                // a job with its own order (-Dhermit.ladder.job.<job>): one choice in ten starts on another rung
                List<String> models = ladder.rungsFor(job).stream().map(EscalationLadder.Rung::model).toList();
                ModelRouter.ModelChoice c = router.choose(pick, s, models, models.get(Math.min(floor, models.size() - 1)),
                        "the job's own order");
                if ("exploration".equals(c.by())) {
                    floor = ladder.rungIndex(job, c.model());
                }
                listener.onPlanRefined(false, "plan model (" + pick + "): " + c.model() + " by " + c.by() + " — " + c.why());
            }
            EscalationLadder.Reply reply = ladder.ask(job, refusals, floor, prompt, maxTokens);
            lastEscalationLabel = ladder.lastLabel();
            return reply == null ? null : reply.text();
        }
        HelperModelTool primary = planEscalation();
        if (primary != null && System.currentTimeMillis() >= primaryExhaustedUntil) {
            lastEscalationLabel = primary.endpointLabel();
            ModelRouter.countCloudCall();
            String reply = primary.complete(prompt, maxTokens);
            if (reply != null) {
                return reply;
            }
            long rest = exhaustionMillisFor(primary.lastFailure());
            if (rest > 0) {
                primaryExhaustedUntil = System.currentTimeMillis() + rest;
                listener.onPlanRefined(false, primary.endpointLabel() + " rate-limited (" + excerpt(primary.lastFailure(), 90)
                        + ") — resting " + (rest / 60_000) + " min; the fallback author takes over");
            } else if (planEscalationFallback() == null) {
                return null;
            }
        }
        HelperModelTool fallback = planEscalationFallback();
        if (fallback == null) {
            return null;
        }
        lastEscalationLabel = fallback.endpointLabel();
        ModelRouter.countCloudCall();
        return fallback.complete(prompt, maxTokens);
    }

    private String escalationLabel() {
        return lastEscalationLabel;
    }

    private String escalationFailure() {
        if (EscalationLadder.shared().enabled()) {
            return EscalationLadder.shared().lastFailure();
        }
        HelperModelTool fallback = planEscalationFallback;
        if (fallback != null && fallback.lastFailure() != null) {
            return fallback.lastFailure();
        }
        HelperModelTool primary = planEscalationTool;
        return primary == null ? null : primary.lastFailure();
    }

    private HelperModelTool planEscalation() {
        if (!planEscalationProbed) {
            planEscalationProbed = true;
            HelperEndpoint cfg = HelperEndpoint.named("plan", null, null, null);
            if (cfg != null && cfg.model() != null) {
                HelperModelTool tool = new HelperModelTool(cfg);
                planEscalationTool = tool.isAvailable() ? tool : null;
            }
        }
        return planEscalationTool;
    }

    /**
     * Local, single-model stall-breaker: when the loop guard has fired, the remote chain could not
     * author a different plan (down or disabled), and a SUCCESSFUL tool result already exists, ask the
     * LOCAL driver to answer straight from the accumulated context instead of re-running the same
     * action to the iteration cap. Returns the synthesized answer, or null on failure.
     */
    private String synthesizeFromResults(String userRequest, String context) {
        try {
            int helperContext = helperContextChars(8000);
            String prompt = "You have ALREADY gathered what you need. Using ONLY the CONTEXT SO FAR "
                    + "below (tool results already obtained this session), answer the user's request "
                    + "directly, concretely and completely, in plain prose. Do NOT propose, mention or emit any tool "
                    + "call — no JSON, no <|channel|> envelope — and do NOT ask a question: just give the answer.\n\nUSER REQUEST:\n"
                    + userRequest + "\n\nCONTEXT SO FAR:\n" + capTail(context, helperContext);
            // asked to answer from its results, the local driver emitted a Harmony tool
            // call (`<|channel|>commentary to=repo_browser.bash …`) twice - the "answer" became a covered read and then a
            // path with no verdict. A different model is asked first (the helper chain; in round C it was the one model
            // that ruled correctly); whichever answers, a tool call or a cut-off text is not an answer.
            // both syntheses of the turn came from the local helper - one flipped the ruling on a
            // claim the read lines contradict, one quoted code no tool had printed - while the strongest configured model
            // was never asked. The answer is the deliverable: the escalation model writes it when one is up, the helper
            // chain only when none is, the local driver last.
            if (anyEscalation() && route(ModelRouter.Point.SYNTHESIS) == ModelRouter.Route.CLOUD) {
                HostedBudget.note("SYNTHESIS", context, HostedBudget.charsFor(context));
                String reply = escalationComplete(EscalationLadder.Job.SYNTHESIS,
                        prompt.replace(capTail(context, helperContext), capTail(context, HostedBudget.charsFor(context))), 2000);
                if (isAnAnswerText(reply)) {
                    listener.onPlanRefined(false, "the answer was synthesized by " + escalationLabel());
                    return reply.strip();
                }
                listener.onPlanRefined(false, "synthesis: " + escalationLabel() + " returned no answer text: " + excerpt(reply)
                        + (escalationFailure() == null ? "" : " (" + escalationFailure() + ")"));   // F364
            }
            if (helpers.anyAvailable()) {
                HelperChain.Answer a = helpers.consult(prompt, "answer", null);
                if (a != null && isAnAnswerText(a.text())) {
                    listener.onPlanRefined(false, "the answer was synthesized by " + a.source());
                    return a.text().strip();
                }
                listener.onPlanRefined(false, "synthesis: the helper chain " + (a == null ? "returned nothing"
                        : "(" + a.source() + ") returned no answer text: " + excerpt(a.text())));   // F359
            }
            String answer = new ChatClient().getResponse(prompt);
            if (isAnAnswerText(answer)) {
                return answer.strip();
            }
            listener.onPlanRefined(false, "synthesis: the local model returned no answer text: " + excerpt(answer));
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The local critic refused `locate LibraryCatalog` - a symbol never looked up - as "the plan repeats a call that
     * already succeeded (Run CodeGraph)": every bare tool call of the driver carried the branch name "Run CodeGraph",
     * and the critic matched the name. Two review rounds and an escalation. A critic's repeat claim stands only when
     * some action of the plan really ran this turn (by signature, or as a covered locate).
     */
    private boolean anyActionRanThisTurn(String planJson) {
        if (planJson == null || ledgerLive == null) {
            return true;   // nothing to check against: the critic keeps its say
        }
        ParsedPlan parsed;
        try {
            parsed = planParser.parse(planJson);
        } catch (RuntimeException e) {
            return true;
        }
        if (parsed == null || parsed.branches() == null) {
            return true;
        }
        for (Branch b : parsed.branches()) {
            if (b.actions() == null) {
                continue;
            }
            for (Action a : b.actions()) {
                if (ledgerLive.executedSignatures.containsKey(signature(a))) {
                    return true;
                }
            }
            if (coveredLocateIssue(b.actions(), ledgerLive.executedSignatures) != null
                    || coveredReadIssue(b.actions(), ledgerLive.executedSignatures.keySet()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Runs the reads a refusal named and folds their results into the context, the evidence and the signatures. */
    private void runNamedRead(List<Action> actions, String note, int iteration, TurnSnapshot live, StringBuilder contextLog,
            StringBuilder successfulResults, Set<String> succeededTools) {
        listener.onPlanRefined(true, "the refusal names " + (actions.size() == 1 ? "a read — it runs" : actions.size() + " reads — they run")
                + " now: " + String.join(" ; ", actions.stream().map(a -> String.valueOf(a.parameters().get("command"))).toList()));
        listener.onBranchSelected("refusal-named read", 1.0, 1, actions);
        Map<String, Object> res = toolExecutor.executeActions(actions);
        listener.onToolResults(res);
        recordSignatures(actions, res, live.executedSignatures);
        res.forEach((name, value) -> {
            if (isSuccess(value)) {
                successfulResults.append(name).append(" => ").append(summarizeResults(Map.of(name, value))).append("\n");
                succeededTools.add(ToolExecutor.baseToolName(name));
            }
        });
        contextLog.append("\n[Iteration ").append(iteration).append("] Executed branch 'refusal-named read' (the read the refusal named ran for you). Tool results: ")
                .append(summarizeResults(res));
    }

    /** The deterministic plan guards, in their order, as one function of the actions (see the rows they cite). */
    private record PlanGate(String issue, List<Action> actions, boolean coveredRead, boolean resolvedPlaceholder) {}

    private PlanGate gatePlan(List<Action> actions, TurnSnapshot live, String contextSoFar, String request) {
        String repeat = repeatIssue(actions, live.executedSignatures);
        boolean coveredRead = false;
        if (repeat == null) {
            // a read of lines earlier reads already covered is a repeat, whatever its exact numbers
            repeat = coveredReadIssue(actions, live.executedSignatures.keySet(), live.lastRefusalWasCoveredRead, contextSoFar);
            coveredRead = repeat != null;
        }
        if (repeat == null) {
            repeat = parameterlessActionIssue(actions);   // F362
        }
        if (repeat == null) {
            repeat = shellSyntaxIssue(actions);   // supervised rewrite drive, FM-018
        }
        if (repeat == null) {
            repeat = oversizedReadIssue(actions, workbenchRoot());   // supervised rewrite drive, FM-003
        }
        if (repeat == null) {
            repeat = readThenWriteInOneCommandIssue(actions);   // supervised rewrite drive, FM-012
        }
        if (repeat == null) {
            repeat = inPlaceJavaEditIssue(actions);   // supervised rewrite drive, FM-021
        }
        if (repeat == null) {
            repeat = inventedTestMethodIssue(actions, toolResultsOnly(contextSoFar));   // F405
        }
        if (repeat == null) {
            repeat = bareFileNameReadIssue(actions, live.executedSignatures.keySet(), workbenchRoot());   // F385
        }
        if (repeat == null) {
            repeat = programToolInAnalysisIssue(actions, request);   // F372
        }
        if (repeat == null) {
            repeat = missingRequestItemsIssue(actions, request);   // rewrite drive, task 037b
        }
        if (repeat == null) {
            repeat = TaskPlan.laterRoundFirstIssue(request, actions, live.exactRoundsRun);   // rewrite drives t100a-t104
        }
        if (repeat == null) {
            repeat = unrequestedStubIssue(actions, request, workbenchRoot());   // rewrite drive, task 042a
        }
        if (repeat == null) {
            repeat = unscopedSearchIssue(actions);   // F371
        }
        if (repeat == null) {
            repeat = derivedPathReadIssue(actions);   // F397
        }
        if (repeat == null) {
            repeat = unknownActionIssue(actions);   // F399
        }
        if (repeat == null) {
            repeat = constantLocateIssue(actions);   // F411
        }
        if (repeat == null) {
            repeat = missingFileReadIssue(actions, workbenchRoot());   // F412
        }
        if (repeat == null) {
            repeat = unknownTypeLocateIssue(actions, toolResultsOnly(contextSoFar), workbenchRoot());   // F416
        }
        if (repeat == null) {
            repeat = oversizedReadIssue(actions, toolResultsOnly(contextSoFar));   // F422
        }
        if (repeat == null) {
            repeat = toolInShellIssue(actions, toolRegistry.names());   // F404
        }
        if (repeat == null) {
            repeat = scriptWritesJavaIssue(actions);   // rewrite drive, FM-103
        }
        boolean resolved = false;
        if (repeat == null) {
            // a placeholder for the one located file resolves to it; any other placeholder is refused
            List<Action> resolvedActions = resolvePlaceholders(actions, live.executedSignatures.keySet());
            if (resolvedActions != actions) {
                actions = resolvedActions;
                resolved = true;
            }
            repeat = placeholderCommandIssue(actions, live.executedSignatures.keySet());
        }
        if (repeat == null) {
            repeat = coveredLocateIssue(actions, live.executedSignatures);   // F341
        }
        if (repeat == null) {
            repeat = locatedByResultIssue(actions, toolResultsOnly(contextSoFar));   // F405
        }
        return new PlanGate(repeat, actions, coveredRead, resolved);
    }

    /** The index of the first shell step that writes a file after a shell step that reads one; 0 when there is none. */
    static final String READ_FIRST_MARK = "[Plan REJECTED — read first]";

    /** Whether the plan being made carries the edit items (see {@link #hostedEditStep}); set at the plan step. */
    private boolean hostedEditPlan;

    static final String HOSTED_EDIT_NOTE = "\n[Edit step] The reads the request names have run; their output is above. "
            + "Write the plan for the request's TreeCodegen call now: every item the request lists, each written from the "
            + "code read above with its exact names, then the request's Bash command.";

    static final String UNREAD_REWRITE_MARK = "[Plan REJECTED — rewrites unread code]";

    /**
     * An oversized read-only output that already ran is handed to the hosted ladder for an extract instead of being
     * cut, when the drive allows it (see {@link ReadHandoff}); the full output is kept with the step records.
     */
    private Map<String, Object> handoffOversizedReads(List<Action> actions, Map<String, Object> results) {
        if (!ReadHandoff.enabled()) {
            return results;
        }
        List<ReadHandoff.Note> notes = new ArrayList<>();
        Map<String, Object> out = ReadHandoff.apply(actions, results, currentRequest, readOutputCap(), ReadHandoff.roots(),
                workbenchRoot(), ReadHandoff.ladderAsker(3000), checkpoint.enabled() ? checkpoint.dir() : null, notes);
        for (ReadHandoff.Note n : notes) {
            listener.onPlanRefined(true, "the " + n.outputChars() + "-character output of " + n.key() + " was handed to "
                    + n.model() + " for a " + n.extractChars() + "-character extract"
                    + (n.fullOutput() != null ? " (full output: " + n.fullOutput() + ")" : ""));
        }
        return out;
    }

    private String currentPlanSummary = "the plan";
    private TurnSnapshot ledgerLive;
    private String currentLedgerBlock = "";   // the ledger as every plan-writing model sees it this iteration

    /** The listener every refusal reaches - it writes the refused plan and the first reason into the turn's ledger. */
    private final class LedgerListener implements AgentListener {
        private final AgentListener inner;

        LedgerListener(AgentListener inner) {
            this.inner = inner;
        }

        private void record(List<String> issues) {
            if (activity != null && issues != null && !issues.isEmpty()) activity.rejected(issues);
            if (ledgerLive == null || issues == null || issues.isEmpty()) {
                return;
            }
            String why = excerpt(issues.get(0).replaceAll("^\\[[^]]*]\\s*", ""), 220);
            String entry = currentPlanSummary + " — " + why;
            List<String> steps = ledgerLive.failedSteps;
            if (steps.isEmpty() || !steps.get(steps.size() - 1).equals(entry)) {
                steps.add(entry);
                if (steps.size() > 12) {
                    steps.remove(0);
                }
            }
        }

        @Override public void onInit(List<String> tools) { inner.onInit(tools); }
        @Override public void onIteration(int iteration, int maxIterations) { inner.onIteration(iteration, maxIterations); }
        @Override public void onPlanUnparsed(int iteration, String rawResponse) { inner.onPlanUnparsed(iteration, rawResponse); }
        @Override public void onPlanProposed(List<Branch> branches) { inner.onPlanProposed(branches); }
        @Override public void onPlanRefined(boolean changed, String note) { inner.onPlanRefined(changed, note); }
        @Override public void onPlanReview(boolean approved, List<String> issues) {
            if (!approved) {
                record(issues);
            }
            inner.onPlanReview(approved, issues);
        }
        @Override public void onAnswerGate(boolean approved, List<String> issues) {
            if (!approved) {
                record(issues);
            }
            inner.onAnswerGate(approved, issues);
        }
        @Override public void onBranchSelected(String name, double confidence, int outOf, List<Action> actions) { inner.onBranchSelected(name, confidence, outOf, actions); }
        @Override public void onToolResults(Map<String, Object> results) { inner.onToolResults(results); }
        @Override public void onFinalAnswer(String answer, int executedSteps) {
            if (activity != null) activity.answer(answer);
            if (ledgerLive != null) {
                ledgerLive.failedSteps.clear();   // an accepted answer closes the ledger
            }
            inner.onFinalAnswer(answer, executedSteps);
        }
        @Override public void onClarify(String question) { inner.onClarify(question); }
        @Override public void onHalted(String contextLog) { inner.onHalted(contextLog); }
        @Override public void onOptionalTool(String name, boolean available, String detail) { inner.onOptionalTool(name, available, detail); }
    }

    /** The second-reviewer reading of a refiner's flipped ruling; null when its claims hold or nobody could review. */
    private String reviewFlippedRuling(String evidence, String flipped) {
        String prompt = planPrompts.verdictCriticPrompt(capTail(evidence, VERDICT_CRITIC_EVIDENCE_CAP), capTail(flipped, 3000));
        boolean hosted = anyEscalation() && route(ModelRouter.Point.FLIP_REVIEW) == ModelRouter.Route.CLOUD;
        if (hosted) {
            HostedBudget.note("FLIP_REVIEW", evidence, HostedBudget.charsFor(evidence));
        }
        String reply = hosted ? escalationComplete(EscalationLadder.Job.FLIP_REVIEW, planPrompts.verdictCriticPrompt(
                capTail(evidence, HostedBudget.charsFor(evidence)), capTail(flipped, 12000)), 1500) : null;
        String source = reply != null ? escalationLabel() : null;
        if (reply != null) {   // the ladder moves the next review up when this one's approval is refused later
            String issue = AnswerGate.verdictCriticIssue(reply, source);
            EscalationLadder.shared().reviewed(EscalationLadder.Job.FLIP_REVIEW, issue == null,
                    routerSignals(ModelRouter.Point.FLIP_REVIEW).answerRefusals());
            return issue;
        }
        if (reply == null && helpers.anyAvailable()) {
            HelperChain.Answer a = helpers.consult(prompt, "answer", null);
            if (a != null) {
                reply = a.text();
                source = a.source();
            }
        }
        return reply == null ? null : AnswerGate.verdictCriticIssue(reply, source);
    }

    /**
     * Consult the helper chain (a local helper, then hosted ones) to fix a failing generated
     * program. Returns a corrected, complete program (fences stripped) or null.
     */
    private String consultOnProgramFailure(String request, String failure) {
        String prompt = "A small coding agent wrote a program that FAILED to compile or run. Fix it. "
                + "Return ONLY the corrected, COMPLETE program (no prose, no fences), valid and runnable, "
                + "using python3 conventions if Python (comments start with '#'). Keep it minimal.\n"
                + "CRITICAL: if the failure is an HTTP error / 404 / connection error / KeyError on a JSON "
                + "field, the ENDPOINT is wrong or DEAD — do NOT reuse the same URL/provider. Switch to a "
                + "DIFFERENT well-known public API and parse ITS actual response shape (e.g. for a crypto price, "
                + "Coinbase spot or CoinGecko simple/price — NOT a provider that just failed).\n\n"
                + "USER TASK: " + request + "\n\n" + failure;
        HelperChain.Answer a = helpers.consult(prompt, "answer", null);
        return a == null ? null : stripFences(a.text());
    }

    /**
     * Ask a helper to rewrite a weak final answer into an accurate, grounded one using
     * the results/context. Returns the improved answer, or null to keep the original -
     * including when the refiner is empty OR returns generic "provide your question"
     * FILLER (a chat model's response to a thin prompt), which must not clobber a real
     * answer. Only refine an answer that is substantive to begin with.
     */
    private String refineAnswer(String request, String contextSoFar, String answer) {
        if (answer == null || answer.strip().length() < 40) {
            return null;   // nothing substantive to improve → don't risk filler
        }
        HelperChain.Answer a = helpers.consult(
                planPrompts.refineAnswerPrompt(request, contextSoFar, capTail(answer, 1500)), "answer", null);
        if (a == null || a.text() == null || a.text().isBlank()) {
            return null;
        }
        lastRefinerSource = a.source();   // F344
        String improved = a.text().strip();
        if (isRefinerFiller(improved)) {
            return null;   // refiner produced a non-answer → keep the original
        }
        return improved;
    }

    /**
     * The final-answer execution gate. Returns a concrete issue if the proposed
     * terminal answer ignores the successful results (must re-answer), or null to
     * accept. A fast deterministic pre-check (clarify-after-success, asks-for-data
     * or placeholder signatures) runs first; otherwise an LLM gate judges whether
     * the answer actually reports the results. An unparseable/failed gate approves
     * (never blocks termination spuriously).
     */
    /** Whether the last issue {@link #answerGate} returned came from a deterministic check. */
    private boolean lastGateDeterministic = false;

    /** A remote-authored plan to run in place of the next local plan call. */
    private String pendingAuthoredPlan;

    // stepping mode - step checkpoints at the loop's seams (off unless -Dhermit.step.dir is set)
    private final Checkpoint checkpoint = Checkpoint.fromSystemProperties();
    private final OptionalReviewer shadow = OptionalReviewer.fromSystemProperties();   // records at the answer seam, never acted on
    private final AnswerAnnotation annotation = AnswerAnnotation.fromSystemProperties();   // beside every answer
    private int turnCounter = 0;

    /** Copies the loop's array-held and builder-held state into {@code live} before it is written. */
    private void mirror(TurnSnapshot live, int nextIteration, StringBuilder contextLog, StringBuilder successfulResults,
            Set<String> succeededTools, boolean[] committedThisSession, boolean[] testOutcomeObserved,
            boolean[] buildBroken, String[] pendingExpertProgram, String[] lastSuccessTool, String[] lastRefusalHint,
            Map<String, Object>[] lastSuccessResult, PlanNode node) {
        live.iteration = nextIteration;
        live.contextLog = contextLog.toString();
        live.successfulResults = successfulResults.toString();
        live.succeededTools = new ArrayList<>(succeededTools);
        live.committedThisSession = committedThisSession[0];
        live.testOutcomeObserved = testOutcomeObserved[0];
        live.buildBroken = buildBroken[0];
        live.pendingExpertProgram = pendingExpertProgram[0];
        live.lastSuccessTool = lastSuccessTool[0];
        live.lastRefusalHint = lastRefusalHint[0];
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = lastSuccessResult[0] == null ? null
                : (Map<String, Object>) TurnSnapshot.jsonSafe(lastSuccessResult[0]);
        live.lastSuccessResult = safe;
        live.history = new ArrayList<>(history);
        live.lastMemoryProgram = lastMemoryProgram;
        live.issueRounds = new LinkedHashMap<>(issueRounds);
        live.planEscalationProbed = planEscalationProbed;
        live.verdictCritics = verdictCritics;   // F343
        live.disputedClaims = new ArrayList<>(disputedClaims);   // F413
        live.editOriginals = TreeEditTool.originalsSnapshot();
        if (node != null) {
            live.nodeName = node.getNodeName();
            @SuppressWarnings("unchecked")
            Map<String, Object> ctx = (Map<String, Object>) TurnSnapshot.jsonSafe(node.getContext());
            live.nodeContext = ctx == null ? new LinkedHashMap<>() : ctx;
        }
    }

    private String answerGate(String successful, String answerText, boolean isClarify,
            Set<String> succeededTools) {
        return answerGate(successful, answerText, isClarify, succeededTools, true);
    }

    private String answerGate(String successful, String answerText, boolean isClarify,
            Set<String> succeededTools, boolean allowLlmGate) {
        lastGateDeterministic = true;
        String det = AnswerGate.deterministicAnswerGateIssue(answerText, isClarify, true);
        if (det != null) {
            return det;
        }
        // F173 / F219b / F227 / F228 / F236: the deterministic chain shared with the loop guard's synthesized answer,
        // fed both the rendered evidence and the tools that actually succeeded this turn
        String deterministic = AnswerGate.deterministicAnswerIssue(currentRequest, successful, answerText, succeededTools);
        if (deterministic != null) {
            return deterministic;
        }
        // "done" while round 2 of the request's exact rounds had never run
        List<Integer> notRun = ledgerLive == null ? List.of()
                : TaskPlan.exactRoundsNotRun(currentRequest, ledgerLive.exactRoundsRun);
        if (!notRun.isEmpty()) {
            return "Round" + (notRun.size() == 1 ? " " : "s ") + notRun + " of the request " + (notRun.size() == 1 ? "has" : "have")
                    + " NOT run: no TreeEdit action this turn sent exactly " + (notRun.size() == 1 ? "its" : "their")
                    + " items and succeeded. Send round " + notRun.get(0) + "'s items exactly as the request gives them; answer "
                    + "only after every round has run.";
        }
        // the accepted verdict cited "LibraryCatalog.addCopy (lines 75-84)" for a
        // duplicate check that the turn's only read of that file (lines 150-250) shows at 195 and 205 - line numbers no
        // read printed, and the second reviewer echoed them. A citation into a file this turn read must fall inside a
        // range it read; otherwise the answer is refused with the ranges that were read.
        if (ledgerLive != null) {
            String citation = uncoveredCitationIssue(answerText, ledgerLive.executedSignatures.keySet());
            if (citation != null) {
                return citation;
            }
        }
        // the verdict showed "the handler does:" and a fenced block in which
        // `if (existingCopy != null) { throw … }` stood for five real lines that go through findSameEdition(…) -
        // two of its three lines were the read's, one was invented. A quoted block that mixes printed lines with lines
        // no read printed is refused; a block introduced as a fix or a proposal is not a quotation and is left alone.
        String quote = paraphrasedQuoteIssue(answerText, successful);
        if (quote != null) {
            return quote;
        }
        // two reviewers disputed "EXPRESS rates the whole parcel including the gift wrapping" - no read line
        // showed it - and the THIRD verdict, carrying the same claim, passed: the reviewer budget
        // was spent and the same-model gate decided alone. A claim a reviewer disputed this turn is checked again for free.
        if (verdictCritics >= MAX_VERDICT_CRITICS && ReviewPanel.isVerdictAnswer(answerText) && !ClaimSession.enabled()) {
            String still = stillDisputedIssue(answerText, disputedClaims);
            if (still != null) {
                return still;
            }
        }
        lastGateDeterministic = false;
        // a verdict is read by a DIFFERENT model before the same-model gate - every
        // consequence it claims against the evidence; the local gate had approved one contradicted by its own quotation
        if (ReviewPanel.isVerdictAnswer(answerText)
                && reviewVerdict(verdictCritics, MAX_VERDICT_CRITICS, lastReviewedRuling, AnswerGate.verdictOf(answerText))) {
            String criticPrompt = planPrompts.verdictCriticPrompt(evidenceFor(successful, answerText, VERDICT_CRITIC_EVIDENCE_CAP), capTail(answerText, 3000));
            // the strongest model reviews a verdict when one is up; the local helper approved a TRUE POSITIVE and then flipped it
            HelperChain.Answer second = null;
            boolean hostedVerdictReview = false;
            if (anyEscalation() && route(ModelRouter.Point.VERDICT_REVIEW) == ModelRouter.Route.CLOUD) {
                int budget = HostedBudget.charsFor(successful);
                HostedBudget.note("VERDICT_REVIEW", successful, budget);
                String reply = escalationComplete(EscalationLadder.Job.VERDICT_REVIEW, planPrompts.verdictCriticPrompt(
                        evidenceFor(successful, answerText, budget), capTail(answerText, 12000)), 1500);
                if (reply != null && !reply.isBlank()) {
                    second = new HelperChain.Answer(escalationLabel(), reply);
                    hostedVerdictReview = true;
                }
            }
            if (second == null && helpers.anyAvailable()) {
                second = helpers.consult(criticPrompt, "answer", null);
            }
            // the helper had been marked down after a failed call, the second reviewer
            // silently did not run, and the verdict passed on the same-model gate alone. The plan-escalation model reviews
            // when the helper chain cannot; a verdict with no second reviewer at all is at least said so.
            if (second == null) {
                listener.onPlanRefined(false, "no second reviewer could be reached for the verdict — the same-model gate decides alone"
                        + (escalationFailure() == null ? "" : " (" + escalationFailure() + ")"));   // F357/F364
            }
            if (second != null) {
                String disputed = AnswerGate.verdictCriticIssue(second.text(), second.source(), answerText, currentRequest,
                        workbenchRoot());   // F436
                if (hostedVerdictReview) {   // an approval refused later moves the next review up the ladder
                    EscalationLadder.shared().reviewed(EscalationLadder.Job.VERDICT_REVIEW, disputed == null,
                            routerSignals(ModelRouter.Point.VERDICT_REVIEW).answerRefusals());
                }
                if (disputed != null) {
                    verdictCritics++;
                    lastReviewedRuling = AnswerGate.verdictOf(answerText);
                    if (ClaimSession.enabled()) {
                        // a dispute is a question for the evidence, not a verdict on the claim - the claim goes into the
                        // ledger OPEN with the reviewer's reason as a note, and the inspector decides it
                        for (String[] d : disputesOf(disputed)) {
                            Claim c = ClaimLedger.add(ClaimSession.ledger(), d[0],
                                    Claim.Origin.REVIEWER, routerIteration, null);
                            c.notes.add(second.source() + ": " + d[1]);
                            if (!c.decided()) {
                                c.status = Claim.Status.OPEN;
                            }
                        }
                    } else {
                        disputedClaims.addAll(ReviewPanel.disputedClaimsOf(disputed));   // F413
                    }
                    listener.onPlanRefined(false, "second reviewer (" + second.source() + ") disputes the verdict");
                    return disputed;
                }
                listener.onPlanRefined(false, "second reviewer (" + second.source() + ") finds the verdict's claims supported");
            }
        }
        if (!allowLlmGate || AnswerGate.isTheDictatedAnswer(currentRequest, answerText)) {
            return null;   // the answer the request dictates word for word is not the model gate's to judge
        }
        String verdict = coTReasoner.getRealLLMResponse(
                planPrompts.answerGatePrompt(successful, answerText == null ? "" : answerText, isClarify));
        try {
            int a = verdict.indexOf('{');
            int b = verdict.lastIndexOf('}');
            if (a < 0 || b <= a) {
                return null;
            }
            var node = mapper.readTree(verdict.substring(a, b + 1));
            if (node.has("approved") && !node.get("approved").asBoolean()) {
                StringBuilder sb = new StringBuilder();
                boolean complaint = false;
                int dropped = 0;
                for (var i : node.path("issues")) {
                    String t = i.asText().trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (ReviewPanel.gateOverrulesEvidence(t, successful)) {
                        dropped++;   // the gate disputing a term the results contain is not a rejection
                        continue;
                    }
                    if (CitationValidator.citesMissing(t, workbenchRoot(), answerText + "\n" + currentRequest)) {
                        dropped++;   // an objection resting on code that does not exist is dismissed
                        continue;
                    }
                    sb.append(sb.length() == 0 ? "" : " | ").append(t);
                    complaint |= ReviewPanel.looksLikeAComplaint(t);
                }
                if (sb.length() == 0 && dropped > 0) {
                    return null;   // every issue overruled evidence - approve
                }
                if (sb.length() > 0 && !complaint) {
                    return null;   // the gate restated the findings instead of judging - not a rejection
                }
                return sb.length() == 0 ? "The answer does not report the successful tool results." : sb.toString();
            }
        } catch (Exception ignored) {
            // unparseable gate -> approve (don't block termination)
        }
        return null;
    }

    /** Append the exact planning prompt sent to the brain (for inspecting memory/context). */
    private void dumpContext(String userRequest, int iteration, String planningPrompt) {
        if (contextDump == null) {
            return;
        }
        try {
            Files.writeString(contextDump,
                    "\n########## turn request=\"" + userRequest.replace("\n", " ")
                            + "\" iteration=" + iteration + " historyEntries=" + history.size()
                            + " ##########\n" + planningPrompt + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /**
     * Record a completed turn in conversation memory, trimming oldest entries
     * so the history stays within a character budget.
     */
    private void remember(String userRequest, String assistantLine) {
        history.add("User: " + userRequest);
        history.add(assistantLine);
        compressOlderHalfIfNeeded();
    }

    /**
     * When history exceeds the budget, COMPRESS the older half into a compact
     * summary (via the LLM) and keep the recent half verbatim - instead of
     * clamping/forgetting. Because an already-summarized older half gets
     * re-summarized on later compressions, distant turns decay exponentially
     * while their key facts keep surfacing.
     */
    private void compressOlderHalfIfNeeded() {
        int total = history.stream().mapToInt(String::length).sum();
        if (total <= historyCharBudget || history.size() < 4) {
            return;
        }
        int mid = history.size() / 2;
        List<String> older = new ArrayList<>(history.subList(0, mid));
        List<String> recent = new ArrayList<>(history.subList(mid, history.size()));
        String olderText = String.join("\n", older);
        int target = Math.max(200, olderText.length() / 10);   // aim for >=10x smaller
        String summary = coTReasoner.getRealLLMResponse(
                "Compress the earlier conversation below into a compact note of at most " + target
                + " characters, preserving the user's goals, decisions, chosen options, and concrete facts "
                + "(names, APIs, URLs, file paths). Output ONLY the note.\n\n" + olderText);
        summary = summary == null ? "" : summary.strip();
        history.clear();
        if (!summary.isEmpty() && !summary.equals("LLM_CALL_FAILED")) {
            history.add("[Earlier conversation summary] " + summary);
        } else {
            history.addAll(older);   // summarization failed: keep older rather than lose it
        }
        history.addAll(recent);
        // No clamp: compression already bounds size, and dropping the oldest would
        // discard the very summary that carries the important older facts. Repeated
        // compression re-summarizes the summary, giving exponential decay instead.
    }
}
