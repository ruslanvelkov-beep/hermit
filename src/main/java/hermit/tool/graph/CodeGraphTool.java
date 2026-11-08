package hermit.tool.graph;

import hermit.analysis.emu.Interpreter;
import hermit.analysis.flow.EntryTypePropagation;
import hermit.analysis.flow.ExceptionFlow;
import hermit.analysis.flow.ImpactSlice;
import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.GraphQueries;
import hermit.analysis.graph.SpecReach;
import hermit.analysis.graph.TestReach;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.tree.NodeType;
import hermit.tool.api.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parse-tree call-graph queries over a source tree - the fast, compile-free alternative to CodeQlQuery
 * for STRUCTURAL questions (where is a method defined, who calls it, what does it call, does A reach B).
 * The whole project is parsed and indexed in seconds, cached per (projectDir, classesDirs), and a file
 * edited by TreeEdit/TreeCodegen is re-indexed in milliseconds. For dataflow/taint analysis CodeQL
 * remains the right tool; this graph is a name+type over-approximation built for reachability questions.
 */
public class CodeGraphTool implements Tool {

    private static final Map<String, CodeGraph> GRAPHS = new ConcurrentHashMap<>();
    private static final Map<String, ClasspathIndex> INDEXES = new ConcurrentHashMap<>();
    private static final int MAX_ROWS = 100;

    @Override
    public String getDocumentation() {
        return "CodeGraph: whole-project code analysis from PARSE TREES (no compilation, no CodeQL "
                + "database; built in seconds and cached). Parameters: {\"action\": \"locate\"|\"callers\"|"
                + "\"callees\"|\"reach\"|\"impls\"|\"escapes\"|\"catches\"|\"impact\"|\"emulate\"|\"flowreach\"|"
                + "\"specreach\"|\"tests\", "
                + "\"symbol\": \"<method or Type#method; for catches/impact: <FileName.java>:<line>>\", "
                + "\"target\": \"<Type#method, for reach/flowreach>\", \"maxDepth\": <int, default 12>, "
                + "\"projectDir\"/\"classesDir\": optional}. "
                + "locate=where defined; callers=RESOLVED call sites; callees=what it invokes; "
                + "reach=transitive call path; impls=dispatch candidates (overrides/implementations) of an "
                + "interface/virtual method; escapes=which exceptions can propagate OUT of a method, with "
                + "provenance; catches=what the catch clause at file:line actually receives (dead-catch "
                + "detection); impact=forward slice of the statement at file:line (what its value "
                + "influences); emulate=PREDICT a test method's outcome (RED/GREEN + why) WITHOUT compiling "
                + "or running it — use before running the real test; flowreach=reach under the entry's actual "
                + "allocations (narrows interface dispatch to what the entry really wires); specreach=which spec "
                + "verbs (the verb classes' static factories) reach the method — reverse callers up to the "
                + "dispatching handler, joined with the dispatch table and the verb catalogue; says NO HANDLER when "
                + "the method is not on any handler's call chain.";
    }

    /** The shared classpath index for a (possibly empty) ':'-separated classes-dir spec. */
    public static ClasspathIndex indexFor(String classesDirSpec) {
        String key = classesDirSpec == null ? "" : classesDirSpec.trim();
        return INDEXES.computeIfAbsent(key, k -> {
            if (k.isEmpty()) {
                return ClasspathIndex.system();
            }
            List<Path> dirs = new ArrayList<>();
            for (String part : k.split(":")) {
                if (!part.isBlank()) {
                    dirs.add(Path.of(part.trim()));
                }
            }
            return ClasspathIndex.withExtra(dirs);
        });
    }

    /** The cached graph for a project (built on first use). */
    public static CodeGraph graphFor(String projectDir, String classesDirSpec) throws Exception {
        String key = projectDir + "|" + (classesDirSpec == null ? "" : classesDirSpec.trim());
        CodeGraph g = GRAPHS.get(key);
        if (g == null) {
            long t0 = System.nanoTime();
            g = CodeGraph.build(List.of(Path.of(projectDir)), indexFor(classesDirSpec));
            GRAPHS.put(key, g);
            System.out.println("[CodeGraph] indexed " + g.fileCount() + " files under " + projectDir
                    + " in " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        }
        return g;
    }

    /** Re-index one edited file in every cached graph that covers it (called by the edit tools). */
    public static void invalidate(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        GRAPHS.forEach((key, graph) -> {
            String root = key.substring(0, key.indexOf('|'));
            if (abs.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                try {
                    graph.refresh(abs);
                } catch (Exception ignored) {
                    // a broken refresh only means the file drops out of the graph until it parses again
                }
            }
        });
    }

    /**
     * Every action this tool answers (the switch's labels and their synonyms) - a plan naming another
     * is refused before it runs.
     */
    public static final Set<String> ACTIONS = Set.of("callees", "callers", "catches", "emulate", "escapes",
            "exercisedby", "flowreach", "impact", "impls", "locate", "methods", "reach", "spec", "spec-reach",
            "specreach", "specverbs", "tests", "testsof", "testsreaching", "verbs", "whichtests");

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String action = str(parameters.get("action"));
        String symbol = normalizeSymbol(str(parameters.get("symbol")));
        if ((symbol == null || symbol.isBlank()) && !"reach".equals(action) && !"flowreach".equals(action)) {
            symbol = normalizeSymbol(firstNonBlank(str(parameters.get("target")), str(parameters.get("method")),
                    str(parameters.get("spec"))));
        }
        String projectDir = firstNonBlank(str(parameters.get("projectDir")),
                System.getProperty("hermit.projectDir"));
        String classesDir = firstNonBlank(str(parameters.get("classesDir")),
                System.getProperty("hermit.classesDir"));
        if (action == null || symbol == null) {
            if (("reach".equals(action) || "flowreach".equals(action)) && str(parameters.get("target")) != null) {
                return Map.of("status", "Error", "message", action + " needs BOTH symbol=<START Type#method> and "
                        + "target=<Type#method>; you gave only target='" + str(parameters.get("target"))
                        + "'. If the question is 'who calls/reaches the target' use action=callers symbol=<that method>; "
                        + "for the TEST methods that exercise it (template candidates for a new test) use action=tests "
                        + "symbol=<that method>.");
            }
            return Map.of("status", "Error", "message", "action and symbol are required.");
        }
        if ((symbol.contains("*") || String.valueOf(parameters.get("target")).contains("*"))) {
            return Map.of("status", "Error", "message", "wildcards are not supported. To list the TEST methods that "
                    + "exercise a method use action=tests symbol=<Type#method>; to list its callers use action=callers.");
        }
        if (projectDir == null || !Files.isDirectory(Path.of(projectDir))) {
            return Map.of("status", "Error", "message",
                    "projectDir not found (pass projectDir or set -Dhermit.projectDir): " + projectDir);
        }
        try {
            CodeGraph graph = graphFor(projectDir, classesDir);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "Success");
            out.put("action", action);
            out.put("symbol", symbol);
            if (symbol != null && symbol.contains(".java:") && !"catches".equals(action)
                    && !"impact".equals(action)) {
                return Map.of("status", "Error", "message", action + " needs symbol=<Type#method> (a METHOD), not a "
                        + "File.java:line address. Line addresses are for action=catches (what a catch clause receives) "
                        + "and action=impact (what a statement's value influences).");
            }
            if (("tests".equals(action) || "testsreaching".equals(action) || "exercisedby".equals(action)
                    || "testsof".equals(action) || "whichtests".equals(action) || "specreach".equals(action)
                    || "spec".equals(action) || "spec-reach".equals(action)) && looksLikeTypeName(symbol)) {
                boolean testType = symbol.endsWith("Test") || symbol.endsWith("Tests");
                return Map.of("status", "Error", "message", action + " takes the PRODUCTION <Type#method> whose "
                        + (action.startsWith("spec") ? "spec verbs" : "tests") + " you want (e.g. "
                        + "TaskQueue#drainPending); you passed the type '" + symbol + "'"
                        + (testType ? " — a TEST class. To list a test class's own methods use action=methods; to find "
                                + "the tests that exercise a production method, pass THAT method." : ". Add #<method>."));
            }
            switch (action) {
                case "methods" -> {
                    // the prompt teaches action=methods (list a type's methods); it was missing here
                    var listed = GraphQueries.methods(graph, symbol.replaceAll("#.*", ""));
                    if (!listed.found()) {
                        return Map.of("status", "Error", "message", "no type named '" + symbol + "' is indexed");
                    }
                    out.put("methods", listed.text().length() > 6000
                            ? listed.text().substring(0, 6000) + "…" : listed.text());
                }
                case "locate" -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (CodeGraph.MethodRef m : graph.locate(symbol)) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Map.of("type", m.typeName(), "method", m.methodName(),
                                "file", m.file().toString(), "line", m.line()));
                    }
                    out.put("declarations", rows);
                }
                case "callers" -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (CodeGraph.CallSite c : graph.callersOf(symbol)) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Map.of("caller", c.caller().spec(),
                                "file", c.file().toString(), "line", c.line(),
                                "receiverType", c.receiverType() == null ? "?" : c.receiverType()));
                    }
                    out.put("callers", rows);
                }
                case "callees" -> {
                    List<CodeGraph.MethodRef> decls = graph.locate(symbol);
                    if (decls.isEmpty()) {
                        return Map.of("status", "Error", "message", "no declaration found for " + symbol);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (CodeGraph.CallSite c : graph.calleesOf(decls.get(0))) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Map.of("callee", c.calleeName(),
                                "receiverType", c.receiverType() == null ? "?" : c.receiverType(),
                                "line", c.line()));
                    }
                    out.put("callees", rows);
                }
                case "reach" -> {
                    String rawTarget = str(parameters.get("target"));
                    boolean exceptionAsk = parameters.keySet().stream()
                    .anyMatch(k -> k.toLowerCase(Locale.ROOT).contains("exception"));
                    if (exceptionAsk || (rawTarget != null && !rawTarget.contains("#")
                            && !rawTarget.contains(".java:")
                            && Character.isUpperCase(rawTarget.trim().charAt(0)))) {
                        // "which exceptions reach the catch" gets mapped to reach with target=Exception.
                        // reach is CALL-GRAPH reachability between two METHODS; exception questions have
                        // their own actions. Coach instead of answering NOT REACHABLE for a target that
                        // is a type.
                        return Map.of("status", "Error", "message", "reach needs target=<Type#method> (a METHOD"
                                + (rawTarget == null ? "" : "; you passed the type '" + rawTarget + "'")
                                + "). If you are asking which EXCEPTIONS can "
                                + "reach/propagate: use action=escapes with symbol=<Type#method> (what escapes a "
                                + "method) or action=catches with symbol=<Type#method> (what its catch clauses "
                                + "actually receive).");
                    }
                    String target = normalizeSymbol(str(parameters.get("target")));
                    if (target == null) {
                        // the mirror case - a start without a target - was answered with one line and re-planned;
                        // name both roles and the single-symbol actions that answer the likely question
                        return Map.of("status", "Error", "message", "reach needs BOTH symbol=<START Type#method> and "
                                + "target=<Type#method> (can the start transitively call the target?); you gave only "
                                + "symbol='" + symbol + "'. For what it CALLS use action=callees; for what calls it use "
                                + "action=callers; for the TEST methods that exercise it use action=tests.");
                    }
                    if (!target.contains("#") && !target.contains(".java:")
                            && (target.contains(".") || Character.isUpperCase(target.trim().charAt(0)))) {
                        // a bare lower-case name is a METHOD to repair against the start's type: a fully-qualified
                        // TYPE target (com.x.FooTest) slipped past the guard (lower-case first letter) and came
                        // back as a Success "reachable=false" non-result. The planner's real question was
                        // "does the test class exercise the method" - say how to ask it.
                        return Map.of("status", "Error", "message", "reach needs target=<Type#method> (a METHOD); '"
                                + target + "' is a type. reach answers: can 'symbol' (the START method) transitively "
                                + "call 'target'? To ask whether a TEST exercises a method, put the test method first: "
                                + "symbol=<TestClass#testMethod>, target=<Type#method> — or use action=callers on the "
                                + "method to list what invokes it.");
                    }
                    int maxDepth = parameters.get("maxDepth") instanceof Number n ? n.intValue() : 12;
                    CodeGraph.ReachResult r = graph.reach(symbol, target, maxDepth);
                    if (!r.reachable() && r.path().stream().anyMatch(x -> x.startsWith("start not found"))) {
                        // an unresolved START (a placeholder or a type instead of a method) is not "not reachable" -
                        // it is an unanswered question; a Success here let the planner move on with a non-result
                        return Map.of("status", "Error", "message", r.path().get(0) + " — 'symbol' must be a real "
                                + "Type#method (use action=locate to find it; never a <placeholder>).");
                    }
                    out.put("reachable", r.reachable());
                    out.put("path", r.path());
                    out.put("reachStatus", r.status());
                    if (r.nameOnlyHops() > 0) {
                        out.put("nameOnlyHops", r.nameOnlyHops());
                    }
                    out.put("verdict", r.describe(maxDepth));
                    if (r.undecided()) {
                        out.put("hint", "NOT REACHABLE here means DON'T KNOW (search bound hit), not 'no path' — raise "
                                + "maxDepth or ask from a closer start; do not report the method as unreachable.");
                    } else if (r.reachable() && !r.confident()) {
                        out.put("hint", "the only route needs name-only hops (receiver types unresolved) — treat as a "
                                + "lead to confirm with callers/HindsightDebugger, not as proof of reachability.");
                    }
                }
                case "impls" -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (CodeGraph.Target t : graph.dispatchCandidatesOf(symbol)) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Map.of("candidate", t.typeName() + "#" + t.methodName(),
                                "how", t.how(),
                                "at", t.source() != null
                                        ? t.source().file() + ":" + t.source().line() : "library"));
                    }
                    out.put("candidates", rows);
                }
                case "escapes" -> {
                    var flow = ExceptionFlow.of(graph);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (var e : flow.escapesOf(symbol)) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        rows.add(Map.of("exception", e.typeName(),
                                "checked", flow.checkedness(e.type()),
                                "tier", e.tier(), "via", e.via()));
                    }
                    out.put("escapes", rows);
                    out.put("declaredThrows", flow.declaredThrows(symbol));
                }
                case "catches" -> {
                    // accept EITHER Type#method (list every catch in it - no line needed) OR File.java:line
                    var flow = ExceptionFlow.of(graph);
                    List<ExceptionFlow.CatchReport> reports;
                    if (symbol.contains("#")) {
                        reports = flow.catchesIn(symbol);
                    } else {
                        int colon = symbol.lastIndexOf(':');
                        if (colon <= 0) {
                            return Map.of("status", "Error", "message",
                                    "catches needs symbol = <Type#method> or <FileName.java>:<line>");
                        }
                        int catchLine = parseLine(symbol);
                        if (catchLine < 0) {
                            return Map.of("status", "Error", "message", "catches needs symbol = <Type#method> or "
                                    + "<FileName.java>:<line> — '" + symbol.substring(colon + 1)
                                    + "' is not a line number");
                        }
                        reports = flow.catchesAt(symbol.substring(0, colon), catchLine);
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (var r : reports) {
                        List<String> receives = new ArrayList<>();
                        for (var c : r.receives()) {
                            receives.add(c.typeName() + " [" + c.tier() + (c.partial() ? ", partial" : "")
                                    + "] from " + c.fromFile().getFileName() + ":" + c.fromLine());
                        }
                        rows.add(Map.of("at", r.file().getFileName() + ":" + r.line(),
                                "declared", r.declared(),
                                "receives", receives.isEmpty()
                                        ? List.of("NOTHING — possibly a dead catch") : receives));
                    }
                    out.put("catches", rows);
                }
                case "impact" -> {
                    // accept Type#method (slice from its first statement) OR File.java:line
                    String fileSuffix;
                    int line;
                    if (symbol.contains("#")) {
                        List<CodeGraph.MethodRef> decls = graph.locate(symbol);
                        if (decls.isEmpty()) {
                            return Map.of("status", "Error", "message", "no declaration for " + symbol);
                        }
                        CodeGraph.MethodRef m = decls.get(0);
                        fileSuffix = m.file().getFileName().toString();
                        String anchor = str(parameters.get("anchor"));
                        int anchored = anchor == null || anchor.isBlank() ? -1 : anchoredStatementLine(m, anchor);
                        if (anchor != null && !anchor.isBlank() && anchored < 0) {
                            // the planner points at a statement by its text; a silent fall-back to the first
                            // statement slices the wrong thing and looks like an answer
                            return Map.of("status", "Error", "message", "impact: no statement in " + symbol
                                    + " matches anchor '" + anchor + "' — copy the statement text exactly, or pass "
                                    + "symbol=<FileName.java>:<line>");
                        }
                        line = anchored >= 0 ? anchored : firstStatementLine(graph, m);
                        if (anchored >= 0) {
                            out.put("anchoredAt", fileSuffix + ":" + anchored);
                        }
                    } else {
                        int colon = symbol.lastIndexOf(':');
                        if (colon <= 0) {
                            return Map.of("status", "Error", "message",
                                    "impact needs symbol = <Type#method> or <FileName.java>:<line>");
                        }
                        fileSuffix = symbol.substring(0, colon);
                        line = parseLine(symbol);
                        if (line < 0) {
                            return Map.of("status", "Error", "message", "impact needs symbol = <Type#method> or "
                                    + "<FileName.java>:<line> — '" + symbol.substring(colon + 1)
                                    + "' is not a line number");
                        }
                    }
                    out.put("impact", ImpactSlice.of(graph, fileSuffix, line, 6, 60));
                }
                case "emulate" -> {
                    var report = parameters.get("steps") instanceof Number steps
                            ? Interpreter.run(graph, symbol, steps.intValue())
                            : Interpreter.run(graph, symbol);
                    out.put("verdict", report.verdict());
                    List<String> tail = report.trace().size() > 40
                            ? report.trace().subList(report.trace().size() - 40, report.trace().size())
                            : report.trace();
                    out.put("traceTail", tail);
                }
                case "verbs", "specverbs" -> {
                    // the composer's action names leak onto CodeGraph calls. Serve the catalogue here too, and
                    // say which action answers a REACH question.
                    String q = firstNonBlank(str(parameters.get("query")), symbol, str(parameters.get("target")));
                    List<String> rows = new ArrayList<>();
                    for (var v : SpecReach.verbs(graph, q)) {
                        if (rows.size() >= 60) {
                            rows.add("… more (narrow the query)");
                            break;
                        }
                        rows.add(v.render());
                    }
                    out.put("verbs", rows);
                    out.put("hint", "this is the verb CATALOGUE filtered by '" + q + "'. To learn which verbs REACH a "
                            + "production method use action=specreach with symbol=<Type#method>.");
                }
                case "tests", "testsreaching", "exercisedby", "testsof", "whichtests" -> {
                    // "which existing tests exercise this method" - the template candidates for a
                    // new test - had no action; the planner tried reach{target=TestClass#*}
                    int maxDepth = parameters.get("maxDepth") instanceof Number n ? n.intValue() : 10;
                    var r = TestReach.of(graph, symbol, maxDepth);
                    String notFound = r.notes().stream().filter(n -> n.startsWith("target not found"))
                            .findFirst().orElse(null);
                    if (notFound != null) {
                        // an unresolved target was reported as Success with an empty list - the planner read
                        // that as "nothing covers it"
                        return Map.of("status", "Error", "message", notFound + " — use action=locate to find the real "
                                + "<Type#method> first (a type alone, a typo or a test class resolves to nothing).");
                    }
                    out.put("action", "tests");
                    out.put("exercised", r.exercised());
                    // the live result was very large (every test with its full chain) - cap and compact it
                    List<Map<String, Object>> tests = new ArrayList<>();
                    int shown = 0;
                    for (var route : r.tests()) {
                        if (shown++ >= 10) {
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("test", route.testMethod());
                        row.put("score", route.score());
                        row.put("direct", route.direct());
                        row.put("hops", route.hops());
                        row.put("overlap", route.overlap());
                        row.put("shared", route.shared().size() > 6
                                ? route.shared().subList(0, 6) : route.shared());
                        List<String> path = route.path();
                        row.put("via", path.size() <= 3 ? String.join(" <- ", path)
                                : path.get(0) + " <- " + path.get(1) + " <- … (" + (path.size() - 2)
                                        + " more hops) <- " + path.get(path.size() - 1));
                        tests.add(row);
                    }
                    out.put("tests", tests);
                    if (r.tests().size() > tests.size()) {
                        out.put("moreTests", r.tests().size() - tests.size());
                    }
                    out.put("testHelpers", r.testHelpers());
                    out.put("notes", r.notes());
                    out.put("hint", r.exercised()
                            ? "direct=true tests drive the target's own class (at most two hops) and rank first; within "
                                    + "a tier 'score' weights the identifiers shared with the target's body and a test "
                                    + "class named after the target's type — tests that reach the method through many "
                                    + "hops of other types (dispatch, workflows) are not evidence they exercise this "
                                    + "path: model a new test on the top DIRECT one (TreeCodegen templateMethod=<TestClass#test>)"
                            : "no annotated test reaches this method within depth " + maxDepth
                                    + (r.testHelpers().isEmpty() ? ""
                                            : " — test-source helpers on the way: " + r.testHelpers()));
                }
                case "specreach", "spec", "spec-reach" -> {
                    int maxDepth = parameters.get("maxDepth") instanceof Number n ? n.intValue() : 10;
                    var r = SpecReach.of(graph, symbol, maxDepth);
                    String specNotFound = r.notes().stream().filter(n -> n.startsWith("target not found")).findFirst().orElse(null);
                    if (specNotFound != null) {
                        return Map.of("status", "Error", "message", specNotFound + " — use action=locate to find the "
                                + "real <Type#method> first; NO HANDLER on a target that does not exist is not an answer.");
                    }
                    out.put("specReachable", r.specReachable());
                    List<Map<String, Object>> routes = new ArrayList<>();
                    for (var route : r.routes()) {
                        List<String> verbs = new ArrayList<>();
                        for (var v : route.verbs()) {
                            verbs.add(v.render());
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("handler", route.handlerMethod());
                        row.put("kind", route.kind() == null ? "?" : route.kind());
                        row.put("path", String.join(" <- ", route.path()));
                        row.put("verbs", verbs);
                        routes.add(row);
                    }
                    out.put("routes", routes);
                    // test roots listed among the entry points were reported as "tests that exercise the path" -
                    // they only reach the method through a shared entry (call graph, flow-insensitive). The split
                    // is by SOURCE SET, done in SpecReach.
                    List<String> testRoots = r.testSupportEntryPoints();
                    out.put("entryPoints", r.entryPoints());
                    if (!testRoots.isEmpty()) {
                        out.put("testEntryPoints", testRoots);
                        out.put("testEntryPointsNote", "test sources / spec infrastructure that STATICALLY reach the "
                                + "method via a shared caller — not evidence they exercise this path; use action=tests "
                                + "for ranked candidates");
                    }
                    out.put("notes", r.notes());
                    out.put("hint", r.specReachable()
                            ? "compose a spec test from these verbs with the SpecTestComposer tool (action=compose)"
                            : "no spec verb dispatches to a handler on this method's caller chains — a spec test "
                                    + "cannot exercise it directly; cover it with a unit test");
                }
                case "flowreach" -> {
                    String target = normalizeSymbol(str(parameters.get("target")));
                    if (target == null) {
                        return Map.of("status", "Error", "message", "flowreach requires a 'target'.");
                    }
                    int maxDepth = parameters.get("maxDepth") instanceof Number n ? n.intValue() : 12;
                    var flow = EntryTypePropagation.analyze(graph, symbol);
                    CodeGraph.ReachResult r = flow.reach(symbol, target, maxDepth);
                    out.put("reachable", r.reachable());
                    out.put("path", r.path());
                    out.put("reachStatus", r.status());
                    if (r.nameOnlyHops() > 0) {
                        out.put("nameOnlyHops", r.nameOnlyHops());
                    }
                    out.put("verdict", r.describe(maxDepth));
                    if (r.undecided()) {
                        out.put("hint", "NOT REACHABLE here means DON'T KNOW (search bound hit), not 'no path' — raise "
                                + "maxDepth or ask from a closer start; do not report the method as unreachable.");
                    } else if (r.reachable() && !r.confident()) {
                        out.put("hint", "the only route needs name-only hops (receiver types unresolved) — treat as a "
                                + "lead to confirm with callers/HindsightDebugger, not as proof of reachability.");
                    }
                    out.put("analyzedMethods", flow.analyzedMethods());
                }
                default -> {
                    return Map.of("status", "Error", "message", "unknown action '" + action
                            + "' (locate/callers/callees/reach/impls/escapes/catches/impact/emulate/flowreach).");
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "CodeGraph failed: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** The line of a File.java:line address, or -1 when the part after the colon is not a number. */
    static int parseLine(String symbol) {
        try {
            return Integer.parseInt(symbol.substring(symbol.lastIndexOf(':') + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True when a symbol names a TYPE, not a method: no {@code #}, and its last dotted segment is capitalised. */
    static boolean looksLikeTypeName(String symbol) {
        if (symbol == null || symbol.contains("#") || symbol.contains(".java:") || symbol.contains("(")) {
            return false;
        }
        String last = symbol.substring(symbol.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    /**
     * Repair the two commonest symbol-shape mistakes the model makes: a {@code receiver.method}
     * qualifier where a {@code Type#method} spec is wanted (drop the lowercase receiver → simple
     * name), and {@code Type.method} written with a dot instead of {@code #}. Leaves File.java:line
     * and already-correct specs untouched.
     */
    public static String normalizeSymbol(String s) {
        if (s == null || s.contains(".java:") || s.matches("<.*>")) {
            return s;
        }
        // the server/CLI write specs as Type:method (URL-safe); the planner copies that shape. Unless the
        // part after the colon is a line number, a colon means '#'.
        int colon = s.indexOf(':');
        if (colon > 0 && colon < s.length() - 1 && !s.substring(colon + 1).matches("\\d+")) {
            s = s.substring(0, colon) + "#" + s.substring(colon + 1);
        }
        // an FQN-qualified spec (a.b.C#m) - reduce the type to its simple name (C#m), which is how the
        // graph is keyed. Also handles a.b.C.Inner#m → Inner#m.
        int hash = s.indexOf('#');
        if (hash > 0) {
            String type = s.substring(0, hash);
            String rest = s.substring(hash);
            int lastDot = type.lastIndexOf('.');
            return (lastDot >= 0 ? type.substring(lastDot + 1) : type) + rest;
        }
        int dot = s.lastIndexOf('.');
        if (dot <= 0 || dot == s.length() - 1) {
            return s;
        }
        String head = s.substring(0, dot);
        String tail = s.substring(dot + 1);
        String headSimple = head.substring(head.lastIndexOf('.') + 1);
        if (headSimple.isEmpty()) {
            return tail;
        }
        // Uppercase head → a type: Type.method → Type#method. lowercase head → a receiver var: drop it.
        return Character.isUpperCase(headSimple.charAt(0)) ? headSimple + "#" + tail : tail;
    }

    /** The line of the first statement inside {@code m} whose text contains {@code anchor} (whitespace-insensitive,
     *  trailing ';' ignored), or -1. Lets the planner address a statement by its text instead of a line number. */
    static int anchoredStatementLine(CodeGraph.MethodRef m, String anchor) {
        String want = anchor.replaceAll("\\s+", "").replaceAll(";+$", "");
        if (want.isEmpty()) {
            return -1;
        }
        int[] best = {-1, Integer.MAX_VALUE};
        m.decl().walk(n -> {
            if (!n.type().isStatement() || n.firstCodeToken() == null) {
                return;
            }
            String text = n.toString().replaceAll("\\s+", "");
            if (text.contains(want) && text.length() < best[1]) {   // the SMALLEST statement containing it
                best[0] = n.firstCodeToken().line();
                best[1] = text.length();
            }
        });
        return best[0];
    }

    private static int firstStatementLine(CodeGraph graph, CodeGraph.MethodRef m) {
        var tree = graph.treeOf(m.file());
        if (tree != null) {
            var body = m.decl().child(NodeType.BLOCK);
            if (body != null) {
                for (var c : body.children()) {
                    if (c.type().isStatement() && c.firstCodeToken() != null) {
                        return c.firstCodeToken().line();
                    }
                }
            }
        }
        return m.line();
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
