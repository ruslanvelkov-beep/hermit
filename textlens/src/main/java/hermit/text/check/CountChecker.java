package hermit.text.check;

import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.model.Check;
import hermit.text.model.Sentence;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Counts against lists (milestone A5 of the annotation plan, section 7: "the following three fixes:" and a list of
 * four), checker {@code count}. A statement is a sentence that is no list item, whose last token is a colon and that
 * holds a NUMBER span valued with a whole number from 2 to 100 that a noun phrase follows (the last such span of the
 * sentence), followed directly by list items. The noun phrase is the tokens right after the number whose first
 * candidates are ADJ, NOUN or PROPN, at least one, and the last of them NOUN ({@code three fixes},
 * {@code three new fixes}, {@code two follow-up fixes}). The items counted are the LIST_ITEM sentences right
 * after it, up to the first sentence that is no list item, that start at the same column of their line as the first of
 * them (deeper items are nested and not counted). The count holds when it equals the number of items: CONSISTENT
 * (reason {@code count-matches}) or INCONSISTENT ({@code count-mismatch}). It is UNVERIFIABLE when one of
 * {@link #HEDGES} is the token right before the number (reason {@code hedged}), or when the last counted item ends
 * with {@code etc.} or an ellipsis, three periods or U+2026 (reason {@code open-list}). A sentence without a list item
 * right after it is not checked. Evidence: {@code {"stated": 3, "listed": 4}}. Target: the NUMBER span. Checks come in
 * text order.
 */
public final class CountChecker implements Checker {

    /** Words that make a count approximate or a bound when they come right before it, in lower case. */
    public static final List<String> HEDGES = List.of("about", "around", "roughly", "approximately", "nearly", "almost",
            "some", "over", "under", "least", "most", "than", "to");

    /** {@code count}. */
    @Override
    public String name() {
        return "count";
    }

    /** The checks of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Check> check(Input input) {
        List<Check> checks = new ArrayList<>();
        for (int sentenceIndex = 0; sentenceIndex < input.sentences().size(); sentenceIndex++) {
            Sentence sentence = input.sentences().get(sentenceIndex);
            if (sentence.kind() == Sentence.Kind.LIST_ITEM) {
                continue;
            }
            int lastToken = sentenceLastToken(input, sentence);
            if (lastToken < 0 || !":".equals(Checks.word(input, lastToken))) {
                continue;
            }
            Span count = countSpan(input, sentence);
            if (count == null || sentenceIndex + 1 >= input.sentences().size()) {
                continue;
            }
            Sentence firstItem = input.sentences().get(sentenceIndex + 1);
            if (firstItem.kind() != Sentence.Kind.LIST_ITEM) {
                continue;
            }
            int column = lineColumn(input.text(), firstItem.range().start());
            int listed = 0;
            Sentence lastCounted = null;
            for (int itemIndex = sentenceIndex + 1; itemIndex < input.sentences().size(); itemIndex++) {
                Sentence item = input.sentences().get(itemIndex);
                if (item.kind() != Sentence.Kind.LIST_ITEM) {
                    break;
                }
                if (lineColumn(input.text(), item.range().start()) == column) {
                    listed++;
                    lastCounted = item;
                }
            }
            int stated = statedCount(count);
            ObjectNode evidence = Checks.JSON.objectNode();
            evidence.put("stated", stated);
            evidence.put("listed", listed);
            int before = Checks.firstToken(input, count) - 1;
            boolean hedged = before >= 0 && HEDGES.contains(Checks.word(input, before));
            boolean open = lastCounted != null && openEnding(input, lastCounted);
            Check.Status status;
            String reason;
            if (hedged) {
                status = Check.Status.UNVERIFIABLE;
                reason = "hedged";
            } else if (open) {
                status = Check.Status.UNVERIFIABLE;
                reason = "open-list";
            } else if (stated == listed) {
                status = Check.Status.CONSISTENT;
                reason = "count-matches";
            } else {
                status = Check.Status.INCONSISTENT;
                reason = "count-mismatch";
            }
            checks.add(Checks.check(name(), status, List.of(count), evidence, List.of(), List.of(reason)));
        }
        return checks;
    }

    private static int sentenceLastToken(Input input, Sentence sentence) {
        for (int i = input.tokens().size() - 1; i >= 0; i--) {
            Token token = input.tokens().get(i);
            if (sentence.range().start() <= token.range().start()
                    && token.range().end() <= sentence.range().end()) {
                return i;
            }
        }
        return -1;
    }

    private static int statedCount(Span span) {
        try {
            BigDecimal value = new BigDecimal(span.value().asText()).stripTrailingZeros();
            if (value.scale() > 0) {
                return -1;
            }
            int count = value.intValueExact();
            return count >= 2 && count <= 100 ? count : -1;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1;
        }
    }

    private static Span countSpan(Input input, Sentence sentence) {
        Span selected = null;
        for (Span span : input.spans()) {
            if (span.concept() != Span.Concept.NUMBER
                    || span.range().start() < sentence.range().start()
                    || span.range().end() > sentence.range().end()
                    || statedCount(span) < 0) {
                continue;
            }
            int next = Checks.lastToken(input, span) + 1;
            boolean hasPhrase = false;
            Tag lastTag = null;
            while (next < input.tokens().size()) {
                Token token = input.tokens().get(next);
                if (token.range().start() < sentence.range().start()
                        || token.range().end() > sentence.range().end()
                        || token.candidates().isEmpty()) {
                    break;
                }
                Tag tag = token.candidates().get(0).tag();
                if (tag != Tag.ADJ && tag != Tag.NOUN && tag != Tag.PROPN) {
                    break;
                }
                hasPhrase = true;
                lastTag = tag;
                next++;
            }
            if (hasPhrase && lastTag == Tag.NOUN) {
                selected = span;
            }
        }
        return selected;
    }

    private static int lineColumn(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1));
        return offset - lineStart - 1;
    }

    private static boolean openEnding(Input input, Sentence sentence) {
        String surface = sentence.range().surfaceIn(input.text()).strip().toLowerCase(Locale.ROOT);
        return surface.endsWith("etc.") || surface.endsWith("...") || surface.endsWith("\u2026");
    }
}
