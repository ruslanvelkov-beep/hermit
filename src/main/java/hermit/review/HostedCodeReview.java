package hermit.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.analysis.tree.JavaParser;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.model.escalation.EscalationLadder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hosted reviewer that joins the code critic panels (TreeCodegen, GenerateCode, GenerateTest) when the escalation
 * ladder is on. How complicated the code is picks the lowest rung: its branch count - if/for/while/do, case, catch,
 * ternary, && and || - read from Hermit's parse tree (a lexical count when the text does not parse, e.g. a fragment).
 * One holistic review: does the code do the task, is its logic right, and are a test's assertions meaningful.
 */
public final class HostedCodeReview {

    /** Branches and the deepest nesting of control statements; {@code parsed} false when counted from the text. */
    public record Complexity(int branches, int depth, boolean parsed) {}

    /** The hosted verdict and the model that gave it. */
    public record Verdict(boolean approved, List<String> issues, String model) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<NodeType> BRANCHES = EnumSet.of(NodeType.IF, NodeType.FOR, NodeType.FOR_EACH, NodeType.WHILE,
            NodeType.DO, NodeType.CASE, NodeType.CATCH, NodeType.TERNARY);
    private static final Set<NodeType> NESTING = EnumSet.of(NodeType.IF, NodeType.FOR, NodeType.FOR_EACH, NodeType.WHILE,
            NodeType.DO, NodeType.SWITCH, NodeType.SWITCH_FUNCTION, NodeType.TRY);
    private static final Pattern LEXICAL_BRANCH = Pattern.compile("\\b(?:if|for|while|do|case|catch)\\b|&&|\\|\\||\\?(?![>,\\s]*[>,])");
    private static final Pattern STRINGS_AND_COMMENTS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|/\\*.*?\\*/",
            Pattern.DOTALL);

    private HostedCodeReview() {}

    /** On when the ladder is. */
    public static boolean enabled() {
        return EscalationLadder.shared().enabled();
    }

    public static Complexity complexity(String code) {
        List<Node> roots = parse(code);
        if (roots == null) {
            return lexical(code);
        }
        int[] branches = {0};
        int[] depth = {0};
        roots.forEach(root -> root.walk(n -> {
            if (BRANCHES.contains(n.type())) {
                branches[0]++;
            } else if (n.type() == NodeType.BINARY_EXPRESSION && n.token() != null
                    && ("&&".equals(n.token().text()) || "||".equals(n.token().text()))) {
                branches[0]++;
            }
            if (NESTING.contains(n.type())) {
                depth[0] = Math.max(depth[0], nesting(n));
            }
        }));
        return new Complexity(branches[0], depth[0], true);
    }

    /** Control statements around and including this one; an else-if continues its chain instead of nesting. */
    private static int nesting(Node n) {
        int d = 0;
        for (Node at = n; at != null; at = at.parent()) {
            boolean elseIf = at.type() == NodeType.IF && at.parent() != null && at.parent().type() == NodeType.ELSE;
            if (NESTING.contains(at.type()) && !elseIf) {
                d++;
            }
        }
        return d;
    }

    /** The code's parse tree: a whole file, one member, or a run of statements; null when none of them parses. */
    private static List<Node> parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            if (code.matches("(?s).*\\b(?:class|interface|enum|record)\\s+\\w+.*")) {
                return List.of(JavaParser.parse(code).root());
            }
        } catch (RuntimeException ignored) {
            // not a whole file: try a member, then statements
        }
        try {
            return List.of(JavaParser.parseMemberSnippet(code));
        } catch (RuntimeException ignored) {
            // not one member
        }
        try {
            return JavaParser.parseStatementsSnippet(code);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Complexity lexical(String code) {
        String text = code == null ? "" : STRINGS_AND_COMMENTS.matcher(code).replaceAll("\"\"");
        int branches = 0;
        Matcher m = LEXICAL_BRANCH.matcher(text);
        while (m.find()) {
            branches++;
        }
        int depth = 0;
        int open = 0;
        for (char c : text.toCharArray()) {
            if (c == '{') {
                depth = Math.max(depth, ++open);
            } else if (c == '}') {
                open = Math.max(0, open - 1);
            }
        }
        return new Complexity(branches, depth, false);
    }

    /**
     * The hosted review of {@code code} for {@code task}; {@code note} adds context (e.g. a red TDD test). Null when the
     * ladder is off or no rung answered with a readable verdict - the local panel then decides alone.
     */
    public static Verdict review(String task, String code, String note) {
        EscalationLadder ladder = EscalationLadder.shared();
        if (!ladder.enabled() || code == null || code.isBlank()) {
            return null;
        }
        Complexity c = complexity(code);
        String prompt = "You are a sceptical senior Java reviewer. Judge the code below on three points: whether it actually "
                + "accomplishes the stated task; the correctness of its logic (control flow, edge cases, contracts); and, "
                + "when it is a test, whether its assertions are meaningful and not vacuous.\n\nTASK the code claims to "
                + "accomplish: " + task + (note == null || note.isBlank() ? "" : "\n\n" + note.strip())
                + "\n\nCODE:\n" + code
                + "\n\nReject ONLY for a concrete defect, and name it. Respond with ONE JSON object: "
                + "{\"approved\": true|false, \"issues\": [\"...\"]}.";
        EscalationLadder.Reply reply = ladder.ask(EscalationLadder.Job.CODE_REVIEW, 0, ladder.reviewFloor(c.branches()), prompt, 4000);
        System.err.println("[Ladder] code review: " + c.branches() + " branches, depth " + c.depth()
                + (c.parsed() ? "" : " (counted from the text)") + (reply == null ? " — no hosted verdict" : " — " + reply.model()));
        if (reply == null) {
            return null;
        }
        try {
            String text = reply.text();
            int from = text.indexOf('{');
            int to = text.lastIndexOf('}');
            JsonNode n = MAPPER.readTree(from >= 0 && to > from ? text.substring(from, to + 1) : text);
            List<String> issues = new ArrayList<>();
            n.path("issues").forEach(i -> issues.add(i.asText()));
            return new Verdict(n.path("approved").asBoolean(false), issues, reply.model());
        } catch (Exception e) {
            return null;
        }
    }
}
