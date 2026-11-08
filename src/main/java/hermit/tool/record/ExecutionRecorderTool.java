package hermit.tool.record;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.tree.NodeType;
import hermit.tool.api.Tool;
import hermit.tool.graph.CodeGraphTool;
import hermit.workspace.GradleModules;
import hermit.workspace.RemoteExec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real Hindsight Debugger integration: records a Java program under the
 * Hindsight -javaagent (which logs every state-changing event to disk) and
 * returns the reader CLI's verification and statistics output. v1 records the
 * debugger's bundled sample fixture (BankSim) via its maintained script;
 * recording arbitrary programs (mainClass/classpath) is a future extension.
 */
public class ExecutionRecorderTool implements Tool {

    // the recorder's folder: the hindsightDir parameter, else -Dhermit.hindsight.dir; there is no default
    private static final String DEFAULT_HINDSIGHT_DIR = System.getProperty("hermit.hindsight.dir", "");
    private static final int TIMEOUT_MINUTES = 10;
    private static final int MAX_STATS_CHARS = 2000;

    @Override
    public String getDocumentation() {
        return "HindsightDebugger: real dynamic analysis — records a program's execution (every method "
                + "call, variable write, branch and thrown exception) with the Hindsight JVM agent and returns "
                + "the trace. Use it to PROVE at runtime which code paths actually run and where an exception "
                + "is thrown/propagates. Two modes:\n"
                + "  • Record a REAL project test (the usual case): {\"testClass\": \"<fully.qualified.TestClass>\", "
                + "\"testMethod\": \"<method>\" (optional), \"projectDir\": \"<repo root>\", \"module\": \":app\" "
                + "(optional), \"trigger\": \"<ClassUnderTest#method>\" (optional — start capture here, e.g. "
                + "\"Playlist#nextTrack\"), \"include\": \"<pkg1.|pkg2.>\" (optional — packages to "
                + "instrument)}. Runs that JUnit test under the agent and returns the trace (method calls + throw "
                + "events, so you can see an exception escaping a method).\n"
                + "  • {\"targetProgram\": \"sample\"} records the bundled BankSim sample.\n"
                + "Add \"remote\": \"user@host:/repo/path\" ONLY when told to record on a remote machine over "
                + "SSH (the Hindsight artifacts + repo are then on THAT host; the recording runs THERE).\n"
                + "Returns the recording directory, the reader stats, and (for a test) the throw/return events.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String hindsightDir = (String) parameters.getOrDefault("hindsightDir", DEFAULT_HINDSIGHT_DIR);
        String targetProgram = (String) parameters.get("targetProgram");
        // Record a REAL Gradle test under the agent when a test class is named (or targetProgram=gradleTest).
        if ((targetProgram != null && targetProgram.equalsIgnoreCase("gradleTest"))
                || parameters.get("testClass") != null) {
            return recordGradleTest(parameters, hindsightDir);
        }
        if (targetProgram == null || targetProgram.isEmpty()) {
            return Map.of("status", "Error",
                    "message", "Provide testClass (record a project test) or targetProgram=sample.");
        }
        Path script = Paths.get(hindsightDir, "scripts", "record-sample.sh");
        if (!Files.isRegularFile(script)) {
            return Map.of("status", "Error", "message", "record script not found: " + script
                    + " (the recorder's folder: hindsightDir or -Dhermit.hindsight.dir)");
        }

        System.out.println("[Hindsight] Recording '" + targetProgram + "' under the agent...");
        try {
            // Absolute path (not bare "bash" on PATH) to avoid executing an untrusted binary earlier in PATH (CodeQL java/relative-path-command).
            Process process = new ProcessBuilder("/bin/bash", script.toString())
                    .directory(Paths.get(hindsightDir).toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return Map.of("status", "Error", "message", "recording timed out after " + TIMEOUT_MINUTES + " min");
            }
            String out = output.toString();
            if (process.exitValue() != 0) {
                String tail = out.length() > 1500 ? out.substring(out.length() - 1500) : out;
                return Map.of("status", "Error", "message", "recording failed (exit "
                        + process.exitValue() + "): " + tail);
            }

            String recordingDir = "";
            for (String line : out.split("\n")) {
                if (line.startsWith("Recording ready: ")) {
                    recordingDir = line.substring("Recording ready: ".length()).trim();
                }
            }
            int statsIdx = out.indexOf("==> stats");
            String stats = statsIdx >= 0 ? out.substring(statsIdx) : out;
            if (stats.length() > MAX_STATS_CHARS) {
                stats = stats.substring(0, MAX_STATS_CHARS) + "\n(...truncated)";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "Success");
            result.put("recordingDir", recordingDir);
            result.put("stats", stats);
            return result;

        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Hindsight execution failed: " + e.getMessage());
        }
    }

    private static final int TEST_TIMEOUT_MINUTES = 25;   // a test under the agent + cleanTest is slow
    private static final int MAX_DUMP_CHARS = 6000;

    /**
     * Record a REAL project JUnit test under the Hindsight -javaagent (wired into the Gradle Test JVM via
     * a generated init script), then read back the trace. This is how the agent proves, at runtime, that a
     * method throws and the exception propagates out of a caller - tracing the exact path prior static
     * (CodeQL) analysis identified.
     */
    private Map<String, Object> recordGradleTest(Map<String, Object> p, String hindsightDir) {
        String testClass = str(p.get("testClass"));
        if (testClass == null || testClass.isBlank()) {
            return Map.of("status", "Error", "message", "testClass (fully-qualified) is required.");
        }
        String projectDir = firstNonBlank(str(p.get("projectDir")), System.getProperty("hermit.projectDir"));
        RemoteExec.Spec spec = RemoteExec.spec(p, projectDir);
        boolean remote = RemoteExec.isRemote(spec);
        // Under remote exec the repo + Hindsight artifacts live on the other host - validate only when local.
        if (!remote && (projectDir == null || !Files.isDirectory(Paths.get(projectDir)))) {
            return Map.of("status", "Error", "message", "projectDir not found (pass projectDir or set "
                    + "-Dhermit.projectDir): " + projectDir);
        }
        String module = firstNonBlank(str(p.get("module")),
                remote ? null : moduleOfTestClass(projectDir, testClass), ":app");
        String testMethod = str(p.get("testMethod"));
        String testFqn = (testMethod == null || testMethod.isBlank()) ? testClass : testClass + "." + testMethod;
        if (!remote && !Boolean.parseBoolean(str(p.get("testLocated")))) {   // the claim inspector located it already
            // A hallucinated test class/method costs a full cleanTest+test cycle under the agent (minutes) and
            // yields a 0-record recording (a fabricated `com.example.…` class seen in an earlier run). Check the
            // class - and the method when given - exist in the parse tree first; hand back the real @Test
            // methods to choose from.
            String missing = missingTestMethod(projectDir, testClass, testMethod);
            if (missing != null) {
                return Map.of("status", "Error", "message", missing);
            }
        }
        String trigger = str(p.get("trigger"));
        // Default the instrument scope to the test class's own package so capture is reliable even if
        // the caller omits include.
        String include = firstNonBlank(str(p.get("include")), packageOf(testClass));
        if (!remote) {
            // The planner copies the guidance's EXAMPLE package into `include` (a file-service package for a
            // workflow test) - the recording would then contain nothing of the class under test. Make the
            // scope cover the test class's own package and the trigger class's package as well.
            include = widenInclude(include, projectDir, testClass, trigger);
        }

        Path agent = Paths.get(hindsightDir, "agent/build/libs/hindsight-agent-0.0.1-SNAPSHOT.jar");
        Path hooks = Paths.get(hindsightDir, "hooks/build/libs/hooks-0.0.1-SNAPSHOT.jar");
        Path reader = Paths.get(hindsightDir, "reader/build/install/reader/bin/reader");
        for (Path req : new Path[] {agent, hooks, reader}) {
            if (!RemoteExec.exists(spec, req.toString())) {
                return Map.of("status", "Error", "message", "Hindsight artifact missing: "
                        + (remote ? spec.host() + ":" : "") + req
                        + " (build the Hindsight agent/hooks/reader first; the recorder's folder: hindsightDir or "
                        + "-Dhermit.hindsight.dir).");
            }
        }

        try {
            // The recording dir + init-script live on the host where the test runs (remote or local).
            String outDir = remote ? RemoteExec.mkdtemp(spec, "hindsight-rec-")
                    : Files.createTempDirectory("hindsight-rec-").toString();
            // agent args: a single string after the '='; comma-separated key=value pairs.
            StringBuilder agentArgs = new StringBuilder("hooks=").append(hooks)
                    .append(",outDir=").append(outDir);
            agentArgs.append(",include=").append(include.trim());
            if (trigger != null && !trigger.isBlank()) {
                // The agent matches a trigger's CLASS against the fully-qualified `include` prefixes; a simple
                // name like "Playlist#m" never fires ("matches no include prefix"). Auto-qualify an
                // unqualified trigger class with the first include package so a simple ClassName#method works.
                agentArgs.append(",trigger=").append(qualifyTrigger(trigger.trim(), include));
            }
            // snapshot the recorded packages and java.time
            agentArgs.append(",snapshotOpen=").append(include.trim()).append("|java.time.")
                    .append(",maxString=512,snapshotDepth=3,debug=false");

            // Gradle init script: attach the -javaagent to every Test task JVM. It MUST go through
            // jvmArgumentProviders - a plain jvmArgs(...) append is CLOBBERED by this build's own
            // module-heavy test config (it assigns jvmArgs), which silently drops the agent (0 records).
            String initScript = "allprojects {\n"
                    + "  tasks.withType(Test).configureEach {\n"
                    + "    jvmArgumentProviders.add({ [\"-javaagent:" + agent + "=" + agentArgs
                    + "\"] } as org.gradle.process.CommandLineArgumentProvider)\n"
                    + "  }\n"
                    + "}\n";
            String initPath;
            if (remote) {
                initPath = outDir + "/hindsight-init.gradle";
                RemoteExec.writeText(spec, initScript, initPath);
            } else {
                Path it = Files.createTempFile("hindsight-init-", ".gradle");
                Files.writeString(it, initScript);
                initPath = it.toString();
            }

            String cmd = "./gradlew " + module + ":cleanTest " + module + ":test "
                    + "--tests \"" + testFqn + "\" --init-script \"" + initPath + "\" --console=plain 2>&1; "
                    + "echo '===HS-STATS==='; \"" + reader + "\" stats --dir \"" + outDir + "\" 2>&1; "
                    + "echo '===HS-DUMP==='; \"" + reader + "\" dump --dir \"" + outDir + "\" --limit 4000 2>&1";
            System.out.println("[Hindsight] recording test " + testFqn + " under the agent (out=" + outDir
                    + (remote ? " on " + spec.host() : "") + ")");

            Process proc = remote
                    ? RemoteExec.shell(cmd, spec).redirectErrorStream(true).start()
                    : new ProcessBuilder("/bin/bash", "-c", cmd)
                            .directory(Paths.get(projectDir).toFile())
                            .redirectErrorStream(true)
                            .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!proc.waitFor(TEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                return Map.of("status", "Error", "message", "test recording timed out after "
                        + TEST_TIMEOUT_MINUTES + " min");
            }
            String full = sb.toString();
            String stats = section(full, "===HS-STATS===", "===HS-DUMP===");
            String dump = section(full, "===HS-DUMP===", null);
            // The load-bearing evidence: throw / return events on the trigger path.
            StringBuilder throwsOnly = new StringBuilder();
            for (String line : dump.split("\n")) {
                if (line.contains("throw ") || line.contains("return ")) {
                    throwsOnly.append(line).append('\n');
                }
            }

            Map<String, Object> result = new HashMap<>();
            if (isEmptyRecording(stats)) {
                // 0 records is NOT a result: either gradle ran no test (wrong class/method, wrong module) or the
                // include/trigger never matched. Reporting Success here let a planner cite an empty recording as
                // runtime evidence.
                result.put("status", "Error");
                result.put("message", "the recording is EMPTY (0 records): the test did not run (check testClass/"
                        + "testMethod and the gradle module — this one used " + module + ") or nothing in include='"
                        + include + "' was executed" + (trigger == null ? "" : " / the trigger '" + trigger
                        + "' never fired") + ". Gradle output tail: "
                        + cap(full.substring(Math.max(0, full.indexOf("===HS-STATS===") - 1200),
                                Math.max(0, full.indexOf("===HS-STATS==="))), 1200));
                result.put("recordingDir", outDir);
                result.put("testRun", testFqn);
                return result;
            }
            result.put("status", "Success");
            result.put("recordingDir", outDir);
            result.put("testRun", testFqn);
            result.put("stats", cap(stats, MAX_STATS_CHARS));
            result.put("throwAndReturnEvents", cap(throwsOnly.toString(), MAX_DUMP_CHARS));
            result.put("dump", cap(dump, MAX_DUMP_CHARS));
            result.put("note", "throwAndReturnEvents shows where methods throw/return at runtime; a "
                    + "'throw X [Method]' on the trigger path followed by the SAME throw in its caller proves "
                    + "the exception propagated (escaped) that method.");
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Hindsight test recording failed: " + e.getMessage());
        }
    }

    /**
     * Null when {@code testClass#testMethod} exists in the project sources (or the graph is unavailable -
     * never block on our own failure); otherwise an error message listing the class's real test methods.
     */
    static String missingTestMethod(String projectDir, String testClass, String testMethod) {
        try {
            CodeGraph graph = CodeGraphTool.graphFor(projectDir, System.getProperty("hermit.classesDir"));
            String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            var entries = graph.hierarchy().entries(simple);
            boolean classKnown = entries.stream().anyMatch(e -> e.decl() != null
                    && (testClass.equals(e.fqn()) || !testClass.contains(".")));
            if (!classKnown) {
                List<String> fqns = entries.stream().filter(e -> e.decl() != null).map(e -> e.fqn()).toList();
                return "testClass " + testClass + " is not in the project sources" + (fqns.isEmpty() ? ""
                        : " — did you mean " + fqns + "?") + " Nothing would run. Use the class's REAL fully-qualified name.";
            }
            if (testMethod == null || testMethod.isBlank()) {
                return null;
            }
            if (!graph.locate(simple + "#" + testMethod).isEmpty()) {
                return null;
            }
            List<String> tests = new ArrayList<>();
            for (var e : graph.hierarchy().entries(simple)) {
                if (e.decl() == null) {
                    continue;
                }
                var body = e.decl().child(NodeType.CLASS_BODY);
                if (body == null) {
                    continue;
                }
                for (var m : body.children(NodeType.METHOD_DECLARATION)) {
                    for (var a : m.children(NodeType.ANNOTATION)) {
                        if (a.name() != null && a.name().endsWith("Test")) {
                            tests.add(m.name());
                        }
                    }
                }
            }
            return "testMethod '" + testMethod + "' does not exist in " + testClass + " — nothing would run. "
                    + "Real @Test methods: " + tests + ". Pick one of them, or omit testMethod to record the whole class.";
        } catch (Exception e) {
            return null;   // graph unavailable → let the run proceed as before
        }
    }

    /** Add the test class's package and the trigger class's package to {@code include} when missing. */
    static String widenInclude(String include, String projectDir, String testClass, String trigger) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String x : include.split("\\|")) {
            if (!x.isBlank()) {
                parts.add(x.trim());
            }
        }
        String testPkg = packageOf(testClass);
        if (testPkg != null && parts.stream().noneMatch(testPkg::startsWith)) {
            parts.add(testPkg);
        }
        if (trigger != null && !trigger.isBlank()) {
            String cls = trigger.contains("#") ? trigger.substring(0, trigger.indexOf('#')) : trigger;
            String triggerPkg = cls.contains(".") ? packageOf(cls) : packageOfSimpleType(projectDir, cls);
            if (triggerPkg != null && parts.stream().noneMatch(triggerPkg::startsWith)) {
                parts.add(triggerPkg);
            }
        }
        return String.join("|", parts);
    }

    private static String packageOf(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(0, dot + 1) : null;
    }

    /** The package (with trailing dot) of a simple type name, resolved through the project graph; null if unknown. */
    static String packageOfSimpleType(String projectDir, String simple) {
        try {
            CodeGraph graph = CodeGraphTool.graphFor(projectDir, System.getProperty("hermit.classesDir"));
            for (var e : graph.hierarchy().entries(simple)) {
                if (e.decl() != null && e.fqn() != null) {
                    return packageOf(e.fqn());
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return null;
    }

    /** The gradle project owning the test class's source file, or null when unknown. */
    static String moduleOfTestClass(String projectDir, String testClass) {
        try {
            CodeGraph graph = CodeGraphTool.graphFor(projectDir, System.getProperty("hermit.classesDir"));
            String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
            for (var e : graph.hierarchy().entries(simple)) {
                if (e.decl() == null || (testClass.contains(".") && !testClass.equals(e.fqn()))) {
                    continue;
                }
                for (Path f : graph.sources().files()) {
                    var tree = graph.treeOf(f);
                    if (tree != null && tree.findType(simple) == e.decl()) {
                        return GradleModules.projectFor(Paths.get(projectDir), f);
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return null;
    }

    /** The reader's stats for a recording that captured nothing. */
    static boolean isEmptyRecording(String stats) {
        return stats == null || stats.isBlank() || stats.replaceAll("\\s+", " ").contains("records: 0 ");
    }

    /** Qualify an unqualified trigger class (e.g. "Playlist#m") with the first {@code include} package
     *  so it matches the agent's fully-qualified prefix matching; a trigger whose class is already qualified
     *  (contains a dot) is returned unchanged. */
    static String qualifyTrigger(String trigger, String include) {
        int hash = trigger.indexOf('#');
        String cls = hash >= 0 ? trigger.substring(0, hash) : trigger;
        String rest = hash >= 0 ? trigger.substring(hash) : "";
        if (!cls.contains(".") && include != null && !include.isBlank()) {
            String pkg = include.split("[|,]")[0].trim();
            if (!pkg.isEmpty()) {
                cls = (pkg.endsWith(".") ? pkg : pkg + ".") + cls;
            }
        }
        return cls + rest;
    }

    private static String section(String full, String startMarker, String endMarker) {
        int s = full.indexOf(startMarker);
        if (s < 0) {
            return "";
        }
        s += startMarker.length();
        int e = endMarker == null ? full.length() : full.indexOf(endMarker, s);
        if (e < 0) {
            e = full.length();
        }
        return full.substring(s, e).strip();
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n(...truncated)";
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
