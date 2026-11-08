package hermit.text.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.Token;
import hermit.text.pos.PosTagger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The layout rules (the owner, 2026-11-06): the lines' tags and the blocks of texts from documents of each kind. */
class LayoutAnalyzerTest {

    private static final LayoutAnalyzer ANALYZER = LayoutAnalyzer.standard();

    /** Each line of {@code text} as its tags in their order, joined by {@code +}, then its text. */
    private static List<String> lines(String text) {
        Layout layout = ANALYZER.analyze(text);
        List<String> out = new ArrayList<>();
        for (Layout.Line line : layout.lines()) {
            out.add(line.tags().stream().map(Enum::name).sorted().collect(Collectors.joining("+")) + " | "
                    + line.range().surfaceIn(text));
        }
        return out;
    }

    /** The blocks of {@code text} of the kinds given, as the kind, the first and last line and the attributes. */
    private static List<String> blocks(String text, Layout.Kind... kinds) {
        Set<Layout.Kind> wanted = Set.of(kinds);
        return ANALYZER.analyze(text).blocks().stream().filter(block -> wanted.contains(block.kind()))
                .map(block -> block.kind() + " " + block.firstLine() + "-" + block.lastLine() + " "
                        + block.attributes())
                .toList();
    }

    @Test
    void aTextWithANulOrManyControlCharactersIsBinary() {
        assertTrue(LayoutAnalyzer.binary("PK\u0003\u0004\u0000\u0000"));
        assertTrue(LayoutAnalyzer.binary("ab\u0001\u0002"));
        assertFalse(LayoutAnalyzer.binary("Tabs\tand\r\nform feeds\f are text."));
        assertEquals(List.of("BINARY 0-1 {}"), blocks("a\u0000b\nc", Layout.Kind.values()));
    }

    @Test
    void frontMatterFencesAndCommentsAreContexts() {
        String text = """
                ---
                title: Notes
                ---
                Run it:
                ```bash
                ./gradlew test
                ```
                <!-- a note
                over two lines -->
                ~~~~
                def run():
                """;
        assertEquals(List.of("METADATA 0-2 {}", "CODE 4-6 {\"language\":\"bash\",\"fenced\":true,\"closed\":true}",
                "COMMENT 7-8 {}", "CODE 9-11 {\"language\":\"python\",\"fenced\":true,\"closed\":false}"),
                blocks(text, Layout.Kind.METADATA, Layout.Kind.CODE, Layout.Kind.COMMENT));
    }

    @Test
    void aDocumentOfCodeIsCodeAsAWhole() {
        String text = """
                RFC-1215 DEFINITIONS ::= BEGIN

                -- This module is empty; it lets other modules
                -- import the TRAP-TYPE macro.

                TRAP-TYPE MACRO ::=
                BEGIN
                END
                """;
        assertEquals(List.of("CODE 0-8 {\"language\":\"asn1\",\"fenced\":false,\"whole\":true}"),
                blocks(text, Layout.Kind.values()));
        assertTrue(lines(text).stream().allMatch(line -> line.startsWith("CODE |")));
    }

    @Test
    void aLiteralBlockARunOfCodeAndAnIndentedBlockAreCode() {
        String literal = """
                Install it with::

                    pip install propcache

                Then import it.
                """;
        assertEquals(List.of("CODE 2-2 {\"language\":\"shell\",\"fenced\":false,\"literal\":true}"),
                blocks(literal, Layout.Kind.CODE));
        String run = """
                The loop counts the lines of the file that the reader opened, one by one, until it reaches the end.
                Nothing else in the class reads the file, and nothing writes to it while the loop runs.
                The count it ends with is what the caller gets back.
                A test reads a file of three lines and expects three.

                // count the lines
                int count = 0;
                for (String line : lines) {
                    count++;
                }
                """;
        assertEquals(List.of("CODE 5-9 {\"language\":\"unknown\",\"fenced\":false}"), blocks(run, Layout.Kind.CODE));
        String indented = """
                # Usage

                Call it so:

                    result = client.fetch(url)
                """;
        assertEquals(List.of("CODE 4-4 {\"language\":\"unknown\",\"fenced\":false}"),
                blocks(indented, Layout.Kind.CODE));
    }

    @Test
    void aPageBreakAndAHeaderOfColumnsAreMarkers() {
        String text = "Network Working Group                                         S. Bradner\n"
                + "Request for Comments: 2119                            Harvard University\n"
                + "\n"
                + "   so that they can be followed.\n"
                + "\n"
                + "Bradner                  Best Current Practice                  [Page 1]\n"
                + "\f\n"
                + "RFC 2119                     RFC Key Words                    March 1997\n"
                + "\n"
                + "   The next page starts here.\n";
        assertEquals(List.of("TABLE_ROW 0-0 {\"columns\":true}", "TABLE_ROW 1-1 {\"columns\":true}",
                "PAGE_BREAK 5-5 {}", "PAGE_BREAK 6-6 {}", "PAGE_BREAK 7-7 {}"),
                blocks(text, Layout.Kind.TABLE_ROW, Layout.Kind.PAGE_BREAK));
    }

    @Test
    void headingsRulesTablesQuotesAndBoldLinesAreMarkers() {
        String text = """
                ## Install
                Usage
                =====
                ---
                | a | b |
                > quoted
                **Phase 1 (tokens):**
                """;
        assertEquals(List.of("TITLE 0-0 {\"level\":2,\"certain\":true}", "TITLE 1-1 {\"level\":1,\"certain\":true}",
                "RULE 2-2 {}", "RULE 3-3 {}", "TABLE_ROW 4-4 {}", "QUOTE 5-5 {}", "TITLE 6-6 {\"certain\":true}"),
                blocks(text, Layout.Kind.TITLE, Layout.Kind.RULE, Layout.Kind.TABLE_ROW, Layout.Kind.QUOTE));
    }

    @Test
    void codeLikeLinesAndTheirLanguage() {
        assertTrue(LayoutAnalyzer.codeLike("    return count;"));
        assertTrue(LayoutAnalyzer.codeLike("public static void main(String[] args) {"));
        assertTrue(LayoutAnalyzer.codeLike("ObjectSyntax ::="));
        assertTrue(LayoutAnalyzer.codeLike("if (a == b && c != d) x()"));
        assertFalse(LayoutAnalyzer.codeLike("The loop ends when the list is empty."));
        assertFalse(LayoutAnalyzer.codeLike("   "));
        assertEquals("java", LayoutAnalyzer.guessLanguage(List.of("public class Main {", "System.out.println(1);")));
        assertEquals("python", LayoutAnalyzer.guessLanguage(List.of("import os", "def run():", "    pass")));
        assertEquals("unknown", LayoutAnalyzer.guessLanguage(List.of("plain words")));
    }

    @Test
    void indentationCountsColumns() {
        assertEquals(6, LayoutAnalyzer.indentOf("\t  x"));
        assertEquals(0, LayoutAnalyzer.indentOf("x"));
    }

    @Test
    void romanNumeralsHaveTheirValues() {
        assertEquals(7, LayoutAnalyzer.romanValue("VII"));
        assertEquals(14, LayoutAnalyzer.romanValue("xiv"));
        assertEquals(1994, LayoutAnalyzer.romanValue("MCMXCIV"));
        assertNull(LayoutAnalyzer.romanValue("IIII"));
        assertNull(LayoutAnalyzer.romanValue(""));
    }

    @Test
    void bulletsAndNumbersInSequenceAreListItems() {
        String text = """
                Steps:
                1. Build the tree.
                2. Run the suite.
                   It takes a while.
                3. Commit.

                - one
                - two
                """;
        assertEquals(List.of("LIST_ITEM 1-1 {\"kind\":\"arabic\",\"label\":\"1\",\"number\":1,\"indent\":0}",
                "LIST_ITEM 2-3 {\"kind\":\"arabic\",\"label\":\"2\",\"number\":2,\"indent\":0}",
                "LIST_ITEM 4-4 {\"kind\":\"arabic\",\"label\":\"3\",\"number\":3,\"indent\":0}",
                "LIST_ITEM 6-6 {\"kind\":\"bullet\",\"marker\":\"-\",\"indent\":0}",
                "LIST_ITEM 7-7 {\"kind\":\"bullet\",\"marker\":\"-\",\"indent\":0}"), blocks(text, Layout.Kind.LIST_ITEM));
        assertEquals("LIST_ITEM_TEXT |    It takes a while.", lines(text).get(3));
    }

    @Test
    void aNumberWithoutItsNeighboursIsNoListItem() {
        String text = """
                7. Evidence

                The run took three tries.

                12. Mixed
                14. Numbers
                """;
        assertEquals(List.of(), blocks(text, Layout.Kind.LIST_ITEM));
    }

    @Test
    void subsectionsLettersAndRomanNumeralsContinueTheirLists() {
        String toc = """
                6.  Sending Data
                7.  Closing the Connection
                  7.1.  Definitions
                  7.2.  Abnormal Closures
                8.  Error Handling
                """;
        assertEquals(5, blocks(toc, Layout.Kind.LIST_ITEM).size());
        String letters = """
                a. The work must carry notices.

                b. The work must say it is released.

                c. You must license the whole work.

                d. Each interface must show notices.
                """;
        assertEquals(List.of(1, 2, 3, 4), ANALYZER.analyze(letters).blocks().stream()
                .filter(block -> block.kind() == Layout.Kind.LIST_ITEM)
                .map(block -> block.attributes().get("number").asInt()).toList(), "c and d are letters here");
        String roman = """
                iii. three
                iv. four
                v. five
                """;
        assertEquals(List.of("roman-lower 5"), ANALYZER.analyze(roman).blocks().stream()
                .filter(block -> block.firstLine() == 2)
                .map(block -> block.attributes().get("kind").asText() + " " + block.attributes().get("number"))
                .toList(), "v is a numeral here");
    }

    @Test
    void anItemIndentedOtherwiseStillContinuesItsList() {
        String text = """
                1. Build.
                2. Test.

                  3. Commit.
                """;
        assertEquals("LIST_ITEM 3-3 {\"kind\":\"arabic\",\"label\":\"3\",\"number\":3,\"indent\":2,"
                + "\"indentMismatch\":true}", blocks(text, Layout.Kind.LIST_ITEM).get(2));
    }

    @Test
    void titlesWithACue() {
        String text = """
                Pick 2-5 Objects

                The steps below pick between 2-5
                objects from the list.

                7. Evidence

                The Hearing

                The witness spoke.
                """;
        assertEquals(List.of("TITLE 0-0 {\"cues\":[\"title-case\"]}", "TITLE 5-5 {\"cues\":[\"numbered\",\"title-case\"]}",
                "TITLE 7-7 {\"cues\":[\"title-case\"]}"), blocks(text, Layout.Kind.TITLE));
    }

    @Test
    void anUnsureLineIsBothAndALeadInIsPartOfItsParagraph() {
        String text = """
                Notes on the run

                Run the server with:

                    ./gradlew run
                """;
        assertEquals("PART_OF_PARAGRAPH+TITLE | Notes on the run", lines(text).get(0));
        assertEquals("PART_OF_PARAGRAPH | Run the server with:", lines(text).get(2));
        assertEquals("PART_OF_PARAGRAPH | The loop ran. It stopped at round 7. Done!",
                lines("The loop ran. It stopped at round 7. Done!").get(0), "a sentence ends inside it");
    }

    @Test
    void centredCapitalAndOutdentedTitlesOfPlainText() {
        String text = """
                                            Apache License
                                      Version 2.0, January 2004

                   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

                Abstract

                   In many standards track documents several words are used to signify
                   the requirements in the specification.
                """;
        assertEquals(List.of("TITLE 0-0 {\"cues\":[\"title-case\",\"centred\"]}", "TITLE 1-1 {\"cues\":[\"title-case\",\"centred\"]}",
                "TITLE 3-3 {\"cues\":[\"caps\"]}", "TITLE 5-5 {\"cues\":[\"title-case\",\"outdented\"]}"),
                blocks(text, Layout.Kind.TITLE));
    }

    @Test
    void paragraphsEndAtBlankLinesIndentsAndShortLines() {
        String text = """
                The first paragraph runs over
                two lines.

                    An indented one starts here and goes on to the end of a line that is long enough.
                The second line of it is long as well, as long as the first line was above.
                It ends here.
                A new one starts after a short line, in hard-wrapped text like this one is.
                """;
        assertEquals(List.of("PARAGRAPH 0-1 {\"doubleSpaced\":false}", "PARAGRAPH 3-5 {\"doubleSpaced\":false}",
                "PARAGRAPH 6-6 {\"doubleSpaced\":false}"), blocks(text, Layout.Kind.PARAGRAPH));
    }

    @Test
    void aDoubleSpacedTextKeepsItsParagraphsTogether() {
        String text = """
                Double Spacing

                The layout stage reads a text line by line before any other rule runs. It finds the code,

                the titles and the paragraphs, and hands on a text whose paragraphs have no line breaks.

                A short paragraph ends here.

                    Then a new one starts with an indent that marks it, and it goes on over the line

                until it ends.
                """;
        assertTrue(LayoutAnalyzer.doubleSpaced(text.lines().toList()));
        assertEquals(List.of("PARAGRAPH 2-6 {\"doubleSpaced\":true}", "PARAGRAPH 8-10 {\"doubleSpaced\":true}"),
                blocks(text, Layout.Kind.PARAGRAPH));
    }

    @Test
    void theFlowTextJoinsAParagraphsLinesAndAnItemsText() {
        String text = "The witness testified before the 9/11\r\nCommission on Monday.\r\n\r\n- an item\r\n\tgoes on\r\n";
        Layout layout = ANALYZER.analyze(text);
        String flow = layout.flow(text);
        assertEquals(text.length(), flow.length());
        assertEquals("The witness testified before the 9/11  Commission on Monday.\r\n\r\n- an item   goes on\r\n",
                flow);
    }

    @Test
    void aPageBreakEndsAParagraph() {
        String text = "   to this document.  Code Components extracted from this document must\n"
                + "\n"
                + "Fette & Melnikov             Standards Track                    [Page 1]\n"
                + "\f\n"
                + "RFC 6455                 The WebSocket Protocol            December 2011\n"
                + "\n"
                + "   include Simplified BSD License text as described in Section 4.e of\n";
        assertEquals(List.of("PARAGRAPH 0-0 {\"doubleSpaced\":false}", "PARAGRAPH 6-6 {\"doubleSpaced\":false}"),
                blocks(text, Layout.Kind.PARAGRAPH));
    }

    @Test
    void titleCaseCapitalsWidthAndSpacing() {
        assertTrue(LayoutAnalyzer.titleCase(List.of("Pick", "Objects")));
        assertFalse(LayoutAnalyzer.titleCase(List.of("Status", "of", "this", "Memo")));
        assertTrue(LayoutAnalyzer.titleCase(List.of("Hidden", "Gem", "in", "Alpharetta")));
        assertFalse(LayoutAnalyzer.titleCase(List.of("ABSTRACT")));
        assertTrue(LayoutAnalyzer.allCapitals(List.of("TERMS", "AND", "CONDITIONS")));
        assertFalse(LayoutAnalyzer.allCapitals(List.of("a")));
        assertEquals(5, LayoutAnalyzer.typicalWidth(List.of("12345", "1234567890", "", "123")), "at 90% of 3 lengths");
        assertFalse(LayoutAnalyzer.doubleSpaced(List.of("a", "", "b", "", "c")));
    }

    @Test
    void aLineOrTwoOfCodeNamesIsNoDocumentOfCode() {
        assertEquals(List.of(), blocks("The items: 1. addImport java.util.List. 2. replaceBody of A#b(): return 1.",
                Layout.Kind.CODE), "a document of code has three or more lines");
        assertEquals(List.of(), blocks("Call run() then stop();\nand close() it.", Layout.Kind.CODE));
    }

    @Test
    void afterTaggingAnUnsureLineIsSettledByItsVerbAndItsNextLine() {
        String paragraph = "We Ran Two Tests\nand they passed.\n";
        Layout layout = ANALYZER.analyze(paragraph);
        assertEquals(Set.of(Layout.LineTag.TITLE, Layout.LineTag.PART_OF_PARAGRAPH), layout.lines().get(0).tags());
        Layout settled = LayoutAnalyzer.settle(layout, paragraph, PosTagger.standard().tagText(paragraph));
        assertEquals(Set.of(Layout.LineTag.PART_OF_PARAGRAPH), settled.lines().get(0).tags(),
                "a verb, then lower case");
        assertEquals(List.of("b1 PARAGRAPH 0-1"), settled.blocks().stream()
                .map(block -> block.id() + " " + block.kind() + " " + block.firstLine() + "-" + block.lastLine())
                .toList());
        String title = "Pick Two Objects\nWe sort them by size.\n";
        settled = LayoutAnalyzer.settle(ANALYZER.analyze(title), title, PosTagger.standard().tagText(title));
        assertEquals(Set.of(Layout.LineTag.TITLE), settled.lines().get(0).tags(), "title case, then prose");
        assertEquals(List.of("b1 TITLE 0-0 true", "b2 PARAGRAPH 1-1 false"), settled.blocks().stream()
                .map(block -> block.id() + " " + block.kind() + " " + block.firstLine() + "-" + block.lastLine() + " "
                        + block.attributes().path("settled").asBoolean())
                .toList());
        assertEquals(17, settled.blocks().get(1).range().start());
        String unsure = "The Big Red Dog\nand the small cat.\n";
        Layout kept = ANALYZER.analyze(unsure);
        assertSame(kept, LayoutAnalyzer.settle(kept, unsure, PosTagger.standard().tagText(unsure)),
                "no verb, then lower case: both readings stay");
    }

    @Test
    void aTitlesCapitalsAreReadInLowerCaseUnlessItsProseWritesThem() {
        String text = "The 9/11 Commission Staff\n\nThe 9/11 Commission met.\n";
        Layout layout = ANALYZER.analyze(text);
        String flow = layout.flow(text);
        List<Token> tokens = Tokenizer.standard().tokenize(flow);
        List<Sentence> sentences = SentenceSplitter.standard().split(flow, tokens, layout);
        Set<String> lower = LayoutAnalyzer.lowerCaseReadings(layout, flow, tokens, sentences);
        assertEquals(List.of("0 The", "20 Staff"), tokens.stream().filter(token -> lower.contains(token.id()))
                .map(token -> token.range().start() + " " + token.range().surfaceIn(text)).toList(),
                "Commission is written so in the prose; The only at a sentence's start");
    }
}
