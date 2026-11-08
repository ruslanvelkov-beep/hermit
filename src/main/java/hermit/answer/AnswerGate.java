package hermit.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.analysis.graph.SpecConventions;
import hermit.evidence.CitationValidator;
import hermit.model.ChatClient;
import hermit.plan.RepeatGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks an answer passes before it ends a turn: an answer that cites a file, a hash or a test outcome no tool
 * result shows, one that only describes what it should have done, working notes or a surrender handed in as the
 * answer, a verdict the critics dispute, a quote the evidence does not hold; and the honesty note added to an answer
 * that claims a commit or a test run the turn did not make. Each check returns the issue to put to the planner, or null.
 */
public final class AnswerGate {

    private AnswerGate() {
    }

    /** Signals in a request that it needs REAL information a tool must fetch/inspect. */
    private static final Pattern NEEDS_TOOL = Pattern.compile(
            "(?i)https?://|#\\d+"
            + "|\\b[\\w./-]+\\.(java|kts|gradle|py|md|txt|json|xml|ya?ml|sh|ts|js|toml|cfg|properties)\\b"
            + "|\\b(take a look|look at|inspect|read|open|fetch|download|clone|run|execute|build|compile|"
            + "diff|grep|contents? of|does .{0,40} exist|what does .{0,60} (do|change|contain)|"
            + "check (if|whether)|this (pr|pull request|issue|repo|file|codebase|project))\\b"
            + "|\\b(search (the web|online|the internet)|look .{0,12} up online|google it|"
            + "(latest|newest|current) (version|release)|release notes|up[- ]to[- ]date)\\b"
            // Real-time / live data (prices, rates, quotes, weather, 'right now'/'today'):
            + "|\\b(current|live|real[- ]?time|today'?s|right now|as of (today|now))\\b.{0,40}"
            + "\\b(price|rate|quote|value|cost|exchange rate|weather|score|balance)\\b"
            + "|\\b(price|rate|quote|value) of \\w+");

    // A request to CONCLUDE from evidence ALREADY gathered - the answer is a synthesis of context, not a
    // from-memory guess, so the no-tool guard must NOT force another tool. (Also avoids the false trigger
    // where "do NOT run more tools" matches the bare word "run" in NEEDS_TOOL.)
    private static final Pattern CONCLUSION_INTENT = Pattern.compile(
            "(?i)\\b(do ?n'?o?t run (any )?(more )?tools?|no more tools|without running (any )?(more )?tools?"
            + "|you (already|now) have (all )?(the )?(evidence|data|results|findings|information)"
            + "|from (the )?context so far|based (solely )?on (the )?(evidence|analysis|findings|context)"
            + "(( you)? (already )?(have|gathered))?|give (your|the) (final )?(verdict|conclusion)"
            + " (from|based on) (the )?(context|evidence|analysis|findings))\\b");

    /**
     * The no-tool hallucination guard's pure check: returns a concrete issue if the
     * request plainly needs a tool (a URL/file/PR/inspection) - so a final answer
     * with no tool run is almost certainly hallucinated - or null if the request is
     * legitimately answerable directly (e.g. "who are you") or is a CONCLUSION drawn
     * from evidence already gathered. Unit-testable.
     */
    public static String noToolAnswerIssue(String userRequest) {
        return noToolAnswerIssue(userRequest, null);
    }

    /**
     * the guard exists to stop a FROM-MEMORY answer. When the context already carries tool
     * FINDINGS - a {@code File.java:line}, a reach/emulator verdict, a listed candidate - a report request is a
     * synthesis of that evidence, and forcing another tool call wastes a step (the eval case answered, was rejected
     * here, and re-planned into a redundant CodeGraph call).
     */
    public static String noToolAnswerIssue(String userRequest, String contextSoFar) {
        if (userRequest == null || !NEEDS_TOOL.matcher(userRequest).find()) {
            return null;
        }
        if (CONCLUSION_INTENT.matcher(userRequest).find()) {
            return null;   // concluding from already-gathered evidence - not a from-memory hallucination
        }
        if (hasGatheredEvidence(contextSoFar)) {
            return null;
        }
        return "You produced a final answer WITHOUT running any tool, but the request needs real information "
                + "(a URL/file/PR/command to inspect, or current/external info to look up) that you cannot know "
                + "from memory. Inspect the local target with the right tool, or use WebSearch for external/"
                + "current info — then answer from the result.";
    }

    /** Findings a tool produced: a file:line, a reach/emulator verdict, a dispatch label, a ranked candidate, a
     *  test/record count. Prose alone never matches. */
    private static final Pattern GATHERED_EVIDENCE = Pattern.compile(
            "(?i)(\\w+\\.java:\\d+|\\bREACHABLE\\b|NOT REACHABLE|NO HANDLER|specReachable=|reachable=|"
            + "\\[(declaration|impl|exact|inherited|name-only)\\]|PREDICTED (RED|GREEN)|\\bUNDECIDED\\b|"
            + "score=\\d|overlap=\\d|catches=\\[|escapes=\\[|tests=\\[|callees=\\[|callers=\\[|"
            + "\\bstatus=Success\\b|\\d+ (records?|tests? (completed|passed|failed)))");

    /** True when {@code context} contains findings a tool already produced (see {@link #GATHERED_EVIDENCE}). */
    public static boolean hasGatheredEvidence(String context) {
        return context != null && GATHERED_EVIDENCE.matcher(context).find();
    }

    // A git commit hash the answer PRESENTS as fact (7-40 hex, not obviously a placeholder run of one char).
    private static final Pattern COMMIT_HASH = Pattern.compile(
            "\\b[0-9a-f]{7,40}\\b");
    // The CLAIM phrasing - "commit hash: X", "commit: X", "committed as X", "<...> commit: X" - followed by
    // a hash-shaped token. Catches FABRICATED hashes that aren't valid hex (g/h/i/j placeholders), which
    // the hex pattern alone misses.
    private static final Pattern COMMIT_CLAIM = Pattern.compile(
            "(?i)commit(\\s+hash)?\\s*(is|:|=|was|as)?\\s*[`\"']?[0-9a-z]{6,}"
            + "|committed\\s+(as|in|at)\\s+[`\"']?[0-9a-z]{6,}");
    /** the token a "commit hash: X" / "committed as X" claim names. */
    private static final Pattern CLAIMED_HASH = Pattern.compile(
            "(?i)(?:commit(?:\\s+hash)?\\s*(?:is|:|=|was|as)?|committed\\s+(?:as|in|at))\\s*[`\"']?([0-9a-zA-Z]{6,})");
    /** The one unfakeable marker of a commit in a tool output: "[branch abc1234] message". */
    private static final Pattern COMMIT_LINE_HASH = Pattern.compile(
            "\\[[^\\]\\n]+\\s([0-9a-f]{7,40})\\]");
    // Claims of a concrete build/test verdict.
    /** a bare "pass(es)" was a verdict - "objects that pass `sameEntry`", "the caller
     *  blindly passes this ID", "passes it to `mergeEntry`" refused two verdicts and put a false honesty note on
     *  a third. Pass or fail is a test verdict only with a test, spec, suite or build as its subject, or as
     *  "now passes". */
    private static final Pattern TEST_VERDICT = Pattern.compile(
            "(?i)\\b(BUILD SUCCESSFUL|BUILD FAILED|(?:tests?|specs?|suites?|builds?)\\s+(?:now\\s+|all\\s+|still\\s+)?"
            + "(?:pass|passe[ds]|fail(?:s|ed)?)|is (now )?(green|red)|turned? green|went green|now pass(?:es|ed)?|goes green)\\b");

    /**
     * ANTI-CONFABULATION guard (pure): a terminal answer must not PRESENT specifics it never produced.
     * If the answer cites git commit hashes but no commit actually succeeded this session, or claims a
     * build/test verdict but no test/emulator outcome was ever observed, return an issue; else null.
     * {@code committed}/{@code testObserved} are ground-truth flags the agent sets from real tool results.
     */
    private static final Pattern TEST_NAME = Pattern.compile("\\btest[A-Z][A-Za-z0-9_]{3,}\\b");
    private static final Pattern SPEC_NAME = Pattern.compile("\\b[A-Z][A-Za-z0-9_]+#[a-z][A-Za-z0-9_]+\\b");

    /**
     * identifiers the answer PRESENTS AS FINDINGS must have come from a tool this turn (or from the
     * request itself). A run "found" two test methods that no tool ever returned. Returns the issue, or null.
     */
    private static final Pattern PROPOSAL_LEAD = Pattern.compile(
            "(?i)\\b(a new (?:method|test)|new (?:method|test) (?:like|named|called)|name[d]? (?:it|like)|call it"
            + "|could be (?:named|called)|will be (?:named|called)|to be created|such as|e\\.?g\\.?|for example"
            + "|propose[d]? (?:a|the)?|suggest[ed]? (?:a|the)?|something like)\\b[^.]{0,40}$");

    /** a NEW test's proposed name is not a finding - "a unit test … can be created … a new method like
     *  'testX'" was flagged as invented. Looks at the 90 characters before the identifier for proposal wording. */
    static boolean introducedAsAProposal(String answer, int at) {
        String before = answer.substring(Math.max(0, at - 90), at);
        return PROPOSAL_LEAD.matcher(before).find();
    }

    /**
     * the rewritten verdict grounded its third point on `ZonePlanExecutor` - a class no
     * tool result had named and that does not exist; the fabricated-name check knew test and spec names, not types. A CamelCase
     * type name with a code suffix (Handler, Calculator, Impl, Store, …) that no result and not the request contains is invented.
     */
    static final Pattern TYPE_NAME = Pattern.compile(
            "\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+(?:Handler|Calculator|Service|Impl|Store|Manager|Test|Utility|Utils|Context|Workflow|"
            + "Dispatcher|Verifier|Builder|Factory|Config|Processor|Provider|Registry|Validator|Executor|Scheduler)\\b");

    public static String fabricatedIdentifiersIssue(String answer, String evidence, String request) {
        if (answer == null || evidence == null) {
            return null;
        }
        String known = (evidence + "\n" + (request == null ? "" : request));
        List<String> unseen = new ArrayList<>();
        for (Pattern pat : new Pattern[] {TEST_NAME, SPEC_NAME, TYPE_NAME}) {
            Matcher m = pat.matcher(answer);
            while (m.find()) {
                String id = m.group();
                if (pat == TYPE_NAME && m.end() < answer.length() && (answer.charAt(m.end()) == '#'
                        || (answer.charAt(m.end()) == '.' && m.end() + 1 < answer.length() && Character.isLowerCase(answer.charAt(m.end() + 1))))) {
                    continue;   // the type of a Type#member spec name - SPEC_NAME judges it by its member
                }
                String method = id.contains("#") ? id.substring(id.indexOf('#') + 1) : id;
                if (!known.contains(id) && !known.contains(method) && !unseen.contains(id)
                        && !introducedAsAProposal(answer, m.start())) {
                    unseen.add(id);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!unseen.isEmpty()) {
            sb.append("Your answer presents ").append(unseen).append(" as findings, but NO tool returned these "
                    + "names this turn — they look invented. Verify them (CodeGraph locate / LocateFile / grep) or "
                    + "drop them. ");
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        boolean claimsSpecReach = lower.contains("spec verb") && lower.contains("reach")
                && !lower.matches("(?s).*\\b(no handler|does not reach|not reach|no spec verb|not (been )?determined|"
                        + "undetermined|unknown whether|cannot (be )?reach|is unknown|not yet (known|verified|analy[sz]ed))\\b.*");
        boolean ranReach = evidence.contains("SpecTestComposer") || evidence.contains("specreach")
                || evidence.contains("specReachable");
        if (claimsSpecReach && !ranReach) {
            sb.append("Your answer claims spec verbs REACH the code, but no spec reach analysis (SpecTestComposer "
                    + "action=reach / CodeGraph action=specreach) ran this turn — run it or state it is unknown.");
        }
        return sb.isEmpty() ? null : sb.toString().strip();
    }

    private static final Pattern LINE_ADDRESS = Pattern.compile(
            "([A-Za-z_][\\w$]*\\.java):(\\d+)");

    /**
     * the planner hard-coded {@code FeedImporter.importAll:212} in its FIRST
     * plan - no tool had run, so the line was invented (it happened to be right). Two critic rounds were spent
     * refusing it. Returns an issue when an action carries a {@code File.java:LINE} address whose line number appears
     * nowhere in the context; null when the address is grounded (or none is used).
     */
    public static String inventedLineAddressIssue(String parametersText, String contextSoFar) {
        if (parametersText == null) {
            return null;
        }
        Matcher m = LINE_ADDRESS.matcher(parametersText);
        while (m.find()) {
            String file = m.group(1);
            String line = m.group(2);
            String ctx = contextSoFar == null ? "" : contextSoFar;
            boolean grounded = ctx.contains(file + ":" + line)
                    || (ctx.contains(file) && ctx.matches("(?s).*\\b" + line + "\\b.*"));
            if (!grounded) {
                return "Your plan addresses " + file + ":" + line + ", but that line number appears nowhere in "
                        + "CONTEXT — it is a GUESS. Get the real location first (CodeGraph action=locate for a "
                        + "method, action=catches for a catch clause) and use the line from that result.";
            }
        }
        return null;
    }

    /**
     * the answer cited "REAL commit hash: 01a2b3c4d5e6f7g8h9i0j1k2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z" - 52
     * characters, letters beyond f - while no commit had happened. The claim pattern's upper bound and word boundary
     * let it through, and nothing compared a cited hash with the commit line the evidence holds.
     */
    public static String hashClaimIssue(String answer, String evidence) {
        if (answer == null) {
            return null;
        }
        Matcher c = CLAIMED_HASH.matcher(answer);
        String realHash = null;
        if (evidence != null) {
            Matcher r = COMMIT_LINE_HASH.matcher(evidence);
            while (r.find()) {
                realHash = r.group(1);   // the latest commit line wins
            }
        }
        while (c.find()) {
            String claimed = c.group(1);
            if (!claimed.matches(".*\\d.*") && !claimed.matches("(?i)[0-9a-f]+")) {
                continue;   // a word after "commit" ("the commit protocol"), not a cited hash (FM-017)
            }
            if (!claimed.matches("[0-9a-f]{7,40}")) {
                return "The commit hash you cite ('" + (claimed.length() > 20 ? claimed.substring(0, 20) + "…" : claimed)
                        + "') is not a git hash. Report the REAL hash from the `git commit` output ([branch abc1234] …)"
                        + (realHash == null ? ", and if no commit ran, say so." : ": it is " + realHash + ".");
            }
            if (realHash != null && !(realHash.startsWith(claimed) || claimed.startsWith(realHash))) {
                return "The commit hash you cite (" + claimed + ") is not the one the `git commit` output shows (" + realHash
                        + "). Report the real hash.";
            }
        }
        return null;
    }

    public static String fabricationIssue(String answer, boolean committed, boolean testObserved) {
        return fabricationIssue(answer, committed, testObserved, "");
    }

    /**
     * the confirmation request itself named the commit (3415d4947c, made in an earlier session) and
     * the answer that cited it was called a fabrication because no commit ran THIS session. A hash that appears in the
     * request or in a tool output (git log, rev-parse, a commit line) is grounded, whether or not this session made it.
     */
    public static String fabricationIssue(String answer, boolean committed, boolean testObserved, String grounding) {
        if (answer == null) {
            return null;
        }
        boolean citesHash = COMMIT_CLAIM.matcher(answer).find();
        if (!citesHash) {
            var m = COMMIT_HASH.matcher(answer);
            while (m.find()) {
                // ignore a monotone placeholder like "0000000" (single repeated char)
                if (m.group().chars().distinct().count() > 2) {
                    citesHash = true;
                    break;
                }
            }
        }
        if (citesHash && !committed && grounding != null) {
            boolean allGrounded = true;
            int seen = 0;
            Matcher claimed = CLAIMED_HASH.matcher(answer);
            while (claimed.find()) {
                seen++;
                if (!grounding.contains(claimed.group(1))) {
                    allGrounded = false;
                }
            }
            if (seen == 0) {
                Matcher hex = COMMIT_HASH.matcher(answer);
                while (hex.find()) {
                    if (hex.group().chars().distinct().count() > 2) {
                        seen++;
                        if (!grounding.contains(hex.group())) {
                            allGrounded = false;
                        }
                    }
                }
            }
            if (seen > 0 && allGrounded) {
                citesHash = false;   // every cited hash is one the request or a tool output holds
            }
        }
        if (citesHash && !committed) {
            return "Your answer cites a git commit hash, but NO commit succeeded this session (no Bash "
                    + "'git commit' returned a commit line). Do not fabricate hashes or claim work you did "
                    + "not do. Either actually create the commit with a Bash git commit, or state plainly "
                    + "that the task is INCOMPLETE and what remains.";
        }
        if (TEST_VERDICT.matcher(answer).find() && !testObserved) {
            return "Your answer claims a build/test verdict (pass/fail/green/red), but no test run or "
                    + "emulator prediction was actually observed this session. Do not fabricate results. "
                    + "Run the test (or the emulator) and report the REAL outcome, or say the task is "
                    + "INCOMPLETE.";
        }
        return null;
    }

    /** A question whose answer is a volatile fact (a version/date/"latest" X) that a cached snippet gets wrong. */
    private static final Pattern CURRENT_FACT = Pattern.compile(
            "(?i)\\b(latest|newest|current|most recent)\\b.{0,40}\\b(version|release|stable)\\b"
            + "|\\b(version|release)\\b.{0,20}\\b(number|is|of)\\b.{0,40}\\?"
            + "|release notes|when (was|is) .{0,40} released|what'?s? the .{0,20}version");

    /**
     * The web-read guard's pure check: for a current-fact question, if a WebSearch SEARCH
     * ran but no page was READ, the answer would rest on cached (often stale) snippets -
     * return an issue demanding a read; otherwise null. Unit-testable, no LLM.
     */
    public static String webReadRequiredIssue(String userRequest, boolean webSearchRan, boolean webReadRan) {
        if (userRequest == null || webReadRan || !webSearchRan) {
            return null;
        }
        if (!CURRENT_FACT.matcher(userRequest).find()) {
            return null;
        }
        return "This is a current-fact question (a version/date/'latest' value). A WebSearch SEARCH ran but no "
                + "page was READ, so any answer would rest on cached, frequently STALE snippets. You MUST read "
                + "the most authoritative result page (the project's official site or its GitHub 'releases' page) "
                + "and answer strictly from it.";
    }

    /** Placeholder / asking-for-what-you-have signatures in a proposed terminal answer. */
    private static final Pattern ASKS_FOR_DATA = Pattern.compile(
            "(?i)(please\\s+)?(provide|give me|send me|share|specify|supply)\\b[^.]{0,60}"
            + "\\b(url|link|pr|pull request|issue|number|repo|reference|path|value)\\b"
            + "|i\\s+need\\s+(you\\s+to\\s+)?(provide|know|the)\\b"
            + "|<\\s*(exact[-_ ]?)?(url|link|pr[-_ ]?url|path|value|id)\\s*>");

    /**
     * DETERMINISTIC pre-check for the final-answer execution gate: given a proposed
     * TERMINAL response and whether at least one tool already SUCCEEDED this turn,
     * returns a concrete issue string if the answer is obviously ignoring that
     * result (a clarify after a successful fetch, or an answer that asks for /
     * uses a placeholder for data already obtained), or null to defer to the LLM
     * gate. Pure and unit-testable - no LLM call.
     */
    private static final Pattern RECORDING_EVIDENCE = Pattern.compile(
            "(?i)recordingDir=|records: \\d+|throwAndReturnEvents");
    private static final Pattern ANSWER_CITES_RUNTIME = Pattern.compile(
            "(?i)\\b(record\\w*|runtime|trace[ds]?|at run ?time|hindsight|seq \\d+|threw|thrown|swallow\\w*|propagat\\w*)\\b");

    /**
     * the agent recorded two existing tests with the runtime debugger - one trace PROVED the
     * fail-open catch swallowing a NullPointerException - and then reported neither. A recording is the most expensive
     * and most convincing evidence a turn can produce; an answer that ignores one is incomplete.
     */
    /**
     * the request asked to COMMIT the failing test; the turn generated it (TreeCodegen Success)
     * and ran it, then answered without any git commit having run. The answer is incomplete: the commit is a step.
     */
    /**
     * the generator had already refused a repeat with "alreadyGenerated … COMMIT it", and the planner
     * still planned the same generation a third time. A plan that generates a method the context shows as already
     * generated is rejected at the plan gate, with the commit named as the step that is actually missing.
     */
    public static String regenerationIssue(String planJson, String contextSoFar) {
        if (planJson == null || contextSoFar == null || !planJson.contains("TreeCodegen")) {
            return null;
        }
        Matcher done = Pattern
                .compile("([A-Za-z_][\\w.]*#\\w+) was ALREADY generated")
                .matcher(contextSoFar);
        Set<String> generated = new HashSet<>();
        while (done.find()) {
            String spec = done.group(1);
            generated.add(spec.contains("#") ? spec.substring(spec.indexOf('#') + 1) : spec);
        }
        // every successful TreeCodegen result block (either rendering) names the generated method in its focus
        String rest = contextSoFar;
        for (String block = successfulResultBlock(rest, Set.of("TreeCodegen")); block != null;
                block = successfulResultBlock(rest, Set.of("TreeCodegen"))) {
            Matcher focus = Pattern
                    .compile("focus=.{0,400}?\\bvoid (\\w+)\\(", Pattern.DOTALL).matcher(block);
            if (focus.find()) {
                generated.add(focus.group(1));
            }
            int at = rest.indexOf(block);
            rest = rest.substring(at + block.length());
        }
        if (generated.isEmpty()) {
            return null;
        }
        Matcher planned = Pattern
                .compile("\"method\"\\s*:\\s*\"([^\"]+)\"|\\bmethod=([^,}\\s]+)").matcher(planJson);
        while (planned.find()) {
            String spec = planned.group(1) != null ? planned.group(1) : planned.group(2);
            String name = spec.contains("#") ? spec.substring(spec.indexOf('#') + 1) : spec;
            if (generated.contains(name)) {
                // the commit had already happened and the gate still demanded it - the planner
                // re-planned the generation instead of answering
                Matcher commit = Pattern.compile("\\[[^\\]\\n]+\\s([0-9a-f]{7,40})\\]")
                        .matcher(contextSoFar);
                String hash = null;
                while (commit.find()) {
                    hash = commit.group(1);
                }
                if (hash != null) {
                    return "This plan generates " + spec + " AGAIN, but the context shows it was already generated, run and "
                            + "COMMITTED (" + hash + "). Nothing is missing: answer now (Shape B) with the real gradle outcome, "
                            + "the recording evidence and the commit hash " + hash + ".";
                }
                return "This plan generates " + spec + " AGAIN, but the context shows it was already generated (and it "
                        + "compiles and runs). Regenerating can only degrade it. The step still missing is the COMMIT: "
                        + "plan `git add <the test file> && git commit -s -m '<message>'` (local only), then report the hash.";
            }
        }
        return null;
    }

    /**
     * the request said "make the minimal production fix … confirmed by the real gradle
     * run"; after ONE failed edit the answer DESCRIBED the fix ("replace X with Y … the run should now pass") and did
     * nothing. A request to change code is not answered by a description: some edit must have succeeded this turn.
     */
    /**
     * the loop guard's synthesized answer returned WITHOUT the deterministic gates and a stage
     * ended with the fix merely described. Every terminal answer - planned, synthesized or refined - goes through
     * the same deterministic chain: ignored recording, missing commit, described-not-done.
     */
    public static String deterministicAnswerIssue(String request, String successfulEvidence, String answer) {
        return deterministicAnswerIssue(request, successfulEvidence, answer, null);
    }

    /**
     * the gates below read the RENDERED evidence, and the agent renders a result as
     * {@code Tool={status=Success, …}} (summarizeResults) while the listener log renders {@code Tool: {…}}. The
     * patterns knew only the log form, so in production the commit gate never fired, the regeneration gate
     * matched nothing but the generator's own phrase, and the described-not-done gate flagged a turn
     * whose edit, green run and commit had all succeeded - sending the driver into a redundant plan. Two repairs:
     * result blocks are located by either rendering (and scoped to the next block, not the next brace, so a value
     * containing braces cannot hide the status), and the agent passes the tools that actually succeeded this turn
     * ({@code succeededTools}, taken from the result maps) as a rendering-independent second signal.
     */
    public static String deterministicAnswerIssue(String request, String successfulEvidence, String answer,
            Set<String> succeededTools) {
        String issue = claimedArtifactMissingIssue(answer);
        if (issue != null) {
            return issue;
        }
        issue = ignoredRecordingIssue(answer, successfulEvidence);
        if (issue != null) {
            return issue;
        }
        issue = uncommittedGenerationIssue(request, successfulEvidence, succeededTools);
        if (issue != null) {
            return issue;
        }
        issue = describedNotDoneIssue(request, successfulEvidence, answer, succeededTools);
        if (issue != null) {
            return issue;
        }
        issue = unconfirmedOutcomeIssue(request, successfulEvidence);
        if (issue != null) {
            return issue;
        }
        issue = claimedTestPassIssue(answer, successfulEvidence);
        if (issue != null) {
            return issue;
        }
        issue = quotedCodeNotInEvidenceIssue(answer, successfulEvidence);
        if (issue != null) {
            return issue;
        }
        issue = misattributedToolFactsIssue(answer, successfulEvidence);
        if (issue != null) {
            return issue;
        }
        return hashClaimIssue(answer, successfulEvidence);
    }

    private static final Pattern CODE_FENCE = Pattern.compile("```[a-zA-Z]*\\n(.*?)```", Pattern.DOTALL);

    /**
     * the verdict quoted a `handle` snippet with a symbol that exists nowhere
     * (CacheErrorEntryAlreadyPresent) and the report's line numbers, as if read from the
     * file. Code the answer presents in a fence as the repository's must have been READ: every distinctive line of a
     * quoted block (an identifier-bearing line of 12+ chars) must occur in a tool result.
     */
    public static String quotedCodeNotInEvidenceIssue(String answer, String evidence) {
        if (answer == null || evidence == null) {
            return null;
        }
        String flat = evidence.replaceAll("\\s+", " ");
        Matcher f = CODE_FENCE.matcher(answer);
        while (f.find()) {
            String block = f.group(1);
            List<String> lines = new ArrayList<>();
            for (String line : block.split("\\n")) {
                String t = line.strip();
                if (t.length() >= 12 && t.matches(".*[A-Za-z_]\\w*\\s*[(.=].*") && !t.startsWith("//") && !t.startsWith("*")
                        && !t.startsWith("@") && !t.equals("}")) {
                    lines.add(t);
                }
            }
            if (lines.size() < 2) {
                continue;   // a one-liner or prose in a fence: not a claimed quotation
            }
            int missing = 0;
            List<String> examples = new ArrayList<>();
            for (String t : lines) {
                String key = t.replaceAll("\\s+", " ").replaceAll("^(?:return |throw |final |var |\\.)", "");
                if (key.length() > 24) {
                    key = key.substring(0, 24);
                }
                if (!flat.contains(key)) {
                    missing++;
                    if (examples.size() < 2) {
                        examples.add(t);
                    }
                }
            }
            if (missing > lines.size() / 2) {
                return "Your answer quotes code as the repository's that no tool result contains (e.g. " + examples
                        + ") — quote only lines you actually read (Bash sed/cat output, CodeGraph) and cite this "
                        + "checkout's file:line, or drop the quotation.";
            }
        }
        return null;
    }

    private static final Pattern ANSWER_IMPORT = Pattern.compile(
            "import\\s+(static\\s+)?([A-Za-z_][\\w.]*?)(?:\\.\\*)?\\s*;");
    private static final Pattern IMPORTS_ADDED = Pattern.compile("importsAdded=\\[([^\\]]*)\\]");
    private static final Pattern NEXT_COMMAND = Pattern.compile("next=run it for real: ([^\\n}]*?)(?:,\\s*\\w+=|\\}|$)");

    /**
     * The answer said "Imports added by the composer:" and listed four static imports the composer never added, and
     * "Gradle command returned by the composer" with a command the composer never returned. A fact the answer
     * attributes to a tool must be the tool's fact; the test annotation and the runner a composed suite imports are
     * the project's (its spec conventions), not facts of the composer.
     */
    public static String misattributedToolFactsIssue(String answer, String evidence) {
        if (answer == null || evidence == null) {
            return null;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        Matcher added = IMPORTS_ADDED.matcher(evidence);
        boolean listsImports = lower.matches("(?s).*imports? (?:added|returned|reported|produced) by the (?:composer|tool).*")
                || lower.contains("imports_added") || lower.contains("importsadded")
                || lower.matches("(?s).*\\bimports? (?:added|used|needed|required)\\b.*") || lower.matches("(?s).*imports:.*");
        if (added.find() && listsImports) {
            String real = added.group(1);
            SpecConventions conventions = SpecConventions.load();
            List<String> wrong = new ArrayList<>();
            Matcher m = ANSWER_IMPORT.matcher(answer);
            while (m.find()) {
                String fqn = m.group(2);
                if (!real.contains(fqn) && !fqn.startsWith("java.") && !fqn.startsWith("org.junit.")
                        && !fqn.endsWith(conventions.testAnnotation()) && !fqn.endsWith(conventions.runner())) {
                    wrong.add((m.group(1) == null ? "" : "static ") + fqn);
                }
            }
            if (!wrong.isEmpty()) {
                return "Your answer attributes imports to the composer that it did not add " + wrong + " — the imports the "
                        + "composer added are exactly: [" + real + "]. Report those.";
            }
        }
        Matcher next = NEXT_COMMAND.matcher(evidence);
        if (next.find() && (lower.matches("(?s).*(?:gradle )?command (?:returned|reported|given|provided) by the (?:composer|tool).*")
                || lower.contains("gradle_command") || lower.matches("(?s).*gradle command.*"))) {
            String realCmd = next.group(1).strip();
            String core = realCmd.replaceAll("\\s+", " ");
            Matcher cmd = Pattern.compile("(\\./gradlew[^\\n`]*)").matcher(answer);
            boolean matched = false;
            while (cmd.find()) {
                String said = cmd.group(1).strip().replaceAll("\\s+", " ");
                if (core.startsWith(said) || said.startsWith(core) || said.contains("--tests")) {
                    matched = true;
                }
            }
            if (cmd.reset().find() && !matched) {
                return "Your answer attributes a gradle command to the composer that it did not return — the composer's command was: "
                        + realCmd + ". Report that one.";
            }
        }
        return null;
    }

    /**
     * the request said "run the real gradle test to confirm RED"; the turn generated the test,
     * recorded it, and was about to commit and report RED on the strength of the emulator's prediction - the test
     * was green. When the request asks for the real gradle outcome, the evidence must hold a real run.
     */
    /** the coaching a refusing tool spelled out - the exact next step the author was given. */
    private static final Pattern REFUSAL_HINT = Pattern.compile(
            "closest known verbs?: \\[[^\\]]*\\]|DECLARE the values[^\\]]*\\]|\\w+'s overloads: [^;\\]]*(;[^;\\]]*)?|"
            + "the verbs that DO reach it: \\[[^\\]]*\\][^\\]]*|e\\.g\\. [a-zA-Z]\\w*\\([^)]*\\)[^,\\]]*");

    /** the coaching fragment of a FAILED tool result - what the refusal told the author to do
     *  next (the catalogue verb closest to an unknown one, the `declare` field, a verb's overloads, the reaching
     *  verbs, a concrete example op) - or null when the failure carries no such step. */
    public static String refusalHint(Object failedResult) {
        if (failedResult == null) {
            return null;
        }
        String text = String.valueOf(failedResult);
        Matcher m = REFUSAL_HINT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find() && sb.length() < 400) {
            sb.append(sb.length() == 0 ? "" : "; ").append(m.group().strip());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static final Pattern SURRENDER = Pattern.compile(
            "(?i)\\b(has not been (fully )?(achieved|completed|accomplished)|remains? incomplete|(task|test) (is|remains) incomplete|"
            + "\\bINCOMPLETE\\b|requires? further (action|work|steps)|could not be (completed|composed|achieved)|"
            + "not (yet )?(been )?(possible|able) to (compose|complete|create|write)|(the )?following steps should be taken)");

    /** three identical refusals, each naming the catalogue verb to use, and at iteration 10 of 20
     *  the planner gave up with "the task remains INCOMPLETE … the following steps should be taken", which the LLM gate
     *  accepted. A final answer that declares the work incomplete while turns remain and the last refusal spelled out
     *  the next step is not an answer - it is the next plan, unwritten. */
    public static String surrenderIssue(String answer, String lastRefusalHint, int iterationsLeft) {
        if (answer == null || lastRefusalHint == null || iterationsLeft <= 0 || !SURRENDER.matcher(answer).find()) {
            return null;
        }
        return "Your answer declares the task incomplete, but " + iterationsLeft + " iteration(s) remain and the last "
                + "refusal spelled out the next step: " + lastRefusalHint + ". Apply that step in a new plan (same "
                + "tool, corrected parameters) instead of stopping; only report incomplete when no step is left.";
    }

    public static String unconfirmedOutcomeIssue(String request, String evidence) {
        if (request == null || evidence == null) {
            return null;
        }
        String req = request.toLowerCase(Locale.ROOT);
        boolean asksForRun = req.matches("(?s).*\\b(real gradle|gradle (test|run|outcome)|run the (real |module'?s? )?(gradle )?test|"
                + "confirm(ed)? (it is |that it is )?(red|green))\\b.*");
        // the preface recalled an earlier stage's "real gradle run" while the stage itself
        // said "do not run the spec yourself" - a request that forbids running is not a request for a run
        boolean forbidsRun = req.matches("(?s).*\\b(do not run|don'?t run|without running|not run the spec)\\b.*");
        if (!asksForRun || forbidsRun) {
            return null;
        }
        if (ranATest(evidence)) {
            return null;
        }
        return "The request asks for the REAL gradle outcome of the test, and no gradle test run happened this turn — the "
                + "emulator's prediction is not a run. Run `./gradlew <module>:test --tests <TestClass.method>` (Bash) now "
                + "and report its exit code and failure text before answering.";
    }

    /** Whether this turn's results show a test run: a test outcome, a build summary or a gradle test command. */
    static boolean ranATest(String evidence) {
        return evidence.contains("testOutcome=") || evidence.contains("failedTests=") || evidence.contains("buildSummary=")
                || Pattern.compile("(?i)BUILD (SUCCESSFUL|FAILED)|Task :[\\w.-]+:test\\b|gradlew[^,;\\n]{0,160}\\btest\\b").matcher(evidence).find();
    }

    /** A sentence that reports tests passing: "all 28 tests pass", "the new tests passed", "the suite is green". */
    private static final Pattern TEST_PASS_CLAIM = Pattern.compile(
            "(?i)[^.!?\\n]*?(?:\\btests?\\b[^.!?\\n]{0,60}?\\b(pass(?:es|ed)?|are green|succeed(?:s|ed)?)\\b"
            + "|\\bsuite\\s+(?:is|was|stays)\\s+(green)\\b)[^.!?\\n]*[.!?]?");

    /**
     * Agent loop port, plan B (2026-10-25): an answer said "All 28 tests in the repository pass" though no test ran
     * that turn, and the gates let it through: the outcome check only looked at whether the request asked for a run.
     * An answer that reports tests passing needs a test run among this turn's results; a prospect ("should pass once
     * …") is not a report, and a failure is not checked. Returns the issue, or null.
     */
    static String claimedTestPassIssue(String answer, String evidence) {
        if (answer == null || answer.isBlank() || evidence != null && ranATest(evidence)) {
            return null;
        }
        Matcher m = TEST_PASS_CLAIM.matcher(answer);
        while (m.find()) {
            int verb = m.start(1) >= 0 ? m.start(1) : m.start(2);
            String before = answer.substring(m.start(), verb).toLowerCase(Locale.ROOT);
            if (before.matches("(?s).*\\b(should|will|would|must|to|can|could|once|until|if|may|might)\\b.*")) {
                continue;
            }
            return "Your answer reports tests passing (\"" + m.group().strip() + "\"), but no test ran this turn: no "
                    + "result shows a test run. Drop the claim, or run the tests and report what they print.";
        }
        return null;
    }

    /** Start of one rendered tool result: the listener's {@code Tool: {} or the agent's {@code Tool={}. */
    private static final Pattern RESULT_BLOCK_START =
            Pattern.compile("(?<![\\w.])([A-Z][A-Za-z]*)(?:#\\d+)?(?:: |=)\\{");
    private static final Pattern STATUS_SUCCESS = Pattern.compile("\\bstatus=Success\\b");
    /** an editing tool's read-only action SUCCEEDED and the gates took it for an edit - a read-only action of an
     *  editing tool writes nothing. */
    static final Pattern READ_ONLY_ACTION =
            Pattern.compile("\\baction=(reach|verbs|list|inspect|locate|show|validate|preview)\\b");

    public static final Set<String> EDIT_TOOLS = Set.of("TreeEdit", "TreeCodegen", "SpecTestComposer");

    /** is a Success of {@code baseTool} with this result an EDIT success (something was written)? */
    public static boolean isEditSuccess(String baseTool, Object result) {
        if (baseTool == null || !EDIT_TOOLS.contains(baseTool)) {
            return false;
        }
        Object action = result instanceof Map<?, ?> m ? m.get("action") : null;
        return action == null || !READ_ONLY_ACTION.matcher("action=" + action).find();
    }

    private static final Pattern CLAIMED_FILE = Pattern.compile(
            "(?i)\\b(?:added|written|composed|created|inserted|saved|placed|generated) (?:in|into|to|at) (?:the )?(?:new )?"
            + "(?:suite |test |source )?(?:file |class )?[`'\"]?(?<![\\w.-])(/[\\w./-]+\\.(?:java|kt|md|json|txt|xml|py|sh))|"
            + "(?i)[`'\"]?(?<![\\w.-])(/[\\w./-]+\\.(?:java|kt|md|json|txt|xml|py|sh))[`'\"]?\\s+(?:was|has been|is now) "
            + "(?:added|written|composed|created|inserted|saved|generated|updated)");

    /** "the test method was added to the suite file /…/ShelfRenameSpec.java" - the only
     *  edit had FAILED and no such file existed. A claimed file that is not on disk is a fabrication. Only an absolute
     *  path is checked: the tail of a relative one ("docs/GUIDE.md was created" → "/GUIDE.md") is not a path at the
     *  root. */
    public static String claimedArtifactMissingIssue(String answer) {
        if (answer == null) {
            return null;
        }
        Matcher m = CLAIMED_FILE.matcher(answer);
        while (m.find()) {
            String path = m.group(1) != null ? m.group(1) : m.group(2);
            if (path != null && !Files.exists(Path.of(path))) {
                return "Your answer says a file was written at " + path + ", but no such file exists — the edit did not "
                        + "happen (the tool result was an Error or a read-only action). Do not claim work that was not "
                        + "done: apply the tool's coaching in a new plan, or state plainly what is INCOMPLETE.";
            }
        }
        return null;
    }

    /**
     * The text of the first rendered result of one of {@code tools} whose status is Success, or null. A block runs
     * from its start marker to the next block's start marker (never to a brace: values carry code and javadoc).
     */
    static String successfulResultBlock(String evidence, Set<String> tools) {
        if (evidence == null || tools == null) {
            return null;
        }
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = RESULT_BLOCK_START.matcher(evidence);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            if (!tools.contains(names.get(i))) {
                continue;
            }
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : evidence.length();
            String block = evidence.substring(spans.get(i)[1], to);
            if (STATUS_SUCCESS.matcher(block).find() && !READ_ONLY_ACTION.matcher(block).find()) {
                return block;
            }
        }
        return null;
    }

    public static String describedNotDoneIssue(String request, String evidence, String answer) {
        return describedNotDoneIssue(request, evidence, answer, null);
    }

    public static String describedNotDoneIssue(String request, String evidence, String answer,
            Set<String> succeededTools) {
        if (request == null || evidence == null) {
            return null;
        }
        String req = request.toLowerCase(Locale.ROOT);
        boolean writeRequest = req.matches("(?s).*\\b(make the (minimal )?(production )?fix|fix to green|apply the fix|"
                + "make the change|edit the|write one failing|implement the fix|change the code|"
                + "compose (?:a|the|one) (?:validated |new )?@?spec\\s?test|compose (?:a|the) (?:spec[- ]level )?"
                + "(?:regression )?test)\\b.*");
        // a CONFIRMATION request ("the fix is already implemented and committed … do NOT edit") still
        // matched "fix to green" and the gate demanded an edit that the request forbids
        boolean alreadyDone = req.matches("(?s).*\\b(already (?:implemented|committed|applied|done|in place)|do not edit|"
                + "confirm the fix|is committed \\()\\b.*") || req.matches("(?s).*\\bis committed\\b.*");
        // the stage-4 request says the FIX is committed and asks to COMPOSE a new test - the
        // "already done" exemption is about the fix, not about the test the request asks to write
        boolean composeRequest = req.matches("(?s).*\\bcompose (?:a|the|one) .{0,40}?test\\b.*");
        if (!writeRequest || (alreadyDone && !composeRequest)) {
            return null;
        }
        // one result block of an editing tool with status=Success (either rendering), or the agent's own
        // record of which tools succeeded this turn - never a scan that can run past a failed edit into another
        // tool's Success
        boolean edited = successfulResultBlock(evidence, EDIT_TOOLS) != null
                || (succeededTools != null && !Collections.disjoint(succeededTools, EDIT_TOOLS));
        if (edited) {
            return null;
        }
        boolean describes = answer != null && answer.toLowerCase(Locale.ROOT)
                .matches("(?s).*\\b(to fix this|replace|should now pass|the fix is|the correct anchor|would be to)\\b.*");
        if (describes || !edited) {
            return "The request asks you to MAKE a code change, and no edit succeeded this turn (every TreeEdit/TreeCodegen/"
                    + "SpecTestComposer compose failed or none ran; a reach/verbs lookup writes nothing). Describing the fix is "
                    + "not doing it. Apply it now: use the EXACT anchor text the "
                    + "tool's coaching listed (copy it verbatim), run the module's test task, and only then answer with "
                    + "the real outcome.";
        }
        return null;
    }

    public static String uncommittedGenerationIssue(String request, String evidence) {
        return uncommittedGenerationIssue(request, evidence, null);
    }

    public static String uncommittedGenerationIssue(String request, String evidence, Set<String> succeededTools) {
        if (request == null || evidence == null) {
            return null;
        }
        String req = request.toLowerCase(Locale.ROOT);
        if (!req.contains("commit")) {
            return null;
        }
        // a TreeCodegen result block with status=Success in either rendering, the generator's own "already
        // generated" reply, or the agent's record that TreeCodegen succeeded this turn
        boolean generated = successfulResultBlock(evidence, Set.of("TreeCodegen")) != null
                || evidence.contains("was ALREADY generated")
                || (succeededTools != null && succeededTools.contains("TreeCodegen"));
        // the commit HAD happened ([fix-order17 bc21b9f4f5] in a Bash output) and the gate still
        // fired - it looked for the text "git commit", which a tool RESULT never carries (only the output does). The
        // commit line is the marker; the command text stays as a fallback for outputs that echo it.
        boolean committed = COMMIT_LINE_HASH.matcher(evidence).find()
                || (Pattern.compile("git commit").matcher(evidence).find()
                && !Pattern.compile("git commit[^\\n]{0,200}(NonZeroExit|fatal:|error:)").matcher(evidence).find());
        if (generated && !committed) {
            // the request gave its own check-and-commit command, and the
            // planner twice answered with an invented hash and invented test results; the refusal asked for "the test
            // file" and then for an answer. A request that names its command gets that command back, to run next.
            String own = requestCommitCommand(request);
            if (own != null) {
                return "The request asks you to COMMIT, and TreeCodegen succeeded, but the request's own command has NOT "
                        + "run, so no test result and no commit hash exists yet. Do not regenerate and do not answer: your "
                        + "next plan is ONE Bash action with exactly this command, as the request writes it:\n" + own;
            }
            return "The request asks you to COMMIT the failing test, and this turn generated it (TreeCodegen succeeded) "
                    + "but no `git commit` ran. Do not regenerate the test. Run: git add <the test file> && git commit -s "
                    + "-m '<message>' (a local commit only — never push), then answer with the commit hash.";
        }
        return null;
    }

    /** The request's own command line that ends in a git commit (as written, trimmed), or null. */
    static String requestCommitCommand(String request) {
        for (String line : request.split("\n")) {
            String t = line.strip();
            if (t.contains("git commit") && (t.startsWith("./") || t.startsWith("cd ") || t.startsWith("git ")
                    || t.contains(" && "))) {
                return t.length() > 4000 ? null : t;
            }
        }
        return null;
    }

    public static String ignoredRecordingIssue(String proposedAnswer, String evidence) {
        if (proposedAnswer == null || evidence == null || !RECORDING_EVIDENCE.matcher(evidence).find()) {
            return null;
        }
        if (ANSWER_CITES_RUNTIME.matcher(proposedAnswer).find()) {
            return null;
        }
        return "A runtime RECORDING succeeded this turn (the strongest evidence available) and your answer does not "
                + "mention it. Report what the trace shows — which methods ran, where an exception was thrown and "
                + "whether it propagated or was swallowed — and only then the conclusions drawn from static analysis.";
    }

    public static String deterministicAnswerGateIssue(String proposedAnswer, boolean isClarify, boolean haveSuccess) {
        if (!haveSuccess || proposedAnswer == null) {
            return null;
        }
        if (isClarify) {
            return "You asked the user a clarifying question, but a tool already returned a SUCCESSFUL result "
                    + "this turn — answer from that result instead of asking.";
        }
        if (ASKS_FOR_DATA.matcher(proposedAnswer).find()) {
            return "Your final answer asks the user for (or uses a <placeholder> for) data that a SUCCESSFUL tool "
                    + "result already provided this turn — report the actual result instead.";
        }
        String planShaped = planShapedAnswerIssue(proposedAnswer);
        if (planShaped != null) {
            return planShaped;
        }
        return null;
    }

    /** self-corrections - one is enough to show the answer is still a draft. */
    private static final Pattern WORKING_NOTE_STRONG = Pattern.compile(
            "(?im)(?:(?:^|[.!?:]\\s+|[*_(]\\s*)wait\\s*[,.!?)]"
            + "|\\blet(?:'s| us| me)\\s+(?:re-?evaluate|reconsider|re-?think|re-?check|re-?read|re-?examine|revisit)\\b"
            + "|^[\\s*#>_-]*(?:verdict\\s+)?re-?evaluat(?:e|ing|ion)\\b"
            + "|\\bcorrection\\s*/\\s*refinement\\b|^[\\s*#>_-]*(?:self-)?correction\\s*:"
            + "|\\bon second thought\\b|\\bscratch that\\b|\\bi(?: will|'ll) stick with\\b|\\bself-correct\\w*"
            + "|(?:^|[.!?]\\s+)hmm+\\b)");
    /** thinking aloud - two of these mark notes rather than a report. */
    private static final Pattern WORKING_NOTE_WEAK = Pattern.compile(
            "(?im)(?:^|[.!?:]\\s+|[*_]\\s*)(?:(?:let's|let us|let me)\\s+(?:check|look at|verify|see|examine|trace|consider)\\b|actually,)");

    /**
     * four final answers were the model's working notes - "Wait, let's re-evaluate based on
     * the hash", "*Correction/Refinement based on Iteration 13*", "Verdict Re-evaluation", "I will stick with FALSE POSITIVE",
     * "Let's re-read `keyOf`" - with the verdict changing between paragraphs, and the same-model reviewer
     * approved them. An answer states its conclusion once. One self-correction, or two think-aloud steps, outside code
     * refuses the answer; returns the issue, or null.
     */
    public static String workingNotesIssue(String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        String prose = answer.replaceAll("(?s)```.*?```", " ").replaceAll("`[^`\\n]*`", "CODE");
        List<String> found = new ArrayList<>();
        Matcher strong = WORKING_NOTE_STRONG.matcher(prose);
        while (strong.find() && found.size() < 3) {
            found.add(strong.group().replaceAll("^[\\s.!?:*_#>(-]+", "").strip());
        }
        int weak = 0;
        List<String> weakFound = new ArrayList<>();
        Matcher w = WORKING_NOTE_WEAK.matcher(prose);
        while (w.find()) {
            weak++;
            if (weakFound.size() < 2) {
                weakFound.add(w.group().replaceAll("^[\\s.!?:*_]+", "").strip());
            }
        }
        if (found.isEmpty() && weak < 2) {
            return null;
        }
        if (found.isEmpty()) {
            found.addAll(weakFound);
        }
        return "Your answer is working notes, not a report: it thinks aloud and corrects itself (" + String.join("; ",
                found.stream().map(f -> "\"" + f + "\"").toList()) + "). State the conclusion once — a Shape B final_answer "
                + "with the verdict, the reasoning with file:line from the tool results, and what the reporter got right and "
                + "wrong — with no re-evaluation, no 'Wait', no 'Let's check'.";
    }

    private static final Pattern PLAN_MARKERS = Pattern.compile(
            "(?i)\\b(we need to|we should|will be (chosen|created|proposed|gathered|identified|determined)|once (this|the) "
            + "(information|data|results?) (is|are) (gathered|collected|available)|the next step|should (check|identify|"
            + "determine|verify)|to address this,? we)\\b");
    private static final Pattern CONCRETE_FACT = Pattern.compile(
            "(\\.java:\\d+|line \\d+|\\b[0-9a-f]{7,40}\\b|REACHABLE|NO HANDLER|reachable=|PREDICTED (RED|GREEN)|"
            + "UNDECIDED|passed|failed|FAIL|PASS|exit(Code)?[ =:]\\d|\\d+ (tests?|records?|files?|method)s?)");

    /**
     * a "final answer" that describes work still to do ("we need to identify…, once gathered…") after
     * tools ALREADY returned results is a plan, not an answer. Reject when the ending is plan-shaped and cites
     * no concrete fact (a file:line, a hash, a reach/predict verdict, a count).
     */
    public static String planShapedAnswerIssue(String answer) {
        if (answer == null) {
            return null;
        }
        Matcher m = PLAN_MARKERS.matcher(answer);
        int markers = 0;
        while (m.find()) {
            markers++;
        }
        if (markers >= 2 && !CONCRETE_FACT.matcher(answer).find()) {
            return "Your ending DESCRIBES WORK TO DO (\"we need to…\", \"once … is gathered…\") instead of reporting "
                    + "results — that is a plan, not an answer. Report the concrete findings the successful tools "
                    + "returned this turn (file:line, reach verdicts, the test file, entry points), or propose the "
                    + "next tool actions as Shape A.";
        }
        return null;
    }

    private static final Pattern DISCOVERABLE_ASK = Pattern.compile(
            "(?i)\\b(exact |full |absolute )?(file ?)?(path|location|directory|folder)\\b|\\bwhere (is|are|does)\\b"
            + "|\\b[A-Za-z0-9_]+\\.java\\b|\\bwhich (file|class|module|package)\\b");

    /**
     * DETERMINISTIC clarify pre-check: a clarifying question asking for something the agent can
     * DISCOVER with its own tools - a file path / location, which file or class - is never necessary; an
     * unattended drive loses the whole stage to it. Returns the coaching issue, or null when the question
     * is about something genuinely undiscoverable.
     */
    private static final Pattern BEHAVIOUR_ASK = Pattern.compile(
            "(?i)\\b(specific|exact|which|what) (behaviou?r|behavior|functionality|requirement|assertion|scenario)\\b"
            + "|\\bwhat (do you want|should (the|it)|exactly)\\b|\\bprovide the (specific|exact) (test|case|behaviou?r)\\b");

    public static String discoverableClarifyIssue(String clarification) {
        return discoverableClarifyIssue(clarification, null);
    }

    /** {@code request} = the user's turn text; a clarify asking for the behaviour it already states is rejected. */
    public static String discoverableClarifyIssue(String clarification, String request) {
        if (clarification == null || clarification.isBlank()) {
            return null;
        }
        if (request != null && BEHAVIOUR_ASK.matcher(clarification).find()
                && request.matches("(?is).*\\b(must|never|only|should|asserts?|expect(ed|s)?|pins?)\\b.*")) {
            return "You asked which behaviour to test ('" + clarification.strip() + "'), but the request STATES it "
                    + "(look for the sentence with must / never / only / asserts). Use that sentence as the TreeCodegen "
                    + "task — never ask.";
        }
        if (DISCOVERABLE_ASK.matcher(clarification).find()) {
            return "You asked the user for a file path / location ('" + clarification.strip() + "'). That is "
                    + "DISCOVERABLE: use LocateFile {\"fileName\": \"<Name>.java\"} or CodeGraph {\"action\": "
                    + "\"locate\", \"symbol\": \"<Type#method>\"} (or derive it from the module's source root + "
                    + "package) — never ask.";
        }
        return null;
    }

    /**
     * Builds the LLM answer-gate prompt: does the proposed terminal answer actually
     * REPORT/USE the successful tool results, or does it ask for / merely describe
     * something those results already provide?
     */
    /** a Shape B ruling - TRUE/FALSE POSITIVE. */
    public static final Pattern VERDICT = Pattern.compile("(?i)\\b(TRUE|FALSE)[ -]POSITIVE\\b");

    /** the ruling a verdict text makes - "TRUE POSITIVE" / "FALSE POSITIVE" (the first one it states), else null. */
    public static String verdictOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VERDICT.matcher(text);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) + " POSITIVE" : null;
    }

    /**
     * the terminal refiner's rewrite ended at "(the `locale` is part of the key" - cut off by
     * the helper's token limit - and replaced a complete answer. A text that stops mid-sentence, or inside a code fence
     * or an open parenthesis, is not a finished answer.
     */
    public static boolean endsMidSentence(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.strip();
        int fences = 0, from = 0;
        while ((from = t.indexOf("```", from)) >= 0) {
            fences++;
            from += 3;
        }
        if (fences % 2 == 1) {
            return true;
        }
        int open = 0;
        for (char c : t.toCharArray()) {
            if (c == '(') {
                open++;
            } else if (c == ')') {
                open--;
            }
        }
        if (open > 0) {
            return true;
        }
        char last = t.charAt(t.length() - 1);
        return ".!?:;)\"'*_`]|>".indexOf(last) < 0 && !Character.isDigit(last) && !t.endsWith("POSITIVE");
    }

    /** the second reviewer's finding as an answer-gate issue; null when it supports the verdict or did not answer usably. */
    public static String verdictCriticIssue(String criticReply, String source) {
        return verdictCriticIssue(criticReply, source, null, null);
    }

    /**
     * the second reviewer disputed two correct FALSE POSITIVE verdicts for "claims" such as
     * "sharing a single cached page" - the REPORT's consequences, which the verdict quoted in order
     * to reject them; the reviewer's own justification agreed with the verdict. When the verdict is FALSE POSITIVE, a
     * disputed claim made of the request's own words is the report's claim and is dropped.
     */
    public static String verdictCriticIssue(String criticReply, String source, String answerText, String request) {
        return verdictCriticIssue(criticReply, source, answerText, request, null);
    }

    /**
     * as above; a disputed claim, or a "does not follow" note, that rests on a code object that does not exist under
     * {@code root} is dismissed (objects the verdict or the request names are judged by their own gates).
     */
    public static String verdictCriticIssue(String criticReply, String source, String answerText, String request, Path root) {
        String own = (answerText == null ? "" : answerText) + "\n" + (request == null ? "" : request);
        if (criticReply == null) {
            return null;
        }
        boolean denial = answerText != null && request != null && "FALSE POSITIVE".equals(verdictOf(answerText));
        Set<String> requestWords = denial ? contentWords(request) : Set.of();
        int a = criticReply.indexOf('{');
        int b = criticReply.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            JsonNode node =
                    new ObjectMapper().readTree(criticReply.substring(a, b + 1));
            List<String> claims = new ArrayList<>();
            for (JsonNode c : node.path("unsupported_claims")) {
                String t = c.asText().trim();
                if (!t.isEmpty() && !(denial && isTheRequestsOwnClaim(t, requestWords))
                        && !(root != null && CitationValidator.citesMissing(t, root, own))) {
                    claims.add(t);
                }
            }
            boolean follows = !node.has("verdict_follows") || node.path("verdict_follows").asBoolean(true)
                    || (root != null && CitationValidator.citesMissing(node.path("note").asText(""), root, own));
            if (claims.isEmpty() && follows) {
                return null;
            }
            StringBuilder sb = new StringBuilder("A second reviewer (").append(source)
                    .append(") read your verdict against the evidence");
            if (!claims.isEmpty()) {
                sb.append(" and finds these claims have NO line behind them or a line CONTRADICTS them: ")
                        .append(String.join(" | ", claims));
            }
            if (!follows) {
                String note = node.path("note").asText("").trim();
                sb.append(claims.isEmpty() ? " and finds that the verdict does not follow from the supported claims"
                        : "; the verdict does not follow from the supported claims alone")
                        .append(note.isEmpty() ? "" : " — " + note);
            }
            sb.append(". Drop each such claim or cite the line that shows it, then re-decide the verdict from what remains.");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** the claim's head (before " - ") is made of the request's own words: at least three content words, 3/4 of them in the request. */
    static boolean isTheRequestsOwnClaim(String claim, Set<String> requestWords) {
        String head = claim.split("\\s+[—–-]\\s+", 2)[0];
        Set<String> words = contentWords(head);
        if (words.size() < 3) {
            return false;
        }
        int hits = 0;
        for (String w : words) {
            if (requestWords.contains(w)) {
                hits++;
            }
        }
        return hits * 4 >= words.size() * 3;
    }

    static Set<String> contentWords(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (w.length() >= 5) {
                out.add(w.replaceAll("(ing|ed|es|s)$", ""));
            }
        }
        return out;
    }

    private static final Pattern READ_RANGE_SIG = Pattern.compile(
            "sed\\s+-n\\s+['\"]?(\\d+),(\\d+)p['\"]?\\s+(?:\"|')?(\\S*?([A-Za-z_][\\w$]*)\\.java)");
    private static final Pattern CITATION = Pattern.compile(
            "\\b([A-Z][\\w$]*)(?:\\.java)?(?:[.#][a-z]\\w*(?:\\(\\))?)?[`'\"]?\\s*(?:\\(|,|\\bat\\b)?\\s*(?:lines?|ll?\\.|:)\\s*~?(\\d{1,5})(?:\\s*[-‐‑‒–—]\\s*~?(\\d{1,5}))?");

    /**
     * an answer that cites line numbers of a file this turn READ (sed -n 'a,bp' file.java) where the cited range
     * lies outside every range read - null when every citation into a read file is covered, or names a file never read.
     */
    public static String uncoveredCitationIssue(String answer, Set<String> executedSignatures) {
        if (answer == null || executedSignatures == null || executedSignatures.isEmpty()) {
            return null;
        }
        Map<String, List<int[]>> read = new LinkedHashMap<>();
        for (String sig : executedSignatures) {
            Matcher m = READ_RANGE_SIG.matcher(sig);
            while (m.find()) {
                read.computeIfAbsent(m.group(4), k -> new ArrayList<>())
                        .add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
            }
        }
        if (read.isEmpty()) {
            return null;
        }
        Matcher c = CITATION.matcher(answer);
        while (c.find()) {
            String file = c.group(1);
            List<int[]> ranges = read.get(file);
            if (ranges == null) {
                continue;
            }
            int from = Integer.parseInt(c.group(2));
            int to = c.group(3) == null ? from : Integer.parseInt(c.group(3));
            if (to < from) {
                int t = from;
                from = to;
                to = t;
            }
            boolean covered = false;
            for (int[] r : ranges) {
                if (from <= r[1] && to >= r[0]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                StringBuilder seen = new StringBuilder();
                for (int[] r : ranges) {
                    seen.append(seen.length() == 0 ? "" : ", ").append(r[0]).append('-').append(r[1]);
                }
                return "The answer cites " + file + " lines " + from + (to == from ? "" : "-" + to) + ", but this turn read only lines "
                        + seen + " of " + file + ".java — no read printed those lines. Cite only lines a read printed (the numbers "
                        + "are in CONTEXT SO FAR), or read the lines you cite first.";
            }
        }
        return null;
    }

    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```[a-zA-Z]*\\n(.*?)```");
    private static final Pattern PROPOSAL_INTRO = Pattern.compile(
            "(?is)\\b(fix|patch|propos\\w*|suggest\\w*|would|should|could|change|instead|replace|add|remove|new test|test case|for example|e\\.g\\.)\\b");

    /**
     * a fenced code block in the answer that mixes lines a read printed with lines no read printed - a paraphrase
     * shown as a quotation. Null when every substantial line of every quoted block is in the evidence, when a block
     * has no line in the evidence at all (an illustration, or a read the cap cut), or when the block is introduced as a
     * fix or proposal.
     */
    public static String paraphrasedQuoteIssue(String answer, String evidence) {
        if (answer == null || evidence == null || evidence.isEmpty() || !answer.contains("```")) {
            return null;
        }
        String flat = evidence.replaceAll("\\s+", "");
        Matcher m = FENCED_BLOCK.matcher(answer);
        while (m.find()) {
            String intro = answer.substring(Math.max(0, m.start() - 160), m.start());
            if (PROPOSAL_INTRO.matcher(intro).find()) {
                continue;
            }
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String line : m.group(1).split("\\n")) {
                String code = line.strip();
                if (code.length() < 20 || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    continue;
                }
                if (flat.contains(code.replaceAll("\\s+", ""))) {
                    found.add(code);
                } else {
                    missing.add(code);
                }
            }
            if (!found.isEmpty() && !missing.isEmpty()) {
                return "The quoted code block is not what the reads printed: `" + RepeatGuard.excerpt(missing.get(0), 100) + "` appears in no tool "
                        + "output this turn, while `" + RepeatGuard.excerpt(found.get(0), 80) + "` does — a paraphrase shown as a quotation. Quote the "
                        + "printed lines verbatim (with their numbers), or describe them in prose.";
            }
        }
        return null;
    }

    /** a claim a second reviewer disputed this turn that the answer still asserts (seven of ten content words present). */
    public static String stillDisputedIssue(String answer, List<String> disputedClaims) {
        if (answer == null || disputedClaims == null || disputedClaims.isEmpty()) {
            return null;
        }
        Set<String> words = AnswerGate.contentWords(answer);
        for (String claim : disputedClaims) {
            Set<String> cw = AnswerGate.contentWords(claim);
            if (cw.size() < 4) {
                continue;
            }
            int hits = 0;
            for (String w : cw) {
                if (words.contains(w)) {
                    hits++;
                }
            }
            if (hits * 10 >= cw.size() * 7) {
                return "A second reviewer disputed this claim earlier this turn and no line read since supports it: \"" + RepeatGuard.excerpt(claim, 160)
                        + "\" — drop it, or read the code that shows it (a tool result) and cite that line; the verdict must follow from "
                        + "the supported claims alone.";
            }
        }
        return null;
    }

    /**
     * the second reviewer saw the TAIL of the results (14k chars) - the quote class's 30-line read
     * from iteration 5 had fallen off the front - and disputed "the charge is flat" as having "no quoted line", though
     * line 23 of that read showed exactly that; the dispute was then held against every later verdict. The reviewer now
     * sees first the result entries the verdict cites (a file name, a symbol, a line number it names), then the tail, within the
     * same cap.
     */
    public static String evidenceFor(String successful, String answer, int cap) {
        if (successful == null || successful.length() <= cap || answer == null) {
            return capTail(successful, cap);
        }
        List<String> entries = new ArrayList<>();
        StringBuilder current = null;
        for (String line : successful.split("\n", -1)) {
            if (line.matches("^[A-Za-z][\\w#.-]* => .*") && current != null) {
                entries.add(current.toString());
                current = null;
            }
            if (current == null) {
                current = new StringBuilder();
            } else {
                current.append('\n');
            }
            current.append(line);
        }
        if (current != null) {
            entries.add(current.toString());
        }
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b([A-Z][A-Za-z0-9]{3,}(?:\\.java)?|[a-z][A-Za-z0-9]{5,})\\b").matcher(answer);
        while (m.find()) {
            cited.add(m.group(1).replace(".java", ""));
        }
        List<String> chosen = new ArrayList<>();
        int used = 0;
        for (String e : entries) {
            if (e.length() > cap / 2) {
                continue;   // a huge entry: left to the tail
            }
            int hits = 0;
            for (String c : cited) {
                if (e.contains(c)) {
                    hits++;
                    if (hits >= 2) {
                        break;
                    }
                }
            }
            if (hits >= 2 && used + e.length() <= cap * 2 / 3) {
                chosen.add(e);
                used += e.length() + 1;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!chosen.isEmpty()) {
            sb.append("--- results the verdict cites ---\n").append(String.join("\n", chosen)).append("\n--- the latest results ---\n");
        }
        sb.append(capTail(successful, Math.max(2000, cap - sb.length())));
        return sb.toString();
    }

    public static Path workbenchRoot() {
        String wb = System.getProperty("hermit.step.workbench", System.getProperty("hermit.projectDir", System.getProperty("hermit.bash.defaultDir")));
        return wb == null || wb.isBlank() ? null : Path.of(wb);
    }

    /** prose that answers - not blank, not the client's failure text, not a tool call or a plan, not cut off. */
    public static boolean isAnAnswerText(String text) {
        if (text == null || text.isBlank() || text.startsWith("Extraction Failed") || ChatClient.LLM_CALL_FAILED.equals(text)) {
            return false;
        }
        String t = text.strip();
        if (t.contains("<|channel|>") || t.contains("<|message|>")) {
            return false;
        }
        if (t.startsWith("{") && t.matches("(?s).*\"(tool|branches|actions|action)\"\\s*:.*")) {
            return false;
        }
        return !AnswerGate.endsMidSentence(t);
    }

    /** Append a deterministic disclaimer when the answer still claims a commit or a test verdict that never
     *  happened this session (after the bounded fabrication gate gave up re-planning). */
    public static String withHonestyNote(String answer, boolean committed, boolean testObserved) {
        return withHonestyNote(answer, committed, testObserved, "", null);
    }

    /** Also marks findings that trace to no tool result (invented identifiers). */
    public static String withHonestyNote(String answer, boolean committed, boolean testObserved, String evidence,
            String request) {
        if (answer == null) {
            return null;
        }
        String residual = AnswerGate.fabricationIssue(answer, committed, testObserved,
                (evidence == null ? "" : evidence) + "\n" + (request == null ? "" : request));
        if (residual == null) {
            residual = AnswerGate.hashClaimIssue(answer, evidence);
        }
        if (residual == null) {
            residual = AnswerGate.fabricatedIdentifiersIssue(answer, evidence == null ? "" : evidence, request);
        }
        String noted = CitationValidator.withNote(answer, workbenchRoot(), request);   // once the citation refusals are spent
        if (residual == null) {
            return noted;
        }
        return noted + "\n\n[Hermit honesty note — this answer was NOT verified: " + residual + "]";
    }

    /** Keep the last {@code max} characters of a context blob (most recent is most relevant). */
    public static String capTail(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return "...(earlier context omitted)...\n" + s.substring(s.length() - max);
    }

    /** "Answer with 'done' once …", "reply exactly \"FIXED\"": the words a request dictates as the answer, quoted. */
    private static final Pattern DICTATED_ANSWER = Pattern.compile(
            "(?i)\\b(?:answer|reply|respond)(?:\\s+exactly)?(?:\\s+with)?(?:\\s+the(?:\\s+single)?\\s+word)?\\s*:?\\s*"
            + "['\"\u2018\u201c]([^'\"\u2019\u201d\\n]{1,40})['\"\u2019\u201d]");

    /** The answer a request dictates word for word ("Answer with 'done' once the last round ran"), or null. */
    static String dictatedAnswer(String request) {
        Matcher m = request == null ? null : DICTATED_ANSWER.matcher(request);
        return m != null && m.find() ? m.group(1).strip() : null;
    }

    /**
     * A request said "Answer with 'done' once the last round ran"; the model gate refused "done" for reporting no
     * substance, and the answers written in its place invented a passing suite and a stricter regex. An answer that is exactly the words the request dictates is the answer it asked for; the model gate does not
     * judge it (the deterministic checks still do).
     */
    public static boolean isTheDictatedAnswer(String request, String answer) {
        String dictated = dictatedAnswer(request);
        return dictated != null && answer != null
                && answer.strip().replaceAll("[.!]+$", "").equalsIgnoreCase(dictated.replaceAll("[.!]+$", ""));
    }
}
