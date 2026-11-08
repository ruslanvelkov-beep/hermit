package hermit.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import hermit.api.Action;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the LLM's planning response into a ParsedPlan: either a final answer
 * (Shape B) or a list of branches with per-branch actions (Shape A).
 */
public class PlanParser {

    // LENIENT parser: LLMs routinely emit JSON that strict parsing rejects,
    // especially when they embed a program in an action parameter - a backslash
    // before a quote (\' → "Unrecognized character escape"), single-quoted strings,
    // literal newlines inside a string, or a trailing comma. Tolerating these turns
    // an otherwise-valid plan from a parse failure (which loops to the iteration cap)
    // into a usable plan.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            // a small model annotates its plan with `// …` comments and leaves trailing commas - sixteen
            // consecutive iterations were lost to one such comment. Lenient by design; the plan's SHAPE is
            // what we validate, not its JSON pedantry.
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    public static String stripShapeLabel(String answer) {
        if (answer == null) {
            return null;
        }
        String a = answer.strip();
        // The label must look like a label: a colon is mandatory, so a sentence mentioning "Shape B" survives.
        Matcher m = Pattern.compile(
                "(?is)^\\*{0,2}\\s*(?:shape\\s*[ab]\\s*(?:\\([^)\\n]{0,30}\\))?|final[_ ]answer)\\s*\\*{0,2}\\s*:\\s*\\*{0,2}\\s*")
                .matcher(a);
        return m.lookingAt() ? a.substring(m.end()).strip() : a;
    }

    public static String joinConcatenatedStrings(String json) {
        return json == null ? null : json.replaceAll("\"\\s*\\+\\s*\"", "");
    }

    /**
     * A model may write Java expressions unquoted inside an array, for example
     * {@code "ops": [stopAll().runAs(ROOT), getItemInfo("1.2.3")]}. Quote each
     * top-level item that is not already a JSON value, splitting outside parentheses
     * and string literals. Objects and nested arrays are left untouched.
     */
    static String quoteUnquotedArrayItems(String json) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {                                   // copy string literals verbatim
                int j = i + 1;
                while (j < json.length() && json.charAt(j) != '"') {
                    if (json.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                out.append(json, i, Math.min(j + 1, json.length()));
                i = j + 1;
                continue;
            }
            if (c == '[') {
                int end = matchingBracket(json, i);
                if (end < 0) {
                    out.append(json.substring(i));
                    break;
                }
                String inner = json.substring(i + 1, end);
                if (inner.trim().startsWith("{") || inner.trim().startsWith("[")) {
                    out.append('[').append(quoteUnquotedArrayItems(inner)).append(']');
                } else {
                    out.append('[').append(quoteItems(inner)).append(']');
                }
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int matchingBracket(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Split on top-level commas (outside parens/strings) and quote items that are not JSON values. */
    private static String quoteItems(String inner) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\\' && i + 1 < inner.length()) {
                    cur.append(inner.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                items.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (cur.toString().trim().length() > 0) {
            items.add(cur.toString());
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < items.size(); k++) {
            String t = items.get(k).trim();
            if (k > 0) {
                sb.append(", ");
            }
            boolean jsonValue = t.startsWith("\"") || t.startsWith("{") || t.startsWith("[")
                    || t.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")
                    || t.equals("true") || t.equals("false") || t.equals("null");
            sb.append(jsonValue ? t : "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return sb.toString();
    }

    /**
     * the model emitted a correct tool call in its NATIVE Harmony form -
     * {@code <|channel|>commentary to=TreeCodegen <|constrain|>json<|message|>{...}} - sixteen iterations in a
     * row, and every one was "no JSON block / no branches". That envelope IS a single-action plan.
     */
    private static final Pattern HARMONY_TOOL_CALL = Pattern.compile(
            "<\\|channel\\|>\\s*\\w*\\s*to=([A-Za-z_][\\w.]*)((?:\\s+[\\w-]+(?:=[^\\s<]+)?)*)\\s*"
                    + "(?:<\\|constrain\\|>\\s*\\w+\\s*)?<\\|message\\|>\\s*", Pattern.DOTALL);
    private static final Pattern HARMONY_FINAL = Pattern.compile(
            "<\\|channel\\|>\\s*final\\s*<\\|message\\|>\\s*(.*?)\\s*(?:<\\|(?:end|return|call)\\|>|$)",
            Pattern.DOTALL);

    static String canonicalTool(String name) {
        if (name == null) {
            return null;
        }
        String n = name.strip();
        if (n.matches("(?i)bash|exec|shell|sh|zsh|terminal|command|run_command|execute|run")) {
            return "Bash";
        }
        for (String real : List.of("Bash", "LocateFile", "CodeGraph", "TreeEdit", "TreeCodegen", "SpecTestComposer",
                "HindsightDebugger", "CodeQL", "CodeQlQuery", "GenerateCode", "TddCycle", "SyntaxCheck",
                "WriteProgram", "RunProgram", "WebSearch", "GitHub", "Byteman", "FileClassifier",
                "AssembleContext", "RemoteAgent", "BigBrother", "PathProof", "ClaimInspector")) {
            if (real.equalsIgnoreCase(n) || real.replace("_", "").equalsIgnoreCase(n.replace("_", ""))) {
                return real;
            }
        }
        return n;
    }

    static Map<String, Object> shellParameters(String tool, Map<String, Object> parameters) {
        if ("LocateFile".equals(tool) && parameters != null && parameters.get("name") == null) {
            for (String alias : List.of("filename", "fileName", "file", "path", "pattern")) {
                Object v = parameters.get(alias);
                if (v != null && !String.valueOf(v).isBlank()) {
                    Map<String, Object> out = new LinkedHashMap<>(parameters);
                    out.remove(alias);
                    out.put("name", String.valueOf(v).strip());
                    return out;
                }
            }
            return parameters;
        }
        if (!"Bash".equals(tool) || parameters == null) {
            return parameters;
        }
        Object command = parameters.get("command");
        if (command instanceof List<?>) {
            String text = shellText(command);
            if (text != null && !text.isBlank()) {
                Map<String, Object> out = new LinkedHashMap<>(parameters);
                out.put("command", text);
                return out;
            }
        }
        if (command != null && !(command instanceof List<?>) && !String.valueOf(command).isBlank()) {
            return parameters;
        }
        for (String alias : List.of("cmd", "command_line", "commandLine", "script", "args",
                "shell_command", "input")) {
            Object v = parameters.get(alias);
            String text = v == null ? null : shellText(v);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>(parameters);
            out.remove(alias);
            out.put("command", text);
            return out;
        }
        return parameters;
    }

    private static String shellText(Object v) {
        if (v instanceof String s) {
            return s.strip();
        }
        if (v instanceof List<?> items) {
            List<String> words = new ArrayList<>();
            for (Object o : items) {
                words.add(String.valueOf(o));
            }
            if (words.size() >= 3 && words.get(0).matches("(?:.*/)?(?:sh|bash|zsh|dash)")
                    && words.get(1).matches("-l?i?c")) {
                return String.join(" ", words.subList(2, words.size())).strip();
            }
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.matches("[\\w./=:,@%+*-]+") ? w : "'" + w.replace("'", "'\\''") + "'");
            }
            return sb.toString();
        }
        return null;
    }

    /** The Harmony envelope as a plan (one tool action, or a final answer), else null. */
    ParsedPlan parseHarmony(String planText) {
        if (planText == null || !planText.contains("<|channel|>")) {
            return null;
        }
        Matcher call = HARMONY_TOOL_CALL.matcher(planText);
        if (call.find()) {
            String tool = call.group(1);
            tool = canonicalTool(tool.substring(tool.lastIndexOf('.') + 1));
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (String tok : call.group(2).trim().split("\\s+")) {
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    parameters.put(tok.substring(0, eq), tok.substring(eq + 1));
                }
            }
            String rest = planText.substring(call.end());
            String payload = extractJsonBlock(rest);
            if (payload != null) {
                try {
                    JsonNode params = readTreeWithRepair(payload);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = objectMapper.convertValue(params, Map.class);
                    parameters.putAll(json);
                } catch (Exception e) {
                    return null;
                }
            } else {
                String raw = rest.replaceAll("<\\|[a-z]+\\|>.*$", "").strip();
                if (raw.isEmpty()) {
                    return null;
                }
                parameters.put(tool.matches("(?i)bash|exec|shell|sh") ? "command" : "input", raw);
                if (!tool.matches("(?i)bash|exec|shell|sh")) {
                    parameters.putIfAbsent("query", raw);
                }
            }
            Action a = new Action(tool, shellParameters(tool, parameters));
            return new ParsedPlan(null, null,
                    List.of(new Branch(runBranchName(a), "native tool call", 1.0, List.of(a))));
        }
        Matcher fin = HARMONY_FINAL.matcher(planText);
        if (fin.find() && !fin.group(1).isBlank()) {
            String text = fin.group(1).strip();
            if (text.startsWith("{")) {
                return null;
            }
            return new ParsedPlan(text, null, null);
        }
        return null;
    }

    private static final Pattern PLAN_START = Pattern.compile(
            "\\{\\s*\"(?:branches|final_answer|clarify)\"\\s*:");

    private String planShapedBlock(String text) {
        if (text == null) {
            return null;
        }
        List<Integer> starts = new ArrayList<>();
        Matcher m = PLAN_START.matcher(text);
        while (m.find()) {
            starts.add(m.start());
        }
        for (int i = starts.size() - 1; i >= 0; i--) {
            String block = extractJsonBlock(text.substring(starts.get(i)));
            if (block == null) {
                continue;
            }
            try {
                JsonNode root = readTreeWithRepair(block);
                if (root.has("branches") || root.has("final_answer") || root.has("clarify")) {
                    return block;
                }
            } catch (Exception notAPlan) {
                // An object that only starts like a plan: try the one before it.
            }
        }
        return null;
    }

    public ParsedPlan parse(String planText) {
        boolean jsonFirst = planText != null && planText.strip().startsWith("{");
        ParsedPlan harmony = jsonFirst ? null : parseHarmony(planText);
        if (harmony != null) {
            return harmony;
        }
        try {
            String jsonBlock = planShapedBlock(planText);
            if (jsonBlock == null) {
                jsonBlock = extractJsonBlock(planText);
            }
            if (jsonBlock == null) {
                System.err.println("[PlanParser]: no JSON block found in plan text.");
                return jsonFirst ? parseHarmony(planText) : null;
            }

            JsonNode root = readTreeWithRepair(jsonBlock);
            if (root.hasNonNull("final_answer")) {
                return new ParsedPlan(stripShapeLabel(root.path("final_answer").asText()), null, null);
            }
            if (root.hasNonNull("clarify")) {
                return new ParsedPlan(null, root.path("clarify").asText(), null);
            }

            // Bare tool-call shapes and top-level action arrays become a single branch.
            if (root.hasNonNull("tool") && !root.has("branches")) {
                Map<String, Object> parameters = readParams(root);
                if (parameters != null) {
                    String canonical = canonicalTool(root.path("tool").asText());
                    Action a = new Action(canonical, shellParameters(canonical, parameters));
                    return new ParsedPlan(null, null,
                            List.of(new Branch(runBranchName(a), "direct tool call", 1.0, List.of(a))));
                }
            }
            if (root.path("actions").isArray() && !root.path("actions").isEmpty()) {
                List<Action> actions = parseActions(root.path("actions"));
                if (!actions.isEmpty()) {
                    return new ParsedPlan(null, null,
                            List.of(new Branch("Run plan", "direct action list", 1.0, actions)));
                }
            }

            JsonNode branchesNode = root.path("branches");
            if (!branchesNode.isArray() || branchesNode.isEmpty()) {
                System.err.println("[PlanParser]: JSON has no final_answer, clarify, or non-empty branches.");
                return null;
            }
            List<Branch> branches = new ArrayList<>();
            for (JsonNode branchNode : branchesNode) {
                String name = branchNode.path("name").asText("");
                String description = branchNode.path("description").asText("");
                double confidence = branchNode.path("confidence").asDouble(0.0);
                List<Action> actions = parseActions(branchNode.path("actions"));
                if (name.isEmpty() || actions.isEmpty()) {
                    System.err.println("[PlanParser]: skipped branch without name or actions: " + branchNode);
                    continue;
                }
                branches.add(new Branch(name, description, confidence, actions));
            }
            if (branches.isEmpty()) {
                System.err.println("[PlanParser]: no valid branches survived parsing.");
                return null;
            }
            return new ParsedPlan(null, null, branches);
        } catch (Exception e) {
            System.err.println("[PlanParser]: FATAL parse error: " + e.getMessage());
            String text = planText == null ? "null" : planText.strip();
            System.err.println("[PlanParser]: the text that did not parse (first 600 chars): "
                    + text.substring(0, Math.min(600, text.length())).replace("\n", "\\n"));
            return null;
        }
    }

    /** Read a tool call's args from either "parameters" or the OpenAI-style "arguments" key. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readParams(JsonNode toolCall) {
        JsonNode params = toolCall.has("parameters") ? toolCall.path("parameters")
                : toolCall.path("arguments");
        if (params.isObject()) {
            return objectMapper.convertValue(params, Map.class);
        }
        // "arguments" is sometimes a JSON STRING (OpenAI function-call convention) - parse it
        if (params.isTextual()) {
            try {
                JsonNode inner = objectMapper.readTree(params.asText());
                if (inner.isObject()) {
                    return objectMapper.convertValue(inner, Map.class);
                }
            } catch (Exception ignored) {
                // not JSON - fall through
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Action> parseActions(JsonNode actionsNode) {
        List<Action> actions = new ArrayList<>();
        if (!actionsNode.isArray()) {
            return actions;
        }
        for (JsonNode actionNode : actionsNode) {
            String tool = actionNode.path("tool").asText("");
            if (tool.isEmpty()) {
                tool = actionNode.path("name").asText("");   // some models key the tool as "name"
            }
            Map<String, Object> parameters = readParams(actionNode);
            if (tool.isEmpty() || parameters == null) {
                System.err.println("[PlanParser]: skipped malformed action: " + actionNode);
                continue;
            }
            String canonical = canonicalTool(tool);
            actions.add(new Action(canonical, shellParameters(canonical, parameters)));
        }
        return actions;
    }

    /**
     * The branch name of a bare tool call includes the action and its first value,
     * so distinct lookups under one tool do not all receive the same name.
     */
    static String runBranchName(Action a) {
        StringBuilder sb = new StringBuilder("Run ").append(a.toolName());
        if (a.parameters() != null) {
            Object action = a.parameters().get("action");
            if (action != null) {
                sb.append(' ').append(action);
            }
            for (String key : List.of("symbol", "name", "command", "testClass", "query", "file")) {
                Object v = a.parameters().get(key);
                if (v != null) {
                    String t = String.valueOf(v).strip().replaceAll("\\s+", " ");
                    sb.append(' ').append(t.length() > 60 ? t.substring(0, 60) + "…" : t);
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * The JSON payload is the first BALANCED {...} object, scanned string-aware so
     * braces inside JSON strings - or in any prose/fenced code that follows the
     * object - do not throw off the boundaries. Falls back to the last '}' if the
     * braces never balance.
     */
    private String extractJsonBlock(String planText) {
        int first = planText.indexOf('{');
        if (first < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = first; i < planText.length(); i++) {
            char c = planText.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return planText.substring(first, i + 1);
                }
            }
        }
        // Close an unfinished JSON prefix in nesting order before falling back to the last brace.
        String closed = closeOpenBrackets(planText.substring(first));
        if (closed != null) {
            return closed;
        }
        int last = planText.lastIndexOf('}');
        return last > first ? planText.substring(first, last + 1) : null;
    }

    /**
     * A JSON block that does not parse as-is gets repairs for code-shaped unquoted
     * array items and unclosed brackets before giving up. Shared by bare JSON and
     * embedded Harmony payloads.
     */
    private JsonNode readTreeWithRepair(String jsonBlock) throws JsonProcessingException {
        try {
            return objectMapper.readTree(jsonBlock);
        } catch (JsonProcessingException first) {
            String quoted = quoteUnquotedArrayItems(jsonBlock);
            if (quoted.equals(jsonBlock)) {
                String repaired = closeOpenBrackets(jsonBlock);
                if (repaired == null) {
                    throw first;
                }
                return objectMapper.readTree(repaired);
            }
            return objectMapper.readTree(quoted);
        }
    }

    /** Return the last non-whitespace character, or zero when there is none. */
    private static char lastCodeChar(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(out.charAt(i))) {
                return out.charAt(i);
            }
        }
        return 0;
    }

    /**
     * Close unclosed braces and brackets at the end, innermost first, or return
     * null when the text is not a JSON prefix that can be repaired.
     */
    static String closeOpenBrackets(String text) {
        String body = text.replaceAll("(\\s|<\\|[a-z_]+\\|>)+$", "");
        ArrayDeque<Character> open = new ArrayDeque<>();
        StringBuilder out = new StringBuilder(body.length() + 4);
        boolean inString = false;
        boolean escaped = false;
        boolean changed = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' && !open.isEmpty() && open.peek() == '{'
                    && lastCodeChar(out) == ',') {
                // An object-opening brace after a comma can mean the previous object
                // was left open; close it at the comma before starting the next one.
                int comma = out.lastIndexOf(",");
                out.insert(comma, '}');
                open.pop();
                open.push(c);
                changed = true;
            } else if (c == '{' || c == '[') {
                open.push(c);
            } else if (c == '}' || c == ']') {
                if (open.isEmpty()) {
                    changed = true;
                    continue;
                }
                char wants = c == '}' ? '{' : '[';
                if (open.peek() != wants && !open.contains(wants)) {
                    // A closer whose bracket type is not open is stray and can be dropped.
                    changed = true;
                    continue;
                }
                char expected = open.pop() == '{' ? '}' : ']';
                if (c != expected) {
                    changed = true;
                    c = expected;
                }
            }
            out.append(c);
        }
        if (inString) {
            return null;
        }
        if (open.isEmpty() && !changed) {
            return null;
        }
        while (out.length() > 0 && (out.charAt(out.length() - 1) == ','
                || Character.isWhitespace(out.charAt(out.length() - 1)))) {
            out.setLength(out.length() - 1);
        }
        while (!open.isEmpty()) {
            out.append(open.pop() == '{' ? '}' : ']');
        }
        return out.toString();
    }
}
