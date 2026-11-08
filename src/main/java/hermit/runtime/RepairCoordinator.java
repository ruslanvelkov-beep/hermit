package hermit.runtime;

import hermit.answer.AnswerGate;
import hermit.api.Action;
import hermit.text.CodeFence;
import hermit.tool.analysis.TreeEditTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a turn recovers around its edits: the build Hermit runs after edits and the build state it observes, a commit
 * refused while the build is broken, the edits reverted when they broke it, the commit Hermit adds when the run allows
 * one, and a program's failure noted at its seam (with the program's code put into the next prompt).
 */
public final class RepairCoordinator {

    private RepairCoordinator() {
    }

    /**
     * null unless EVERY action of the plan is a verbatim repeat of a call already executed this turn.
     * Repeating successes = re-learning what CONTEXT holds; repeating a failure identically = the same failure.
     */
    /**
     * the gradle command that runs the request's test after a plan whose TreeEdit succeeded without any build or
     * test step - {@code ./gradlew <module>:test --tests <Class.method>} - or null when nothing is to be run: no edit
     * succeeded, a build already ran in the plan, or no module and test are known.
     */
    public static String buildAfterEditsCommand(List<Action> actions, Map<String, Object> results, String request) {
        if (actions == null || results == null) {
            return null;
        }
        boolean edited = results.entrySet().stream().anyMatch(e -> "TreeEdit".equals(ToolExecutor.baseToolName(e.getKey()))
                && isSuccessStatic(e.getValue()));
        if (!edited) {
            return null;
        }
        for (Action a : actions) {
            String cmd = a.parameters() == null || a.parameters().get("command") == null ? "" : String.valueOf(a.parameters().get("command"));
            if (("Bash".equals(a.toolName()) || "RunProgram".equals(a.toolName())) && cmd.contains("gradlew")) {
                return null;
            }
            if ("TreeCodegen".equals(a.toolName()) || "HindsightDebugger".equals(a.toolName())) {
                return null;   // those run the test themselves
            }
        }
        String module = System.getProperty("hermit.treegen.module", System.getProperty("hermit.gencode.module", ""));
        if (module == null || module.isBlank() || request == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b([A-Z]\\w*(?:Test|Tests|IT))#(\\w+)\\b").matcher(request);
        if (!m.find()) {
            return null;
        }
        return "./gradlew " + module + ":test --tests " + m.group(1) + "." + m.group(2);
    }

    private static boolean isSuccessStatic(Object value) {
        return value instanceof Map<?, ?> m && "Success".equals(String.valueOf(m.get("status")));
    }

    /** the build state after a plan - compile errors set it broken, a successful gradle/test run clears it. */
    public static void observeBuildState(Map<String, Object> results, boolean[] buildBroken) {
        if (results == null) {
            return;
        }
        for (Object v : results.values()) {
            if (!(v instanceof Map<?, ?> m)) {
                continue;
            }
            if (m.get("compileErrors") != null) {
                buildBroken[0] = true;
            } else if ("Success".equals(String.valueOf(m.get("status"))) && String.valueOf(m).contains("Task :")) {
                buildBroken[0] = false;
            }
        }
    }

    /** a plan that commits while the module does not compile is refused, naming the state. */
    public static String commitWhileBrokenIssue(List<Action> actions, boolean buildBroken) {
        if (!buildBroken || actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null) {
                continue;
            }
            String cmd = String.valueOf(a.parameters().get("command"));
            if (cmd.matches("(?s).*\\bgit\\s+(?:-C\\s+\\S+\\s+)?commit\\b.*")) {
                return "This plan runs `git commit` while the module does NOT compile (the last build reported compile errors "
                        + "in a file this turn edited). Fix the code and run the module's build/test GREEN first; commit only "
                        + "after that.";
            }
        }
        return null;
    }

    /**
     * when a result of this plan carries {@code compileErrors} naming a file that a TreeEdit of the same plan
     * modified successfully, that file is restored to its pre-edit content. Returns the note for the context, or null.
     */
    public static String revertEditsThatBrokeTheBuild(List<Action> actions, Map<String, Object> results) {
        if (actions == null || results == null) {
            return null;
        }
        String errors = null;
        for (Object v : results.values()) {
            if (v instanceof Map<?, ?> m && m.get("compileErrors") != null) {
                errors = String.valueOf(m.get("compileErrors"));
            }
        }
        if (errors == null) {
            return null;
        }
        List<String> revertedFiles = new ArrayList<>();
        for (Map.Entry<String, Object> e : results.entrySet()) {
            if (!"TreeEdit".equals(ToolExecutor.baseToolName(e.getKey())) || !(e.getValue() instanceof Map<?, ?> r)
                    || !"Success".equals(String.valueOf(r.get("status"))) || r.get("file") == null) {
                continue;
            }
            Path file = Path.of(String.valueOf(r.get("file")));
            String name = file.getFileName().toString();
            if (errors.contains(name + ":") && TreeEditTool.revertLastEdit(file)) {
                revertedFiles.add(name);
            }
        }
        if (revertedFiles.isEmpty()) {
            return null;
        }
        return "The edit(s) this plan made to " + String.join(", ", revertedFiles) + " left the module UNCOMPILABLE ("
                + (errors.length() > 400 ? errors.substring(0, 400) + "…" : errors) + ") and were REVERTED — the file is "
                + "back to its pre-edit content. Write the edit again from the compile error and the method's real names.";
    }

    /**
     * after TreeCodegen generated PARSER code, the planner twice ended without running
     * the request's check command, and the answer gate's auto-commit committed the production file as "test: add failing
     * test parseSelectors", past the step-mode seam that every action waits at. The auto-commit is for a generated TEST
     * file, and never runs in step mode, where the supervisor decides each commit.
     */
    public static boolean autoCommitAllowed(String generatedFile, boolean stepMode) {
        return !stepMode && generatedFile != null && generatedFile.replace('\\', '/').contains("/src/test/");
    }

    /** a LOCAL, signed-off, conventional commit of the generated test - never a push. */
    public static String autoCommitCommand(String file, String methodSpec, Path repo) {
        String method = methodSpec == null ? "the generated test"
                : methodSpec.contains("#") ? methodSpec.substring(methodSpec.indexOf('#') + 1) : methodSpec;
        // a RELATIVE file path (the planner's) got a relative `-C` directory prefixed to it and
        // git saw a doubled path - a relative path is relative to the shell tool's default project directory, so
        // plain git applies; only an absolute path names its repository with -C
        boolean absolute = file != null && Path.of(file).isAbsolute();
        String git = absolute && repo != null ? "git -C '" + repo + "'" : "git";
        return git + " add '" + file + "' && " + git + " commit -s -m 'test: add failing test " + method + "'";
    }

    @SuppressWarnings("unchecked")
    public static String programFailure(Map<String, Object> results) {
        for (Map.Entry<String, Object> e : results.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> r = (Map<String, Object>) e.getValue();
            String tool = ToolExecutor.baseToolName(e.getKey());
            String status = String.valueOf(r.get("status"));
            boolean syntaxBad = Boolean.FALSE.equals(r.get("syntaxOk")) || r.get("syntaxError") != null;
            // a failing PROGRAM tool - a Bash command's non-zero exit (a grep with no match, a failing build) is not
            // a program to repair; the arbitrary-command case in an analysis-only task is stopped at the call site
            boolean ranFailed = ("RunProgram".equals(tool) && ("Error".equals(status) || "NonZeroExit".equals(status)))
                    || ("WriteProgram".equals(tool) && "Error".equals(status))
                    || ("SyntaxCheck".equals(tool) && Boolean.FALSE.equals(r.get("syntaxOk")))
                    || ("GenerateCode".equals(tool) && "Error".equals(status));
            if (syntaxBad || ranFailed) {
                StringBuilder sb = new StringBuilder("TOOL ").append(tool).append(" failed. ");
                Object code = r.getOrDefault("code", r.get("content"));
                if (code != null) {
                    sb.append("\nPROGRAM:\n").append(AnswerGate.capTail(String.valueOf(code), 1500));
                }
                Object err = firstNonNull(r.get("syntaxError"), r.get("stderr"), r.get("message"));
                if (err != null) {
                    sb.append("\nERROR:\n").append(AnswerGate.capTail(String.valueOf(err), 800));
                }
                return sb.toString();
            }
        }
        return null;
    }

    public static Object firstNonNull(Object... xs) {
        for (Object x : xs) {
            if (x != null && !String.valueOf(x).isBlank()) {
                return x;
            }
        }
        return null;
    }

    /**
     * Inject an out-of-band fenced program into the code-bearing action that needs
     * it (WriteProgram / SyntaxCheck), so the model never has to escape code into
     * JSON. Only fills a code param that is missing/blank/placeholder; other actions
     * pass through unchanged. Also supplies the fenced block's language if absent.
     */
    /** The program a WriteProgram or SyntaxCheck action of {@code actions} will run (the fenced block wins), or null. */
    public static String programAtSeam(List<Action> actions, CodeFence.Extracted program) {
        for (Action a : injectProgramCode(actions, program)) {
            if ("WriteProgram".equals(a.toolName()) || "SyntaxCheck".equals(a.toolName())) {
                Object code = a.parameters() == null ? null : a.parameters().get("code");
                if (code != null && !String.valueOf(code).isBlank()) {
                    return String.valueOf(code);
                }
            }
        }
        return null;
    }

    static List<Action> injectProgramCode(List<Action> actions, CodeFence.Extracted program) {
        if (program == null || program.code() == null || program.code().isBlank()) {
            return actions;
        }
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            boolean codeBearing = "WriteProgram".equals(a.toolName()) || "SyntaxCheck".equals(a.toolName());
            // PREFER the fenced out-of-band block: it preserves the program's newlines,
            // whereas a weak model often inlines FLATTENED code in the JSON `code` param
            // (newlines stripped to dodge escaping) which then fails to compile. When a
            // fence was extracted (program non-blank, guaranteed here), it wins over inline.
            if (codeBearing) {
                Map<String, Object> p = new HashMap<>(a.parameters());
                p.put("code", program.code());
                if (!p.containsKey("language") && program.language() != null) {
                    p.put("language", program.language());
                }
                out.add(new Action(a.toolName(), p));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    // git prints a commit as e.g. "[branch abc1234] message" - the one unfakeable success marker.
    private static final Pattern GIT_COMMIT_LINE =
            Pattern.compile("\\[[^\\]\\n]+\\s[0-9a-f]{7,}\\]");

    /**
     * Update the anti-confabulation ground-truth flags from real tool results: a Bash git commit that
     * actually produced a commit line, and any real test/emulator outcome (gradle BUILD verdict, a
     * TreeCodegen testOutcome/emulatorPrediction, or a CodeGraph emulate verdict).
     */
    public static void observeGroundTruth(Map<String, Object> results,
            boolean[] committed, boolean[] testObserved) {
        if (results == null) {
            return;
        }
        results.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?> m)) {
                return;
            }
            String blob = String.valueOf(m).toLowerCase();
            Object out = m.get("output");
            if (out != null && GIT_COMMIT_LINE.matcher(String.valueOf(out)).find()) {
                committed[0] = true;
            }
            // a prediction (emulatorPrediction, a CodeGraph emulate verdict) is NOT an observed
            // test outcome - a vacuous test predicted RED would have been reported as RED. Only a real run counts:
            // TreeCodegen's testOutcome, a gradle BUILD verdict or test task in a Bash output, BashTool's failedTests.
            if (m.containsKey("testOutcome") || m.containsKey("failedTests") || m.containsKey("buildSummary")
                    || blob.contains("build successful") || blob.contains("build failed")
                    || blob.matches("(?s).*task :[\\w.-]+:test\\b.*")) {
                testObserved[0] = true;
            }
        });
    }
}
