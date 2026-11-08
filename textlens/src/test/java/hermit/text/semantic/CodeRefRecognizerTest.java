package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Span;
import hermit.text.model.Token.Shape;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeRefRecognizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Span> spans(String text) {
        return new CodeRefRecognizer().recognize(ConceptRecognizers.input(text, null));
    }

    /** The span whose text is {@code surface}. */
    private static Span span(String text, String surface) {
        return spans(text).stream()
                .filter(s -> text.substring(s.range().start(), s.range().end()).equals(surface)).findFirst()
                .orElseThrow();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void kindOfFollowsTheShape() {
        assertEquals("file", CodeRefRecognizer.kindOf(Shape.PATH, "src/main/X.java"));
        assertEquals("line", CodeRefRecognizer.kindOf(Shape.FILE_LINE, "X.java:4"));
        assertEquals("hash", CodeRefRecognizer.kindOf(Shape.HASH, "d4016e8"));
        assertEquals("value", CodeRefRecognizer.kindOf(Shape.URL, "https://example.org/a"));
        assertEquals("member", CodeRefRecognizer.kindOf(Shape.MEMBER_REF, "RepeatGuard#signature"));
        assertEquals("member", CodeRefRecognizer.kindOf(Shape.MEMBER_REF, "Tokenizer.standard()"));
        assertEquals("member", CodeRefRecognizer.kindOf(Shape.MEMBER_REF, "TaskPlan.verdict"));
        assertEquals("type", CodeRefRecognizer.kindOf(Shape.MEMBER_REF, "TaskPlan.Verdict"));
        assertEquals("task", CodeRefRecognizer.kindOf(Shape.IDENT, "ta2r2"));
        assertEquals("constant", CodeRefRecognizer.kindOf(Shape.IDENT, "CHARS_PER_TOKEN"));
        assertEquals("type", CodeRefRecognizer.kindOf(Shape.IDENT, "RepeatGuard"));
        assertEquals("identifier", CodeRefRecognizer.kindOf(Shape.IDENT, "processRequest"));
        assertEquals("value", CodeRefRecognizer.kindOf(Shape.WORD, "continue"));
        assertEquals("value", CodeRefRecognizer.kindOf(null, "a b"));
    }

    @Test
    void aFileLineWithAColumnKeepsTheColumn() {
        String text = "The parser stops at Reader.kt:14:9.";
        assertEquals(json("{\"kind\": \"line\", \"name\": \"Reader.kt\", \"line\": 14, \"column\": 9}"),
                span(text, "Reader.kt:14:9").value());
    }

    @Test
    void fileLineIsACodeRef() {
        String text = "It fails at AgentLoop.java:1281 and in src/X.java:44-60 too.";
        Span one = span(text, "AgentLoop.java:1281");
        assertEquals(Span.Concept.CODE_REF, one.concept());
        assertEquals(json("{\"kind\": \"line\", \"name\": \"AgentLoop.java\", \"line\": 1281}"), one.value());
        assertEquals(json("{\"kind\": \"line\", \"name\": \"src/X.java\", \"line\": 44, \"endLine\": 60}"),
                span(text, "src/X.java:44-60").value());
        assertEquals("code-ref", one.provenance().rule());
    }

    @Test
    void codeShapedTokensAreCodeRefs() {
        String text = "Call RepeatGuard#signature in src/main/Tokenizer.java after d4016e8 for t154 and MAX_DEPTH.";
        assertEquals(List.of(json("{\"kind\": \"member\", \"name\": \"RepeatGuard#signature\"}"),
                json("{\"kind\": \"file\", \"name\": \"src/main/Tokenizer.java\"}"),
                json("{\"kind\": \"hash\", \"name\": \"d4016e8\"}"), json("{\"kind\": \"task\", \"name\": \"t154\"}"),
                json("{\"kind\": \"constant\", \"name\": \"MAX_DEPTH\"}")),
                spans(text).stream().map(Span::value).toList());
        assertEquals("k1", spans(text).get(0).id());
    }

    @Test
    void flagsAreFlags() {
        String text = "Run it with --offline and -Dhermit.trace=true.";
        Span offline = span(text, "--offline");
        assertEquals(Span.Concept.FLAG, offline.concept());
        assertEquals(json("\"--offline\""), offline.value());
        assertEquals(json("\"-Dhermit.trace=true\""), span(text, "-Dhermit.trace=true").value());
        assertEquals("flag", offline.provenance().rule());
    }

    @Test
    void aCodeSpanWithSpacesIsACommand() {
        String text = "Then run `./gradlew test --offline` once.";
        Span s = span(text, "`./gradlew test --offline`");
        assertEquals(Span.Concept.COMMAND, s.concept());
        assertEquals(json("\"./gradlew test --offline\""), s.value());
        assertEquals("command", s.provenance().rule());
        assertEquals(1, spans(text).size(), "the flag inside the command is not a span of its own");
    }

    @Test
    void aCodeSpanWithoutSpacesIsReadByItsShape() {
        String text = "Use `TreeEdit`, `--quiet`, `Spans#over` and `continue` here.";
        assertEquals(json("{\"kind\": \"type\", \"name\": \"TreeEdit\"}"), span(text, "`TreeEdit`").value());
        assertEquals(Span.Concept.FLAG, span(text, "`--quiet`").concept());
        assertEquals(json("\"--quiet\""), span(text, "`--quiet`").value());
        assertEquals(json("{\"kind\": \"member\", \"name\": \"Spans#over\"}"), span(text, "`Spans#over`").value());
        assertEquals(json("{\"kind\": \"value\", \"name\": \"continue\"}"), span(text, "`continue`").value());
    }

    @Test
    void ordinaryWordsAreNoCode() {
        assertEquals(List.of(), spans("The tests pass on this branch."));
    }

    @Test
    void aCommitHashOfDigitsOnlyNeedsCommitWording() {
        String text = "Committed: t119 (7375134).";
        Span hash = span(text, "7375134");
        assertEquals(Span.Concept.CODE_REF, hash.concept());
        assertEquals(json("{\"kind\": \"hash\", \"name\": \"7375134\"}"), hash.value());
        assertEquals("code-ref", hash.provenance().rule());
        assertEquals(List.of(), spans("The table holds 7375134 rows."));
        assertEquals(List.of(), spans("The commit added 123456 rows."), "six digits are a number");
    }

    @Test
    void digitHashLooksAtItsOwnSentenceOnly() {
        ConceptRecognizer.Input input =
                ConceptRecognizers.input("See 2607411, the hash of the fix. Then 7375134 rows went in.", null);
        assertEquals("2607411", Spans.surface(input, 1, 1));
        assertEquals("7375134", Spans.surface(input, 10, 10));
        assertTrue(CodeRefRecognizer.digitHash(input, 1));
        assertFalse(CodeRefRecognizer.digitHash(input, 10));
        assertFalse(CodeRefRecognizer.digitHash(input, 4), "only a NUMBER token");
    }

    @Test
    void possessiveStemCutsTheEnding() {
        assertEquals("TreeEdit", CodeRefRecognizer.possessiveStem("TreeEdit's"));
        assertEquals("Hermit", CodeRefRecognizer.possessiveStem("Hermit\u2019s"));
        assertNull(CodeRefRecognizer.possessiveStem("TreeEdit"));
        assertNull(CodeRefRecognizer.possessiveStem("its"));
        assertNull(CodeRefRecognizer.possessiveStem("'s"));
    }

    @Test
    void aCodeNameWithAPossessiveIsACodeRef() {
        String text = "TreeEdit's ops and the port's notes.";
        Span name = span(text, "TreeEdit");
        assertEquals(Span.Concept.CODE_REF, name.concept());
        assertEquals(json("{\"kind\": \"type\", \"name\": \"TreeEdit\"}"), name.value());
        assertEquals(1, spans(text).size(), "a word with a possessive ending is no code");
    }

    @Test
    void aCodeSpanIsACommandOnlyWhenTheTaggerReadsOne() {
        String text = "Run `bash -n` on `parseStatement(Cursor, Node)` and `a.graph \u2192 a.sym`.";
        assertEquals(Span.Concept.COMMAND, span(text, "`bash -n`").concept());
        assertEquals(json("{\"kind\": \"member\", \"name\": \"parseStatement(Cursor, Node)\"}"),
                span(text, "`parseStatement(Cursor, Node)`").value());
        assertEquals(Span.Concept.CODE_REF, span(text, "`a.graph \u2192 a.sym`").concept());
    }

    @Test
    void codeSpanKindReadsTheText() {
        assertEquals("syntax", CodeRefRecognizer.codeSpanKind("=="));
        assertEquals("syntax", CodeRefRecognizer.codeSpanKind("&&"));
        assertEquals("syntax", CodeRefRecognizer.codeSpanKind("static"));
        assertEquals("value", CodeRefRecognizer.codeSpanKind("continue"));
        assertEquals("file", CodeRefRecognizer.codeSpanKind("runs/t088a"));
        assertEquals("value", CodeRefRecognizer.codeSpanKind("3/4/5"), "a slash without a letter is no file");
        assertEquals("value", CodeRefRecognizer.codeSpanKind("\"draft\""));
        assertEquals("member", CodeRefRecognizer.codeSpanKind("evict()"));
        assertEquals("member", CodeRefRecognizer.codeSpanKind("shelf.restock(3)"));
        assertEquals("type", CodeRefRecognizer.codeSpanKind("Crate(size, label)"));
        assertEquals("type", CodeRefRecognizer.codeSpanKind("@Disabled"));
        assertEquals("type", CodeRefRecognizer.codeSpanKind("List<Crate>"));
        assertEquals("member", CodeRefRecognizer.codeSpanKind("#restock"));
        assertEquals("constant", CodeRefRecognizer.codeSpanKind("#MAX_ITEMS"));
        assertEquals("member", CodeRefRecognizer.codeSpanKind("Shelf#add"));
    }

    @Test
    void codeSpanKindReadsNamesPathsAndEndpoints() {
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("GET /v1/items"));
        assertEquals("value", CodeRefRecognizer.codeSpanKind("GET /v1/items 200 4ms"));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("/items?shelf="));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("a \u2192 b"));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("<name>"));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind(".shelfCount"));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("store.retryLimit"));
        assertEquals("package", CodeRefRecognizer.codeSpanKind("store.cache"));
        assertEquals("package", CodeRefRecognizer.codeSpanKind("java.util.*"));
        assertEquals("host", CodeRefRecognizer.codeSpanKind("api.example.com"));
        assertEquals("package", CodeRefRecognizer.codeSpanKind("org.example"));
        assertEquals("file", CodeRefRecognizer.codeSpanKind("drafts/today/"));
        assertEquals("type", CodeRefRecognizer.codeSpanKind("Crate"));
        assertEquals("identifier", CodeRefRecognizer.codeSpanKind("restock"));
        assertEquals("constant", CodeRefRecognizer.codeSpanKind("MAX_ITEMS"));
        assertEquals("value", CodeRefRecognizer.codeSpanKind("a b c"));
    }

    @Test
    void isHostReadsAHostAndAPortOnly() {
        assertTrue(CodeRefRecognizer.isHost("example.org"));
        assertTrue(CodeRefRecognizer.isHost("api.example.com:443"));
        assertTrue(CodeRefRecognizer.isHost("localhost"));
        assertTrue(CodeRefRecognizer.isHost("localhost:8080"));
        assertTrue(CodeRefRecognizer.isHost("127.0.0.1"));
        assertFalse(CodeRefRecognizer.isHost("org.example"));
        assertFalse(CodeRefRecognizer.isHost("example.org/feed"));
        assertFalse(CodeRefRecognizer.isHost("https://example.org"));
        assertFalse(CodeRefRecognizer.isHost("ws://127.0.0.1:3001"));
        assertFalse(CodeRefRecognizer.isHost("example.org:http"));
        assertEquals("host", CodeRefRecognizer.kindOf(Shape.URL, "example.org:8443"));
        assertEquals("value", CodeRefRecognizer.kindOf(Shape.URL, "example.org/feed"));
    }

    @Test
    void hostsAreOfTheKindHostInProseAndCodeSpans() {
        String text = "Point it at example.org or 127.0.0.1, then at `localhost:8080` and `api.example.com`; "
                + "https://example.org/feed and `hermit.text` are no hosts.";
        assertEquals(List.of(json("{\"kind\": \"host\", \"name\": \"example.org\"}"),
                json("{\"kind\": \"host\", \"name\": \"127.0.0.1\"}"),
                json("{\"kind\": \"host\", \"name\": \"localhost:8080\"}"),
                json("{\"kind\": \"host\", \"name\": \"api.example.com\"}"),
                json("{\"kind\": \"value\", \"name\": \"https://example.org/feed\"}"),
                json("{\"kind\": \"package\", \"name\": \"hermit.text\"}")),
                spans(text).stream().map(Span::value).toList());
    }

    @Test
    void kindOfReadsWorkLabelsAndSlashLists() {
        assertEquals("task", CodeRefRecognizer.kindOf(Shape.IDENT, "L1"));
        assertEquals("task", CodeRefRecognizer.kindOf(Shape.IDENT, "QA-12"));
        assertEquals("task", CodeRefRecognizer.kindOf(Shape.IDENT, "042b"));
        assertEquals("constant", CodeRefRecognizer.kindOf(Shape.IDENT, "MAX_ITEMS"));
        assertEquals("value", CodeRefRecognizer.kindOf(Shape.PATH, "null/0/false"));
        assertEquals("syntax", CodeRefRecognizer.kindOf(Shape.PATH, "class/interface/enum/record"));
        assertEquals("file", CodeRefRecognizer.kindOf(Shape.PATH, "docs/shelf.md"));
    }

    @Test
    void literalsInProseAreValues() {
        String text = "Returns true if the shelf is full; null stays null. False when empty. This is true.";
        assertEquals(List.of(json("{\"kind\": \"value\", \"name\": \"true\"}"),
                json("{\"kind\": \"value\", \"name\": \"null\"}"), json("{\"kind\": \"value\", \"name\": \"null\"}"),
                json("{\"kind\": \"value\", \"name\": \"false\"}")), spans(text).stream().map(Span::value).toList());
        assertEquals("False", text.substring(spans(text).get(3).range().start(), spans(text).get(3).range().end()));
    }

    @Test
    void capitalsAreCodeOnlyInCodeLikePlaces() {
        String text = "It returns CONTINUE, throws an NPE, reads OPEN/CLOSED, and prints \"READY\" by GET; "
                + "it is FIRST in line.";
        assertEquals(List.of(json("{\"kind\": \"constant\", \"name\": \"CONTINUE\"}"),
                json("{\"kind\": \"type\", \"name\": \"NPE\"}"), json("{\"kind\": \"constant\", \"name\": \"OPEN\"}"),
                json("{\"kind\": \"constant\", \"name\": \"CLOSED\"}"),
                json("{\"kind\": \"value\", \"name\": \"READY\"}"),
                json("{\"kind\": \"constant\", \"name\": \"GET\"}")), spans(text).stream().map(Span::value).toList());
    }

    @Test
    void javadocMarkupNamesCode() {
        String text = "@param shelf the shelf to fill; reads {@code limit} first.";
        assertEquals(List.of(json("{\"kind\": \"identifier\", \"name\": \"shelf\"}"),
                json("{\"kind\": \"identifier\", \"name\": \"limit\"}")),
                spans(text).stream().map(Span::value).toList());
    }

    @Test
    void commandsOutsideCodeSpans() {
        String text = "Then run 'git add docs/shelf.md' and see git log for 'sed -n'; the git history stays.";
        assertEquals(List.of(json("\"git add docs/shelf.md\""), json("\"git log\""), json("\"sed -n\"")),
                spans(text).stream().filter(s -> s.concept() == Span.Concept.COMMAND).map(Span::value).toList());
        assertEquals("command", span(text, "git log").provenance().rule());
        assertEquals(Span.Concept.FLAG, span(text, "-n").concept(), "the flag inside keeps its span");
        ConceptRecognizer.Input input = ConceptRecognizers.input("See git log now.", null);
        assertEquals(2, CodeRefRecognizer.commandEnd(input, 1));
        assertEquals(-1, CodeRefRecognizer.commandEnd(input, 2));
    }

    @Test
    void wordsWithADigitOrACapitalAreNoCode() {
        assertEquals(List.of(), spans("An L2-normalized vector, a 404-ing link."));
    }

    @Test
    void metaVariablesAndJdkTypesInProse() {
        String text = "For each type T and argument k, a String comes back. String values pass.";
        assertEquals(List.of(json("{\"kind\": \"identifier\", \"name\": \"T\"}"),
                json("{\"kind\": \"identifier\", \"name\": \"k\"}"),
                json("{\"kind\": \"type\", \"name\": \"String\"}")), spans(text).stream().map(Span::value).toList());
    }
}
