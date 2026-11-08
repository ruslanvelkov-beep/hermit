package hermit.text.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("UTC"), "en");

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Token token(String id, int start, int end, Token.Shape shape, Tag tag, String rule) {
        return new Token(id, new TextRange(start, end), shape, List.of(new Token.Candidate(tag, rule)), false,
                Provenance.rule(rule));
    }

    private static Span span(String id, int start, int end, Span.Concept concept, String value) {
        return new Span(id, new TextRange(start, end), concept, json(value), List.of(), Provenance.rule("recognizer"));
    }

    /** One sentence over the whole text, each token covered by the spans whose range contains it. */
    private static AnnotationDocument document(String text, List<Token> tokens, List<Span> spans,
            List<Relation> relations, List<Check> checks) {
        List<String> ids = tokens.stream().map(Token::id).toList();
        List<Coverage> coverage = tokens.stream().map(t -> new Coverage(t.id(), spans.stream()
                .filter(s -> s.range().start() <= t.range().start() && t.range().end() <= s.range().end())
                .map(Span::id).toList())).toList();
        return new AnnotationDocument(1, "text-1", text, CONTEXT, null, List.of(new Sentence("s1",
                new TextRange(0, text.length()), Sentence.Kind.PROSE, Sentence.Boundary.CERTAIN, ids)), tokens, spans,
                relations, checks, coverage);
    }

    private static AnnotationDocument document(String text, List<Token> tokens) {
        return document(text, tokens, List.of(), List.of(), List.of());
    }

    private static List<String> ids(List<Span> spans) {
        return spans.stream().map(Span::id).toList();
    }

    private static AnnotationDocument minimal() {
        Span version = new Span("v", new TextRange(3, 6), Span.Concept.VERSION,
                json("{\"segments\":[\"1\",\"4\"],\"scheme\":\"dotted\"}"), List.of(json("{\"number\":\"1.4\"}")),
                Provenance.rule("version"));
        return document("Hi 1.4", List.of(token("t1", 0, 2, Token.Shape.WORD, Tag.INTJ, "closed-class"),
                token("t2", 3, 6, Token.Shape.VERSION, Tag.NUM, "shape")), List.of(version), List.of(), List.of());
    }

    /** The design's example sentence, with a model's guess for one token and a span that overlaps another. */
    private static AnnotationDocument sample() {
        String text = "On Mon Oct 20 the 3 runs took 2+2=5 minutes in Hermit 1.4.";
        Token took = new Token("t8", new TextRange(25, 29), Token.Shape.WORD, List.of(
                new Token.Candidate(Tag.VERB, "context-subject-verb"), new Token.Candidate(Tag.NOUN, "lexicon")), true,
                new Provenance(Provenance.Source.MODEL, 0.75, null, "model-a"));
        List<Token> tokens = List.of(token("t1", 0, 2, Token.Shape.WORD, Tag.ADP, "closed-class"),
                token("t2", 3, 6, Token.Shape.WORD, Tag.PROPN, "lexicon"),
                token("t3", 7, 10, Token.Shape.WORD, Tag.PROPN, "lexicon"),
                token("t4", 11, 13, Token.Shape.NUMBER, Tag.NUM, "shape"),
                token("t5", 14, 17, Token.Shape.WORD, Tag.DET, "closed-class"),
                token("t6", 18, 19, Token.Shape.NUMBER, Tag.NUM, "shape"),
                token("t7", 20, 24, Token.Shape.WORD, Tag.NOUN, "context-number-noun"), took,
                token("t9", 30, 31, Token.Shape.NUMBER, Tag.NUM, "shape"),
                token("t10", 31, 32, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t11", 32, 33, Token.Shape.NUMBER, Tag.NUM, "shape"),
                token("t12", 33, 34, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t13", 34, 35, Token.Shape.NUMBER, Tag.NUM, "shape"),
                token("t14", 36, 43, Token.Shape.WORD, Tag.NOUN, "suffix"),
                token("t15", 44, 46, Token.Shape.WORD, Tag.ADP, "closed-class"),
                token("t16", 47, 53, Token.Shape.WORD, Tag.PROPN, "lexicon"),
                token("t17", 54, 57, Token.Shape.VERSION, Tag.NUM, "shape"),
                token("t18", 57, 58, Token.Shape.PUNCT, Tag.PUNCT, "shape"));
        List<Span> spans = List.of(span("d", 3, 13, Span.Concept.DATE,
                        "{\"iso\":\"2026-10-20\",\"statedWeekday\":\"MONDAY\"}"),
                span("w", 3, 6, Span.Concept.WEEKDAY, "\"MONDAY\""),
                span("n", 18, 24, Span.Concept.QUANTITY, "{\"number\":\"3\",\"unit\":\"run\"}"),
                span("e", 30, 35, Span.Concept.EXPRESSION, "{\"op\":\"eq\",\"left\":{\"op\":\"add\",\"args\":"
                        + "[{\"integer\":\"2\"},{\"integer\":\"2\"}]},\"right\":{\"integer\":\"5\"}}"),
                span("q", 34, 43, Span.Concept.QUANTITY, "{\"number\":\"5\",\"unit\":\"minute\"}"),
                span("u", 36, 43, Span.Concept.UNIT, "\"minute\""),
                span("p", 47, 53, Span.Concept.PRODUCT, "\"Hermit\""),
                new Span("v", new TextRange(54, 57), Span.Concept.VERSION,
                        json("{\"segments\":[\"1\",\"4\"],\"scheme\":\"dotted\"}"),
                        List.of(json("{\"number\":\"1.4\"}")), Provenance.rule("version")));
        List<Relation> relations = List.of(new Relation("r1", Relation.Kind.EQUALITY, List.of("e"),
                Provenance.rule("expression")));
        List<Check> checks = List.of(new Check(List.of("d"), "weekday-1", Check.Status.INCONSISTENT,
                        json("{\"computedWeekday\":\"TUESDAY\",\"statedWeekday\":\"MONDAY\"}"),
                        List.of(new Check.Assumption("missingYear", json("2026"), "context.instant@context.zone")),
                        List.of("weekday-mismatch")),
                new Check(List.of("r1"), "exact-expression-1", Check.Status.INCONSISTENT,
                        json("{\"left\":\"4\",\"right\":\"5\"}"), List.of(), List.of()));
        return document(text, tokens, spans, relations, checks);
    }

    @Test
    void utf16RangesRoundTrip() {
        String text = "ship 😀 now";   // an emoji is two UTF-16 units
        AnnotationDocument d = document(text, List.of(token("t1", 0, 4, Token.Shape.WORD, Tag.VERB, "lexicon"),
                token("t2", 5, 7, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t3", 8, 11, Token.Shape.WORD, Tag.ADV, "lexicon")));
        AnnotationDocument back = AnnotationDocument.fromJson(d.toJson());
        assertEquals(text, back.text());
        assertEquals(new TextRange(5, 7), back.tokens().get(1).range());
        assertEquals("😀", back.tokens().get(1).range().surfaceIn(back.text()));
    }

    @Test
    void jsonRoundTripIsExact() {
        AnnotationDocument d = sample();
        String json = d.toJson();
        assertEquals(d, AnnotationDocument.fromJson(json));
        assertEquals(json, AnnotationDocument.fromJson(json).toJson());
    }

    @Test
    void jsonFieldsAreNamedAndOrderedAsTheSchema() {
        String expected = ("{'schemaVersion':1,'ruleset':'text-1','text':'Hi 1.4','context':{'instant':"
                + "'2026-10-22T00:00:00Z','zone':'UTC','locale':'en'},'sentences':[{'id':'s1','range':[0,6],"
                + "'kind':'prose','boundary':'certain','tokens':['t1','t2']}],'tokens':[{'id':'t1','range':[0,2],"
                + "'shape':'WORD','tags':[{'tag':'INTJ','rule':'closed-class'}],'guessed':false,'provenance':"
                + "{'source':'rules','confidence':1.0,'rule':'closed-class'}},{'id':'t2','range':[3,6],"
                + "'shape':'VERSION','tags':[{'tag':'NUM','rule':'shape'}],'guessed':false,'provenance':"
                + "{'source':'rules','confidence':1.0,'rule':'shape'}}],'spans':[{'id':'v','range':[3,6],"
                + "'class':'VERSION','value':{'segments':['1','4'],'scheme':'dotted'},'alternatives':"
                + "[{'number':'1.4'}],'provenance':{'source':'rules','confidence':1.0,'rule':'version'}}],"
                + "'relations':[],'checks':[],'coverage':[{'token':'t1','spans':[]},{'token':'t2','spans':['v']}]}")
                .replace('\'', '"');
        assertEquals(expected, minimal().toJson());
        AnnotationDocument d = minimal();
        AnnotationDocument pinned = new AnnotationDocument(1, d.ruleset(), d.text(), d.context(), "abc1234",
                d.sentences(), d.tokens(), d.spans(), d.relations(), d.checks(), d.coverage());
        assertTrue(pinned.toJson().contains("\"locale\":\"en\"},\"codeIndexRevision\":\"abc1234\",\"sentences\":"),
                pinned.toJson());
    }

    @Test
    void anotherSchemaVersionIsRefused() {
        String json = minimal().toJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AnnotationDocument.fromJson(json));
        assertTrue(e.getMessage().contains("schemaVersion"), e.getMessage());
        AnnotationDocument d = minimal();
        assertThrows(IllegalArgumentException.class, () -> new AnnotationDocument(2, d.ruleset(), d.text(),
                d.context(), null, d.sentences(), d.tokens(), d.spans(), d.relations(), d.checks(), d.coverage())
                .validated());
    }

    @Test
    void everyTokenRetained() {
        AnnotationDocument d = sample();
        List<TextRange> pieces = new ArrayList<>(d.gaps());
        d.tokens().forEach(t -> pieces.add(t.range()));
        pieces.sort(Comparator.comparingInt(TextRange::start));
        StringBuilder rebuilt = new StringBuilder();
        int at = 0;
        for (TextRange r : pieces) {
            assertEquals(at, r.start(), "tokens and gaps tile the text");
            rebuilt.append(r.surfaceIn(d.text()));
            at = r.end();
        }
        assertEquals(d.text(), rebuilt.toString());
        d.gaps().forEach(g -> assertTrue(g.surfaceIn(d.text()).isBlank(), g.toString()));
        // a word left out of the tokens is text between them that is not whitespace
        List<Token> missing = new ArrayList<>(d.tokens());
        missing.remove(6);
        assertThrows(IllegalArgumentException.class, () -> document(d.text(), missing).validated());
    }

    @Test
    void aRangeInsideASurrogatePairIsRefused() {
        String text = "ship 😀 now";
        AnnotationDocument whole = document(text, List.of(token("t1", 0, 4, Token.Shape.WORD, Tag.VERB, "lexicon"),
                token("t2", 5, 7, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t3", 8, 11, Token.Shape.WORD, Tag.ADV, "lexicon")));
        assertSame(whole, whole.validated());
        AnnotationDocument halves = document(text, List.of(token("t1", 0, 4, Token.Shape.WORD, Tag.VERB, "lexicon"),
                token("t2", 5, 6, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t2b", 6, 7, Token.Shape.SYMBOL, Tag.SYM, "shape"),
                token("t3", 8, 11, Token.Shape.WORD, Tag.ADV, "lexicon")));
        assertThrows(IllegalArgumentException.class, halves::validated);
    }

    @Test
    void combiningMarksAreNotNormalized() {
        String text = "café open";   // an e and a combining acute accent: two UTF-16 units, kept as written
        AnnotationDocument d = document(text, List.of(token("t1", 0, 5, Token.Shape.WORD, Tag.NOUN, "lexicon"),
                token("t2", 6, 10, Token.Shape.WORD, Tag.ADJ, "lexicon")));
        AnnotationDocument back = AnnotationDocument.fromJson(d.toJson());
        assertEquals(text, back.text());
        assertEquals("café", back.tokens().get(0).range().surfaceIn(back.text()));
        assertEquals(5, back.tokens().get(0).range().length());
    }

    @Test
    void nestedAndOverlappingSpansAreKept() {
        AnnotationDocument back = AnnotationDocument.fromJson(sample().toJson());
        assertEquals(List.of("d"), ids(back.spansContaining("w")), "the weekday in the date");
        assertEquals(List.of("q"), ids(back.spansContaining("u")), "the unit in the quantity");
        assertEquals(List.of(), ids(back.spansContaining("e")), "an overlap is not nesting");
        assertTrue(back.spans().get(3).range().overlaps(back.spans().get(4).range()), "2+2=5 and 5 minutes share 5");
        assertEquals(List.of("e", "q"), back.coverage().get(12).spanIds());
        assertEquals(List.of(json("{\"number\":\"1.4\"}")), back.spans().get(7).alternatives(), "1.4 as a decimal too");
    }

    @Test
    void tagCandidatesKeepTheirOrderAndRules() {
        Token took = AnnotationDocument.fromJson(sample().toJson()).tokens().get(7);
        assertEquals(List.of(new Token.Candidate(Tag.VERB, "context-subject-verb"),
                new Token.Candidate(Tag.NOUN, "lexicon")), took.candidates());
        assertEquals(Tag.VERB, took.tag());
        assertTrue(took.guessed());
        assertEquals(new Provenance(Provenance.Source.MODEL, 0.75, null, "model-a"), took.provenance());
        assertEquals(Tag.X, new Token("t", new TextRange(0, 1), Token.Shape.WORD, List.of(), true,
                Provenance.rule("none")).tag());
    }

    @Test
    void anInvalidRangeIsRefusedOnRead() {
        String json = minimal().toJson();
        for (String range : List.of("[6,3]", "[3,60]", "[-1,6]")) {
            String bad = json.replace("\"range\":[3,6],\"shape\"", "\"range\":" + range + ",\"shape\"");
            assertThrows(IllegalArgumentException.class, () -> AnnotationDocument.fromJson(bad), range);
        }
    }

    @Test
    void anUnknownEnumOrAMissingKeyIsRefusedOnRead() {
        String json = minimal().toJson();
        List<List<String>> swaps = List.of(List.of("\"tag\":\"INTJ\"", "\"tag\":\"INTERJ\"", "INTERJ"),
                List.of("\"shape\":\"WORD\"", "\"shape\":\"BLOB\"", "BLOB"),
                List.of("\"kind\":\"prose\"", "\"kind\":\"poem\"", "poem"),
                List.of("\"source\":\"rules\"", "\"source\":\"oracle\"", "oracle"),
                List.of("\"class\":\"VERSION\"", "\"class\":\"RELEASE\"", "RELEASE"),
                List.of("\"ruleset\":\"text-1\",", "", "ruleset"));
        for (List<String> swap : swaps) {
            String bad = json.replace(swap.get(0), swap.get(1));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AnnotationDocument.fromJson(bad), swap.get(2));
            assertTrue(e.getMessage().contains(swap.get(2)), e.getMessage());
        }
        String status = sample().toJson().replace("\"status\":\"inconsistent\"", "\"status\":\"maybe\"");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AnnotationDocument.fromJson(status));
        assertTrue(e.getMessage().contains("maybe"), e.getMessage());
    }

    @Test
    void idsMustNameWhatExists() {
        AnnotationDocument d = sample();
        assertSame(d, d.validated());
        Sentence s = d.sentences().get(0);
        List<String> unknown = new ArrayList<>(s.tokenIds());
        unknown.set(0, "t99");
        List<String> swapped = new ArrayList<>(s.tokenIds());
        swapped.set(0, s.tokenIds().get(1));
        swapped.set(1, s.tokenIds().get(0));
        List<Span> twice = new ArrayList<>(d.spans());
        twice.add(twice.get(0));
        List<List<Object>> broken = List.of(
                List.of(List.of(new Sentence("s1", s.range(), s.kind(), s.boundary(), unknown)), d.spans(),
                        d.relations(), d.checks(), d.coverage()),
                List.of(List.of(new Sentence("s1", s.range(), s.kind(), s.boundary(), swapped)), d.spans(),
                        d.relations(), d.checks(), d.coverage()),
                List.of(d.sentences(), d.spans(), List.of(new Relation("r1", Relation.Kind.EQUALITY, List.of("zz"),
                        Provenance.rule("expression"))), d.checks(), d.coverage()),
                List.of(d.sentences(), d.spans(), d.relations(), List.of(new Check(List.of("zz"), "weekday-1",
                        Check.Status.CONSISTENT, json("{}"), List.of(), List.of())), d.coverage()),
                List.of(d.sentences(), d.spans(), d.relations(), d.checks(),
                        d.coverage().subList(0, d.coverage().size() - 1)),
                List.of(d.sentences(), twice, d.relations(), d.checks(), d.coverage()));
        for (List<Object> parts : broken) {
            @SuppressWarnings("unchecked")
            AnnotationDocument bad = new AnnotationDocument(1, d.ruleset(), d.text(), d.context(), null,
                    (List<Sentence>) parts.get(0), d.tokens(), (List<Span>) parts.get(1),
                    (List<Relation>) parts.get(2), (List<Check>) parts.get(3), (List<Coverage>) parts.get(4));
            assertThrows(IllegalArgumentException.class, bad::validated, bad.toString());
        }
    }
}
