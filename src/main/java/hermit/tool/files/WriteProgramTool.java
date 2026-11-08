package hermit.tool.files;

import hermit.tool.api.Tool;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes a standalone program and (by default) runs it - with the program supplied
 * OUT OF BAND. The planner puts only {language, filename?, run?} in the JSON and
 * provides the actual code as a fenced ```lang block AFTER the JSON; the agent
 * injects that code here as the "code" parameter. This is the "code disposes" fix
 * for the small model repeatedly mangling the JSON-escaping of an embedded program
 * (\' and unescaped quotes) and looping to the iteration cap: the code never has to
 * be escaped into a JSON string, so the plan JSON stays tiny and always parseable.
 */
public class WriteProgramTool implements Tool {

    private static final int TIMEOUT_SEC = 30;
    private static final int MAX_OUTPUT = 8000;

    @Override
    public String getDocumentation() {
        return "WriteProgram: write a standalone program and run it. Put ONLY "
        + "{\"language\": \"python\"|\"bash\"|\"java\", \"filename\": \"<optional>\", \"run\": true} "
        + "in the JSON — provide the ACTUAL program AFTER the JSON object as a fenced code block "
        + "(```python … ```), NEVER inside the JSON (embedding code in JSON breaks parsing). Writes the "
        + "file, checks its syntax, and (if run) executes it, returning syntaxOk + stdout/stderr/exit.";
    }

    /**
     * Repair a program a weak model FLATTENED by writing literal {@code \n}/{@code \t}
     * escape sequences instead of real line breaks (a common failure that makes Python
     * un-compilable). CONSERVATIVE: only rewrites when the code has NO real newlines but
     * does contain literal {@code \n} - so genuine multi-line code (whose strings may
     * legitimately contain {@code \n}) is left completely untouched.
     */
    static String deflatten(String code) {
        if (code == null || code.contains("\n") || !code.contains("\\n")) {
            return code;   // already multi-line, or nothing to fix
        }
        return code.replace("\\n", "\n").replace("\\t", "\t");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String code = str(parameters.get("code"));
        if (code == null || code.isBlank()) {
            return err("No program supplied. Provide the code as a fenced ```<language> block AFTER the JSON "
                    + "object (it is injected here); do not put the program inside the JSON.");
        }
        code = deflatten(code);
        String language = normLang(str(parameters.get("language")), code);
        boolean run = !"false".equalsIgnoreCase(str(parameters.get("run")));
        String filename = str(parameters.get("filename"));
        try {
            Path dir = Files.createTempDirectory("hermit-prog-");
            String ext = switch (language) {
                case "python" -> ".py";
                case "bash", "sh" -> ".sh";
                case "java" -> ".java";
                default -> ".txt";
            };
            Path file = dir.resolve(filename != null && !filename.isBlank() ? filename : "program" + ext);
            Files.writeString(file, code);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "Success");
            result.put("language", language);
            result.put("file", file.toString());

            // Syntax check.
            Exec syntax = switch (language) {
                case "python" -> run("python3", "-m", "py_compile", file.toString());
                case "bash", "sh" -> run("bash", "-n", file.toString());
                case "java" -> run("javac", "-d", dir.toString(), file.toString());
                default -> null;
            };
            boolean syntaxOk = syntax == null || syntax.exit == 0;
            result.put("syntaxOk", syntaxOk);
            if (syntax != null && !syntaxOk) {
                result.put("syntaxError", cap(syntax.err.isBlank() ? syntax.out : syntax.err));
                result.put("ran", false);
                return result;              // don't run a program that fails to compile
            }

            // Run (python/bash only; java would need a main-class/classpath dance).
            if (run && (language.equals("python") || language.equals("bash") || language.equals("sh"))) {
                Exec r = language.equals("python")
                        ? run("python3", file.toString())
                        : run("bash", file.toString());
                result.put("ran", true);
                result.put("exitCode", r.exit);
                result.put("stdout", cap(r.out));
                result.put("stderr", cap(r.err));
            } else {
                result.put("ran", false);
            }
            return result;
        } catch (Exception e) {
            return err("WriteProgram failed: " + e.getMessage());
        }
    }

    private static String normLang(String lang, String code) {
        if (lang != null && !lang.isBlank()) {
            String l = lang.trim().toLowerCase();
            if (l.equals("py")) {
                return "python";
            }
            if (l.equals("shell")) {
                return "bash";
            }
            return l;
        }
        // Infer from a shebang or obvious syntax.
        if (code.startsWith("#!")) {
                if (code.contains("python")) {
                    return "python";
                }
                if (code.contains("bash") || code.contains("/sh")) {
                    return "bash";
                }
            }
        return code.contains("public class ") ? "java" : "python";   // sensible default
    }

    // ---- process exec (arg array, no shell) --------------------------------

    private static final class Exec {
        final int exit;
        final String out;
        final String err;
        Exec(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private Exec run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread to = drain(p.getInputStream(), out);
            Thread te = drain(p.getErrorStream(), err);
            to.start();
            te.start();
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Exec(-1, out.toString(), "timed out after " + TIMEOUT_SEC + "s");
            }
            to.join(2000);
            te.join(2000);
            return new Exec(p.exitValue(), out.toString(), err.toString());
        } catch (Exception e) {
            return new Exec(-1, "", e.getMessage());
        }
    }

    private Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
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
        t.setDaemon(true);
        return t;
    }

    private Map<String, Object> err(String message) {
        return Map.of("status", "Error", "message", message);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_OUTPUT ? s : s.substring(0, MAX_OUTPUT) + "\n...(truncated)";
    }
}
