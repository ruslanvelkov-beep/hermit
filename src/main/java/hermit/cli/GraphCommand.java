package hermit.cli;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.GraphQueries;
import hermit.analysis.sym.ClasspathIndex;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line front end for the call graph, without an agent, a model or the network: parse a source tree, answer a
 * structural question, exit. Made for other tools and scripts to call.
 *
 * <pre>
 *   java -cp build/classes/java/main hermit.cli.GraphCommand \
 *       --root &lt;srcDir&gt; [--root &lt;more&gt;] [--classes &lt;dir|jar&gt;[,&lt;more&gt;]] \
 *       &lt;command&gt; &lt;args&gt;
 * </pre>
 *
 * Commands: see {@link #USAGE}. Results go to stdout (one finding per line); timings and diagnostics go to stderr.
 * Exit 0 = found/ok, 1 = not found/problems, 2 = usage error.
 */
public final class GraphCommand {

    /** The commands this front end dispatches; each is documented in {@link #USAGE}. */
    static final List<String> COMMANDS = List.of("locate", "callers", "callees", "reach", "hierarchy", "impls", "tests",
            "methods", "validate", "stats");
    static final String USAGE = """
    usage: GraphCommand --root <srcDir> [--root <more>] [--classes <dir|jar>[,<more>]] <command>
    commands:
      locate    <name|Type#method>                    find method declarations
      callers   <Type#method>                         RESOLVED call sites of the method
      callees   <Type#method>                         calls made inside the method
      reach     <from> <to> [maxDepth=12]             BFS path (polymorphic hops annotated)
      hierarchy <Type>                                supertypes and subtypes
      impls     <Type#method>                         dispatch candidates (overrides/impls)
      tests     <Type#method> [maxDepth=10]           existing TEST methods that exercise it (ranked)
      methods   <Type>                                visible methods (declared + inherited)
      validate  [<file-suffix>]                       provable name/member/arity problems
      stats                                           file count""";

    private GraphCommand() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<Path> classes = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> roots.add(Path.of(args[++i]));
                case "--classes" -> {
                    for (String c : args[++i].split(",")) {
                        classes.add(Path.of(c));
                    }
                }
                default -> rest.add(args[i]);
            }
        }
        if (roots.isEmpty() || rest.isEmpty()) {
            err.println(USAGE);
            return 2;
        }
        String command = rest.get(0);
        List<String> a = rest.subList(1, rest.size());

        long t0 = System.nanoTime();
        ClasspathIndex index = classes.isEmpty() ? ClasspathIndex.system()
                : ClasspathIndex.withExtra(classes);
        CodeGraph g = CodeGraph.build(roots, index);
        err.printf("[graph] %d files indexed in %dms%n", g.fileCount(),
                (System.nanoTime() - t0) / 1_000_000);

        GraphQueries.Result r;
        switch (command) {
            case "locate" -> {
                requireArgs(a, 1, "locate <name|Type#method>");
                r = GraphQueries.locate(g, a.get(0));
            }
            case "callers" -> {
                requireArgs(a, 1, "callers <Type#method>");
                r = GraphQueries.callers(g, a.get(0));
            }
            case "callees" -> {
                requireArgs(a, 1, "callees <Type#method>");
                r = GraphQueries.callees(g, a.get(0));
            }
            case "reach" -> {
                requireArgs(a, 2, "reach <fromType#method> <toType#method> [maxDepth]");
                int depth = a.size() > 2 ? Integer.parseInt(a.get(2)) : 12;
                r = GraphQueries.reach(g, a.get(0), a.get(1), depth);
            }
            case "validate" -> {
                GraphQueries.Result v = GraphQueries.validate(g, index,
                        a.isEmpty() ? null : a.get(0));
                out.print(v.text());
                return v.found() ? 1 : 0;   // found == problems exist
            }
            case "hierarchy" -> {
                requireArgs(a, 1, "hierarchy <Type>");
                r = GraphQueries.hierarchy(g, a.get(0));
            }
            case "impls" -> {
                requireArgs(a, 1, "impls <Type#method>");
                r = GraphQueries.impls(g, a.get(0));
            }
            case "methods" -> {
                requireArgs(a, 1, "methods <Type>");
                r = GraphQueries.methods(g, a.get(0));
            }
            case "tests" -> {
                requireArgs(a, 1, "tests <Type#method> [maxDepth]");
                int depth = a.size() > 1 ? Integer.parseInt(a.get(1)) : 10;
                r = GraphQueries.tests(g, a.get(0), depth);
            }
            case "stats" -> {
                r = GraphQueries.stats(g);
            }
            default -> {
                err.println(USAGE);
                return 2;
            }
        }
        out.print(r.text());
        return r.found() ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    private static void requireArgs(List<String> a, int n, String usage) {
        if (a.size() < n) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }
}
