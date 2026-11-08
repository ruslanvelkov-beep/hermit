package hermit.text.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A concept span: its range, its class, its normalized value as typed JSON (exact numbers as decimal strings) and the
 * other readings it keeps, such as a decimal and a version for 1.10. Spans may nest and overlap.
 */
public record Span(String id, TextRange range, Concept concept, JsonNode value, List<JsonNode> alternatives,
        Provenance provenance) {

    /** The concept classes a recognizer may report. */
    public enum Concept {
        NUMBER, QUANTITY, PERCENT, UNIT, EXPRESSION, COMPARISON, DATE, TIME, WEEKDAY, DURATION, VERSION, CODE_REF,
        COMMAND, FLAG, PRODUCT, NAME, RANGE, SECTION, TALLY
    }
}
