package hermit.analysis.sym;

import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import hermit.analysis.tree.Node;

/** A project-wide index of .java files, mapping packages and simple names to source declarations. */
public final class SourceIndex {
    public static SourceIndex build(List<Path> roots, ClasspathIndex classpath) throws IOException {
        SourceIndex si = new SourceIndex(classpath);
        for (Path root : roots) {
            if (Files.isRegularFile(root) && root.toString().endsWith(".java")) {
                si.register(root);
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                List<Path> files = s.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> {
                            String path = p.toString();
                            return !path.contains("/build/") || path.contains("/build/generated/");
                        })
                        .sorted()
                        .toList();
                for (Path f : files) {
                    si.register(f);
                }
            }
        }
        return si;
    }

    public void refresh(Path file) {
        Path key = file.toAbsolutePath().normalize();
        trees.remove(key);
        resolvers.remove(key);
        for (Map<String, Entry> perPkg : byPackage.values()) {
            perPkg.values().removeIf(e -> e.file().equals(key));
        }
        if (Files.isRegularFile(key)) {
            register(key);
        }
    }

    public boolean hasPackage(String pkg) {
        return byPackage.containsKey(pkg);
    }

    public Set<String> typesInPackage(String pkg) {
        Map<String, Entry> m = byPackage.get(pkg);
        return m == null ? Set.of() : Set.copyOf(m.keySet());
    }

    public List<String> fqnsOf(String simpleName) {
        TreeSet<String> out = new TreeSet<>();
        for (Map.Entry<String, Map<String, Entry>> e : byPackage.entrySet()) {
            if (e.getValue().containsKey(simpleName)) {
                String pkg = e.getKey();
                out.add(pkg.isEmpty() ? simpleName : pkg + "." + simpleName);
            }
        }
        return new ArrayList<>(out);
    }

    public Symbol resolveType(String pkg, String simpleName) {
        Map<String, Entry> m = byPackage.get(pkg);
        Entry e = m == null ? null : m.get(simpleName);
        if (e == null) {
            return null;
        }
        if (scopeOf(e.decl()) == null) {
            resolverFor(e.file());
        }
        if (scopeOf(e.decl()) != null) {
            return Symbol.type(simpleName, e.decl());
        }
        return Symbol.typeByName(pkg.isEmpty() ? simpleName : pkg + "." + simpleName);
    }

    public SymbolResolver resolverFor(Path file) {
        Path key = file.toAbsolutePath().normalize();
        SymbolResolver cached = resolvers.get(key);
        if (cached != null || inProgress.contains(key)) {
            return cached;
        }
        ParseTree tree = trees.get(key);
        if (tree == null) {
            return null;
        }
        inProgress.add(key);
        try {
            resolvers.put(key, SymbolResolver.resolve(tree, classpath, this));
        } catch (Throwable t) {
            // Resolution failure leaves the file registered but member-less.
        } finally {
            inProgress.remove(key);
        }
        return resolvers.get(key);
    }

    public Collection<Path> files() {
        return trees.keySet();
    }

    public ParseTree treeOf(Path file) {
        return trees.get(file.toAbsolutePath().normalize());
    }

    public int fileCount() {
        return trees.size();
    }

    // An indexed source declaration and the file that contains it.
    private record Entry(Path file, Node decl) {}

    // The classpath used to resolve declarations in indexed files.
    private final ClasspathIndex classpath;

    // Parsed source trees, keyed by normalized absolute path.
    private final Map<Path, ParseTree> trees = new LinkedHashMap<>();

    // Source declarations indexed by package and simple name.
    private final Map<String, Map<String, Entry>> byPackage = new HashMap<>();

    // Lazily-created resolvers, keyed by normalized absolute path.
    private final Map<Path, SymbolResolver> resolvers = new HashMap<>();

    // Files whose resolvers are currently being constructed.
    private final Set<Path> inProgress = new HashSet<>();

    // Scopes recorded by resolvers for declarations in any indexed file.
    private final Map<Node, Scope> scopes = new IdentityHashMap<>();

    // Create an index backed by the supplied classpath.
    private SourceIndex(ClasspathIndex classpath) {
        this.classpath = classpath;
    }

    // Parse and register one source file, skipping files that cannot be parsed.
    private void register(Path file) {
        Path key = file.toAbsolutePath().normalize();
        try {
            ParseTree tree = ParseTree.parse(Files.readString(key));
            trees.put(key, tree);
            String pkg = tree.packageName();
            for (Node decl : tree.typeDeclarations()) {
                if (decl.name() != null) {
                    byPackage.computeIfAbsent(pkg, k -> new HashMap<>())
                            .putIfAbsent(decl.name(), new Entry(key, decl));
                }
            }
        } catch (Throwable t) {
            // Unparseable mid-edit files are omitted until they recover.
        }
    }

    // Look up a declaration's scope recorded by its resolver.
    Scope scopeOf(Node owner) {
        return scopes.get(owner);
    }

    // Record a declaration's scope for lookups across indexed files.
    void recordScope(Node owner, Scope scope) {
        scopes.put(owner, scope);
    }
}
