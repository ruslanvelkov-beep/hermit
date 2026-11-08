package hermit.tool.process;

import hermit.tool.api.Tool;
import hermit.workspace.RemoteExec;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a program or shell command and captures its stdout and stderr
 * SEPARATELY (unlike Bash, which merges them), plus the exit code - so the
 * agent can run a program it just wrote and report its output. The two streams
 * are drained on separate threads so a silent long-running program still honors
 * the timeout. The TUI renders stdout in cyan and stderr in red; an empty run
 * is reported explicitly.
 */
public class RunProgramTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_STREAM_CHARS = 20_000;

    @Override
    public String getDocumentation() {
        return "RunProgram: run a program or shell command and capture stdout and stderr SEPARATELY "
        + "plus the exit code (use this to run a program you wrote and report its output). "
        + "Parameters: {\"command\": \"<shell, e.g. 'python3 hello.py'>\", \"workingDir\": "
        + "\"<dir>\" (optional), \"timeoutSec\": <int> (optional), \"remote\": "
        + "\"user@host:/abs/path\" (optional - set ONLY when told to run on a remote machine over "
        + "SSH)}. Returns stdout, stderr, exitCode and hadOutput (false = no output).";
    }

    /**
     * Defense-in-depth: models often invoke the interpreter as {@code python}, which
     * many machines don't have on PATH (only {@code python3}) → "command not found".
     * Rewrite a standalone {@code python} token (at the command start or after a shell
     * separator, not {@code python3}/{@code mypython}/an explicit path) to {@code python3}.
     */
    static String normalizePython(String command) {
        return command.replaceAll("(?m)(^|(?<=[\\s;&|(]))python(?=\\s)", "python3");
    }

    /**
     * the planner ran gradle through THIS tool with `--tests PlaylistTest#method` - a filter
     * gradle rejects ("No tests found") - while the fix for that shape lived only in the Bash tool. The same
     * normalisation applies to every shell, and a failed gradle test run reports its failed tests here too.
     */
    static String effectiveCommand(String command) {
        return BashTool.normalizeGradleTestFilter(normalizePython(command));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String command = (String) parameters.get("command");
        if (command == null || command.isBlank()) {
            return Map.of("status", "Error", "message", "command is required.");
        }
        command = effectiveCommand(command);
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
        String workingDir = (String) parameters.get("workingDir");
        // Resolve the remote target from "remote" (or the global default); validate the dir only LOCALLY.
        RemoteExec.Spec spec = RemoteExec.spec(parameters, workingDir);
        boolean remote = RemoteExec.isRemote(spec);
        if (!remote && workingDir != null && !workingDir.isBlank()) {
            Path dir = Paths.get(workingDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return Map.of("status", "Error", "message", "workingDir not found: " + dir);
            }
            workingDir = dir.toString();
            spec = RemoteExec.spec(parameters, workingDir);
        }
        // streams NOT merged (RemoteExec.shell leaves stderr separate; ssh preserves both streams)
        ProcessBuilder pb = RemoteExec.shell(command, spec);
        try {
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread to = drain(process.getInputStream(), out);
            Thread te = drain(process.getErrorStream(), err);
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                to.join(1000);
                te.join(1000);
                return result("Error", -1, out, err, "timed out after " + timeoutSec + "s");
            }
            to.join(2000);
            te.join(2000);
            Map<String, Object> res = result(process.exitValue() == 0 ? "Success" : "NonZeroExit",
                    process.exitValue(), out, err, null);
            if (command.contains("gradlew")) {
                Map<String, Object> withBuild = new LinkedHashMap<>();
                withBuild.put("command", command.length() > 200 ? command.substring(0, 200) + "…" : command);
                withBuild.put("buildSummary", BashTool.buildSummary(command, process.exitValue(), out.toString() + "\n" + err.toString()));
                withBuild.putAll(res);
                res = withBuild;
            }
            if (process.exitValue() != 0) {
                String errors = BashTool.compileErrors(out.toString() + "\n" + err.toString());
                if (!errors.isBlank()) {
                    Map<String, Object> withErrors = new LinkedHashMap<>(res);
                    withErrors.put("compileErrors", errors);
                    res = withErrors;
                }
            }
            if (process.exitValue() != 0 && command.contains("--tests")) {
                String wd = workingDir != null && !workingDir.isBlank() ? workingDir : BashTool.resolveWorkingDir(parameters);
                String failures = BashTool.gradleTestFailures(wd == null ? null : Paths.get(wd), command);
                if (failures != null && !failures.isBlank()) {
                    Map<String, Object> withFailures = new LinkedHashMap<>(res);
                    withFailures.put("failedTests", failures.strip());
                    return withFailures;
                }
            }
            return res;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "run failed: " + e.getMessage());
        }
    }

    private Map<String, Object> result(String status, int exit, StringBuilder out, StringBuilder err, String note) {
        String o = cap(out.toString());
        String e = cap(err.toString());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("exitCode", exit);
        r.put("stdout", o);
        r.put("stderr", e);
        r.put("hadOutput", !o.isBlank() || !e.isBlank());
        if (note != null) {
            r.put("message", note);
        }
        return r;
    }

    private Thread drain(InputStream in, StringBuilder sink) {
        Thread th = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        });
        th.setDaemon(true);
        th.start();
        return th;
    }

    private String cap(String s) {
        if (s.length() <= MAX_STREAM_CHARS) {
            return s;
        }
        return s.substring(0, MAX_STREAM_CHARS) + "\n...(" + (s.length() - MAX_STREAM_CHARS) + " more chars)";
    }
}
