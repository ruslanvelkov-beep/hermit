package hermit.tool.files;

import hermit.tool.api.Tool;
import hermit.workspace.RemoteExec;
import hermit.workspace.RemoteExec.Spec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Locate a source file by name anywhere under the project, WITHOUT the caller having to know or
 * guess the directory layout. This exists because a small driver model repeatedly assumes a flat
 * {@code <repo>/src/main/java/...} layout and greps/finds a path that does not exist (a big
 * multi-module repo keeps a class at e.g. {@code order-service/order-app/src/main/java/.../steps/}).
 *
 * <p>Give it a bare file name (optionally with a {@code *} glob) and it returns the real relative
 * and absolute paths, searching from the project root - {@code root} param, else
 * {@code -Dhermit.projectDir}, else the current directory - while pruning build/VCS/IDE dirs so it
 * stays fast and doesn't return generated copies. Read-only.
 */
public class LocateFileTool implements Tool {

    private static final Set<String> PRUNE =
            Set.of("build", ".git", ".gradle", "node_modules", ".idea", "target", "out", ".mvn");
    private static final int DEFAULT_MAX = 50;
    private static final long WALK_BUDGET_MS = 20_000;

    @Override
    public String getDocumentation() {
        return "LocateFile: find a source file by name ANYWHERE in the project when you do not know "
        + "its exact path (a multi-module repo does NOT keep everything under a flat "
        + "src/main/java — use this BEFORE grepping or reading a path you are unsure of). "
        + "Parameters: {\"name\": \"<file name, e.g. OrderTotals.java, may contain * glob>\", "
        + "\"root\": \"<dir to search from>\" (optional; defaults to the project dir), "
        + "\"maxResults\": <int> (optional, default " + DEFAULT_MAX + "), \"remote\": "
        + "\"user@host:/abs/path\" (optional - set ONLY when told to locate files on a remote "
        + "machine over SSH; searches THAT host under that path)}. Returns the matching relative "
        + "+ absolute paths. Build/.git/.idea dirs are skipped.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String name = str(parameters.get("name"));
        if (name == null || name.isBlank()) {
            return err("name is required (e.g. \"OrderTotals.java\").");
        }
        int maxResults = intParam(parameters.get("maxResults"), DEFAULT_MAX);
        // Remote mode: the files live on another host - locate them there with `find` over SSH.
        String rootParam = str(parameters.get("root"));
        String fallbackRoot = (rootParam != null && !rootParam.isBlank())
                ? rootParam : System.getProperty("hermit.projectDir");
        Spec spec = RemoteExec.spec(parameters, fallbackRoot);
        if (RemoteExec.isRemote(spec)) {
            return locateRemote(spec, name.trim(), maxResults);
        }
        Path root = resolveRoot(rootParam);
        if (!Files.isDirectory(root)) {
            return err("search root is not a directory: " + root);
        }
        int max = maxResults;
        final Pattern matcher = globToRegex(name.trim());
        List<Path> hits = new ArrayList<>();
        long deadline = System.currentTimeMillis() + WALK_BUDGET_MS;
        boolean[] timedOut = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    String dn = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return PRUNE.contains(dn) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (System.currentTimeMillis() > deadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Path fn = file.getFileName();
                    if (fn != null && matcher.matcher(fn.toString()).matches()) {
                        hits.add(file);
                        if (hits.size() >= max) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // unreadable entry - skip it
                }
            });
        } catch (IOException e) {
            return err("walk failed under " + root + ": " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "Success");
        out.put("action", "locate");
        out.put("name", name);
        out.put("root", root.toString());
        out.put("count", hits.size());
        List<Map<String, String>> matches = new ArrayList<>();
        for (Path p : hits) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("relative", root.relativize(p).toString());
            m.put("absolute", p.toString());
            matches.add(m);
        }
        out.put("matches", matches);
        if (hits.isEmpty()) {
            out.put("note", "no file named '" + name + "' under " + root
                    + " — check the name/spelling, or widen with a * glob.");
        } else if (timedOut[0]) {
            out.put("note", "search budget elapsed; results may be partial.");
        }
        return out;
    }

    /** Locate files on a remote host with `find` over SSH (mirrors the local walk: same pruned dirs). */
    private Map<String, Object> locateRemote(Spec spec, String glob, int max) {
        String root = (spec.dir() != null && !spec.dir().isBlank()) ? spec.dir() : ".";
        List<String> args = new ArrayList<>(List.of(
                "find", root, "-type", "d", "(",
                "-name", "build", "-o", "-name", ".git", "-o", "-name", ".gradle", "-o",
                "-name", "node_modules", "-o", "-name", ".idea", "-o", "-name", "target", "-o",
                "-name", "out", "-o", "-name", ".mvn", ")", "-prune",
                "-o", "-type", "f", "-name", glob, "-print"));
        try {
            Process p = RemoteExec.command(args, new Spec(spec.host(), null))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<Path> hits = new ArrayList<>();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null && hits.size() < max) {
                    if (!line.isBlank()) {
                        hits.add(Paths.get(line.trim()));
                    }
                }
            }
            p.waitFor(WALK_BUDGET_MS + 5000, TimeUnit.MILLISECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "Success");
            out.put("action", "locate");
            out.put("name", glob);
            out.put("root", spec.host() + ":" + root);
            out.put("count", hits.size());
            List<Map<String, String>> matches = new ArrayList<>();
            String prefix = root.endsWith("/") ? root : root + "/";
            for (Path hit : hits) {
                String abs = hit.toString();
                Map<String, String> m = new LinkedHashMap<>();
                m.put("relative", abs.startsWith(prefix) ? abs.substring(prefix.length()) : abs);
                m.put("absolute", abs);
                matches.add(m);
            }
            out.put("matches", matches);
            if (hits.isEmpty()) {
                out.put("note", "no file named '" + glob + "' under " + root + " on " + spec.host()
                        + " — check the name/spelling, or widen with a * glob.");
            }
            return out;
        } catch (Exception e) {
            return err("remote find failed on " + spec.host() + ": " + e.getMessage());
        }
    }

    private static Path resolveRoot(String rootParam) {
        String r = rootParam;
        if (r == null || r.isBlank()) {
            r = System.getProperty("hermit.projectDir");
        }
        if (r == null || r.isBlank()) {
            r = System.getProperty("user.dir");
        }
        return Paths.get(r).toAbsolutePath().normalize();
    }

    /** Translate a bare name / simple {@code *} glob into an anchored regex over the file name. */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile("^" + sb + "$");
    }

    private static Map<String, Object> err(String message) {
        return Map.of("status", "Error", "message", message);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intParam(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
