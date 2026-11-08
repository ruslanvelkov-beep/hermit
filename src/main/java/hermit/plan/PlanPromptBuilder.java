package hermit.plan;

import hermit.tool.api.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The prompts Hermit puts to its planners and reviewers: the step planner's prompt (the goal, the context, the tools
 * and their contracts), the task plan, the first plan, the plan author's and the reviewers' prompts, the step judge,
 * the look-ahead and the revision, the answer gate's and the verdict critic's prompts, and the repair of a reply that
 * is not valid JSON; with the blocks they share (what ran, the failure ledger) and the fitting of context to a budget.
 */
public final class PlanPromptBuilder {

    // the tools the planner is offered: the AVAILABLE TOOLS section is their documentation
    private final ToolRegistry tools;

    /** The prompts with the tools of a fresh registry. */
    public PlanPromptBuilder() {
        this(new ToolRegistry());
    }

    /** The prompts with the tools {@code tools} registers. */
    public PlanPromptBuilder(ToolRegistry tools) {
        this.tools = tools;
    }

    /**
     * Generates the planning prompt: goal + accumulated context + the strict
     * two-shape output contract (branching plan with per-branch actions, or
     * final answer to terminate).
     */
    public String generatePrompt(PlanNode node, String previousStepContext, String failureLedger) {
        // Prefix-cache order (opt-in): the context grew in the MIDDLE of the prompt, so the ~6.5k
        // tokens of rules after it were prefilled again on every call (2.5-5 min per planner call at 80 tok/s). With
        // `hermit.prompt.stableFirst` everything that is the same on every call comes first and the growing context and the
        // ledger last, so a server-side prompt cache re-reads only what is new.
        boolean stableFirst = Boolean.getBoolean("hermit.prompt.stableFirst");
        StringBuilder prompt = new StringBuilder();
        prompt.append("--- AGENT INSTRUCTION ---\n");
        prompt.append("Overall goal: ").append(node.getGoal()).append("\n\n");
        if (!stableFirst) {
            prompt.append("CONTEXT SO FAR:\n").append(previousStepContext).append("\n\n");
        }
        // PROJECT CONTEXT: hand the planner the real paths so it never emits a placeholder like
        // "<project dir>" (a small model copies those verbatim and the action fails).
        String projectDir = System.getProperty("hermit.projectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            prompt.append("--- PROJECT CONTEXT (use these EXACT values, never a placeholder) ---\n");
            prompt.append("- Project dir (workingDir / projectDir / sourceCodePath / root): ")
                    .append(projectDir).append('\n');
            prompt.append('\n');
        }
        // REMOTE EXECUTION: when this run is configured to execute tools on another machine over SSH,
        // tell the planner the exact remote target so every execution tool carries the "remote" param.
        String execHost = System.getProperty("hermit.exec.host");
        if (execHost != null && !execHost.isBlank()) {
            String execPath = (projectDir != null && !projectDir.isBlank()) ? projectDir : "";
            String remoteVal = execPath.isEmpty() ? execHost.trim() : execHost.trim() + ":" + execPath;
            prompt.append("--- REMOTE EXECUTION (this run executes tools on another machine over SSH) ---\n");
            prompt.append("- Add \"remote\": \"").append(remoteVal).append("\" to the JSON params of EVERY "
                    + "execution tool (" + executionTools() + ") so it runs on that machine. All the "
                    + "paths above are paths on THAT machine.\n\n");
        }
        prompt.append("--- TOOL SELECTION ---\n");
        prompt.append("Invoke a tool by its NAME with JSON parameters — NEVER run a tool as a shell command "
                + "or script (e.g. to find a file, select the LocateFile tool; do NOT run a 'LocateFile.sh' "
                + "in Bash). Every tool listed under AVAILABLE TOOLS is installed and callable — NEVER plan a "
                + "step to 'verify a tool is available/installed'; call it directly. Pick by WHAT you're "
                + "searching for:\n"
                + "  • a FILE by its file NAME (you know the filename, e.g. `InvoiceFormatter.java`) → LocateFile. "
                + "LocateFile matches FILE NAMES only — NEVER pass a method or class name to it (there is no file "
                + "named `drainPendingTasks.java`); that always returns nothing.\n"
                + "  • WHERE A METHOD/SYMBOL is DEFINED or used (you have a method/class name, not a filename) → "
                + "`grep -rn \"<name>\" <dir>` via Bash, or CodeGraph (symbol=<name>) for callers/callees. This is "
                + "how you find which file contains a method the ticket cites — do NOT use LocateFile for a method.\n"
                + "  • a method's callers/callees/call graph → CodeGraph.\n"
                + "  • read a GitHub issue/PR → " + (tools.names().contains("GitHub") ? "GitHub or " : "")
                + "`gh` via Bash;  • dynamic execution trace → HindsightDebugger.\n"
                + "Class/method line numbers in a ticket are often STALE — find code by NAME (grep/CodeGraph for a "
                + "method; LocateFile for a filename), not by the ticket's line numbers.\n\n");
        prompt.append("--- REMOTE TOOL EXECUTION (\"remote\" param) ---\n"
                + "An execution tool (" + executionTools() + ") can run on ANOTHER machine over SSH by adding "
                + "a \"remote\": \"user@host:/absolute/path\" field to its JSON params. Set it if AND ONLY IF you "
                + "are told to run that tool on a remote machine over SSH (either the REMOTE EXECUTION context "
                + "above gives the target, or the task text names a machine + path). If nothing instructs you to "
                + "use a remote machine, DO NOT add a \"remote\" field — omit it entirely so the tool runs "
                + "locally. Never invent a host/path: use the exact user@host:/path you were given.\n"
                + "CRITICAL: to run on the remote machine, put the PLAIN command/params and add \"remote\" — "
                + "do NOT hand-write an `ssh user@host \"...\"` command yourself. Writing your own ssh is WRONG: "
                + "tools like HindsightDebugger CANNOT be expressed as an inline ssh and REQUIRE the "
                + "\"remote\" param, and for Bash the \"remote\" param already does the ssh + cd for you. "
                + "Example — run git status on the remote: {\"command\": \"git status\", \"remote\": "
                + "\"user@host:/abs/path\"}  (NOT {\"command\": \"ssh user@host 'cd /abs/path && git status'\"}).\n\n");
        prompt.append(availableToolsSection());
        prompt.append("CODE RULE: never present unverified code. Before putting any program in a "
                + "final_answer you MUST verify it: SyntaxCheck the exact source (pass classpath to compile "
                + "in project context for Java)"
                + (tools.names().contains("GenerateCode") && tools.names().contains("TddCycle")
                ? ", and for changes to this project use GenerateCode/TddCycle which compile in context AND run "
                + "the related tests" : "")
                + ". If a check fails, fix it and re-verify - do NOT answer with code that failed a check.\n\n");
        prompt.append("--- AGENT CONTRACT ---\n");
        prompt.append("WHEN THE REQUEST SAYS *WRITE* (a test, a fix, a method), the plan MUST contain a code-authoring "
                + "action — " + (tools.names().contains("TreeCodegen") ? "TreeCodegen" : "TreeEdit")
                + " (unit tests, edits)"
                + (tools.names().contains("SpecTestComposer") ? " or SpecTestComposer (spec tests)" : "")
                + ". Analysis actions alone "
                + "(locate/callers/callees/tests/escapes/catches) do NOT satisfy a write request: they are stage-1 "
                + "moves, and repeating them instead of generating is the commonest way a TDD step is lost.\n");
        prompt.append("AND SYMMETRICALLY: when the request says CHARACTERIZE / ANALYSE / REPORT — or forbids touching "
                + "code — the plan must contain NO code-authoring action. Generating a test or an edit in that step is "
                + "out of scope, and writing a test into a PRODUCTION file is refused outright.\n");
        prompt.append("PARAMETER VALUES ARE CONCRETE: never emit a value in angle brackets or a description of the "
                + "value ('<specify the exact test method name>', '<symbol>') — the executor refuses it and the step is "
                + "wasted. If CONTEXT does not give the value, plan the DISCOVERY step first (CodeGraph locate/tests, "
                + "LocateFile, a Bash read) and use its result next turn.\n");
        prompt.append("Respond with ONE JSON object and NOTHING else. Choose exactly one shape:\n\n");
        prompt.append("Shape A - propose 1 to 3 alternative strategies (branches) when more work is needed.\n");
        prompt.append("BRANCHES ARE ALTERNATIVES — only the single best one runs; the others are DISCARDED. A request "
                + "with several parts (a)(b)(c)(d) is ONE branch whose \"actions\" array holds one action per part — they "
                + "all execute this iteration. Never spread a checklist across branches: parts 2..n would never run "
                + ".\n");
        prompt.append("Each branch MUST include its concrete tool actions:\n");
        prompt.append("{\"branches\": [\n");
        prompt.append("  {\"name\": \"<BranchName>\", \"description\": \"<why this path>\", \"confidence\": <0.0-1.0>,\n");
        prompt.append("   \"actions\": [{\"tool\": \"CodeGraph\", \"parameters\": {\"action\": \"locate\", \"symbol\": \"<Type#method>\"}}]}\n");
        prompt.append("]}\n\n");
        prompt.append("Shape B - ONLY when the goal is fully achieved using tool results already present in CONTEXT SO FAR:\n");
        prompt.append("{\"final_answer\": \"<complete answer for the user>\"}\n");
        prompt.append("When the request asks for THE FIX, state it as a CONCRETE code change derived from the evidence — which "
                + "expression or value replaces which, at which method/line (e.g. 'in the catch at line 686 advance "
                + "lastIntervalProcessTime to executionStart instead of clockNow') — never a generic remedy such as "
                + "'handle exceptions more granularly'.\n\n");
        prompt.append("CONCLUDE, DON'T OVER-TOOL: if CONTEXT SO FAR ALREADY contains the evidence/results "
                + "needed to answer (e.g. the request asks for a verdict/conclusion/summary of findings already "
                + "gathered, or explicitly says you already have the data or to 'not run more tools'), you MUST "
                + "respond with Shape B (final_answer) synthesized from that context. Calling ANOTHER tool when "
                + "the answer is already available in CONTEXT SO FAR is WRONG — it wastes a step and derails.\n\n");
        prompt.append("Shape C - when the request is ambiguous or refers to something you cannot resolve from "
                + "CONTEXT SO FAR (e.g. 'the program', 'this functionality' with no prior detail): ASK, do "
                + "not guess and do not loop.\n");
        prompt.append("{\"clarify\": \"<the specific question you need answered>\"}\n\n");
        prompt.append("CONTEXT SO FAR may include earlier turns of this conversation - use them to resolve "
                + "references before asking.\n");
        prompt.append("NEVER assume the task is a simulation, a stub, boilerplate, or a mere code example. "
                + "Do not invent placeholder behavior. Otherwise plan and implement the REAL functionality end to end.\n");
        prompt.append("DO NOT OVER-ASK. If the request is 'make a plan', or can be resolved from CONTEXT SO FAR "
                + "(the earlier turns already name the details), you MUST synthesize those turns and respond with "
                + "Shape A (a concrete plan) or Shape B - NOT Shape C. Use Shape C ONLY for a genuinely new, "
                + "specific unknown that CONTEXT SO FAR does not answer, and never re-ask something already answered above.\n");
        prompt.append("STANDALONE PROGRAMS: to write a standalone program or script (not a change to THIS Java "
                + "project) — e.g. 'write a Python program that fetches X and run it' — you MUST use a WriteProgram "
                + "action with run=true. Keep the JSON tiny ({\"language\":\"python\",\"run\":true}) and put the whole "
                + "program in a fenced ```python block AFTER the JSON object. NEVER embed the program inside a JSON "
                + "string (escaping code as JSON breaks the plan). "
                + (tools.names().contains("GenerateCode") && tools.names().contains("TddCycle")
                ? "Do NOT use GenerateCode/TddCycle for standalone programs — those are ONLY for changing THIS Java "
                + "project and REQUIRE a real projectDir; a placeholder like '/path/to/your/project' or "
                + "'<project dir>' is ALWAYS wrong and the action will fail. " : "")
                + "Write real, runnable code: valid syntax for the language (Python comments start "
                + "with '#', never '//'), the interpreter is python3, and pick concrete defaults (a public API you "
                + "actually named) instead of asking. WriteProgram syntax-checks and runs it; if syntaxOk is false or "
                + "it exits non-zero, FIX the exact error and try again — do not answer with a program that failed.\n");
        prompt.append("EXTERNAL SERVICES/URLs: do NOT trust a URL or API endpoint you are unsure of - VERIFY it. "
                + "Write a tiny throw-away script that just connects to the endpoint and RunProgram it: if it fails "
                + "because the host/URL is invalid or unreachable, the URL is wrong (fix it or, if you cannot obtain "
                + "a valid one, ask the user for the exact service URL); a failure only about a missing/invalid API "
                + "key means the URL is reachable and acceptable. Never present a program whose base URL you have not "
                + "reached at least this far.\n");
        prompt.append("REFERENCE RESOLUTION: when the request refers to something mentioned earlier (a URL, a "
                + "file path, a PR/issue link, a name, an id — e.g. 'the link I gave', 'that repo', 'the issue "
                + "above'), find that exact value in CONTEXT SO FAR and COPY IT VERBATIM into the tool arguments. "
                + "Do NOT re-type it from memory, do NOT invent or 'correct' a different value, and do NOT ask the "
                + "user for something CONTEXT SO FAR already contains. If it truly is not in CONTEXT, then and only "
                + "then use Shape C.\n");
        prompt.append("COMPLETE TOOL ARGS: every action MUST carry the concrete arguments that actually do the "
                + "work. NEVER invoke a command with no/placeholder arguments (no bare 'gh', no bare 'curl', no "
                + "'<url>') — that does nothing. If you mean to inspect a PR, form the REAL command, e.g. "
                + "`gh pr view <exact-url> --json title,body,files` or `gh pr diff <exact-url>`. If you lack a value "
                + "a required argument needs, resolve it via REFERENCE RESOLUTION above before proposing the action.\n");
        prompt.append("PRIVATE/AUTHENTICATED RESOURCES: a 404 / 'Not Found' from an UNauthenticated fetch (a plain "
                + "`curl` of an access-controlled web or API URL) does NOT mean the URL is wrong — a private "
                + "resource returns 404 precisely because the request is not authenticated. Do NOT conclude the link "
                + "is bad and do NOT ask the user to 'verify the URL' on that basis. Instead switch to the "
                + "AUTHENTICATED command: for GitHub use `gh` through Bash (already logged in), e.g. `gh pr view <exact-url> --json "
                + "title,body,files` / `gh pr diff <exact-url>`, which carries the user's credentials. Only treat a "
                + "URL as genuinely wrong if an AUTHENTICATED tool also fails to find it.\n");
        prompt.append("REQUEST EFFORT — match effort to the question, do not over-think simple ones:\n");
        prompt.append("- SIMPLE/GENERIC (who are you, what do you do, your capabilities, which tools you have, "
                + "general how-to advice): answer DIRECTLY in ONE pass with Shape B, NO tools. For capabilities/"
                + "tools, synthesize from the AVAILABLE TOOLS list above.\n");
        prompt.append("- FILE/DIR EXISTS (does X exist?): one Bash action (test -e / ls), then answer.\n");
        prompt.append("- FILE/DIR CONTENTS: a Bash script (grep/cat/find), then answer from its output.\n");
        prompt.append("- 'Can program/command X be used for ...?': inspect its help/docs (Bash 'X --help' if it "
                + "supports one), then answer.\n");
        prompt.append("- A SPECIFIC file or THIS codebase/project: PLAN and ITERATE (read/query via CodeGraph/"
                + (tools.names().contains("AssembleContext") ? "AssembleContext/" : "")
                + "Bash), verifying, for the most accurate answer.\n");
        prompt.append("AUTHORITY OF STRONGER MODELS: when a reviewer, the remote refiner, or an expert "
                + "consultation gives you a concrete value/URL/snippet, or tells you to use a specific TOOL or API, "
                + "ADOPT IT EXACTLY in your next plan — it OVERRIDES your own memory, your data, and any earlier "
                + "plan. If it suggests a tool, USE that tool. NEVER revert to an approach (e.g. an API/endpoint) "
                + "that already FAILED this turn — pick the one the stronger model or the tool results point to.\n");
        prompt.append("RULE: if any tool still needs to run, you MUST respond with Shape A (branches).\n");
        prompt.append("Never announce or describe what you need to do - propose it as a branch, ask via "
                + "Shape C, or answer via Shape B. To ask the user ANYTHING, use Shape C only — NEVER run a tool "
                + "(no `echo \"please provide...\"`, no command whose purpose is to print a question) to ask; a tool "
                + "action must do real work, not pose a question.\n");
        prompt.append("The final_answer must contain the answer itself, never intentions or plans.\n");
        prompt.append("EVIDENCE FIRST: what a tool returned this turn — a CodeGraph locate/callers/callees/reach, a "
                + "runtime recording (HindsightDebugger), a test run, the lines a read printed — is a FACT; anything not in "
                + "a tool result is a guess. A fact outranks a guess, a ticket's claim and your own memory; when a fact "
                + "contradicts what you believed, the fact wins and your belief changes. Before you conclude, check every "
                + "claim in your answer against a tool result and cite it (file:line, or the tool and its value).\n");
        prompt.append("A STRONGER MODEL'S PLAN OR ANSWER is a strong hint, not a verdict: adopt its concrete facts and "
                + "tools, but it passes the same guards as your own plan and its claims need the same tool results behind "
                + "them — a plan that repeats a refused step, or an answer that contradicts a read line, is wrong whoever wrote it.\n");
        prompt.append("FIXES ARE TDD: when a change to the code is required, first a focused test that FAILS for the "
                + "defect (run it, see RED), then the minimal production change, then the same test GREEN and the module's "
                + "build; never a fix without its failing test first.\n");
        prompt.append("--------------------------\n");
        if (stableFirst) {
            prompt.append("CONTEXT SO FAR:\n").append(previousStepContext).append("\n\n");
        }
        if (failureLedger != null && !failureLedger.isBlank()) {
            prompt.append(failureLedger);
        }
        if (stableFirst) {
            prompt.append("\nRespond now with ONE JSON object — Shape A, B or C as specified above.\n");
        }
        return prompt.toString();
    }

    /** The planning prompt without a failure ledger (the evals and the older call sites). */
    public String generatePrompt(PlanNode node, String previousStepContext) {
        return generatePrompt(node, previousStepContext, null);
    }

    /**
     * The execution tools that take a "remote" parameter, those of them the registry has: the generation tools join
     * when they are registered.
     */
    private String executionTools() {
        return String.join(", ", List.of("Bash", "RunProgram", "LocateFile", "HindsightDebugger", "GenerateTest",
                "GenerateCode").stream().filter(tools.names()::contains).toList());
    }

    /**
     * A refusal appended only its reason, never the plan it refused, and sat mid-prompt inside fifty thousand
     * characters of unchanged instructions - so the planner re-derived the same intent, in a new spelling, iteration
     * after iteration. The refusals since the last success now travel as ONE numbered list, plan and reason side by
     * side, at the very END of the prompt, and the list empties on success.
     */
    /**
     * With the ledger reset by a success, the planner proposed the SUCCEEDED `CodeGraph locate` a third time (a native
     * tool call, verbatim), its result twenty lines above in the context. The calls this turn has already made are
     * listed at the end of the prompt, beside the ledger, with their outcome - the empirical record the next step must
     * build on, not redo.
     */
    public static String executedBlock(Map<String, String> executedSignatures) {
        if (executedSignatures == null || executedSignatures.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(executedSignatures.entrySet());
        int from = Math.max(0, entries.size() - 20);
        StringBuilder sb = new StringBuilder("\nALREADY EXECUTED THIS TURN — their results are in CONTEXT SO FAR and are FACTS; "
                + "proposing any of them again (verbatim, re-spelled, or as a native tool call) is refused:\n");
        int n = 1;
        for (Map.Entry<String, String> e : entries.subList(from, entries.size())) {
            sb.append(n++).append(". ").append(e.getKey().replaceFirst("\\|", " ")).append(" → ").append(e.getValue()).append('\n');
        }
        sb.append("Build on those results: the next step is a NEW one the method calls for, or the Shape B final_answer "
                + "when they already answer the request.\n");
        return sb.toString();
    }

    public static String failureLedgerBlock(List<String> failedSteps) {
        if (failedSteps == null || failedSteps.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nFAILED SINCE YOUR LAST SUCCESS — each of these was REFUSED, in this order; proposing any of them "
                + "again (in any spelling, wider range, other tool doing the same) is a wasted step:\n");
        int n = 1;
        for (String s : failedSteps) {
            sb.append(n++).append(". ").append(s).append('\n');
        }
        sb.append("Your next plan must be something NOT on this list that moves the task forward — or, if CONTEXT SO FAR "
                + "already holds the evidence, the Shape B final_answer built from it.\n");
        return sb.toString();
    }

    public static final String[] REVIEW_LENSES = {
        "completeness",
        "grounding",
        "necessity",
        "effort",
        "reference",
    };

    /**
     * The AVAILABLE TOOLS section of the planning prompt, generated from the registry: each registered tool's own
     * documentation as "- Name: ...", its further lines indented so that they stay with it. Shared with the review
     * prompt, so the reviewers judge a plan against the same documentation the planner saw.
     */
    String availableToolsSection() {
        StringBuilder section = new StringBuilder("--- AVAILABLE TOOLS ---\n");
        tools.getAllTools().forEach((name, tool) -> {
            String doc = tool.getDocumentation().strip().replace("\n", "\n    ");
            section.append("- ").append(doc.startsWith(name + ":") ? doc : name + ": " + doc).append('\n');
        });
        return section.toString();
    }

    /** The contracts of the tools a plan uses (its "tool" names), taken from the AVAILABLE TOOLS section. */
    String toolContracts(String planJson) {
        if (planJson == null) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"tool\"\\s*:\\s*\"([A-Za-z][\\w-]*)\"").matcher(planJson);
        while (m.find()) {
            names.add(m.group(1));
        }
        StringBuilder out = new StringBuilder();
        boolean inEntry = false;
        for (String line : availableToolsSection().split("\n")) {
            boolean starts = false;
            for (String n : names) {
                if (line.startsWith("- " + n + ":") || line.startsWith("- " + n + " ")) {
                    starts = true;
                    break;
                }
            }
            if (starts) {
                inEntry = true;
            } else if (line.startsWith("- ") || line.startsWith("--- ")) {
                inEntry = false;   // the next tool's entry (or a section header) ends this one
            }
            if (inEntry) {
                // a tool entry may span CONTINUATION lines; truncating at the first one hid `tests` from critics.
                // «…» spans are PLANNER-ONLY warnings ("never a test class") - a reviewer that reads them turns
                // them into accusations against a plan that already follows them, so they are stripped here.
                String descriptive = line.replaceAll("«[^»]*»", "").replaceAll("\\s+$", "");
                out.append(descriptive.length() > 2000 ? descriptive.substring(0, 2000) + "…" : descriptive).append("\n");
            }
        }
        return out.toString();
    }

    /**
     * Builds a critical reviewer prompt: a critic checks the produced plan
     * against the conversation/context through one lens, to catch a MISSED point
     * (completeness), an INVENTED/assumed one (grounding), or an unnecessary
     * clarifying question that CONTEXT already answers (necessity).
     */
    public String reviewPrompt(String lens, String contextSoFar, String planJson) {
        boolean composition = tools.names().contains("SpecTestComposer");   // its spec-test passages, while it is registered
        boolean generation = tools.names().contains("TreeCodegen");   // its parameter semantics, while it is registered
        StringBuilder p = new StringBuilder();
        p.append("You are a critical reviewer of an agent's PLAN, focused on ").append(lens).append(".\n\n");
        p.append("CONTEXT (the conversation so far and the current request):\n").append(contextSoFar).append("\n\n");
        p.append("THE PLAN the agent produced (JSON):\n").append(planJson).append("\n\n");
        String contracts = toolContracts(planJson);
        if (!contracts.isBlank()) {
            // critics saw no tool documentation and called a documented action "incorrect"
            p.append("TOOL CONTRACTS (the documentation of the tools this plan uses — an action or parameter that appears "
                    + "here is VALID by definition; never call a documented action incorrect, never demand a different "
                    + "action or a parameter not listed here):\n").append(contracts).append("\n");
        }
        p.append("REVIEW GROUND RULES (apply to every judgement below):\n");
        p.append("1. The CURRENT request (the latest user turn) is AUTHORITATIVE. When it conflicts with an "
                + "earlier turn, the latest instruction SUPERSEDES the earlier one — do NOT reject a plan for "
                + "obeying the newest instruction (e.g. the user first said 'use curl' then later said 'use gh': "
                + "the plan SHOULD use gh, and rejecting it for 'not using curl' is WRONG).\n");
        p.append("2. The agent can run ANY shell command — git, gh, curl, jq, gradle, etc. — through its Bash/"
                + "RunProgram tools. 'That tool/command is not available' is NEVER a valid rejection for a standard "
                + "CLI; assume it is installed unless CONTEXT shows it failed.\n");
        p.append("3. Do NOT reject a plan merely because a DIFFERENT tool/approach could also work. If the plan's "
                + "chosen tool is CAPABLE of the task, that choice is valid. 'It should use curl instead of gh' (or "
                + "vice-versa) is NEVER a valid rejection, and you must NEVER demand a specific tool, "
                + "API, endpoint, or command the user did not name. Reject only for a CONCRETE defect (a missing "
                + "requirement, an invented/assumed detail, a placeholder/empty argument, or a value contradicting "
                + "CONTEXT) — not for tool preference.\n\n");
        switch (lens) {
            case "completeness" -> {
                if (generation) {
                    p.append("PARAMETER SEMANTICS you must not misread: in TreeCodegen, `method` is the "
                            + "NEW/edited method and `templateMethod` names an EXISTING test to MODEL ON — it is CORRECT and "
                            + "desirable for templateMethod to be an existing method; never reject a plan because its "
                            + "templateMethod already exists or 'is not new'. `expectTestOutcome=red` means the NEW test must "
                            + "fail against the current code (TDD) — that is the intended state, not a defect. ");
                }
                p.append("Reject if the plan MISSES anything the user asked for in CONTEXT "
                        + "(a requested feature, an explicit detail like a named API/exchange). List each missing point. "
                        + "EXCEPTION — a plan is ONE STEP, not the whole job: a single well-specified tool call for the "
                        + "CURRENT step (locate a file, generate the test, run the recording) must NOT be rejected because "
                        + "later steps (committing, reporting, follow-up runs) are not spelled out yet — those come after "
                        + "this tool's result. Reject only for a requested detail the CURRENT step itself omits."
                        + (generation ? " CODE IS WRITTEN BY THE TOOL: with TreeCodegen"
                        + (composition ? " or SpecTestComposer" : "") + " in the plan, the test's "
                        + "or fix's code is authored by the tool from `task` (the behaviour) and `templateMethod` — NEVER "
                        + "demand the test's content, assertions or code in the plan." : ""));
            }
            case "grounding" -> p.append("Reject if the plan ASSUMES, INVENTS, or STUBS anything not supported by "
                    + "CONTEXT - e.g. treating the task as a simulation, adding placeholder behavior, or choosing a "
                    + "detail the user did not give when CONTEXT does give it. List each unsupported assumption. "
                    + "EXCEPTION — tool availability is NEVER an assumption: every tool in AVAILABLE TOOLS is "
                    + "installed and callable; NEVER demand 'verifying' that a tool exists or is installed, and "
                    + "NEVER reject a plan for using a listed tool directly. A FILE PATH or LOCATION is likewise never "
                    + "an assumption to reject: the agent can DERIVE it (module source root + package) or DISCOVER it "
                    + "(LocateFile, CodeGraph locate) — never demand that the user supply a path. Reject a path only "
                    + "when it CONTRADICTS one CONTEXT gives (e.g. it drops the module directory) and state the "
                    + "correct one. "
                    + (generation ? "In TreeCodegen, `method` names the NEW method the tool CREATES — it does not exist "
                    + "yet and must not; only `templateMethod` must exist. " : "")
                    + "An ABSOLUTE path that ends with the relative path CONTEXT gives is the SAME location, never a "
                    + "contradiction."
                    + (generation ? " A tool's DOCUMENTED capability is never an assumption either: TreeCodegen "
                    + "generating a test that stubs a collaborator to throw and asserts a value is what it is for." : ""));
            case "necessity" -> p.append("If the plan is a clarifying question ('clarify'), DEFAULT TO REJECT: "
                    + "a clarification is almost always the wrong move when CONTEXT names the task. Approve the "
                    + "clarify ONLY if it asks for something genuinely absent from CONTEXT for which NO reasonable "
                    + "default exists. If CONTEXT gives the task (e.g. 'a Python program for the BTC price via "
                    + "Coinbase', or 'make a plan' for a task already described in earlier turns), REJECT and say the "
                    + "agent must proceed with sensible defaults instead of asking. If the plan is NOT a clarification, approve.");
            case "effort" -> p.append("Judge whether the plan's EFFORT matches the request. 'Simple/generic' means "
                    + "ONLY questions answerable from the model itself — who are you, your capabilities/tools, "
                    + "general how-to advice, arithmetic. A request for a LIVE/EXTERNAL fact (a price, the weather, "
                    + "a current value, a latest version) is NOT simple — it REQUIRES a tool, so a plan that runs one "
                    + "for it is CORRECT, not overthinking. REJECT only if the plan truly "
                    + "over-thinks a from-memory question (adds tools to answer 'who are you'), or UNDER-thinks a "
                    + "question about a SPECIFIC file or THIS codebase/project (answers from memory instead of "
                    + "inspecting). Otherwise approve.");
            case "reference" -> p.append("Check how the plan handles values that CONTEXT already provides. REJECT if: "
                    + "(a) it CLARIFIES/asks for a value (a URL, path, link, name, id) that is already present "
                    + "somewhere in CONTEXT — quote where it appears; (b) any action carries an EMPTY, MISSING, or "
                    + "PLACEHOLDER required argument, or invokes a bare command with no real arguments (e.g. `gh` or "
                    + "`curl` alone, `<url>`); or (c) an action's value CONTRADICTS the one in CONTEXT — the agent "
                    + "invented or altered a URL/path instead of copying the exact one the user gave. For each, say "
                    + "the exact value from CONTEXT the action should have used. EXCEPTION: a \"remote\" param of the "
                    + "form user@host:/path is CORRECT and does NOT contradict the projectDir — it deliberately "
                    + "combines the SSH target with that path; never flag \"remote\" as contradicting the plain path. "
                    + (composition ? "(d) a @SpecTest / spec / end-to-end network test is being written with TreeCodegen, "
                    + "TreeEdit or Bash instead of SpecTestComposer (compose) — REJECT and name SpecTestComposer. " : "")
                    + "Otherwise approve.");
            case "holistic" -> p.append(generation ? "Parameter semantics: TreeCodegen `templateMethod` = an EXISTING "
                    + "test to model on (correctly existing), `method` = the new/edited one, `expectTestOutcome=red` = "
                    + "TDD's intended failing state. " : "")
                    .append("You are a SENIOR engineer giving the plan one rigorous end-to-end "
                    + "review. REJECT for ANY concrete defect: a requirement from CONTEXT it misses; a detail it "
                    + "INVENTS/assumes/stubs; a wrong, unavailable, or bare-argument tool action; a clarification "
                    + "for something CONTEXT already answers; a value that contradicts CONTEXT; "
                    + (composition ? "a @SpecTest/spec test authored with anything but SpecTestComposer; " : "")
                    + "or a step that "
                    + "simply won't achieve the goal. Be specific and list each concrete problem. If the plan is "
                    + "sound, approve.");
            default -> p.append("Reject only for a concrete defect in your area.");
        }
        p.append("\nRespond with ONE JSON object and nothing else: {\"approved\": true|false, "
                + "\"issues\": [\"<concrete problem>\"]}. Approve only if you find no concrete problem in your area.");
        return p.toString();
    }

    /**
     * Builds the remote JSON-REPAIR prompt: a stronger model rewrites a small
     * model's malformed plan into ONE valid JSON object (same intent), and - if the
     * broken text embedded a program - moves the code out to a WriteProgram action
     * plus a fenced block, which is what made it invalid in the first place.
     */
    public String repairJsonPrompt(String rawResponse) {
        return "The text below was meant to be ONE JSON object (an agent plan) but is INVALID JSON — usually "
                + "because a program/command was embedded in a string with unescaped quotes or newlines. Rewrite "
                + "it as ONE VALID, minified JSON object with the SAME intent and correct escaping. If it embeds a "
                + "program, MOVE the code OUT: use an action {\"tool\":\"WriteProgram\",\"parameters\":"
                + "{\"language\":\"python\"|\"bash\",\"run\":true}} and append the program AFTER the JSON as a "
                + "fenced ```<language> block. Output ONLY the JSON object (and the optional fenced block), "
                + "nothing else.\n\nINVALID:\n" + rawResponse;
    }

    /**
     * Builds the terminal-answer refiner prompt: a stronger model rewrites a weak
     * final answer to be accurate and grounded in the actual tool results.
     */
    public String refineAnswerPrompt(String request, String contextSoFar, String proposedAnswer) {
        return "Improve the FINAL ANSWER to the user's request, using the context and tool results below. "
                + "Report the ACTUAL values from the results; stay accurate and grounded; invent NOTHING and add "
                + "no commentary. Return ONLY the improved answer text.\n\nREQUEST: " + request
                + "\n\nCONTEXT:\n" + contextSoFar + "\n\nPROPOSED ANSWER:\n" + proposedAnswer;
    }

    /**
     * Builds the remote PLAN-REFINER prompt: a stronger model rewrites the local
     * brain's branch-plan into a better one, returned in the SAME Shape-A contract so
     * it can be parsed and used directly. Kept compact (short tool list, capped
     * context) to fit a small remote context window.
     */
    public String refinePrompt(String compactTools, String contextSoFar, String proposedPlanJson) {
        return "You are an expert planner improving another agent's plan. Available tools (name: purpose):\n"
                + compactTools + "\nCONTEXT (recent, most relevant last):\n" + contextSoFar
                + "\n\nThe agent proposed this plan:\n" + proposedPlanJson
                + "\n\nReturn an IMPROVED plan as ONE JSON object and NOTHING else, SAME shape: "
                + "{\"branches\":[{\"name\":\"..\",\"description\":\"..\",\"confidence\":0.0-1.0,"
                + "\"actions\":[{\"tool\":\"<ToolName>\",\"parameters\":{..}}]}]}. Rules: fix gaps, drop "
                + "unnecessary steps, make every action concrete with REAL arguments copied verbatim from CONTEXT "
                + "(e.g. a full URL/path), use ONLY the tools listed above, and never leave a placeholder or bare "
                + "command. If the plan is already good, return it unchanged. Output JSON only.";
    }

    /**
     * Prompt for the remote model to AUTHOR a fresh plan when the local brain can't
     * produce a parseable one. Given the goal + context (prior results and any critic
     * rejection), it plans the corrective next step directly.
     */
    public String authorPlanPrompt(String compactTools, String userRequest, String contextSoFar) {
        return "You are the PLANNER for an agent. The small local model failed to produce a valid plan, so "
                + "you must author the corrective next step. Available tools (name: purpose):\n" + compactTools
                + "\n\nUSER REQUEST: " + userRequest
                + "\n\nCONTEXT SO FAR (prior tool results and any reviewer rejection are at the end):\n"
                + contextSoFar
                + "\n\nAuthor ONE JSON object and NOTHING else, this shape: {\"branches\":[{\"name\":\"..\","
                + "\"description\":\"..\",\"confidence\":0.0-1.0,\"actions\":[{\"tool\":\"<ToolName>\","
                + "\"parameters\":{..}}]}]}. Make progress toward answering the request using the CONTEXT: if a "
                + "reviewer rejected the last plan, FIX exactly what it flagged. Use REAL arguments copied verbatim from CONTEXT, only the "
                + "tools listed, no placeholders. Output JSON only.";
    }

    /** The prompt for the task's first plan, written by a hosted model before the step planner takes over. */
    public String initialPlanPrompt(String compactTools, String userRequest, String contextSoFar) {
        return "You are the PLANNER for an agent. Write the FIRST plan for this task: the actions the agent runs first. "
                + "Available tools (name: purpose):\n" + compactTools
                + "\n\nUSER REQUEST: " + userRequest
                + "\n\nCONTEXT SO FAR:\n" + contextSoFar
                + "\n\nAuthor ONE JSON object and NOTHING else, this shape: {\"branches\":[{\"name\":\"..\","
                + "\"description\":\"..\",\"confidence\":0.0-1.0,\"actions\":[{\"tool\":\"<ToolName>\","
                + "\"parameters\":{..}}]}]}. Put the approach to the whole task in the branch's description; its later "
                + "steps are planned after these results, so the actions are only the first steps, the ones whose results "
                + "the rest needs. Use REAL arguments copied verbatim from the request and the context, only the tools "
                + "listed, no placeholders. Output JSON only.";
    }

    /**
     * The task plan (user, 2026-10-21): the task's steps, numbered, written before any command runs. The agent plans the
     * commands of each step when it gets there, so a step says what is done and how to tell, not the commands.
     */
    public String taskPlanPrompt(String compactTools, String userRequest, String contextSoFar) {
        return "You are the PLANNER for an agent. Write the TASK PLAN for this request: the numbered steps that complete it, "
                + "in order, each a goal that one or a few tool calls reach, the last one the answer the request asks for. "
                + "When the request numbers its own steps, follow them, one step each. The agent plans each step's commands "
                + "when it gets there, so a step says WHAT is done and how to tell it is done, not the commands. Available "
                + "tools (name: purpose):\n" + compactTools
                + "\n\nUSER REQUEST: " + userRequest
                + "\n\nCONTEXT SO FAR:\n" + contextSoFar
                + "\n\nReply with ONE JSON object and NOTHING else: {\"steps\": [{\"goal\": \"...\"}, ...]}";
    }

    /** Whether the current step of the task plan is done, judged from what the agent did for it and what came back. */
    public String stepDonePrompt(String userRequest, String planBlock, String step, String results) {
        return "You judge an agent's progress on a task.\n\nUSER REQUEST: " + userRequest + "\n" + planBlock
                + "\nTHE CURRENT STEP: " + step
                + "\n\nWHAT THE AGENT DID AND WHAT CAME BACK:\n" + results
                + "\n\nIs the CURRENT step done: did its goal happen, as these results show (not as the agent says)? "
                + "Reply with ONE JSON object and NOTHING else: {\"done\": true|false, \"why\": \"one sentence that cites "
                + "the result\"}";
    }

    /** The steps ahead checked against what the done steps found: kept as they are, or rewritten. */
    public String planAheadPrompt(String compactTools, String userRequest, String planBlock, String contextSoFar) {
        return "You are the PLANNER for an agent working through a task plan. A step was just done. Check the steps ahead "
                + "(the CURRENT one and those after it) against what the done steps found: give them back unchanged when "
                + "they still fit, rewritten when they do not (a step the results made unnecessary goes; one they show is "
                + "missing comes in). Each step is a GOAL the agent still has to reach, said in full (what it does and how to "
                + "tell it is done), never a result, a value or a summary of what was done. Available tools (name: "
                + "purpose):\n" + compactTools
                + "\n\nUSER REQUEST: " + userRequest + "\n" + planBlock
                + "\nCONTEXT SO FAR:\n" + contextSoFar
                + "\n\nReply with ONE JSON object and NOTHING else, the CURRENT step and the ones after it: "
                + "{\"steps\": [{\"goal\": \"...\"}, ...]}";
    }

    /** The current and later steps rewritten, because the attempts at the current one brought nothing. */
    public String revisePlanPrompt(String compactTools, String userRequest, String planBlock, String contextSoFar, String why) {
        return "You are the PLANNER for an agent working through a task plan. The CURRENT step is not done after several "
                + "attempts (" + why + "). Rewrite the CURRENT step and the steps after it so that they can be done with "
                + "what the context shows: smaller steps, another way, or the one the context shows is missing first. The "
                + "done steps stay as they are. Available tools (name: purpose):\n" + compactTools
                + "\n\nUSER REQUEST: " + userRequest + "\n" + planBlock
                + "\nCONTEXT SO FAR:\n" + contextSoFar
                + "\n\nReply with ONE JSON object and NOTHING else, the new CURRENT step and the ones after it: "
                + "{\"steps\": [{\"goal\": \"...\"}, ...]}";
    }

    /**
     * The same model's answer gate approved a TRUE POSITIVE whose decisive consequence ("control over the booking's
     * lifecycle - guest, check-out date, cancel rights") had no line behind it and was contradicted by the very
     * comparison the answer quoted (guest and check-out date are compared). A verdict is read by a DIFFERENT model
     * first: every claimed consequence against the evidence, nothing added.
     */
    public String verdictCriticPrompt(String evidence, String proposedVerdict) {
        return "You are a SECOND REVIEWER — a different model from the one that wrote this verdict. Below is the EVIDENCE: "
                + "the tool results (source lines, lookups) gathered this turn, and nothing else is known.\n" + evidence
                + "\n\nPROPOSED VERDICT:\n" + proposedVerdict
                + "\n\nCheck EVERY claimed CONSEQUENCE — what an actor can do, gain, control, alter, delete, redirect, bypass, "
                + "or make someone else pay — against the EVIDENCE only. A claim is SUPPORTED when a quoted line shows it. A "
                + "claim is UNSUPPORTED when no line shows it, or when a quoted line contradicts it (a field that a comparison "
                + "REQUIRES to be equal cannot differ between the two records it calls identical; a cost the actor pays itself "
                + "is not a harm to someone else). A consequence the verdict attributes to the REPORT and rejects is the report's "
                + "claim, not the verdict's — do not list it. Do not add facts of your own and do not judge style. Respond with ONE JSON "
                + "object and nothing else: {\"unsupported_claims\": [\"<claim> — <the line that is missing or contradicts it>\"], "
                + "\"verdict_follows\": true|false, \"note\": \"<one sentence: does the verdict follow from the SUPPORTED claims alone?>\"}";
    }

    public String answerGatePrompt(String successfulResults, String proposedAnswer, boolean isClarify) {
        return "You are the ANSWER GATE for an agent. These tool results already SUCCEEDED this turn:\n"
                + successfulResults + "\n\nThe agent wants to END the turn with "
                + (isClarify ? "this CLARIFYING QUESTION" : "this FINAL ANSWER") + ":\n" + proposedAnswer
                + "\n\nA terminal response MUST convey the information the user asked for, USING the successful "
                + "results above. REJECT if the agent instead asks the user for, or merely describes/plans/"
                + "shows-the-command-for, something the results already provide (e.g. asks 'please provide the "
                + "URL' or prints `gh pr view <url>` when a result already fetched that PR). APPROVE only if the "
                + "answer actually reports the substance of the results (or the results genuinely do not address "
                + "the user's request). You judge REPORTING, not the code: never assert where a defect 'really' "
                + "is, never propose an alternative fact of your own — a claim in the answer that is consistent with a "
                + "result above (a method a catches/locate result names, a line it gives) STANDS; flag only what the "
                + "answer omits or claims WITHOUT any result behind it. Respond with ONE JSON object and nothing else: "
                + "{\"approved\": true|false, \"issues\": [\"<what the answer ignores or should report>\"]}.";
    }

    /**
     * LM Studio had reloaded the driver with an 8192-token context and the planning
     * prompt (55k characters) came back as HTTP 400 "tokens to keep … greater than the context length"; the halt said
     * "load the model", the eval's last cases failed the same way, and nothing in the harness watched prompt size. The
     * CONTEXT SO FAR is trimmed from its OLDEST iterations when the prompt would exceed the budget; the request, the
     * latest results and the failure ledger are what the planner needs, the first iterations' summaries are not.
     */
    public static String fitContext(String context, int maxChars) {
        if (context == null || context.length() <= maxChars) {
            return context;
        }
        Matcher m = Pattern.compile("(?m)^\\[Iteration \\d+\\]").matcher(context);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        int cut = -1;
        for (int i = 0; i < starts.size(); i++) {
            if (context.length() - starts.get(i) <= maxChars) {
                cut = starts.get(i);
                break;
            }
        }
        String head = context.substring(0, starts.isEmpty() ? 0 : starts.get(0));   // the request line(s) before the first iteration
        if (cut < 0) {
            cut = Math.max(0, context.length() - maxChars);
        }
        int dropped = 0;
        for (int s : starts) {
            if (s < cut) {
                dropped++;
            }
        }
        String kept = context.substring(cut);
        String headKept = head.length() > 2000 ? head.substring(0, 2000) + "…" : head;
        return headKept + "\n[" + dropped + " earlier iteration(s) trimmed to fit the model's context — their tool results still count as executed]\n" + kept;
    }
}
