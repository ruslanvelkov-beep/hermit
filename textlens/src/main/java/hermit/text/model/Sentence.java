package hermit.text.model;

import java.util.List;

/**
 * One sentence: its range, its kind (a list item, a table row, a heading and a fenced code block are sentences of
 * their own) and its tokens' ids in text order. An uncertain boundary is recorded, not decided.
 */
public record Sentence(String id, TextRange range, Kind kind, Boundary boundary, List<String> tokenIds) {

    /** What kind of text the sentence is. */
    public enum Kind { PROSE, LIST_ITEM, TABLE_ROW, HEADING, CODE }

    /** Whether the splitter was sure where the sentence ends. */
    public enum Boundary { CERTAIN, UNCERTAIN }
}
