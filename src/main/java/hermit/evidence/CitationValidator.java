package hermit.evidence;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every code object a claim cites - a file, a line of a file, code quoted at a line, a type, a member - must
 * exist in the repository. The check runs every time a text is checked and for every citation in it.
 * A claim resting on an object that does not exist is dismissed at once: an answer is refused before any
 * reviewer runs, and a reviewer's or critic's issue (or disputed claim) is dropped.
 *
 * <p>Only a proven absence dismisses a claim. Types and members are decided by CodeGraph (the project's
 * source index), with the declared, generated and imported names and the JDK as further evidence of
 * existence; a member of a type the sources cannot see (generated, a library's) is undecided and left alone.
 */
public final class CitationValidator {
    private CitationValidator() {}

    public enum Kind { FILE, LINE, TYPE, MEMBER }

    /** One cited object; {@code at} is its offset in the checked text. */
    public record Citation(Kind kind, String text, String file, String type, String member,
            int lineFrom, int lineTo, int at) {
        String key() {
            if (file != null) {
                return file.substring(file.lastIndexOf('/') + 1);
            }
            return member != null ? member : type;
        }
    }

    /** A citation whose object does not exist, and why. */
    public record Missing(Citation citation, String why) {
        public String describe() {
            return citation.text() + " (" + why + ")";
        }
    }

    /** What the repository holds - behind an interface so tests can stub it. */
    public interface Repo {
        /** Files whose path is, or ends with, the given path or file name. */
        List<Path> filesNamed(String pathOrName);

        /** TRUE / FALSE when decided; null when it cannot be decided. */
        Boolean typeExists(String simpleName);

        /** TRUE / FALSE when decided; null when the type is generated or a library's. */
        Boolean memberExists(String type, String member);
    }

    private static final String EXT = "(?:java|kt|kts|gradle|json|properties|ya?ml|xml|proto)";
    private static final Pattern FILE = Pattern.compile(
            "(?<![\\w/.$~-])((?:~?/)?(?:[\\w.-]+/)*[\\w$-]+\\." + EXT + ")(?![\\w])");
    /** `LoanRules:201`, `ReturnLoanStep:75-76` - a class's source file and a line, without the extension. */
    private static final Pattern CLASS_LINE = Pattern.compile(
            "(?<![\\w/.$#-])([A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+)(?::L?|#L)(\\d{1,5})"
                    + "(?:\\s*(?:-|–)\\s*L?(\\d{1,5}))?(?!\\d)");
    /** Lines after a file: `:123`, `:L123`, `#L12-L20`, ` (lines 73-77)`, ` line 201`, ` l.201`. */
    private static final Pattern LINES_AFTER = Pattern.compile(
            "^`?(?::L?|#L|\\s*\\(?\\s*(?:at\\s+)?(?:lines?|ll?\\.|L)\\s*~?\\s*)(\\d{1,5})"
                    + "(?:\\s*(?:-|–|to)\\s*L?(\\d{1,5}))?",
            Pattern.CASE_INSENSITIVE);
    /** Lines written before a file: `line 201 of X.java`, `lines 73-77 in X.java`. */
    private static final Pattern LINES_BEFORE = Pattern.compile(
            "(?i)\\blines?\\s+(\\d{1,5})(?:\\s*(?:-|–|to)\\s*(\\d{1,5}))?\\s+(?:of|in)\\s+`?$");
    private static final Pattern MEMBER = Pattern.compile(
            "(?<![\\w.$])([A-Z][A-Za-z0-9_]*)(#|\\.)([a-z_][A-Za-z0-9_]*)(\\s*\\()?");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]{1,200})`");
    private static final Pattern CAMEL_TYPE = Pattern.compile("[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+");
    private static final Pattern NEGATED = Pattern.compile(
            "(?i)\\b(does ?n[o']t exist|do ?n[o']t exist|did ?n[o']t exist|no such|not found|non-?existent|not exist|"
                    + "absent|invented|made[- ]up|fabricat\\w*|hallucinat\\w*|"
                    + "no (?:file|class|type|method|member) (?:named|called))\\b");
    private static final Pattern ATTRIBUTED = Pattern.compile(
            "(?i)\\b(reporter|the report|the finding|the ticket|reported|claims?|claimed|alleged\\w*|"
                    + "according to|asserts?|says)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Set<String> JAVA_WORDS = Set.of(
            "final", "return", "this", "new", "null", "true", "false", "public", "private", "protected", "static",
            "void", "boolean", "int", "long", "var", "else", "throw", "throws", "NonNull", "Nullable", "instanceof",
            "case", "default", "break", "continue", "while", "for", "try", "catch", "class", "record");
    /** A line reference with or without a file: `line 195`, `lines 36-45`, `L12`, `:201`. */
    private static final Pattern LINE_REFERENCE = Pattern.compile(
            "(?i)\\blines?\\s+~?\\d{1,5}|\\bL\\d{1,5}\\b|:L?\\d{1,5}\\b");
    private static final Map<Path, WorkbenchRepo> REPOS = new ConcurrentHashMap<>();
    private static final Pattern PROPOSAL_LEAD = Pattern.compile(
            "(?i)\\b(a new (?:method|test)|new (?:method|test) (?:like|named|called)|name[d]? (?:it|like)|call it"
                    + "|could be (?:named|called)|will be (?:named|called)|to be created|such as|e\\.?g\\.?|for example"
                    + "|propose[d]? (?:a|the)?|suggest[ed]? (?:a|the)?|something like)\\b[^.]{0,40}$");
    /** A CamelCase type name with a code suffix that no result and not the request contains is invented. */
    private static final Pattern TYPE_NAME = Pattern.compile(
            "\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+(?:Handler|Calculator|Service|Impl|Store|Manager|Test|Utility|Utils|Context|Workflow|"
                    + "Dispatcher|Verifier|Builder|Factory|Config|Processor|Provider|Registry|Validator|Executor|Scheduler)\\b");

    public static List<Citation> extract(String text) {
        List<Citation> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Set<Integer> typeOffsetsTaken = new HashSet<>();
        Matcher f = FILE.matcher(text);
        while (f.find()) {
            String file = f.group(1);
            int from = -1, to = -1;
            Matcher after = LINES_AFTER.matcher(text.substring(f.end(), Math.min(text.length(), f.end() + 40)));
            if (after.find()) {
                from = Integer.parseInt(after.group(1));
                to = after.group(2) == null ? from : Integer.parseInt(after.group(2));
            } else {
                Matcher before = LINES_BEFORE.matcher(text.substring(Math.max(0, f.start() - 40), f.start()));
                if (before.find()) {
                    from = Integer.parseInt(before.group(1));
                    to = before.group(2) == null ? from : Integer.parseInt(before.group(2));
                }
            }
            out.add(fileCitation(file, from, to, f.start()));
            String base = file.substring(file.lastIndexOf('/') + 1);
            typeOffsetsTaken.add(f.start() + file.length() - base.length());
        }
        Matcher cl = CLASS_LINE.matcher(text);
        while (cl.find()) {
            int from = Integer.parseInt(cl.group(2));
            int to = cl.group(3) == null ? from : Integer.parseInt(cl.group(3));
            Citation c = fileCitation(cl.group(1) + ".java", from, to, cl.start());
            out.add(new Citation(Kind.LINE, cl.group(1) + ":" + from + (to != from ? "-" + to : ""), c.file(), null, null,
                    c.lineFrom(), c.lineTo(), c.at()));
            typeOffsetsTaken.add(cl.start());
        }
        Matcher m = MEMBER.matcher(text);
        while (m.find()) {
            String type = m.group(1), sep = m.group(2), member = m.group(3);
            boolean call = m.group(4) != null;
            boolean spec = "#".equals(sep);
            if (!spec && (member.matches(EXT) || text.startsWith(type + "." + member + ".", m.start()))) {
                continue;   // `X.java` is a file; `Foo.bar.baz` is a package-like chain, not one member
            }
            if (!(spec || call || inBackticks(text, m.start()))) {
                continue;   // plain prose like "Handler.then" is not a code citation
            }
            if (type.matches("[A-Z][A-Z0-9_]*")) {
                continue;   // a constant's qualifier, not a type
            }
            out.add(new Citation(Kind.MEMBER, type + (spec ? "#" : ".") + member + (call ? "()" : ""), null, type, member, -1, -1,
                    m.start()));
            typeOffsetsTaken.add(m.start());
        }
        Set<String> typesSeen = new LinkedHashSet<>();
        Matcher t = TYPE_NAME.matcher(text);
        while (t.find()) {
            if (!typeOffsetsTaken.contains(t.start()) && !text.startsWith(".", t.end()) && !text.startsWith(":", t.end())
                    && typesSeen.add(t.group())) {
                out.add(new Citation(Kind.TYPE, t.group(), null, t.group(), null, -1, -1, t.start()));
            }
        }
        Matcher b = BACKTICKED.matcher(text);
        while (b.find()) {
            String inner = b.group(1).strip();
            if (CAMEL_TYPE.matcher(inner).matches() && typesSeen.add(inner)) {
                out.add(new Citation(Kind.TYPE, inner, null, inner, null, -1, -1, b.start(1)));
            }
        }
        out.sort(Comparator.comparingInt(Citation::at));
        return out;
    }

    private static Citation fileCitation(String file, int from, int to, int at) {
        boolean lines = from > 0;
        return new Citation(lines ? Kind.LINE : Kind.FILE, file + (lines ? ":" + from + (to != from ? "-" + to : "") : ""), file,
                null, null, from, Math.max(from, to), at);
    }

    private static boolean inBackticks(String text, int at) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
        int ticks = 0;
        for (int i = lineStart; i < at; i++) {
            if (text.charAt(i) == '`') {
                ticks++;
            }
        }
        return ticks % 2 == 1;
    }

    public static List<Missing> missing(String text, Repo repo) {
        return missing(text, repo, null, false);
    }

    public static List<Missing> missing(String text, Repo repo, String exempt, boolean exemptAlways) {
        List<Missing> out = new ArrayList<>();
        if (text == null || repo == null) {
            return out;
        }
        for (Citation c : extract(text)) {
            String sentence = sentenceAt(text, c.at());
            if (NEGATED.matcher(sentence).find() || introducedAsAProposal(text, c.at())) {
                continue;   // "X does not exist" / "a new test named testX" is not a claim that X exists
            }
            if (exempt != null && exempt.contains(c.key()) && (exemptAlways || ATTRIBUTED.matcher(sentence).find())) {
                continue;
            }
            String why = switch (c.kind()) {
                case FILE, LINE -> fileProblem(c, repo, text);
                case TYPE -> Boolean.FALSE.equals(repo.typeExists(c.type())) ? "no such type in the repository" : null;
                case MEMBER -> memberProblem(c, repo);
            };
            if (why != null && out.stream().noneMatch(x -> x.citation().text().equals(c.text()))) {
                out.add(new Missing(c, why));
            }
        }
        return out;
    }

    private static String fileProblem(Citation c, Repo repo, String text) {
        List<Path> files = repo.filesNamed(c.file());
        if (files.isEmpty()) {
            return "no such file in the repository";
        }
        if (c.kind() != Kind.LINE) {
            return null;
        }
        int longest = 0;
        for (Path p : files) {
            longest = Math.max(longest, lineCount(p));
        }
        if (longest > 0 && c.lineFrom() > longest) {
            return "the file has " + longest + " lines";
        }
        String quoted = quotedCodeNear(text, c.at());
        if (quoted != null && longest > 0) {
            for (Path p : files) {
                if (codeIsNear(p, quoted, c.lineFrom(), c.lineTo())) {
                    return null;
                }
            }
            return "`" + abbreviate(quoted) + "` is not at those lines";
        }
        return null;
    }

    private static String memberProblem(Citation c, Repo repo) {
        Boolean type = repo.typeExists(c.type());
        if (Boolean.FALSE.equals(type)) {
            return "no such type in the repository";
        }
        if (Boolean.TRUE.equals(type) && Boolean.FALSE.equals(repo.memberExists(c.type(), c.member()))) {
            return c.type() + " declares no " + c.member() + " (nor do its supertypes)";
        }
        return null;
    }

    private static String sentenceAt(String text, int at) {
        int start = Math.max(Math.max(text.lastIndexOf(". ", at), text.lastIndexOf('\n', at)), 0);
        int endDot = text.indexOf(". ", at), endNl = text.indexOf('\n', at);
        int end = Math.min(endDot < 0 ? text.length() : endDot, endNl < 0 ? text.length() : endNl);
        return text.substring(start, Math.max(start, end));
    }

    /**
     * Inline code on the citation's own line that reads as code (a call), when the line cites one place only.
     * In "`findNearest()` (line 195, which uses the key from `ShelfKeys.java` lines 36-45)",
     * the quote belongs to the bare line reference. Several references cannot attribute a quote to one file.
     */
    private static String quotedCodeNear(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at) + 1;
        int lineEnd = text.indexOf('\n', at);
        String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
        if (FILE.matcher(line).results().count() + CLASS_LINE.matcher(line).results().count() != 1
                || LINE_REFERENCE.matcher(line).results().count() > 1) {
            return null;   // two places on one line: the quote cannot be attributed
        }
        Matcher q = BACKTICKED.matcher(line);
        String best = null;
        while (q.find()) {
            String code = q.group(1).strip();
            // A bare mention of a method beside ReturnLoanStep.java is not code quoted from those lines;
            // only code with arguments, a member access or an assignment is.
            if (code.length() >= 12 && code.matches(".*[A-Za-z_]\\w*\\s*\\(.*") && !FILE.matcher(code).find()
                    && !code.matches("[A-Za-z_][\\w$]*\\s*\\(\\s*\\)")
                    && !code.contains("...") && !code.contains("…") && (best == null || code.length() > best.length())) {
                best = code;
            }
        }
        return best;
    }

    /**
     * Every method or variable name of the quote (keywords aside) appears within three lines of the range,
     * and every capitalized name somewhere in the file. A qualifier added to a statically imported call
     * (`ShelfKeys.keyOf(…)` for `keyOf(…)`) is a paraphrase.
     * A bare method mention beside `ReturnLoanStep.java` is not code quoted from that file.
     */
    private static boolean codeIsNear(Path file, String code, int from, int to) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int a = Math.max(0, from - 4), b = Math.min(lines.size(), Math.max(from, to) + 3);
            Set<String> window = new HashSet<>(), whole = new HashSet<>();
            IDENTIFIER.matcher(String.join("\n", lines.subList(a, b))).results().forEach(r -> window.add(r.group()));
            IDENTIFIER.matcher(String.join("\n", lines)).results().forEach(r -> whole.add(r.group()));
            Matcher id = IDENTIFIER.matcher(code);
            while (id.find()) {
                String name = id.group();
                boolean found = Character.isUpperCase(name.charAt(0)) ? whole.contains(name) : window.contains(name);
                if (!JAVA_WORDS.contains(name) && !found) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return true;   // cannot read: not a proven mismatch
        }
    }

    private static int lineCount(Path p) {
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }

    /**
     * The refusal for an ending that cites objects that do not exist, or null when every cited object exists. An object
     * the request names is not judged when the sentence attributes it to the report.
     */
    public static String issue(String text, Path root, String request) {
        List<Missing> gone = missing(text, repoFor(root), request, requestNamesExempt());
        if (gone.isEmpty()) {
            return null;
        }
        return "These cited code objects do not exist in the repository, so every claim resting on them is dismissed: "
                + describe(gone) + ". Drop those claims, or cite the real file, line, type or member as a tool printed it "
                + "this turn (verify with LocateFile, CodeGraph locate or grep -n first).";
    }

    /**
     * -Dhermit.citations.requestNamesExempt=true: an object the request names is never judged here, however the answer
     * cites it, for build tasks whose request names the files and types still to be created.
     * Off by default: in an analysis, a request may name code that does not exist.
     */
    static boolean requestNamesExempt() {
        return Boolean.getBoolean("hermit.citations.requestNamesExempt");
    }

    /** How many cited objects of {@code text} do not exist (0 without a workbench). */
    public static int missingCount(String text, Path root) {
        return missing(text, repoFor(root)).size();
    }

    /**
     * True when a reviewer's or critic's issue rests on a code object that does not exist - the issue is dropped. Objects
     * that {@code own} names (the reviewed plan or answer) are judged by their own gates, not here.
     */
    public static boolean citesMissing(String issue, Path root, String own) {
        return !missing(issue, repoFor(root), own, true).isEmpty();
    }

    /** The answer with a note listing the cited objects that do not exist (once its refusals are spent), else the answer. */
    public static String withNote(String answer, Path root, String request) {
        List<Missing> gone = missing(answer, repoFor(root), request, requestNamesExempt());
        if (gone.isEmpty()) {
            return answer;
        }
        return answer + "\n\n[Hermit citation note — these cited code objects do NOT exist in the repository and every claim "
                + "resting on them is void: " + describe(gone) + "]";
    }

    private static String describe(List<Missing> gone) {
        return String.join("; ", gone.stream().limit(8).map(Missing::describe).toList());
    }

    public static Repo repoFor(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        return REPOS.computeIfAbsent(root.toAbsolutePath().normalize(), WorkbenchRepo::new);
    }

    /**
     * The real oracle: tracked files (git ls-files, else a walk), declared and generated types, imported
     * names, the JDK, and CodeGraph for members.
     */
    static final class WorkbenchRepo implements Repo {
        private final Path root;
        private final Map<String, List<Path>> filesByName = new HashMap<>();
        private Set<String> sourceTypes;
        private Set<String> generatedTypes;
        private Set<String> importedNames;
        private final Map<String, Boolean> memberCache = new ConcurrentHashMap<>();
        private CodeGraph graph;
        private boolean graphAttempted;
        private static final Pattern DECLARATION = Pattern.compile(
                "\\b(?:class|interface|enum|record|@interface)\\s+([A-Z][A-Za-z0-9_]*)");
        private static final Pattern IMPORT = Pattern.compile(
                "(?m)^import\\s+(?:static\\s+)?[\\w.]+\\.([A-Z][A-Za-z0-9_]*)(?:\\.[\\w*]+)*\\s*;");

        WorkbenchRepo(Path root) {
            this.root = root;
            for (String rel : listFiles(root)) {
                String name = rel.substring(rel.lastIndexOf('/') + 1);
                filesByName.computeIfAbsent(name, k -> new ArrayList<>()).add(root.resolve(rel));
            }
        }

        @Override
        public List<Path> filesNamed(String spelled) {
            String s = spelled.replace('\\', '/');
            if (s.startsWith("~/")) {
                s = System.getProperty("user.home") + s.substring(1);
            }
            Path asPath = Path.of(s);
            if (asPath.isAbsolute()) {
                return Files.isRegularFile(asPath) ? List.of(asPath) : List.of();
            }
            s = s.startsWith("./") ? s.substring(2) : s;
            String name = s.substring(s.lastIndexOf('/') + 1);
            List<Path> byName = filesByName.getOrDefault(name, List.of());
            if (!s.contains("/")) {
                return byName;
            }
            String suffix = "/" + s;
            List<Path> out = new ArrayList<>();
            for (Path p : byName) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.equals(s) || rel.endsWith(suffix)) {
                    out.add(p);
                }
            }
            if (out.isEmpty() && Files.isRegularFile(root.resolve(s))) {
                out.add(root.resolve(s));
            }
            return out;
        }

        @Override
        public synchronized Boolean typeExists(String simple) {
            if (simple == null || simple.isBlank()) {
                return null;
            }
            if (filesByName.containsKey(simple + ".java") || filesByName.containsKey(simple + ".kt")) {
                return true;
            }
            indexNames();
            if (sourceTypes.contains(simple) || generatedTypes.contains(simple) || importedNames.contains(simple)) {
                return true;
            }
            CodeGraph graph = graph();
            if (graph != null && !graph.sources().fqnsOf(simple).isEmpty()) {
                return true;
            }
            if (jdkType(simple)) {
                return true;
            }
            // a loose name for a real family - `ShelfIndex` for CachedShelfIndex / RemoteShelfIndex - is not an
            // invented type: undecided
            for (Set<String> names : List.of(sourceTypes, generatedTypes)) {
                for (String declared : names) {
                    if (partOf(simple, declared)) {
                        return null;
                    }
                }
            }
            return false;
        }

        static boolean partOf(String name, String declared) {
            if (declared.length() <= name.length()) {
                return false;
            }
            for (int at = declared.indexOf(name); at >= 0; at = declared.indexOf(name, at + 1)) {
                int end = at + name.length();
                if (end == declared.length() || Character.isUpperCase(declared.charAt(end))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Boolean memberExists(String type, String member) {
            return memberCache.computeIfAbsent(type + "#" + member, k -> {
                Boolean decided = decideMember(type, member);
                return decided == null ? Boolean.TRUE : decided; // undecided is cached as "exists": never dismissed
            });
        }

        private Boolean decideMember(String type, String member) {
            CodeGraph graph = graph();
            if (graph != null && !graph.locate(type + "#" + member).isEmpty()) {
                return true;
            }
            List<Path> sources = filesByName.getOrDefault(type + ".java", List.of());
            synchronized (this) {
                indexNames();
            }
            if (sources.isEmpty() || generatedTypes.contains(type)) {
                return null; // a type the sources cannot see whole (generated, a library's): undecided
            }
            return declaredInTypeOrSupertypes(type, member, new HashSet<>(), 0);
        }

        private boolean declaredInTypeOrSupertypes(String type, String member, Set<String> seen, int depth) {
            if (!seen.add(type) || depth > 6) {
                return false;
            }
            for (Path source : filesByName.getOrDefault(type + ".java", List.of())) {
                String text = read(source);
                if (text == null || declaresMember(text, member)) {
                    return true; // declared - or unreadable, which is not a proven absence
                }
                for (String sup : supertypesIn(text, type)) {
                    if (!filesByName.containsKey(sup + ".java") || generatedTypes.contains(sup)) {
                        return true; // a supertype outside the sources: the member may come from it
                    }
                    if (declaredInTypeOrSupertypes(sup, member, seen, depth + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        static boolean declaresMember(String text, String member) {
            String q = Pattern.quote(member);
            return Pattern.compile("(?m)(?:[\\w<>\\[\\],?\\s]+\\s+|^\\s*)" + q + "\\s*\\(").matcher(text).find() // a method
                    || Pattern.compile("[\\w<>\\[\\]]+\\s+" + q + "\\s*[=;,)]").matcher(text).find() // a field or component
                    || Pattern.compile("(?m)^\\s*" + q + "\\s*[(,;{]").matcher(text).find() // an enum constant
                    || Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + q + "\\b").matcher(text).find(); // a nested type
        }

        private static List<String> supertypesIn(String text, String type) {
            Matcher m = Pattern.compile("\\b(?:class|interface|record|enum)\\s+" + Pattern.quote(type)
                    + "\\b([^{]*)\\{").matcher(text);
            List<String> out = new ArrayList<>();
            if (!m.find()) {
                return out;
            }
            String header = m.group(1).replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "");
            Matcher clause = Pattern.compile(
                    "\\b(?:extends|implements|permits)\\b([^{]*?)(?=\\b(?:extends|implements|permits)\\b|$)")
                    .matcher(header);
            while (clause.find()) {
                if (clause.group().startsWith("permits")) {
                    continue;
                }
                for (String part : clause.group(1).split(",")) {
                    String name = part.strip().replaceAll(".*\\.", "").replaceAll("\\W.*", "");
                    if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                        out.add(name);
                    }
                }
            }
            return out;
        }

        private void indexNames() {
            if (sourceTypes != null) {
                return;
            }
            Set<String> declared = new HashSet<>(), imported = new HashSet<>(), generated = new HashSet<>();
            for (List<Path> files : filesByName.values()) {
                for (Path f : files) {
                    if (f.toString().endsWith(".java") || f.toString().endsWith(".kt")) {
                        String text = read(f);
                        if (text != null) {
                            DECLARATION.matcher(text).results().forEach(r -> declared.add(r.group(1)));
                            IMPORT.matcher(text).results().forEach(r -> imported.add(r.group(1)));
                        }
                    }
                }
            }
            for (Path f : generatedSources(root)) {
                String text = read(f);
                if (text != null) {
                    DECLARATION.matcher(text).results().forEach(r -> generated.add(r.group(1)));
                }
            }
            sourceTypes = declared;
            importedNames = imported;
            generatedTypes = generated;
        }

        private CodeGraph graph() {
            String project = System.getProperty("hermit.projectDir");
            if (project == null || !Path.of(project).toAbsolutePath().normalize().equals(root)) {
                return null;
            }
            try {
                if (graph == null) {
                    graph = CodeGraph.build(List.of(root), ClasspathIndex.system());
                }
                return graph;
            } catch (Exception | LinkageError e) {
                return null;
            }
        }

        private static String read(Path f) {
            try {
                return Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        private static boolean jdkType(String simple) {
            for (String pkg : List.of("java.lang.", "java.util.", "java.util.function.", "java.util.concurrent.",
                    "java.io.", "java.nio.file.", "java.nio.", "java.time.", "java.math.", "java.net.",
                    "java.util.stream.", "java.util.regex.")) {
                try {
                    Class.forName(pkg + simple, false, CitationValidator.class.getClassLoader());
                    return true;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // next package
                }
            }
            return false;
        }

        private static List<Path> generatedSources(Path root) {
            List<Path> out = new ArrayList<>();
            try {
                Files.walkFileTree(root, Set.of(), 30, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return name.equals(".git") || name.equals("node_modules") || name.equals(".gradle")
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String s = file.toString().replace('\\', '/');
                        if (s.contains("/build/generated") && s.endsWith(".java")) {
                            out.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // generated sources are optional evidence
            }
            return out;
        }

        private static List<String> listFiles(Path root) {
            try {
                Process p = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
                        .redirectErrorStream(true).start();
                byte[] out = p.getInputStream().readAllBytes();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    List<String> rels = new ArrayList<>();
                    for (String rel : new String(out, StandardCharsets.UTF_8).split("\0")) {
                        if (!rel.isBlank()) {
                            rels.add(rel);
                        }
                    }
                    if (!rels.isEmpty()) {
                        return rels;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // fall back to a walk
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<String> rels = new ArrayList<>();
            try (var walk = Files.walk(root, 40)) {
                walk.filter(Files::isRegularFile)
                        .map(f -> root.relativize(f).toString().replace('\\', '/'))
                        .filter(r -> !r.startsWith(".git/") && !r.contains("/build/") && !r.startsWith("build/"))
                        .forEach(rels::add);
            } catch (IOException | RuntimeException ignored) {
                // an unreadable tree indexes nothing
            }
            return rels;
        }
    }

    /** A proposed new method or test name is not a claim that the object exists. */
    private static boolean introducedAsAProposal(String answer, int at) {
        String before = answer.substring(Math.max(0, at - 90), at);
        return PROPOSAL_LEAD.matcher(before).find();
    }
}
