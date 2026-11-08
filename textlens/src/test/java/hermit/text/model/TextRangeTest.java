package hermit.text.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextRangeTest {

    @Test
    void ofRefusesARangeOutsideTheText() {
        assertEquals(new TextRange(3, 5), TextRange.of(3, 5, 10));
        assertEquals(new TextRange(10, 10), TextRange.of(10, 10, 10));   // an empty range at the end
        assertThrows(IllegalArgumentException.class, () -> TextRange.of(-1, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> TextRange.of(5, 4, 10));
        assertThrows(IllegalArgumentException.class, () -> TextRange.of(3, 11, 10));
    }

    @Test
    void containsAndOverlapsAreHalfOpen() {
        TextRange word = new TextRange(0, 5);
        assertTrue(word.contains(new TextRange(1, 3)));
        assertTrue(word.contains(new TextRange(5, 5)), "an empty range at the end lies inside");
        assertFalse(word.contains(new TextRange(4, 6)));
        assertTrue(word.overlaps(new TextRange(4, 6)));
        assertFalse(word.overlaps(new TextRange(5, 6)), "ranges that only touch do not overlap");
    }

    @Test
    void surfaceIsTheUtf16Slice() {
        String text = "a😀b";   // an emoji is two UTF-16 units
        TextRange emoji = new TextRange(1, 3);
        assertEquals("😀", emoji.surfaceIn(text));
        assertEquals(2, emoji.length());
        assertEquals("b", new TextRange(3, 4).surfaceIn(text));
    }
}
