package hermit.plan;

import hermit.answer.AnswerGate;
import hermit.api.Action;
import hermit.runtime.ToolExecutor;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.graph.CodeGraphTool;
import hermit.tool.process.BashTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The checks a plan passes before its actions run (the agent loop's plan gate): an action that repeats one already
 * run, a read of what is already in context, a placeholder left in a command, a shell command that should be a tool,
 * a write the request did not ask for, and the like. Each returns the issue to put to the planner, or null when the
 * plan passes it.
 */
public final class RepeatGuard {

    private RepeatGuard() {
    }

    public static String excerpt(String s) {
        return excerpt(s, 120);
    }

    public static String excerpt(String s, int max) {
        if (s == null) {
            return "null";
        }
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }

    private static final Pattern DERIVED_PATH_READ = Pattern.compile(
            "(?s)\\b(sed|cat|head|tail|awk|grep|less|more|nl|wc)\\b[^|;&]*\\$\\(\\s*(ls|find|locate|fd|rg|grep|git\\s+ls-files|realpath|readlink|cat|head|tail|echo)\\b[^)]*\\)");

    /**
     * the author read `sed -n '30,80p' $(ls -1 **&#47;InventoryLedger.java | head -n1)` -
     * twice, after a refusal - a file operand derived from a shell lookup: the `**` glob matches nothing in this shell, `ls`
     * prints nothing, and sed waits on stdin until the tool's timeout. A read whose file comes out of `$(ls|find|grep …)`
     * is refused before it runs; the path must be the literal one a tool result (LocateFile, CodeGraph) returned.
     */
    // `sed -n '30,80p' "$(cat /tmp/locate_output_path | head -n1)"` - the next author plan read through a file nothing wrote
    public static String derivedPathReadIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            Matcher m = DERIVED_PATH_READ.matcher(command);
            if (m.find()) {
                return "`" + excerpt(command, 140) + "` derives the file to read from a shell lookup (`$(" + m.group(2)
                        + " …)`) — a guess: a `**` glob matches nothing in this shell, an empty result leaves the read waiting "
                        + "on stdin, and a wrong first hit reads the wrong file. Locate first (LocateFile or CodeGraph locate, "
                        + "one step), then read the LITERAL absolute path that result returned.";
            }
        }
        return null;
    }

    /**
     * the planner called `CodeGraph {action=read, file=…, lineStart=30, lineEnd=70}` - an
     * action the tool does not have (it would have answered "unknown action" and the iteration was gone), for a read that
     * Bash does. A CodeGraph action outside the tool's own list is refused with the list and the tool that does the job.
     */
    public static String unknownActionIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"CodeGraph".equals(a.toolName()) || a.parameters() == null || a.parameters().get("action") == null) {
                continue;
            }
            String action = String.valueOf(a.parameters().get("action")).strip().toLowerCase(Locale.ROOT);
            if (action.isEmpty() || CodeGraphTool.ACTIONS.contains(action)) {
                continue;
            }
            boolean readLike = action.matches("read|view|show|print|cat|source|lines|open|file");
            // `action=search` twice in a row, each refused with "or Bash for a plain grep" -
            // the driver wanted a TEXT search and got no command; the refusal now carries the grep, scoped to the project
            boolean searchLike = action.matches("search|grep|find|text|lookup|query|scan|usages?|references?");
            Object file = a.parameters().get("file");
            Object needle = null;
            for (String key : List.of("symbol", "query", "pattern", "text", "name", "term")) {
                if (a.parameters().get(key) != null) {
                    needle = a.parameters().get(key);
                    break;
                }
            }
            String root = System.getProperty("hermit.projectDir", ".");
            String hint = readLike
                    ? " To read lines of a file use Bash: sed -n 'A,Bp' " + (file == null ? "<absolute path from a tool result>" : file) + "."
                    : searchLike
                    ? " To search text use Bash: grep -rn \"" + (needle == null ? "<text>" : needle) + "\" " + root
                            + " --include=*.java --include=*.json | head -40 (CodeGraph locate takes a Type or Type#method, not a constant or a string)."
                    : " Pick one of them, or Bash for a plain file read/grep.";
            return "CodeGraph has no action '" + action + "' — its actions are locate, callers, callees, reach, tests, impls, escapes, "
                    + "catches, impact, emulate, verbs (and their synonyms); the call would fail." + hint;
        }
        return null;
    }

    /**
     * after `callers stockKeyOf` had answered `caller=ShelfIndex#lookup,
     * file=…/ShelfIndex.java, line=75`, the planner proposed `locate ShelfIndex#lookup` -
     * a lookup whose whole answer already sat in a result. A locate of a symbol that a callers/callees/reach result of this
     * turn names with its file is refused with that file and line, and the read that follows.
     */
    /** `a.b.C#m`, `a.b.C.m` and `C#m` are all `C#m`; a bare type keeps its simple name. */
    static String simpleSymbol(String symbol) {
        String s = symbol.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            String type = s.substring(0, hash);
            return type.substring(type.lastIndexOf('.') + 1) + "#" + s.substring(hash + 1);
        }
        String[] parts = s.split("\\.");
        if (parts.length >= 2 && !parts[parts.length - 2].isEmpty() && Character.isUpperCase(parts[parts.length - 2].charAt(0))
                && !parts[parts.length - 1].isEmpty() && !Character.isUpperCase(parts[parts.length - 1].charAt(0))) {
            return parts[parts.length - 2] + "#" + parts[parts.length - 1];
        }
        return parts[parts.length - 1];
    }

    public static String locatedByResultIssue(List<Action> actions, String contextSoFar) {
        if (actions == null || contextSoFar == null || contextSoFar.isEmpty()) {
            return null;
        }
        for (Action a : actions) {
            if (!"CodeGraph".equals(a.toolName()) || a.parameters() == null
                    || !"locate".equalsIgnoreCase(String.valueOf(a.parameters().getOrDefault("action", "")))) {
                continue;
            }
            String symbol = String.valueOf(a.parameters().getOrDefault("symbol", "")).strip();
            if (symbol.isEmpty() || symbol.equals("null")) {
                continue;
            }
            String simple = simpleSymbol(symbol);
            // a result entry naming the symbol with its file: {line=75, file=/…/X.java, caller=X#m, …} or {…, callee=X#m}
            Matcher m = Pattern.compile(
                    "\\{([^{}]*?)\\b(?:caller|callee|symbol|method|declaration)=([A-Za-z_][\\w.$]*[#.][A-Za-z_]\\w*)([^{}]*)\\}").matcher(contextSoFar);
            while (m.find()) {
                String named = m.group(2);
                String namedSimple = simpleSymbol(named);
                if (!namedSimple.equalsIgnoreCase(simple)) {
                    continue;
                }
                String entry = m.group(1) + m.group(3);
                Matcher f = Pattern.compile("file=(\\S+?\\.java)").matcher(entry);
                Matcher l = Pattern.compile("\\bline=(\\d+)").matcher(entry);
                if (f.find()) {
                    String line = l.find() ? l.group(1) : null;
                    return "'" + symbol + "' is already located by a result this turn: " + f.group(1)
                            + (line == null ? "" : ", line " + line) + " — locating it again adds nothing. Read it instead: sed -n '"
                            + (line == null ? "A,Bp" : Math.max(1, Integer.parseInt(line) - 15) + "," + (Integer.parseInt(line) + 45) + "p")
                            + "' " + f.group(1) + " — or look up a symbol no result has placed yet.";
                }
            }
        }
        return null;
    }

    /**
     * the author wrote `sed -n '1,120p' $(LocateFile name=… maxResults=1 | jq -r '.[0].absolute')` -
     * a Hermit TOOL invoked as a shell command inside a substitution; the shell has no such command, the read gets no
     * file, and the guards for `$(ls …)` and `$(locate_file_output)` did not know the spelling. A registered
     * tool name used as a shell command is refused: a tool runs as its own step and its result is used in the next plan.
     */
    public static String toolInShellIssue(List<Action> actions, Set<String> registeredTools) {
        if (actions == null || registeredTools == null || registeredTools.isEmpty()) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            // the text of a quoted heredoc (<< 'EOF', << "EOF", << \EOF) is data, never run;
            // a document written through one names tools in code spans
            String command = String.valueOf(a.parameters().get("command"))
                    .replaceAll("(?s)<<-?\\s*(?:'(\\w+)'|\"(\\w+)\"|\\\\(\\w+))[^\\n]*\\n.*?\\n\\s*(?:\\1|\\2|\\3)(?=\\n|$)", "<<''");
            for (String tool : registeredTools) {
                if ("Bash".equals(tool)) {
                    continue;
                }
                Matcher m = Pattern.compile(
                        "(?:\\$\\(\\s*|`\\s*|^\\s*|[|;&\\n]\\s*)" + Pattern.quote(tool) + "\\b(?![\\w./-])").matcher(command);
                if (m.find()) {
                    return "`" + excerpt(command, 120) + "` runs the Hermit tool " + tool + " as a shell command — the shell has no such "
                            + "command, so the step fails (and any read fed from it gets no file). A tool is its own plan step: run "
                            + tool + " first, then use the literal value its result returns in the next plan.";
                }
            }
        }
        return null;
    }

    /** A script (Python, Perl, Ruby or Node) that writes, or names a .java file and a write call. */
    private static final Pattern SCRIPT_WRITES = Pattern.compile(
            "(?s)\\b(?:python3?|perl|ruby|node)\\b.*(?:write_text|\\.write\\(|open\\([^)]*['\"][wa]\\+?['\"]|print\\s*>)");
    /** A here-document or tee into a .java file. */
    private static final Pattern HEREDOC_OR_TEE_TO_JAVA = Pattern.compile(
            "\\bcat\\s*>>?\\s*\\S+\\.java\\b|\\btee\\s+(?:-a\\s+)?\\S+\\.java\\b");
    /** An echo or printf redirected into a .java file; group 1 is a printf's quoted format. */
    private static final Pattern ECHO_OR_PRINTF_TO_JAVA = Pattern.compile(
            "\\b(?:echo\\b[^|;&>]*|printf\\s+'([^']*)'[^|;&>]*)>>?\\s*\\S+\\.java\\b");

    /** The start of a here-document: {@code <<EOF}, {@code <<-'EOF'}, {@code << "EOF"}, {@code <<\EOF}; group 1 the delimiter. */
    private static final Pattern HEREDOC_OPEN = Pattern.compile("<<-?\\s*['\"\\\\]?([A-Za-z_][A-Za-z0-9_]*)['\"]?");

    /**
     * The commands of a shell line: cut at {@code &&}, {@code ||}, {@code |}, {@code ;} and line breaks outside quotes,
     * a here-document's body kept with the command that opened it.
     */
    static List<String> shellCommands(String line) {
        List<StringBuilder> parts = new ArrayList<>(List.of(new StringBuilder()));
        char quote = 0;
        String heredoc = null;
        int owner = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            StringBuilder current = parts.get(parts.size() - 1);
            Matcher open = HEREDOC_OPEN.matcher(line).region(i, line.length());
            if (quote != 0) {
                current.append(ch);
                if (ch == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                current.append(ch);
            } else if (ch == '<' && (i == 0 || line.charAt(i - 1) != '<') && open.lookingAt()) {
                heredoc = open.group(1);
                owner = parts.size() - 1;
                current.append(open.group());
                i = open.end() - 1;
            } else if (ch == '\n' && heredoc != null) {
                // the body, up to the line that is the delimiter alone, belongs to the command that opened it
                int from = i + 1;
                while (true) {
                    int eol = line.indexOf('\n', from);
                    String body = line.substring(from, eol < 0 ? line.length() : eol);
                    parts.get(owner).append('\n').append(body);
                    if (eol < 0 || body.strip().equals(heredoc)) {
                        i = eol < 0 ? line.length() : eol;
                        break;
                    }
                    from = eol + 1;
                }
                heredoc = null;
                parts.add(new StringBuilder());
            } else if (ch == ';' || ch == '|' || ch == '\n' || (ch == '&' && i + 1 < line.length() && line.charAt(i + 1) == '&')) {
                if (ch != '\n' && i + 1 < line.length() && line.charAt(i + 1) == ch) {
                    i++;
                }
                parts.add(new StringBuilder());
            } else {
                current.append(ch);
            }
        }
        List<String> out = new ArrayList<>();
        for (StringBuilder p : parts) {
            if (!p.toString().isBlank()) {
                out.add(p.toString().strip());
            }
        }
        return out;
    }

    /**
     * plans wrote Java source with a Python script instead of the tree
     * editor, whose checks (parse, resolve, imports, layout) such text never passes. A shell or program step that writes
     * a .java file is refused; a printf of a new file's package line, a cp and a sed copy of an existing file are not.
     */
    public static String scriptWritesJavaIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            String tool = ToolExecutor.baseToolName(a.toolName());
            Map<String, Object> p = a.parameters();
            if (p == null) {
                continue;
            }
            String text = "Bash".equals(tool) ? String.valueOf(p.get("command"))
                    : "WriteProgram".equals(tool) ? String.valueOf(p.get("code")) : null;
            if (text == null || !text.contains(".java")) {
                continue;
            }
            // a round's one command replaced a ledger row with a Python script and then named the task's .java files
            // for the wording check and git add; read as one script, it was refused. A script writes Java only when
            // its own command names the .java file.
            boolean writes = ("Bash".equals(tool) ? shellCommands(text) : List.of(text)).stream()
                    .anyMatch(c -> SCRIPT_WRITES.matcher(c).find() && c.matches("(?s).*\\.java\\b.*"))
                    || HEREDOC_OR_TEE_TO_JAVA.matcher(text).find();
            Matcher m = ECHO_OR_PRINTF_TO_JAVA.matcher(text);
            while (!writes && m.find()) {
                String format = m.group(1);
                writes = format == null || !format.strip().matches("package [\\w.]+;(\\\\n)?");
            }
            if (writes) {
                return "`" + excerpt(text, 120) + "` writes Java source with a script. Java code is written only "
                        + "through TreeEdit or TreeCodegen (addImport, addType, insertMember, replaceMember, items ...), "
                        + "whose checks a script skips; a printf of a new file's package line, a cp or a sed copy of an "
                        + "existing file are fine.";
            }
        }
        return null;
    }

    /**
     * `CodeGraph locate SCHEDULE_CREATE` twice - an enum constant, not a type or a method;
     * the graph places declarations, and a constant's definition or uses are a text search. A locate of an ALL_CAPS name is
     * refused with the grep, scoped to the project.
     */
    public static String constantLocateIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"CodeGraph".equals(a.toolName()) || a.parameters() == null
                    || !"locate".equalsIgnoreCase(String.valueOf(a.parameters().getOrDefault("action", "")))) {
                continue;
            }
            String symbol = String.valueOf(a.parameters().getOrDefault("symbol", "")).strip();
            String last = symbol.substring(Math.max(symbol.lastIndexOf('.'), symbol.lastIndexOf('#')) + 1);
            if (last.matches("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+") || last.matches("[A-Z]{4,}")) {
                String root = System.getProperty("hermit.projectDir", ".");
                return "'" + symbol + "' is a constant, not a type or a method — CodeGraph locate places declarations of a Type or "
                        + "Type#method. To find where the constant is defined or used: grep -rn \"" + last + "\" " + root
                        + " --include=*.java --include=*.json | head -40.";
            }
        }
        return null;
    }

    private static final Pattern ABSOLUTE_READ_TARGET = Pattern.compile(
            "\\b(?:sed\\s+-n\\s+\\S+|cat|head(?:\\s+-n\\s*\\d+)?|tail(?:\\s+-n\\s*\\d+)?|nl|wc\\s+-l)\\s+(?:\"|')?(/[^\\s\"'|;&<>*?\\[\\]$]+\\.(?:java|json|kt|xml|properties|txt|md|yaml|yml|gradle|kts))");

    /**
     * the author read `sed -n '1,200p' …/billing/InvoiceTotalsTest.java` right
     * after a LocateFile result had said `count=0` for that very name - a path that does not exist, read for the price of an
     * iteration. A plain read of an absolute path inside the workbench that is not on disk is refused before it runs.
     */
    public static String missingFileReadIssue(List<Action> actions, Path root) {
        if (actions == null || root == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            Matcher m = ABSOLUTE_READ_TARGET.matcher(command);
            while (m.find()) {
                Path target = Path.of(m.group(1));
                if (!target.startsWith(root) || Files.exists(target)) {
                    continue;
                }
                Path parent = target.getParent();
                while (parent != null && !Files.isDirectory(parent)) {
                    parent = parent.getParent();
                }
                return "`" + excerpt(command, 120) + "` reads " + target.getFileName() + ", but no such file exists (the nearest existing "
                        + "directory is " + (parent == null ? root : parent) + "). Read only a path a tool result returned — a LocateFile "
                        + "match, a CodeGraph declaration's file, a grep hit — never a path spelled from memory.";
            }
        }
        return null;
    }

    /**
     * Reads shell words from {@code text} at {@code at} into {@code words} until an unquoted `;`, `&`, `|`, newline or
     * backtick (or 24 words); returns where it stopped. A word that is a lone period or ends a sentence closes the command.
     */
    static int shellWords(String text, int at, List<String> words) {
        StringBuilder word = null;
        int i = at;
        for (; i < text.length() && words.size() < 24; i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                int close = text.indexOf('\'', i + 1);
                if (close < 0) {
                    break;
                }
                (word == null ? word = new StringBuilder() : word).append(text, i + 1, close);
                i = close;
            } else if (c == '"') {
                StringBuilder w = word == null ? word = new StringBuilder() : word;
                int j = i + 1;
                for (; j < text.length() && text.charAt(j) != '"'; j++) {
                    char d = text.charAt(j);
                    if (d == '\\' && j + 1 < text.length() && "\"\\$`".indexOf(text.charAt(j + 1)) >= 0) {
                        w.append(text.charAt(++j));
                    } else {
                        w.append(d);
                    }
                }
                if (j >= text.length()) {
                    break;
                }
                i = j;
            } else if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                (word == null ? word = new StringBuilder() : word).append(text.charAt(++i));
            } else if (Character.isWhitespace(c) && c != '\n') {
                if (word != null) {
                    words.add(word.toString());
                    word = null;
                }
            } else if (c == ';' || c == '&' || c == '|' || c == '\n' || c == '`') {
                break;
            } else if (word == null && (c == '(' || c == '\u2014')) {
                break;   // prose after the command: "… File.java (how it merges entries)" or "… - why"
            } else {
                (word == null ? word = new StringBuilder() : word).append(c);
            }
        }
        if (word != null) {
            words.add(word.toString());
        }
        return i;
    }

    /**
     * `CodeGraph locate ShippingQuote#regionalRate` - a class no tool result had
     * named and that exists nowhere in the repository: a name the planner made up, located for the price of an iteration.
     * A locate of a type that no result names and for which no source file exists under the workbench is refused with the
     * grep that would find the real one.
     */
    public static String unknownTypeLocateIssue(List<Action> actions, String toolResults, Path root) {
        if (actions == null || root == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"CodeGraph".equals(a.toolName()) || a.parameters() == null
                    || !"locate".equalsIgnoreCase(String.valueOf(a.parameters().getOrDefault("action", "")))) {
                continue;
            }
            String symbol = String.valueOf(a.parameters().getOrDefault("symbol", "")).strip();
            String simple = simpleSymbol(symbol);
            String type = simple.contains("#") ? simple.substring(0, simple.indexOf('#')) : simple;
            if (type.isEmpty() || !Character.isUpperCase(type.charAt(0)) || type.matches("[A-Z][A-Z0-9_]*")) {
                continue;   // not a type name (a constant is another check's)
            }
            String member = simple.contains("#") ? simple.substring(simple.indexOf('#') + 1) : null;
            if (toolResults != null && toolResults.contains(type)) {
                // `locate PlaylistImporterTest` - a type that results name ONLY inside callers entries
                // with `receiverType=?`, a same-named method of another type matched by name (the trail of the check above, followed one step further)
                if (groundedOnlyByUnresolvedReceivers(toolResults, type)) {
                    return "'" + symbol + "' is named only by callers entries with receiverType=? — a same-named method of ANOTHER type, "
                            + "matched by name, not a caller of the method under review. Do not follow that trail: use a symbol a resolved "
                            + "result placed, or grep the method's own callers: grep -rn \"" + (member == null ? type : member) + "\" " + root
                            + " --include=*.java | head -20.";
                }
                continue;   // a result named it: grounded
            }
            Path source = sourceFileOf(root, type);
            if (source != null) {
                // `locate RateTable#regionalRate` - a real type, a made-up
                // member (the grep just run showed the method it wanted is the EXPRESS case at line 97)
                if (member == null || memberNamedIn(source, member) || (toolResults != null && toolResults.contains(member))) {
                    continue;   // the type exists (and the member is in its file or a result)
                }
                return "'" + symbol + "': " + type + ".java exists (" + source + ") but declares no '" + member + "' and no tool result "
                        + "names it — a member made up. Read the file where the results point instead: grep -n \"" + member.replaceAll("[^A-Za-z0-9_]", "")
                        + "\" " + source + " (or sed -n around the line a result gave).";
            }
            return "'" + symbol + "' appears in no tool result this turn and no source file " + type + ".java exists under the "
                    + "repository — a name made up, not found. Use a type a result named, or find the real one: grep -rn \""
                    + (member == null ? type : member) + "\" " + root + " --include=*.java | head -20.";
        }
        return null;
    }

    private static Path sourceFileOf(Path root, String type) {
        // a walk 12 levels deep called HeatingCurveParser.java - fourteen directories
        // down, like most of this repository's classes - "not found", and the first correct locate of the round was refused.
        try (Stream<Path> walk = Files.walk(root, 40)) {
            String name = type + ".java";
            return walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(name)
                    && !p.toString().contains("/build/")).findFirst().orElse(null);
        } catch (IOException | UncheckedIOException e) {
            return root;   // cannot tell: treated as existing, the tool decides
        }
    }

    private static boolean memberNamedIn(Path source, String member) {
        if (source == null || member == null || member.isBlank()) {
            return true;
        }
        try {
            if (!Files.isRegularFile(source)) {
                return true;
            }
            String text = Files.readString(source);
            return text.matches("(?s).*\\b" + Pattern.quote(member.replaceAll("\\(.*", "")) + "\\b.*");
        } catch (IOException e) {
            return true;
        }
    }

    private static final Pattern RANGE_READ = Pattern.compile(
            "sed\\s+-n\\s+['\"]?(\\d+),(\\d+)p['\"]?\\s+(\\S*?([A-Za-z_][\\w$]*\\.(?:java|json|kt)))");

    /** About how many numbered lines arrive within the output cap (≈ 50 characters a line; 90 at 4500). */
    static int readLinesThatArrive() {
        return readOutputCap() / 50;
    }

    /**
     * `sed -n '1,200p' RateTable.java` right after a grep had placed the decisive
     * lines at 122 and 133 - the planner's view is capped at about ninety lines, so the read would have ended near line 90 and
     * the lines it was for would never have arrived. A read of more than a hundred lines is refused with the range around the
     * grep hits the results hold for that file, or with the sixty-line rule.
     */
    public static String oversizedReadIssue(List<Action> actions, String toolResults) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            Matcher m = RANGE_READ.matcher(command);
            if (!m.find()) {
                continue;
            }
            int from = Integer.parseInt(m.group(1));
            int to = Integer.parseInt(m.group(2));
            if (to - from + 1 <= readLinesThatArrive() + 10) {
                continue;
            }
            String file = m.group(4);
            List<Integer> hits = new ArrayList<>();
            if (toolResults != null) {
                Matcher h = Pattern.compile(Pattern.quote(file) + ":(\\d+):").matcher(toolResults);
                while (h.find()) {
                    hits.add(Integer.parseInt(h.group(1)));
                }
            }
            String suggestion;
            if (hits.isEmpty()) {
                suggestion = "read at most 60 lines at a time, starting where the located declaration is (sed -n '" + from + "," + (from + 59) + "p' …).";
            } else {
                int lo = Math.max(1, Collections.min(hits) - 10);
                int hi = Collections.max(hits) + 30;
                if (hi - lo > readLinesThatArrive()) {
                    hi = lo + readLinesThatArrive() - 1;
                }
                suggestion = "the grep hits for this file sit at lines " + hits + " — read around them: sed -n '" + lo + "," + hi + "p' " + m.group(3) + ".";
            }
            return "`" + excerpt(command, 120) + "` asks for " + (to - from + 1) + " lines, but a read arrives capped at about " + readLinesThatArrive()
                    + " lines — the rest never reaches you, and lines past ~" + (from + readLinesThatArrive()) + " are lost. Instead, " + suggestion;
        }
        return null;
    }

    /**
     * Supervised rewrite drive, FM-018: a shell step that bash cannot parse (here-documents chained with `&&` on the
     * line after their delimiter) runs only up to the error and then fails. `bash -n` reads it without running it.
     */
    public static String shellSyntaxIssue(List<Action> actions) {
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if (!"Bash".equals(a.toolName()) || c == null || a.parameters().get("remote") != null) {
                continue;
            }
            try {
                Process p = new ProcessBuilder("bash", "-n", "-c", String.valueOf(c)).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() != 0) {
                    return "Step " + (i + 1) + "'s shell command does not parse, so it would stop at the error: "
                            + excerpt(out.replace("bash: -c: ", ""), 220) + ". Fix the syntax (a line after a here-document's "
                            + "closing delimiter starts a new command: do not begin it with && or |).";
                }
            } catch (IOException e) {
                return null;   // no bash to ask: the step runs as before
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static final Pattern READ_SEGMENT = Pattern.compile(
            "^\\s*(?:cat\\s+(\\S+)|sed\\s+-n\\s+['\"]?(\\d+),(\\d+|\\$)p['\"]?\\s+(\\S+)|head\\s+-(?:n\\s*)?(\\d+)\\s+(\\S+))\\s*$");
    /** {@code sed -n '/start/,/end/p' file}: a read by a pattern range, with nothing but the print command. */
    private static final Pattern PATTERN_RANGE_READ = Pattern.compile(
            "^\\s*sed\\s+-n\\s+'(/[^/']*/,/[^/']*/p)'\\s+(\\S+)\\s*$");

    /** The characters a pattern-range read prints, measured by running it (a sed that only prints); 0 when it cannot run. */
    static long patternRangeChars(String expression, Path file) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            Process process = new ProcessBuilder("sed", "-n", expression, file.toString()).redirectErrorStream(true).start();
            byte[] out = process.getInputStream().readAllBytes();
            process.waitFor(5, TimeUnit.SECONDS);
            return out.length;
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static final Pattern CD_SEGMENT = Pattern.compile("^\\s*cd\\s+(\\S+)\\s*$");

    /**
     * Supervised rewrite drive, FM-003: one shell step's file reads (cat, sed -n A,Bp, head -N of files that exist) print
     * more than the context keeps of one result, so the middle of the output would be lost unseen. The size is known
     * before the step runs: the step is refused with a range that fits.
     */
    public static String oversizedReadIssue(List<Action> actions, Path root) {
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if (!"Bash".equals(a.toolName()) || c == null || String.valueOf(c).contains("|") || String.valueOf(c).contains("<<")) {
                continue;
            }
            Path cwd = root;
            Object wd = a.parameters().get("workingDir");
            if (wd != null && !String.valueOf(wd).isBlank()) {
                cwd = Path.of(String.valueOf(wd));
            }
            long total = 0;
            Path biggest = null;
            long biggestChars = 0;
            for (String segment : String.valueOf(c).split("&&|;|\\n")) {
                Matcher cd = CD_SEGMENT.matcher(segment);
                if (cd.matches()) {
                    cwd = cwd == null ? Path.of(cd.group(1)) : cwd.resolve(cd.group(1));
                    continue;
                }
                Matcher pr = PATTERN_RANGE_READ.matcher(segment);
                if (pr.matches()) {
                    // a range by patterns was not measured, and its end
                    // pattern ('/^\\s*}/' in a sed without \\s, '/^ }/' where methods end in four spaces) never matched,
                    // so the read ran to the end of the file. The read is side-effect free: run it and measure it.
                    Path file = cwd == null ? Path.of(pr.group(2)) : cwd.resolve(pr.group(2));
                    long chars = patternRangeChars(pr.group(1), file);
                    total += chars;
                    if (chars > biggestChars) {
                        biggestChars = chars;
                        biggest = file;
                    }
                    continue;
                }
                Matcher m = READ_SEGMENT.matcher(segment);
                if (!m.matches()) {
                    continue;
                }
                String name = m.group(1) != null ? m.group(1) : m.group(4) != null ? m.group(4) : m.group(6);
                Path file = cwd == null ? Path.of(name) : cwd.resolve(name);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    List<String> lines = Files.readAllLines(file);
                    int from = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                    int to = m.group(3) != null ? ("$".equals(m.group(3)) ? lines.size() : Integer.parseInt(m.group(3)))
                            : m.group(5) != null ? Integer.parseInt(m.group(5)) : lines.size();
                    long chars = 0;
                    for (int l = Math.max(1, from); l <= Math.min(to, lines.size()); l++) {
                        chars += lines.get(l - 1).length() + 1;
                    }
                    total += chars;
                    if (chars > biggestChars) {
                        biggestChars = chars;
                        biggest = file;
                    }
                } catch (IOException | RuntimeException e) {
                    // unreadable here: the step runs as before
                }
            }
            if (total > readOutputCap() && biggest != null) {
                int fits = 0;
                long chars = 0;
                try {
                    for (String line : Files.readAllLines(biggest)) {
                        if (chars + line.length() + 1 > readOutputCap() - 500) {
                            break;
                        }
                        chars += line.length() + 1;
                        fits++;
                    }
                } catch (IOException e) {
                    fits = 40;
                }
                return "Step " + (i + 1) + "'s reads print about " + total + " characters, and the context keeps only "
                        + readOutputCap() + " of one result, so the middle would be lost. Read in smaller parts, each its own "
                        + "action: e.g. sed -n '1," + Math.max(1, fits) + "p' " + biggest + " (about " + chars + " characters), "
                        + "then the next range.";
            }
        }
        return null;
    }

    /**
     * the longest prefix of a multi-step plan that the gate passes - the steps before the first one it refuses.
     * Empty when even the first step is refused; never the whole plan (the caller has already seen it refused).
     */
    static final Pattern SHELL_FILE_WRITE = Pattern.compile(
            "(?s)>{1,2}\\s*[^\\s<>|&;]+\\s*<<|<<-?\\s*['\"]?\\w+['\"]?\\s*>{1,2}\\s*[^\\s<>|&;]+|\\btee\\s+(?:-a\\s+)?[^\\s<>|&;]+");
    static final Pattern SHELL_FILE_READ = Pattern.compile(
            "(?:^|&&|;|\\|)\\s*(?:sed\\s+-n|cat\\s+(?!>)|head\\b|tail\\b|grep\\b|nl\\b|wc\\b)");

    /**
     * Supervised rewrite drive, FM-021: the shell tool refuses a whole command when any part of it edits a Java source in
     * place (sed -i, perl -pi, awk into a .java file), so nothing of it runs. Said before approval, not after.
     */
    public static String inPlaceJavaEditIssue(List<Action> actions) {
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if ("Bash".equals(a.toolName()) && c != null
                    && BashTool.editsJavaSourceInPlace(HEREDOC_BODY_TEXT.matcher(String.valueOf(c)).replaceAll(""))) {
                return "Step " + (i + 1) + " edits a .java file in place (sed -i / perl -pi / awk into the file); the shell "
                        + "tool refuses the WHOLE command for that, so none of it would run. Rewrite the Java file whole with a "
                        + "here-document, or edit it with TreeEdit; sed -i stays fine for non-Java files.";
            }
        }
        return null;
    }

    private static final Pattern HEREDOC_BODY_TEXT = Pattern.compile(
            "(?s)<<-?\\s*(['\"]?)([A-Za-z_]\\w*)\\1[^\\n]*\\n.*?\\n\\2[ \\t]*(?=\\n|$)");

    /** FM-012 within one step: a shell command that reads files and then writes one cannot use what it reads. */
    public static String readThenWriteInOneCommandIssue(List<Action> actions) {
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if (!"Bash".equals(a.toolName()) || c == null) {
                continue;
            }
            String command = String.valueOf(c);
            Matcher w = SHELL_FILE_WRITE.matcher(command);
            // the text before the redirect, without the write's own `cat` / `tee`
            if (w.find() && SHELL_FILE_READ.matcher(command.substring(0, w.start()).replaceAll("\\b(?:cat|tee)\\s*$", "")).find()) {
                return "Step " + (i + 1) + " reads files and then writes one in the same command, so the written content "
                        + "cannot use what the reads print. Run the reads as their own step first; write in the next plan.";
            }
        }
        return null;
    }

    /**
     * besides the items the request named, a port item replaced a later part's stub
     * ('throw new UnsupportedOperationException("not implemented")') with 'return new TypeHierarchy();', a plausible
     * success that the test-first rules forbid. A replace edit of a method whose body is that stub, when the request
     * does not name the method, is refused; null when there is none.
     */
    public static String unrequestedStubIssue(List<Action> actions, String request, Path defaultDir) {
        if (actions == null || request == null) {
            return null;
        }
        for (Action a : actions) {
            String tool = ToolExecutor.baseToolName(a.toolName());
            Map<String, Object> p = a.parameters();
            if (p == null || !("TreeCodegen".equals(tool) || "TreeEdit".equals(tool))) {
                continue;
            }
            List<Map<?, ?>> edits = new ArrayList<>();
            if (p.get("items") instanceof List<?> items) {
                items.stream().filter(Map.class::isInstance).forEach(i -> edits.add((Map<?, ?>) i));
            } else if ("TreeEdit".equals(tool)) {
                edits.add(p);
            }
            for (Map<?, ?> e : edits) {
                String op = String.valueOf(e.get("op"));
                Object file = e.get("file") != null ? e.get("file") : p.get("file");
                Object method = e.get("method");
                if (file == null || method == null || !(op.equals("replaceBody") || op.equals("replaceMember"))) {
                    continue;
                }
                if (String.valueOf(e.get("code")).contains("UnsupportedOperationException(\"not implemented\")")) {
                    continue;   // a stub stays a stub (rewrite drive t060: a comment fixed around an unchanged stub)
                }
                String name = String.valueOf(method).replaceAll("\\(.*", "");
                // a bare method name belongs to the file's own type (rewrite drive t059: "closing" in Reader.java)
                String owner = name.contains("#") ? name.substring(0, name.lastIndexOf('#'))
                        : Path.of(String.valueOf(file)).getFileName().toString().replaceAll("\\.java$", "");
                owner = owner.substring(owner.lastIndexOf('.') + 1);
                name = name.substring(name.lastIndexOf('#') + 1);
                if (request.matches("(?s).*\\b" + Pattern.quote(name) + "\\b.*")
                        || (!owner.isEmpty() && namesEveryMethodOf(request, owner)) || asksForTheStubs(request)) {
                    continue;
                }
                Path path = Path.of(String.valueOf(file));
                if (!path.isAbsolute() && defaultDir != null) {
                    path = defaultDir.resolve(path);
                }
                try {
                    String source = Files.readString(path);
                    int[] body = TreeEditTool.rewrittenLines(source, "replaceBody", String.valueOf(method), null);
                    if (body == null) {
                        continue;
                    }
                    List<String> lines = source.lines().toList().subList(body[0] - 1, Math.min(body[1], (int) source.lines().count()));
                    if (String.join("\n", lines).contains("UnsupportedOperationException(\"not implemented\")")) {
                        return "The plan replaces the stub of " + name + ", which this request does not name: a later part "
                                + "ports it, and until then it keeps throwing UnsupportedOperationException(\"not implemented\") "
                                + "(a stub never returns a plausible value). Leave that method as it is and edit only what the "
                                + "request names.";
                    }
                } catch (Exception unreadable) {
                    // no file, no check
                }
            }
        }
        return null;
    }

    /**
     * Whether the request asks to port the stubs themselves ("port the stubbed methods", "replace every stub", "port the
     * methods except the writer's"): then each stub of the file is named. Rewrite drive t069: a request to port "the
     * stubbed methods" of a class had each of its sixteen replacements refused; t077 the same for "the methods".
     */
    static boolean asksForTheStubs(String request) {
        return Pattern.compile("(?i)\\b(?:port|replace|implement|fill)\\b[^.\\n]{0,40}?\\b(?:the|every|each|all(?: the)?)\\s+"
                + "(?:stubbed methods|stubs?|stubbed members|methods)\\b").matcher(request).find();
    }

    /**
     * Whether the request names every method of the type: "each H method", "every method of H", "all of H's methods".
     * Rewrite drive t058: "Port each ClaimLedger method" was refused method by method because no method was named.
     */
    static boolean namesEveryMethodOf(String request, String type) {
        String t = Pattern.quote(type);
        String other = "(?:other\\s+|remaining\\s+)?";
        String quantifier = "\\b(?:each|every|all)\\s+(?:of\\s+)?(?:the\\s+)?" + other;
        String words = "(?:\\w+\\s+){0,2}?";   // "the nested WorkbenchStore", "each public Store method"
        return Pattern.compile("(?i)" + quantifier + words + t + "(?:'s)?\\s+" + other + "methods?\\b"
                + "|" + quantifier + "methods?\\s+of\\s+(?:the\\s+)?" + words + t + "\\b").matcher(request).find();
    }

    /** every `{…}` result entry that names the class carries `receiverType=?` (an unresolved, name-only match). */
    static boolean groundedOnlyByUnresolvedReceivers(String ctx, String simpleClass) {
        int from = 0;
        boolean any = false;
        while (true) {
            int at = ctx.indexOf(simpleClass, from);
            if (at < 0) {
                break;
            }
            any = true;
            int open = ctx.lastIndexOf('{', at);
            int close = ctx.indexOf('}', at);
            if (open < 0 || close < 0) {
                return false;   // named outside any result entry (a read, a grep line): grounded
            }
            String entry = ctx.substring(open, close + 1);
            if (!entry.contains("receiverType=?")) {
                return false;
            }
            from = at + simpleClass.length();
        }
        return any;
    }

    /** a HindsightDebugger recording whose test method (or class) appears in no tool result this turn. */
    public static String inventedTestMethodIssue(List<Action> actions, String contextSoFar) {
        if (actions == null) {
            return null;
        }
        String ctx = contextSoFar == null ? "" : contextSoFar;
        for (Action a : actions) {
            if (!"HindsightDebugger".equals(a.toolName()) || a.parameters() == null) {
                continue;
            }
            String method = String.valueOf(a.parameters().getOrDefault("testMethod", "")).strip();
            String clazz = String.valueOf(a.parameters().getOrDefault("testClass", "")).strip();
            String simpleClass = clazz.contains(".") ? clazz.substring(clazz.lastIndexOf('.') + 1) : clazz;
            if (!method.isEmpty() && !method.equals("null") && !ctx.matches("(?s).*\\b" + Pattern.quote(method) + "\\b.*")) {
                return "HindsightDebugger names testMethod '" + method + "', which appears in NO tool result this turn — a guessed name "
                        + "(the last one 'did not exist'). Find a real test first: grep -n -A2 '@Test' <the test file a LocateFile result "
                        + "named>, or read that class with sed -n, then record the method the grep shows.";
            }
            if (!simpleClass.isEmpty() && !simpleClass.equals("null") && !ctx.contains(simpleClass)) {
                return "HindsightDebugger names testClass '" + clazz + "', which appears in NO tool result this turn — locate the test "
                        + "class first (LocateFile name=*Test.java in the module), then record a method it actually declares.";
            }
            // the only result naming the test was a callers entry with `receiverType=?` - a
            // same-named method (PlaylistImporter.TrackParser#appendTrack) of ANOTHER type, matched by name; the
            // recording of that test could never reach the trigger. A test grounded only by unresolved-receiver entries is refused.
            if (!simpleClass.isEmpty() && !simpleClass.equals("null") && groundedOnlyByUnresolvedReceivers(ctx, simpleClass)) {
                Object trigger = a.parameters().get("trigger");
                return "HindsightDebugger names testClass '" + clazz + "', but the only result naming it is a callers entry with "
                        + "receiverType=? — a same-named method of ANOTHER type matched by name, so that test cannot reach "
                        + (trigger == null ? "the trigger" : "'" + trigger + "'") + ". Find a test that reaches the method itself: "
                        + "CodeGraph action=tests on " + (trigger == null ? "the trigger method" : "'" + trigger + "'")
                        + ", or a LocateFile of *Test.java in ITS module.";
            }
        }
        return null;
    }

    /** a sed/cat/grep read whose file operand is a bare `Name.java` (no directory) that the repository root does not hold. */
    public static String bareFileNameReadIssue(List<Action> actions, Set<String> executedSignatures, Path root) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            for (String segment : command.split("\\|\\||&&|[|;]")) {
                String[] w = shellWords(segment.strip());
                if (w.length < 2 || !w[0].matches("sed|cat|grep|head|tail|awk")) {
                    continue;
                }
                for (int i = 1; i < w.length; i++) {
                    String op = w[i].replaceAll("^[\"']|[\"']$", "");
                    if (op.matches("[A-Za-z_][\\w$]*\\.(java|kt|md|txt|json|yaml|yml|gradle|xml)") && !op.contains("/")
                            && (root == null || !Files.exists(root.resolve(op)))) {
                        String known = null;
                        for (String sig : executedSignatures) {
                            Matcher p = Pattern.compile("(/[^\\s,}=\"']*" + Pattern.quote(op) + ")").matcher(sig);
                            if (p.find()) {
                                known = p.group(1);
                                break;
                            }
                        }
                        return "`" + op + "` is a bare file name with no directory, and the repository root holds no such file — the shell "
                                + "will read nothing. " + (known != null ? "Use the path CONTEXT SO FAR already gives: " + known
                                : "Locate it first (CodeGraph action=locate on the type, or LocateFile name=" + op + ") and read with the absolute path the result gives.");
                    }
                }
            }
        }
        return null;
    }

    /** the program tools have no place in an analysis-only request. */
    static final Set<String> PROGRAM_TOOLS = Set.of("WriteProgram", "RunProgram", "TddCycle", "GenerateCode");

    /**
     * the request listed TreeCodegen's items one by one ("The items: 1. ... 2. ..."); the plan
     * called TreeCodegen with none, which makes the tool author its own edits from the task sentence. Refused before it
     * runs, when the request lists items.
     */
    public static String missingRequestItemsIssue(List<Action> actions, String request) {
        if (actions == null || request == null || !request.matches("(?s).*\\bThe items?:\\s*\\n\\s*1\\..*")) {
            return null;
        }
        for (Action a : actions) {
            if (!"TreeCodegen".equals(ToolExecutor.baseToolName(a.toolName())) || a.parameters() == null) {
                continue;
            }
            Object items = a.parameters().get("items");
            if (!(items instanceof List<?> list) || list.isEmpty()) {
                return "The request lists TreeCodegen's items one by one, and this TreeCodegen call has no 'items': the "
                        + "tool would author its own edits from the task sentence. Pass the request's items in 'items', "
                        + "each written from the code you read.";
            }
        }
        return null;
    }

    public static String programToolInAnalysisIssue(List<Action> actions, String request) {
        if (actions == null || !analysisOnly(request)) {
            return null;
        }
        for (Action a : actions) {
            if (PROGRAM_TOOLS.contains(a.toolName())) {
                return "'" + a.toolName() + "' writes or runs a program — this is an analysis-only task, decided by reading the code: "
                        + "use Bash (sed -n / grep -n on a file in the repository), CodeGraph (locate/callers/callees/reach) or "
                        + "LocateFile, or write the Shape B final_answer from CONTEXT SO FAR.";
            }
        }
        return null;
    }

    /** a shell segment as words - `$(…)`, `"…"` and `'…'` stay whole. */
    static String[] shellWords(String segment) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '$' && i + 1 < segment.length() && segment.charAt(i + 1) == '(') {
                depth++;
                cur.append(c);
            } else if (c == '(' && depth > 0) {
                depth++;
                cur.append(c);
            } else if (c == ')' && depth > 0) {
                depth--;
                cur.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            words.add(cur.toString());
        }
        return words.toArray(new String[0]);
    }

    /** a recursive grep/find whose root leaves the repository (`..`, `/`, `~`, `$HOME`) or is the whole repository root. */
    public static String unscopedSearchIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            for (String segment : command.split("\\|\\||&&|[|;]")) {
                // `grep -R --line-number "X" $(git rev-parse --show-toplevel) | head` slipped
                // through - split on whitespace, the substitution became three tokens and the root read "rev-parse".
                // A `$(…)` substitution and a quoted string are ONE operand.
                String[] tokens = shellWords(segment.strip());
                if (tokens.length < 2) {
                    continue;
                }
                String root = null;
                if (tokens[0].equals("grep") || tokens[0].endsWith("/grep")) {
                    boolean recursive = false;
                    List<String> operands = new ArrayList<>();
                    for (int i = 1; i < tokens.length; i++) {
                        if (tokens[i].startsWith("-") && tokens[i].length() > 1) {
                            recursive |= tokens[i].matches("-[a-zA-Z]*[rR][a-zA-Z]*") || tokens[i].equals("--recursive");
                        } else {
                            operands.add(tokens[i]);
                        }
                    }
                    if (recursive && operands.size() >= 2) {
                        root = operands.get(operands.size() - 1);
                    } else if (recursive && operands.size() == 1) {
                        root = ".";   // grep -r pattern → the current directory, the repository root
                    }
                } else if (tokens[0].equals("find") || tokens[0].endsWith("/find")) {
                    root = tokens[1].startsWith("-") ? "." : tokens[1];
                }
                if (root == null) {
                    continue;
                }
                String bare = root.replaceAll("^[\"']|[\"']$", "");
                boolean outside = bare.startsWith("..") || bare.equals("/") || bare.startsWith("~") || bare.startsWith("$HOME");
                boolean wholeRepo = bare.equals(".") || bare.equals("./") || bare.matches("\\$\\(git rev-parse --show-toplevel\\)/?");
                if (outside || wholeRepo) {
                    return "`" + segment.strip() + "` searches recursively from `" + bare + "` — "
                            + (outside ? "OUTSIDE the repository, every project on this disk" : "the WHOLE repository (thousands of files, most of them generated)")
                            + ". Root the search in the module you are analysing (e.g. grep -rn '<name>' <project>/<module>/src/main/java), "
                            + "or use CodeGraph callers/locate for a symbol.";
                }
            }
        }
        return null;
    }

    /** the first action whose parameter map is null or empty, as a refusal; null when every action has parameters. */
    public static String parameterlessActionIssue(ParsedPlan plan) {
        if (plan == null || plan.branches() == null) {
            return null;
        }
        for (Branch b : plan.branches()) {
            String issue = parameterlessActionIssue(b.actions());
            if (issue != null) {
                return issue;
            }
        }
        return null;
    }

    public static String parameterlessActionIssue(List<Action> actions) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (a.parameters() == null || a.parameters().isEmpty()) {
                return "'" + a.toolName() + "' is planned with NO parameters — nothing to run. Every tool here takes parameters "
                        + "(a Bash command, a CodeGraph action and symbol, a LocateFile name…); write the concrete call, or answer from CONTEXT SO FAR.";
            }
        }
        return null;
    }

    /** the request forbids edits or asks for analysis only. */
    public static boolean analysisOnly(String request) {
        return request != null && request.matches("(?is).*(do not edit|don'?t edit|must not edit|no edits?\\b|analysis[- ]only|read[- ]only task).*");
    }

    /** A stable signature of an action: tool + its parameters (sorted). */
    /** parameters that do not change WHAT a call looks up - a repeat with one of them added is the same call. */
    static final Set<String> INCIDENTAL_PARAMS = Set.of("projectDir", "maxDepth", "root", "maxResults", "timeoutSec", "workingDir");

    public static String signature(Action a) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (a.parameters() != null) {
            a.parameters().forEach((k, v) -> {
                if (!INCIDENTAL_PARAMS.contains(k)) {   // callers of the same symbol, once with projectDir
                    sorted.put(canonicalKey(k, a.parameters()), canonicalValue(v));
                }
            });
        }
        return a.toolName() + "|" + sorted;
    }

    /** Aliases the tools' role repair folds into {@code symbol} for SINGLE-symbol actions. */
    private static final Set<String> SYMBOL_ALIASES = Set.of("target", "method", "spec");
    /** The actions that take a start AND a target, where the two roles must stay apart. */
    private static final Set<String> TWO_SYMBOL_ACTIONS = Set.of("reach", "flowreach");

    /** {@code reach{target=X}} and {@code reach{symbol=X}} are the SAME call - the tool folds
     *  the alias - so the repeat guard must sign them alike, except for the two-symbol actions. */
    static String canonicalKey(String key, Map<String, Object> parameters) {
        if (key == null || !SYMBOL_ALIASES.contains(key.toLowerCase(Locale.ROOT))
                || parameters.containsKey("symbol")) {
            return key;
        }
        Object action = parameters.get("action");
        if (action != null && TWO_SYMBOL_ACTIONS.contains(String.valueOf(action).toLowerCase(Locale.ROOT))) {
            return key;
        }
        return "symbol";
    }

    /** {@code catches{symbol=com.x.TaskQueue#m}} and {@code catches{symbol=TaskQueue#m}}
     *  are the SAME call - the tools normalize the qualification internally - so the repeat guard must too, or a
     *  re-qualified plan costs a whole iteration. Also normalizes {@code Type:method} and stray whitespace. */
    static Object canonicalValue(Object value) {
        if (!(value instanceof String v)) {
            return value;
        }
        String s = v.trim().replaceAll("\\s+", " ");
        if (s.matches("[A-Za-z_][\\w.$]*[#:][A-Za-z_]\\w*")) {
            String spec = s.replace(':', '#');
            String type = spec.substring(0, spec.indexOf('#'));
            int dot = type.lastIndexOf('.');
            return (dot < 0 ? type : type.substring(dot + 1)) + spec.substring(spec.indexOf('#'));
        }
        return s;
    }

    public static String repeatIssue(List<Action> actions, Map<String, String> executed) {
        if (actions == null || actions.isEmpty() || executed.isEmpty()) {
            return null;
        }
        // the FIRST action repeated a FAILED edit verbatim three plans running; the plan slipped
        // through because the gradle/commit actions after it were new. Everything after a failed first action is
        // moot - refuse the plan on that alone.
        String first = executed.get(signature(actions.get(0)));
        if (first != null && !"Success".equals(first)) {
            return "The FIRST action of this plan repeats, VERBATIM, a call that FAILED this turn with the same "
                    + "parameters — the steps after it cannot succeed either. Read the error text in CONTEXT SO FAR "
                    + "(it lists what to copy), change the parameters, and only then plan what follows.";
        }
        boolean anyFailedRepeat = false;
        for (Action a : actions) {
            String st = executed.get(signature(a));
            if (st == null) {
                return null;   // something new is being tried - let it run
            }
            if (!"Success".equals(st)) {
                anyFailedRepeat = true;
            }
        }
        if (anyFailedRepeat) {
            return "Every action in this plan repeats, VERBATIM, a call that already ran this turn — at least one of "
                    + "them FAILED with the same parameters. Change the parameters or the tool (read the error text in "
                    + "CONTEXT SO FAR), or conclude honestly.";
        }
        return "Every action in this plan repeats, VERBATIM, a call that already SUCCEEDED this turn — its results "
                + "are in CONTEXT SO FAR. Do not re-run them: write the Shape B final_answer from those results, or "
                + "propose a NEW action that moves the task forward.";
    }

    // `$(git rev-parse --show-toplevel)/…` prefixes, and a bare `$(git ls-files | grep 'X.java')`
    static final Pattern SED_RANGE = Pattern.compile(
            "sed\\s+-n\\s+['\"]?(\\d+),(\\d+)p['\"]?\\s+((?:\\$\\([^)]*\\))?[^\\s,}]*)");

    /**
     * the author read `sed -n '300,380p' $(git ls-files | grep 'LibraryCatalog.java')`
     * - the file named only INSIDE the substitution - and the covered-read guard, which took the file name from the path
     * after it, saw no path and let a fully covered read through. The file of a sed read is the last `X.java` anywhere
     * in its path expression.
     */
    static String sedFileName(String pathExpression) {
        return sedFileName(pathExpression, null);
    }

    /**
     * the author read `file=$(find … LibraryCatalog.java …); sed -n '196,209p' "$file"`
     * - the path a shell VARIABLE, the file named only in the assignment before it - and two fully covered reads passed.
     * When the path expression names no `.java`, the file is the last `.java` anywhere in the whole command.
     */
    static String sedFileName(String pathExpression, String wholeCommand) {
        String inPath = lastJavaName(pathExpression);
        if (inPath != null) {
            return inPath;
        }
        if (pathExpression != null && pathExpression.contains("$")) {   // a variable or a substitution: look at the command
            String inCommand = lastJavaName(wholeCommand);
            if (inCommand != null) {
                return inCommand;
            }
        }
        if (pathExpression == null || pathExpression.isBlank()) {
            return null;
        }
        try {
            return Path.of(pathExpression).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String lastJavaName(String text) {
        if (text == null) {
            return null;
        }
        Matcher f = Pattern.compile("([A-Za-z_][\\w$]*\\.java)\\b").matcher(text);
        String last = null;
        while (f.find()) {
            last = f.group(1);
        }
        return last;
    }

    private static final Pattern SIG_LOCATE_SYMBOL = Pattern.compile(
            "^CodeGraph\\|\\{.*\\baction=locate\\b.*\\bsymbol=([^,}\\s]+)");
    private static final Pattern SIG_LOCATE_FILE = Pattern.compile(
            "^LocateFile\\|\\{.*\\bname=([^,}\\s]+)");

    /**
     * a plan that only locates types (or members) this turn has already located - in any spelling: qualified,
     * simple, `Type#m`, `Type.m`, `Type.java` - is a repeat. A member locate is covered once the member itself was
     * located, or the type was located AND its file has been read (the line numbers are in the context).
     */
    public static String coveredLocateIssue(List<Action> actions, Map<String, String> executed) {
        if (actions == null || actions.isEmpty() || executed == null || executed.isEmpty()) {
            return null;
        }
        String firstCovered = null;
        for (Action a : actions) {
            String target = locateTarget(a);
            if (target == null) {
                return null;   // the plan does something other than locate - let it run
            }
            String type = simpleTypeName(target);
            String member = memberOf(target);
            boolean typeLocated = false, memberLocated = false, fileRead = false;
            for (Map.Entry<String, String> e : executed.entrySet()) {
                if (!"Success".equals(e.getValue())) {
                    continue;
                }
                String sig = e.getKey();
                String done = null;
                Matcher m = SIG_LOCATE_SYMBOL.matcher(sig);
                if (m.find()) {
                    done = m.group(1);
                } else {
                    Matcher f = SIG_LOCATE_FILE.matcher(sig);
                    if (f.find()) {
                        done = f.group(1);
                    }
                }
                if (done != null && !done.contains("*") && simpleTypeName(done).equals(type)) {
                    typeLocated = true;
                    if (member != null && member.equals(memberOf(done))) {
                        memberLocated = true;
                    }
                }
                if (sig.startsWith("Bash|") && sig.contains(type + ".java")) {
                    fileRead = true;
                }
            }
            boolean covered = member == null ? typeLocated : memberLocated || (typeLocated && fileRead);
            if (!covered) {
                return null;
            }
            if (firstCovered == null) {
                firstCovered = target;
            }
        }
        return "'" + firstCovered + "' was already located this turn (qualified or simple spelling, #member or .member, "
                + "or its .java file — the path and the line numbers are in CONTEXT SO FAR). Do not locate it again: read a "
                + "range of it you have NOT seen, look up a symbol you have NOT located, or write the Shape B final_answer "
                + "from what you have.";
    }

    static String locateTarget(Action a) {
        if (a == null || a.parameters() == null) {
            return null;
        }
        Map<String, Object> p = a.parameters();
        if ("CodeGraph".equals(a.toolName()) && "locate".equalsIgnoreCase(String.valueOf(p.get("action")))) {
            for (String k : List.of("symbol", "name", "class", "type", "target", "method")) {
                Object v = p.get(k);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v).strip();
                }
            }
            return null;
        }
        if ("LocateFile".equals(a.toolName())) {
            Object v = p.get("name");
            return v == null || String.valueOf(v).isBlank() || String.valueOf(v).contains("*") ? null : String.valueOf(v).strip();
        }
        return null;
    }

    /** `com.x.Type#m`, `Type.m`, `Type.java`, `Type` → `Type`. */
    static String simpleTypeName(String symbol) {
        String s = symbol.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        if (s.endsWith(".java")) {
            s = s.substring(0, s.length() - 5);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0 && dot < s.length() - 1 && Character.isLowerCase(s.charAt(dot + 1))) {   // Type.member in dot form
            String before = s.substring(0, dot);
            if (Character.isUpperCase(before.charAt(before.lastIndexOf('.') + 1))) {
                s = before;
            }
        }
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** The member of `Type#m` or `Type.m` (a lower-case segment after an upper-case type), else null. */
    static String memberOf(String symbol) {
        String s = symbol.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            return s.substring(hash + 1);
        }
        if (s.endsWith(".java")) {
            return null;
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0 && dot < s.length() - 1 && Character.isLowerCase(s.charAt(dot + 1))) {
            String before = s.substring(0, dot);
            if (Character.isUpperCase(before.charAt(before.lastIndexOf('.') + 1))) {
                return s.substring(dot + 1);
            }
        }
        return null;
    }

    /** `$(some_placeholder_name)` - an identifier with underscores and no arguments is a stand-in, not a command. */
    static final Pattern PLACEHOLDER_SUBSTITUTION = Pattern.compile(
            "\\$\\(\\s*([A-Za-z]+(?:_[A-Za-z0-9]+)+)\\s*\\)|<([A-Za-z]+(?:[ _][A-Za-z]+)+)>|\\$\\{?(PATH_TO|FILE_PATH|LOCATED_PATH)[A-Z_]*\\}?"
            // `sed -n '30,80p' "$(jq -r '.file' <<< "${CODEGRAPH_RESULT}")"` - a variable that
            // holds another action's result exists in no shell; an upper-case name ending in RESULT/OUTPUT/PATH/FILE/DIR that
            // is not one the environment really has is a stand-in for a value the plan never had
            + "|\\$\\{?(?!HOME\\b|PWD\\b|USER\\b|PATH\\b|JAVA_HOME\\b|GRADLE_HOME\\b|TMPDIR\\b)([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*_?(?:RESULT|OUTPUT|PATH|FILE|DIR|LOCATION|ROOT)S?)\\}?"
            // `sed -n '1,200p' $(locateFileResult[0].absolutePath)` - a camelCase, indexed,
            // dotted "expression" standing for another action's result: no shell resolves it, and the underscore-only
            // placeholder shapes above did not know it. An identifier followed by an index or a field, inside $( ), is one.
            + "|\\$\\(\\s*([A-Za-z_]\\w*(?:\\[[^\\]]*\\]|\\.[A-Za-z_]\\w*)+)\\s*\\)");

    public static String placeholderCommandIssue(List<Action> actions, Set<String> executedSignatures) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null || a.parameters().get("command") == null) {
                continue;
            }
            String command = String.valueOf(a.parameters().get("command"));
            Matcher m = PLACEHOLDER_SUBSTITUTION.matcher(command);
            if (!m.find()) {
                continue;
            }
            String placeholder = m.group();
            String known = null;
            for (String sig : executedSignatures) {   // the path the plan meant: a file this turn already located or read
                Matcher p = Pattern.compile("(/[^\\s,}=]+\\.java)").matcher(sig);
                if (p.find()) {
                    known = p.group(1);
                    break;
                }
            }
            return "`" + placeholder + "` in `" + (command.length() > 120 ? command.substring(0, 120) + "…" : command)
                    + "` is a placeholder, not a command or a path — the shell cannot run it. Write the REAL path"
                    + (known == null ? " from CONTEXT SO FAR" : " (CONTEXT SO FAR has " + known + ")")
                    + ", or reason from what you have.";
        }
        return null;
    }

    /** a `sed -n 'A,Bp' file` whose range lies inside a range already read from the same file this turn. */
    public static String coveredReadIssue(List<Action> actions, Set<String> executedSignatures) {
        return coveredReadIssue(actions, executedSignatures, false);
    }

    /** @param afterCoveredRefusal the last refusal this turn was a covered read - any overlap is covered now */
    public static String coveredReadIssue(List<Action> actions, Set<String> executedSignatures, boolean afterCoveredRefusal) {
        return coveredReadIssue(actions, executedSignatures, afterCoveredRefusal, null);
    }

    /**
     * @param context the planner's context; a read whose lines are no longer in it is not covered (null: not checked).
     *     Rewrite drive t061: a covered read was refused after the earlier output had left the context, and the model
     *     wrote a test class from memory.
     */
    public static String coveredReadIssue(List<Action> actions, Set<String> executedSignatures, boolean afterCoveredRefusal,
            String context) {
        if (actions == null) {
            return null;
        }
        for (Action a : actions) {
            if (!"Bash".equals(a.toolName()) || a.parameters() == null) {
                continue;
            }
            String plannedCommand = String.valueOf(a.parameters().get("command"));
            Matcher m = SED_RANGE.matcher(plannedCommand);
            if (!m.find()) {
                continue;
            }
            int from = Integer.parseInt(m.group(1)), to = Integer.parseInt(m.group(2));
            String file = sedFileName(m.group(3), plannedCommand);
            if (file == null || to < from || to - from > 5000
                    || !linesStillInContext(plannedCommand, from, to, context) || supervisorAskedFor(from, to, context)) {
                continue;
            }
            boolean[] seen = new boolean[to - from + 1];
            for (String sig : executedSignatures) {
                Matcher done = SED_RANGE.matcher(sig);
                while (done.find()) {
                    int f2 = Integer.parseInt(done.group(1)), t2 = Integer.parseInt(done.group(2));
                    String file2 = sedFileName(done.group(3), sig);
                    if (file2 == null || !file.equals(file2)) {
                        continue;
                    }
                    if (f2 <= from && to <= t2) {
                        return "Lines " + from + "-" + to + " of " + file + " are inside the " + f2 + "-" + t2 + " you already read this turn "
                                + "— the text is in CONTEXT SO FAR; reason from it (or read a range you have NOT seen).";
                    }
                    for (int l = Math.max(from, f2); l <= Math.min(to, t2); l++) {
                        seen[l - from] = true;
                    }
                }
            }
            // the reads crept - 310-360 after 330-350, 320-380 after 310-360, 300-360 after
            // 310-360, 180-260 after 150-230 - each mostly already read, none inside one earlier range. Two thirds or more
            // already in the context is a covered read; the refusal names the lines that would be new.
            int covered = 0;
            for (boolean s : seen) {
                covered += s ? 1 : 0;
            }
            int total = to - from + 1;
            if (covered > 0 && (afterCoveredRefusal || covered * 3 >= total * 2)) {
                StringBuilder gaps = new StringBuilder();
                int i = 0;
                while (i < total) {
                    if (!seen[i]) {
                        int start = i;
                        while (i + 1 < total && !seen[i + 1]) {
                            i++;
                        }
                        gaps.append(gaps.length() == 0 ? "" : ", ").append(from + start)
                                .append(start == i ? "" : "-" + (from + i));
                    }
                    i++;
                }
                return (afterCoveredRefusal && covered * 3 < total * 2
                        ? "Your last plan was refused as a covered read of this file, and a WIDER range is the same read. " : "")
                        + "Of lines " + from + "-" + to + " of " + file + ", " + covered + " of " + total + " are already in CONTEXT SO FAR "
                        + "from your earlier reads; only lines " + gaps + " would be new. Read exactly those (sed -n 'A,Bp') if you "
                        + "need them, or reason from what you have.";
            }
        }
        return null;
    }

    /**
     * Whether the supervisor's latest refusal asks for exactly this read (sed -n 'from,top'). Rewrite drive t080b: the
     * planner wrote a method from memory, the supervisor asked it to read the method's lines again, and the covered-read
     * gate refused that read four times; the next plan wrote the method from memory again.
     */
    static boolean supervisorAskedFor(int from, int to, String context) {
        if (context == null) {
            return false;
        }
        int note = context.lastIndexOf("[Supervisor refused the last plan");
        return note >= 0 && context.indexOf(from + "," + to + "p", note) >= 0;
    }

    /**
     * Whether the lines from..to of the file a read names are still in the context: three of them (first, middle and
     * last of the range, long enough to be distinctive) must appear in it. An earlier read may have been cut to the
     * output cap or summarized away. Unknown (no context, no readable file): true.
     */
    static boolean linesStillInContext(String command, int from, int to, String context) {
        if (context == null) {
            return true;
        }
        Matcher path = Pattern.compile("((?:/|\\./)?[\\w./-]*\\.java)").matcher(command);
        String named = null;
        while (path.find()) {
            named = path.group(1);
        }
        if (named == null) {
            return true;
        }
        Path file = Path.of(named);
        if (!file.isAbsolute()) {
            String dir = System.getProperty("hermit.bash.defaultDir", System.getProperty("hermit.projectDir"));
            if (dir == null) {
                return true;
            }
            file = Path.of(dir).resolve(named);
        }
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> distinct = new ArrayList<>();
            for (int i = Math.max(1, from); i <= Math.min(to, lines.size()); i++) {
                String line = lines.get(i - 1).strip();
                if (line.length() >= 24) {
                    distinct.add(line);
                }
            }
            if (distinct.isEmpty()) {
                return true;
            }
            List<String> samples = List.of(distinct.get(0), distinct.get(distinct.size() / 2),
                    distinct.get(distinct.size() - 1));
            for (String sample : samples) {
                if (!context.contains(sample)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    /**
     * Stringify tool results for the planning context, capping each value so a
     * verbose result (large file contents, long reports) cannot dominate the
     * next planning prompt. The full result still lives in the CoTNode context.
     */
    /**
     * a read of 42 lines reached the
     * planner as its first 13 - `output` was capped at 600 chars like any other map entry - so no model in ten rounds
     * ever saw `sameEdition`; the verdicts argued from the report text, and the second reviewer rightly found "no
     * provided line shows this method". A read's output is what the read was FOR: it is kept whole up to a 60-line
     * range's worth (the size the oversized-read check advises), and only longer outputs are cut.
     */
    /**
     * The characters of one tool output the planner sees: the rest is cut, with a note saying how much.
     * {@code -Dhermit.context.outputCap}, default 4500 - sized for a small context window; a drive on a larger one raises it.
     */
    public static int readOutputCap() {
        return Math.max(1000, Integer.getInteger("hermit.context.outputCap", 4500));
    }

    /** the production {@code Type#method} specs named in {@code text} (test classes excluded), at most three. */
    public static List<String> specsMentioned(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)#([a-z]\\w*)\\b").matcher(text);
        while (m.find() && out.size() < 3) {
            String type = m.group(1);
            String spec = type + "#" + m.group(2);
            if (type.matches(".*(Test|Tests|IT|Spec|TestCase)$") || out.contains(spec)) {
                continue;
            }
            out.add(spec);
        }
        return out;
    }

    /** Where a named command starts in a refusal's prose. */
    private static final Pattern NAMED_COMMAND = Pattern.compile("(?<![\\w./-])(grep|sed)\\s");
    private static final Pattern NAMED_SED_RANGE = Pattern.compile("(\\d{1,6})(?:,(\\d{1,6}))?p");
    private static final Pattern GREP_FLAGS = Pattern.compile("-[niwEFHhoclv]+");
    private static final Pattern GREP_CONTEXT = Pattern.compile("-([ABCm])(\\d{1,3})?");
    private static final Pattern GREP_INCLUDE = Pattern.compile("--include=['\"]?([\\w*.?-]+)['\"]?");
    private static final Set<String> CONNECTORS = Set.of("in", "on", "of", "from");
    public static final int MAX_NAMED_READS = 4;

    /**
     * every concrete read a refusal names - `grep [flags] PATTERN FILE…` and `sed -n 'A,Bp' FILE`, the path
     * absolute or relative to the workbench - as Bash actions over files that exist, at most {@link #MAX_NAMED_READS}.
     * the refusal named three greps separated by `;` with single-quoted patterns; the old
     * parse ran only the first and kept its quotes inside double quotes (`grep -n "'borrowerId'" …`, no match), so
     * none of the evidence the refusal asked for arrived. Each command is now tokenized the way a shell would read it
     * (quotes removed, `;` `&&` `|` and a backtick end it), checked against a small set of read-only grep flags, and
     * rebuilt with its pattern single-quoted.
     */
    public static List<Action> readsNamedIn(String note, Path root) {
        List<Action> out = new ArrayList<>();
        if (note == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = NAMED_COMMAND.matcher(note);
        int from = 0;
        while (out.size() < MAX_NAMED_READS && m.find(from)) {
            List<String> words = new ArrayList<>();
            int end = shellWords(note, m.start(), words);
            from = Math.max(end, m.end());
            String command = "grep".equals(m.group(1)) ? namedGrep(words, root) : namedSed(words, root);
            if (command != null && seen.add(command)) {
                out.add(new Action("Bash", new LinkedHashMap<>(Map.of("command", command))));
            }
        }
        return out;
    }

    /** A grep the refusal named, rebuilt safely, or null when it is not a plain read of existing files. */
    private static String namedGrep(List<String> words, Path root) {
        List<String> flags = new ArrayList<>(List.of("-n"));
        String pattern = null;
        boolean recursive = false;
        List<String> includes = new ArrayList<>();
        int i = 1;
        for (; i < words.size(); i++) {
            String w = words.get(i);
            Matcher ctx = GREP_CONTEXT.matcher(w);
            Matcher inc = GREP_INCLUDE.matcher(w);
            if ("--".equals(w)) {
                i++;
                break;
            } else if ("-e".equals(w) && i + 1 < words.size()) {
                pattern = words.get(++i);
            } else if ("-r".equals(w) || "-R".equals(w) || "-rn".equals(w) || "-nr".equals(w)) {
                recursive = true;
            } else if (inc.matches()) {
                includes.add("--include='" + inc.group(1) + "'");
            } else if (ctx.matches()) {
                String n = ctx.group(2);
                if (n == null) {
                    if (i + 1 >= words.size() || !words.get(i + 1).matches("\\d{1,3}")) {
                        return null;
                    }
                    n = words.get(++i);
                }
                flags.add("-" + ctx.group(1) + n);
            } else if (GREP_FLAGS.matcher(w).matches()) {
                for (char f : w.substring(1).toCharArray()) {
                    if (f != 'n' && !flags.contains("-" + f)) {
                        flags.add("-" + f);
                    }
                }
            } else if (w.startsWith("-") && pattern == null) {
                return null;   // a flag outside the read-only set
            } else {
                break;
            }
        }
        if (pattern == null) {
            if (i >= words.size()) {
                return null;
            }
            pattern = words.get(i++);
        }
        if (pattern.isEmpty() || pattern.contains("\n")) {
            return null;
        }
        List<String> targets = namedTargets(words, i, root, recursive);
        if (targets.isEmpty()) {
            return null;
        }
        if (recursive) {
            flags.add("-r");
            flags.addAll(includes);
        }
        return "grep " + String.join(" ", flags) + " -e " + shellQuote(pattern) + " " + String.join(" ", targets);
    }

    /** A `sed -n 'A,Bp' FILE` the refusal named, or null. */
    private static String namedSed(List<String> words, Path root) {
        if (words.size() < 4 || !"-n".equals(words.get(1))) {
            return null;
        }
        Matcher r = NAMED_SED_RANGE.matcher(words.get(2));
        if (!r.matches()) {
            return null;
        }
        List<String> targets = namedTargets(words, 3, root, false);
        if (targets.size() != 1) {
            return null;
        }
        return "sed -n '" + r.group(1) + (r.group(2) == null ? "" : "," + r.group(2)) + "p' " + targets.get(0);
    }

    /** The existing files (or, recursive, directories) named from {@code i} on; stops at the first word that is neither. */
    private static List<String> namedTargets(List<String> words, int i, Path root, boolean recursive) {
        List<String> out = new ArrayList<>();
        for (; i < words.size(); i++) {
            String w = words.get(i);
            if (out.isEmpty() && CONNECTORS.contains(w.toLowerCase(Locale.ROOT))) {
                continue;   // "grep -n X in Foo.java"
            }
            String spelled = w.replaceAll("[.,:)]+$", "");
            Path file = resolveNamed(spelled, root);
            if (file == null && recursive && !spelled.isEmpty()) {
                Path dir = Path.of(spelled).isAbsolute() ? Path.of(spelled) : root == null ? null : root.resolve(spelled).normalize();
                file = dir != null && Files.isDirectory(dir) && (root == null || dir.startsWith(root)) ? dir : null;
            }
            if (file == null) {
                break;
            }
            out.add(shellQuote(file.toString()));
            if (!spelled.equals(w)) {
                break;   // the path ended a sentence
            }
        }
        return out;
    }

    static String shellQuote(String s) {
        return s.matches("[\\w./@%+=:,-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }

    private static Path resolveNamed(String spelled, Path root) {
        Path p = Path.of(spelled);
        if (p.isAbsolute()) {
            return Files.isRegularFile(p) ? p : null;
        }
        if (root == null) {
            return null;
        }
        Path under = root.resolve(spelled).normalize();
        return Files.isRegularFile(under) ? under : null;
    }

    /** Whether a plan writes nothing: no shell step writes a file and no editing tool is called. */
    /**
     * the request named the reads to make first, the first plan made only
     * those reads, and a critic rejected it each time for lacking the later edit, in a new wording each time
     * ("omits the core task", "is missing from the plan", "fails to execute the core instruction"). A plan that only
     * reads cannot do harm and the plan guards still apply to it, so when the request says "Read first" such a plan
     * skips the critic panel, and its five model calls. A plan whose edits follow its reads counts as its reads: the
     * edits are cut before they run.
     */
    public static boolean onlyNamedReads(ParsedPlan plan, String request) {
        if (plan == null || plan.isFinal() || plan.branches() == null || plan.branches().isEmpty() || request == null
                || !request.contains("Read first")) {
            return false;
        }
        for (Branch b : plan.branches()) {
            if (b.actions() == null || b.actions().isEmpty()) {
                return false;
            }
            // task 037d: an edit after the reads is cut before it runs (FM-012), so only the reads before it are judged
            int cut = writeAfterRead(b.actions());
            for (Action a : cut > 0 ? b.actions().subList(0, cut) : b.actions()) {
                Object c = a.parameters() == null ? null : a.parameters().get("command");
                if (!"Bash".equals(ToolExecutor.baseToolName(a.toolName())) || c == null || !onlyReadCommands(String.valueOf(c))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final Set<String> READ_COMMANDS = Set.of("sed", "cat", "head", "tail", "grep", "nl",
            "wc", "echo", "ls", "cd", "sort", "uniq", "cut", "tr");

    /** Every segment of the command is a read (sed only with -n), with no redirect into a file. */
    static boolean onlyReadCommands(String command) {
        String unquoted = command.replaceAll("'[^']*'|\"[^\"]*\"", "''");
        if (unquoted.replaceAll("2>&1|>\\s*/dev/null", "").contains(">")) {
            return false;
        }
        for (String segment : unquoted.split("&&|\\|\\||;|\\||\\n")) {
            String[] words = segment.strip().split("\\s+");
            if (words[0].isEmpty()) {
                continue;
            }
            if (!READ_COMMANDS.contains(words[0]) || "sed".equals(words[0]) && !segment.matches("\\s*sed\\s+-n\\b.*")) {
                return false;
            }
        }
        return SHELL_FILE_READ.matcher(command).find();
    }

    /** The request's "Read first" instruction, when the plan edits code and reads nothing before anything has run. */
    public static String readFirstIssue(String request, List<Action> actions, boolean anythingRan) {
        if (anythingRan || request == null || actions == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?m)^Read first[^\\n]*").matcher(request);
        if (!m.find()) {
            return null;
        }
        boolean edits = false;
        for (Action a : actions) {
            String tool = ToolExecutor.baseToolName(a.toolName());
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if ("Bash".equals(tool) && c != null && SHELL_FILE_READ.matcher(String.valueOf(c)).find()
                    || "LocateFile".equals(tool) || "ReadFile".equals(tool) || "CodeGraph".equals(tool)) {
                return null;   // a read is planned (a write after it is cut by FM-012's rule)
            }
            edits |= AnswerGate.EDIT_TOOLS.contains(tool) || "WriteProgram".equals(tool);
        }
        return edits ? "The request names what to read before editing, and nothing has been read in this turn: plan "
                + "those reads now, as their own step, and write the edits from their output. The request says: "
                + m.group().strip() : null;
    }

    /**
     * the reads found where the methods are but not their bodies, and the edits rewrote the
     * bodies from memory, with names the code does not have. A TreeEdit or TreeCodegen edit that rewrites existing code
     * (replaceBody, replaceStatement) whose current lines are not in this turn's context is refused, with the lines to
     * read. Null when every rewritten part was read.
     */
    public static String unreadRewriteIssue(List<Action> actions, String turnContext, Path defaultDir) {
        if (actions == null || turnContext == null) {
            return null;
        }
        Map<String, List<String>> unread = new LinkedHashMap<>();
        for (Action a : actions) {
            String tool = ToolExecutor.baseToolName(a.toolName());
            Map<String, Object> p = a.parameters();
            if (p == null || !("TreeCodegen".equals(tool) || "TreeEdit".equals(tool))) {
                continue;
            }
            List<Map<?, ?>> edits = new ArrayList<>();
            if (p.get("items") instanceof List<?> items) {
                items.stream().filter(Map.class::isInstance).forEach(i -> edits.add((Map<?, ?>) i));
            } else if ("TreeEdit".equals(tool)) {
                edits.add(p);
            }
            for (Map<?, ?> e : edits) {
                Object file = e.get("file") != null ? e.get("file") : p.get("file");
                if (file == null || e.get("method") == null) {
                    continue;
                }
                Path path = Path.of(String.valueOf(file));
                if (!path.isAbsolute() && defaultDir != null) {
                    path = defaultDir.resolve(path);
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path);
                } catch (Exception unreadable) {
                    continue;
                }
                int[] range = TreeEditTool.rewrittenLines(String.join("\n", lines),
                        String.valueOf(e.get("op")), String.valueOf(e.get("method")),
                        e.get("anchor") == null ? null : String.valueOf(e.get("anchor")));
                if (range != null && !shownIn(lines.subList(range[0] - 1, Math.min(range[1], lines.size())), turnContext)) {
                    unread.computeIfAbsent(String.valueOf(file), f -> new ArrayList<>()).add(range[0] + "," + range[1] + "p");
                }
            }
        }
        if (unread.isEmpty()) {
            return null;
        }
        StringBuilder reads = new StringBuilder();
        unread.forEach((file, ranges) -> {
            for (String r : ranges.stream().distinct().toList()) {
                reads.append(reads.length() == 0 ? "" : " ; ").append("sed -n '").append(r).append("' ").append(file);
            }
        });
        return "The plan rewrites code whose current text is not in this turn's context (it was located, not read), so "
                + "the edits would be written from memory. Read it first, as its own step: " + reads
                + " — then write each edit from today's code with only the change the request names.";
    }

    /** At least half of the lines that carry code (more than a brace or a word) appear in the text. */
    private static boolean shownIn(List<String> lines, String text) {
        int code = 0;
        int shown = 0;
        for (String line : lines) {
            String s = line.strip();
            if (s.length() < 6) {
                continue;
            }
            code++;
            shown += text.contains(s) ? 1 : 0;
        }
        return shown * 2 >= code;
    }

    public static int writeAfterRead(List<Action> actions) {
        boolean read = false;
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            String tool = ToolExecutor.baseToolName(a.toolName());
            // edits planned in the same step as the reads they depend on were
            // written blind - bare 'if' headers, an invented TokenType, placeholder bodies ('return false', empty
            // lists) in place of the code the reads would have shown. An edit tool after a read is cut like a shell
            // write after a read.
            if (AnswerGate.EDIT_TOOLS.contains(tool) || "WriteProgram".equals(tool)) {
                if (read) {
                    return i;
                }
                continue;
            }
            Object c = a.parameters() == null ? null : a.parameters().get("command");
            if (!"Bash".equals(a.toolName()) || c == null) {
                continue;
            }
            String command = String.valueOf(c);
            boolean writes = SHELL_FILE_WRITE.matcher(command).find();
            if (writes && read) {
                return i;
            }
            read |= !writes && SHELL_FILE_READ.matcher(command).find();
        }
        return 0;
    }

    /**
     * If a program-producing tool failed this turn (WriteProgram syntaxOk=false, a
     * non-zero RunProgram, or a SyntaxCheck/GenerateCode error), return a short
     * description of the failure (tool + the code + the error) for an expert consult;
     * otherwise null.
     */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("CodeGraph", "LocateFile",
            "FileClassifier", "CodeQlQuery", "AssembleContext");

    /** Is this action free of side effects (analysis / lookup / read-only shell)? */
    static boolean isReadOnly(Action a) {
        if (a == null || a.toolName() == null) {
            return false;
        }
        Map<String, Object> p = a.parameters() == null ? Map.of() : a.parameters();
        switch (a.toolName()) {
            case "SpecTestComposer" -> {
                String act = String.valueOf(p.getOrDefault("action", ""));
                return act.equals("reach") || act.equals("specreach") || act.equals("verbs");
            }
            case "Bash" -> {
                String cmd = String.valueOf(p.getOrDefault("command", "")).trim();
                // read-only shell: an inspection command, no redirection, no chained second command
                return cmd.matches("^(grep|rg|ls|cat|head|tail|find|wc|git (log|status|diff|show|rev-parse|branch))\\b[^>;&]*$");
            }
            default -> {
                return READ_ONLY_TOOLS.contains(a.toolName());
            }
        }
    }

    /** Remember each executed action's outcome (results are keyed in action order: Tool, Tool#2, …). */
    private static final Pattern READ_COMMAND = Pattern.compile("\\b(grep|sed|cat|head|tail|awk|nl)\\b");
    private static final Pattern READ_FILE = Pattern.compile("([A-Z][A-Za-z0-9_]*)\\.(?:java|kt)\\b");

    /**
     * the invented-name check refused `InventoryLedger` as "no tool returned this name"
     * although a successful grep had just read InventoryLedger.java - the file name was in the command, and `grep -n`
     * on one file prints no file name. The type name of a file a successful read command ran on is grounded: it is
     * added to the evidence the check looks in.
     */
    public static String withReadFiles(String evidence, Map<String, String> signatures) {
        StringBuilder sb = new StringBuilder(evidence == null ? "" : evidence);
        if (signatures != null) {
            for (Map.Entry<String, String> e : signatures.entrySet()) {
                if (!"Success".equals(e.getValue()) || !e.getKey().startsWith("Bash|") || !READ_COMMAND.matcher(e.getKey()).find()) {
                    continue;
                }
                Matcher m = READ_FILE.matcher(e.getKey());
                while (m.find()) {
                    sb.append('\n').append(m.group(1));
                }
            }
        }
        return sb.toString();
    }

    /**
     * the plan addressed LibraryCatalog.java:335 and the invented-address guard
     * called it a GUESS - the turn had read lines 310-360 of that file, but `sed -n` prints no line numbers, so
     * the number appeared nowhere in the context. Every File.java:LINE in the text is grounded when its line lies
     * inside a `sed -n 'A,Bp' …File.java` range this turn executed.
     */
    public static boolean groundedByARead(String text, Set<String> executedSignatures) {
        if (text == null || executedSignatures == null) {
            return false;
        }
        Matcher m = Pattern.compile("([A-Za-z_][\\w$]*\\.java):(\\d+)").matcher(text);
        boolean any = false;
        while (m.find()) {
            any = true;
            String file = m.group(1);
            int line = Integer.parseInt(m.group(2));
            boolean inside = false;
            for (String sig : executedSignatures) {
                Matcher r = SED_RANGE.matcher(sig);
                while (r.find()) {
                    int from = Integer.parseInt(r.group(1)), to = Integer.parseInt(r.group(2));
                    if (file.equals(sedFileName(r.group(3))) && from <= line && line <= to) {
                        inside = true;
                    }
                }
            }
            if (!inside) {
                return false;
            }
        }
        return any;
    }

    /** is every action a LocateFile whose name matches the file already written? */
    public static boolean locatesOnly(List<Action> actions, String writtenFile) {
        if (actions == null || actions.isEmpty()) {
            return false;
        }
        String base = Path.of(writtenFile).getFileName().toString();
        for (Action a : actions) {
            if (!"LocateFile".equals(a.toolName()) || a.parameters() == null) {
                return false;
            }
            String name = String.valueOf(a.parameters().getOrDefault("name", ""));
            String glob = "^" + Pattern.quote(name).replace("*", "\\E.*\\Q") + "$";
            if (!name.equals(base) && !base.matches(glob)) {
                return false;
            }
        }
        return true;
    }

    /** The first read a refusal names, or null (see {@link #readsNamedIn}). */
    public static Action readNamedIn(String note, Path root) {
        List<Action> reads = readsNamedIn(note, root);
        return reads.isEmpty() ? null : reads.get(0);
    }
}
