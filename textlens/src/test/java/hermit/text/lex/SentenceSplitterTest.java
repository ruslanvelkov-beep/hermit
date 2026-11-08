package hermit.text.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Coverage;
import hermit.text.model.Sentence;
import hermit.text.model.Token;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SentenceSplitterTest {

    private static final Tokenizer TOKENIZER = Tokenizer.standard();
    private static final SentenceSplitter SPLITTER = SentenceSplitter.standard();

    /** Each sentence of {@code text} as its surface and its kind, and a question mark when its boundary is uncertain. */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (Sentence s : SPLITTER.split(text, TOKENIZER.tokenize(text))) {
            out.add(text.substring(s.range().start(), s.range().end()) + " @ " + s.kind()
                    + (s.boundary() == Sentence.Boundary.UNCERTAIN ? " ?" : ""));
        }
        return out;
    }

    @Test
    void aTerminatorBeforeACapitalEndsASentence() {
        assertEquals(List.of("The loop ran. @ PROSE", "It stopped at round 7. @ PROSE", "Done! @ PROSE"),
                sentences("The loop ran. It stopped at round 7. Done!"));
    }

    @Test
    void aDigitOrACodeNameAfterATerminatorStartsASentence() {
        assertEquals(List.of("Did it pass? @ PROSE", "3 runs failed! @ PROSE",
                "`RepeatGuard#signature` returns null. @ PROSE"),
                sentences("Did it pass? 3 runs failed! `RepeatGuard#signature` returns null."));
    }

    @Test
    void aLowerCaseWordOnTheSameLineContinuesTheSentence() {
        assertEquals(List.of("The value is 3. then it is 4. @ PROSE"), sentences("The value is 3. then it is 4."));
    }

    @Test
    void versionPeriodDoesNotSplit() {
        assertEquals(List.of("Gradle 8.10 is required. @ PROSE", "The plugin needs 2.21.1 or later. @ PROSE"),
                sentences("Gradle 8.10 is required. The plugin needs 2.21.1 or later."));
    }

    @Test
    void periodsInsidePathsAndMemberRefsDoNotSplit() {
        assertEquals(List.of("Edit src/main/java/hermit/text/CodeFence.java first. @ PROSE",
                "Then check TaskPlan.Verdict in AgentLoop.java:1281 again. @ PROSE"),
                sentences("Edit src/main/java/hermit/text/CodeFence.java first. Then check TaskPlan.Verdict in "
                        + "AgentLoop.java:1281 again."));
    }

    @Test
    void anAbbreviationDoesNotSplit() {
        assertEquals(List.of("Use a lexicon, e.g. the closed classes, for this. @ PROSE", "Then tag. @ PROSE"),
                sentences("Use a lexicon, e.g. the closed classes, for this. Then tag."));
    }

    @Test
    void anAbbreviationBeforeACapitalIsUncertain() {
        assertEquals(List.of("It ports tools, tests etc. The loop follows. @ PROSE ?"),
                sentences("It ports tools, tests etc. The loop follows."));
    }

    @Test
    void noSentenceEndsInsideBrackets() {
        assertEquals(List.of("(See A. Then B.) @ PROSE", "Next step. @ PROSE"), sentences("(See A. Then B.) Next step."));
    }

    @Test
    void noSentenceEndsInsideQuotes() {
        assertEquals(List.of("He wrote \"Done. Next.\" and left. @ PROSE", "Then he waited. @ PROSE"),
                sentences("He wrote \"Done. Next.\" and left. Then he waited."));
    }

    @Test
    void aBracketLeftOpenMakesTheSentenceUncertain() {
        assertEquals(List.of("It ran (see the log. Then it stopped. @ PROSE ?"),
                sentences("It ran (see the log. Then it stopped."));
    }

    @Test
    void aColonEndsASentenceOnlyAtTheEndOfALine() {
        assertEquals(List.of("Three fixes: one and two. @ PROSE", "The plan:\nPort the loop. @ PROSE"),
                sentences("Three fixes: one and two.\nThe plan:\nPort the loop."),
                "a paragraph's lines are one line");
    }

    @Test
    void aWrappedLineContinuesTheSentenceUnlessATerminatorEndsTheLine() {
        assertEquals(List.of("The run ended\nafter round 7, as planned. @ PROSE"),
                sentences("The run ended\nafter round 7, as planned."));
        assertEquals(List.of("The run ended.\nthen the next one began. @ PROSE"),
                sentences("The run ended.\nthen the next one began."),
                "a paragraph's lines are one line");
    }

    @Test
    void listItemIsASentence() {
        assertEquals(List.of("Results: @ PROSE", "- First item @ LIST_ITEM", "- Second item. @ LIST_ITEM",
                "It has two sentences\n  wrapped here @ LIST_ITEM", "After the list. @ PROSE"),
                sentences("Results:\n- First item\n- Second item. It has two sentences\n  wrapped here\n\nAfter the list."));
    }

    @Test
    void aNumberedMarkerDoesNotEndASentence() {
        assertEquals(List.of("1. Port the loop @ LIST_ITEM", "2) Test it. @ LIST_ITEM"),
                sentences("1. Port the loop\n2) Test it."));
    }

    @Test
    void headingsAndTableRowsAreSentencesOfTheirOwn() {
        assertEquals(List.of("## Results @ HEADING", "| Task | Rounds | @ TABLE_ROW", "|---|---| @ TABLE_ROW",
                "| t1. | 3 | @ TABLE_ROW", "Text after. @ PROSE"),
                sentences("## Results\n| Task | Rounds |\n|---|---|\n| t1. | 3 |\nText after."));
    }

    @Test
    void aBlankLineEndsASentence() {
        assertEquals(List.of("A line without a period @ PROSE", "Next paragraph here @ PROSE"),
                sentences("A line without a period\n\nNext paragraph here"));
    }

    @Test
    void aTitleTheLayoutIsSureOfIsAHeading() {
        assertEquals(List.of("Pick 2-5 Objects @ HEADING", "The steps below pick between 2-5 objects. @ PROSE"),
                sentences("Pick 2-5 Objects\n\nThe steps below pick between 2-5 objects."));
        assertEquals(List.of("Notes on the run @ PROSE", "It ended. @ PROSE"),
                sentences("Notes on the run\n\nIt ended."), "an unsure title is prose");
    }

    @Test
    void codeFenceIsOneSentence() {
        assertEquals(List.of("Before. @ PROSE", "```java\nint x = 1. y;\nreturn x;\n``` @ CODE", "After. @ PROSE"),
                sentences("Before.\n```java\nint x = 1. y;\nreturn x;\n```\nAfter."));
    }

    @Test
    void aFenceNeverClosedRunsToTheEndAndIsUncertain() {
        assertEquals(List.of("Text. @ PROSE", "```\ncode here. More @ CODE ?"), sentences("Text.\n```\ncode here. More"));
    }

    @Test
    void everyTokenLiesInExactlyOneSentenceAndTheDocumentIsValid() {
        String text = "## Plan\nPort the loop. Then test it.\n- First, e.g. the tokenizer\n| a | b |\n```\nx.y();\n```\nEnd";
        List<Token> tokens = TOKENIZER.tokenize(text);
        List<Sentence> sentences = SPLITTER.split(text, tokens);
        List<String> ids = new ArrayList<>();
        for (int k = 0; k < sentences.size(); k++) {
            assertEquals("s" + (k + 1), sentences.get(k).id());
            ids.addAll(sentences.get(k).tokenIds());
        }
        assertEquals(tokens.stream().map(Token::id).toList(), ids);
        List<Coverage> coverage = tokens.stream().map(t -> new Coverage(t.id(), List.of())).toList();
        AnnotationDocument document = new AnnotationDocument(AnnotationDocument.SCHEMA_VERSION, "a1", text,
                new AnnotationDocument.Context(Instant.parse("2026-10-28T00:00:00Z"), ZoneId.of("UTC"), "en"), null,
                sentences, tokens, List.of(), List.of(), List.of(), coverage);
        assertEquals(document, document.validated());
    }

    @Test
    void aBlankTextHasNoSentences() {
        assertTrue(SPLITTER.split("", List.of()).isEmpty());
        assertTrue(sentences(" \n\n ").isEmpty());
    }

    @Test
    void aSentenceEndsWhereItsSpaceIsMissing() {
        assertEquals(List.of("The build passed. @ PROSE", "Then it stopped. @ PROSE"),
                sentences("The build passed.Then it stopped."));
    }
}
