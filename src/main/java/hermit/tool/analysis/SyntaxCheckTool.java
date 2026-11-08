package hermit.tool.analysis;

import hermit.tool.api.Tool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the syntax of a generated program so the agent never answers with code
 * it hasn't verified. Python via `python3 -m py_compile`, Java via `javac` -
 * both to a throwaway temp dir. Returns whether it is syntactically valid and,
 * if not, the compiler's diagnostics (which the agent can feed back to itself).
 */
public class SyntaxCheckTool implements Tool {

    private static final int TIMEOUT_SEC = 60;
    // First top-level Java type - javac needs the .java filename to match a public type.
    private static final Pattern JAVA_TYPE =
            Pattern.compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    @Override
    public String getDocumentation() {
        return "SyntaxCheck: verifies a program BEFORE you answer (Python via py_compile, Java via "
                + "javac). Compile IN PROJECT CONTEXT by passing classpath so the code is checked "
                + "against the project's own types and built binaries. Parameters: "
                + "{\"language\": \"python\"|\"java\", \"code\": \"<source>\", \"classpath\": \"<java "
                + "classpath, optional>\"}. Returns valid=true/false and diagnostics when invalid.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String language = (String) parameters.get("language");
        String code = (String) parameters.get("code");
        if (language == null || code == null) {
            return Map.of("status", "Error", "message", "language and code are required.");
        }
        String lang = language.trim().toLowerCase();

        Path work = null;
        try {
            work = Files.createTempDirectory("hermit-syntax");
            CliResult r;
            if (lang.startsWith("py")) {
                Path f = work.resolve("snippet.py");
                Files.writeString(f, code);
                r = run(work, TIMEOUT_SEC, "python3", "-m", "py_compile", f.toString());
            } else if (lang.equals("java")) {
                Matcher m = JAVA_TYPE.matcher(code);
                String cls = m.find() ? m.group(1) : "Snippet";
                Path f = work.resolve(cls + ".java");
                Files.writeString(f, code);
                String classpath = (String) parameters.get("classpath");
                if (classpath != null && !classpath.isBlank()) {
                    // Compile in project context: the project's classes + deps on the classpath.
                    r = run(work, TIMEOUT_SEC, "javac", "-cp", classpath, "-d", work.toString(), f.toString());
                } else {
                    r = run(work, TIMEOUT_SEC, "javac", "-d", work.toString(), f.toString());
                }
            } else {
                return Map.of("status", "Error", "message", "unsupported language: " + language
                        + " (use python or java)");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            boolean valid = r.exitCode == 0;
            result.put("status", "Success");
            result.put("language", lang);
            result.put("valid", valid);
            if (!valid) {
                result.put("diagnostics", r.tail());
            }
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "syntax check failed: " + e.getMessage());
        } finally {
            deleteQuietly(work);
        }
    }

    private record CliResult(int exitCode, String output) {
        String tail() {
            return output.length() > 1500 ? "..." + output.substring(output.length() - 1500) : output;
        }
    }

    private CliResult run(Path dir, int timeoutSec, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new CliResult(-1, "TIMEOUT\n" + out);
        }
        return new CliResult(p.exitValue(), out.toString());
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
