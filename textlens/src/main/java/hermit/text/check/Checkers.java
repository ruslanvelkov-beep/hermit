package hermit.text.check;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.semantic.ConceptRecognizers;
import java.util.List;

/**
 * The check stage of the annotator (milestone A5 of the annotation plan, section 7): every checker's checks for one
 * text, the checkers in their order and each checker's checks in its own order. The checks only report; nothing here
 * refuses an answer (the plan's shadow step comes first).
 */
public final class Checkers {

    private final List<Checker> checkers;

    /** The check stage with these checkers. */
    public Checkers(List<Checker> checkers) {
        this.checkers = List.copyOf(checkers);
    }

    /** The checkers' names, in their order. */
    public List<String> names() {
        return checkers.stream().map(Checker::name).toList();
    }

    /** The standard checkers: dates, arithmetic, counts, versions, totals and units (in that order). */
    public static Checkers standard() {
        return new Checkers(List.of(new DateChecker(), new ArithmeticChecker(), new CountChecker(),
                new VersionChecker(), new UnitChecker()));
    }

    /**
     * The input for {@code text}: the tokens, sentences and context of {@link ConceptRecognizers#input}, with the
     * merged spans of the standard recognizers.
     */
    public static Checker.Input input(String text, AnnotationDocument.Context context) {
        return input(text, context, List.of());
    }

    /**
     * The input for {@code text} as {@link #input(String, AnnotationDocument.Context)} gives it, with the year mentions
     * of {@code sources} ({@link DateChecker#sourceMentions}).
     */
    public static Checker.Input input(String text, AnnotationDocument.Context context, List<String> sources) {
        var recognized = ConceptRecognizers.input(text, context);
        return new Checker.Input(recognized.text(), recognized.tokens(), recognized.sentences(),
                ConceptRecognizers.standard().recognize(recognized), recognized.context(),
                DateChecker.sourceMentions(sources));
    }

    /** Every checker's checks for {@code input}. */
    public List<Check> check(Checker.Input input) {
        return checkers.stream().flatMap(checker -> checker.check(input).stream()).toList();
    }

    /** The standard checkers' checks for {@code text} with {@code context}. */
    public static List<Check> checksOf(String text, AnnotationDocument.Context context) {
        return standard().check(input(text, context));
    }
}
