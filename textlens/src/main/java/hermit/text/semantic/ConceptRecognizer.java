package hermit.text.semantic;

import hermit.text.lex.LayoutAnalyzer;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.util.List;
import java.util.Set;

/**
 * One kind of concept span (milestone A3 of the annotation plan, section 6.1). A recognizer reads the tagged tokens of
 * one text and reports the spans of its classes: each span starts at a token's start and ends at a token's end, keeps
 * the other readings the text allows in its alternatives, and has a provenance of the rules. Its ids are its own
 * ({@link ConceptRecognizers} numbers the merged spans again).
 *
 * <p>A value is typed JSON, as the gold set writes it: a number as a decimal string ({@code "78000"}), a quantity as
 * {@code {"amount": "12", "unit": "GB"}}, a date as {@code "2026-10-28"} or, without a year, {@code "--10-28"}, a
 * relative date as {@code {"relative": "today"}}, a weekday as {@code "THURSDAY"}, a time as {@code "22:12"}, a
 * duration in ISO 8601 ({@code "PT40M"}, {@code "P2D"}), a version, a command, a flag, an expression, a product or a
 * name as written or as its catalogue entry names it, a comparison as {@code {"op": ">=", "value": "0.95"}}, and a code
 * reference as {@code {"kind": "member", "name": "RepeatGuard#signature"}}. An alternative is
 * {@code {"concept": "VERSION", "value": "1.10"}}: the concept of the other reading and its value.
 */
public interface ConceptRecognizer {

    /** The spans this recognizer finds, in text order. */
    List<Span> recognize(Input input);

    /**
     * A text with its tagged tokens, its sentences and the clock captured for it; its layout ({@code null} when it has
     * none) and the ids of the tokens that the rules reading a capital as a name's sign read in lower case
     * ({@link LayoutAnalyzer#lowerCaseReadings}).
     */
    record Input(String text, List<Token> tokens, List<Sentence> sentences, AnnotationDocument.Context context,
            Layout layout, Set<String> lowerCase) {

        /** A text with its tagged tokens, its sentences and its clock, no layout and no token read in lower case. */
        public Input(String text, List<Token> tokens, List<Sentence> sentences, AnnotationDocument.Context context) {
            this(text, tokens, sentences, context, null, Set.of());
        }
    }
}
