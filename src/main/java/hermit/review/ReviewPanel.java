package hermit.review;

import hermit.answer.AnswerGate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The review panel's rulings on its critics: which objections a critic raised do not hold, so a plan is not refused for
 * them. A critic that demands the later steps of the task, doubts a registered tool or a path the request names, calls
 * a grounded value ungrounded, disputes a documented value or the plan's schema, or overrules the evidence is set
 * aside; the issues the critics agree on are merged once.
 */
public final class ReviewPanel {

    private ReviewPanel() {
    }

    private static final Pattern COMPLAINT = Pattern.compile(
            "(?i)\\b(not|no|never|missing|lacks?|fails?(?![-\\w])|failed|should|must|wrong|incorrect|invalid|fabricat\\w*|invent\\w*|"
            + "unsupported|unverified|placeholder|does ?n['o]t|did ?n['o]t|is ?n['o]t|are ?n['o]t|cannot|can't|without|omit\\w*|"
            + "ignore[sd]?|reject\\w*|instead|rather than|but)\\b");

    private static final Pattern DEMANDED_PARAM = Pattern.compile(
            "(?i)(?:param(?:eter)?|argument|field|key|option)s?\\s*(?:named\\s*|called\\s*)?[`'\"]([A-Za-z_][A-Za-z0-9_]*)[`'\"]"
            + "|[`'\"]([A-Za-z_][A-Za-z0-9_]*)[`'\"]\\s+(?:param(?:eter)?|argument|field|key|option)\\b");
    private static final Pattern DEMAND_WORDS = Pattern.compile(
            "(?i)\\b(missing|required?|requires|lacks?|lacking|omits?|omitted|needs?|should (?:include|specify|provide|have)|"
            + "must (?:include|specify|provide|have)|does not (?:include|specify|provide)|without)\\b");

    /** a critic demanded "the required parameter 'exceptionType'" - a parameter no tool in the plan
     *  declares. The demanded name is read out of the issue; when it is absent from the vocabulary of the plan's tool
     *  documentation and parameter keys, the issue is an invention and must not block the plan. */
    public static boolean fabricatedParameterIssue(String issue, Set<String> vocabulary) {
        if (issue == null || vocabulary == null || vocabulary.isEmpty() || !DEMAND_WORDS.matcher(issue).find()) {
            return false;
        }
        Matcher m = DEMANDED_PARAM.matcher(issue);
        boolean sawName = false;
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            sawName = true;
            if (vocabulary.contains(name.toLowerCase(Locale.ROOT))) {
                return false;   // a real parameter is being asked for
            }
        }
        return sawName;
    }

    private static final Pattern GENERATED_CODE_DEMAND = Pattern.compile(
            "(?i)\\b(exact|specific|actual|full|complete|concrete|detailed)\\b[^.]{0,40}\\b(content|contents|code|body|"
            + "implementation|assertions?|statements?|source)\\b[^.]{0,80}\\b(test|fix|method|change)\\b"
            + "|\\b(content|code|body|implementation)\\s+of\\s+the\\s+(new|failing|generated|unit)\\b");

    /** the completeness critic demanded "the exact content of the new failing unit test" in the PLAN.
     *  With a code-authoring tool in the plan (TreeCodegen) the code is written by the tool from the
     *  task behaviour and template - a plan never carries it, so such an issue is a misreading, not a gap. */
    public static boolean demandsGeneratedCode(String issue) {
        if (issue == null || DEMANDED_PARAM.matcher(issue).find()) {
            return false;
        }
        // the third phrasing a rigid sequence pattern could not span ("the specific code that should
        // be written is not included in the plan", "does not explicitly state how the test will be written").
        // CO-OCCURRENCE of a code-artifact word with a demand word is the stable signal (the caller has already
        // established that a code-authoring tool is in the plan).
        boolean coOccurrence = CODE_ARTIFACT.matcher(issue).find() && CODE_DEMAND.matcher(issue).find();
        return coOccurrence || GENERATED_CODE_DEMAND.matcher(issue).find() || DOUBTS_GENERATOR.matcher(issue).find()
                || PRECOMPUTE_DEMAND.matcher(issue).find() || HOW_WRITTEN.matcher(issue).find()
                || OMITS_AUTHORING.matcher(issue).find();
    }

    private static final Pattern CODE_ARTIFACT = Pattern.compile(
            "(?i)\\b(code|content|contents|body|implementation|assertions?|statements?|source|snippet)\\b");
    private static final Pattern CODE_DEMAND = Pattern.compile(
            "(?i)\\b(specific|exact|actual|concrete|detailed|verbatim|not included|omits?|omitted|does not include|"
            + "missing|lacks?|should be (?:included|written|provided|specified|stated|shown))\\b");
    /** "The plan omits the step to actually write the failing unit test" - the step the code-authoring tool IS. */
    private static final Pattern OMITS_AUTHORING = Pattern.compile(
            "(?i)\\b(omits?|omitted|does not include|missing|lacks?|no)\\b[^.]{0,60}"
            + "\\b(?:write|writing|generate|generating|create|creating|author|authoring|implement|implementing)\\b"
            + "[^.]{0,40}\\b(test|fix|method|code)\\b");
    private static final Pattern HOW_WRITTEN = Pattern.compile(
            "(?i)\\bhow\\b[^.]{0,70}\\b(?:will|to|should|would) be\\s+(written|generated|implemented|authored|created|composed)\\b");

    /** critics demanded "a step to compute the expected hash (hex SHA-256 of the content)" and
     *  complained that "the 'contents' variable is not defined" - values and variables of the TEST the tool composes;
     *  after two such rounds the planner replaced the generator with a shell sha256sum. */
    private static final Pattern PRECOMPUTE_DEMAND = Pattern.compile(
            "(?i)\\b(compute|computing|calculate|calculating|determine|derive|obtain|pre-?compute)\\b[^.]{0,50}"
            + "\\b(expected\\s+)?(hash|checksum|digest|sha-?\\d*|value|memo|result)\\b"
            + "|\\b(variable|identifier|symbol)\\b[^.]{0,50}\\b(is|was|be)\\s+(not\\s+)?(defined|declared|provided|initiali[sz]ed|available)\\b");

    private static final Pattern DOUBTS_GENERATOR = Pattern.compile(
            "(?i)\\bassum\\w*\\b[^.]{0,30}\\b(TreeCodegen|SpecTestComposer|the tool|the code generator)\\b[^.]{0,20}"
            + "\\b(can|could|will|is able to)\\b[^.]{0,20}\\b(generate|write|author|produce|create|compose)\\b");
    private static final Pattern LATER_STEP_DEMAND = Pattern.compile(
            "(?i)\\b(omits?|omitted|does not include|does not report|not report(?:ing|ed)?|lacks?|lacking|missing|no step)\\b[^.]{0,100}"
            + "\\b(emulator (?:verdict|prediction|gate)|emulate\\b[^.]{0,40}\\b(?:action|predict\\w*)|(?:real |the )?commit hash|"
            + "predict\\w*[^.]{0,40}\\b(?:RED|GREEN|outcome)|(?:real )?gradle (?:test )?(?:run|outcome|result|verdict)|"
            + "hindsight(?:debugger)?\\b[^.]{0,40}\\b(?:run|output|evidence|recording|proof|capture)|"
            + "(?:run|running|capture|capturing)\\b[^.]{0,30}\\bhindsight|commit(?:ting)? (?:the|it|hash)|git commit|"
            + "final (?:report|answer)|report(?:ing)? (?:the|its) (?:result|outcome|verdict))\\b");

    public static boolean demandsLaterSteps(String issue) {
        if (demandsLaterMethodSteps(issue)) {
            return true;
        }
        return issue != null && LATER_STEP_DEMAND.matcher(issue).find() && !DEMANDED_PARAM.matcher(issue).find()
                && !issue.matches("(?is).*\\b(parameter|param|expectTestOutcome|templateMethod|targetReach|testFilter)\\b.*");
    }

    private static final Pattern PATH_TOKEN = Pattern.compile(
            "(?:/?[\\w.\\-]+)(?:/[\\w.\\-]+)+(?:\\.java)?");
    private static final Pattern CONTRADICTION_WORDS = Pattern.compile(
            "(?i)\\b(contradict\\w*|inconsisten\\w*|differs?|different|mismatch\\w*|does not match|instead of|rather than|not the same)\\b");

    /** "the path /abs/…/spool/spool-core/src/test/java/… contradicts the context's
     *  spool/spool-core/src/test/java" - an absolute path and the relative path it ends with are the SAME location. */
    public static boolean falsePathContradiction(String issue) {
        if (issue == null || !CONTRADICTION_WORDS.matcher(issue).find()) {
            return false;
        }
        List<String> paths = new ArrayList<>();
        Matcher m = PATH_TOKEN.matcher(issue);
        while (m.find()) {
            String t = m.group();
            if (t.contains("/") && !t.matches("^[\\d./-]+$")) {
                paths.add(t.replaceAll("^\\./", "").replaceAll("/+$", ""));
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = 0; j < paths.size(); j++) {
                if (i == j) {
                    continue;
                }
                String a = paths.get(i);
                String b = paths.get(j);
                if (!a.equals(b) && (a.endsWith("/" + b) || a.endsWith(b) && a.length() > b.length()
                        || a.contains("/" + b + "/") || a.contains("/" + b + "."))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A token the issue puts in quotes or backticks, with an {@code action=}/{@code op=} prefix stripped. */
    private static final Pattern QUOTED_VALUE = Pattern.compile(
            "[`'\"](?:action\\s*=\\s*|op\\s*=\\s*)?([A-Za-z_][\\w.#$-]*)[`'\"]");

    /** Phrases that dispute a value's EXISTENCE or VALIDITY - as opposed to asking for something to be ADDED
     *  ("the plan lacks/omits X", which is a legitimate completeness complaint and must survive). */
    private static final Pattern EXISTENCE_DISPUTE = Pattern.compile(
            "(?i)\\b(incorrect|invalid|unsupported|undocumented|unknown|wrong|no such|unnecessary|redundant"
            + "|should (?:be )?(?:omitted|removed|dropped)|(?:is |are )?not (?:required|needed|necessary)"
            + "|not\\s+(?:a\\s+|an\\s+)?(?:valid|supported|documented|listed|recognised|recognized|existing|available)"
            + "|does\\s+not\\s+(?:exist|have)|is\\s+missing\\s+from"
            + "|(?:does|do)\\s+not\\s+support|doesn't\\s+support|(?:operations?|actions?|ops)\\s+excludes?"
            // "the contract does not list X as a supported operation", "TreeEdit only supports A, B"
            + "|(?:does|do)\\s+not\\s+list|doesn't\\s+list"
            + "|(?:(?-i:\\b[A-Z][A-Za-z]+)|\\btool)\\s+only\\s+(?:supports?|allows?|accepts?)"
            + "|valid\\s+(?:actions?|parameters?|values?|options?)\\s+(?:are|include|for)"
            + "|according\\s+to\\s+the\\s+TOOL\\s+CONTRACTS)\\b");

    private static final Pattern SYMBOL_TOKEN = Pattern.compile(
            "\\b([A-Za-z_][\\w.$]*#[A-Za-z_]\\w*)\\b");
    private static final Pattern PREFERENCE_PHRASE = Pattern.compile(
            "(?i)\\b(should (?:be )?(?:target\\w*|use|point|analyse|analyze|query)|instead of|rather than"
            + "|but it should|ought to (?:be|target|use)|would be better)\\b");

    /**
     * a critic rejected the plan twice because its reach target was
     * {@code PrintSpooler#printBatch} and "should be targeting"
     * {@code PrintSpooler#flushPending} - BOTH are named in CONTEXT and both answer the question.
     * Ground rule 3 forbids demanding a different tool; the same holds for a different ARGUMENT when the plan's own
     * value is grounded. Fires only when the issue swaps one context-grounded symbol for another.
     */
    public static boolean prefersAnotherGroundedSymbol(String issue, String planJson, String contextSoFar) {
        if (issue == null || planJson == null || contextSoFar == null
                || !PREFERENCE_PHRASE.matcher(issue).find()) {
            return false;
        }
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        Matcher m = SYMBOL_TOKEN.matcher(issue);
        while (m.find()) {
            symbols.add(m.group(1));
        }
        if (symbols.size() < 2) {
            return false;
        }
        boolean planUsesOne = false;
        for (String sym : symbols) {
            String simple = sym.substring(sym.lastIndexOf('.') + 1);
            if (planJson.contains(sym) || planJson.contains(simple)) {
                planUsesOne = true;
            }
            String method = sym.substring(sym.indexOf('#') + 1);
            if (!contextSoFar.contains(method)) {
                return false;   // the alternative is NOT grounded - a real correction, keep it
            }
        }
        return planUsesOne;
    }

    /** the critic called the documented CodeGraph action `tests` "incorrect … use reach or callees" -
     *  reviewers see no tool documentation, so a documented action/parameter value they dispute is their invention. */
    /**
     * "The TreeCodegen tool is not documented or available for use" / "is not a valid tool
     * according to the TOOL CONTRACTS" - the reviewer disputed the existence of a REGISTERED tool. A tool the
     * registry holds exists; an existence dispute naming it is a misreading, not an issue.
     */
    public static boolean disputesRegisteredTool(String issue, Set<String> toolNames) {
        if (issue == null || toolNames == null || toolNames.isEmpty() || !EXISTENCE_DISPUTE.matcher(issue).find()) {
            return false;
        }
        String lower = issue.toLowerCase(Locale.ROOT);
        if (!(lower.contains("tool") || lower.contains("action") || lower.contains("documented"))) {
            return false;
        }
        for (String tool : toolNames) {
            if (tool != null && !tool.isBlank() && issue.matches("(?s).*\\b" + Pattern.quote(tool) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    public static boolean disputesDocumentedValue(String issue, Set<String> vocabulary) {
        if (issue == null || vocabulary == null || vocabulary.isEmpty()
                || !EXISTENCE_DISPUTE.matcher(issue).find()) {
            return false;
        }
        // the critic's phrasing kept changing ("is incorrect", "is not documented", "assumes the tool
        // has an action called 'tests', but according to the TOOL CONTRACTS the valid actions are …"). Co-occurrence of
        // a DOCUMENTED quoted token with an existence dispute anywhere in the issue is the stable signal.
        Matcher m = QUOTED_VALUE.matcher(issue);
        while (m.find()) {
            if (vocabulary.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // "TreeEdit does not support the moveMember operation; its documented operations exclude moveMember" refused an
        // exact round twice; the op was unquoted and TreeEdit documents it. An unquoted camel-case op or action the
        // tool documents counts as well.
        m = CAMEL_VALUE.matcher(issue);
        while (m.find()) {
            if (vocabulary.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** An unquoted camel-case word ({@code moveMember}, {@code replaceMember}): an op or action name, not prose. */
    private static final Pattern CAMEL_VALUE = Pattern.compile(
            "\\b([a-z][a-z0-9]*[A-Z][A-Za-z0-9]*)\\b");

    private static final Pattern OVERRULE_WORDS = Pattern.compile(
            "(?i)\\b(incorrectly|wrongly|instead|actually|should (?:be|state|say)|is in fact|really)\\b");
    private static final Pattern QUOTED_TERM = Pattern.compile(
            "[`'\"]([A-Za-z_][\\w.#$]*[A-Za-z0-9_])[`'\"]");

    /** the LLM answer gate rejected a CORRECT answer, asserting the defect was in a different method
     *  than the one the catches result names. A gate issue that disputes a term the successful results themselves
     *  contain is the gate overruling evidence - advisory at most, never a rejection. */
    public static boolean gateOverrulesEvidence(String issue, String evidence) {
        if (issue == null || evidence == null || evidence.isBlank() || !OVERRULE_WORDS.matcher(issue).find()) {
            return false;
        }
        Matcher m = QUOTED_TERM.matcher(issue);
        while (m.find()) {
            String term = m.group(1);
            String simple = term.contains("#") ? term.substring(term.indexOf('#') + 1) : term;
            if (simple.length() >= 4 && (evidence.contains(term) || evidence.contains("#" + simple)
                    || evidence.contains("method=" + simple) || evidence.contains(simple + "("))) {
                return true;
            }
        }
        return false;
    }

    /** the grounding critic "assumes the test method 'X' exists" where X is the NEW method the plan's
     *  TreeCodegen call creates - the semantics (method = the NEW method) were only in the completeness lens. */
    public static boolean disputesNewMethodExistence(String issue, String planJson) {
        if (issue == null || planJson == null) {
            return false;
        }
        Matcher pm = Pattern.compile(
                "\"method\"\\s*:\\s*\"(?:[\\w.]*[#.])?([A-Za-z_]\\w*)\"").matcher(planJson);
        Set<String> newMethods = new HashSet<>();
        while (pm.find()) {
            newMethods.add(pm.group(1));
        }
        if (newMethods.isEmpty() || !issue.matches("(?is).*\\b(assum\\w*|exist\\w*|does not exist|not (?:yet )?(?:defined|present|found))\\b.*")) {
            return false;
        }
        for (String m : newMethods) {
            if (issue.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern SCHEMA_FIELD = Pattern.compile(
            "(?i)[`'\"]?\\b(branches|actions|tool|parameters|final_answer|clarification|confidence|description|name)\\b[`'\"]?");
    private static final Pattern RENAME_PHRASE = Pattern.compile(
            "(?i)\\b(should (?:be|be called|be named|be renamed|use)|must (?:be|be called|be named)|ought to be"
            + "|instead of|rather than|rename[d]?\\s+to|is expected to be)\\b");

    /**
     * a critic rejected the plan because "the plan's 'branches' field should be 'steps', as per the
     * TASK PLAYBOOK instruction". The plan's JSON SCHEMA is fixed by the agent contract; a reviewer never gets to
     * rename its fields, and a user prompt never redefines them.
     */
    /**
     * the reviewer rejected two plans running because "the plan's confidence is set to 1.0,
     * which suggests absolute certainty". The confidence is a field of the plan schema the planner must fill; its
     * value is never a defect of the plan.
     */
    public static boolean disputesPlanConfidence(String issue) {
        if (issue == null) {
            return false;
        }
        String t = issue.toLowerCase(Locale.ROOT);
        return t.matches("(?s).*\\bconfidence\\b.*\\b(is set to|level|value|of \\d|too high|absolute certainty|overconfiden\\w*)\\b.*")
                || t.matches("(?s).*\\b(too high|absolute certainty|overconfiden\\w*)\\b.*\\bconfidence\\b.*");
    }

    /**
     * "the plan contains an action with a placeholder value for the 'command' parameter" while
     * the plan's parameters carried no placeholder at all (the reviewer even quoted the concrete command it wanted).
     * A placeholder claim is checked against the plan text: no marker, no issue.
     */
    public static boolean claimsPlaceholderWhereNoneIs(String issue, String planJson) {
        if (issue == null || planJson == null) {
            return false;
        }
        String t = issue.toLowerCase(Locale.ROOT);
        if (!t.contains("placeholder")) {
            return false;
        }
        Pattern marker = Pattern.compile(
                "<[^>\"\\n]{1,60}>|\\.\\.\\.|…|\\bTODO\\b|\\bTBD\\b|\\bXXX\\b|/path/to/|\\b(?:X|Y|VALUE|CALL|NAME|FILE|CLASS)\\b|\\$\\{[^}]*\\}");
        return !marker.matcher(planJson).find();
    }

    public static boolean disputesThePlanSchema(String issue) {
        if (issue == null || !RENAME_PHRASE.matcher(issue).find()) {
            return false;
        }
        Matcher m = SCHEMA_FIELD.matcher(issue);
        while (m.find()) {
            String field = m.group(1).toLowerCase(Locale.ROOT);
            // the field must be quoted or called a "field"/"key"/"property" - a bare word like "name" is too common
            String around = issue.substring(Math.max(0, m.start() - 12),
                    Math.min(issue.length(), m.end() + 12)).toLowerCase(Locale.ROOT);
            if (around.contains("field") || around.contains("key") || around.contains("property")
                    || issue.contains("'" + m.group(1) + "'") || issue.contains("\"" + m.group(1) + "\"")
                    || issue.contains("`" + m.group(1) + "`")) {
                return !field.isEmpty();
            }
        }
        return false;
    }

    private static final Pattern DOUBT_PHRASE = Pattern.compile(
            "(?i)\\b(not clear (?:if|whether|that)|unclear (?:if|whether)|should confirm|must confirm|needs? to be "
            + "(?:confirmed|verified|validated)|should verify|please verify|cannot be sure|no guarantee that)\\b");

    /**
     * a critic doubted whether the named template test "actually exercises the queued-execution
     * path" although the tests result in CONTEXT lists it with its call path and score. A demand to re-verify a fact
     * the context already establishes costs a round and adds nothing.
     */
    /**
     * once the request carried the review method as numbered steps, every critic
     * refused the first plan for "only performing step 1" and "omitting the required steps to trace callers, gather
     * runtime evidence, compare, produce the verdict" - two review rounds and an escalation before a single read.
     * A plan is ONE step; a complaint that later steps are missing from it is not a defect of the plan.
     */
    private static final Pattern LATER_STEPS = Pattern.compile(
            "(?is)\\b(only|merely|just|solely)\\b.{0,80}\\b(perform|do|does|cover|locat|read|address|includ|contain|execut)\\w*\\b.{0,160}"
            + "\\b(omit|does not|doesn.t|fails? to|without|but not|lack|missing|no)\\w*\\b.{0,120}"
            + "\\b(trace|gather|compar|produc|verdict|steps?|runtime|remaining|subsequent|later|next|analy[sz]|assess|determin|evaluat)\\w*");

    /**
     * the same complaint in a new shape - "Plan incomplete: missing steps to trace the
     * method call chain, gather runtime evidence, compare findings with code behavior, and produce a verdict", "Plan
     * missing required steps: trace callers, …", "Plan does not include all required steps; only locates the method" -
     * passed the earlier pattern (which wanted "only performs … omits …"), refused the one-step plan twice and spent a
     * paid escalation. A complaint about missing STEPS that names two or more of the method's later stages, or that
     * says the plan only does its one step, is the same non-defect.
     */
    private static final Pattern MISSING_STEPS = Pattern.compile(
            "(?is)\\b(incomplete|missing|omits?|omitted|does not include|doesn.t include|lacks?|lacking|without|no)\\b.{0,60}"
            + "\\b(steps?|stages?|phases?)\\b|\\b(steps?|stages?|phases?)\\b.{0,30}\\b(missing|omitted|not included|absent)\\b");
    private static final Pattern METHOD_STAGE = Pattern.compile(
            "(?i)\\b(trac(?:e|ing)|callers?|call chain|runtime|recording|compar(?:e|ing|ison)|verdict|rul(?:e|ing) on|"
            + "gather(?:ing)? (?:runtime )?evidence|produc(?:e|ing) (?:a |the )?(?:verdict|ruling)|findings?)\\b");
    private static final Pattern ONLY_ONE_STEP = Pattern.compile(
            "(?is)\\b(only|merely|just|solely)\\b.{0,20}\\b(locat|read|perform|do|does|cover|includ|execut|run|address)\\w*\\b");

    /**
     * the local critic refused `CodeGraph locate` because "CodeGraph locate / callers / reach are
     * not standard CLI tools and cannot be assumed to be installed unless explicitly stated in CONTEXT" - two review rounds
     * and an escalation over a tool the agent registers itself. A critic doubting that a REGISTERED tool exists, is
     * installed, is available or is "standard" is wrong by definition and its issue is dropped.
     */
    private static final Pattern TOOL_EXISTENCE_DOUBT = Pattern.compile(
            "(?is)\\b(not|n.t|cannot|can.t|never|no)\\b[^.]{0,50}\\b(installed|available|present|exists?|assumed|standard|"
            + "known|recogni[sz]ed|real|actual|valid) (?:cli |command.line |shell )?(tools?|commands?|utilit(?:y|ies)|binar(?:y|ies))"
            + "|\\b(not|n.t|cannot|can.t|never)\\b[^.]{0,50}\\b(installed|available|present|exists?|be assumed)\\b"
            + "|\\bunknown tool\\b|\\bno such tool\\b");

    /** the critic says the plan repeats / re-runs / duplicates a call already made this turn. */
    private static final Pattern REPEAT_CLAIM = Pattern.compile(
            "(?is)\\b(repeats?|repeating|re-?runs?|re-?running|duplicates?|same (?:action|call|lookup|step)|already (?:succeeded|executed|ran|run|made|performed|done))\\b");

    public static boolean claimsARepeat(String issue) {
        return issue != null && REPEAT_CLAIM.matcher(issue).find()
                && issue.matches("(?is).*\\b(plan|action|call|step|lookup|tool)\\b.*");
    }

    /**
     * the local critic refused `CodeGraph locate ShippingQuote#addHandlingCharge` - the
     * method's first step, proposed as the plan - with "The agent did not perform the first step of locating the cited code
     * (CodeGraph action=locate …) as instructed": a complaint that the plan's OWN action has not been done yet. Nothing has run
     * before a plan; a critic saying the plan's tool and action were "not performed" is refusing the plan for being a plan.
     */
    private static final Pattern NOT_YET_DONE = Pattern.compile(
            "(?is)\\b(did not|didn.t|has not|hasn.t|have not|haven.t|never|failed to|without having)\\b\\s+(?:yet\\s+)?(?:been\\s+)?(?:yet\\s+)?"
            + "(perform\\w*|run|ran|execut\\w*|do|done|carr\\w+ out|complet\\w*|locat\\w*|read|trac\\w*|call\\w*|invok\\w*|us\\w+)\\b");

    public static boolean complainsThePlanIsNotYetDone(String issue, String planJson) {
        if (issue == null || planJson == null || !NOT_YET_DONE.matcher(issue).find()) {
            return false;
        }
        // the plan's own tool (and action, when it has one) must be what the critic says was not done
        Matcher tools = Pattern.compile("\"tool\"\\s*:\\s*\"([A-Za-z]+)\"").matcher(planJson);
        Matcher actions = Pattern.compile("\"action\"\\s*:\\s*\"([A-Za-z]+)\"").matcher(planJson);
        boolean toolNamed = false;
        while (tools.find()) {
            if (issue.contains(tools.group(1))) {
                toolNamed = true;
            }
        }
        if (!toolNamed) {
            return false;
        }
        boolean anyAction = false;
        while (actions.find()) {
            anyAction = true;
            if (issue.toLowerCase(Locale.ROOT).contains(actions.group(1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return !anyAction;   // a tool with no action parameter: naming the tool is enough
    }

    /**
     * the critics refused `CodeGraph {action=tests, symbol=…}` - a documented action - as
     * "Invalid tool 'tests' used in plan": the action's name read as a tool's. A claim that a tool is invalid, unknown or
     * not available is dropped when the name it quotes is a registered tool, or a documented action of a tool the plan uses.
     */
    private static final Pattern INVALID_TOOL_CLAIM = Pattern.compile(
            "(?is)\\b(invalid|unknown|unsupported|non-?existent|not (?:a )?(?:valid|known|recogni[sz]ed|available|registered|supported))\\s+"
            + "(?:tool|action|command|skill)\\s*['\"`]?([A-Za-z][\\w.-]*)['\"`]?"
            + "|\\b(?:tool|action)\\s*['\"`]([A-Za-z][\\w.-]*)['\"`]\\s*(?:is|was)?\\s*(?:invalid|unknown|unsupported|not (?:a )?(?:valid|known|recogni[sz]ed|available|registered|supported))"
            + "|['\"`]([A-Za-z][\\w.-]*)['\"`]\\s+(?:tool|action)\\b[^.]{0,40}\\b(?:invalid|unknown|unsupported|not (?:a )?(?:valid|known|recogni[sz]ed|available|registered|supported))");

    public static boolean callsAValidToolOrActionInvalid(String issue, String planJson, Set<String> registeredTools,
            Set<String> codeGraphActions) {
        if (issue == null) {
            return false;
        }
        Matcher m = INVALID_TOOL_CLAIM.matcher(issue);
        if (!m.find()) {
            return false;
        }
        String named = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
        if (named == null) {
            return false;
        }
        if (registeredTools != null && registeredTools.contains(named)) {
            return true;
        }
        String lower = named.toLowerCase(Locale.ROOT);
        return codeGraphActions != null && codeGraphActions.contains(lower) && planJson != null && planJson.contains("\"CodeGraph\"");
    }

    public static boolean doubtsARegisteredTool(String issue, Set<String> registeredTools) {
        if (issue == null || registeredTools == null || !TOOL_EXISTENCE_DOUBT.matcher(issue).find()) {
            return false;
        }
        for (String tool : registeredTools) {
            if (issue.matches("(?s).*\\b" + Pattern.quote(tool) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    // "The plan does not describe how to run runtime evidence using HindsightDebugger" -
    // the first plan (a locate) refused for not carrying the method's THIRD step, in words neither pattern above knew
    private static final Pattern LATER_STAGE_ABSENT = Pattern.compile(
            "(?is)\\b(does not|doesn.t|do not|fails? to|without|no|missing|lacks?|omits?)\\b.{0,60}"
            + "\\b(runtime evidence|hindsight\\w*|recording|record(?:ing)? (?:the|an?) (?:execution|test)|trace (?:the )?callers|"
            + "callers? (?:trace|lookup)|compar(?:e|ison) (?:the )?(?:finding|claim)s?|(?:produce|reach|state) (?:a |the )?verdict)\\b");

    public static boolean demandsLaterMethodSteps(String issue) {
        if (issue == null || issue.matches("(?is).*\\b(parameter|param|argument)s?\\b.*")) {   // a missing PARAMETER is a real defect
            return false;
        }
        if (LATER_STEPS.matcher(issue).find() || LATER_STAGE_ABSENT.matcher(issue).find()) {
            return true;
        }
        if (!MISSING_STEPS.matcher(issue).find()) {
            return false;
        }
        Set<String> stages = new HashSet<>();
        Matcher m = METHOD_STAGE.matcher(issue);
        while (m.find()) {
            stages.add(m.group(1).toLowerCase(Locale.ROOT).replaceAll("(?<=\\w)(e|ing|ison|ers?)\\b", ""));
        }
        return stages.size() >= 2 || ONLY_ONE_STEP.matcher(issue).find();
    }

    public static boolean demandsReVerificationOfContext(String issue, String contextSoFar) {
        if (issue == null || contextSoFar == null || !DOUBT_PHRASE.matcher(issue).find()) {
            return false;
        }
        Matcher m = Pattern.compile(
                "\\b([A-Za-z_][\\w.$]*[#.][A-Za-z_]\\w{3,})\\b").matcher(issue);
        while (m.find()) {
            String sym = m.group(1);
            String simple = sym.substring(Math.max(sym.lastIndexOf('.'), sym.lastIndexOf('#')) + 1);
            if (simple.length() >= 6 && contextSoFar.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern FORBIDS_EDITING = Pattern.compile(
            "(?i)\\b(should not|must not|shall not|is not (?:supposed|allowed) to|without)\\s+"
            + "(?:be\\s+)?(edit\\w*|modif\\w*|chang\\w*|writ\\w*|touch\\w*|generat\\w*)"
            + "|\\bonly report\\b|\\breport (?:the )?results only\\b");
    private static final Pattern REQUEST_ASKS_TO_EDIT = Pattern.compile(
            "(?i)\\b(write|add|create|implement|make|apply|generate|compose|commit)\\b[^.]{0,60}"
            + "\\b(test|fix|change|edit|method|patch)\\b|\\b(edit|modify) the\\b");

    /**
     * in the TDD step a critic rejected the plan because "the request specifically states that the
     * agent should NOT edit code and should only report results" - that was the PREVIOUS turn's constraint. Ground
     * rule 1 makes the CURRENT request authoritative; an issue that contradicts it is not a defect of the plan.
     */
    /**
     * "the plan commits the test locally … but the instruction states that NO git push should be
     * done" - a LOCAL commit is not a push, and the request asked for the commit. Also: "TreeCodegen is missing the
     * required 'templateMethod' parameter" - templateMethod, targetReach, module and testFilter are derived by the
     * tool when absent; demanding them is not an issue.
     */
    /**
     * "the TreeCodegen 'task' parameter contains an invented/assumed detail: '<the requirement,
     * quoted almost verbatim from the request>'". A quoted value whose words are the request's own is not invented.
     */
    /**
     * "the plan does not use the spec-test generator for writing a spec test … even though it
     * is required" - on the stage whose request asks for ONE failing UNIT test. A spec-level demand against a
     * unit-test request is a stage confusion, not an issue.
     */
    public static boolean demandsSpecTestForAUnitTestRequest(String issue, String userRequest) {
        if (issue == null || userRequest == null) {
            return false;
        }
        String t = issue.toLowerCase(Locale.ROOT);
        String req = userRequest.toLowerCase(Locale.ROOT);
        boolean spec = t.matches("(?s).*\\b(spectestcomposer|spectest|@spectest|spec-level|spec test|end-to-end network test)\\b.*");
        boolean unitRequest = req.contains("unit test")
                && !req.matches("(?s).*\\b(spectest|@spectest|spec-level|spec composer|spectestcomposer)\\b.*");
        return spec && unitRequest;
    }

    public static boolean claimsRequestWordsAreInvented(String issue, String userRequest) {
        if (issue == null || userRequest == null) {
            return false;
        }
        String t = issue.toLowerCase(Locale.ROOT);
        if (!t.matches("(?s).*\\b(invent\\w*|assum\\w*|fabricat\\w*|made[- ]up|not (in|from|part of) the (current )?request)\\b.*")) {
            return false;
        }
        Set<String> requestWords = new HashSet<>();
        for (String w : userRequest.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 4) {
                requestWords.add(w);
            }
        }
        // every span between two consecutive quote characters of the same kind is a candidate (an apostrophe in
        // "action's" must not swallow the value that follows)
        for (char quote : new char[] {'\'', '"', '`'}) {
            int prev = issue.indexOf(quote);
            while (prev >= 0) {
                int next = issue.indexOf(quote, prev + 1);
                if (next < 0) {
                    break;
                }
                String span = issue.substring(prev + 1, next);
                int total = 0;
                int known = 0;
                for (String w : span.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (w.length() >= 4) {
                        total++;
                        if (requestWords.contains(w)) {
                            known++;
                        }
                    }
                }
                if (total >= 4 && known >= total * 0.7) {
                    return true;
                }
                prev = next;
            }
        }
        return false;
    }

    private static final Pattern DOUBTS_EXISTENCE = Pattern.compile(
            "(?i)\\b(unlikely to exist|may not exist|might not exist|does ?n[o']t exist|not exist|not (?:be )?(?:present|available"
            + "|accessible|found)|execution environment)\\b");
    private static final Pattern PATH_IN_TEXT = Pattern.compile("[~.]{0,2}/[\\w.@~+-]+(?:/[\\w.@~+-]+)+");

    /**
     * a critic refused the request's own command because a path it names "is unlikely to exist in
     * the execution environment". The request is authoritative about its paths; a command whose path is missing fails
     * and says so.
     */
    public static boolean doubtsAPathTheRequestNames(String issue, String userRequest) {
        if (issue == null || userRequest == null || !DOUBTS_EXISTENCE.matcher(issue).find()) {
            return false;
        }
        Matcher m = PATH_IN_TEXT.matcher(issue);
        while (m.find()) {
            String path = m.group().replaceAll("[.,;:]+$", "");
            if (path.length() > 3 && userRequest.contains(path)) {
                return true;
            }
        }
        return false;
    }

    public static boolean confusesLocalCommitWithPush(String issue, String userRequest) {
        if (issue == null || userRequest == null) {
            return false;
        }
        String t = issue.toLowerCase(Locale.ROOT);
        boolean aboutCommit = t.contains("commit");
        boolean citesPushRule = t.matches("(?s).*\\b(push|post(ing)? to (any |a )?(ticket|pr|pull request))\\b.*");
        boolean requestAsksToCommit = userRequest.toLowerCase(Locale.ROOT).contains("commit");
        return aboutCommit && citesPushRule && requestAsksToCommit && !t.contains("git push origin");
    }

    private static final Pattern AUTO_DERIVED_PARAM = Pattern.compile(
            "(?i)['\"`]?(templateMethod|targetReach|module|testFilter|expectTestOutcome|trigger|include|projectDir)['\"`]?\\s+(parameter\\s+)?(is\\s+)?(missing|required|not (provided|specified|set|given))"
            + "|(missing|requires?|lacks|without)\\s+(the\\s+)?(required\\s+)?['\"`]?(templateMethod|targetReach|module|testFilter|expectTestOutcome|trigger|include|projectDir)['\"`]?");

    public static boolean demandsAutoDerivedParameter(String issue) {
        return issue != null && AUTO_DERIVED_PARAM.matcher(issue).find();
    }

    public static boolean contradictsTheCurrentRequest(String issue, String userRequest) {
        return issue != null && userRequest != null
                && FORBIDS_EDITING.matcher(issue).find()
                && REQUEST_ASKS_TO_EDIT.matcher(userRequest).find();
    }

    private static final Pattern CLAIMS_UNGROUNDED = Pattern.compile(
            "(?i)\\b(does ?n[o']t (?:\\w+ly )?(?:provide|specify|mention|include|name)|is not (?:\\w+ly )?(?:provided|specified|mentioned|given"
            + "|supported|named)|not (?:in|from|supported by) the (?:context|request)|no (?:such )?(?:test method|method"
            + "|value|name) (?:in|from) the (?:context|request)|assumes (?:the )?existence of|assumes (?:that )?\\S+ exists"
            + "|without (?:providing|ensuring|verifying) (?:its|their|that)\\b)");

    /**
     * a critic rejected the stage-2 plan because "the current request does not provide a specific
     * existing test method to use as a template" while quoting the very template the stage-1 answer and the `tests`
     * result had established. A value the context contains is grounded, whatever the current turn's prompt says.
     */
    public static boolean claimsAGroundedValueIsUngrounded(String issue, String contextSoFar) {
        if (issue == null || contextSoFar == null || !CLAIMS_UNGROUNDED.matcher(issue).find()) {
            return false;
        }
        Matcher m = Pattern.compile(
                "\\b([A-Za-z_][\\w.$]*[#.])?([A-Za-z_]\\w{5,})\\b").matcher(issue);
        while (m.find()) {
            String name = m.group(2);
            if (name.matches("(?i)(context|request|template|specific|existing|method|provide|plan|current|should)")) {
                continue;
            }
            if (contextSoFar.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** does a reviewer's "issue" text actually complain about something (vs. restate findings)? */
    public static boolean looksLikeAComplaint(String issue) {
        return issue != null && COMPLAINT.matcher(issue).find();
    }

    public static boolean isVerdictAnswer(String answer) {
        return answer != null && AnswerGate.VERDICT.matcher(answer).find();
    }

    /** the claims (their heads, before " - ") named in a reviewer's issue built by verdictCriticIssue. */
    public static List<String> disputedClaimsOf(String issue) {
        List<String> out = new ArrayList<>();
        if (issue == null) {
            return out;
        }
        String marker = "CONTRADICTS them: ";
        int at = issue.indexOf(marker);
        if (at < 0) {
            return out;
        }
        String body = issue.substring(at + marker.length());
        int end = body.indexOf(". Drop each such claim");
        int semi = body.indexOf("; the verdict does not follow");
        if (semi >= 0 && (end < 0 || semi < end)) {
            end = semi;
        }
        if (end >= 0) {
            body = body.substring(0, end);
        }
        for (String part : body.split(" \\| ")) {
            String head = part.split("\\s+[—–-]\\s+", 2)[0].strip();
            if (head.length() >= 12) {
                out.add(head);
            }
        }
        return out;
    }

    public static void addWords(Set<String> vocab, String text) {
        if (text == null) {
            return;
        }
        for (String w : text.split("[^A-Za-z0-9_]+")) {
            if (!w.isEmpty()) {
                vocab.add(w.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Collapse issues whose wording (ignoring the [lens] tag, case and whitespace) is the same. */
    public static List<String> dedupeIssues(List<String> issues) {
        LinkedHashMap<String, String> byNorm = new LinkedHashMap<>();
        for (String issue : issues) {
            String body = issue.replaceFirst("^\\[[^\\]]*]\\s*", "");
            String norm = body.toLowerCase().replaceAll("\\s+", " ").replaceAll("[.\"'`]", "").trim();
            byNorm.putIfAbsent(norm, issue);   // keep the first (tagged) phrasing
        }
        return new ArrayList<>(byNorm.values());
    }

    /** "the PLAN omits the emulator verdict, the real gradle outcome and the Hindsight evidence" - those
     *  are LATER steps of the same job; a plan is one step (the completeness lens says so, the critic ignored it). */
    private static final Pattern WRITE_STEP_DEMAND = Pattern.compile(
            "(?i)\\b(fails? to (?:include|follow)|does not include|doesn'?t include|lacks?|lacking|missing|omits?|omitted|"
            + "is incomplete|stops? at|stopping at|only (?:includes|contains|performs|has|reads))\\b(?:[^.]|\\.(?=\\w)){0,260}"
            + "\\b(writ\\w*|creat\\w*|produc\\w*|commit\\w*|verif\\w*|execut\\w*|implement\\w*|renam\\w*|updat\\w*|"
            + "run(?:s|ning)? the tests?|the (?:output |target )?file|invok\\w*|appl(?:y|ies|ying) the (?:changes?|edits?)|"
            + "TreeCodegen|TreeEdit|WriteProgram)\\b");

    private static final Pattern SHELL_SYNTAX_CLAIM = Pattern.compile(
            "(?i)\\b(syntax error|shell syntax|invalid syntax|no (?:command )?separator|missing (?:a )?(?:command )?separator|"
            + "unterminated|here-?doc\\w*[^.]{0,80}\\b(?:invalid|broken|malformed|unterminated|never closed)|"
            + "(?:will|would) (?:fail|not run|not parse) (?:in|under|with) (?:bash|the shell))");

    /** a critic's claim that a shell step is malformed. */
    public static boolean claimsShellSyntaxError(String issue) {
        return issue != null && SHELL_SYNTAX_CLAIM.matcher(issue).find();
    }

    /** the demand named first and its absence after it ("... git commit commands, which is missing"). */
    private static final Pattern WRITE_STEP_DEMAND_NAMED_FIRST = Pattern.compile(
            "(?i)\\b(commit\\w*|gradle|neutral-check|TreeCodegen|TreeEdit|WriteProgram|verification|(?:final |the )?Bash command)\\b"
            + "(?:[^.]|\\.(?=\\w)){0,200}\\b(is missing|are missing|missing from the plan|not included|is absent|absent from the plan)\\b");

    private static final Pattern AFTER_TOOL_STEP = Pattern.compile(
            "(?i)\\bWhen (TreeCodegen|TreeEdit|WriteProgram|SpecTestComposer) succeeds\\b");
    private static final Pattern OMITS = Pattern.compile(
            "(?i)\\b(omits?|omitted|lacks?|lacking|missing|does not include|doesn'?t include|without|fails? to (?:include|run|execute))\\b");
    private static final Pattern AFTER_STEP_WORDS = Pattern.compile(
            "(?i)\\b(Bash command|bash|gradle|git commit|commit\\w*|the tests?|test run|verification|neutral-check)\\b");

    /**
     * the request said "When TreeCodegen succeeds, run ONE Bash command"; the plan was that
     * TreeCodegen call, and a critic rejected it for omitting the command, which can only run after the call succeeds.
     * True for a complaint that the plan omits the test, Bash or commit step when the request names that step as the
     * one after the tool this plan calls.
     */
    public static boolean demandsTheStepAfterTheTool(String issue, String request, String planJson) {
        if (issue == null || request == null || planJson == null) {
            return false;
        }
        Matcher m = AFTER_TOOL_STEP.matcher(request);
        if (!m.find() || !planJson.contains("\"" + m.group(1) + "\"")) {
            return false;
        }
        return OMITS.matcher(issue).find() && AFTER_STEP_WORDS.matcher(issue).find();
    }

    /** a critic's complaint that a plan does not (yet) write, create or commit the file. */
    public static boolean demandsTheWriteStep(String issue) {
        return issue != null && (WRITE_STEP_DEMAND.matcher(issue).find() || WRITE_STEP_DEMAND_NAMED_FIRST.matcher(issue).find());
    }

    /**
     * Whether a second reviewer reads this verdict - within the budget, or past it (by up to three) when the ruling
     * changed since the last disputed one: a flip decided after the budget was spent reached the supervisor unreviewed.
     */
    public static boolean reviewVerdict(int critics, int max, String lastDisputedRuling, String ruling) {
        boolean changed = lastDisputedRuling != null && !lastDisputedRuling.equals(ruling);
        return critics < max || (changed && critics < max + 3);
    }

    /** A ruling written in prose - TRUE/FALSE POSITIVE stated, no tool call, no JSON plan, not cut off. */
    private static final Pattern RULING_STATEMENT = Pattern.compile(
            "(?im)^[\\s*#(\\d)]*(?:verdict\\s*[:\\-]?\\s*)?\\**\\s*(true|false)[ -]positive\\b|(?i)\\bverdict\\s*[:\\-]\\s*\\**\\s*(true|false)[ -]positive\\b");

    public static boolean isProseRuling(String text) {
        if (text == null || text.strip().startsWith("{") || text.strip().length() < 80
                || !AnswerGate.isAnAnswerText(text)) {
            return false;
        }
        return RULING_STATEMENT.matcher(text).find();   // the ruling is STATED, not merely mentioned in passing
    }
}
