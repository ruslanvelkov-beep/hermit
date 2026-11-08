package hermit.text.pos;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The domain lexicon (milestone A2 of the annotation plan, section 5, stage 3): software verbs and nouns with their
 * usual tags, the tools' names, and names taken from the code. A name matches exactly as written and is PROPN.
 * Otherwise the word, in lower case, matches a word list entry as it is, or with a final {@code es} removed when what
 * is left ends in s, x, z, ch or sh ({@code fixes}), or with a final {@code s} removed ({@code runs}): the third person
 * and the plural keep the entry's candidates. Every candidate names the rule {@link #RULE}.
 */
public final class DomainLexicon implements Lexicon {

    /** The rule every domain candidate names. */
    public static final String RULE = "domain";

    /** VERB. */
    public static final List<String> VERBS = List.of("fail", "succeed", "refactor", "rebase", "deploy", "compile",
            "rename", "revert", "parse", "tokenize", "implement", "configure", "install", "invoke", "override",
            "extract", "serialize", "deserialize", "validate", "verify", "execute", "initialize", "iterate", "render",
            "resolve", "restart", "retry", "migrate", "normalize", "enable", "disable", "apply", "supply", "multiply",
            "rely", "append", "prepend", "insert", "delete", "remove", "replace", "compute", "emit", "add", "read",
            "write", "make", "get", "find", "keep", "skip", "wait", "accept", "reject", "approve", "contain", "create",
            "define", "describe", "ensure", "expect", "explain", "include");

    /** NOUN. */
    public static final List<String> NOUNS = List.of("repository", "repo", "class", "method", "field", "interface",
            "module", "constructor", "parameter", "argument", "variable", "constant", "exception", "suite", "assertion",
            "fixture", "ledger", "diff", "token", "sentence", "parser", "compiler", "runtime", "classpath",
            "dependency", "endpoint", "timeout", "config", "server", "client", "thread", "queue", "schema", "enum",
            "annotation", "javadoc", "regex", "boolean", "integer", "array", "key", "value", "payload", "response",
            "header", "provider", "router", "window", "budget", "task", "driver", "supervisor", "follower", "planner",
            "critic", "lens", "verdict", "target", "seam", "file", "directory", "folder", "path", "line", "column",
            "version", "session", "function", "string", "type", "object", "instance", "node", "graph", "tree", "table",
            "bug", "error", "warning", "failure", "success", "hash", "snapshot", "tool", "model", "agent", "question",
            "iteration", "item", "entry", "feature");

    /** Both verb and noun in code reports, kept ambiguous on purpose: NOUN, then VERB. */
    public static final List<String> AMBIGUOUS = List.of("test", "build", "commit", "run", "call", "return", "throw",
            "merge", "port", "replay", "push", "pull", "fetch", "patch", "log", "check", "fix", "change", "update",
            "release", "start", "stop", "use", "need", "plan", "request", "record", "list", "map", "set", "index",
            "copy", "branch", "report", "review", "edit", "prompt", "rewrite", "import", "export", "link", "cache",
            "stage", "design", "process", "sort", "filter", "match", "search", "load", "save", "print", "step", "pass",
            "output", "input", "query", "trace", "benchmark", "package", "comment", "document", "increment",
            "reference", "break", "catch", "handle", "view", "lock", "block", "flag", "tag", "mark", "label", "note",
            "answer", "reply", "chain", "round");

    /** The tools' and the build's names, PROPN. */
    public static final List<String> NAMES = List.of("TreeEdit", "TreeCodegen", "Bash", "CodeGraph", "LocateFile",
            "SyntaxCheck", "FileClassifier", "AssembleContext", "ProcessInspect", "PathProof", "Gradle", "Git", "JUnit",
            "Jackson", "Java", "Python", "Markdown", "JSON", "YAML", "Hermit");

    /** The modifiers that may start a declaration's line, for {@link #declaredNames}. */
    public static final List<String> MODIFIERS = List.of("public", "protected", "private", "static", "final",
            "abstract", "default", "synchronized", "sealed", "non-sealed", "strictfp");

    private final Set<String> names;

    DomainLexicon(Set<String> names) {
        this.names = names;
    }

    /** The word lists and {@link #NAMES}. */
    public static DomainLexicon standard() {
        return new DomainLexicon(Set.copyOf(NAMES));
    }

    /**
     * The word lists, {@link #NAMES} and these names (the code's type and member names, the hosted catalogue's model
     * names), refused with an IllegalArgumentException when a name is blank or holds whitespace.
     */
    public static DomainLexicon withNames(Collection<String> names) {
        LinkedHashSet<String> allNames = new LinkedHashSet<>(NAMES);
        for (String name : names) {
            if (name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("A name must be nonblank and contain no whitespace");
            }
            allNames.add(name);
        }
        return new DomainLexicon(Set.copyOf(allNames));
    }

    /**
     * The names a Java source declares, each once, in the order they first appear: the name after {@code class},
     * {@code interface}, {@code enum} or {@code record} on a line whose first word is that keyword or one of
     * {@link #MODIFIERS}, and the last name before the first {@code (} on a line whose first word is one of
     * {@link #MODIFIERS} and that holds no {@code =} before that parenthesis. A line's first word is what it starts
     * with after its indentation, up to the next whitespace.
     */
    public static List<String> declaredNames(String javaSource) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String rawLine : javaSource.lines().toList()) {
            String line = rawLine.stripLeading();
            if (line.isEmpty()) {
                continue;
            }
            int firstEnd = 0;
            while (firstEnd < line.length() && !Character.isWhitespace(line.charAt(firstEnd))) {
                firstEnd++;
            }
            String first = line.substring(0, firstEnd);
            boolean startsWithModifier = MODIFIERS.contains(first);
            boolean startsWithType = first.equals("class") || first.equals("interface")
                    || first.equals("enum") || first.equals("record");
            if (!startsWithModifier && !startsWithType) {
                continue;
            }

            String[] words = line.split("\\s+");
            for (int i = 0; i + 1 < words.length; i++) {
                if (words[i].equals("class") || words[i].equals("interface") || words[i].equals("enum")
                        || words[i].equals("record")) {
                    String name = words[i + 1].replaceFirst("[^A-Za-z0-9_$].*$", "");
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                    break;
                }
            }

            if (startsWithModifier) {
                int parenthesis = line.indexOf('(');
                int assignment = line.indexOf('=');
                if (parenthesis >= 0 && (assignment < 0 || assignment > parenthesis)) {
                    String before = line.substring(0, parenthesis).stripTrailing();
                    int nameStart = before.length();
                    while (nameStart > 0 && Character.isJavaIdentifierPart(before.charAt(nameStart - 1))) {
                        nameStart--;
                    }
                    String name = before.substring(nameStart);
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    @Override
    public List<Candidate> candidates(String word) {
        if (names.contains(word)) {
            return List.of(new Candidate(Tag.PROPN, RULE));
        }

        String normalized = word.toLowerCase(Locale.ROOT);
        List<String> forms = new ArrayList<>();
        forms.add(normalized);
        if (normalized.endsWith("es") && normalized.length() > 2) {
            String withoutEs = normalized.substring(0, normalized.length() - 2);
            if (withoutEs.endsWith("s") || withoutEs.endsWith("x") || withoutEs.endsWith("z")
                    || withoutEs.endsWith("ch") || withoutEs.endsWith("sh")) {
                forms.add(withoutEs);
            }
        }
        if (normalized.endsWith("s") && normalized.length() > 1) {
            forms.add(normalized.substring(0, normalized.length() - 1));
        }

        for (String form : forms) {
            if (AMBIGUOUS.contains(form)) {
                return List.of(new Candidate(Tag.NOUN, RULE), new Candidate(Tag.VERB, RULE));
            }
            if (VERBS.contains(form)) {
                return List.of(new Candidate(Tag.VERB, RULE));
            }
            if (NOUNS.contains(form)) {
                return List.of(new Candidate(Tag.NOUN, RULE));
            }
        }
        return List.of();
    }
}
