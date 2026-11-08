package hermit.analysis.sym;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Indexes the classpath to resolve binary names, packages, and load classes at analysis time.
 */
public final class ClasspathIndex {

    /** The shared index over the JDK and this JVM's {@code java.class.path}. */
    public static ClasspathIndex system() {
        ClasspathIndex current = system;
        if (current != null) {
            return current;
        }
        synchronized (ClasspathIndex.class) {
            if (system == null) {
                Map<String, Set<String>> indexedPackages = new HashMap<>();
                scanJdk(indexedPackages);
                String classPath = System.getProperty("java.class.path", "");
                for (String item : classPath.split(Pattern.quote(File.pathSeparator))) {
                    if (!item.isBlank()) {
                        scanEntry(Path.of(item), indexedPackages);
                    }
                }
                system = new ClasspathIndex(indexedPackages, ClasspathIndex.class.getClassLoader());
            }
            return system;
        }
    }

    /**
     * An index that also covers {@code extraEntries} (class directories and jars, such as a project's build output).
     */
    public static ClasspathIndex withExtra(List<Path> extraEntries) {
        ClasspathIndex base = system();
        Map<String, Set<String>> indexedPackages = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : base.packages.entrySet()) {
            indexedPackages.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        List<URL> urls = new ArrayList<>();
        if (extraEntries != null) {
            for (Path entry : extraEntries) {
                scanEntry(entry, indexedPackages);
                try {
                    urls.add(entry.toUri().toURL());
                } catch (IOException ignored) {
                    continue;
                }
            }
        }
        ClassLoader extraLoader = new URLClassLoader(urls.toArray(new URL[0]), base.loader);
        return new ClasspathIndex(indexedPackages, extraLoader);
    }

    /**
     * Top-level simple class names in {@code pkg} (the expansion of {@code import pkg.*}), as a read-only view: a
     * caller cannot change the index through it.
     */
    public Set<String> classesIn(String pkg) {
        Set<String> names = packages.get(pkg);
        return names == null ? Set.of() : Collections.unmodifiableSet(names);
    }

    /**
     * Every fully qualified top-level class named {@code simpleName} anywhere the index can see (JDK modules, classpath
     * and extras), sorted. A unique candidate can be imported without guessing.
     */
    public List<String> fqnsOf(String simpleName) {
        Set<String> names = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : packages.entrySet()) {
            if (entry.getValue().contains(simpleName)) {
                names.add(entry.getKey().isEmpty() ? simpleName : entry.getKey() + "." + simpleName);
            }
        }
        return new ArrayList<>(names);
    }

    /** True if {@code pkg} is a known package, or the prefix of one. */
    public boolean hasPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (packages.containsKey(pkg)) {
            return true;
        }
        String prefix = pkg.isEmpty() ? "" : pkg + ".";
        for (String known : packages.keySet()) {
            if (!prefix.isEmpty() && known.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code name} is the first segment of a known package ({@code java}, {@code com}). */
    public boolean isPackageRoot(String name) {
        return name != null && !name.isEmpty() && name.indexOf('.') < 0 && hasPackage(name);
    }

    /**
     * Resolves a dotted name, possibly naming a nested class ({@code a.b.Outer.Inner}), to its binary name ({@code
     * a.b.Outer$Inner}), or null when no such class exists anywhere the index can see.
     */
    public String binaryNameOf(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return null;
        }
        String[] parts = dotted.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
        }
        for (int packageParts = 0; packageParts < parts.length; packageParts++) {
            StringBuilder packageName = new StringBuilder();
            for (int i = 0; i < packageParts; i++) {
                if (i > 0) {
                    packageName.append('.');
                }
                packageName.append(parts[i]);
            }
            String pkg = packageName.toString();
            if (classesIn(pkg).contains(parts[packageParts])) {
                StringBuilder binaryName = new StringBuilder();
                if (!pkg.isEmpty()) {
                    binaryName.append(pkg).append('.');
                }
                binaryName.append(parts[packageParts]);
                for (int i = packageParts + 1; i < parts.length; i++) {
                    binaryName.append('$').append(parts[i]);
                }
                return binaryName.toString();
            }
        }
        return null;
    }

    /** True if a class of that dotted name exists anywhere the index can see. */
    public boolean hasClass(String dotted) {
        return binaryNameOf(dotted) != null;
    }

    /** Loads the class for a dotted name without initializing it; null if it cannot be loaded for any reason. */
    public Class<?> load(String dotted) {
        String binaryName = binaryNameOf(dotted);
        if (binaryName == null) {
            return null;
        }
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    // Maps each package to its top-level class names.
    private final Map<String, Set<String>> packages;

    // Loads classes represented by this index.
    private final ClassLoader loader;

    // Caches the process-wide JDK and classpath index.
    private static volatile ClasspathIndex system;

    // Creates an index from its package map and class loader.
    private ClasspathIndex(Map<String, Set<String>> packages, ClassLoader loader) {
        this.packages = packages;
        this.loader = loader;
    }

    // Records a top-level class file in its package.
    private static void add(String classFile, Map<String, Set<String>> into) {
        String name = classFile.replace('\\', '/');
        if (!name.endsWith(".class") || name.indexOf('$') >= 0 || name.endsWith("module-info.class")
                || name.endsWith("package-info.class")) {
            return;
        }
        int slash = name.lastIndexOf('/');
        String pkg = slash < 0 ? "" : name.substring(0, slash).replace('/', '.');
        String simpleName = name.substring(slash + 1, name.length() - ".class".length());
        into.computeIfAbsent(pkg, ignored -> new TreeSet<>()).add(simpleName);
    }

    // Scans all JDK modules and merges their classes only after a successful scan.
    private static void scanJdk(Map<String, Set<String>> into) {
        Map<String, Set<String>> found = new HashMap<>();
        try {
            FileSystem fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modules = fileSystem.getPath("/modules");
            try (Stream<Path> modulePaths = Files.list(modules)) {
                for (Path module : (Iterable<Path>) modulePaths::iterator) {
                    try (Stream<Path> files = Files.walk(module)) {
                        files.filter(Files::isRegularFile)
                                .forEach(path -> add(module.relativize(path).toString(), found));
                    }
                }
            }
            for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
                into.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>()).addAll(entry.getValue());
            }
        } catch (IOException | RuntimeException ignored) {
            return;
        }
    }

    // Scans a directory or jar and merges its classes only after a successful scan.
    private static void scanEntry(Path entry, Map<String, Set<String>> into) {
        Map<String, Set<String>> found = new HashMap<>();
        try {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> files = Files.walk(entry)) {
                    files.filter(Files::isRegularFile)
                            .forEach(path -> add(entry.relativize(path).toString(), found));
                }
            } else if (entry.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    jar.stream().filter(item -> !item.isDirectory()).forEach(item -> add(item.getName(), found));
                }
            } else {
                return;
            }
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        for (Map.Entry<String, Set<String>> item : found.entrySet()) {
            into.computeIfAbsent(item.getKey(), ignored -> new TreeSet<>()).addAll(item.getValue());
        }
    }
}
