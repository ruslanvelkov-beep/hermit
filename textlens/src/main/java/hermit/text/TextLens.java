package hermit.text;

import hermit.text.check.Checker;
import hermit.text.check.Checkers;
import hermit.text.check.DateChecker;
import hermit.text.check.Findings;
import hermit.text.lex.LayoutAnalyzer;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.Coverage;
import hermit.text.model.Layers;
import hermit.text.model.Span;
import hermit.text.semantic.ClauseSegmenter;
import hermit.text.semantic.ConceptRecognizer;
import hermit.text.semantic.MarkerRecognizer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * TextLens, the text annotator as a tool of its own: its single entry point. A text is annotated as one
 * {@link AnnotationDocument}, and {@link #findings} lists what in it would refuse the text ({@link Findings}). TextLens
 * never refuses: a host decides what to do with the findings. The tagger reads its models from the system properties
 * {@code hermit.pos.weights} and {@code hermit.pos.search}, once.
 */
public final class TextLens {

    /** The annotator's milestones so far, as the document's ruleset: A0-A3 and A5. */
    public static final String RULESET = "a5";
    private static final TextLens STANDARD = new TextLens();

    private TextLens() {
    }

    /** The annotator with the tagger's models of the system properties. */
    public static TextLens standard() {
        return STANDARD;
    }

    /**
     * {@code text} annotated with the clock {@code instant} in {@code zone}, with the years that {@code sources}
     * mention given to the checkers ({@link Checkers#input(String, AnnotationDocument.Context, List)}): the texts it
     * was written from, such as a request and tool results. The document holds the tokens, sentences, concept spans,
     * checks and each token's coverage by the spans, and the layers of the markers ({@link MarkerRecognizer}), the
     * clauses ({@link ClauseSegmenter}) over the same input and those markers, the dates ({@link DateChecker#resolve})
     * and the layout ({@link LayoutAnalyzer}, settled by the text's tokens: {@link LayoutAnalyzer#settle}); its frame
     * and coreference layers are absent until the annotator computes them. The document is validated.
     */
    public AnnotationDocument annotate(String text, List<String> sources, Instant instant, ZoneId zone) {
        AnnotationDocument.Context context = new AnnotationDocument.Context(instant, zone, "en");
        Checker.Input input = Checkers.input(text, context, sources);
        List<Check> checks = Checkers.standard().check(input);
        List<Coverage> coverage = input.tokens().stream().map(t -> new Coverage(t.id(), input.spans().stream()
                .filter(s -> s.range().start() <= t.range().start() && t.range().end() <= s.range().end())
                .map(Span::id).toList())).toList();
        ConceptRecognizer.Input markerInput = new ConceptRecognizer.Input(input.text(), input.tokens(),
                input.sentences(), context);
        var markers = new MarkerRecognizer().recognize(markerInput, input.spans());
        var clauses = new ClauseSegmenter().segment(markerInput, markers);
        Layers layers = new Layers(markers, clauses, null, null, DateChecker.resolve(input),
                LayoutAnalyzer.settle(LayoutAnalyzer.standard().analyze(text), input.text(), input.tokens()));
        return new AnnotationDocument(AnnotationDocument.SCHEMA_VERSION, RULESET, text, context, null,
                input.sentences(), input.tokens(), input.spans(), List.of(), checks, coverage, layers).validated();
    }

    /** What in {@code document} would refuse its text: {@link Findings#of}. */
    public List<Findings.Finding> findings(AnnotationDocument document) {
        return Findings.of(document);
    }
}
