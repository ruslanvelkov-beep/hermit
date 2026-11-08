package hermit.tool.files;

import hermit.tool.api.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The registry's first REAL tool: classifies files in a directory into
 * VERSION_CONTROLLED / GENERATED / USER_CREATED / LEFTOVER_UNKNOWN using
 * git (ls-files + check-ignore) when available, falling back to path and
 * modification-time heuristics.
 */
public class FileClassifier implements Tool {

    // Java package trees easily exceed shallow depths (src/main/java/com/...),
    // so the walk must go deep; the per-category path cap bounds output size.
    private static final int MAX_DEPTH = 12;
    private static final int MAX_PATHS_PER_CATEGORY = 50;
    private static final Set<String> GENERATED_DIRS =
            Set.of("build", "target", "out", ".gradle", "node_modules");
    private static final List<String> GENERATED_SUFFIXES =
            List.of(".class", ".jar", ".log", ".tmp", ".tar.gz");

    @Override
    public String getDocumentation() {
        return "FileClassifier: classifies every file under a directory into VERSION_CONTROLLED "
                + "(tracked by git), GENERATED (git-ignored or build artifacts), USER_CREATED "
                + "(untracked, recently modified) or LEFTOVER_UNKNOWN (untracked, old). "
                + "Required parameter: directoryPath.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String directoryPath = (String) parameters.get("directoryPath");
        if (directoryPath == null || directoryPath.isEmpty()) {
            return Map.of("status", "Error", "message", "directoryPath is required.");
        }
        Path root = Paths.get(directoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Map.of("status", "Error", "message", "Not a directory: " + root);
        }

        System.out.println("--- Executing File Classifier on: " + root + " ---");
        try {
            Map<String, List<String>> classified = classify(root);
            StringBuilder summary = new StringBuilder();
            classified.forEach((category, paths) -> summary
                    .append(category).append("=").append(paths.size()).append(" "));

            Map<String, Object> capped = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : classified.entrySet()) {
                List<String> paths = e.getValue();
                if (paths.size() > MAX_PATHS_PER_CATEGORY) {
                    List<String> head = new ArrayList<>(paths.subList(0, MAX_PATHS_PER_CATEGORY));
                    head.add("(+" + (paths.size() - MAX_PATHS_PER_CATEGORY) + " more)");
                    capped.put(e.getKey(), head);
                } else {
                    capped.put(e.getKey(), paths);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "Success");
            result.put("classified", capped);
            result.put("summary", summary.toString().trim());
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Classification failed: " + e.getMessage());
        }
    }

    private Map<String, List<String>> classify(Path root) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String category : List.of("VERSION_CONTROLLED", "GENERATED", "USER_CREATED", "LEFTOVER_UNKNOWN")) {
            result.put(category, new ArrayList<>());
        }

        Set<String> tracked = gitLines(root, null, "ls-files");
        boolean isGitRepo = tracked != null;

        // PRUNE heavy generated/VCS subtrees at the directory level - walking into build/ etc. on a big
        // multi-module repo enumerates hundreds of thousands of files and effectively hangs the tool.
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path dir, BasicFileAttributes a) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return (name.equals(".git") || GENERATED_DIRS.contains(name))
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(
                            Path f, BasicFileAttributes a) {
                        if (a.isRegularFile()) {
                            files.add(f);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });

        // Batch git check-ignore over all non-tracked files (one process).
        Set<String> ignored = new HashSet<>();
        if (isGitRepo) {
            List<String> candidates = new ArrayList<>();
            for (Path f : files) {
                String rel = root.relativize(f).toString();
                if (!tracked.contains(rel)) {
                    candidates.add(rel);
                }
            }
            Set<String> out = gitLines(root, candidates, "check-ignore", "--stdin");
            if (out != null) {
                ignored = out;
            }
        }

        Instant recentThreshold = Instant.now().minus(24, ChronoUnit.HOURS);
        for (Path f : files) {
            String rel = root.relativize(f).toString();
            if (isGitRepo && tracked.contains(rel)) {
                result.get("VERSION_CONTROLLED").add(rel);
            } else if (ignored.contains(rel) || looksGenerated(root.relativize(f))) {
                result.get("GENERATED").add(rel);
            } else if (Files.getLastModifiedTime(f).toInstant().isAfter(recentThreshold)) {
                result.get("USER_CREATED").add(rel);
            } else {
                result.get("LEFTOVER_UNKNOWN").add(rel);
            }
        }
        return result;
    }

    private boolean looksGenerated(Path relative) {
        if (containsAnySegment(relative, GENERATED_DIRS)) {
            return true;
        }
        String name = relative.getFileName().toString();
        return GENERATED_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private boolean containsSegment(Path relative, String segment) {
        for (Path part : relative) {
            if (part.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnySegment(Path relative, Set<String> segments) {
        for (Path part : relative) {
            if (segments.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a git command in the given directory and returns its stdout lines,
     * or null if git fails (e.g. not a repository). When stdinLines is given,
     * they are written to the process's stdin (for check-ignore --stdin,
     * which exits 1 when nothing matches - that still counts as success).
     */
    private Set<String> gitLines(Path dir, List<String> stdinLines, String... gitArgs) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
            cmd.addAll(List.of(gitArgs));
            Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();

            if (stdinLines != null) {
                try (Writer w = new OutputStreamWriter(process.getOutputStream())) {
                    for (String line : stdinLines) {
                        w.write(line + "\n");
                    }
                }
            } else {
                process.getOutputStream().close();
            }

            Set<String> lines = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            int exit = process.waitFor();
            boolean checkIgnore = String.join(" ", gitArgs).startsWith("check-ignore");
            if (exit == 0 || (checkIgnore && exit == 1)) {
                return lines;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
