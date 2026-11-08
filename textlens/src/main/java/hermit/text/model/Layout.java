package hermit.text.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/**
 * A text's layout (the owner, 2026-11-06), found on its lines before any token is read: each line with its tags, and
 * the blocks the lines make. A line that may be a title or part of a paragraph carries both tags and lies in a TITLE
 * block, and in a PARAGRAPH block when text follows it. The token-level rules read the {@link #flow} text.
 */
public record Layout(List<Line> lines, List<Block> blocks) {

    /** A line's tags. */
    public enum LineTag {
        TITLE, PART_OF_PARAGRAPH, LIST_ITEM, LIST_ITEM_TEXT, CODE, TABLE_ROW, QUOTE, RULE, PAGE_BREAK, METADATA,
        COMMENT, BINARY
    }

    /** A block's kind. */
    public enum Kind {
        PARAGRAPH, TITLE, LIST_ITEM, CODE, TABLE_ROW, QUOTE, RULE, PAGE_BREAK, METADATA, COMMENT, BINARY
    }

    /**
     * One line: its index from 0, its range (without its line break, and without a carriage return before the break),
     * its indentation in columns (a tab to the next multiple of 4), and its tags (none for a blank line).
     */
    public record Line(int index, TextRange range, int indent, Set<LineTag> tags) {}

    /**
     * One block: its id ({@code b1}, {@code b2}, ... in the order of the blocks), its kind, its range (from its first
     * line's start to its last line's end), its first and last line, and its attributes (an object, empty when it has
     * none; the rules of {@code hermit.text.lex.LayoutAnalyzer} name them).
     */
    public record Block(String id, Kind kind, TextRange range, int firstLine, int lastLine, JsonNode attributes) {}

    /**
     * The flow text of {@code text} (the text this layout was found in): the text with the same length, in which every
     * character between two lines of a PARAGRAPH or a LIST_ITEM block (a line feed, and a carriage return before it)
     * and every tab inside such a block is a space. A paragraph is so one line to the token-level rules, and every
     * range into the flow text is the same range into the text.
     */
    public String flow(String text) {
        char[] chars = text.toCharArray();
        for (Block block : blocks) {
            if (block.kind() != Kind.PARAGRAPH && block.kind() != Kind.LIST_ITEM) {
                continue;
            }
            for (int index = block.firstLine(); index <= block.lastLine(); index++) {
                TextRange range = lines.get(index).range();
                int end = index < block.lastLine() ? lines.get(index + 1).range().start() : range.end();
                for (int at = range.start(); at < end; at++) {
                    if (at >= range.end() || chars[at] == '\t') {
                        chars[at] = ' ';
                    }
                }
            }
        }
        return new String(chars);
    }
}
