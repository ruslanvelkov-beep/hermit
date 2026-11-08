package hermit.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.check.Findings;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.DateResolution;
import hermit.text.model.Layout;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TextLens's entry point: a text annotated as one document with its layers, and the findings in it. */
class TextLensTest {

    static final Instant CLOCK = Instant.parse("2026-10-28T10:00:00Z");
    static final ZoneId UTC = ZoneId.of("UTC");
    static final TextLens LENS = TextLens.standard();

    @Test
    void anInconsistentCheckIsAFindingWithItsSentence() {
        AnnotationDocument document = LENS.annotate("The release was on Mon Oct 20, 2026.", List.of(), CLOCK, UTC);
        assertEquals(TextLens.RULESET, document.ruleset());
        assertEquals(List.of(new Findings.Finding("date", document.checks().get(0).targets(),
                "October 20, 2026 was a Tuesday, not a Monday.")), LENS.findings(document));
    }

    @Test
    void theDocumentHoldsTheDateLayer() {
        AnnotationDocument document = LENS.annotate("We met on Tue Oct 20 and ship Nov 3.", List.of(), CLOCK, UTC);
        assertEquals(List.of(DateResolution.State.DISAMBIGUATED, DateResolution.State.ASSUMED),
                document.layers().dates().stream().map(DateResolution::state).toList());
        assertEquals("2026-11-03", document.layers().dates().get(1).date());
    }

    @Test
    void theDocumentHoldsTheLayoutLayer() {
        AnnotationDocument document = LENS.annotate("## Plan\n\nRun the suite,\nthen commit.\n", List.of(), CLOCK, UTC);
        assertEquals(List.of(Layout.Kind.TITLE, Layout.Kind.PARAGRAPH),
                document.layers().layout().blocks().stream().map(Layout.Block::kind).toList());
        assertEquals(document, AnnotationDocument.fromJson(document.toJson()));
    }
}
