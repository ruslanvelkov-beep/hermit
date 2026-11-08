package hermit.tool.generate;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.SpecConventions;
import hermit.analysis.graph.SpecReach;
import hermit.generate.SpecTestComposer;
import hermit.tool.api.Tool;
import hermit.tool.graph.CodeGraphTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planner-facing spec-test composer: {@code reach} (which spec verbs reach a production method),
 * {@code verbs} (the verb catalogue) and {@code compose} (write a validated spec test into a suite,
 * refusing vacuous tests). Backed by {@link SpecReach}/{@link SpecTestComposer} over the cached CodeGraph; the
 * test's annotation, runner and helpers are the project's {@link SpecConventions}.
 */
public class SpecTestComposerTool implements Tool {

    @Override
    public String getDocumentation() {
        SpecConventions conventions = SpecConventions.load();
        return "SpecTestComposer: compose an END-TO-END spec test (@" + conventions.testAnnotation() + ") from the "
                + "project's spec verbs (the " + String.join("/", conventions.verbClasses()) + " static factories) "
                + "directly in the parse tree. "
                + "Actions: {\"action\": \"reach\", \"target\": \"<Type#method>\"} — which verbs reach that "
                + "production method (reverse callers up to the dispatching handler, joined with the dispatch "
                + "table); {\"action\": \"verbs\", \"query\": \"<functionality|op class|verb fragment>\"} — the "
                + "verb catalogue with signatures; {\"action\": \"compose\", \"suiteFile\": \"<existing suite "
                + ".java>\" OR \"newSuite\": {\"dir\": \"<source root>\", \"package\": \"<pkg>\", \"className\": "
                + "\"<Name>\"}, \"testName\": \"<methodName>\", \"ops\": [\"<spec op expression>\", ...], "
                + "\"declare\": [{\"name\": \"<local>\", \"expr\": \"<Java expression>\"}] (optional locals emitted "
                + "before " + conventions.runner() + "(...), e.g. an expected digest of the bytes the test sends), "
                + "\"target\": \"<Type#method the test must exercise>\" (recommended), \"projectDir\", "
                + "\"classesDir\" (optional)} — inserts the validated @" + conventions.testAnnotation()
                + " member with the static imports "
                + "it needs and reports which verb reaches the target; a test whose verbs reach NO handler on "
                + "the target's call chain is REJECTED as vacuous and nothing is written.";
    }

    /**
     * The ops arrived as quoted strings - {@code "itemGetInfo(42).hasName(x)"} - and were pasted into the method as
     * string literals, so no verb was seen and the composition was judged vacuous. An op wrapped in quotes is the op.
     */
    /**
     * Three plans in a row, two of them authored by the strongest model, wrote the ids as {@code '42'} and were
     * refused for the single quotes each time. A single-quoted literal in an op that has no double quotes is a Java
     * string written the Python way - rewrite it instead of bouncing the plan.
     */
    static String doubleQuoted(String op) {
        if (op == null || op.indexOf('\'') < 0 || op.indexOf('"') >= 0) {
            return op;
        }
        return op.replaceAll("'([^'\\n]*)'", "\"$1\"");
    }

    static String unquoted(String op) {
        String o = op == null ? "" : op.strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            if (o.endsWith(",")) {
                o = o.substring(0, o.length() - 1).strip();
                changed = true;
            }
            if (o.length() >= 2 && ((o.startsWith("\"") && o.endsWith("\"")) || (o.startsWith("'") && o.endsWith("'")))) {
                o = o.substring(1, o.length() - 1).strip();
                changed = true;
            }
        }
        // the ops carried JSON-escaped quotes (`\"0xdeadbeef\"`) into the method - "illegal
        // character '\\'"; an escaped quote inside an op is a quote
        if (o.contains("\\\"")) {
            o = o.replace("\\\"", "\"");
        }
        return o;
    }

    /**
     * The composition reached the target and only "unknown variable 'expectedDigest'" remained - the value a spec
     * asserts must be a local, and the planner never used the `declare` field. Unknown variables get a hint naming the
     * field and the names to declare.
     */
    static List<String> withDeclareHint(List<String> problems, List<String> declares) {
        if (problems == null || problems.isEmpty()) {
            return problems;
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String p : problems) {
            Matcher m = Pattern.compile("unknown variable '(\\w+)'").matcher(p);
            while (m.find()) {
                missing.add(m.group(1));
            }
        }
        if (missing.isEmpty()) {
            return problems;
        }
        List<String> out = new ArrayList<>(problems);
        StringBuilder hint = new StringBuilder("DECLARE the values the ops use as locals with the `declare` field: \"declare\": [");
        boolean first = true;
        for (String name : missing) {
            hint.append(first ? "" : ", ").append("{\"name\": \"").append(name).append("\", \"expr\": \"<Java expression>\"}");
            first = false;
        }
        // the old example (a digest service's method) was copied as a `digests` VARIABLE - a handler
        // field that does not exist in a spec. The example is now the suites' own idiom, and every helper in it is
        // auto-imported by the composer.
        String digest = SpecConventions.load().digestHelper();
        hint.append("] — e.g. {\"name\": \"payload\", \"expr\": \"\\\"sample\\\".getBytes()\"}, "
                + "{\"name\": \"expectedDigest\", \"expr\": \"hex(" + digest + "(payload))\"} (hex and " + digest
                + " are imported for you; a handler field such as `digests` does not exist in a spec)"
                + (declares == null || declares.isEmpty() ? "" : "; already declared: " + declares));
        out.add(hint.toString());
        return out;
    }

    /** The package of a Java file path below a {@code src/<set>/java} root, or null when there is no such root. */
    static String packageFromPath(Path file) {
        Path dir = file.getParent();
        if (dir == null) {
            return null;
        }
        List<String> segs = new ArrayList<>();
        for (Path p = dir; p != null; p = p.getParent()) {
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.equals("java") && p.getParent() != null && p.getParent().getParent() != null
                    && p.getParent().getParent().getFileName() != null
                    && p.getParent().getParent().getFileName().toString().equals("src")) {
                Collections.reverse(segs);
                return String.join(".", segs);
            }
            segs.add(name);
        }
        return null;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String action = str(parameters.get("action"));
        String projectDir = firstNonBlank(str(parameters.get("projectDir")),
                System.getProperty("hermit.projectDir"));
        String classesDir = firstNonBlank(str(parameters.get("classesDir")),
                System.getProperty("hermit.classesDir"));
        if (action == null) {
            // the planner had every compose parameter but no action - infer it
            if (parameters.get("ops") != null || str(parameters.get("testName")) != null) {
                action = "compose";
            } else if (str(parameters.get("target")) != null) {
                action = "reach";
            } else if (str(parameters.get("query")) != null) {
                action = "verbs";
            } else {
                return Map.of("status", "Error", "message", "action is required: reach | verbs | compose");
            }
        }
        if (projectDir == null || !Files.isDirectory(Path.of(projectDir))) {
            return Map.of("status", "Error", "message",
                    "projectDir not found (pass projectDir or set -Dhermit.projectDir): " + projectDir);
        }
        try {
            CodeGraph graph = CodeGraphTool.graphFor(projectDir, classesDir);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "Success");
            out.put("action", action);
            switch (action) {
                case "reach", "specreach" -> {
                    String target = CodeGraphTool.normalizeSymbol(
                            firstNonBlank(str(parameters.get("target")), str(parameters.get("symbol"))));
                    if (target == null) {
                        return Map.of("status", "Error", "message", "reach requires 'target' (Type#method).");
                    }
                    int depth = parameters.get("maxDepth") instanceof Number n ? n.intValue() : 10;
                    SpecReach.Result r = SpecReach.of(graph, target, depth);
                    out.put("target", target);
                    out.put("specReachable", r.specReachable());
                    out.put("report", r.render());
                    List<String> verbs = new ArrayList<>();
                    for (var route : r.routes()) {
                        for (var v : route.verbs()) {
                            verbs.add(v.name() + v.signature());
                        }
                    }
                    out.put("verbs", verbs);
                    out.put("entryPoints", r.entryPoints());
                    if (!r.testSupportEntryPoints().isEmpty()) {
                        out.put("testSupportEntryPoints", r.testSupportEntryPoints());
                    }
                }
                case "verbs" -> {
                    String q = str(parameters.get("query"));
                    List<String> rows = new ArrayList<>();
                    for (SpecReach.Verb v : SpecReach.verbs(graph, q)) {
                        if (rows.size() >= 80) {
                            rows.add("… more (narrow the query)");
                            break;
                        }
                        rows.add(v.render());
                    }
                    out.put("query", q == null ? "" : q);
                    out.put("verbs", rows);
                }
                case "compose" -> {
                    return compose(parameters, graph, projectDir, classesDir, out);
                }
                default -> {
                    return Map.of("status", "Error", "message", "unknown action '" + action
                            + "' — one of reach (which verbs reach a Type#method; alias specreach) | verbs (the "
                            + "catalogue) | compose (write the validated spec test)");
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compose(Map<String, Object> p, CodeGraph graph, String projectDir,
            String classesDir, Map<String, Object> out) throws Exception {
        String testName = str(p.get("testName"));
        List<String> ops = new ArrayList<>();
        Object rawOps = p.get("ops");
        if (rawOps instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    ops.add(doubleQuoted(unquoted(String.valueOf(o))));
                }
            }
        } else if (rawOps instanceof String s && !s.isBlank()) {
            ops.add(doubleQuoted(unquoted(s)));
        }
        String target = CodeGraphTool.normalizeSymbol(str(p.get("target")));
        Path suite;
        String source;
        boolean fresh = false;
        String suiteFile = str(p.get("suiteFile"));
        if (suiteFile != null && !suiteFile.isBlank()) {
            suite = Path.of(suiteFile);
            if (!suite.isAbsolute()) {
                suite = Path.of(projectDir).resolve(suiteFile);
            }
            suite = suite.toAbsolutePath().normalize();
            if (!Files.isRegularFile(suite)) {
                // a suiteFile that does not exist yet under a java source root IS a new suite -
                // its package is the path below the source root; refusing it sent the planner round in circles
                String derivedPkg = packageFromPath(suite);
                if (derivedPkg == null) {
                    return Map.of("status", "Error", "message", "suiteFile not found: " + suite
                            + " — to create a NEW suite use newSuite{dir,package,className}");
                }
                String cls = suite.getFileName().toString().replaceFirst("\\.java$", "");
                source = SpecTestComposer.newSuiteSource(derivedPkg, cls);
                fresh = true;
                out.put("note", "suiteFile did not exist — created as a new suite in package " + derivedPkg);
            } else {
                source = Files.readString(suite);
            }
        } else if (p.get("newSuite") instanceof Map<?, ?> ns) {
            String dir = str(ns.get("dir"));
            String pkg = str(ns.get("package"));
            String cls = str(ns.get("className"));
            if (dir == null || cls == null) {
                return Map.of("status", "Error", "message", "newSuite needs dir and className (package optional).");
            }
            Path root = SpecTestComposer.resolveOverlapping(Path.of(projectDir).toAbsolutePath().normalize(), dir);
            suite = SpecTestComposer.suitePath(root.toAbsolutePath().normalize(), pkg, cls);
            // a suite path outside the project's source tree (…/app/app/…) was
            // accepted, "validated" against nothing and written - the first composition ever reported Success with an
            // undeclared variable and a three-argument itemCreate in it. A suite must live under the project's
            // src/main/java or src/test/java of a module.
            String outside = outsideSourceTree(root, Path.of(projectDir).toAbsolutePath().normalize());
            if (outside != null) {
                return Map.of("status", "Error", "message", outside);
            }
            source = Files.isRegularFile(suite) ? Files.readString(suite)
                    : SpecTestComposer.newSuiteSource(pkg, cls);
            fresh = !Files.isRegularFile(suite);
        } else {
            return Map.of("status", "Error", "message", "compose needs suiteFile or newSuite{dir,package,className}.");
        }
        List<String> declares = new ArrayList<>();
        Object rawDeclare = p.get("declare");
        if (rawDeclare instanceof List<?> dl) {
            for (Object d : dl) {
                if (d instanceof Map<?, ?> dm && dm.get("name") != null && dm.get("expr") != null) {
                    declares.add(dm.get("name") + " = " + doubleQuoted(unquoted(String.valueOf(dm.get("expr")))));
                } else if (d != null && String.valueOf(d).contains("=")) {
                    declares.add(unquoted(String.valueOf(d)));
                }
            }
        } else if (rawDeclare instanceof String ds && ds.contains("=")) {
            declares.add(unquoted(ds));
        }
        SpecTestComposer.Composition c = SpecTestComposer.compose(graph, CodeGraphTool.indexFor(classesDir),
                source, testName, ops, target, declares);
        out.put("suiteFile", suite.toString());
        out.put("testName", testName);
        out.put("methodText", c.methodText());
        out.put("verbDispatch", c.verbDispatch());
        out.put("reaching", c.reaching());
        out.put("unreached", c.unreached());
        out.put("importsAdded", c.imports());
        if (!c.ok()) {
            out.put("status", "Error");
            out.put("message", "composition rejected — nothing was written; fix the operations and retry");
            out.put("problems", withDeclareHint(c.problems(), declares));
            return out;
        }
        Files.createDirectories(suite.getParent());
        Files.writeString(suite, c.rendered());
        CodeGraphTool.invalidate(suite);
        out.put("written", true);
        out.put("newFile", fresh);
        out.put("next", "run it for real: "
                + String.format(SpecConventions.load().runCommand(), fqnOf(c.rendered(), suite) + "." + testName));
        return out;
    }

    /**
     * What is provably wrong with a compose ACTION before it runs - the shape and soundness checks the composer would
     * refuse it for, so the planner is told without spending the iteration. Null when nothing is wrong.
     */
    public static String preflight(Map<String, Object> p) {
        if (p == null || !"compose".equals(String.valueOf(p.getOrDefault("action", "compose")))) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        if (p.get("suiteFile") == null && !(p.get("newSuite") instanceof Map)) {
            problems.add("compose needs suiteFile or newSuite{dir,package,className}");
        }
        List<String> ops = new ArrayList<>();
        Object rawOps = p.get("ops");
        if (rawOps instanceof List<?> l) {
            for (Object o : l) {
                ops.add(doubleQuoted(unquoted(String.valueOf(o))));
            }
        } else if (rawOps instanceof String s && !s.isBlank()) {
            ops.add(doubleQuoted(unquoted(s)));
        }
        if (ops.isEmpty()) {
            problems.add("compose needs ops (the spec operations, positional Java expressions)");
        }
        Set<String> declared = new LinkedHashSet<>();
        Object rawDeclare = p.get("declare");
        if (rawDeclare instanceof List<?> dl) {
            for (Object d : dl) {
                if (d instanceof Map<?, ?> dm && dm.get("name") != null) {
                    declared.add(String.valueOf(dm.get("name")));
                    String expr = String.valueOf(dm.get("expr"));
                    if (expr.matches("(?s).*<[^<>\\n]{1,80}>.*") || expr.matches("(?s).*\\.\\.\\..*")) {
                        problems.add("declare '" + dm.get("name") + "' holds a placeholder (" + expr.strip()
                                + ") — give a real Java expression, e.g. hex(" + SpecConventions.load().digestHelper()
                                + "(payload))");
                    }
                }
            }
        }
        for (String op : ops) {
            if (op.matches("(?s).*<[^<>\\n]{1,80}>.*") || op.contains("...")) {
                problems.add("op «" + op + "» holds a placeholder — every argument must be a real Java expression");
            }
            Matcher m = Pattern.compile("^\\s*([a-z]\\w*)\\s*\\(\\s*(\\d+L?)\\s*[,)]").matcher(op);
            if (m.find()) {
                problems.add("op «" + op + "» passes the number " + m.group(2)
                        + " where spec verbs take a String id — write \"" + SpecConventions.load().idPrefix()
                        + m.group(2).replaceAll("L$", "") + "\"");
            }
            Matcher bare = Pattern.compile("(?<![\\w.\"])([a-z][A-Za-z0-9_]*)(?![\\w\"(])").matcher(op.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\""));
            while (bare.find()) {
                String name = bare.group(1);
                if (!declared.contains(name) && !name.matches("true|false|null|new|final|var") && !SpecTestComposer.isSpecHelper(name)) {
                    problems.add("op «" + op + "» uses '" + name + "' which no declare defines — add {\"name\": \"" + name + "\", \"expr\": \"...\"} to declare");
                }
            }
        }
        return problems.isEmpty() ? null : "this compose would be refused: " + String.join("; ", problems);
    }

    /** null when the resolved source root is an existing directory under the project - the doubled path
     *  (…/app/app/…) of one run existed nowhere, so nothing could validate the composition. */
    static String outsideSourceTree(Path root, Path project) {
        if (Files.isDirectory(root) && root.toAbsolutePath().normalize().startsWith(project)) {
            return null;
        }
        return "newSuite.dir resolves to " + root + ", which is not an existing directory under the project " + project
                + " — give it as the module's source root relative to the project (e.g. "
                + SpecConventions.load().testSources() + ") and the package separately";
    }

    private static String fqnOf(String source, Path file) {
        String cls = file.getFileName().toString().replaceFirst("\\.java$", "");
        Matcher m = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(source);
        return m.find() ? m.group(1) + "." + cls : cls;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
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
