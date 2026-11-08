package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Provenance;
import hermit.text.model.Span;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumberRecognizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T20:00:00Z"), ZoneId.of("UTC"), "en");

    private static List<Span> spans(String text) {
        return new NumberRecognizer().recognize(ConceptRecognizers.input(text, CONTEXT));
    }

    /** Each span as {@code CONCEPT surface = value}. */
    private static List<String> found(String text) {
        return spans(text).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end()) + " = " + s.value())
                .toList();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void decimalOfWritesTheNumberOut() {
        assertEquals("3500", NumberRecognizer.decimalOf("3,500"));
        assertEquals("78000", NumberRecognizer.decimalOf("78k"));
        assertEquals("1500", NumberRecognizer.decimalOf("1.5K"));
        assertEquals("27000000000", NumberRecognizer.decimalOf("27B"));
        assertEquals("2000000", NumberRecognizer.decimalOf("2M"));
        assertEquals("0.000000001", NumberRecognizer.decimalOf("1e-9"));
        assertEquals("-2.5", NumberRecognizer.decimalOf("-2.50"));
        assertEquals("7", NumberRecognizer.decimalOf("+7"));
        assertNull(NumberRecognizer.decimalOf("seven"));
    }

    @Test
    void numberWordsNormalize() {
        assertEquals("3", NumberRecognizer.wordsValue(List.of("three")));
        assertEquals("21", NumberRecognizer.wordsValue(List.of("Twenty-one")));
        assertEquals("205", NumberRecognizer.wordsValue(List.of("two", "hundred", "and", "five")));
        assertEquals("3012", NumberRecognizer.wordsValue(List.of("three", "thousand", "twelve")));
        assertEquals("3000", NumberRecognizer.wordsValue(List.of("3", "thousand")));
        assertEquals("1500000", NumberRecognizer.wordsValue(List.of("one", "million", "five", "hundred", "thousand")));
        assertNull(NumberRecognizer.wordsValue(List.of("three", "four")));
        assertNull(NumberRecognizer.wordsValue(List.of("hundred")));
        assertNull(NumberRecognizer.wordsValue(List.of("two", "and")));
        assertNull(NumberRecognizer.wordsValue(List.of()));
    }

    @Test
    void numbersInTextAreValuedAndInOrder() {
        assertEquals(List.of("NUMBER 3,500 = \"3500\"", "NUMBER 78k = \"78000\"", "NUMBER twenty-one = \"21\""),
                found("We read 3,500 lines in 78k tokens over twenty-one runs."));
        List<Span> spans = spans("Keep 12 of them.");
        assertEquals("n1", spans.get(0).id());
        assertEquals(Provenance.rule("number"), spans.get(0).provenance());
    }

    @Test
    void wordRunsAreOneNumber() {
        List<Span> spans = spans("It took two hundred and five steps.");
        assertEquals(1, spans.size());
        assertEquals(json("\"205\""), spans.get(0).value());
        assertEquals(Provenance.rule("number-word"), spans.get(0).provenance());
        assertEquals(List.of("NUMBER three thousand = \"3000\""), found("Add three thousand rows."));
    }

    @Test
    void oneCountsOnlyBeforeACountedWord() {
        assertEquals(List.of("NUMBER one = \"1\""), found("Only one test failed."));
        assertEquals(List.of(), found("Pick the one you like."));
    }

    @Test
    void aPlainDecimalKeepsTheVersionReading() {
        Span s = spans("The score was 0.95 here.").get(0);
        assertEquals(json("\"0.95\""), s.value());
        assertEquals(List.of(json("{\"concept\": \"VERSION\", \"value\": \"0.95\"}")), s.alternatives());
        assertEquals(List.of(), spans("We saw 3,500.5 there.").get(0).alternatives());
    }

    @Test
    void percentagesTakeTheirSign() {
        assertEquals(List.of("PERCENT 98.6% = \"98.6\"", "PERCENT 40 percent = \"40\"",
                "PERCENT five per cent = \"5\""),
                found("It agreed on 98.6% of tokens, 40 percent of rows and five per cent of files."));
        assertEquals(List.of("NUMBER 30 = \"30\""), found("Use 30 % less."), "the sign must touch the number");
    }

    @Test
    void dataSizesAreQuantities() {
        List<Span> spans = spans("The model takes 12 GB and the cache 512 MiB.");
        assertEquals(2, spans.size());
        assertEquals(Span.Concept.QUANTITY, spans.get(0).concept());
        assertEquals(json("{\"amount\": \"12\", \"unit\": \"GB\"}"), spans.get(0).value());
        assertEquals(json("{\"amount\": \"512\", \"unit\": \"MiB\"}"), spans.get(1).value());
        assertEquals(Provenance.rule("quantity"), spans.get(1).provenance());
    }

    @Test
    void moneyIsAQuantityWithItsCurrencyCode() {
        List<Span> spans = spans("It cost $9.99 and then 5.50 EUR, or 20 dollars.");
        assertEquals(List.of(json("{\"amount\": \"9.99\", \"unit\": \"USD\"}"),
                json("{\"amount\": \"5.5\", \"unit\": \"EUR\"}"), json("{\"amount\": \"20\", \"unit\": \"USD\"}")),
                spans.stream().map(Span::value).toList());
        String text = "It cost $9.99 today.";
        Span dollars = spans(text).get(0);
        assertEquals("$9.99", text.substring(dollars.range().start(), dollars.range().end()));
    }

    @Test
    void numbersInsideCodeNamesAreNotNumbers() {
        assertEquals(List.of(), found("See gpt-oss-20b and x86 in Qwen3.8-27B."));
    }

    @Test
    void aCommitHashOfDigitsIsNoNumber() {
        assertEquals(List.of(), found("Committed: t119 (7375134)."));
        assertEquals(List.of("NUMBER 7375134 = \"7375134\""), found("The table holds 7375134 rows."));
    }

    @Test
    void oneCountsBeforeOfOrAName() {
        assertEquals(List.of("NUMBER one = \"1\"", "NUMBER two = \"2\""), found("It is fixed in one of two ways."));
        assertEquals(List.of("NUMBER One = \"1\""), found("One of them failed."));
        assertEquals(List.of("NUMBER one = \"1\""), found("Since then one insertMember item holds both."));
        assertEquals(List.of(), found("Pick the one that passed."));
    }

    @Test
    void listAndHeadingNumbersAreLabels() {
        assertEquals(List.of(), found("2. Shelve the returned books."));
        assertEquals(List.of(), found("## 11. Open questions"));
        assertEquals(List.of("NUMBER 4 = \"4\""), found("1) Count 4 boxes."));
        ConceptRecognizer.Input input = ConceptRecognizers.input("3. Stack 12 crates.", CONTEXT);
        assertTrue(NumberRecognizer.labelNumber(input, 0));
        assertFalse(NumberRecognizer.labelNumber(input, 3), "a count");
        assertFalse(NumberRecognizer.labelNumber(input, 2), "a word");
    }

    @Test
    void numbersAfterLabelWordsAreLabels() {
        assertEquals(List.of(), found("Sections 2 and 5 describe the shelves; step 7 waits."));
        assertEquals(List.of("NUMBER 6 = \"6\""), found("Steps 3-4 take 6 minutes."));
        assertEquals(List.of("NUMBER 3 = \"3\""), found("Read 3 sections."));
    }

    @Test
    void aTableRowsNumberCellIsALabel() {
        assertEquals(List.of("NUMBER 9 = \"9\""), found("| 014 | Restock the shelf | 9 |"));
        assertEquals(List.of("NUMBER 14 = \"14\""), found("| Shelf | 14 |"));
    }
}
