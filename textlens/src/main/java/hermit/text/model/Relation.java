package hermit.text.model;

import java.util.List;

/** A relation between spans that a checker may check. */
public record Relation(String id, Kind kind, List<String> spanIds, Provenance provenance) {

    /** The relations the checkers know. */
    public enum Kind { EQUALITY, COMPARISON, TOTAL, DURATION, VERSION_ORDER, CODE_REFERENCE }
}
