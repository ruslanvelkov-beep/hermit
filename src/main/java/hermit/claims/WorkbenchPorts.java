package hermit.claims;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.TestReach;
import hermit.evidence.RecordedExecutionReader;
import hermit.tool.generate.TreeGenerationTool;
import hermit.tool.graph.CodeGraphTool;
import hermit.tool.record.ExecutionRecorderTool;
import hermit.workspace.GradleModules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The claim inspector's ports bound to the real tools: CodeGraph for static facts, Hindsight for recordings,
 * TreeCodegen in a scratch git worktree for tests that do not exist yet. The workbench itself is never written:
 * a test the inspector writes lives in the worktree ({@code -Dhermit.claims.worktree}, default a temp directory),
 * which is never committed.
 */
public final class WorkbenchPorts {

    private WorkbenchPorts() {}

    static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    /** CodeGraph over the workbench. */
    public static ClaimInspector.Code code(Path projectDir, String classesDir) {
        return new ClaimInspector.Code() {
            private CodeGraph graph() {
                try {
                    return CodeGraphTool.graphFor(projectDir.toString(), classesDir);
                } catch (Exception e) {
                    throw new IllegalStateException("CodeGraph unavailable: " + e.getMessage(), e);
                }
            }

            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                List<ClaimInspector.Located> out = new ArrayList<>();
                for (CodeGraph.MethodRef r : graph().locate(ClaimInspector.normalize(spec))) {
                    out.add(new ClaimInspector.Located(r.spec(), r.file(), r.line()));
                }
                return out;
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                CodeGraph.ReachResult r = graph().reach(ClaimInspector.normalize(from), ClaimInspector.normalize(to), 12);
                return new ClaimInspector.Reach(r.reachable(), r.path(), r.status());
            }

            @Override
            public List<String> tests(String target) {
                List<String> out = new ArrayList<>();
                try {
                    for (TestReach.Route route : TestReach.of(graph(), ClaimInspector.normalize(target), 6, 20_000).tests()) {
                        out.add(route.testMethod());
                    }
                } catch (RuntimeException e) {
                    // an unresolvable target has no tests
                }
                return out;
            }

            @Override
            public Path root() {
                return projectDir;
            }

            private Map<String, ClaimInspector.Located> protos;

            @Override
            public String definitionOf(String name) {
                if (protos == null) {
                    protos = protoIndex(projectDir);
                }
                ClaimInspector.Located at = protos.get(name);
                return at == null ? "" : ClaimInspector.source(at, 80);
            }

            @Override
            public Set<String> typeNames() {
                return graph().typeNames(f -> !ClaimInspector.isTestCode(f) && !ClaimInspector.isGenerated(f));
            }

            @Override
            public List<String> methodsOf(String type) {
                Set<String> out = new LinkedHashSet<>();
                CodeGraph g = graph();
                for (var entry : g.hierarchy().entries(type)) {
                    g.hierarchy().visibleMethods(entry).values().stream()
                            .filter(d -> d.reflect() == null)   // declared in source, not a library method
                            .forEach(d -> out.add(d.owner().name() + "#" + d.name()));
                }
                return new ArrayList<>(out);
            }
        };
    }

    /** Hindsight recordings of JUnit tests; a test in the scratch worktree is run there, any other in the workbench. */
    public static ClaimInspector.Runtime runtime(Path projectDir, Path worktree, String hindsightDir) {
        Path reader = Path.of(hindsightDir, "reader", "build", "install", "reader", "bin", "reader");
        return new ClaimInspector.Runtime() {
            @Override
            public String record(ClaimInspector.Located test, List<ClaimInspector.Located> targets) throws Exception {
                String fqn = fqnOf(test);
                String method = test.spec().contains("#") ? test.spec().substring(test.spec().indexOf('#') + 1) : null;
                Path runIn = worktree != null && test.file().toAbsolutePath().startsWith(worktree.toAbsolutePath())
                        ? worktree : projectDir;
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("testClass", fqn);
                if (method != null) {
                    params.put("testMethod", method);
                }
                params.put("projectDir", runIn.toString());
                params.put("hindsightDir", hindsightDir);
                // the module and the test are known from the located file: no second call graph is built to find them
                String module = GradleModules.projectFor(runIn, test.file());
                if (module != null) {
                    params.put("module", module);
                }
                params.put("testLocated", "true");
                Set<String> include = new LinkedHashSet<>(packagesOf(targets));
                include.add(packageOf(test.file()) + ".");
                params.put("include", String.join("|", include));
                Map<String, Object> r = new ExecutionRecorderTool().execute(params);
                if (!"Success".equals(String.valueOf(r.get("status")))) {
                    throw new IllegalStateException(String.valueOf(r.getOrDefault("message", r.get("status"))));
                }
                return String.valueOf(r.get("recordingDir"));
            }

            @Override
            public RecordedExecutionReader query(String recordingDir) {
                return new RecordedExecutionReader(reader, Path.of(recordingDir));
            }
        };
    }

    /** TreeCodegen in the scratch worktree (created on first use with `git worktree add --detach`). */
    public static ClaimInspector.TestWriter writer(Path projectDir, Path worktree) {
        return (claim, nearest, scenario) -> {
            ensureWorktree(projectDir, worktree);
            Path testFile = nearest != null ? inWorktree(projectDir, worktree, nearest.file()) : testFileFor(claim, worktree);
            if (testFile == null || !Files.isRegularFile(testFile)) {
                throw new IllegalStateException("no test class to add a test to for " + claim.id);
            }
            String testClass = testFile.getFileName().toString().replace(".java", "");
            String name = "claim" + claim.id + "_" + Integer.toHexString(Math.abs(claim.text.hashCode()));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("task", scenario);
            params.put("file", testFile.toString());
            params.put("projectDir", worktree.toString());
            params.put("method", testClass + "#" + name);
            List<String> targets = ClaimInspector.specs(claim.hypothesis.get("targets"));
            if (!targets.isEmpty()) {
                params.put("targetReach", targets.get(targets.size() - 1));
            }
            Map<String, Object> r = new TreeGenerationTool().execute(params);
            if (!"Success".equals(String.valueOf(r.get("status"))) || r.get("testOutcome") == null) {
                throw new IllegalStateException("TreeCodegen: " + r.getOrDefault("message", r.get("status")));
            }
            int line = lineOf(testFile, name);
            return line <= 0 ? null : new ClaimInspector.Located(testClass + "#" + name, testFile, line);
        };
    }

    private static final Pattern PROTO_TYPE = Pattern.compile("^\\s*(?:message|enum)\\s+(\\w+)\\s*\\{");

    /** Where each protobuf message and enum of the repository is declared (build output left out). */
    static Map<String, ClaimInspector.Located> protoIndex(Path root) {
        Map<String, ClaimInspector.Located> out = new LinkedHashMap<>();
        try (var walk = Files.walk(root, 20)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".proto") && !p.toString().contains("/build/")
                    && !p.toString().contains("/node_modules/")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = PROTO_TYPE.matcher(lines.get(i));
                    if (m.find()) {
                        out.putIfAbsent(m.group(1), new ClaimInspector.Located(m.group(1), f, i + 1));
                    }
                }
            }
        } catch (IOException e) {
            // no protobuf definitions
        }
        return out;
    }

    static synchronized void ensureWorktree(Path projectDir, Path worktree) throws IOException, InterruptedException {
        if (Files.isDirectory(worktree.resolve(".git")) || Files.isRegularFile(worktree.resolve(".git"))) {
            return;
        }
        Process p = new ProcessBuilder("git", "-C", projectDir.toString(), "worktree", "add", "--detach", worktree.toString(), "HEAD")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("git worktree add failed: " + out);
        }
    }

    static Path inWorktree(Path projectDir, Path worktree, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = projectDir.toAbsolutePath().normalize();
        return abs.startsWith(root) ? worktree.resolve(root.relativize(abs)) : abs;
    }

    /** The test class named after the claim's last target type (TypeTest.java) under the worktree, or null. */
    static Path testFileFor(Claim claim, Path worktree) throws IOException {
        List<String> targets = ClaimInspector.specs(claim.hypothesis.get("targets"));
        if (targets.isEmpty()) {
            return null;
        }
        String type = targets.get(targets.size() - 1).replaceAll("#.*", "");
        String name = type + "Test.java";
        try (var walk = Files.walk(worktree, 25)) {
            return walk.filter(f -> f.getFileName().toString().equals(name) && f.toString().contains("/src/test/"))
                    .findFirst().orElse(null);
        }
    }

    static int lineOf(Path file, String method) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches(".*\\b" + Pattern.quote(method) + "\\s*\\(.*")) {
                    return i + 1;
                }
            }
        } catch (IOException ignored) {
            // not found
        }
        return 0;
    }

    static String packageOf(Path file) {
        try {
            Matcher m = PACKAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static String fqnOf(ClaimInspector.Located test) {
        String cls = test.spec().contains("#") ? test.spec().substring(0, test.spec().indexOf('#')) : test.spec();
        String outer = test.file().getFileName().toString().replaceAll("\\.java$", "");
        if (!outer.equals(cls)) {
            cls = outer + "$" + cls;   // a nested test class: Gradle's filter wants Outer$Inner
        }
        String pkg = packageOf(test.file());
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    /** The packages of the claim's located targets (for Hindsight's include). */
    public static List<String> packagesOf(List<ClaimInspector.Located> located) {
        Set<String> out = new LinkedHashSet<>();
        for (ClaimInspector.Located l : located) {
            String p = packageOf(l.file());
            if (!p.isEmpty()) {
                out.add(p + ".");
            }
        }
        return new ArrayList<>(out);
    }
}
