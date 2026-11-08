package hermit.generate;

import hermit.analysis.edit.EditCommitException;
import hermit.analysis.edit.TreeEditor;
import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.SpecConventions;
import hermit.analysis.graph.SpecReach;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import hermit.tool.analysis.TreeEditTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec-test composition: assemble a spec test method (its annotation and runner are the project's
 * {@link SpecConventions}) from spec verbs DIRECTLY IN THE PARSE TREE of a suite class - the member is parsed,
 * the static imports for every verb/constant it uses are resolved from the project's own sources, and the whole
 * edited file is re-validated (an unknown verb or a mistyped chain method is rejected with the exact
 * problem). A test is refused as VACUOUS when none of its verbs dispatches to a handler that reaches the
 * production method the test is meant to exercise (the {@link SpecReach} dispatch tables decide).
 */
public final class SpecTestComposer {

    public record Composition(boolean ok, String rendered, String methodText, List<String> problems,
            List<String> imports, Map<String, String> verbDispatch, List<String> reaching,
            List<String> unreached) {}

    private static final Pattern CALL = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(");
    /** The first call of an op - the verb it starts with. */
    private static final Pattern HEAD_CALL = Pattern.compile("^\\s*([a-z][A-Za-z0-9_]*)\\s*\\(");
    /** `name=value` inside an argument list - Python/Kotlin style, never Java. */
    private static final Pattern NAMED_ARGUMENT = Pattern.compile("\\(\\s*[a-zA-Z_]\\w*\\s*=(?!=)");
    private static final Set<String> HELPER_HEADS = Set.of("hex", "bytes", "asList", "of", "valueOf", "format");
    private static final Pattern CONSTANT = Pattern.compile("(?<![A-Za-z0-9_.\"])([A-Z][A-Z0-9_]{2,})(?![A-Za-z0-9_(\"])");

    private SpecTestComposer() {
    }

    /** The source of a fresh suite class ready to receive composed tests. */
    public static String newSuiteSource(String pkg, String className) {
        SpecConventions conventions = SpecConventions.load();
        return (conventions.suiteHeader().isEmpty() ? "" : conventions.suiteHeader() + "\n")
                + (pkg == null || pkg.isBlank() ? "" : "package " + pkg + ";\n\n")
                + "import static " + conventions.runnerImport() + ";\n\n"
                + "import " + conventions.testAnnotationImport() + ";\n"
                + "import java.util.stream.Stream;\n"
                + "import org.junit.jupiter.api.DynamicTest;\n\n"
                + "class " + className + " {\n}\n";
    }

    /** The method text for {@code testName} over {@code ops} (each op is one spec-operation expression). */
    public static String methodText(String testName, List<String> ops) {
        return methodText(testName, ops, List.of());
    }

    /**
     * A spec that asserts a COMPUTED value (a hex digest of the bytes the test sends) needs a local before the runner
     * call - {@code expectedDigest} stayed an "unknown variable" for want of one. {@code declares} are
     * {@code name = expression} pairs emitted as {@code final var} locals ahead of the return.
     */
    public static String methodText(String testName, List<String> ops, List<String> declares) {
        SpecConventions conventions = SpecConventions.load();
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(conventions.testAnnotation()).append("\nfinal Stream<DynamicTest> ").append(testName)
                .append("() {\n");
        for (String d : declares) {
            String decl = d.strip().replaceAll(";$", "");
            if (!decl.isEmpty()) {
                sb.append("    final var ").append(decl.replaceFirst("^(?:final\\s+)?(?:var\\s+)?", "")).append(";\n");
            }
        }
        sb.append("    return ").append(conventions.runner()).append("(");
        for (int i = 0; i < ops.size(); i++) {
            sb.append(i == 0 ? "\n            " : ",\n            ").append(ops.get(i).strip());
        }
        sb.append(");\n}");
        return sb.toString();
    }

    /**
     * Compose {@code testName} into the suite's first top-level type. {@code targetSpec} (optional) is the
     * production method the test must reach through some verb's handler; with it, a test no verb can reach
     * is rejected as vacuous. Never touches disk - the caller writes {@code rendered()} when {@code ok}.
     */
    public static Composition compose(CodeGraph graph, ClasspathIndex index, String suiteSource,
            String testName, List<String> ops, String targetSpec) {
        return compose(graph, index, suiteSource, testName, ops, targetSpec, List.of());
    }

    public static Composition compose(CodeGraph graph, ClasspathIndex index, String suiteSource,
            String testName, List<String> ops, String targetSpec, List<String> declares) {
        List<String> problems = new ArrayList<>();
        if (testName == null || !testName.matches("[a-zA-Z_][A-Za-z0-9_]*")) {
            problems.add("testName must be a Java identifier: " + testName);
        }
        if (ops == null || ops.isEmpty()) {
            problems.add("at least one spec operation is required");
        }
        if (!problems.isEmpty()) {
            return new Composition(false, null, null, problems, List.of(), Map.of(), List.of(), List.of());
        }
        // ---- which verbs / constants does the test use, and where do they live? ----
        Map<String, String> verbOwner = verbOwners(graph);
        Map<String, String> verbDispatch = new LinkedHashMap<>();
        // three plans in a row wrote `updateItem(...)` although each refusal named `itemUpdate` -
        // an unknown head verb made of exactly one catalogue verb's words in another order IS that verb
        List<String> canonical = new ArrayList<>();
        for (String op : ops) {
            Matcher head = HEAD_CALL.matcher(op);
            String verb = head.find() ? head.group(1) : null;
            String same = verb == null || verbOwner.containsKey(verb) ? null : sameWordsVerb(verb, verbOwner.keySet());
            if (same != null) {
                verbDispatch.put(verb, "→ " + same + " (the catalogue verb with the same words; the op was rewritten)");
                op = op.substring(0, head.start(1)) + same + op.substring(head.end(1));
            }
            canonical.add(op);
        }
        ops = canonical;
        String method = methodText(testName, ops, declares == null ? List.of() : declares);
        SpecConventions conventions = SpecConventions.load();
        LinkedHashSet<String> imports = new LinkedHashSet<>(conventions.testImports());
        LinkedHashSet<String> usedVerbs = new LinkedHashSet<>();
        for (String op : ops) {
            Matcher m = CALL.matcher(op);
            while (m.find()) {
                String name = m.group(1);
                if (verbOwner.containsKey(name)) {
                    usedVerbs.add(name);
                    imports.add("static " + verbOwner.get(name) + "." + name);
                }
            }
            Matcher c = CONSTANT.matcher(op);
            while (c.find()) {
                String owner = constantOwner(graph, c.group(1));
                if (owner != null) {
                    imports.add("static " + owner + "." + c.group(1));
                }
            }
        }
        // `hex(...)` and `Payload.of(...)` are the suites' own idiom (the conventions' helper imports) and stayed
        // "no visible method" / "unknown variable" - well-known spec
        // helpers the method text uses are imported like the verbs
        imports.addAll(specHelperImports(method));
        if (usedVerbs.isEmpty()) {
            problems.add("no known spec verb in the operations — verbs are the static factories of "
                    + String.join("/", conventions.verbClasses()) + " (ask SpecTestComposer action=verbs)");
        }

        // ---- dispatch reach: verb → op → KIND → handler → (reach) target ----
        Map<String, List<SpecReach.Verb>> byName = new LinkedHashMap<>();
        for (SpecReach.Verb v : SpecReach.verbs(graph, null)) {
            byName.computeIfAbsent(v.name(), k -> new ArrayList<>()).add(v);
        }
        // a composition with an undeclared receiver (`catalog.lookupSync(...)`), a
        // numeric id in a String parameter, a two-argument itemCreate, an unused declared local and no assertion
        // passed the whole-file validation, because one unresolvable static import made every name "unprovable"
        // and the resolver, by contract, flags only what it can prove. The ops are checked deterministically first.
        Set<String> declaredNames = new LinkedHashSet<>();
        for (String d : declares == null ? List.<String>of() : declares) {
            int eq = d.indexOf('=');
            if (eq > 0) {
                declaredNames.add(d.substring(0, eq).strip().replaceFirst("^(?:final\\s+)?(?:var\\s+)?", ""));
            }
        }
        Set<String> importedConstants = new LinkedHashSet<>();
        for (String imp : imports) {
            if (imp.startsWith("static ")) {
                importedConstants.add(imp.substring(imp.lastIndexOf('.') + 1));
            }
        }
        problems.addAll(soundnessProblems(ops, declares == null ? List.of() : declares, declaredNames, verbOwner.keySet(),
                importedConstants, byName));
        Map<String, Set<String>> table = SpecReach.dispatchTable(graph);
        List<String> reaching = new ArrayList<>();
        List<String> unreached = new ArrayList<>();
        for (String verb : usedVerbs) {
            List<SpecReach.Verb> vs = byName.getOrDefault(verb, List.of());
            String functionality = vs.isEmpty() ? null : vs.get(0).functionality();
            List<String> handlers = handlersFor(table, functionality);
            verbDispatch.put(verb, functionality == null ? "?"
                    : functionality + (handlers.isEmpty() ? " (no handler in dispatch table)"
                            : " → " + String.join("/", handlers)));
            if (targetSpec != null && !targetSpec.isBlank()) {
                boolean reaches = false;
                for (String h : handlers) {
                    for (String entry : conventions.handlerEntryMethods()) {
                        if (graph.locate(h + "#" + entry).isEmpty()) {
                            continue;
                        }
                        CodeGraph.ReachResult r = graph.reach(h + "#" + entry, targetSpec, 10);
                        if (r.reachable()) {
                            reaching.add(verb + " → " + String.join(" -> ", r.path()));
                            reaches = true;
                            break;
                        }
                    }
                    if (reaches) {
                        break;
                    }
                }
                if (!reaches) {
                    unreached.add(verb);
                }
            }
        }
        // a QUERY op whose verb reaches the target must ASSERT something - a bare getItemInfo(...) proves nothing
        // (a transaction verb exercises its handler by running; the assertion belongs to the query that observes it)
        for (String op : ops) {
            Matcher head = HEAD_CALL.matcher(op);
            if (!head.find()) {
                continue;
            }
            String verb = head.group(1);
            boolean reachesTarget = reaching.stream().anyMatch(r -> r.startsWith(verb + " "));
            String issue = reachesTarget ? assertsNothing(op, verb, verbOwner.get(verb)) : null;
            if (issue != null) {
                problems.add(issue);
            }
        }
        // `itemGetInfo(item=42)` - an unknown verb name (the query verb is getItemInfo),
        // named arguments and single-quoted strings, none of which the first refusal named. Each op's HEAD call that
        // is no known verb is reported with its closest known verbs; non-Java argument shapes are named; and the
        // VACUOUS refusal lists the verbs that DO reach the target.
        for (String op : ops) {
            problems.addAll(opShapeProblems(op, verbOwner.keySet()));
        }
        if (targetSpec != null && !targetSpec.isBlank() && !usedVerbs.isEmpty() && reaching.isEmpty()) {
            String reachingVerbs = "";
            try {
                SpecReach.Result r = SpecReach.of(graph, targetSpec, 10);
                List<String> names = new ArrayList<>();
                String example = null;
                if (r != null && r.routes() != null) {
                    for (SpecReach.Route route : r.routes()) {
                        for (SpecReach.Verb v : route.verbs()) {
                            if (!names.contains(v.name())) {
                                names.add(v.name());
                            }
                            if (example == null) {
                                example = exampleOp(v, ops);
                            }
                        }
                    }
                }
                if (!names.isEmpty()) {
                    // four rounds, four invented names for the one verb that reaches the target -
                    // the refusal now shows a concrete op built from that verb's signature
                    reachingVerbs = " — the verbs that DO reach it: " + names + "; include one of them"
                            + (example == null ? "" : ", e.g. " + example);
                }
            } catch (Exception ignored) {
                // the hint is optional
            }
            problems.add("VACUOUS: no verb in this test dispatches to a handler that reaches " + targetSpec
                    + " (verbs: " + usedVerbs + ")" + reachingVerbs
                    + " — use CodeGraph action=specreach on the target to pick verbs whose handler actually "
                    + "reaches it");
        }
        if (!problems.isEmpty()) {
            return new Composition(false, null, method, problems, new ArrayList<>(imports), verbDispatch,
                    reaching, unreached);
        }

        // ---- splice into the parse tree and validate the WHOLE file ----
        ParseTree tree = ParseTree.parse(suiteSource);
        TreeEditor editor = TreeEditor.of(tree, index, graph.sources());
        List<Node> types = tree.typeDeclarations();
        if (types.isEmpty()) {
            return new Composition(false, null, method, List.of("suite has no top-level type"),
                    new ArrayList<>(imports), verbDispatch, reaching, unreached);
        }
        Set<String> present = new LinkedHashSet<>();
        for (Node imp : tree.imports()) {
            present.add((ParseTree.isStaticImport(imp) ? "static " : "") + ParseTree.importedName(imp));
        }
        List<String> added = new ArrayList<>();
        for (String imp : imports) {
            if (!present.contains(imp) && !coveredByWildcard(present, imp)) {
                editor.addImport(imp);
                added.add(imp);
            }
        }
        try {
            editor.insertMember(types.get(0), indentContinuation(method, "    "));
        } catch (RuntimeException e) {
            return new Composition(false, null, method, List.of("member does not parse: " + e.getMessage()),
                    added, verbDispatch, reaching, unreached);
        }
        ParseTree fresh;
        try {
            fresh = editor.commit();
        } catch (EditCommitException first) {
            List<String> enriched = TreeEditTool.autoImport(editor, index, graph.sources(),
                    first.problems());
            if (enriched != null) {
                return new Composition(false, null, method, withChainableMethods(graph, withVerbOverloads(enriched, byName, ops)),
                        added, verbDispatch, reaching, unreached);
            }
            try {
                fresh = editor.commit();
            } catch (EditCommitException second) {
                return new Composition(false, null, method, withChainableMethods(graph, withVerbOverloads(second.problems(), byName, ops)),
                        added, verbDispatch, reaching, unreached);
            }
        }
        return new Composition(true, fresh.toString(), method, List.of(), added, verbDispatch, reaching,
                unreached);
    }

    /** The editor indents a member's FIRST line; the rest of a multi-line member must carry the indent. */
    static String indentContinuation(String member, String indent) {
        String[] lines = member.split("\n", -1);
        StringBuilder sb = new StringBuilder(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            sb.append('\n').append(lines[i].isEmpty() ? "" : indent + lines[i]);
        }
        return sb.toString();
    }

    private static boolean coveredByWildcard(Set<String> present, String imp) {
        int dot = imp.lastIndexOf('.');
        return dot > 0 && present.contains(imp.substring(0, dot) + ".*");
    }

    private static List<String> handlersFor(Map<String, Set<String>> table, String functionality) {
        List<String> out = new ArrayList<>();
        if (functionality == null) {
            return out;
        }
        String f = SpecReach.norm(functionality);
        for (Map.Entry<String, Set<String>> e : table.entrySet()) {
            for (String kind : e.getValue()) {
                String k = SpecReach.norm(kind);
                if (k.equals(f) || k.startsWith(f) || f.startsWith(k)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /** Verb name → owning class FQN (only unambiguous names). */
    static Map<String, String> verbOwners(CodeGraph graph) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (SpecReach.Verb v : SpecReach.verbs(graph, null)) {
            owners.computeIfAbsent(v.name(), k -> new LinkedHashSet<>()).add(v.ownerFqn());
        }
        for (Map.Entry<String, Set<String>> e : owners.entrySet()) {
            if (e.getValue().size() == 1) {
                out.put(e.getKey(), e.getValue().iterator().next());
            }
        }
        return out;
    }

    /**
     * The FQN of the type declaring a static field / enum constant named {@code name} - preferring the
     * spec framework's own types (the conventions' spec package) and the generated message classes the specs are
     * written against (the conventions' generated package marker).
     */
    static String constantOwner(CodeGraph graph, String name) {
        List<String> candidates = new ArrayList<>();
        TypeHierarchy h = graph.hierarchy();
        for (TypeHierarchy.TypeEntry e : h.sourceEntries()) {
            Node decl = e.decl();
            if (decl == null) {
                continue;
            }
            Node body = decl.child(NodeType.CLASS_BODY);
            if (body == null) {
                continue;
            }
            boolean declares = false;
            for (Node ec : body.children(NodeType.ENUM_CONSTANT)) {
                if (name.equals(ec.name())) {
                    declares = true;
                }
            }
            for (Node fd : body.children(NodeType.FIELD_DECLARATION)) {
                if (!fd.hasModifier(TokenType.STATIC)) {
                    continue;
                }
                for (Node d : fd.children(NodeType.VAR_DECLARATOR)) {
                    if (name.equals(d.name())) {
                        declares = true;
                    }
                }
            }
            if (declares) {
                candidates.add(e.fqn());
            }
        }
        // only the spec framework's constant holders count - an enum constant of an unrelated class
        // (Layout.Mode.COMPACT) was imported for a name the author meant as a local
        SpecConventions conventions = SpecConventions.load();
        candidates.removeIf(c -> conventions.constantHolders().stream().noneMatch(c::endsWith));
        if (candidates.isEmpty()) {
            return null;
        }
        for (String c : candidates) {
            if (c.startsWith(conventions.specPackage())) {
                return c;
            }
        }
        String generated = conventions.generatedPackageMarker();
        for (String c : candidates) {
            if (!generated.isEmpty() && c.contains(generated)) {
                return c;
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** Where the composed suite would live for a package (mirrors the package as directories). */
    /** A well-known spec helper or a Java word the ops may use without declaring it. */
    public static boolean isSpecHelper(String name) {
        SpecConventions conventions = SpecConventions.load();
        return conventions.helperImports().containsKey(name) || isHelperHead(name, conventions);
    }

    /** A Java word or well-known helper an op may start with, or one the conventions add. */
    private static boolean isHelperHead(String name, SpecConventions conventions) {
        return HELPER_HEADS.contains(name) || conventions.extraHelperHeads().contains(name);
    }

    /** The imports for the well-known spec helpers (the conventions' helper imports) that {@code method} uses
     *  unqualified. */
    static List<String> specHelperImports(String method) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> helper : SpecConventions.load().helperImports().entrySet()) {
            if (Pattern.compile("(?<![\\w.])" + Pattern.quote(helper.getKey()) + "\\b").matcher(method).find()) {
                out.add(helper.getValue());
            }
        }
        return out;
    }

    /** "no overload of 'itemCreate' takes 2 arguments" told the author nothing about what
     *  the verb DOES take - an overload problem on a spec verb now names its real overloads and a concrete op. */
    static List<String> withVerbOverloads(List<String> problems, Map<String, List<SpecReach.Verb>> byName,
            List<String> ops) {
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            Matcher m = Pattern.compile("no overload of '(\\w+)' takes").matcher(problem);
            List<SpecReach.Verb> verbs = m.find() ? byName.getOrDefault(m.group(1), List.of()) : List.of();
            if (!verbs.isEmpty()) {
                List<String> sigs = new ArrayList<>();
                for (SpecReach.Verb v : verbs) {
                    if (v.signature() != null && !sigs.contains(v.signature()) && sigs.size() < 4) {
                        sigs.add(v.signature());
                    }
                }
                problem += " — " + m.group(1) + "'s overloads: " + String.join("; ", sigs) + "; e.g. "
                        + exampleOp(verbs.get(0), ops)
                        + " (ids are Strings in spec verbs — \"" + SpecConventions.load().idPrefix()
                        + "42\", not 42; modifiers such as .withName(...) or"
                        + " .hasName(...) are chained after the call)";
            }
            out.add(problem);
        }
        return out;
    }

    /** a concrete op for {@code verb} from its signature - a String argument becomes the id the ops mention, quoted
     *  after the conventions' id prefix, a numeric one that number, anything else a placeholder the author must
     *  fill. */
    static String exampleOp(SpecReach.Verb verb, List<String> ops) {
        String sig = verb.signature() == null ? "" : verb.signature();
        String prefix = SpecConventions.load().idPrefix();
        String id = null;
        for (String op : ops == null ? List.<String>of() : ops) {
            Matcher m = Pattern.compile("\\b(\\d{1,6})\\b").matcher(op);
            if (m.find()) {
                id = m.group(1);
                break;
            }
        }
        List<String> args = new ArrayList<>();
        int open = sig.indexOf('('), close = sig.lastIndexOf(')');
        if (open >= 0 && close > open) {
            String params = sig.substring(open + 1, close).strip();
            if (!params.isEmpty()) {
                for (String param : params.split(",")) {
                    String type = param.strip().replaceFirst("^final\\s+", "").split("\\s+")[0];
                    if (type.equals("String")) {
                        args.add("\"" + prefix + (id == null ? "1" : id) + "\"");
                    } else if (type.matches("long|int|Long|Integer")) {
                        args.add(id == null ? "1" : id);
                    } else {
                        args.add("<" + type + ">");
                    }
                }
            }
        }
        return verb.name() + "(" + String.join(", ", args) + ")";
    }

    /** What is wrong with the SHAPE of one op - an unknown head verb, named arguments, single quotes. */
    static List<String> opShapeProblems(String op, Collection<String> knownVerbs) {
        SpecConventions conventions = SpecConventions.load();
        List<String> out = new ArrayList<>();
        Matcher head = HEAD_CALL.matcher(op);
        if (head.find() && !knownVerbs.contains(head.group(1)) && !isHelperHead(head.group(1), conventions)) {
            out.add("unknown verb '" + head.group(1) + "' in op «" + op + "» — closest known verbs: "
                    + closestVerbs(head.group(1), knownVerbs, 3) + " (use one of the catalogue's names exactly)");
        }
        if (NAMED_ARGUMENT.matcher(op).find()) {
            out.add("op «" + op + "» uses named arguments — spec verbs take positional Java arguments, e.g. "
                    + "itemUpdate(\"" + conventions.idPrefix() + "42\").withName(name) or getItemInfo(\""
                    + conventions.idPrefix() + "42\").hasName(expected)");
        }
        if (op.matches("(?s).*'[^']*'.*")) {
            out.add("op «" + op + "» uses single quotes — Java strings use double quotes");
        }
        return out;
    }

    private static final Set<String> JAVA_WORDS = Set.of("true", "false", "null", "new", "final", "var", "int", "long",
            "byte", "boolean", "String", "this", "return", "class");

    /**
     * What is provably wrong with the ops and declares BEFORE the resolver sees them - unknown bare names and
     * receivers, declared locals never used, a verb called with an arity no overload has, a numeric id where the verb
     * takes a String. Every check is deterministic from the verb catalogue and the text.
     */
    static List<String> soundnessProblems(List<String> ops, List<String> declares, Set<String> declaredNames,
            Set<String> knownVerbs, Set<String> importedConstants, Map<String, List<SpecReach.Verb>> byName) {
        SpecConventions conventions = SpecConventions.load();
        List<String> out = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(knownVerbs);
        known.addAll(declaredNames);
        known.addAll(importedConstants);
        known.addAll(conventions.helperImports().keySet());
        known.addAll(HELPER_HEADS);
        known.addAll(conventions.extraHelperHeads());
        known.addAll(JAVA_WORDS);
        // (a) unknown names: a lowercase identifier that heads a chain (`x.m()`) or stands as a bare argument, and an
        //     UPPERCASE constant that no import covers
        List<String> texts = new ArrayList<>(ops);
        for (String d : declares) {
            int eq = d.indexOf('=');
            texts.add(eq > 0 ? d.substring(eq + 1) : d);
        }
        Pattern ident = Pattern.compile("(?<![\\w.\\\"])([A-Za-z_][A-Za-z0-9_]*)(?![\\w\\\"])(\\s*[.(])?");
        Set<String> reported = new LinkedHashSet<>();
        for (String text : texts) {
            String noStrings = text.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
            Matcher m = ident.matcher(noStrings);
            while (m.find()) {
                String name = m.group(1);
                String follow = m.group(2) == null ? "" : m.group(2).strip();
                if (follow.equals("(") || known.contains(name) || reported.contains(name)) {
                    continue;   // a call (checked as a verb/method), or a name we can account for
                }
                boolean receiver = follow.equals(".");
                boolean upper = name.matches("[A-Z][A-Z0-9_]{2,}");
                boolean typeLike = Character.isUpperCase(name.charAt(0)) && !upper;   // Payload.of(...) - a type
                if (typeLike) {
                    continue;
                }
                if (receiver || upper || isBareArgument(noStrings, m.start(1))) {
                    reported.add(name);
                    out.add("unknown variable '" + name + "' in «" + text.strip() + "» — "
                            + (receiver ? "a handler field such as `" + name + "` does not exist in a spec; " : "")
                            + "declare it with the `declare` field (e.g. {\"name\": \"" + name + "\", \"expr\": \""
                            + (upper ? "\\\"sample\\\".getBytes()"
                                    : "hex(" + conventions.digestHelper() + "(payload))")
                            + "\"}) or use the suites' idiom hex(" + conventions.digestHelper() + "(bytes))");
                }
            }
        }
        // (b) declared but never used
        for (String name : declaredNames) {
            boolean used = ops.stream().anyMatch(op -> Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b").matcher(op).find());
            if (!used) {
                out.add("'" + name + "' is declared but never used by an op — assert it, e.g. "
                        + "getItemInfo(\"" + conventions.idPrefix() + "42\").hasName(" + name + ")");
            }
        }
        // (c) verb arity and id shape against the catalogue
        for (String op : ops) {
            Matcher head = HEAD_CALL.matcher(op);
            if (!head.find()) {
                continue;
            }
            String verb = head.group(1);
            List<SpecReach.Verb> overloads = byName.getOrDefault(verb, List.of());
            if (overloads.isEmpty()) {
                continue;
            }
            List<String> args = topLevelArgs(op, head.end() - 1);
            if (args == null) {
                continue;
            }
            boolean arityOk = false;
            boolean firstIsString = false;
            for (SpecReach.Verb v : overloads) {
                List<String> params = paramTypes(v.signature());
                boolean varargs = !params.isEmpty() && params.get(params.size() - 1).endsWith("...");
                if (params.size() == args.size() || (varargs && args.size() >= params.size() - 1)) {
                    arityOk = true;
                    if (!params.isEmpty() && params.get(0).equals("String")) {
                        firstIsString = true;
                    }
                }
            }
            if (!arityOk) {
                List<String> sigs = new ArrayList<>();
                for (SpecReach.Verb v : overloads) {
                    if (v.signature() != null && !sigs.contains(v.signature()) && sigs.size() < 4) {
                        sigs.add(v.signature());
                    }
                }
                out.add("no overload of '" + verb + "' takes " + args.size() + " argument" + (args.size() == 1 ? "" : "s")
                        + " — " + verb + "'s overloads: " + String.join("; ", sigs) + "; e.g. " + exampleOp(overloads.get(0), ops)
                        + " (modifiers such as .withName(...) or .hasName(...) are chained after the call)");
            } else if (firstIsString && !args.isEmpty() && args.get(0).strip().matches("\\d+L?")) {
                out.add("op «" + op.strip() + "» passes the number " + args.get(0).strip() + " where " + verb
                        + " takes a String id — write \"" + conventions.idPrefix()
                        + args.get(0).strip().replaceAll("L$", "") + "\"");
            }
        }
        return out;
    }

    /** Whether the identifier at {@code at} is a whole argument (preceded by '(' or ',' and followed by ',' or ')'). */
    private static boolean isBareArgument(String text, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int j = at;
        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        boolean before = i < 0 || text.charAt(i) == '(' || text.charAt(i) == ',' || text.charAt(i) == '=';
        boolean after = j >= text.length() || text.charAt(j) == ',' || text.charAt(j) == ')' || text.charAt(j) == ';';
        return before && after;
    }

    /** The top-level arguments of the call whose '(' is at {@code open}; null when unbalanced. */
    static List<String> topLevelArgs(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '(') {
            return null;
        }
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    String last = text.substring(start, i).strip();
                    if (!last.isEmpty()) {
                        args.add(last);
                    }
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(text.substring(start, i).strip());
                start = i + 1;
            }
        }
        return null;
    }

    /** Parameter types of a signature like {@code itemCreate(final String name, final byte[]... contents)}. */
    static List<String> paramTypes(String signature) {
        List<String> out = new ArrayList<>();
        if (signature == null) {
            return out;
        }
        int open = signature.indexOf('('), close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return out;
        }
        String params = signature.substring(open + 1, close).strip();
        if (params.isEmpty()) {
            return out;
        }
        for (String p : params.split(",")) {
            String[] words = p.strip().replaceFirst("^final\\s+", "").split("\\s+");
            out.add(words.length >= 2 ? words[words.length - 2] : words[0]);
        }
        return out;
    }

    /** "SpecItemInfo has no method 'assertNameEquals'" named no alternative, and the planner
     *  never found hasName - a no-such-method problem on a class the graph knows now lists the class's chainable
     *  methods, the closest first. */
    static List<String> withChainableMethods(CodeGraph graph, List<String> problems) {
        List<String> out = new ArrayList<>();
        Pattern noMethod = Pattern.compile("(\\w+) has no method '(\\w+)'");
        for (String problem : problems) {
            Matcher m = noMethod.matcher(problem);
            if (m.find()) {
                List<String> methods = methodsOf(graph, m.group(1));
                if (!methods.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (String sig : methods) {
                        names.add(sig.substring(0, sig.indexOf('(')));
                    }
                    List<String> closest = closestVerbs(m.group(2), names, 3);
                    List<String> shown = new ArrayList<>();
                    for (String c : closest) {
                        for (String sig : methods) {
                            if (sig.startsWith(c + "(") && !shown.contains(sig)) {
                                shown.add(sig);
                            }
                        }
                    }
                    for (String sig : methods) {
                        if (shown.size() >= 12) {
                            break;
                        }
                        if (!shown.contains(sig)) {
                            shown.add(sig);
                        }
                    }
                    problem += " — " + m.group(1) + "'s chainable methods (closest first): " + shown;
                }
            }
            out.add(problem);
        }
        return out;
    }

    /** The public method signatures declared by the source class with simple name {@code simpleName}, `has…` first. */
    static List<String> methodsOf(CodeGraph graph, String simpleName) {
        List<String> has = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (TypeHierarchy.TypeEntry e : graph.hierarchy().sourceEntries()) {
            if (!simpleName.equals(e.name()) || e.decl() == null) {
                continue;
            }
            Node body = e.decl().child(NodeType.CLASS_BODY);
            if (body == null) {
                continue;
            }
            for (Node md : body.children(NodeType.METHOD_DECLARATION)) {
                if (md.name() == null || md.hasModifier(TokenType.PRIVATE) || md.hasModifier(TokenType.STATIC)) {
                    continue;
                }
                List<String> ps = new ArrayList<>();
                for (Node p : md.children(NodeType.PARAMETER)) {
                    String[] words = p.toString().strip().replaceAll("\\s+", " ").replaceFirst("^final ", "").split(" ");
                    ps.add(words.length >= 2 ? words[words.length - 2] : words[0]);
                }
                String sig = md.name() + "(" + String.join(", ", ps) + ")";
                (md.name().startsWith("has") ? has : rest).add(sig);
            }
        }
        has.addAll(rest);
        return has;
    }

    /** The problem when a QUERY verb's op (owner the conventions' query verbs, or a get… verb) carries no chained
     *  assertion. */
    static String assertsNothing(String op, String verb, String ownerFqn) {
        boolean query = (ownerFqn != null && ownerFqn.endsWith(SpecConventions.load().queryVerbs()))
                || verb.startsWith("get");
        if (!query || hasChainedCall(op)) {
            return null;
        }
        return "op «" + op.strip() + "» asserts nothing — chain an assertion on the query that reaches the target, e.g. "
                + op.strip() + ".hasName(expected)";
    }

    /** Whether the op chains a method after its head call: {@code verb(...).something(...)}. */
    static boolean hasChainedCall(String op) {
        Matcher head = HEAD_CALL.matcher(op);
        if (!head.find()) {
            return false;
        }
        List<String> args = topLevelArgs(op, head.end() - 1);
        if (args == null) {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = head.end() - 1; i < op.length(); i++) {
            char c = op.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return op.substring(i + 1).strip().startsWith(".");
                }
            }
        }
        return false;
    }

    /** The ONE known verb whose camel words are exactly {@code verb}'s words in another order, else null. */
    static String sameWordsVerb(String verb, Collection<String> known) {
        List<String> words = camelWords(verb);
        String found = null;
        for (String k : known) {
            if (!k.equals(verb) && camelWords(k).equals(words)) {
                if (found != null) {
                    return null;   // ambiguous
                }
                found = k;
            }
        }
        return found;
    }

    private static List<String> camelWords(String name) {
        List<String> w = new ArrayList<>(Arrays.asList(
                name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("\\s+")));
        Collections.sort(w);
        return w;
    }

    /** The known verb names closest to {@code verb} - same camel words first, then edit distance. */
    static List<String> closestVerbs(String verb, Collection<String> known, int max) {
        Set<String> words = new HashSet<>(Arrays.asList(
                verb.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("\\s+")));
        List<String> ranked = new ArrayList<>(known);
        ranked.sort((a, b) -> Integer.compare(verbDistance(a, verb, words), verbDistance(b, verb, words)));
        return ranked.subList(0, Math.min(max, ranked.size()));
    }

    private static int verbDistance(String candidate, String verb, Set<String> words) {
        Set<String> cw = new HashSet<>(Arrays.asList(
                candidate.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("\\s+")));
        int shared = 0;
        for (String w : words) {
            if (cw.contains(w)) {
                shared++;
            }
        }
        // fewer shared words → larger distance; ties broken by length difference
        return (words.size() - shared) * 100 + Math.abs(candidate.length() - verb.length());
    }

    public static Path suitePath(Path sourceRoot, String pkg, String className) {
        Path p = sourceRoot;
        if (pkg != null && !pkg.isBlank()) {
            // the plan named the package DIRECTORY as `dir` and the package too, and the path came
            // out doubled (…/spec/suites/example/spec/suites/X.java). A root that already ends with the
            // package's segments is the package directory itself.
            // the package EXTENDED the directory's package (dir …/spec/suites, package
            // example.spec.suites.misc) and the path came out doubled again - only the segments the
            // directory does not already end with are appended (…/spec/suites/misc).
            String[] segs = pkg.split("\\.");
            int covered = 0;   // how many leading package segments the root already ends with
            for (int k = segs.length; k >= 1 && covered == 0; k--) {
                boolean tailMatches = true;
                Path probe = sourceRoot;
                for (int i = k - 1; i >= 0; i--) {
                    if (probe == null || probe.getFileName() == null
                            || !probe.getFileName().toString().equals(segs[i])) {
                        tailMatches = false;
                        break;
                    }
                    probe = probe.getParent();
                }
                if (tailMatches) {
                    covered = k;
                }
            }
            for (int i = covered; i < segs.length; i++) {
                p = p.resolve(segs[i]);
            }
        }
        return p.resolve(className + ".java");
    }

    /**
     * The plan's projectDir ended with {@code app} and its relative {@code dir} began with
     * {@code app/…}; resolving one against the other doubled the segment. A relative path whose leading
     * segments repeat the base's trailing segments continues from the base instead.
     */
    public static Path resolveOverlapping(Path base, String relative) {
        Path rel = Path.of(relative);
        if (rel.isAbsolute()) {
            return rel.normalize();
        }
        int best = 0;
        for (int k = Math.min(rel.getNameCount(), base.getNameCount()); k >= 1; k--) {
            boolean match = true;
            for (int i = 0; i < k; i++) {
                if (!base.getName(base.getNameCount() - k + i).toString().equals(rel.getName(i).toString())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                best = k;
                break;
            }
        }
        Path out = base;
        for (int i = best; i < rel.getNameCount(); i++) {
            out = out.resolve(rel.getName(i));
        }
        return out.normalize();
    }
}
