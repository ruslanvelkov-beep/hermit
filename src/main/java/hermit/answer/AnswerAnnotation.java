package hermit.answer;

import hermit.text.TextLens;
import hermit.text.check.Findings;
import hermit.text.model.AnnotationDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The annotator's shadow run on a final answer (milestone A6 of the annotation plan, section 7). Every final answer is
 * annotated by TextLens ({@link TextLens#annotate}), and the document is written next to the answer step as
 * {@code annotation.json}, with its findings, what would refuse the answer ({@link Findings}), in
 * {@code annotation-findings.txt}; nothing in the turn reads them, as with the shadow critic. Refusals stay off, and
 * the shadow run only records the findings (the owner, 2026-11-07): {@code -Dhermit.annotation.refuse=true} turns
 * them on, {@code -Dhermit.annotation=off} turns the whole run off.
 */
public final class AnswerAnnotation {

    static final String FILE = "annotation.json";
    static final String ERROR_FILE = "annotation-error.txt";
    static final String FINDINGS_FILE = "annotation-findings.txt";

    private final boolean enabled;
    private final boolean refuse;

    /** One answer's annotation: the document, or the annotator's failure when it threw. */
    public record Result(AnnotationDocument document, String error) {}

    AnswerAnnotation(boolean enabled, boolean refuse) {
        this.enabled = enabled;
        this.refuse = refuse;
    }

    public static AnswerAnnotation fromSystemProperties() {
        return new AnswerAnnotation(!"off".equalsIgnoreCase(System.getProperty("hermit.annotation", "on")),
                Boolean.getBoolean("hermit.annotation.refuse"));
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@code answer} annotated as {@link #annotate(String, List, Instant, ZoneId)} describes, with no sources. */
    public Result annotate(String answer, Instant instant, ZoneId zone) {
        return annotate(answer, List.of(), instant, zone);
    }

    /**
     * {@code answer} annotated by {@link TextLens#annotate} with the clock {@code instant} in {@code zone} and the
     * years that {@code sources} mention: the texts the answer was written from, such as the user's request and the
     * tool results; null when the run is off. Never throws: the annotator's failure is the result's error.
     */
    public Result annotate(String answer, List<String> sources, Instant instant, ZoneId zone) {
        if (!enabled || answer == null) {
            return null;
        }
        try {
            return new Result(TextLens.standard().annotate(answer, sources, instant, zone), null);
        } catch (RuntimeException e) {   // the shadow run never breaks an answer: its failure is recorded instead
            return new Result(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Writes {@code result} next to the answer step: the document as JSON and, when it has findings, one line for each
     * in {@link #FINDINGS_FILE} (its checker, a colon and its sentence), whether refusals are on or not; or the
     * annotator's failure.
     */
    public void record(Path step, Result result) {
        if (step == null || result == null) {
            return;
        }
        try {
            if (result.document() != null) {
                Files.writeString(step.resolve(FILE), result.document().toJson(), StandardCharsets.UTF_8);
                StringBuilder lines = new StringBuilder();
                for (Findings.Finding finding : Findings.of(result.document())) {
                    lines.append(finding.checker()).append(": ").append(finding.sentence()).append('\n');
                }
                if (!lines.isEmpty()) {
                    Files.writeString(step.resolve(FINDINGS_FILE), lines, StandardCharsets.UTF_8);
                }
            } else {
                Files.writeString(step.resolve(ERROR_FILE), result.error() + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[annotation] not recorded at " + step + ": " + e.getMessage());
        }
    }

    /**
     * The issues that refuse the answer: the sentence of each of its findings ({@link Findings#of}), in the
     * document's order; empty while refusals are off.
     */
    public List<String> refusals(Result result) {
        List<String> issues = new ArrayList<>();
        if (!refuse || result == null || result.document() == null) {
            return issues;
        }
        for (Findings.Finding finding : Findings.of(result.document())) {
            issues.add(finding.sentence());
        }
        return issues;
    }
}
