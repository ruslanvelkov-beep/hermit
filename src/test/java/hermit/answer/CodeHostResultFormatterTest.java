package hermit.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure tests: the formatter turns a GitHub skill result into an answer that
 * actually contains the real values - the guarantee the model kept dropping. */
class CodeHostResultFormatterTest {

    private final CodeHostResultFormatter fmt = new CodeHostResultFormatter();

    private Map<String, Object> prResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "Success");
        r.put("action", "prView");
        r.put("number", 500);
        r.put("title", "fix: make the date parser thread-safe");
        r.put("state", "OPEN");
        r.put("author", "example-dev");
        r.put("url", "https://github.com/example-org/calendar-app/pull/500");
        r.put("additions", 174);
        r.put("deletions", 16);
        r.put("filesChanged", 5);
        r.put("baseRef", "main");
        r.put("headRef", "fix/thread-safe-parser");
        r.put("body", "Make DateParser and TimeParser thread-safe.");
        r.put("files", List.of("A.java", "B.java"));
        return r;
    }

    @Test
    void formatsPrWithAllRealValues() {
        assertTrue(fmt.canFormat("GitHub", prResult()));
        String out = fmt.format("GitHub", prResult());
        // The exact substance the 7B kept failing to report is present verbatim.
        assertTrue(out.contains("#500"), out);
        assertTrue(out.contains("fix: make the date parser thread-safe"), out);
        assertTrue(out.contains("OPEN"), out);
        assertTrue(out.contains("example-dev"), out);
        assertTrue(out.contains("+174/-16"), out);
        assertTrue(out.contains("5 files changed"), out);
        assertTrue(out.contains("A.java"), out);
    }

    @Test
    void formatsDiffAndIssue() {
        Map<String, Object> diff = Map.of("status", "Success", "action", "diffPR",
                "ref", "https://github.com/o/r/pull/1", "diff", "diff --git a/X b/X\n+added");
        assertTrue(fmt.canFormat("GitHub", diff));
        assertTrue(fmt.format("GitHub", diff).contains("+added"));

        Map<String, Object> issue = Map.of("status", "Success", "action", "issueView",
                "number", 7, "title", "Bug", "state", "CLOSED", "author", "x",
                "url", "u", "body", "boom");
        assertTrue(fmt.format("GitHub", issue).contains("Issue #7"));
        assertTrue(fmt.format("GitHub", issue).contains("boom"));
    }

    @Test
    void doesNotFormatErrorsOrOtherTools() {
        assertFalse(fmt.canFormat("GitHub", Map.of("status", "Error", "message", "nope")));
        assertFalse(fmt.canFormat("Bash", Map.of("status", "Success", "action", "prView")));
        assertFalse(fmt.canFormat("GitHub", Map.of("status", "Success", "action", "somethingElse")));
    }
}
