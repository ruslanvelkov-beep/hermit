package hermit.text.model;

/** A half-open range [start, end) of UTF-16 indices into a document's unchanged text, as a Java String indexes it. */
public record TextRange(int start, int end) {

    /**
     * The range, refused with an IllegalArgumentException when start is negative, when end is before start or when end
     * is past the text's length.
     */
    public static TextRange of(int start, int end, int textLength) {
        if (start < 0) {
            throw new IllegalArgumentException("start: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("end: " + end);
        }
        if (end > textLength) {
            throw new IllegalArgumentException("end: " + end);
        }
        return new TextRange(start, end);
    }

    /** The number of UTF-16 units in the range. */
    public int length() {
        return end - start;
    }

    /** Whether {@code other} lies inside this range; an empty range at either edge lies inside. */
    public boolean contains(TextRange other) {
        return start <= other.start() && other.end() <= end;
    }

    /** Whether the two ranges share at least one index; ranges that only touch do not overlap. */
    public boolean overlaps(TextRange other) {
        return start < other.end() && other.start() < end;
    }

    /** The slice of {@code text} that the range covers. */
    public String surfaceIn(String text) {
        return text.substring(start, end);
    }
}
