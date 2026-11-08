package hermit.text.semantic;

import hermit.text.lex.LayoutAnalyzer;
import hermit.text.lex.SentenceSplitter;
import hermit.text.lex.Tokenizer;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.Span;
import hermit.text.model.Token;
import hermit.text.pos.PosTagger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The concept stage of the annotator (milestone A3 of the annotation plan, section 6.1): every recognizer's spans,
 * merged. Merging drops a NUMBER span that lies inside a span of another concept other than a DURATION or a
 * COMPARISON (the numbers of a date, a time, an expression; the number of {@code 40 minutes} or {@code at most 3}
 * stays, nested), a CODE_REF or COMMAND span with the same range as a span of a concept other than these two
 * (a product, a flag, a version, an expression, a duration written {@code 5-hour}), and a span with the same range and
 * concept as one kept already. The rest come ordered by start, then the longer first, then by concept in the order of
 * {@link Span.Concept}, with the ids {@code c1}, {@code c2}, ... in that order.
 */
public final class ConceptRecognizers {

    private final List<ConceptRecognizer> recognizers;

    /** The concept stage with these recognizers. */
    public ConceptRecognizers(List<ConceptRecognizer> recognizers) {
        this.recognizers = List.copyOf(recognizers);
    }

    /**
     * The standard recognizers: numbers, dates and times, versions, code references, products, expressions and pairs
     * of numbers ({@link PairRecognizer}), in that order.
     */
    public static ConceptRecognizers standard() {
        return new ConceptRecognizers(List.of(new NumberRecognizer(), new DateTimeRecognizer(),
                new VersionRecognizer(), new CodeRefRecognizer(), new ProductRecognizer(),
                new ExpressionRecognizer(), new PairRecognizer()));
    }

    /**
     * The input for {@code text}: its layout first ({@link LayoutAnalyzer}, the owner, 2026-11-06), then, over the
     * layout's flow text ({@link Layout#flow}, the text with the same length whose paragraphs have no line breaks),
     * its tokens from the standard tokenizer, its sentences from the standard splitter in the layout's blocks, and the
     * tokens tagged by the standard tagger. Then the layout is settled by the tagged tokens
     * ({@link LayoutAnalyzer#settle}); when that changes it, the flow text, the tokens, the sentences and the tags come
     * once more from the settled layout. The input's text is the flow text, with {@code context}, the layout, and the
     * tokens read in lower case ({@link LayoutAnalyzer#lowerCaseReadings}).
     */
    public static ConceptRecognizer.Input input(String text, AnnotationDocument.Context context) {
        Layout layout = LayoutAnalyzer.standard().analyze(text);
        String flow = layout.flow(text);
        List<Token> tokens = Tokenizer.standard().tokenize(flow);
        List<Sentence> sentences = SentenceSplitter.standard().split(flow, tokens, layout);
        tokens = PosTagger.standard().tag(flow, tokens, sentences);
        Layout settled = LayoutAnalyzer.settle(layout, flow, tokens);
        if (settled != layout) {
            layout = settled;
            flow = layout.flow(text);
            tokens = Tokenizer.standard().tokenize(flow);
            sentences = SentenceSplitter.standard().split(flow, tokens, layout);
            tokens = PosTagger.standard().tag(flow, tokens, sentences);
        }
        return new ConceptRecognizer.Input(flow, tokens, sentences, context, layout,
                LayoutAnalyzer.lowerCaseReadings(layout, flow, tokens, sentences));
    }

    /** The merged spans of every recognizer for {@code input}. */
    public List<Span> recognize(ConceptRecognizer.Input input) {
        List<Span> found = new ArrayList<>();
        for (ConceptRecognizer recognizer : recognizers) {
            found.addAll(recognizer.recognize(input));
        }
        found.sort(Comparator.comparingInt((Span span) -> span.range().start())
                .thenComparing(Comparator.comparingInt((Span span) -> span.range().end()).reversed())
                .thenComparing(Span::concept));

        List<Span> kept = new ArrayList<>();
        for (Span candidate : found) {
            boolean suppressed = false;
            if (candidate.concept() == Span.Concept.NUMBER) {
                for (Span other : found) {
                    if (other != candidate && other.concept() != Span.Concept.NUMBER
                            && other.concept() != Span.Concept.DURATION
                            && other.concept() != Span.Concept.COMPARISON
                            && other.range().contains(candidate.range())) {
                        suppressed = true;
                        break;
                    }
                }
            } else if (candidate.concept() == Span.Concept.CODE_REF
                    || candidate.concept() == Span.Concept.COMMAND) {
                for (Span other : found) {
                    if (other != candidate && other.concept() != Span.Concept.CODE_REF
                            && other.concept() != Span.Concept.COMMAND
                            && other.range().equals(candidate.range())) {
                        suppressed = true;
                        break;
                    }
                }
            }
            if (suppressed) {
                continue;
            }
            boolean duplicate = kept.stream().anyMatch(span -> span.concept() == candidate.concept()
                    && span.range().equals(candidate.range()));
            if (!duplicate) {
                kept.add(candidate);
            }
        }

        Map<Integer, Integer> startToIndex = new HashMap<>();
        Map<Integer, Integer> endToIndex = new HashMap<>();
        for (int i = 0; i < input.tokens().size(); i++) {
            startToIndex.put(input.tokens().get(i).range().start(), i);
            endToIndex.put(input.tokens().get(i).range().end(), i);
        }
        List<Span> merged = new ArrayList<>();
        for (Span span : kept) {
            Integer from = startToIndex.get(span.range().start());
            Integer to = endToIndex.get(span.range().end());
            if (from == null || to == null || to < from) {
                throw new IllegalArgumentException("concept span is not token-aligned: " + span.range());
            }
            merged.add(Spans.over(input, from, to, "c" + (merged.size() + 1), span.concept(), span.value(),
                    span.alternatives(), span.provenance().rule()));
        }
        return List.copyOf(merged);
    }

    /** The merged spans of the standard recognizers for {@code text} with {@code context}. */
    public static List<Span> spansOf(String text, AnnotationDocument.Context context) {
        return standard().recognize(input(text, context));
    }
}
