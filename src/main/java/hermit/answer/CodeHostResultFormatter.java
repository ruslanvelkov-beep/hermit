package hermit.answer;

import java.util.List;
import java.util.Map;

/**
 * Deterministically renders a code-host skill result (prView / issueView /
 * diffPR) into a finished answer. Because it reads the structured fields the skill
 * already parsed from gh, the actual PR/issue values (title, state, author, files,
 * counts, body/diff) always reach the user - no model synthesis in the loop.
 */
public class CodeHostResultFormatter implements ResultFormatter {

    @Override
    public boolean canFormat(String toolName, Map<String, Object> result) {
        if (!"GitHub".equals(toolName) || result == null) {
            return false;
        }
        if (!"Success".equalsIgnoreCase(String.valueOf(result.get("status")))) {
            return false;
        }
        String action = String.valueOf(result.get("action"));
        return action.equals("prView") || action.equals("issueView") || action.equals("diffPR");
    }

    @Override
    public String format(String toolName, Map<String, Object> result) {
        String action = String.valueOf(result.get("action"));
        return switch (action) {
            case "diffPR" -> formatDiff(result);
            case "issueView" -> formatIssue(result);
            default -> formatPr(result);
        };
    }

    private String formatPr(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("PR #").append(str(r.get("number"))).append(" \"").append(str(r.get("title")))
                .append("\" [").append(str(r.get("state"))).append("]");
        String author = str(r.get("author"));
        if (!author.isBlank()) {
            sb.append(" by ").append(author);
        }
        sb.append("\n").append(str(r.get("url")));
        String base = str(r.get("baseRef"));
        String head = str(r.get("headRef"));
        if (!base.isBlank() || !head.isBlank()) {
            sb.append("\n").append(head).append(" → ").append(base);
        }
        Object add = r.get("additions");
        Object del = r.get("deletions");
        Object fc = r.get("filesChanged");
        if (add != null || del != null || fc != null) {
            sb.append("\n").append(fc == null ? "?" : str(fc)).append(" files changed");
            if (add != null || del != null) {
                sb.append(" (+").append(add == null ? 0 : str(add)).append("/-")
                        .append(del == null ? 0 : str(del)).append(")");
            }
        }
        String body = str(r.get("body"));
        if (!body.isBlank()) {
            sb.append("\n\n").append(body.strip());
        }
        appendFiles(sb, r.get("files"));
        return sb.toString();
    }

    private String formatIssue(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue #").append(str(r.get("number"))).append(" \"").append(str(r.get("title")))
                .append("\" [").append(str(r.get("state"))).append("]");
        String author = str(r.get("author"));
        if (!author.isBlank()) {
            sb.append(" by ").append(author);
        }
        sb.append("\n").append(str(r.get("url")));
        String body = str(r.get("body"));
        if (!body.isBlank()) {
            sb.append("\n\n").append(body.strip());
        }
        return sb.toString();
    }

    private String formatDiff(Map<String, Object> r) {
        String diff = str(r.get("diff"));
        return "Diff for " + str(r.get("ref")) + ":\n" + diff;
    }

    private void appendFiles(StringBuilder sb, Object files) {
        if (files instanceof List<?> list && !list.isEmpty()) {
            sb.append("\n\nFiles:");
            for (Object f : list) {
                sb.append("\n  - ").append(String.valueOf(f));
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
