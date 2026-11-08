package hermit.text.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnnotationLayersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("UTC"), "en");
    private static final String TEXT = "If `Shelf#lend` fails, it returns 2 notices; the clerk says \"never retry\".";
    private static final List<String> SURFACES = List.of("If", "`Shelf#lend`", "fails", ",", "it", "returns", "2",
            "notices", ";", "the", "clerk", "says", "\"", "never", "retry", "\"", ".");
    private static final List<Tag> TAGS = List.of(Tag.SCONJ, Tag.PROPN, Tag.VERB, Tag.PUNCT, Tag.PRON, Tag.VERB,
            Tag.NUM, Tag.NOUN, Tag.PUNCT, Tag.DET, Tag.NOUN, Tag.VERB, Tag.PUNCT, Tag.ADV, Tag.VERB, Tag.PUNCT,
            Tag.PUNCT);

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The fixture's tokens t1 to t17, each found after the one before it. */
    private static List<Token> tokens() {
        List<Token> tokens = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < SURFACES.size(); i++) {
            int start = TEXT.indexOf(SURFACES.get(i), at);
            at = start + SURFACES.get(i).length();
            Token.Shape shape = i == 1 ? Token.Shape.CODE_SPAN : i == 6 ? Token.Shape.NUMBER
                    : TAGS.get(i) == Tag.PUNCT ? Token.Shape.PUNCT : Token.Shape.WORD;
            tokens.add(new Token("t" + (i + 1), new TextRange(start, at), shape,
                    List.of(new Token.Candidate(TAGS.get(i), "fixture")), false, Provenance.rule("fixture")));
        }
        return tokens;
    }

    /** The fixture text with its one sentence, tokens, two spans and coverage, and these layers. */
    private static AnnotationDocument document(Layers layers) {
        List<Token> tokens = tokens();
        List<Span> spans = List.of(new Span("s1", tokens.get(1).range(), Span.Concept.CODE_REF,
                        json("{\"kind\":\"member\",\"name\":\"Shelf#lend\"}"), List.of(), Provenance.rule("code-ref")),
                new Span("s2", tokens.get(6).range(), Span.Concept.NUMBER, json("\"2\""), List.of(),
                        Provenance.rule("number")));
        List<Coverage> coverage = tokens.stream().map(t -> new Coverage(t.id(), spans.stream()
                .filter(s -> s.range().contains(t.range())).map(Span::id).toList())).toList();
        return new AnnotationDocument(1, "text-1", TEXT, CONTEXT, null, List.of(new Sentence("s1",
                new TextRange(0, TEXT.length()), Sentence.Kind.PROSE, Sentence.Boundary.CERTAIN,
                tokens.stream().map(Token::id).toList())), tokens, spans, List.of(), List.of(), coverage, layers);
    }

    /** The token ids t{from} to t{to}. */
    private static List<String> ids(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add("t" + i);
        }
        return ids;
    }

    /** From the start of the first named token to the end of the last. */
    private static TextRange range(List<String> tokenIds) {
        List<Token> tokens = tokens();
        int first = Integer.parseInt(tokenIds.get(0).substring(1)) - 1;
        int last = Integer.parseInt(tokenIds.get(tokenIds.size() - 1).substring(1)) - 1;
        return new TextRange(tokens.get(first).range().start(), tokens.get(last).range().end());
    }

    private static Clause clause(String id, int from, int to, Clause.Type type, String head, String parent,
            Clause.Asserted asserted) {
        return new Clause(id, range(ids(from, to)), ids(from, to), type, head, true, parent, asserted);
    }

    private static Marker marker(String id, List<String> tokenIds, Marker.Class markerClass, String kind,
            Marker.Scope scope) {
        return new Marker(id, range(tokenIds), tokenIds, markerClass, kind, scope, Provenance.rule("marker"));
    }

    private static Frame.Phrase phrase(String head, int from, int to) {
        return new Frame.Phrase(head, ids(from, to));
    }

    /** Every layer, with each form of scope, a null in each place that allows one, and an unresolved anaphor. */
    private static Layers full() {
        List<Clause> clauses = List.of(
                clause("c1", 1, 4, Clause.Type.SUBORDINATE, "t3", "c2", Clause.Asserted.ASSERTED),
                clause("c2", 1, 9, Clause.Type.MAIN, "t6", null, Clause.Asserted.ASSERTED),
                clause("c3", 10, 17, Clause.Type.MAIN, "t12", null, Clause.Asserted.ASSERTED),
                clause("c4", 14, 15, Clause.Type.SUBORDINATE, "t15", "c3", Clause.Asserted.QUOTED));
        List<Marker> markers = List.of(
                marker("m1", ids(1, 1), Marker.Class.CONDITIONAL, null,
                        new Marker.Scope("c1", null, "c1", "c2", null)),
                marker("m2", ids(12, 12), Marker.Class.ATTRIBUTION, null,
                        new Marker.Scope("c3", null, null, null, List.of("c4"))),
                marker("m3", List.of("t13", "t16"), Marker.Class.QUOTATION, "quote",
                        new Marker.Scope(null, ids(14, 15), null, null, List.of("c4"))),
                marker("m4", ids(14, 14), Marker.Class.NEGATION, "clausal",
                        new Marker.Scope("c4", null, null, null, null)),
                marker("m5", ids(14, 14), Marker.Class.FREQUENCY, "never",
                        new Marker.Scope("c4", null, null, null, null)));
        List<Frame> frames = List.of(
                new Frame("c1", phrase("t2", 2, 2), phrase("t3", 3, 3), null, null, null, Frame.Polarity.POSITIVE),
                new Frame("c2", phrase("t5", 5, 5), phrase("t6", 6, 6), phrase("t8", 7, 8), null, null,
                        Frame.Polarity.POSITIVE),
                new Frame("c3", phrase("t11", 10, 11), phrase("t12", 12, 12), null, "c4", null,
                        Frame.Polarity.POSITIVE),
                new Frame("c4", null, phrase("t15", 14, 15), null, null, "you", Frame.Polarity.NEGATIVE));
        List<Coreference> coreference = List.of(
                new Coreference(ids(5, 5), new Coreference.Antecedent(ids(2, 2), "s1")),
                new Coreference(ids(10, 11), null));
        return new Layers(markers, clauses, frames, coreference);
    }

    private static <T> List<T> with(List<T> list, int index, T value) {
        List<T> copy = new ArrayList<>(list);
        copy.set(index, value);
        return copy;
    }

    private static Layers withMarker(int index, Marker marker) {
        Layers l = full();
        return new Layers(with(l.markers(), index, marker), l.clauses(), l.frames(), l.coreference());
    }

    private static Layers withClause(int index, Clause clause) {
        Layers l = full();
        return new Layers(l.markers(), with(l.clauses(), index, clause), l.frames(), l.coreference());
    }

    private static Layers withFrame(int index, Frame frame) {
        Layers l = full();
        return new Layers(l.markers(), l.clauses(), with(l.frames(), index, frame), l.coreference());
    }

    private static Layers withCoreference(int index, Coreference coreference) {
        Layers l = full();
        return new Layers(l.markers(), l.clauses(), l.frames(), with(l.coreference(), index, coreference));
    }

    private static Marker scoped(Marker m, Marker.Scope scope) {
        return new Marker(m.id(), m.range(), m.tokenIds(), m.markerClass(), m.kind(), scope, m.provenance());
    }

    /** The fixture with its NUMBER span s2 as a DATE span valued {@code "--10-02"}, and these layers. */
    private static AnnotationDocument dated(Layers layers) {
        AnnotationDocument d = document(layers);
        List<Span> spans = d.spans().stream().map(s -> s.id().equals("s2") ? new Span(s.id(), s.range(),
                Span.Concept.DATE, json("\"--10-02\""), List.of(), s.provenance()) : s).toList();
        return new AnnotationDocument(d.schemaVersion(), d.ruleset(), d.text(), d.context(), d.codeIndexRevision(),
                d.sentences(), d.tokens(), spans, d.relations(), d.checks(), d.coverage(), layers);
    }

    /** A date entry for s2 with two candidate years, one of them correct, under a future tense. */
    private static DateResolution date(String spanId, int month) {
        return new DateResolution(spanId, null, month, 2, "FRIDAY",
                new DateResolution.Tense(DateResolution.Time.FUTURE, "is"), List.of(
                new DateResolution.Candidate(2026, month, 2, Map.of("year", "clock"),
                        DateResolution.Fit.COMPUTED_CORRECT, DateResolution.Time.FUTURE, DateResolution.TenseFit.FITS),
                new DateResolution.Candidate(2025, month, 2, Map.of("year", "boundary"),
                        DateResolution.Fit.COMPUTED_INCORRECT, null, null)),
                DateResolution.State.DISAMBIGUATED, "2026-10-02");
    }

    private static void refused(String what, Layers layers) {
        AnnotationDocument d = document(layers);
        assertThrows(IllegalArgumentException.class, d::validated, what);
    }

    @Test
    void v1DocumentReadsWithoutLayers() {
        AnnotationDocument plain = document(Layers.NONE);
        String json = plain.toJson();
        for (String key : List.of("markers", "clauses", "frames", "coreference")) {
            assertFalse(json.contains("\"" + key + "\""), key);
        }
        AnnotationDocument back = AnnotationDocument.fromJson(json);
        assertEquals(Layers.NONE, back.layers());
        assertEquals(plain, back);
        assertEquals(Layers.NONE, new AnnotationDocument(1, plain.ruleset(), plain.text(), plain.context(), null,
                plain.sentences(), plain.tokens(), plain.spans(), plain.relations(), plain.checks(), plain.coverage())
                .layers(), "the eleven components of version 1 give a document without layers");
        assertNotEquals(json, document(full()).toJson(), "a document with layers writes their keys");
    }

    @Test
    void absentAndEmptyLayersDiffer() {
        String json = document(new Layers(List.of(), null, List.of(), null)).toJson();
        assertTrue(json.endsWith("\"spans\":[]}],\"markers\":[],\"frames\":[]}"), json);
        assertFalse(json.contains("\"clauses\""), json);
        assertFalse(json.contains("\"coreference\""), json);
        Layers back = AnnotationDocument.fromJson(json).layers();
        assertEquals(List.of(), back.markers(), "ran and found nothing");
        assertNull(back.clauses(), "did not run");
        assertEquals(List.of(), back.frames(), "ran and found nothing");
        assertNull(back.coreference(), "did not run");
        assertNotEquals(Layers.NONE, back);
    }

    @Test
    void theDateLayerRoundTripsAndNamesDateSpans() {
        AnnotationDocument d = dated(new Layers(null, null, null, null, List.of(date("s2", 10))));
        assertSame(d, d.validated());
        assertEquals(d, AnnotationDocument.fromJson(d.toJson()));
        assertTrue(d.toJson().endsWith(("'dates':[{'spanId':'s2','year':null,'month':10,'day':2,'weekday':'FRIDAY',"
                + "'tense':{'time':'future','cue':'is'},'candidates':[{'year':2026,'month':10,'day':2,"
                + "'derived':{'year':'clock'},'fit':'computed-correct','time':'future','tense':'fits'},{'year':2025,"
                + "'month':10,'day':2,'derived':{'year':'boundary'},'fit':'computed-incorrect','time':null,'tense':null}],"
                + "'state':'disambiguated','date':'2026-10-02'}]}").replace('\'', '"')), d.toJson());
        assertThrows(IllegalArgumentException.class, () -> dated(new Layers(null, null, null, null,
                List.of(date("s1", 10)))).validated(), "not a DATE span");
        assertThrows(IllegalArgumentException.class, () -> dated(new Layers(null, null, null, null,
                List.of(date("s2", 10), date("s2", 10)))).validated(), "two entries for one span");
        assertThrows(IllegalArgumentException.class,
                () -> dated(new Layers(null, null, null, null, List.of(date("s2", 13)))).validated(), "no month 13");
    }

    @Test
    void theLayoutLayerRoundTripsAndHoldsItsLines() {
        TextRange all = new TextRange(0, TEXT.length());
        Layout layout = new Layout(List.of(new Layout.Line(0, all, 0, Set.of(Layout.LineTag.PART_OF_PARAGRAPH))),
                List.of(new Layout.Block("b1", Layout.Kind.PARAGRAPH, all, 0, 0,
                        json("{\"doubleSpaced\":false}"))));
        AnnotationDocument d = document(new Layers(null, null, null, null, null, layout)).validated();
        assertEquals(d, AnnotationDocument.fromJson(d.toJson()));
        assertTrue(d.toJson().endsWith(("'layout':{'lines':[{'index':0,'range':[0," + TEXT.length() + "],'indent':0,"
                + "'tags':['part-of-paragraph']}],'blocks':[{'id':'b1','kind':'paragraph','range':[0," + TEXT.length()
                + "],'lines':[0,0],'attributes':{'doubleSpaced':false}}]}}").replace('\'', '"')));
        refused("a block over no such line", new Layers(null, null, null, null, null, new Layout(layout.lines(),
                List.of(new Layout.Block("b1", Layout.Kind.PARAGRAPH, all, 0, 1, json("{}"))))));
        refused("a block whose range is not its lines'", new Layers(null, null, null, null, null,
                new Layout(layout.lines(), List.of(new Layout.Block("b1", Layout.Kind.PARAGRAPH, new TextRange(0, 3),
                        0, 0, json("{}"))))));
    }

    @Test
    void aDateEntryMayNameASpanWithADateAlternative() {
        AnnotationDocument d = document(Layers.NONE);
        List<Span> spans = d.spans().stream().map(s -> s.id().equals("s2") ? new Span(s.id(), s.range(),
                Span.Concept.EXPRESSION, json("\"2\""), List.of(json("{\"concept\":\"DATE\",\"value\":\"--10-02\"}")),
                s.provenance()) : s).toList();
        AnnotationDocument dated = new AnnotationDocument(d.schemaVersion(), d.ruleset(), d.text(), d.context(),
                d.codeIndexRevision(), d.sentences(), d.tokens(), spans, d.relations(), d.checks(), d.coverage(),
                new Layers(null, null, null, null, List.of(date("s2", 10))));
        assertSame(dated, dated.validated());
        assertEquals(dated, AnnotationDocument.fromJson(dated.toJson()));
    }

    @Test
    void layersRoundTripExactly() {
        AnnotationDocument d = document(full());
        assertSame(d, d.validated());
        String json = d.toJson();
        assertEquals(d, AnnotationDocument.fromJson(json));
        assertEquals(json, AnnotationDocument.fromJson(json).toJson());
    }

    @Test
    void layerFieldsAreNamedAndOrderedAsTheSchema() {
        String json = document(full()).toJson();
        List<String> pieces = List.of("'coverage':[",
                "'markers':[{'id':'m1','range':[0,2],'tokenIds':['t1'],'class':'CONDITIONAL','kind':null,"
                        + "'scope':{'clause':'c1','antecedent':'c1','consequent':'c2'},'provenance':{'source':'rules',"
                        + "'confidence':1.0,'rule':'marker'}},",
                "'scope':{'tokenIds':['t14','t15'],'notAsserted':['c4']}",
                "'clauses':[{'id':'c1','range':[0,22],'tokenIds':['t1','t2','t3','t4'],'type':'subordinate',"
                        + "'head':'t3','finite':true,'parent':'c2','asserted':'asserted'},",
                "'parent':null,'asserted':'asserted'}",
                "'asserted':'quoted'}],'frames':[",
                "{'clause':'c4','subject':null,'verb':{'head':'t15','tokenIds':['t14','t15']},'object':null,"
                        + "'objectClause':null,'implicit':'you','polarity':'negative'}],",
                "'coreference':[{'tokenIds':['t5'],'antecedent':{'tokenIds':['t2'],'spanId':'s1'}},"
                        + "{'tokenIds':['t10','t11'],'antecedent':null}]}");
        int at = 0;
        for (String piece : pieces) {
            int found = json.indexOf(piece.replace('\'', '"'), at);
            assertTrue(found >= at, piece + " in order in " + json);
            at = found;
        }
        assertTrue(json.endsWith("'antecedent':null}]}".replace('\'', '"')), json);
    }

    @Test
    void anUnknownLayerValueOrAMissingKeyIsRefusedOnRead() {
        String json = document(full()).toJson();
        List<List<String>> swaps = List.of(
                List.of("\"class\":\"NEGATION\"", "\"class\":\"NEGATE\"", "NEGATE"),
                List.of("\"type\":\"main\"", "\"type\":\"primary\"", "primary"),
                List.of("\"asserted\":\"quoted\"", "\"asserted\":\"claimed\"", "claimed"),
                List.of("\"polarity\":\"negative\"", "\"polarity\":\"neutral\"", "neutral"),
                List.of("\"finite\":true,", "", "finite"),
                List.of(",\"polarity\":\"negative\"", "", "polarity"));
        for (List<String> swap : swaps) {
            String bad = json.replace(swap.get(0), swap.get(1));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AnnotationDocument.fromJson(bad), swap.get(2));
            assertTrue(e.getMessage().contains(swap.get(2)), e.getMessage());
        }
    }

    @Test
    void layerIdsAreUnique() {
        Layers l = full();
        Marker m5 = l.markers().get(4);
        refused("two markers m4", withMarker(4, new Marker("m4", m5.range(), m5.tokenIds(), m5.markerClass(),
                m5.kind(), m5.scope(), m5.provenance())));
        Clause c2 = l.clauses().get(1);
        refused("two clauses c1", withClause(1, new Clause("c1", c2.range(), c2.tokenIds(), c2.type(), c2.head(),
                c2.finite(), c2.parent(), c2.asserted())));
    }

    @Test
    void layerRangesLieInTheText() {
        Marker m1 = full().markers().get(0);
        refused("a marker past the text", withMarker(0, new Marker(m1.id(), new TextRange(0, TEXT.length() + 1),
                m1.tokenIds(), m1.markerClass(), m1.kind(), m1.scope(), m1.provenance())));
        Clause c3 = full().clauses().get(2);
        refused("an empty clause range", withClause(2, new Clause(c3.id(), new TextRange(45, 45), c3.tokenIds(),
                c3.type(), c3.head(), c3.finite(), c3.parent(), c3.asserted())));
    }

    @Test
    void layerTokenListsNameExistingTokensInTextOrder() {
        Layers l = full();
        Marker m4 = l.markers().get(3);
        refused("an unknown marker token", withMarker(3, new Marker(m4.id(), m4.range(), List.of("t99"),
                m4.markerClass(), m4.kind(), m4.scope(), m4.provenance())));
        refused("scope tokens out of order", withMarker(2, scoped(l.markers().get(2),
                new Marker.Scope(null, List.of("t15", "t14"), null, null, List.of("c4")))));
        Clause c3 = l.clauses().get(2);
        List<String> swapped = with(with(c3.tokenIds(), 0, "t11"), 1, "t10");
        refused("clause tokens out of order", withClause(2, new Clause(c3.id(), c3.range(), swapped, c3.type(),
                c3.head(), c3.finite(), c3.parent(), c3.asserted())));
        refused("an unknown clause head", withClause(2, new Clause(c3.id(), c3.range(), c3.tokenIds(), c3.type(),
                "t0", c3.finite(), c3.parent(), c3.asserted())));
        Frame f2 = l.frames().get(1);
        refused("an unknown phrase head", withFrame(1, new Frame(f2.clause(), f2.subject(), f2.verb(),
                new Frame.Phrase("t40", List.of("t7", "t8")), null, null, f2.polarity())));
        refused("a phrase without tokens", withFrame(1, new Frame(f2.clause(), new Frame.Phrase("t5", List.of()),
                f2.verb(), f2.object(), null, null, f2.polarity())));
        refused("an unknown antecedent token", withCoreference(0, new Coreference(List.of("t5"),
                new Coreference.Antecedent(List.of("t77"), null))));
        refused("an anaphor without tokens", withCoreference(1, new Coreference(List.of(), null)));
    }

    @Test
    void layerReferencesNameExistingClausesAndSpans() {
        Layers l = full();
        Clause c1 = l.clauses().get(0);
        refused("an unknown parent", withClause(0, new Clause(c1.id(), c1.range(), c1.tokenIds(), c1.type(),
                c1.head(), c1.finite(), "c9", c1.asserted())));
        Frame f3 = l.frames().get(2);
        refused("a frame of an unknown clause", withFrame(2, new Frame("c7", f3.subject(), f3.verb(), f3.object(),
                f3.objectClause(), f3.implicit(), f3.polarity())));
        refused("an unknown object clause", withFrame(2, new Frame(f3.clause(), f3.subject(), f3.verb(),
                f3.object(), "c8", f3.implicit(), f3.polarity())));
        Marker m1 = l.markers().get(0);
        refused("an unknown scope clause", withMarker(0, scoped(m1, new Marker.Scope("c6", null, "c1", "c2",
                null))));
        refused("an unknown antecedent clause", withMarker(0, scoped(m1, new Marker.Scope("c1", null, "c6", "c2",
                null))));
        refused("an unknown consequent clause", withMarker(0, scoped(m1, new Marker.Scope("c1", null, "c1", "c6",
                null))));
        refused("an unknown clause not asserted", withMarker(1, scoped(l.markers().get(1),
                new Marker.Scope("c3", null, null, null, List.of("c6")))));
        refused("an unknown antecedent span", withCoreference(0, new Coreference(List.of("t5"),
                new Coreference.Antecedent(List.of("t2"), "s9"))));
        refused("frames that name clauses, without the clause layer", new Layers(null, null, l.frames(), null));
        AnnotationDocument alone = document(new Layers(null, null, null, l.coreference()));
        assertSame(alone, alone.validated(), "coreference needs no other layer");
    }

    @Test
    void anAntecedentComesBeforeItsAnaphor() {
        refused("an antecedent after its anaphor", withCoreference(0, new Coreference(List.of("t2"),
                new Coreference.Antecedent(List.of("t5"), null))));
        refused("an antecedent that is its anaphor", withCoreference(0, new Coreference(List.of("t5"),
                new Coreference.Antecedent(List.of("t5"), null))));
        AnnotationDocument d = document(withCoreference(0, new Coreference(List.of("t5"),
                new Coreference.Antecedent(List.of("t2"), null))));
        assertSame(d, d.validated());
    }

    @Test
    void withClausesEverySentenceTokenBelongsToOne() {
        Clause c3 = full().clauses().get(2);
        AnnotationDocument shorter = document(withClause(2, new Clause(c3.id(), range(List.of("t10", "t16")),
                List.of("t10", "t11", "t12", "t13", "t14", "t15", "t16"), c3.type(), c3.head(), c3.finite(),
                c3.parent(), c3.asserted())));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, shorter::validated);
        assertTrue(e.getMessage().contains("t17"), e.getMessage());
        refused("an empty clause layer", new Layers(null, List.of(), null, null));
        AnnotationDocument none = document(new Layers(null, null, null, List.of()));
        assertSame(none, none.validated());
    }
}
