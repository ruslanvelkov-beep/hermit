package hermit.tool.process;

import hermit.tool.api.Tool;
import hermit.workspace.RemoteExec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * General shell execution - the capability that turns hermit's fixed tool set
 * into open-ended autonomy: compose command chains or scripts (git, gradle,
 * maven, grep, diff, ssh, file edits), check out a repo, build it, run tests,
 * launch static/dynamic analysis. Runs via `bash -c`, captures combined
 * stdout+stderr and the exit code.
 *
 * Safety: refuses only unmistakably catastrophic whole-filesystem/home deletes.
 * It is otherwise unrestricted by design (the agent is meant to operate the
 * machine); the guard exists so a single hallucinated command from the small
 * model cannot wipe everything. Remove GUARD to lift it entirely.
 *
 * <p>READ-ONLY / DRY-RUN MODE ({@code -Dhermit.readonly=true}): an additional,
 * opt-in guard for supervised dry-runs (e.g. "analyse this ticket but do NOT
 * change anything upstream"). When enabled, any command that would perform a
 * <em>destructive or outward-facing</em> git/GitHub operation - committing,
 * pushing, merging/rebasing, deleting refs/untracked files, or ANY {@code gh}
 * write (PR/issue create·comment·edit·close·merge·review, releases, a writing
 * {@code gh api} call) - is refused before it runs. Local file edits, builds,
 * tests, and read-only git/gh ({@code status/log/diff/show}, {@code gh pr/issue
 * view/diff/list/checks}) stay allowed, so the agent can still analyse and even
 * implement a fix locally; it just cannot publish it. Default is OFF, so normal
 * autonomous operation is unchanged.
 */
public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SEC = 120;
    // A small driver model rarely sets timeoutSec, and 120s guarantees spurious timeouts on a
    // Gradle/Maven/CodeQL build (cold config + dependency download + compile can take many minutes).
    // For commands that clearly kick off such a build/test, floor the timeout at this instead.
    private static final int LONG_DEFAULT_TIMEOUT_SEC = 1200;
    private static final Pattern LONG_RUNNING = Pattern.compile(
            "(?i)(\\bgradlew?\\b|\\bmvn\\b|\\bmaven\\b|--tests\\b|:test\\b|:build\\b|:compile|:assemble"
            + "|\\bcodeql\\s+database\\b|\\bbazel\\b)");
    private static final int MAX_OUTPUT_CHARS = 20_000;
    // Catastrophic whole-system / home recursive deletes only.
    private static final Pattern GUARD = Pattern.compile(
            "rm\\s+(-[a-zA-Z]*\\s+)*(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s+(/|~|\\$HOME|/\\*|~/\\*)\\s*$");
    // ---- read-only / dry-run guard: only OUTWARD-FACING ops are blocked ----
    // Policy: LOCAL git is fine (commit, stash, merge, rebase, reset, checkout, branch, tag) - it never
    // leaves the machine and is fully reversible. Block ONLY what publishes/notifies others: pushing to a
    // remote, creating/commenting/merging a PR or issue (a *draft* PR still creates a remote object → also
    // blocked; "drafting" means writing the text locally, not `gh pr create --draft`), and writing gh api
    // calls. Read-only git/gh (status/log/diff/show, gh view/diff/list/checks) always passes.
    private static final Pattern[] DESTRUCTIVE = {
        // pushing to a remote - the one git op that leaves the machine
        Pattern.compile("(?i)\\bgit\\s+(?:-C\\s+\\S+\\s+|--\\S+\\s+)*push\\b"),
        // gh PR/issue write verbs (create covers `gh pr create --draft` too)
        Pattern.compile("(?i)\\bgh\\s+(?:pr|issue)\\s+"
                + "(create|comment|edit|close|reopen|merge|review|ready|lock|unlock|delete|develop|pin|unpin|transfer)\\b"),
        // gh other-noun write verbs
        Pattern.compile("(?i)\\bgh\\s+(?:release|repo|gist|secret|variable|label|workflow|run|cache|ruleset|project)\\s+"
                + "(create|delete|edit|comment|merge|upload|sync|dispatch|close|set|rename|archive|enable|disable|cancel|rerun|run|clear)\\b"),
        // a WRITING gh api call (explicit write method, or -f/-F/--field which force POST)
        Pattern.compile("(?i)\\bgh\\s+api\\b[^|;&]*(-X\\s*(POST|PUT|PATCH|DELETE)|--method\\s+(POST|PUT|PATCH|DELETE)"
                + "|\\s-f\\s|\\s-F\\s|--field\\b|--raw-field\\b|--input\\b)"),
    };
    private static final Pattern PLAIN_RANGE_READ = Pattern.compile(
            "^\\s*sed\\s+-n\\s+(['\"]?)(\\d+),(\\d+)p\\1\\s+(\"[^\"|;&<>]+\"|'[^'|;&<>]+'|[^\\s|;&<>\"']+)\\s*$");
    /** Gradle's {@code --tests} filter takes {@code Class.method}; the planner writes {@code Class#method}
     *  (the analysis tools' spec shape) about half the time, which matches nothing and reads as "no such test".
     *  `sed -i … X.java`, `perl -pi … X.java` - text edits of a Java source. */
    static final Pattern IN_PLACE_SOURCE_EDIT = Pattern.compile(
            "(?s)\\b(sed\\s+(-[A-Za-z]*i|--in-place)|perl\\s+-[A-Za-z]*i)[^\\n]*\\.java\\b");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("&&|\\|\\||;|\\||\\n");
    /** `awk … > X.java` / `>> X.java`: a redirect into a Java source. */
    private static final Pattern AWK_REDIRECT_TO_JAVA = Pattern.compile(
            "\\bawk\\b[^|;&\\n]*>{1,2}\\s*[^\\s|;&]*\\.java\\b");

    /**
     * What one map entry of a tool result amounts to in the planning context (SimpleAgent.summarizeResults).
     * The planner's view of one output (SimpleAgent's -Dhermit.context.outputCap, default 4500).
     */
    static int plannerViewChars() {
        return Math.max(1000, Integer.getInteger("hermit.context.outputCap", 4500));
    }

    /** Opt-in dry-run policy; read per-call so tests/runs can toggle it via the system property. */
    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    public static boolean readOnly() {
        return Boolean.parseBoolean(System.getProperty("hermit.readonly", "false"));
    }

    /** True if the command clearly launches a slow build/test (Gradle/Maven/CodeQL/Bazel). */
    static boolean looksLongRunning(String command) {
        return command != null && LONG_RUNNING.matcher(command).find();
    }

    /**
     * The timeout to actually use: a long-running build/test is floored at {@link #LONG_DEFAULT_TIMEOUT_SEC}
     * so a small model's missing/too-small value can't kill it mid-compile; an explicitly larger request is
     * kept as-is. Non-build commands keep whatever was requested. Package-visible for tests.
     */
    static int effectiveTimeout(String command, int requested) {
        if (looksLongRunning(command) && requested < LONG_DEFAULT_TIMEOUT_SEC) {
            System.err.println("[BASH] floored timeout to " + LONG_DEFAULT_TIMEOUT_SEC
                    + "s for a build/test command (was " + requested + "s)");
            return LONG_DEFAULT_TIMEOUT_SEC;
        }
        return requested;
    }

    /**
     * If {@code command} contains a destructive/outward-facing git/gh operation, return a short
     * label for it (for the refusal message + log); otherwise null. Public so a supervising
     * front-end/listener can reuse the exact same classifier to observe plans, not just executions.
     */
    public static String destructiveOp(String command) {
        if (command == null) {
            return null;
        }
        for (Pattern p : DESTRUCTIVE) {
            Matcher m = p.matcher(command);
            if (m.find()) {
                return m.group().strip();
            }
        }
        return null;
    }

    @Override
    public String getDocumentation() {
        return "Bash: run a shell command (via bash -c) and get its combined stdout+stderr and exit "
        + "code. Use for git, gradle, maven, grep, diff, file edits, ssh, cloning/building "
        + "projects, running tests, launching analysis - compose chains with && | ; or a "
        + "here-doc script. Parameters: {\"command\": \"<shell>\", \"workingDir\": \"<dir>\" "
        + "(optional), \"timeoutSec\": <int> (optional, default 120), \"remote\": "
        + "\"user@host:/abs/path\" (optional - set ONLY when told to run this command over SSH "
        + "on a remote machine; then it runs there, cd'd to that path)}.";
    }

    /** A bare {@code gradle …} invocation becomes {@code ./gradlew …} when the working dir has the wrapper -
     *  the planner alternates between the two, and a machine without a global gradle "fails" the real run. */
    static String preferGradleWrapper(String command, String workingDir) {
        if (command == null || workingDir == null) {
            return command;
        }
        if (!Files.isRegularFile(Path.of(workingDir, "gradlew"))) {
            return command;
        }
        return command.replaceAll("(^|&&\\s*|;\\s*|\\|\\s*)gradle(\\s)", "$1./gradlew$2");
    }

    /**
     * Every read was {@code sed -n '150,250p' File.java}, which prints the lines WITHOUT
     * their numbers - so the verdict cited "lines 75-84", then "≈155-165", for code the read showed at 195 and 205: with no
     * numbers in front of it, every citation into a read is a guess. A plain range read of one file runs as
     * {@code cat -n File.java | sed -n '150,250p'}: the same lines, each with its real number. The plan's own command (the
     * guards' signature) is untouched; only what runs changes.
     */
    static String numberedRead(String command) {
        if (command == null) {
            return null;
        }
        Matcher m = PLAIN_RANGE_READ.matcher(command);
        if (!m.matches()) {
            return command;
        }
        return "cat -n " + m.group(4) + " | sed -n '" + m.group(2) + "," + m.group(3) + "p'";
    }

    /** The working dir a command will run in: the param, else the configured project default, else null. */
    static String resolveWorkingDir(Map<String, Object> parameters) {
        Object wd = parameters == null ? null : parameters.get("workingDir");
        if (wd != null && !String.valueOf(wd).isBlank()) {
            return String.valueOf(wd);
        }
        String def = System.getProperty("hermit.bash.defaultDir", System.getProperty("hermit.projectDir"));
        return def == null || def.isBlank() ? null : def;
    }

    /** `git commit` with nothing staged says "nothing to commit"/"no changes added" although the generator
     *  changed files - the model then reports a non-commit. Name the fix in the result. */
    static String commitHint(String command, String output) {
        if (command == null || output == null || !command.matches("(?s).*\\bgit\\s+commit\\b.*")) {
            return null;
        }
        String o = output.toLowerCase(Locale.ROOT);
        if (o.contains("nothing to commit") || o.contains("no changes added to commit") || o.contains("nothing added to commit")) {
            return "nothing was staged: stage the changed file(s) first — `git add <path> && git commit -s -m '…'` "
                    + "(or `git commit -a -s -m '…'`). The commit did NOT happen.";
        }
        return null;
    }

    /** The command with ';', '&' and '|' inside quotes replaced by spaces, so that only real separators split it. */
    static String separatorsInQuotesBlanked(String command) {
        StringBuilder out = new StringBuilder(command.length());
        char quote = 0;
        for (char c : command.toCharArray()) {
            if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c;
            } else if (c == quote) {
                quote = 0;
            } else if (quote != 0 && (c == ';' || c == '&' || c == '|')) {
                c = ' ';
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * An in-place text edit of a Java source. A read-only
     * `awk '… count>=60 {exit}' X.java` was refused as an edit - the `>` of the program's comparison read as a redirect.
     * awk's redirect is looked for outside the quoted program, and only into a .java file.
     */
    public static boolean editsJavaSourceInPlace(String command) {
        if (command == null) {
            return false;
        }
        // 'sed -i ... TEST-LEDGER.md && git add ...A.java' edits no Java source; the edited file
        // is in the sed command's own segment, so separators outside quotes end the search
        for (String segment : SEGMENT_SEPARATOR.split(separatorsInQuotesBlanked(command))) {
            if (IN_PLACE_SOURCE_EDIT.matcher(segment).find()) {
                return true;
            }
        }
        String unquoted = command.replaceAll("'[^']*'", "''").replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        return AWK_REDIRECT_TO_JAVA.matcher(unquoted).find();
    }

    static String normalizeGradleTestFilter(String command) {
        if (command == null || !command.contains("--tests")) {
            return command;
        }
        String c = command.replaceAll("(--tests[ =][\"']?[A-Za-z0-9_.]+)#([A-Za-z0-9_]+)", "$1.$2");
        return scopeGradleTestToModule(c, System.getProperty("hermit.treegen.module",
                System.getProperty("hermit.gencode.module", "")));
    }

    /**
     * `./gradlew test --tests X` runs the WHOLE multi-module build's tests - tens of minutes
     * for one test - while the configured module task (`:app:test`) takes seconds. When a module is configured and
     * the command names the bare `test` task, scope it.
     *
     * A gradle test run's output is long and the tool keeps its HEAD, so the failure - a
     * Mockito UnnecessaryStubbingException at the very end - never reached the planner, which saw only "exit 1".
     * After a failed {@code --tests} run, the test report XMLs written in the last few minutes are read and each
     * failed test's message is returned, so the result says WHY the run failed.
     */
    /** {@code <gradle task(s)> → exit N, BUILD SUCCESSFUL|FAILED[, T tests completed, F failed]} - one line. */
    static String buildSummary(String command, int exit, String output) {
        StringBuilder sb = new StringBuilder();
        Matcher tasks = Pattern.compile("(:[\\w.-]+(?::[\\w-]+)*)").matcher(command == null ? "" : command);
        List<String> names = new ArrayList<>();
        while (tasks.find()) {
            names.add(tasks.group(1));
        }
        sb.append(names.isEmpty() ? "gradle" : String.join(" ", names)).append(" → exit ").append(exit);
        String o = output == null ? "" : output;
        if (o.contains("BUILD SUCCESSFUL")) {
            sb.append(", BUILD SUCCESSFUL");
        } else if (o.contains("BUILD FAILED")) {
            sb.append(", BUILD FAILED");
        } else if (exit == 0) {
            sb.append(", completed");
        }
        Matcher counts = Pattern.compile("(\\d+) tests? completed, (\\d+) failed").matcher(o);
        if (counts.find()) {
            sb.append(", ").append(counts.group(1)).append(" tests completed, ").append(counts.group(2)).append(" failed");
        }
        Matcher testTask = Pattern.compile("Task (:[\\w.-]+:test)\\b( FAILED)?").matcher(o);
        if (testTask.find()) {
            sb.append(" [").append(testTask.group(1)).append(testTask.group(2) == null ? " ran" : " FAILED").append(']');
        }
        return sb.toString();
    }

    /** The first javac errors of a build output as {@code File.java:LINE: message <- source line}, at most three. */
    static String compileErrors(String output) {
        if (output == null || !output.contains(": error:")) {
            return "";
        }
        Matcher m = Pattern
                .compile("([\\w./-]+\\.java):(\\d+): error: ([^\\n]+)\\n([^\\n]*)").matcher(output);
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        while (m.find() && lines.size() < 3) {
            String file = m.group(1);
            String simple = file.substring(file.lastIndexOf('/') + 1);
            lines.add(simple + ":" + m.group(2) + ": " + m.group(3).strip() + " <- " + m.group(4).strip());
        }
        return lines.isEmpty() ? "" : "=== compile errors === " + String.join(" | ", lines);
    }

    public static String gradleTestFailures(Path workingDir, String command) {
        if (workingDir == null || command == null || !command.contains("--tests")) {
            return "";
        }
        try {
            List<Path> reports = new ArrayList<>();
            long recent = System.currentTimeMillis() - 15 * 60_000L;
            try (Stream<Path> walk = Files.walk(workingDir, 6)) {
                walk.filter(f -> f.toString().contains("/build/test-results/") && f.getFileName().toString().startsWith("TEST-")
                        && f.getFileName().toString().endsWith(".xml"))
                        .filter(f -> f.toFile().lastModified() >= recent)
                        .forEach(reports::add);
            }
            StringBuilder sb = new StringBuilder();
            for (Path r : reports) {
                String xml = Files.readString(r);
                Matcher m = Pattern.compile(
                        "<testcase name=\"([^\"]+)\"[^>]*>\\s*<(failure|error)[^>]*message=\"([^\"]{0,600})").matcher(xml);
                while (m.find()) {
                    String msg = m.group(3).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&amp;", "&").replace("&#10;", " ").replaceAll("\\s+", " ");
                    sb.append("\nTEST FAILED: ").append(m.group(1)).append(" — ").append(msg);
                    // the message names File.java:LINE; quote the statement there so the exact
                    // anchor can be copied into a TreeEdit instead of a placeholder
                    Matcher loc = Pattern.compile("\\((\\w+\\.java):(\\d+)\\)").matcher(msg);
                    int quoted = 0;
                    while (loc.find() && quoted < 2) {
                        String line = sourceLineAt(workingDir, loc.group(1), Integer.parseInt(loc.group(2)));
                        if (line != null) {
                            sb.append("\n  at ").append(loc.group(1)).append(':').append(loc.group(2)).append(" → ").append(line);
                            quoted++;
                        }
                    }
                }
            }
            return sb.length() == 0 ? "" : "\n\n=== failed tests (from the test reports) ===" + sb;
        } catch (Exception e) {
            return "";
        }
    }

    /** The trimmed source line {@code line} of the first file named {@code fileName} under {@code root}, or null. */
    static String sourceLineAt(Path root, String fileName, int line) {
        try (Stream<Path> walk = Files.walk(root, 12)) {
            Path f = walk.filter(p -> p.getFileName().toString().equals(fileName) && p.toString().contains("/src/"))
                    .findFirst().orElse(null);
            if (f == null) {
                return null;
            }
            List<String> lines = Files.readAllLines(f);
            if (line < 1 || line > lines.size()) {
                return null;
            }
            String t = lines.get(line - 1).strip();
            return t.length() > 200 ? t.substring(0, 200) + "…" : t;
        } catch (Exception e) {
            return null;
        }
    }

    static String scopeGradleTestToModule(String command, String module) {
        if (command == null || module == null || module.isBlank() || !command.contains("--tests")) {
            return command;
        }
        String m = module.startsWith(":") ? module : ":" + module;
        return command.replaceAll("(\\./gradlew|gradle)(\\s+)test(\\s)", "$1$2" + m + ":test$3");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String command = normalizeGradleTestFilter((String) parameters.get("command"));
        command = preferGradleWrapper(command, resolveWorkingDir(parameters));
        command = numberedRead(command);
        if (command == null || command.isBlank()) {
            return Map.of("status", "Error", "message", "command is required.");
        }
        // the planner reached for `sed -i` on a test source to change one stub - an in-place
        // text edit that bypasses the parse-tree validation every other edit goes through, and it was malformed.
        // Source files are edited with TreeEdit (statements) or TreeCodegen (methods), never with sed/perl/awk.
        if (editsJavaSourceInPlace(command)) {
            return Map.of("status", "Error",
                    "message", "Refused: in-place text edits of Java sources (sed -i / perl -pi / awk > file) bypass the "
                            + "parse-tree validation. Edit the file with TreeEdit: op=replaceStatement, method=<Type#method>, "
                            + "anchor=<the exact statement text>, code=<the new statement>.");
        }
        if (GUARD.matcher(command.trim()).find()) {
            return Map.of("status", "Error",
                    "message", "Refused: command matches a catastrophic whole-filesystem delete guard.");
        }
        if (readOnly()) {
            String op = destructiveOp(command);
            if (op != null) {
                // Loud, greppable marker so a supervisor can SEE the agent tried an outward-facing op.
                System.err.println("[READONLY-GUARD] refused outward-facing git/gh op: " + op);
                return Map.of("status", "Error",
                        "message", "Refused (dry-run mode): '" + op + "' PUBLISHES to a remote (git push, or a "
                                + "gh PR/issue create/comment/merge/edit — a draft PR still creates a remote "
                                + "object). This is a local-only run: do NOT push, open/comment on PRs, or post "
                                + "to issues. You MAY work fully locally — read via gh view/diff, edit files, and "
                                + "git add/commit/stash/merge locally, and DRAFT PR bodies/comments to a file. "
                                + "Re-plan without publishing.");
            }
        }
        int timeoutSec = DEFAULT_TIMEOUT_SEC;
        Object t = parameters.get("timeoutSec");
        if (t instanceof Number n) {
            timeoutSec = n.intValue();
        } else if (t instanceof String s) {
            try {
                timeoutSec = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        // Floor slow build/test commands at a build-sized timeout so a too-small (or default)
        // value doesn't kill a Gradle run mid-compile. Explicitly LARGER values are respected.
        timeoutSec = effectiveTimeout(command, timeoutSec);
        String workingDir = (String) parameters.get("workingDir");
        // Default the working directory to the project under investigation when the driver sets one and
        // the call didn't name a dir - so git/gradle/grep operate on the WORKBENCH, not hermit's own repo.
        // (An explicit workingDir, or a remote target, always wins.)
        if ((workingDir == null || workingDir.isBlank()) && parameters.get("remote") == null) {
            String def = firstNonBlank(System.getProperty("hermit.bash.defaultDir"),
                    System.getProperty("hermit.projectDir"));
            if (def != null && Files.isDirectory(Paths.get(def))) {
                workingDir = def;
            }
        }
        // Resolve the remote target from the call's "remote" param (or the global -Dhermit.exec.host).
        // Only validate/normalize the dir LOCALLY; under remote exec the dir lives on the remote host, so
        // we pass it through and RemoteExec folds `cd <dir>` into the ssh command.
        boolean remote = RemoteExec.isRemote(
                RemoteExec.spec(parameters, workingDir));
        if (workingDir != null && !workingDir.isBlank() && !remote) {
            Path dir = Paths.get(workingDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return Map.of("status", "Error", "message", "workingDir not found: " + dir);
            }
            workingDir = dir.toString();
        }
        RemoteExec.Spec spec = RemoteExec.spec(parameters, workingDir);
        ProcessBuilder pb = RemoteExec.shell(command, spec).redirectErrorStream(true);
        try {
            Process process = pb.start();
            // Drain stdout on a separate thread: a blocking read here would let a
            // silent-but-running command outlast the timeout (waitFor never gets a
            // chance to fire until the stream closes).
            StringBuilder out = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drain.join(1000);
                synchronized (out) {
                    return Map.of("status", "Error",
                            "message", "command timed out after " + timeoutSec + "s",
                            "output", cap(out.toString()));
                }
            }
            drain.join(2000);
            int exit = process.exitValue();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", exit == 0 ? "Success" : "NonZeroExit");
            result.put("exitCode", exit);
            // two GREEN gradle runs happened and the real-run gate still said none had - the
            // evidence view caps each value at 1200 chars, and a long successful gradle output puts the test task and
            // the BUILD verdict beyond it. The command and a compact build summary come first and survive the cap.
            boolean silent;
            synchronized (out) {
                silent = out.toString().isBlank();
            }
            // a command that prints nothing (mkdir, a redirect) left no trace of what it did
            if (command.contains("gradlew") || silent) {
                result.put("command", command.length() > 200 ? command.substring(0, 200) + "…" : command);
            }
            if (command.contains("gradlew")) {
                synchronized (out) {
                    result.put("buildSummary", buildSummary(command, exit, out.toString()));
                }
            }
            if (exit != 0 && command.contains("--tests")) {
                String wd = resolveWorkingDir(parameters);
                String failures = gradleTestFailures(wd == null ? null : Path.of(wd), command);
                if (!failures.isBlank()) {
                    // `output` is capped keeping its HEAD, so an appended tail was cut off before the planner
                    // saw it - the failures get their own (short, uncapped) key
                    result.put("failedTests", failures.strip());
                }
            }
            if (exit != 0) {
                // the fix did not compile ("incompatible types: String cannot be converted
                // to Optional<FileInfo>") and the javac line sat beyond the capped output - the planner never saw why
                String errors;
                synchronized (out) {
                    errors = compileErrors(out.toString());
                }
                if (!errors.isBlank()) {
                    result.put("compileErrors", errors);
                }
            }
            synchronized (out) {
                result.put("output", cap(out.toString()));
                // a `cat` of a 400-line file returned 20k chars; the planner's context
                // shows the first ~600 of them (the license header) and nothing said so - the read was wasted and the
                // planner re-located the file instead of reading a range. A long output says how much of it the
                // planner sees and how to read the rest.
                if (out.length() > plannerViewChars()) {
                    result.put("outputNote", outputNote(command, out.toString()));
                }
                String commitHint = commitHint(command, out.toString());
                if (commitHint != null) {
                    result.put("hint", commitHint);
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "execution failed: " + e.getMessage());
        }
    }

    /** How much of a long output the planner will see, and how to read the rest. */
    static String outputNote(String command, String full) {
        int lines = (int) full.chars().filter(c -> c == '\n').count() + 1;
        int shown = (int) full.substring(0, Math.min(full.length(), plannerViewChars())).chars().filter(c -> c == '\n').count() + 1;
        Matcher m = Pattern
                .compile("(\\S+\\.(?:java|kt|md|txt|json|yaml|yml|gradle|xml|properties))\\b").matcher(command == null ? "" : command);
        String file = m.find() ? m.group(1) : "<file>";
        return "this output is " + lines + " lines / " + full.length() + " chars; your planning context shows only its first ~"
                + shown + " lines. Read the part you need in ranges of at most 60 lines: sed -n 'A,Bp' " + file
                + " (grep -n '<name>' " + file + " finds the line first).";
    }

    private String cap(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        // Keep head and tail - the interesting output is usually at both ends.
        int half = MAX_OUTPUT_CHARS / 2;
        return s.substring(0, half) + "\n...(" + (s.length() - MAX_OUTPUT_CHARS)
                + " chars omitted)...\n" + s.substring(s.length() - half);
    }
}
