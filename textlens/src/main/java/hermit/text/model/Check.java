package hermit.text.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A checker's result for spans or relations, with its evidence, the assumptions it made (each with its source) and
 * its reason codes. It holds for the checked relation only.
 */
public record Check(List<String> targets, String checker, Status status, JsonNode evidence,
        List<Assumption> assumptions, List<String> reasons) {

    /** A check's outcome; missing inputs and competing readings are unverifiable, never consistent. */
    public enum Status { CONSISTENT, INCONSISTENT, UNVERIFIABLE }

    /** An input that the checker supplied itself, such as a missing year taken from the captured clock. */
    public record Assumption(String kind, JsonNode value, String source) {}
}
