package hermit.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Clause;
import hermit.text.model.Marker;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A4 of the annotation plan: the shadow document of an answer carries the marker and clause layers, no other. */
class AnswerMarkersTest {

    private static final AnswerAnnotation SHADOW = new AnswerAnnotation(true, false);
    private static final Instant CLOCK = Instant.parse("2026-10-28T10:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void anAnswerCarriesItsMarkers() {
        AnnotationDocument document = SHADOW.annotate("No test failed, so the build can ship.", CLOCK, UTC).document();
        assertEquals(List.of(Marker.Class.NEGATION, Marker.Class.QUANTIFIER, Marker.Class.CAUSAL,
                Marker.Class.MODALITY), document.layers().markers().stream().map(Marker::markerClass).toList());
        assertEquals(List.of(Clause.Type.MAIN, Clause.Type.COORDINATE),
                document.layers().clauses().stream().map(Clause::type).toList());
        assertNull(document.layers().frames());
        assertNull(document.layers().coreference());
    }

    @Test
    void anAnswerWithoutMarkersHasAnEmptyMarkerLayer() {
        AnnotationDocument document = SHADOW.annotate("The build passed.", CLOCK, UTC).document();
        assertEquals(List.of(), document.layers().markers());
        assertTrue(document.toJson().contains("\"markers\":[]"));
    }
}
