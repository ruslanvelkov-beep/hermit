package hermit.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.TextLens;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A6 of the annotation plan: the annotator's shadow run on a final answer, the findings it records and its refusals
 * once they are turned on. The findings themselves are TextLens's (hermit.text.check.FindingsTest).
 */
class AnswerAnnotationTest {

    static final Instant CLOCK = Instant.parse("2026-10-28T10:00:00Z");
    static final ZoneId UTC = ZoneId.of("UTC");
    static final AnswerAnnotation SHADOW = new AnswerAnnotation(true, false);
    static final AnswerAnnotation REFUSING = new AnswerAnnotation(true, true);

    static List<Check.Status> statuses(AnswerAnnotation.Result result) {
        return result.document().checks().stream().map(Check::status).toList();
    }

    @Test
    void inconsistentDateRefusesTheAnswer() {
        AnswerAnnotation.Result result = REFUSING.annotate("The release was on Mon Oct 20, 2026.", CLOCK, UTC);
        assertEquals(List.of(Check.Status.INCONSISTENT), statuses(result));
        assertEquals(List.of("October 20, 2026 was a Tuesday, not a Monday."), REFUSING.refusals(result));
    }

    @Test
    void theSourcesGiveTheYearOfADateWithoutOne() {
        String answer = "How about Thursday, July 6?";
        assertEquals(List.of("July 6, 2026 was a Monday, not a Thursday."),
                REFUSING.refusals(REFUSING.annotate(answer, CLOCK, UTC)));
        AnswerAnnotation.Result result = REFUSING.annotate(answer, List.of("Sent 07/18/2000 08:58 AM"), CLOCK, UTC);
        assertEquals(List.of(Check.Status.CONSISTENT), statuses(result));
        assertEquals(List.of(), REFUSING.refusals(result));
    }

    @Test
    void refusalsStayOffUnlessTurnedOn() {
        AnswerAnnotation.Result result = SHADOW.annotate("The release was on Mon Oct 20, 2026.", CLOCK, UTC);
        assertEquals(List.of(Check.Status.INCONSISTENT), statuses(result));
        assertEquals(List.of(), SHADOW.refusals(result));
        assertNull(new AnswerAnnotation(false, true).annotate("The release was on Mon Oct 20, 2026.", CLOCK, UTC));
    }

    @Test
    void annotationWrittenNextToTheAnswer() throws Exception {
        Path step = Files.createTempDirectory("hermit-annotation");
        AnswerAnnotation.Result result = SHADOW.annotate("The command printed marker-42 (Bash exit 0).", CLOCK, UTC);
        SHADOW.record(step, result);
        AnnotationDocument read = AnnotationDocument.fromJson(Files.readString(step.resolve(AnswerAnnotation.FILE)));
        assertEquals(result.document(), read);
        assertEquals(TextLens.RULESET, read.ruleset());
        assertFalse(read.tokens().isEmpty());
        assertFalse(Files.exists(step.resolve(AnswerAnnotation.FINDINGS_FILE)), "no findings, no file");
        SHADOW.record(step, new AnswerAnnotation.Result(null, "IllegalStateException: no tokens"));
        assertTrue(Files.readString(step.resolve(AnswerAnnotation.ERROR_FILE)).startsWith("IllegalStateException"));
    }

    @Test
    void theShadowRunRecordsTheFindings() throws Exception {
        Path step = Files.createTempDirectory("hermit-annotation");
        SHADOW.record(step, SHADOW.annotate("The release was on Mon Oct 20, 2026, and 2 + 2 = 5 here.", CLOCK, UTC));
        assertEquals("date: October 20, 2026 was a Tuesday, not a Monday.\n"
                + "arithmetic: 2 + 2 = 5 does not hold: 4 = 5 is false.\n",
                Files.readString(step.resolve(AnswerAnnotation.FINDINGS_FILE)));
    }
}
