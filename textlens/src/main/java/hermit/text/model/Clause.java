package hermit.text.model;

import java.util.List;

/**
 * A clause (plan section 6.3): its range and tokens, its type, its head (the lexical verb, or the copula before a
 * predicate; null for a verbless clause), whether it is finite, the clause it depends on (null for a main clause) and
 * whether the text asserts it. A clause nested in another lies inside the other's range.
 */
public record Clause(String id, TextRange range, List<String> tokenIds, Type type, String head, boolean finite,
        String parent, Asserted asserted) {

    /** What kind of clause it is; a coordinate clause is the second or a later one of coordinated clauses. */
    public enum Type { MAIN, SUBORDINATE, RELATIVE, COORDINATE, CELL, VERBLESS }

    /** Whether the text asserts the clause, or only quotes it or reports someone else's claim. */
    public enum Asserted { ASSERTED, QUOTED, ATTRIBUTED }
}
