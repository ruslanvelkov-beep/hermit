package hermit.runtime;

import hermit.api.Action;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.api.Tool;
import hermit.tool.api.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class ToolExecutor {
    private final ToolRegistry toolRegistry;
    public interface Observer {
        void before(Action action);
        void after(Action action, Map<String, Object> result);
    }
    private Observer observer;
    // carries out a plan's instructions (-Dhermit.follower.model); null: as given
    private InstructionFollower follower;

    public void setObserver(Observer observer) { this.observer = observer; }
    public void setFollower(InstructionFollower follower) { this.follower = follower; }
    public boolean hasFollower() { return follower != null; }

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Executes a list of actions sequentially, managing the tool calls and result collection.
     * @param actions The list of structured actions to perform.
     * @return A map containing the results of all executed tools.
     */
    public Map<String, Object> executeActions(List<Action> rawActions) {
        return execute(rawActions, false);
    }

    /**
     * A plan's instructions, as {@link #executeActions}, each carried out by the instruction follower when the run has
     * one. Hermit's own actions (a build after edits, a commit, a named read) are not instructions and run as they are.
     */
    public Map<String, Object> executeInstructions(List<Action> rawActions) {
        return execute(rawActions, true);
    }

    private Map<String, Object> execute(List<Action> rawActions, boolean follow) {
        List<Action> actions = declarationsFirst(mergedMemberInserts(rawActions));
        // Insertion-ordered and keyed UNIQUELY per action: a plan that runs the same tool several times
        // (gradle test → git commit → git rev-parse, all Bash) used to collapse into the LAST result, so the
        // planner never saw the test output or the commit failure. First use keeps the plain tool name
        // (existing consumers), later uses get Tool#2, Tool#3 …
        Map<String, Object> executionResults = new LinkedHashMap<>();
        String failedEdit = null;

        String lastKey = null;
        String lastTool = null;
        for (Action action : actions) {
            if (failedEdit == null && lastKey != null && EDIT_TOOLS.contains(lastTool)
                    && executionResults.get(lastKey) instanceof Map<?, ?> last
                    && !"Success".equals(last.get("status"))) {
                // recorded here, so an edit refused before it ran ('continue') counts too
                failedEdit = lastKey + ": "
                        + String.valueOf(last.get("message")).lines().findFirst().orElse("").strip();
            }
            // the planner named a tool "TreeEdit#2", copying the result-key style of an earlier
            // iteration, and the step died with "Tool not found" - the suffix is ours, never part of a tool's name
            String toolName = baseToolName(action.toolName());
            String key = uniqueKey(executionResults, toolName);
            lastKey = key;
            lastTool = toolName;
            Tool tool = toolRegistry.getTool(toolName);

            if (observer != null) observer.before(action);
            // the plan's first edit was refused and the plan went on to its commit, which
            // committed a file holding only its imports. After an edit of this plan failed, its commit is not run.
            if (failedEdit != null && "Bash".equals(toolName) && action.parameters() != null
                    && GIT_COMMIT.matcher(String.valueOf(action.parameters().get("command"))).find()) {
                executionResults.put(key, Map.of("status", "Error", "message", "not run: an earlier edit of this plan "
                        + "failed (" + failedEdit + "); a commit now would record the file without that edit. Fix the "
                        + "edit first, then commit."));
                if (observer != null) observer.after(action, (Map<String, Object>) executionResults.get(key));
                continue;
            }
            if (tool == null) {
                executionResults.put(key, Map.of("status", "Error", "message", "Tool not found"));
                if (observer != null) observer.after(action, (Map<String,Object>) executionResults.get(key));
                continue;
            }

            try {
                System.out.println("\n[EXECUTOR]: Running Tool: " + action.toolName());
                Map<String, Object> resolved = normalizeProjectDir(resolvePlaceholders(action.parameters()));
                if ("TreeEdit".equals(toolName) && resolved.get("type") != null
                        && TreeEditTool.looksLikeDeclaration(String.valueOf(resolved.get("type")))
                        && (resolved.get("code") == null || String.valueOf(resolved.get("code")).isBlank())) {
                    // the class text arrived in `type` - it is the code, not a placeholder
                    resolved = new LinkedHashMap<>(resolved);
                    resolved.put("code", resolved.remove("type"));
                }
                String unfilled = unfilledSlot(resolved);
                if (unfilled != null) {
                    // "<an EXISTING method - the tool refuses unknown ones …>" was passed as testMethod -
                    // a guidance slot copied verbatim. Refuse before the tool runs, and say how to fill it.
                    String hint = "";
                    if ("TreeEdit".equals(action.toolName()) && ("anchor".equals(unfilled) || "code".equals(unfilled))
                            && resolved.get("file") != null && resolved.get("method") != null
                            && unfilledSlot(Map.of("file", resolved.get("file"),
                                    "method", resolved.get("method"))) == null) {
                        // a placeholder anchor - list what the method actually offers to anchor on
                        List<String> stmts = TreeEditTool.anchorableStatements(
                                Path.of(String.valueOf(resolved.get("file"))),
                                String.valueOf(resolved.get("method")), 14);
                        if (!stmts.isEmpty()) {
                            hint = " The statements of that method you can anchor on (copy one EXACTLY):\n  - "
                                    + String.join("\n  - ", stmts);
                        }
                    }
                    executionResults.put(key, Map.of("status", "Error", "message", "parameter '" + unfilled
                            + "' is not a real value: '" + resolved.get(unfilled) + "'. It is a placeholder or a "
                            + "DESCRIPTION of the value. Put the actual name/path/symbol there — copy it from "
                            + "CONTEXT or from an earlier tool result (CodeGraph locate/tests lists real methods); "
                            + "the tool was not run." + hint));
                    continue;
                }
                Map<String, Object> input = resolved;
                Action given = new Action(toolName, input);
                Map<String, Object> toolOutput = follow && follower != null
                        ? follower.follow(given, step -> step == given ? tool.execute(input) : runStep(step))
                        : tool.execute(input);
                executionResults.put(key, toolOutput);

            } catch (Exception e) {
                System.err.println("\n[EXECUTOR ERROR]: Tool execution failed for " + action.toolName() + ": "
                        + e.getMessage());
                executionResults.put(key, Map.of("status", "Execution Failed", "message",
                        String.valueOf(e.getMessage())));
            } finally {
                if (observer != null) observer.after(action, (Map<String,Object>) executionResults.get(key));
            }
        }

        return executionResults;
    }

    /** One step of an instruction the follower split: its tool on its parameters, resolved as any action's are. */
    private Map<String, Object> runStep(Action step) {
        Tool tool = toolRegistry.getTool(baseToolName(step.toolName()));
        if (tool == null) {
            return Map.of("status", "Error", "message", "Tool not found: " + step.toolName());
        }
        try {
            Map<String, Object> resolved = normalizeProjectDir(resolvePlaceholders(step.parameters()));
            String unfilled = unfilledSlot(resolved);
            if (unfilled != null) {
                return Map.of("status", "Error", "message", "parameter '" + unfilled + "' is not a real value: '"
                        + resolved.get(unfilled) + "'; the step was not run.");
            }
            return tool.execute(resolved);
        } catch (Exception e) {
            return Map.of("status", "Execution Failed", "message", String.valueOf(e.getMessage()));
        }
    }

    /** The tools whose failure leaves a file without the edit the plan meant to make. */
    private static final Set<String> EDIT_TOOLS = Set.of("TreeEdit", "TreeCodegen");
    /** A shell command that records a commit. */
    private static final Pattern GIT_COMMIT = Pattern.compile("\\bgit\\s+(?:-\\S+\\s+\\S+\\s+)*commit\\b");
    static String uniqueKey(Map<String, Object> results, String toolName) {
        if (!results.containsKey(toolName)) {
            return toolName;
        }
        int n = 2;
        while (results.containsKey(toolName + "#" + n)) {
            n++;
        }
        return toolName + "#" + n;
    }

    /**
     * Members that call each other (collectMethods and collectInheritedMethods) were inserted as
     * separate items, and each was refused for the other's absence. Consecutive insertMember edits of one file and one
     * type become one edit, whose members the tool inserts together and validates once.
     */
    public static List<Action> mergedMemberInserts(List<Action> actions) {
        if (actions == null || actions.size() < 2) {
            return actions;
        }
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            if (!out.isEmpty() && isMemberInsert(a) && isMemberInsert(out.get(out.size() - 1))
                    && sameTarget(out.get(out.size() - 1), a)) {
                Action prev = out.remove(out.size() - 1);
                Map<String, Object> merged = new LinkedHashMap<>(prev.parameters());
                merged.put("code", String.valueOf(prev.parameters().get("code")).strip() + "\n\n"
                        + String.valueOf(a.parameters().get("code")).strip());
                out.add(new Action(prev.toolName(), merged));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    private static boolean isMemberInsert(Action a) {
        return "TreeEdit".equals(baseToolName(a.toolName())) && a.parameters() != null
                && "insertMember".equals(String.valueOf(a.parameters().get("op")))
                && a.parameters().get("code") != null;
    }

    private static boolean sameTarget(Action x, Action y) {
        return Objects.equals(x.parameters().get("file"), y.parameters().get("file"))
                && Objects.equals(x.parameters().get("type"), y.parameters().get("type"));
    }

    /**
     * The fix plan used a new field `memo` in two statement edits and declared it in a
     * THIRD edit - the validator rejects a statement that names an undeclared field, so the order alone sank the plan.
     * Within one plan, TreeEdit items that add declarations (imports, members, types) run before statement edits of
     * the same file; the relative order of everything else is kept.
     */
    public static List<Action> declarationsFirst(List<Action> actions) {
        if (actions == null || actions.size() < 2) {
            return actions;
        }
        List<Action> declarations = new ArrayList<>();
        List<Action> rest = new ArrayList<>();
        for (Action a : actions) {
            String op = a.parameters() == null || a.parameters().get("op") == null ? ""
                    : String.valueOf(a.parameters().get("op")).toLowerCase(Locale.ROOT).replace("_", "");
            boolean declaration = "TreeEdit".equals(baseToolName(a.toolName()))
                    && (op.equals("addimport") || op.equals("insertmember") || op.equals("addmember")
                    || op.equals("addtype") || op.equals("insertfield") || op.equals("addfield")
                    || op.equals("insertmethod") || op.equals("addmethod"));
            (declaration ? declarations : rest).add(a);
        }
        // the cache FIELD was inserted before the nested CLASS it is typed with - "unknown type
        // CachedInfo". Among declarations: imports, then type declarations, then fields and methods.
        declarations.sort(Comparator.comparingInt(ToolExecutor::declarationRank));
        if (declarations.isEmpty()) {
            return actions;
        }
        if (rest.isEmpty()) {
            return declarations;
        }
        // declarations move ahead of the FIRST TreeEdit statement edit only; tools before it keep their place
        List<Action> out = new ArrayList<>();
        boolean placed = false;
        for (Action a : actions) {
            if (declarations.contains(a)) {
                continue;
            }
            if (!placed && "TreeEdit".equals(baseToolName(a.toolName()))) {
                out.addAll(declarations);
                placed = true;
            }
            out.add(a);
        }
        if (!placed) {
            // a Bash step created two files, then TreeEdit added their imports and
            // classes. With no statement edit to go ahead of, the declarations were moved to the very front, ran before
            // the files existed ("file not found") and the check after them compiled a half-written package. Without a
            // statement edit there is nothing to move ahead of: the plan keeps its order across other tools.
            // Within a run of consecutive declarations, the imports go first, then the types, then
            // the members, as above; a class's members written before its imports were checked without them.
            List<Action> runs = new ArrayList<>();
            List<Action> run = new ArrayList<>();
            for (Action a : actions) {
                if (declarations.contains(a)) {
                    run.add(a);
                    continue;
                }
                run.sort(Comparator.comparingInt(ToolExecutor::declarationRank));
                runs.addAll(run);
                run.clear();
                runs.add(a);
            }
            run.sort(Comparator.comparingInt(ToolExecutor::declarationRank));
            runs.addAll(run);
            return runs;
        }
        return out;
    }

    /** 0 imports, 1 type declarations (class/interface/enum/record), 2 everything else - for declarationsFirst. */
    static int declarationRank(Action a) {
        String op = a.parameters() == null || a.parameters().get("op") == null ? ""
                : String.valueOf(a.parameters().get("op")).toLowerCase(Locale.ROOT).replace("_", "");
        String code = a.parameters() == null || a.parameters().get("code") == null ? ""
                : String.valueOf(a.parameters().get("code")).strip();
        if (op.equals("addimport") || code.startsWith("import ")) {
            return 0;
        }
        if (op.equals("addtype") || code.matches(
                "(?s)^(?:(?:public|private|protected|static|final|abstract|sealed)\\s+)*"
                        + "(?:class|interface|enum|record)\\s.*")) {
            return 1;
        }
        return 2;
    }

    /**
     * The escalated plan passed {@code projectDir=./service-module} - a directory that does
     * not exist - and the composer refused ("projectDir not found"). A {@code projectDir} that names no existing
     * directory resolves to the configured project (-Dhermit.projectDir), or to that path under the project when the
     * relative form exists there; every tool shares this parameter, so the executor normalises it once.
     */
    public static Map<String, Object> normalizeProjectDir(Map<String, Object> params) {
        if (params == null || params.get("projectDir") == null) {
            return params;
        }
        String given = String.valueOf(params.get("projectDir")).trim();
        if (given.isEmpty() || Files.isDirectory(Path.of(given))) {
            return params;
        }
        String configured = System.getProperty("hermit.projectDir", System.getProperty("hermit.bash.defaultDir"));
        if (configured == null || !Files.isDirectory(Path.of(configured))) {
            return params;
        }
        Path under = Path.of(configured).resolve(given).normalize();
        Map<String, Object> out = new LinkedHashMap<>(params);
        out.put("projectDir", Files.isDirectory(under) && !under.equals(Path.of(configured))
                ? under.toString() : configured);
        return out;
    }

    /** The tool name behind a result key ({@code Bash#2 → Bash}). */
    public static String baseToolName(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        return hash > 0 ? key.substring(0, hash) : key;
    }

    /**
     * Replace unresolved PLACEHOLDER arguments a small model tends to copy verbatim from the tool docs
     * (e.g. {@code "<project dir>"}, {@code "/path/to/your/project"}) with the
     * real, configured values - the single choke-point fix for the recurring unresolved-arg failure.
     * Only rewrites when a default is configured; otherwise leaves the value for the tool to reject.
     */
    static Map<String, Object> resolvePlaceholders(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        String projectDir = System.getProperty("hermit.projectDir");
        Map<String, Object> out = new HashMap<>(params);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            if (!(e.getValue() instanceof String v)) {
                continue;
            }
            boolean placeholder = looksLikePlaceholder(v);
            switch (key) {
                case "projectDir", "sourceCodePath", "root", "workingDir", "directoryPath" -> {
                    if ((placeholder || v.isBlank()) && projectDir != null && !projectDir.isBlank()) {
                        out.put(key, projectDir);
                    }
                }
                default -> { /* leave other args untouched */ }
            }
        }
        return out;
    }

    /** The first parameter whose value is still an angle-bracket slot ({@code <…>} spanning words), or null. Generic
     *  Java like {@code List<String>} or a lambda arrow does not count: the slot must start with '<' and end with
     *  '>'. */
    static String unfilledSlot(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!(e.getValue() instanceof String v)) {
                continue;
            }
            // the body of a here-document is the content of the file being written, not a value to fill in: a document
            // that says `docs/contracts/<area>.md` is not an unfilled command
            String s = HEREDOC_BODY.matcher(v).replaceAll("<<'$2'\n…\n$2").trim();
            // the comments of code are not slots: a class comment's <ul> and <li> read as
            // placeholders and refused the item that carried the class
            if ("code".equals(e.getKey()) || "content".equals(e.getKey())) {
                // and the text of strings: a usage text block's <srcDir> is no slot
                s = s.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"").replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"")
                        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").trim();
            }
            boolean bracketed = s.length() > 2 && s.startsWith("<") && s.endsWith(">")
                    && s.indexOf('>') == s.length() - 1 && (s.contains(" ") || s.length() > 12);
            // "<File.java>:<line>" and "<Type>#<method>" carry SEVERAL slots, so the single-span
            // rule missed them. A slot starts lower-case or names a source file; Java generics (List<String>) do not.
            boolean multiSlot = PLACEHOLDER_SLOT.matcher(s).find();
            // with angle brackets forbidden, the planner emits the SLOT NAME itself
            // (testMethod=existingTestMethodName) or a whole prose phrase - both are unfilled values
            boolean slotWords = placeholderWords(s);
            boolean prose = IDENTIFIER_PARAMS.contains(e.getKey().toLowerCase(Locale.ROOT))
                    && isProse(s);
            if (bracketed || multiSlot || slotWords || prose) {
                return e.getKey();
            }
        }
        return null;
    }

    /** A here-document: its opening (`<<'EOF'`, `<< "EOF"`, `<<-EOF`), its body and its closing delimiter line. */
    static final Pattern HEREDOC_BODY = Pattern.compile(
            "(?s)<<-?\\s*(['\"]?)([A-Za-z_]\\w*)\\1[^\\n]*\\n.*?\\n\\2[ \\t]*(?=\\n|$)");
    /** A {@code <…>} slot inside a larger value: lower-case content ("<line>", "<method>") or a file placeholder
     *  ("<File.java>"). Deliberately does NOT match Java generics, whose arguments are capitalised types. */
    static final Pattern PLACEHOLDER_SLOT = Pattern.compile(
            "<(?:[a-z][\\w. ]*|[A-Za-z][\\w]*\\.(?:java|kt|kts|xml|json|md|txt))>");
    /** Params whose value is a program element: a name, a spec or a file:line - never a phrase. */
    private static final Set<String> IDENTIFIER_PARAMS = Set.of(
            "method", "testmethod", "templatemethod", "symbol", "target", "testclass", "type", "spec",
            "testfilter", "targetreach", "emulatespec", "module");
    /** Words that only ever appear in a DESCRIPTION of a value, never in a real Java name of one. */
    private static final Set<String> SLOT_WORDS = Set.of(
            "existing", "exact", "specify", "specified", "your", "some", "the", "a", "an", "placeholder",
            "here", "goes", "todo", "fixme", "foo", "bar", "baz", "xyz", "name", "names", "value", "values",
            "method", "methods", "class", "classes", "file", "files", "path", "paths", "test", "tests",
            "symbol", "target", "type", "dir", "directory", "real", "actual", "new", "of", "to", "for");
    /** A DESCRIPTION where a program element belongs: whitespace plus either several words or a slot word. A spec with
     *  stray spaces ("Svc # work") is not prose - the tools repair that themselves. */
    static boolean isProse(String value) {
        if (value == null || !value.matches("(?s).*\\s.*")) {
            return false;
        }
        // only spaces AROUND the '#' (or at the ends) make a spec loosely typed; interior words are prose
        String tightened = value.trim().replaceAll("\\s*#\\s*", "#");
        if (tightened.matches("[A-Za-z_][\\w.$]*(#[A-Za-z_]\\w*)?(\\(\\))?")) {
            return false;   // a spec, only loosely typed
        }
        // a spec with a parameter list ("Type#name(Cursor, Node, Node)") has a space
        // after each comma and read as a three-word phrase; its parameter types are names, not prose
        if (tightened.matches("[A-Za-z_][\\w.$]*(#[A-Za-z_]\\w*)?\\s*\\([\\w.$<>\\[\\],?\\s]*\\)")) {
            return false;
        }
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length >= 3) {
            return true;
        }
        for (String t : tokens) {
            if (SLOT_WORDS.contains(t.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""))) {
                return true;
            }
        }
        return false;
    }

    /** True when a camel/underscore identifier is made ONLY of slot words ("existingTestMethodName"). */
    static boolean placeholderWords(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return false;
        }
        String[] words = value.replace('_', ' ').replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ").toLowerCase(
                Locale.ROOT).trim().split("\\s+");
        if (words.length < 2) {
            return false;   // a single word is too likely to be a real name
        }
        for (String w : words) {
            if (!SLOT_WORDS.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** A value the model clearly did not fill in: an angle-bracket token or a /path/to placeholder. */
    private static boolean looksLikePlaceholder(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        return s.contains("<") || s.contains(">") || s.toLowerCase().contains("/path/to")
                || s.equals("<project dir>");
    }
}
