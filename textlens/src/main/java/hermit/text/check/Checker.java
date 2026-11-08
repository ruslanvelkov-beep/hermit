package hermit.text.check;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.Sentence;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.util.List;

/**
 * One consistency check of the annotator (milestone A5 of the annotation plan, section 7). A checker reads the concept
 * spans of one text and reports a {@link Check} for each statement of its kind, with the spans it used as the targets,
 * its evidence, the assumptions it made and its reason codes. A statement that can be read more than one way is
 * consistent only when every reading holds and inconsistent only when no reading holds; otherwise it is unverifiable,
 * so a checker never calls a statement inconsistent while one of its readings is consistent. A statement whose inputs
 * are missing (no clock, a calendar unit, a division by zero) is unverifiable too.
 */
public interface Checker {

    /** The checker's name, as its checks report it. */
    String name();

    /** The checks of {@code input}, in text order. */
    List<Check> check(Input input);

    /**
     * A text with its tagged tokens, its sentences, its merged concept spans, the clock captured for it and the year
     * mentions of its sources ({@link DateChecker#sourceMentions}: the texts it was written from).
     */
    record Input(String text, List<Token> tokens, List<Sentence> sentences, List<Span> spans,
            AnnotationDocument.Context context, List<DateChecker.YearMention> sourceMentions) {

        /** The input as given, with a copy of the sources' mentions. */
        public Input {
            sourceMentions = List.copyOf(sourceMentions);
        }

        /** The input of a text without sources. */
        public Input(String text, List<Token> tokens, List<Sentence> sentences, List<Span> spans,
                AnnotationDocument.Context context) {
            this(text, tokens, sentences, spans, context, List.of());
        }
    }
}
