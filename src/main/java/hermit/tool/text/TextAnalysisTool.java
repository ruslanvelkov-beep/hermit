package hermit.tool.text;

import hermit.text.TextLens;
import hermit.text.check.Findings;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Span;
import hermit.text.model.Token;
import hermit.tool.api.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TextLens as an agent tool, in this process: the findings, the tags or the concept spans of a text the agent gives or
 * of a file it names, so that it can check any text it reads or writes, not only its final answer. The registry offers
 * it only with {@code -Dhermit.textlens.tool=true}.
 */
public class TextAnalysisTool implements Tool {

    /** The longest text the tool reads, in characters: a longer text or file is cut there. */
    static final int MAX_CHARS = 100_000;
    /** The most tokens or spans an answer lists; the answer then says how many more there are. */
    static final int MAX_ROWS = 200;

    private final Clock clock;

    /** The tool with the system's clock and zone. */
    public TextAnalysisTool() {
        this(Clock.systemDefaultZone());
    }

    /** The tool with {@code clock}, whose instant and zone the annotation takes. */
    TextAnalysisTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String getDocumentation() {
        return "TextAnalysis: TextLens over a text or a file. Parameters: {\"action\": \"check\"|\"tag\"|"
                + "\"spans\", \"text\": \"<the text>\" or \"path\": \"<a file>\"}. check = the findings, what in "
                + "the text does not hold: a weekday that does not fit its date, a false sum, parts that do not add up "
                + "to a stated total, a version said to be newer than a later one (a quotation or an example in "
                + "backquotes is not asserted, so it is no finding); tag = each word with its part of speech; spans = "
                + "the concepts found (dates, numbers, versions, code references, ...), each with its text.";
    }

    /**
     * Runs one action over the text: {@code action} is check, tag or spans, and the text is {@code text} or the file
     * at {@code path} (exactly one of them, cut at {@link #MAX_CHARS} characters). The text is annotated by
     * {@link TextLens#standard()} with the tool's clock and no sources. Success answers {@code status} "Success" with:
     * for check, {@code findings} (the findings' sentences, in order) and {@code message} ("no findings", "1
     * finding" or "N findings"); for tag, {@code tags}, each token as written, a slash and its tag, joined by a
     * space; for spans, {@code spans}, each span's concept, a colon, a space and its text. Tag and spans list at most
     * {@link #MAX_ROWS} rows and then add a last row "... and N more". A problem answers {@code status} "Error" and a
     * {@code message}: no action or another one, neither text nor path or both, a file that cannot be read.
     */
    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        Object actionValue = parameters.get("action");
        if (!(actionValue instanceof String action)
                || !(action.equals("check") || action.equals("tag") || action.equals("spans"))) {
            return Map.of("status", "Error", "message", "action must be check, tag or spans.");
        }
        boolean hasText = parameters.containsKey("text");
        boolean hasPath = parameters.containsKey("path");
        if (hasText == hasPath) {
            return Map.of("status", "Error", "message", "give either text or path, not both and not neither.");
        }
        String text;
        if (hasText) {
            text = String.valueOf(parameters.get("text"));
        } else {
            String path = String.valueOf(parameters.get("path"));
            try {
                text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException exception) {
                return Map.of("status", "Error", "message", "cannot read " + path + ": " + exception.getMessage());
            }
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        AnnotationDocument document = TextLens.standard().annotate(
                text, List.of(), clock.instant(), clock.getZone());
        if (action.equals("check")) {
            List<String> findings = new ArrayList<>();
            for (var finding : Findings.of(document)) {
                findings.add(finding.sentence());
            }
            String message = findings.isEmpty() ? "no findings"
                    : findings.size() == 1 ? "1 finding" : findings.size() + " findings";
            return Map.of("status", "Success", "findings", findings, "message", message);
        }
        if (action.equals("tag")) {
            List<String> tags = new ArrayList<>();
            for (Token token : document.tokens()) {
                tags.add(token.range().surfaceIn(document.text()) + "/" + token.tag());
            }
            return Map.of("status", "Success", "tags", String.join(" ", limitRows(tags)));
        }
        List<String> spans = new ArrayList<>();
        for (Span span : document.spans()) {
            spans.add(span.concept() + ": " + span.range().surfaceIn(document.text()));
        }
        return Map.of("status", "Success", "spans", limitRows(spans));
    }

    private static List<String> limitRows(List<String> rows) {
        if (rows.size() <= MAX_ROWS) {
            return rows;
        }
        List<String> limited = new ArrayList<>(rows.subList(0, MAX_ROWS));
        limited.add("... and " + (rows.size() - MAX_ROWS) + " more");
        return limited;
    }
}
