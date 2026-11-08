package hermit.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which gradle project owns a source file. Tools defaulted the module to {@code :app}, so a test in
 * another module compiled/ran the wrong project (silently: "no tests found", 0 records). The owning module
 * directory is the nearest ancestor holding a {@code build.gradle(.kts)}; its project name is the
 * {@code artifact} mapped for that directory in the root {@code settings.gradle.kts}
 * ({@code module("library-loans-impl") { artifact = "app-loans-impl" }}), else the directory name.
 */
public final class GradleModules {

    private static final Pattern MODULE_ARTIFACT = Pattern.compile(
            "module\\(\"([^\"]+)\"\\)\\s*\\{[^}]*?artifact\\s*=\\s*\"([^\"]+)\"");

    private GradleModules() {
    }

    public static String projectFor(Path projectDir, Path file) {
        if (projectDir == null || file == null) {
            return null;
        }
        Path root = projectDir.toAbsolutePath().normalize();
        Path f = file.isAbsolute() ? file.toAbsolutePath().normalize() : root.resolve(file).normalize();
        Path dir = Files.isDirectory(f) ? f : f.getParent();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            if (Files.isRegularFile(dir.resolve("build.gradle.kts"))
                    || Files.isRegularFile(dir.resolve("build.gradle"))) {
                String dirName = dir.getFileName().toString();
                String artifact = artifactsOf(root).get(dirName);
                return ":" + (artifact != null ? artifact : dirName);
            }
            dir = dir.getParent();
        }
        // a single-project build (build.gradle at the root, sources under root/src) is the root
        // project ":", whose tasks are ":compileJava" and ":test"; before, such a file had no module
        // and the code generator compiled every source of the repository with plain javac and its
        // own classpath
        if (dir != null && dir.equals(root)
                && (Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("build.gradle")))) {
            return ":";
        }
        return null;
    }

    public static String task(String module, String task) {
        if (module == null || module.isBlank()) {
            return task;
        }
        return module.equals(":") ? ":" + task : module + ":" + task;
    }

    static Map<String, String> artifactsOf(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : new String[] {"settings.gradle.kts", "settings.gradle"}) {
            Path settings = root.resolve(name);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            try {
                Matcher m = MODULE_ARTIFACT.matcher(Files.readString(settings));
                while (m.find()) {
                    out.put(m.group(1), m.group(2));
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
        return out;
    }

    public static String resolve(String explicit, Path projectDir, Path file, String fallback) {
        String derived = projectFor(projectDir, file);
        if (explicit != null && !explicit.isBlank()) {
            String e = explicit.trim();
            // the planner copied ':app' from the tool's example into a single-project build; an explicit
            // module the project does not have yields to the one its file belongs to
            if (derived != null && !e.equals(derived) && !projectExists(projectDir, e)) {
                return derived;
            }
            return e;
        }
        return derived != null ? derived : fallback;
    }

    static boolean projectExists(Path projectDir, String module) {
        if (projectDir == null || module == null) {
            return false;
        }
        Path root = projectDir.toAbsolutePath().normalize();
        if (module.equals(":")) {
            return Files.isRegularFile(root.resolve("build.gradle.kts"))
                    || Files.isRegularFile(root.resolve("build.gradle"));
        }
        String name = module.substring(module.lastIndexOf(':') + 1);
        if (artifactsOf(root).containsValue(name) || artifactsOf(root).containsKey(name)) {
            return true;
        }
        try (Stream<Path> dirs = Files.walk(root, 3)) {
            return dirs.anyMatch(d -> d.getFileName() != null && d.getFileName().toString().equals(name)
                    && (Files.isRegularFile(d.resolve("build.gradle.kts"))
                    || Files.isRegularFile(d.resolve("build.gradle"))));
        } catch (IOException e) {
            return true;   // cannot tell: keep the caller's choice
        }
    }
}
