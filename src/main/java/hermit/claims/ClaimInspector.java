package hermit.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.analysis.graph.SpecConventions;
import hermit.evidence.CitationValidator;
import hermit.evidence.Evidence;
import hermit.evidence.RecordedExecutionReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps one claim to code and decides it on evidence only. The steps, in order:
 * <ol>
 *   <li>existence - a claim resting on a file, type or member that does not exist is refuted at once
 *       (CitationValidator);</li>
 *   <li>translation - the claim becomes a code hypothesis: entry, targets, implied call path, condition, observable;</li>
 *   <li>static - CodeGraph locates the targets and checks the implied path (a resolved no-path refutes); the source of the
 *       targets decides a claim about what code contains or calls;</li>
 *   <li>runtime - a test that reaches the target (CodeGraph tests, else one written in the scratch worktree) is
 *       recorded (a recording of the test); the calls, arguments, returns and conditions on the path decide a claim
 *       about behaviour;</li>
 *   <li>logic - only when neither can decide: a blind review over the collected lines; still open, the claim is
 *       UNDECIDED.</li>
 * </ol>
 * External effects go through small ports so the unit can be tested and the hosted translator swapped in.
 */
public final class ClaimInspector {

    public record Located(String spec, Path file, int line) {}

    public record Reach(boolean reachable, List<String> path, String status) {}

    /** Static facts about the repository. */
    public interface Code {
        List<Located> locate(String spec);

        Reach reach(String fromSpec, String toSpec);

        /** Test methods that reach the target, best first ({@code Class#method}). */
        List<String> tests(String targetSpec);

        /** The root that cited-object existence is checked against. */
        Path root();

        /**
         * The methods a type declares or inherits from source ({@code Owner#method}); empty when the type is not indexed.
         */
        default List<String> methodsOf(String type) {
            return List.of();
        }

        /** The simple names of the indexed types that declare methods. */
        default Set<String> typeNames() {
            return Set.of();
        }

        /**
         * The numbered definition of a protobuf message or enum (generated types are not in the Java sources), or "".
         */
        default String definitionOf(String name) {
            return "";
        }
    }

    /** A recording of a test. */
    public interface Runtime {
        /**
         * Records the test with the targets' code instrumented; returns the recording directory
         * (throws when it did not run).
         */
        String record(Located test, List<Located> targets) throws Exception;

        RecordedExecutionReader query(String recordingDir);
    }

    /** Writes a focused test for a claim in the scratch worktree; returns where it is, or null. */
    public interface TestWriter {
        Located write(Claim claim, Located nearestTest, String scenario) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Function<String, String> llm;
    private final Function<String, String> translator;
    private final Code code;
    private final Runtime runtime;
    private final TestWriter writer;
    private final int maxRecordings;
    private final Map<String, String> recordingByTest = new LinkedHashMap<>();
    private int recordings;
    /** The types the request names: a claim's bare method name ("handle()") means the method on one of them first. */
    private final Set<String> contextTypes = new LinkedHashSet<>();
    /** Tests written per turn: generating, compiling and running one in the worktree costs many minutes. */
    private final int maxWritten = Integer.getInteger("hermit.claims.maxWrittenTests", 1);
    private int written;
    private final List<String> log = new ArrayList<>();
    static final String EXTRACT_PROMPT = """
            Split the text below into atomic claims about the code under analysis. An atomic claim states ONE thing: \
            what a piece of code does or does not do, contains or calls, or what an actor can or cannot achieve through \
            it, together with the conditions it depends on. Split "A and B" into two claims. Keep the text's own names \
            (classes, methods, fields, status codes). Every sentence that states what happens, what an actor can or \
            cannot do or obtain, or a consequence (an impact) is at least one claim — an impact is a claim about what \
            the code lets happen. Leave out verdicts on the report as a whole (that it is right or wrong; that the code \
            "is a defect", "is a flaw" or "has the reported problem", or where that problem is located), severity and \
            category labels, recommendations, statements about the reporter, and anything that is not about this code.

            TEXT:
            %s

            Reply with ONE JSON object and nothing else: {"claims": ["<claim>", "<claim>"]}""";
    static final String TRANSLATE_PROMPT = """
            Translate ONE claim about this Java repository into a code hypothesis that a call graph and a runtime \
            recording of a test can check.

            CLAIM: %s

            DECLARATIONS located for the names the claim uses:
            %s

            Reply with ONE JSON object and nothing else:
            {"kind": "structure" | "behaviour" | "outside",
             "entry": "Type#method where the claimed path starts, or empty",
             "targets": ["Type#method the claim is about"],
             "path": ["Type#method", "..."],
             "condition": "the initial state or inputs the claim assumes, or empty",
             "observable": "what the source or a recording would show if the claim holds: a call made or not made, \
            an argument or return value, a condition evaluated, a field compared or written",
             "expected": "the value or event that decides the claim"}
            kind: "structure" = what a method's code contains, compares or calls; "behaviour" = what happens at runtime \
            on a path under a condition; "outside" = ONLY about what this repository does not model: client software, \
            people, the world (a transaction, handler, key, account or fee the repository processes is NOT outside). \
            entry, targets and path use ONLY Type#method specs from DECLARATIONS: never invent a type or a method. \
            When the claim's code is not among them, give the bare method name the claim uses.""";
    static final String STATIC_JUDGE_PROMPT = """
            You check ONE claim about a Java code base. The source lines and call-graph facts below, printed by tools, \
            are your only evidence: do not use what you believe the code probably does elsewhere.

            CLAIM: %s

            EVIDENCE:
            %s

            Decide:
            - "supported": the evidence shows every part of the claim, under the conditions the claim states.
            - "contradicted": the evidence shows the opposite of some part of the claim.
            - "insufficient": some part of the claim, or a condition it leaves out, is neither shown nor refuted.
            A claim with several parts is supported only if each part is. A claim stated without a condition the code \
            makes it depend on is not supported.
            A claim that something does NOT happen in the system (no fee, no call, no check, never) is supported only \
            when the evidence covers every place it could happen: a method that does not do it itself, or throws \
            first, does not show that nothing else does — that is "insufficient".
            A claim about a party or a scenario (who created, owns, holds or signs with a key; a first and a second \
            account) is supported only when the lines link that party to the key or value; never assume who holds a key.
            A claim that code requires or does something FOR a purpose (for signing, for deletion, for execution) is \
            supported only when the lines show it for that purpose: the same key checked for another purpose does not \
            support it.
            Reply with ONE JSON object and nothing else:
            {"status": "supported" | "contradicted" | "insufficient", "evidence": "<file:line that decides it>", \
            "summary": "<one sentence: what the lines show>", "next_read": "<what would settle it, or empty>"}""";
    static final String RUNTIME_JUDGE_PROMPT = """
            You check ONE claim against a runtime recording of a test and the source of the methods involved. The \
            recording shows the calls with their arguments and return values, the conditions evaluated \
            (a OP b -> true/false) and local values. Use only this evidence.

            CLAIM: %s
            CONDITION the claim assumes: %s
            OBSERVABLE: %s
            TEST that ran: %s

            RECORDED EVENTS:
            %s

            SOURCE:
            %s

            Decide:
            - "supported": the recording shows the claim holds on this path, and the test's setup meets the claim's \
            condition.
            - "contradicted": the recording shows the opposite, with the condition met.
            - "insufficient": the setup differs from the claim's condition, or the events do not show the observable.
            Reply with ONE JSON object and nothing else:
            {"status": "supported" | "contradicted" | "insufficient", "condition_met": true | false, \
            "evidence": "<seq or file:line that decides it>", "summary": "<one sentence: what the recording shows>", \
            "missing": "<what a test would have to set up or observe, or empty>"}""";
    static final String SKEPTIC_PROMPT = """
            A judge decided that the evidence below SUPPORTS a claim about a Java code base. Check that decision \
            phrase by phrase; you are looking for what is NOT shown.

            CLAIM: %s

            THE JUDGE'S REASON: %s

            EVIDENCE:
            %s

            Take each phrase of the claim — the subject, the action, the object, the purpose ("for X"), the party (who \
            creates, holds, signs, pays), the condition, the consequence — and ask whether the evidence shows exactly \
            that. A phrase the evidence shows only for another purpose, another operation, another type or another \
            party is NOT shown. Something the evidence does not mention is NOT shown.
            Reply with ONE JSON object and nothing else:
            {"unshown": ["<phrase of the claim> — <why it is not shown>"]} (an empty list when every phrase is shown).""";
    private static final Pattern PREVENTED = Pattern.compile(
            "(?i)\\b(cannot|can ?not|can't|unable to|not allowed|not permitted|is rejected|are rejected|fails?)\\b");
    private static final Set<String> INTENSIFIERS = Set.of("explicitly", "directly", "crucially", "effectively", "actually",
            "simply", "clearly", "really", "always", "merely", "indeed", "fully", "exactly", "precisely");
    private static final Pattern JOINED = Pattern.compile(
            "(?i)\\b(and|so|because|since|while|but|therefore|thus)\\b|;");
    static final String REFUTATION_CHECK_PROMPT = """
            A judge decided that the evidence below CONTRADICTS a claim about a Java code base. Check that decision.

            CLAIM: %s

            THE JUDGE'S REASON: %s

            EVIDENCE:
            %s

            A claim is contradicted only when the evidence shows the OPPOSITE of what it states, in the situation it \
            describes — on the ordinary path, under the conditions the claim states or implies. A case the claim does not \
            mention (another branch, a flag set differently, an exceptional path) is a condition the claim leaves out, not \
            its opposite. A different value, field or behaviour on the claim's own path IS its opposite.
            Reply with ONE JSON object and nothing else:
            {"opposite_shown": true | false, "why": "<one sentence>"}""";
    static final String SPLIT_PROMPT = """
            Split a claim about a code base into its atomic parts: each part asserts ONE thing that could be true or false \
            on its own. Each part is self-contained (repeat the subject) and keeps the claim's wording.

            Split on "and", "so", "because", "since", "while", "but", "therefore" and semicolons — also when "and" joins two \
            purposes, objects or conditions of one verb. Never split "or" (a disjunction is ONE claim).

            Examples:
            "Handler H requires key K for deletion and for signing." -> {"parts": ["Handler H requires key K for deletion.", \
            "Handler H requires key K for signing."]}
            "The user cannot delete the record, because deletion needs the owner's key." -> {"parts": ["The user cannot \
            delete the record.", "Deletion needs the owner's key."]}
            "lookup hashes the name and the id, and does not hash the owner." -> {"parts": ["lookup hashes the name.", \
            "lookup hashes the id.", "lookup does not hash the owner."]}
            "A rejected request is not charged any fee." -> {"parts": ["A rejected request is not charged any fee."]}
            "Checkout needs the gift card or the coupon." -> {"parts": ["Checkout needs the gift card or the coupon."]}

            CLAIM: %s

            Reply with ONE JSON object and nothing else: {"parts": ["...", "..."]}.""";

    public ClaimInspector(Function<String, String> llm, Function<String, String> translator, Code code, Runtime runtime,
            TestWriter writer, int maxRecordings) {
        this.llm = llm;
        this.translator = translator == null ? llm : translator;
        this.code = code;
        this.runtime = runtime;
        this.writer = writer;
        this.maxRecordings = maxRecordings;
    }

    /** Records the types a text (the request) names that the repository declares. */
    public void context(String text) {
        Matcher m = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b").matcher(text == null ? "" : text);
        Set<String> known = code.typeNames();   // only words that are the repository's types count
        while (m.find()) {
            if (known.contains(m.group())) {
                contextTypes.add(m.group());
            }
        }
        if (!contextTypes.isEmpty()) {
            log.add("context types: " + String.join(", ", contextTypes));
        }
    }

    public List<String> log() {
        return log;
    }

    /** A test may be written: a writer, the written-test budget not spent, and a recording left to run it. */
    private boolean canWrite() {
        return writer != null && written < maxWritten && recordings < maxRecordings;
    }

    public int recordingsUsed() {
        return recordings;
    }

    /** Splits {@code text} into atomic claims and adds the new ones to the ledger (at most {@code maxClaims} in it). */
    public List<Claim> extract(List<Claim> ledger, String text, Claim.Origin origin, int iteration, int maxClaims) {
        List<Claim> added = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return added;
        }
        // a paragraph at a time: given a whole report, a small model stops after the first paragraph's claims
        for (String chunk : chunks(text.length() > 12000 ? text.substring(0, 12000) : text, 1500)) {
            JsonNode node = json(llm.apply(EXTRACT_PROMPT.formatted(chunk)));
            if (node == null) {
                log.add("extract: the model returned no JSON for a paragraph");
                continue;
            }
            for (JsonNode c : node.path("claims")) {
                String t = c.asText("").strip();
                if (t.length() < 12 || ledger.size() >= maxClaims) {
                    continue;
                }
                int before = ledger.size();
                Claim claim = ClaimLedger.add(ledger, t, origin, iteration, null);
                if (ledger.size() > before) {
                    added.add(claim);
                }
            }
        }
        return added;
    }

    /**
     * The text in paragraphs (blank-line separated), neighbours merged up to {@code target} characters, short ones dropped.
     */
    static List<String> chunks(String text, int target) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean advice = false;
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.matches("(?is)^[#*\\s]*(remediation|recommendations?|mitigations?|suggested fix|fix)\\b.*")
                    && p.length() < 60) {
                advice = true;   // what the reporter recommends is not a claim about the code
                continue;
            }
            if (p.matches("(?s)^#{1,6}\\s.*") && p.length() < 60) {
                advice = false;
            }
            if (p.length() < 40 || advice || !p.matches("(?s).*[.!?](\\s.*|$)")) {
                continue;   // headings, labels and metadata (no sentence in them), advice
            }
            if (cur.length() > 0 && cur.length() + p.length() > target) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(cur.length() > 0 ? "\n\n" : "").append(p);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        if (out.isEmpty() && !text.isBlank()) {
            out.add(text.strip());   // one short paragraph is still a text to read
        }
        return out;
    }

    /** A claim that an effect is absent ("does not charge", "never", "no fee") - not that an operation is prevented. */
    static boolean absence(String text) {
        // a negation inside a condition ("when no limit is set") qualifies the claim; it does not make it negative
        String asserted = (text == null ? "" : text)
                .replaceAll("(?i)\\b(when|if|unless|while|where|whenever)\\b[^,.;]*", " ");
        return ClaimLedger.negated(asserted) && !PREVENTED.matcher(asserted).find();
    }

    /** Null when the evidence shows the claim's opposite; else why the refutation is only a case the claim leaves out. */
    String notOpposite(String claim, String reason, String evidence) {
        JsonNode v = json(llm.apply(REFUTATION_CHECK_PROMPT.formatted(claim, reason,
                evidence.length() > 14000 ? evidence.substring(0, 14000) : evidence)));
        if (v == null || !v.has("opposite_shown") || v.path("opposite_shown").asBoolean(true)) {
            return null;
        }
        String why = v.path("why").asText("").strip();
        return why.isEmpty() ? "the refutation rests on a case the claim does not describe" : why;
    }

    /** The skeptic's list of claim phrases the evidence does not show, joined; null when it finds none. */
    String unshown(String claim, String reason, String evidence) {
        JsonNode v = json(llm.apply(SKEPTIC_PROMPT.formatted(claim, reason,
                evidence.length() > 14000 ? evidence.substring(0, 14000) : evidence)));
        if (v == null) {
            return null;   // no answer from the skeptic is not a gap it found
        }
        List<String> gaps = new ArrayList<>();
        for (JsonNode g : v.path("unshown")) {
            String t = g.asText("").strip();
            if (t.length() > 3 && specificGap(claim, t)) {
                gaps.add(t);
            }
        }
        return gaps.isEmpty() ? null : String.join("; ", gaps);
    }

    /**
     * True when a skeptic's gap names a specific phrase of the claim: not the whole claim restated (that is disagreement,
     * not a phrase the lines leave out) and not an intensifier alone ("explicitly" - wording, not content).
     */
    static boolean specificGap(String claim, String gap) {
        String phrase = gap.split("\\s+[—–-]\\s+", 2)[0].replaceAll("^[\"'`]+|[\"'`]+$", "");
        Set<String> phraseWords = ClaimLedger.words(phrase);
        phraseWords.removeAll(INTENSIFIERS);
        if (phraseWords.isEmpty()) {
            return false;
        }
        Set<String> claimWords = ClaimLedger.words(claim);
        if (claimWords.size() >= 4) {
            Set<String> covered = new LinkedHashSet<>(claimWords);
            covered.retainAll(phraseWords);
            if (covered.size() >= 0.7 * claimWords.size()) {
                // the whole claim restated: the skeptic disagrees with the judge, it names no unshown phrase
                return false;
            }
        }
        return true;
    }

    /**
     * Maps one claim; its status and evidence are set in place. A compound claim is split first and decided by its parts:
     * one refuted part refutes it, and it holds only when every part does - a judge reading the whole tends to check the
     * first half only.
     */
    public Claim map(Claim claim) {
        if (claim.parentId == null && claim.parts.isEmpty() && JOINED.matcher(claim.text).find()) {
            List<String> parts = split(claim.text);
            if (parts.size() >= 2) {
                for (int i = 0; i < parts.size(); i++) {
                    Claim part = new Claim(claim.id + "." + (i + 1), parts.get(i), claim.origin, claim.iteration);
                    part.parentId = claim.id;
                    claim.parts.add(part);
                }
                log.add(claim.id + ": split into " + parts.size() + " parts");
            }
        }
        if (claim.parts.isEmpty()) {
            return mapAtomic(claim);
        }
        List<String> held = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (Claim part : claim.parts) {
            if (part.status == Claim.Status.OPEN) {
                mapAtomic(part);
            }
            Evidence pp = part.decidingEvidence();
            if (part.status == Claim.Status.REFUTED) {
                Evidence p = new Evidence(pp == null ? Evidence.Kind.LOGIC : pp.kind, Claim.Status.REFUTED, true,
                        "part " + part.id + " (\"" + part.text + "\") is refuted: "
                                + (pp == null ? "" : pp.summary));
                if (pp != null) {
                    p.anchors.addAll(pp.anchors);
                    p.callChain.addAll(pp.callChain);
                    p.test = pp.test;
                    p.events = pp.events;
                }
                return decide(claim, p);
            }
            if (part.status == Claim.Status.SUPPORTED) {
                held.add(part.id + ": " + (pp == null ? "" : pp.summary));
            } else {
                open.add(part.id + " (\"" + part.text + "\"): "
                        + (part.undecidedReason == null ? "undecided" : part.undecidedReason));
            }
        }
        if (open.isEmpty()) {
            Evidence p = new Evidence(Evidence.Kind.LOGIC, Claim.Status.SUPPORTED, true,
                    "every part holds — " + String.join("; ", held));
            for (Claim part : claim.parts) {
                if (part.decidingEvidence() != null) {
                    p.anchors.addAll(part.decidingEvidence().anchors);
                }
            }
            return decide(claim, p);
        }
        claim.status = Claim.Status.UNDECIDED;
        claim.undecidedReason = "not every part is decided — " + String.join("; ", open)
                + (held.isEmpty() ? "" : "; holding: " + String.join("; ", held));
        return claim;
    }

    List<String> split(String text) {
        JsonNode node = json(llm.apply(SPLIT_PROMPT.formatted(text)));
        List<String> out = new ArrayList<>();
        if (node != null) {
            for (JsonNode p : node.path("parts")) {
                String t = p.asText("").strip();
                if (t.length() >= 12) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** Maps one atomic claim: existence, translation, the call graph and the lines, a recording, then logic. */
    Claim mapAtomic(Claim claim) {
        // 1. existence
        List<CitationValidator.Missing> gone = CitationValidator.missing(claim.text,
                CitationValidator.repoFor(code.root()));
        if (!gone.isEmpty()) {
            Evidence p = new Evidence(Evidence.Kind.STATIC, Claim.Status.REFUTED, true,
                    "the claim rests on code that does not exist: "
                            + String.join("; ", gone.stream().map(CitationValidator.Missing::describe).toList()));
            return decide(claim, p);
        }
        // 2. translation
        Map<String, Object> hyp = translate(claim);
        claim.hypothesis = hyp;
        String kind = str(hyp.get("kind"));
        List<String> targets = specs(hyp.get("targets"));
        List<String> path = specs(hyp.get("path"));
        String entry = str(hyp.get("entry"));
        // 3. static
        StringBuilder evidence = new StringBuilder();
        List<String> anchors = new ArrayList<>();
        List<Located> located = new ArrayList<>();
        for (String t : targets) {
            List<Located> l = code.locate(t);
            if (l.isEmpty()) {
                evidence.append("CodeGraph locate ").append(t).append(": no declaration\n");
                continue;
            }
            located.add(l.get(0));
            anchors.add(anchor(l.get(0)));
            evidence.append(source(l.get(0), 60)).append('\n');
        }
        List<String> chain = new ArrayList<>();
        if (!entry.isBlank() && !targets.isEmpty() && !path.isEmpty()
                && !entry.equals(targets.get(targets.size() - 1))) {
            Reach r = code.reach(entry, targets.get(targets.size() - 1));
            evidence.append("CodeGraph reach ").append(entry).append(" -> ")
                    .append(targets.get(targets.size() - 1)).append(": ");
            if (certain(r)) {
                evidence.append("a call path of ").append(r.path().size() - 1).append(" call(s): ")
                        .append(String.join(" -> ", r.path()));
                chain = r.path();
            } else if (r.reachable()) {
                // the graph over-approximates virtual dispatch: such a path is a possibility, never a shown call
                evidence.append("only a POSSIBLE path through dispatch candidates (an over-approximation; it does not show the "
                        + "call happens): ").append(String.join(" -> ", r.path()));
            } else {
                evidence.append(r.status());
            }
            evidence.append('\n');
            if (!r.reachable() && "no-path".equals(r.status())
                    && ownPath(claim.text, entry, targets.get(targets.size() - 1))) {
                Evidence p = new Evidence(Evidence.Kind.STATIC, Claim.Status.REFUTED, true,
                        "the claimed path does not exist: CodeGraph finds no call path from " + entry + " to "
                                + targets.get(targets.size() - 1));
                p.anchors.addAll(anchors);
                return decide(claim, p);
            }
        }
        if (located.isEmpty() && !"outside".equals(kind)) {
            // the claim names no code: read the methods of the types its words describe
            for (String type : relatedTypes(claim.text, code.typeNames(), 2)) {
                for (String m : relevantMethods(code.methodsOf(type), claim.text, 2)) {
                    List<Located> l = mainCode(code.locate(m));
                    if (!l.isEmpty() && !anchors.contains(anchor(l.get(0)))) {
                        located.add(l.get(0));
                        anchors.add(anchor(l.get(0)));
                        evidence.append(source(l.get(0), 60)).append('\n');
                    }
                }
            }
            if (!located.isEmpty()) {
                log.add(claim.id + ": no code named; read "
                        + String.join(", ", located.stream().map(Located::spec).toList()));
            }
        }
        if (located.isEmpty() && !"outside".equals(kind)) {
            claim.status = Claim.Status.UNDECIDED;
            claim.undecidedReason = "the claim's code could not be located (" + String.join(", ", targets) + ")";
            return claim;
        }
        if (!"behaviour".equals(kind)) {
            Evidence p = judgeStatic(claim, evidence.toString(), anchors, chain,
                    "outside".equals(kind) ? Evidence.Kind.LOGIC : Evidence.Kind.STATIC);
            if (p.decides) {
                return decide(claim, p);
            }
            claim.evidence.add(p);
            if ("outside".equals(kind)) {
                claim.status = Claim.Status.UNDECIDED;
                claim.undecidedReason = "about something outside this repository; the code neither shows nor refutes it"
                        + (blank(p.summary) ? "" : " (" + p.summary + ")");
                return claim;
            }
        }
        // 4. runtime
        Evidence runtimeProof = runtime(claim, hyp, located, evidence.toString());
        if (runtimeProof != null && runtimeProof.decides) {
            return decide(claim, runtimeProof);
        }
        if (runtimeProof != null) {
            claim.evidence.add(runtimeProof);
        }
        // 5. logic
        StringBuilder all = new StringBuilder(evidence);
        if (runtimeProof != null && runtimeProof.events != null) {
            all.append("\nRECORDED (").append(runtimeProof.test).append("):\n").append(runtimeProof.events);
        }
        Evidence logic = judgeStatic(claim, all.toString(), anchors, chain, Evidence.Kind.LOGIC);
        boolean ownLines = located.stream().anyMatch(l -> subjectIs(claim.text, l.spec()));
        // "m does not hash X": m's own lines
        if (logic.decides && logic.verdict == Claim.Status.SUPPORTED && "behaviour".equals(kind)
                && absence(claim.text) && !ownLines) {
            // that something does not happen at runtime is shown by a recording or the call graph, never by reading lines;
            // "cannot X" is different: an explicit rejection on X's path shows it
            logic.decides = false;
            logic.verdict = null;
            logic.summary = "a negative claim about what happens is not shown by reading lines (a recording or the call graph "
                    + "must show it): " + logic.summary;
        }
        if (logic.decides) {
            return decide(claim, logic);
        }
        claim.evidence.add(logic);
        claim.status = Claim.Status.UNDECIDED;
        claim.undecidedReason = blank(logic.summary) ? "neither the code, a recording nor the lines decide it"
                : logic.summary;
        return claim;
    }

    Map<String, Object> translate(Claim claim) {
        StringBuilder decl = new StringBuilder();
        Set<String> names = new LinkedHashSet<>(candidateNames(claim.text));
        names.addAll(contextTypes);
        names.addAll(relatedTypes(claim.text, code.typeNames(), 4));
        for (String name : names) {
            if (decl.length() > 3000) {
                break;
            }
            if (!name.contains("#") && Character.isUpperCase(name.charAt(0))) {
                List<String> methods = code.methodsOf(name);
                if (!methods.isEmpty()) {
                    decl.append(name).append(" declares: ").append(String.join(", ", methods.subList(0, Math.min(40, methods.size()))))
                            .append('\n');
                } else if (!code.definitionOf(name).isBlank()) {
                    decl.append(name).append(" is a protobuf message (generated; no Java source)\n");
                }
                continue;
            }
            List<Located> found = mainCode(code.locate(name));
            for (Located l : found.subList(0, Math.min(4, found.size()))) {
                decl.append(l.spec()).append(" at ").append(anchor(l)).append('\n');
            }
        }
        JsonNode node = json(translator.apply(TRANSLATE_PROMPT.formatted(claim.text, decl.length() == 0 ? "(none located)" : decl)));
        Map<String, Object> hyp = new LinkedHashMap<>();
        if (node == null) {
            hyp.put("kind", "structure");
            log.add(claim.id + ": translation returned no JSON");
            return hyp;
        }
        node.fields().forEachRemaining(e -> hyp.put(e.getKey(), e.getValue().isArray()
                ? JSON.convertValue(e.getValue(), List.class) : e.getValue().asText("")));
        for (String key : List.of("targets", "path")) {
            if (hyp.get(key) != null) {
                List<String> resolved = new ArrayList<>();
                for (String spec : specs(hyp.get(key))) {
                    String r = resolve(claim, spec);
                    if (r != null && !resolved.contains(r)) {
                        resolved.add(r);
                    }
                }
                hyp.put(key, resolved);
            }
        }
        if (!str(hyp.get("entry")).isBlank()) {
            String r = resolve(claim, str(hyp.get("entry")));
            hyp.put("entry", r == null ? "" : r);
        }
        return hyp;
    }

    /**
     * The declaration a translated spec means: itself when it exists, else the method of that name (the translator put it
     * on a type that does not declare it - a translation slip, not the claim's), preferring a type that shares a word with
     * the one it named; null when no method has that name.
     */
    String resolve(Claim claim, String spec) {
        String n = normalize(spec);
        List<Located> exact = mainCode(code.locate(n));
        String method = n.contains("#") ? n.substring(n.indexOf('#') + 1) : n;
        String chosenType = n.contains("#") ? n.substring(0, n.indexOf('#')) : "";
        if (!chosenType.isEmpty() && !claim.text.contains(chosenType) && !contextTypes.contains(chosenType)) {
            // the claim does not name the type the translator chose: the request's own type declaring the method comes first
            for (String type : contextTypes) {
                List<Located> own = mainCode(code.locate(type + "#" + method));
                if (!own.isEmpty()) {
                    log.add(claim.id + ": " + spec + " re-anchored to the request's " + own.get(0).spec());
                    return own.get(0).spec();
                }
            }
        }
        if (!exact.isEmpty()) {
            return exact.get(0).spec();
        }
        String name = n.contains("#") ? n.substring(n.indexOf('#') + 1) : n;
        List<Located> byName = mainCode(code.locate(name));
        if (byName.isEmpty()) {
            log.add(claim.id + ": " + spec + " is not declared in the main code");
            return null;
        }
        String typed = n.contains("#") ? n.substring(0, n.indexOf('#')) : "";
        Located best = byName.size() <= 2 ? byName.get(0) : null;   // a common name on an unrelated type means nothing
        for (Located l : byName) {
            if (contextTypes.contains(l.spec().replaceAll("#.*", ""))) {
                best = l;   // the request's own type
                break;
            }
        }
        for (Located l : byName) {
            if (best != null && contextTypes.contains(best.spec().replaceAll("#.*", ""))) {
                break;
            }
            if (!typed.isEmpty() && sharesWord(typed, l.spec().replaceAll("#.*", ""))) {
                best = l;
                break;
            }
        }
        if (best == null) {
            log.add(claim.id + ": " + spec + " is ambiguous (" + byName.size() + " methods named " + name + ", none on a type like "
                    + typed + ")");
            return null;
        }
        if (!best.spec().equals(n)) {
            log.add(claim.id + ": " + spec + " resolved to " + best.spec() + (byName.size() > 1 ? " (one of " + byName.size() + ")" : ""));
        }
        return best.spec();
    }

    /**
     * The repository's types whose names are made of the claim's words ("renewing a loan" → LoanRenewService):
     * two words at least, rarer words weigh more, a type that contains an identifier the claim names (LoanReturn →
     * LoanReturnService) first, the closest first.
     */
    static List<String> relatedTypes(String text, Set<String> types, int max) {
        String t = text == null ? "" : text;
        Set<String> stems = new LinkedHashSet<>();
        for (String w : ClaimLedger.words(t.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").replace('_', ' '))) {
            stems.add(stem(w));
        }
        List<String> identifiers = new ArrayList<>();
        Matcher id = Pattern.compile("\\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\\b").matcher(t);
        while (id.find()) {
            identifiers.add(id.group());
        }
        Map<String, Integer> frequency = new HashMap<>();
        for (String type : types) {
            for (String part : parts(type)) {
                frequency.merge(stem(part.toLowerCase(Locale.ROOT)), 1, Integer::sum);
            }
        }
        double n = Math.max(1, types.size());
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (String type : types) {
            double score = 0;
            int hit = 0;
            for (String part : parts(type)) {
                String st = stem(part.toLowerCase(Locale.ROOT));
                double weight = Math.log(n / frequency.getOrDefault(st, 1));
                if (st.length() > 2 && stems.contains(st)) {
                    hit++;
                    score += weight;
                } else {
                    score -= weight / 4;
                }
            }
            boolean named = identifiers.stream().anyMatch(type::contains);
            if (hit >= 2 || named) {
                scored.add(Map.entry(type, score + (named ? 100 : 0)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.stream().limit(max).map(Map.Entry::getKey).toList();
    }

    static String[] parts(String camel) {
        return camel.split("(?<=[a-z0-9])(?=[A-Z])");
    }

    /** A crude stem: deleting, deletes, delete → delet; folder, folders → fold; approving, approves → approv. */
    static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("ing", "ers", "er", "ed", "es", "s", "e")) {
            if (w.endsWith(suffix) && w.length() - suffix.length() >= 3) {
                return w.substring(0, w.length() - suffix.length());
            }
        }
        return w;
    }

    /** The methods whose names share the most words with the text, at most {@code max}, none sharing none. */
    static List<String> relevantMethods(List<String> methods, String text, int max) {
        Set<String> stems = new LinkedHashSet<>();
        for (String w : ClaimLedger.words((text == null ? "" : text).replaceAll("(?<=[a-z0-9])(?=[A-Z])", " "))) {
            stems.add(stem(w));
        }
        List<String[]> scored = new ArrayList<>();
        for (String m : methods) {
            String name = m.contains("#") ? m.substring(m.indexOf('#') + 1) : m;
            int hit = 0;
            for (String part : parts(name)) {
                String st = stem(part);
                if (st.length() > 2 && stems.contains(st)) {
                    hit++;
                }
            }
            if (hit > 0) {
                scored.add(new String[] {m, String.valueOf(hit)});
            }
        }
        scored.sort((a, b) -> Integer.compare(Integer.parseInt(b[1]), Integer.parseInt(a[1])));
        return scored.stream().limit(max).map(x -> x[0]).toList();
    }

    /** The declarations outside test code and generated code: a claim about the system is about its main code. */
    static List<Located> mainCode(List<Located> located) {
        return located.stream().filter(l -> !isTestCode(l.file()) && !isGenerated(l.file())).toList();
    }

    /** Build output and generated sources (protobuf data classes and their generated tests carry no behaviour). */
    public static boolean isGenerated(Path file) {
        String f = file == null ? "" : file.toString();
        return f.contains("/build/") || f.contains("/generated/");
    }

    /** Test sources, test fixtures and the conventions' spec module (whose code lives under src/main). */
    public static boolean isTestCode(Path file) {
        return file != null && testCode(SpecConventions.load()).matcher(file.toString()).find();
    }

    /** Test code: the usual test source sets and the conventions' extra ones, the conventions' spec module, test
     *  fixtures, and a file named like a test. */
    static Pattern testCode(SpecConventions conventions) {
        StringBuilder sets = new StringBuilder("test|testFixtures|itest|jmh|integrationTest");
        for (String set : conventions.extraTestSourceSets()) {
            sets.append('|').append(Pattern.quote(set));
        }
        String module = conventions.specModuleMarker();
        return Pattern.compile("/src/(" + sets + ")/" + (module.isEmpty() ? "" : "|" + Pattern.quote(module))
                + "|/testFixtures/|(Test|Tests|IT|Suite|TestCases)\\.java$");
    }

    /** Two type names share two words longer than three letters (ShelfCatalog ~ CachedShelfCatalogImpl). */
    static boolean sharesWord(String a, String b) {
        Set<String> x = new LinkedHashSet<>();
        for (String w : parts(a)) {
            if (w.length() > 3) {
                x.add(w);
            }
        }
        int shared = 0;
        for (String w : parts(b)) {
            if (w.length() > 3 && x.remove(w)) {
                shared++;
            }
        }
        return shared >= Math.min(2, Math.max(1, parts(a).length - 1));
    }

    /**
     * The judge on the lines read so far; when it names what would settle the claim (next_read), the inspector reads those
     * declarations and asks again - twice at most, and only while something new was read.
     */
    private Evidence judgeStatic(Claim claim, String evidence, List<String> anchors, List<String> chain,
            Evidence.Kind kind) {
        StringBuilder read = new StringBuilder(evidence);
        List<String> seen = new ArrayList<>(anchors);
        Evidence p = null;
        for (int round = 0; round < 3; round++) {
            String prompt = STATIC_JUDGE_PROMPT.formatted(claim.text, read.isEmpty() ? "(no lines located)" : read);
            JsonNode v = json(llm.apply(prompt));
            if (v == null) {
                v = json(llm.apply(prompt));   // one retry: a reply without JSON is no judgement
            }
            String status = v == null ? "insufficient"
                    : v.path("status").asText("insufficient").toLowerCase(Locale.ROOT);
            Claim.Status verdict = "supported".equals(status) ? Claim.Status.SUPPORTED
                    : "contradicted".equals(status) ? Claim.Status.REFUTED : null;
            String summary = v == null ? "no judgement" : v.path("summary").asText("");
            String decisive = v == null ? "" : v.path("evidence").asText("");
            p = new Evidence(kind, verdict, verdict != null, summary.isBlank() ? status : summary
                    + (decisive.isBlank() ? "" : " (" + decisive + ")"));
            p.anchors.addAll(seen);
            p.callChain.addAll(chain);
            String next = v == null ? "" : v.path("next_read").asText("");
            if (verdict == Claim.Status.SUPPORTED) {
                String gap = unshown(claim.text, summary, read.toString());
                if (gap != null) {
                    // the skeptic found a phrase of the claim the lines do not show: the support does not stand
                    p.verdict = null;
                    p.decides = false;
                    p.summary = "not every phrase is shown — " + gap + " (the judge had read: " + p.summary + ")";
                    log.add(claim.id + ": support withdrawn, unshown: " + gap);
                    return p;
                }
            }
            if (verdict == Claim.Status.REFUTED) {
                String uncovered = notOpposite(claim.text, summary, read.toString());
                if (uncovered != null) {
                    // a case the claim does not cover is a missing condition, not the opposite of the claim
                    p.verdict = null;
                    p.decides = false;
                    p.summary = "the lines show a case the claim leaves out, not its opposite — " + uncovered
                            + " (the judge had read: " + p.summary + ")";
                    log.add(claim.id + ": refutation withdrawn: " + uncovered);
                    return p;
                }
            }
            if (verdict != null || next.isBlank()) {
                return p;
            }
            p.summary += " — to settle: " + next;
            if (round == 2) {
                return p;
            }
            List<String> fetched = new ArrayList<>();
            Set<String> asked = new LinkedHashSet<>(candidateNames(next));
            Matcher typeWord = Pattern.compile("\\b[A-Z][A-Za-z0-9]{3,}\\b").matcher(next);
            while (typeWord.find()) {
                asked.add(typeWord.group());
            }
            for (String name : asked) {
                if (!name.contains("#") && Character.isUpperCase(name.charAt(0))) {
                    List<String> methods = code.methodsOf(name);
                    if (methods.isEmpty() && !seen.contains(name)) {
                        String definition = code.definitionOf(name);
                        if (!definition.isBlank()) {
                            seen.add(name);
                            fetched.add(name + " (protobuf)");
                            read.append(definition).append('\n');
                        }
                    }
                    if (!methods.isEmpty() && !seen.contains(name)) {
                        seen.add(name);
                        fetched.add(name);
                        read.append(name).append(" declares: ")
                                .append(String.join(", ", methods.subList(0, Math.min(40, methods.size()))))
                                .append('\n');
                        for (String m : relevantMethods(methods, claim.text + " " + next, 2)) {
                            List<Located> l = mainCode(code.locate(m));
                            if (!l.isEmpty() && !seen.contains(anchor(l.get(0)))) {
                                seen.add(anchor(l.get(0)));
                                fetched.add(m);
                                read.append(source(l.get(0), 60)).append('\n');
                            }
                        }
                    }
                    continue;
                }
                List<Located> found = mainCode(code.locate(normalize(name)));
                if (found.size() > 2) {
                    // a common name (delete, get): only its declarations on the types the claim is about
                    List<String> related = relatedTypes(claim.text + " " + next, code.typeNames(), 8);
                    found = found.stream().filter(l -> related.contains(l.spec().replaceAll("#.*", ""))).toList();
                }
                for (Located l : found.subList(0, Math.min(2, found.size()))) {
                    if (!seen.contains(anchor(l))) {
                        seen.add(anchor(l));
                        fetched.add(l.spec());
                        read.append(source(l, 60)).append('\n');
                    }
                }
            }
            if (fetched.isEmpty()) {
                return p;
            }
            log.add(claim.id + ": read " + String.join(", ", fetched) + " for the judge");
        }
        return p;
    }

    boolean ownPath(String text, String entry, String target) {
        List<Located> start = code.locate(entry);
        if (start.isEmpty() || isTestCode(start.get(0).file())) {
            return false;
        }
        String from = normalize(entry).replaceAll(".*#", ""), to = normalize(target).replaceAll(".*#", "");
        // one method on an implementation and its interface is not a path the claim asserts
        return !from.equals(to) && namesMethod(text, entry) && namesMethod(text, target);
    }

    /**
     * True when the claim's subject is the spec's method ("slugOf does not …", "The `sameValue()` method …").
     */
    static boolean subjectIs(String text, String spec) {
        String n = normalize(spec);
        String method = n.contains("#") ? n.substring(n.indexOf('#') + 1) : n;
        String t = (text == null ? "" : text).strip().replaceAll("^(?i)(the\\s+)?[`'\"]*", "");
        String type = n.contains("#") ? n.substring(0, n.indexOf('#')) : "";
        return t.startsWith(method) || (!type.isEmpty() && t.startsWith(type + "." + method))
                || (!type.isEmpty() && t.startsWith(type + "#" + method));
    }

    /** True when the text names the spec's method as a word. */
    static boolean namesMethod(String text, String spec) {
        String n = normalize(spec);
        String method = n.contains("#") ? n.substring(n.indexOf('#') + 1) : n;
        return Pattern.compile("\\b" + Pattern.quote(method) + "\\b")
                .matcher(text == null ? "" : text).find();
    }

    /** True when the text names the spec's method (a word) or its type. */
    static boolean names(String text, String spec) {
        String n = normalize(spec);
        String method = n.contains("#") ? n.substring(n.indexOf('#') + 1) : n;
        String type = n.contains("#") ? n.substring(0, n.indexOf('#')) : "";
        String t = text == null ? "" : text;
        return Pattern.compile("\\b" + Pattern.quote(method) + "\\b").matcher(t).find()
                || (!type.isEmpty() && t.contains(type));
    }

    /** A path that shows the call: resolved, and no hop is one of several dispatch candidates. */
    static boolean certain(Reach r) {
        return r.reachable() && "resolved".equals(r.status())
                && r.path().stream().noneMatch(h -> h.contains("candidates"));
    }

    /**
     * The tests that reach a target, the nearest first: the target type's own test class, then tests named after the type,
     * then tests in the target's module; the call graph's over-approximation lists unrelated tests too.
     */
    List<Located> rankTests(List<String> tests, Located target) {
        String type = target.spec().contains("#") ? target.spec().substring(0, target.spec().indexOf('#')) : target.spec();
        String module = moduleOf(target.file());
        List<Located> found = new ArrayList<>();
        List<Integer> rank = new ArrayList<>();
        for (String t : tests) {
            List<Located> l = code.locate(t).stream().filter(x -> !isGenerated(x.file())).toList();
            if (l.isEmpty()) {
                continue;
            }
            String testType = t.contains("#") ? t.substring(0, t.indexOf('#')) : t;
            int r = testType.equals(type + "Test") ? 0 : testType.startsWith(type) ? 1
                    : module != null && module.equals(moduleOf(l.get(0).file())) ? 2 : 3;
            if (r == 3) {
                continue;   // another module's test reaches the target only through name-matched calls (handle(), get())
            }
            found.add(l.get(0));
            rank.add(r);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(rank.get(a), rank.get(b)));   // stable: the graph's order within a rank
        return order.stream().map(found::get).toList();
    }

    /** The module directory of a source file (the path before /src/), or null. */
    static String moduleOf(Path file) {
        String f = file == null ? "" : file.toAbsolutePath().toString();
        int at = f.indexOf("/src/");
        return at < 0 ? null : f.substring(0, at);
    }

    /**
     * Records a test that reaches the target (written in the worktree when none does) and judges the claim on the events.
     */
    private Evidence runtime(Claim claim, Map<String, Object> hyp, List<Located> located, String sourceEvidence) {
        if (runtime == null || located.isEmpty()) {
            return null;
        }
        Located target = located.get(located.size() - 1);
        List<Located> ranked = new ArrayList<>(rankTests(code.tests(target.spec()), target));
        Located test = ranked.isEmpty() ? null : ranked.remove(0);
        String condition = str(hyp.get("condition"));
        Evidence last = null;
        boolean retried = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (test == null && canWrite()) {
                try {
                    written++;
                    test = writer.write(claim, null, scenario(claim, hyp));
                } catch (Exception e) {
                    log.add(claim.id + ": no test reaches " + target.spec() + " and writing one failed: " + e.getMessage());
                }
            }
            if (test == null) {
                Evidence p = new Evidence(Evidence.Kind.RUNTIME, null, false, "no test reaches " + target.spec());
                return last != null ? last : p;
            }
            String dir = recordingByTest.get(test.spec());
            if (dir == null) {
                if (recordings >= maxRecordings) {
                    return new Evidence(Evidence.Kind.RUNTIME, null, false,
                            "the recording budget (" + maxRecordings + ") is spent");
                }
                try {
                    recordings++;
                    dir = runtime.record(test, located);
                    recordingByTest.put(test.spec(), dir);
                } catch (Exception e) {
                    String why = String.valueOf(e.getMessage());
                    log.add(claim.id + ": recording " + test.spec() + " failed: "
                            + (why.length() > 300 ? why.substring(0, 300) + "\u2026" : why));
                    if (!ranked.isEmpty() && !retried) {
                        retried = true;
                        test = ranked.remove(0);   // the next nearest test, once
                        attempt--;
                        continue;
                    }
                    return new Evidence(Evidence.Kind.RUNTIME, null, false, "recording " + test.spec() + " failed");
                }
            }
            String events = events(runtime.query(dir), located);
            if (!entered(events, located)) {
                // a recording in which the claim's code never ran shows nothing about the claim
                log.add(claim.id + ": " + test.spec() + " never entered "
                        + String.join(", ", located.stream().map(Located::spec).toList()));
                if (!ranked.isEmpty() && !retried) {
                    retried = true;
                    test = ranked.remove(0);
                    attempt--;
                    continue;
                }
                Evidence none = new Evidence(Evidence.Kind.RUNTIME, null, false,
                        "the recorded test " + test.spec() + " never entered the claim's code");
                none.test = test.spec();
                return none;
            }
            JsonNode v = json(llm.apply(RUNTIME_JUDGE_PROMPT.formatted(claim.text,
                    condition.isBlank() ? "(none stated)" : condition,
                    str(hyp.get("observable")), test.spec(),
                    events.isBlank() ? "(the targets were not entered)" : events,
                    sourceEvidence.length() > 6000 ? sourceEvidence.substring(0, 6000) : sourceEvidence)));
            String status = v == null ? "insufficient"
                    : v.path("status").asText("insufficient").toLowerCase(Locale.ROOT);
            Claim.Status verdict = "supported".equals(status) ? Claim.Status.SUPPORTED
                    : "contradicted".equals(status) ? Claim.Status.REFUTED : null;
            Evidence p = new Evidence(Evidence.Kind.RUNTIME, verdict, verdict != null,
                    v == null ? "no judgement" : v.path("summary").asText(status));
            p.test = test.spec();
            p.recording = dir;
            p.events = events.length() > 3000 ? events.substring(0, 3000) + "\u2026" : events;
            p.anchors.add(anchor(target));
            if (verdict == Claim.Status.REFUTED) {
                // as for the lines: a recording refutes only when it shows the claim's opposite on the claim's own path
                String uncovered = notOpposite(claim.text, p.summary, "RECORDED (" + test.spec() + "):\n" + events
                        + "\n\nSOURCE:\n"
                        + (sourceEvidence.length() > 6000 ? sourceEvidence.substring(0, 6000) : sourceEvidence));
                if (uncovered != null) {
                    p.verdict = null;
                    p.decides = false;
                    p.summary = "the recording shows a case the claim leaves out, not its opposite \u2014 " + uncovered
                            + " (the judge had read: " + p.summary + ")";
                    log.add(claim.id + ": runtime refutation withdrawn: " + uncovered);
                    return p;
                }
            }
            if (verdict != null) {
                return p;
            }
            last = p;
            // the recorded test does not meet the claim's condition: write one that does, once
            if (!canWrite() || v == null || v.path("missing").asText("").isBlank() || attempt > 0) {
                return p;
            }
            try {
                written++;
                Located focused = writer.write(claim, test, scenario(claim, hyp)
                        + "\nThe existing test " + test.spec()
                        + " did not settle it: " + v.path("missing").asText());
                test = focused;
            } catch (Exception e) {
                log.add(claim.id + ": writing a test for the missing condition failed: " + e.getMessage());
                return p;
            }
            if (test == null) {
                return p;
            }
        }
        return last;
    }

    /** The recorded calls of the targets (arguments, returns) and the events inside the last target call. */
    /** True when the recording shows at least one of the targets entered. */
    static boolean entered(String events, List<Located> targets) {
        for (Located t : targets) {
            if (!events.contains(t.spec() + ": not entered")
                    && !events.contains(t.spec() + ": the recording could not be read")) {
                return true;
            }
        }
        return false;
    }

    static String events(RecordedExecutionReader q, List<Located> targets) {
        StringBuilder sb = new StringBuilder();
        for (Located t : targets) {
            String method = t.spec().contains("#") ? t.spec().substring(t.spec().indexOf('#') + 1) : t.spec();
            String owner = t.spec().contains("#") ? t.spec().substring(0, t.spec().indexOf('#')) : "";
            try {
                // the reader's --method is a substring match: keep the calls of the target method itself
                List<RecordedExecutionReader.Call> calls = q.calls(owner.isEmpty() ? method : owner + "." + method, 6)
                        .stream()
                        .filter(c -> c.method().equals(method) && (owner.isEmpty() || c.owner().contains(owner)))
                        .toList();   // an Impl of the type too
                if (calls.isEmpty()) {
                    sb.append(t.spec()).append(": not entered in this recording\n");
                    continue;
                }
                for (RecordedExecutionReader.Call c : calls) {
                    sb.append("[seq ").append(c.seq()).append("] ").append(c.render()).append('\n');
                }
                for (String e : q.inside(calls.get(calls.size() - 1), 40)) {
                    sb.append("    ").append(e).append('\n');
                }
            } catch (Exception e) {
                sb.append(t.spec()).append(": the recording could not be read (").append(e.getMessage()).append(")\n");
            }
        }
        return sb.toString();
    }

    private static String scenario(Claim claim, Map<String, Object> hyp) {
        return "Write a focused JUnit test that exercises: " + claim.text
                + "\nSet up this condition: " + str(hyp.get("condition"))
                + "\nIt must reach " + String.join(" -> ", specs(hyp.get("path"))) + " " + specs(hyp.get("targets"))
                + "\nso that a recording shows: " + str(hyp.get("observable"));
    }

    private Claim decide(Claim claim, Evidence p) {
        claim.evidence.add(p);
        claim.status = p.verdict;
        claim.undecidedReason = null;
        return claim;
    }

    /** Numbered source of a declaration: from its line to the closing brace, at most {@code max} lines. */
    static String source(Located l, int max) {
        try {
            List<String> lines = Files.readAllLines(l.file(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder("--- ").append(l.spec()).append(" (")
                    .append(l.file().getFileName()).append(")\n");
            int depth = 0;
            boolean opened = false;
            for (int i = Math.max(0, l.line() - 1); i < lines.size() && i < l.line() - 1 + max; i++) {
                String s = lines.get(i);
                sb.append(String.format("%6d\t%s%n", i + 1, s));
                for (char ch : s.toCharArray()) {
                    if (ch == '{') {
                        depth++;
                        opened = true;
                    } else if (ch == '}') {
                        depth--;
                    }
                }
                if (opened && depth <= 0) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "--- " + l.spec() + ": source not readable";
        }
    }

    static String anchor(Located l) {
        return l.file().getFileName() + ":" + l.line();
    }

    /** Type#member specs and CamelCase type names the claim mentions, for the translator's declarations. */
    static Set<String> candidateNames(String text) {
        Set<String> out = new LinkedHashSet<>();
        String t = text == null ? "" : text;
        Matcher m = Pattern.compile("\\b([A-Z][A-Za-z0-9_]+)[#.]([a-z][A-Za-z0-9_]*)").matcher(t);
        while (m.find() && out.size() < 8) {
            out.add(m.group(1) + "#" + m.group(2));
        }
        // a method named in prose (findNearest, sameValue) and a type (LoanRenewService): two humps at least
        Matcher w = Pattern.compile("\\b([a-z]+[A-Z][A-Za-z0-9_]*|[A-Z][a-z0-9]+[A-Z][A-Za-z0-9_]*)\\b")
                .matcher(t);
        while (w.find() && out.size() < 14) {
            out.add(w.group(1));
        }
        return out;
    }

    /** `a.b.Type.method(..)`, `Type.method`, `Type#method` → `Type#method`; a bare name stays. */
    public static String normalize(String spec) {
        String s = spec == null ? "" : spec.strip().replaceAll("\\(.*\\)$", "");
        if (!s.contains("#")) {
            int dot = s.lastIndexOf('.');
            if (dot > 0 && dot + 1 < s.length() && Character.isLowerCase(s.charAt(dot + 1))) {
                s = s.substring(0, dot) + "#" + s.substring(dot + 1);
            }
        }
        int hash = s.indexOf('#');
        if (hash > 0) {
            String type = s.substring(0, hash);
            s = type.substring(type.lastIndexOf('.') + 1) + s.substring(hash);
        }
        return s;
    }

    static List<String> specs(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                String s = String.valueOf(x).strip();
                if (!s.isEmpty()) {
                    out.add(normalize(s));
                }
            }
        }
        return out;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** The first JSON object in a model reply, or null. */
    static JsonNode json(String reply) {
        if (reply == null) {
            return null;
        }
        int a = reply.indexOf('{'), b = reply.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            return JSON.readTree(reply.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
