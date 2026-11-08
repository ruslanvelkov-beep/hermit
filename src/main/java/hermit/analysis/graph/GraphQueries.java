package hermit.analysis.graph;

import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.SymbolResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The query surface shared by the command line (one-shot) and the graph server (resident). */
public final class GraphQueries {

    /** One answer: the text to show and whether the query FOUND anything (drives exit code / HTTP status). */
    public record Result(String text, boolean found) {}

    private GraphQueries() {
    }

    public static Result locate(CodeGraph g, String spec) {
        StringBuilder sb = new StringBuilder();
        List<CodeGraph.MethodRef> found = g.locate(spec);
        for (CodeGraph.MethodRef m : found) {
            sb.append(m.spec()).append("  ").append(m.file()).append(':').append(m.line()).append('\n');
        }
        return new Result(sb.toString(), !found.isEmpty());
    }

    public static Result callers(CodeGraph g, String spec) {
        StringBuilder sb = new StringBuilder();
        List<CodeGraph.CallerEdge> edges = g.callerEdges(spec);
        for (CodeGraph.CallerEdge e : edges) {
            CodeGraph.CallSite s = e.site();
            sb.append(s.caller().spec()).append("  ").append(s.file()).append(':').append(s.line())
                    .append("  [").append(e.how()).append("]\n");
        }
        if (!edges.isEmpty()) {
            return new Result(sb.toString(), true);
        }
        // no resolved reverse edges (e.g. the method itself is unknown) - fall back to name matching
        List<CodeGraph.CallSite> sites = g.callersOf(spec);
        for (CodeGraph.CallSite s : sites) {
            sb.append(s.caller().spec()).append("  ").append(s.file()).append(':').append(s.line())
                    .append("  [name match]\n");
        }
        return new Result(sb.toString(), !sites.isEmpty());
    }

    public static Result hierarchy(CodeGraph g, String typeName) {
        StringBuilder sb = new StringBuilder();
        var entries = g.hierarchy().entries(typeName);
        for (var e : entries) {
            sb.append(e.fqn()).append(e.isInterface() ? "  (interface)" : "")
                    .append(e.clazz() != null ? "  [library]" : "").append('\n');
            for (var s : e.supertypes()) {
                sb.append("  extends/implements ").append(s.fqn()).append('\n');
            }
            var subs = e.subtypes();
            for (int i = 0; i < Math.min(20, subs.size()); i++) {
                sb.append("  subtype ").append(subs.get(i).fqn()).append('\n');
            }
            if (subs.size() > 20) {
                sb.append("  … ").append(subs.size() - 20).append(" more subtypes\n");
            }
        }
        return new Result(sb.toString(), !entries.isEmpty());
    }

    public static Result impls(CodeGraph g, String spec) {
        StringBuilder sb = new StringBuilder();
        List<CodeGraph.Target> targets = g.dispatchCandidatesOf(spec);
        for (CodeGraph.Target t : targets) {
            sb.append(t.typeName()).append('#').append(t.methodName())
                    .append("  [").append(t.how()).append("]  ")
                    .append(t.source() != null
                            ? t.source().file() + ":" + t.source().line()
                            : "library").append('\n');
        }
        boolean onlyDeclarations = !targets.isEmpty()
                && targets.stream().allMatch(t -> "declaration".equals(t.how()));
        if (onlyDeclarations) {
            // the shape a lost supertype edge produces
            sb.append("SUSPICIOUS: no implementation of this abstract/interface method is indexed — either the ")
                    .append("implementer is outside the indexed roots, or its supertype edge is missing ")
                    .append("(check `hierarchy <Impl>` and `validate <Impl>.java`).\n");
        }
        return new Result(sb.toString(), !targets.isEmpty());
    }

    public static Result methods(CodeGraph g, String typeName) {
        StringBuilder sb = new StringBuilder();
        var entries = g.hierarchy().entries(typeName);
        for (var e : entries) {
            sb.append(e.fqn()).append(":\n");
            g.hierarchy().visibleMethods(e).values().stream()
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .forEach(d -> sb.append("  ").append(d.name()).append('(').append(d.params())
                            .append(d.varargs() ? "+" : "").append(" args)  ← ")
                            .append(d.owner().name())
                            .append(d.reflect() != null ? "  [library]" : "").append('\n'));
        }
        return new Result(sb.toString(), !entries.isEmpty());
    }

    public static Result callees(CodeGraph g, String spec) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (CodeGraph.MethodRef m : g.locate(spec)) {
            for (CodeGraph.CallSite s : g.calleesOf(m)) {
                sb.append(s.calleeName()).append("  ").append(s.file()).append(':').append(s.line())
                        .append("  (receiver ").append(s.receiverType()).append(")\n");
                any = true;
            }
        }
        return new Result(sb.toString(), any);
    }

    public static Result reach(CodeGraph g, String from, String to, int maxDepth) {
        CodeGraph.ReachResult r = g.reach(from, to, maxDepth);
        return new Result(describeReach(r, maxDepth) + "\n", r.reachable());
    }

    static String describeReach(CodeGraph.ReachResult r, int maxDepth) {
        return r.describe(maxDepth);
    }

    /** Problems across the tree (or files whose path ends with {@code suffix}); found == PROBLEMS EXIST. */
    public static Result validate(CodeGraph g, ClasspathIndex index, String suffix) {
        StringBuilder sb = new StringBuilder();
        int problems = 0;
        for (Path f : g.sources().files()) {
            if (suffix != null && !suffix.isBlank() && !f.toString().endsWith(suffix)) {
                continue;
            }
            List<String> ps;
            try {
                // the SAME check the editors apply before committing an edit: name/arity resolution PLUS
                // type inference (mistyped members on typed receivers, wrong overload arity)
                SymbolResolver r = SymbolResolver.resolve(g.treeOf(f), index, g.sources());
                ps = new ArrayList<>(r.validate());
                ps.addAll(r.typeInference().validate(g.treeOf(f).root()));
            } catch (Throwable t) {
                sb.append(f).append("  CRASH ").append(t).append('\n');
                problems++;
                continue;
            }
            for (String p : ps) {
                sb.append(f).append("  ").append(p).append('\n');
                problems++;
            }
        }
        sb.append(problems).append(" problem(s)\n");
        return new Result(sb.toString(), problems > 0);
    }

    /** Which existing test methods exercise {@code spec}, ranked by what they share with it. */
    public static Result tests(CodeGraph g, String spec, int maxDepth) {
        TestReach.Result r = TestReach.of(g, spec, maxDepth);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TestReach.Route t : r.tests()) {
            if (shown++ >= 20) {
                sb.append("… ").append(r.tests().size() - 20).append(" more\n");
                break;
            }
            sb.append(t.render()).append('\n');
        }
        if (r.tests().isEmpty()) {
            sb.append("NO annotated test reaches ").append(spec).append(" within depth ").append(maxDepth)
                    .append(" — either nothing covers it, or its callers are outside the indexed roots\n");
        }
        if (!r.testHelpers().isEmpty()) {
            sb.append("test-source helpers on the way (not annotated tests): ")
                    .append(String.join(", ", r.testHelpers())).append('\n');
        }
        for (String n : r.notes()) {
            sb.append("note: ").append(n).append('\n');
        }
        return new Result(sb.toString(), r.exercised());
    }

    /** Which spec verbs reach {@code spec} (reverse callers → dispatch table → verb catalogue). */
    public static Result specreach(CodeGraph g, String spec, int maxDepth) {
        SpecReach.Result r = SpecReach.of(g, spec, maxDepth);
        return new Result(r.render(), r.specReachable());
    }

    /** The spec-verb catalogue, filtered by a functionality/op/verb fragment (empty = all). */
    public static Result specverbs(CodeGraph g, String query) {
        StringBuilder sb = new StringBuilder();
        List<SpecReach.Verb> vs = SpecReach.verbs(g, query);
        for (SpecReach.Verb v : vs) {
            sb.append(v.render()).append('\n');
        }
        return new Result(sb.toString(), !vs.isEmpty());
    }

    public static Result stats(CodeGraph g) {
        return new Result(g.fileCount() + " files\n", true);
    }
}
