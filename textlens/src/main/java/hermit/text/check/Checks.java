package hermit.text.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import hermit.text.model.Check;
import hermit.text.model.Sentence;
import hermit.text.model.Span;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What the checkers share: the status of a statement's readings, the check record, and the tokens around a span. */
public final class Checks {

    /** The factory every checker makes its evidence with. */
    public static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Checks() {}

    /**
     * The status of a statement from whether each of its readings holds: CONSISTENT when every reading holds,
     * INCONSISTENT when none holds, UNVERIFIABLE when they differ or when there is no reading.
     */
    public static Check.Status status(List<Boolean> readings) {
        if (readings.isEmpty()) {
            return Check.Status.UNVERIFIABLE;
        }
        if (readings.stream().allMatch(Boolean.TRUE::equals)) {
            return Check.Status.CONSISTENT;
        }
        if (readings.stream().noneMatch(Boolean.TRUE::equals)) {
            return Check.Status.INCONSISTENT;
        }
        return Check.Status.UNVERIFIABLE;
    }

    /** A check of {@code checker} that targets the ids of {@code spans} in their order, with copies of the lists. */
    public static Check check(String checker, Check.Status status, List<Span> spans, JsonNode evidence,
            List<Check.Assumption> assumptions, List<String> reasons) {
        return new Check(spans.stream().map(Span::id).toList(), checker, status, evidence,
                List.copyOf(assumptions), List.copyOf(reasons));
    }

    /** The index of the token that starts where {@code span} starts, or -1. */
    public static int firstToken(Checker.Input input, Span span) {
        for (int i = 0; i < input.tokens().size(); i++) {
            if (input.tokens().get(i).range().start() == span.range().start()) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the token that ends where {@code span} ends, or -1. */
    public static int lastToken(Checker.Input input, Span span) {
        for (int i = 0; i < input.tokens().size(); i++) {
            if (input.tokens().get(i).range().end() == span.range().end()) {
                return i;
            }
        }
        return -1;
    }

    /** The sentence whose range holds the start of {@code span}, or null. */
    public static Sentence sentenceOf(Checker.Input input, Span span) {
        int start = span.range().start();
        for (Sentence sentence : input.sentences()) {
            if (sentence.range().start() <= start && start < sentence.range().end()) {
                return sentence;
            }
        }
        return null;
    }

    /** Token {@code i}'s text, lower-cased with the root locale; null when there is no token {@code i}. */
    public static String word(Checker.Input input, int i) {
        if (i < 0 || i >= input.tokens().size()) {
            return null;
        }
        return input.tokens().get(i).range().surfaceIn(input.text()).toLowerCase(Locale.ROOT);
    }

    /** The longest span of one of {@code concepts} that starts where token {@code i} starts, or null. */
    public static Span spanAt(Checker.Input input, int i, Set<Span.Concept> concepts) {
        if (i < 0 || i >= input.tokens().size()) {
            return null;
        }
        int start = input.tokens().get(i).range().start();
        Span longest = null;
        for (Span span : input.spans()) {
            if (span.range().start() == start && concepts.contains(span.concept())
                    && (longest == null || span.range().end() > longest.range().end())) {
                longest = span;
            }
        }
        return longest;
    }
}
