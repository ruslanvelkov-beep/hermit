package hermit.analysis.graph;

import hermit.analysis.lex.Token;
import hermit.analysis.lex.TokenType;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * A caller walk from a target method back to the test methods that transitively exercise it: the existing tests of a
 * code path, which are the template candidates for a new test. A test method is one annotated with a JUnit test
 * annotation ({@code @Test}, {@code @ParameterizedTest}, {@code @RepeatedTest}, {@code @TestFactory} or
 * {@code @TestTemplate}); methods under a test source set that carry none are reported separately as helpers.
 */
public final class TestReach {

    /** {@code overlap} = identifiers the test body shares with the target's body; {@code score} weights those by what
     *  they mean (a collaborator of the target counts more than common setup) and adds the intent words the test's NAME
     *  shares with the target and its callees. Pool-independent: the same roots or the whole repo give the same order. */
    /** {@code direct}: the route stays inside the target's own type until the test (test → a method of that type →
     *  target, at most two hops) - the test drives that class itself rather than reaching it through other types. */
    public record Route(String testMethod, List<String> path, int overlap, List<String> shared, double score,
            boolean direct) {
        public int hops() {
            return path.size() - 1;
        }

        public String render() {
            return testMethod + "  score=" + String.format(Locale.ROOT, "%.1f", score) + "  overlap=" + overlap
                    + "  (" + String.join(" <- ", path) + ")";
        }
    }

    public record Result(String target, List<Route> tests, List<String> testHelpers, List<String> notes) {
        public boolean exercised() {
            return !tests.isEmpty();
        }
    }

    /** The JUnit annotations that make a method a test. */
    static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
            "TestTemplate");
    private static final Pattern ANNOTATION_NAME = Pattern.compile("^@\\s*([A-Za-z_][\\w.]*)");

    private final CodeGraph graph;
    private final List<String> notes = new ArrayList<>();

    private static final Set<String> STOP_WORDS = Set.of("test", "tests", "should", "when", "with", "then", "that",
            "this", "does", "doesnt", "given", "uses", "used", "using", "case", "the", "and", "for", "not", "each",
            "from", "into", "only", "also", "still", "value", "values");

    private TestReach(CodeGraph graph) {
        this.graph = graph;
    }

    public static Result of(CodeGraph graph, String targetSpec, int maxDepth) {
        return new TestReach(graph).testsReaching(targetSpec, maxDepth, Integer.MAX_VALUE);
    }

    /** As {@link #of(CodeGraph, String, int)}, visiting at most {@code maxVisited} methods (a common name fans out widely). */
    public static Result of(CodeGraph graph, String targetSpec, int maxDepth, int maxVisited) {
        return new TestReach(graph).testsReaching(targetSpec, maxDepth, maxVisited);
    }

    /** True when every hop between the target and the test is a method of the target's own type, within two hops. */
    static boolean isDirect(List<String> path, List<CodeGraph.MethodRef> targets) {
        if (path.size() > 3) {
            return false;
        }
        Set<String> targetTypes = new HashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            targetTypes.add(t.typeName());
        }
        for (int i = 1; i < path.size() - 1; i++) {
            String hop = path.get(i);
            String type = hop.contains("#") ? hop.substring(0, hop.indexOf('#')) : hop;
            if (!targetTypes.contains(type)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A test type is named after a target type when its stem (the suffix Test, Tests, IT, TestCase or Spec removed)
     * is a prefix of the type's simple name or the other way round, with at least four characters in common:
     * {@code LedgerTest} and {@code LedgerWriterTest} are named after {@code LedgerWriter}.
     */
    static boolean namedAfter(String testType, Set<String> targetSimpleTypes) {
        String stem = simpleName(testType).replaceFirst("(Tests?|IT|TestCase|Spec)$", "");
        if (stem.length() < 4) {
            return false;
        }
        for (String type : targetSimpleTypes) {
            if (type.startsWith(stem) || stem.startsWith(type)) {
                return true;
            }
        }
        return false;
    }

    /** Annotation simple names on a declaration (those before its body). */
    static List<String> annotationNames(Node decl) {
        List<String> names = new ArrayList<>();
        if (decl == null) {
            return names;
        }
        for (Node child : decl.children()) {
            if (child.type() == NodeType.BLOCK) {
                break;
            }
            if (child.type() == NodeType.ANNOTATION) {
                // the node's source text carries its leading trivia, so a Javadoc above @Test would hide
                // the annotation from the regex; the principal token is the name, and the regex is only
                // a fallback
                String n = child.name();
                if (n == null || n.isBlank() || "@".equals(n)) {
                    Matcher m = ANNOTATION_NAME.matcher(stripComments(child.toString()));
                    n = m.find() ? m.group(1) : null;
                }
                if (n != null && !n.isBlank()) {
                    names.add(n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n);
                }
            }
        }
        return names;
    }

    /** Drop leading {@code //} lines and {@code /* … *}{@code /} blocks so an annotation's own text is visible. */
    private static String stripComments(String text) {
        String t = text == null ? "" : text.strip();
        while (true) {
            if (t.startsWith("//")) {
                int nl = t.indexOf('\n');
                t = nl < 0 ? "" : t.substring(nl + 1).strip();
            } else if (t.startsWith("/*")) {
                int end = t.indexOf("*/");
                t = end < 0 ? "" : t.substring(end + 2).strip();
            } else {
                return t;
            }
        }
    }

    static boolean isTestMethod(CodeGraph.MethodRef m) {
        for (String a : annotationNames(m.decl())) {
            if (TEST_ANNOTATIONS.contains(a)) {
                return true;
            }
        }
        return false;
    }

    static boolean inTestSources(CodeGraph.MethodRef m) {
        String p = m.file() == null ? "" : m.file().toString().replace('\\', '/');
        return p.contains("/src/test/") || p.contains("/src/testFixtures/") || p.contains("/src/integrationTest/")
                || m.typeName().endsWith("Test") || m.typeName().endsWith("Tests");
    }

    static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        String simple = dot >= 0 ? typeName.substring(dot + 1) : typeName;
        int dollar = simple.lastIndexOf('$');
        return dollar >= 0 ? simple.substring(dollar + 1) : simple;
    }

    /**
     * The test body's identifiers plus those of the same-class fixture helpers it calls unqualified (one level): tests
     * delegate their arrangement to helper methods, and the target's collaborators live in those bodies, not in the
     * test's own.
     */
    private Set<String> identifiersWithFixtures(CodeGraph.MethodRef test) {
        Set<String> out = new LinkedHashSet<>(identifiers(test.decl()));
        for (CodeGraph.CallSite cs : graph.calleesOfDecl(test.decl())) {
            boolean ownCall = cs.receiverType() == null || cs.receiverType().equals(test.typeName())
                    || cs.receiverType().equals(simpleName(test.typeName()));
            if (!ownCall || cs.calleeName().equals(test.methodName())) {
                continue;
            }
            for (CodeGraph.MethodRef helper : graph.locate(test.typeName() + "#" + cs.calleeName())) {
                if (helper.file().equals(test.file()) && !isTestMethod(helper)) {
                    out.addAll(identifiers(helper.decl()));
                }
            }
        }
        return out;
    }

    /** 1.0 for a collaborator of the target (a callee name or a receiver type), 0.2 for other shared vocabulary. */
    private static double weightOf(String identifier, Set<String> collaborators) {
        return collaborators.contains(identifier) ? 1.0 : 0.2;
    }

    /** Lower-cased camel/underscore words of a name, length ≥ 4 and not a stop word. */
    static Set<String> words(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) {
            return out;
        }
        for (String w : name.replace('_', ' ').replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (w.length() >= 4 && !STOP_WORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Distinct identifier texts in a declaration (length ≥ 4, so `i`, `get`, `of` do not count as shared vocabulary). */
    static Set<String> identifiers(Node decl) {
        Set<String> out = new LinkedHashSet<>();
        if (decl == null) {
            return out;
        }
        collectIdentifiers(decl, out);
        return out;
    }

    private static void collectIdentifiers(Node n, Set<String> out) {
        for (Object p : n.parts()) {
            if (p instanceof Token t) {
                if (t.type() == TokenType.IDENTIFIER && t.text().length() >= 4) {
                    out.add(t.text());
                }
            } else {
                collectIdentifiers((Node) p, out);
            }
        }
    }

    private List<CodeGraph.MethodRef> callersOf(CodeGraph.MethodRef m) {
        LinkedHashSet<CodeGraph.MethodRef> out = new LinkedHashSet<>();
        for (CodeGraph.CallerEdge e : graph.callerEdges(m.spec())) {
            out.add(e.site().caller());
        }
        if (out.isEmpty()) {
            for (CodeGraph.CallSite s : graph.callersOf(m.spec())) {
                out.add(s.caller());
            }
            if (!out.isEmpty() && !notes.contains("some caller hops are name-matched (unresolved dispatch)")) {
                notes.add("some caller hops are name-matched (unresolved dispatch)");
            }
        }
        return new ArrayList<>(out);
    }

    private Result testsReaching(String targetSpec, int maxDepth, int maxVisited) {
        List<CodeGraph.MethodRef> targets = graph.locate(targetSpec);
        if (targets.isEmpty()) {
            return new Result(targetSpec, List.of(), List.of(), List.of("target not found: " + targetSpec));
        }
        record Step(CodeGraph.MethodRef at, List<String> path) {}
        Deque<Step> queue = new ArrayDeque<>();
        Set<Node> visited = new HashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            queue.add(new Step(t, List.of(t.spec())));
            visited.add(t.decl());
        }
        Map<String, Route> tests = new LinkedHashMap<>();
        LinkedHashSet<String> helpers = new LinkedHashSet<>();
        Set<String> targetWords = new LinkedHashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            targetWords.addAll(identifiers(t.decl()));
            targetWords.remove(t.methodName());
        }
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (step.path().size() > 1 && isTestMethod(step.at())) {
                List<String> shared = new ArrayList<>();
                for (String w : identifiersWithFixtures(step.at())) {
                    if (targetWords.contains(w)) {
                        shared.add(w);
                    }
                }
                tests.putIfAbsent(step.at().spec(), new Route(step.at().spec(), step.path(), shared.size(), shared, 0,
                        isDirect(step.path(), targets)));
                continue;   // a test is an endpoint of the reverse walk
            }
            if (step.path().size() > 1 && inTestSources(step.at())) {
                helpers.add(step.at().spec());
            }
            if (step.path().size() > maxDepth || visited.size() >= maxVisited) {
                truncated = true;
                continue;
            }
            for (CodeGraph.MethodRef c : callersOf(step.at())) {
                if (!visited.add(c.decl())) {
                    continue;
                }
                List<String> path = new ArrayList<>(step.path());
                path.add(c.spec());
                queue.add(new Step(c, path));
            }
        }
        List<String> out = new ArrayList<>(notes);
        if (truncated) {
            out.add("caller walk truncated at depth " + maxDepth + " on some branches");
        }
        List<String> helperList = new ArrayList<>(helpers);
        if (helperList.size() > 12) {
            helperList = new ArrayList<>(helperList.subList(0, 12));
            helperList.add("… " + (helpers.size() - 12) + " more");
        }
        // The weights do not depend on the indexed roots, and each one can be explained:
        //   2.0 per camel WORD the test's NAME shares with the target's name or its callees' names (intent), and
        //   1.0 per shared identifier that names one of the target's CALLEES or their receiver types (collaborator),
        //   0.2 for any other shared body identifier (the class's common setup vocabulary).
        Set<String> collaborators = new LinkedHashSet<>();
        Set<String> intentWords = new LinkedHashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            intentWords.addAll(words(t.methodName()));
            for (CodeGraph.CallSite cs : graph.calleesOfDecl(t.decl())) {
                collaborators.add(cs.calleeName());
                intentWords.addAll(words(cs.calleeName()));
                if (cs.receiverType() != null) {
                    collaborators.add(cs.receiverType());
                    collaborators.add(Character.toLowerCase(cs.receiverType().charAt(0))
                            + cs.receiverType().substring(1));
                    intentWords.addAll(words(cs.receiverType()));
                }
            }
        }
        // Two more signals keep a type's own tests ahead of long routes that share vocabulary by chance:
        //   3.0 when the test CLASS is named after the target's type (LedgerTest ~ LedgerWriter), and
        //   DIRECT routes (the test drives the target's own type, at most two hops) sort before every other route.
        Set<String> targetTypes = new LinkedHashSet<>();
        for (CodeGraph.MethodRef t : targets) {
            targetTypes.add(simpleName(t.typeName()));
        }
        List<Route> ranked = new ArrayList<>();
        for (Route r : tests.values()) {
            List<String> shared = new ArrayList<>(new LinkedHashSet<>(r.shared()));
            shared.sort((a, b) -> Double.compare(weightOf(b, collaborators), weightOf(a, collaborators)));
            double score = 0;
            for (String w : shared) {
                score += weightOf(w, collaborators);
            }
            int nameHits = 0;
            for (String w : words(r.testMethod().substring(r.testMethod().indexOf('#') + 1))) {
                if (intentWords.contains(w)) {
                    nameHits++;
                }
            }
            score += 2.0 * nameHits;
            if (namedAfter(r.testMethod().substring(0, r.testMethod().indexOf('#')), targetTypes)) {
                score += 3.0;
            }
            ranked.add(new Route(r.testMethod(), r.path(), r.overlap(), shared, Math.round(score * 10) / 10.0,
                    r.direct()));
        }
        ranked.sort((a, b) -> a.direct() != b.direct() ? Boolean.compare(b.direct(), a.direct())
                : a.score() != b.score() ? Double.compare(b.score(), a.score())
                : a.overlap() != b.overlap() ? Integer.compare(b.overlap(), a.overlap())
                : Integer.compare(a.path().size(), b.path().size()));
        return new Result(targetSpec, ranked, helperList, out);
    }
}

