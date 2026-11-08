package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Clause;
import hermit.text.model.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A4 of the annotation plan (section 6.3): clauses by the rules of the class javadoc. */
class ClauseSegmenterTest {

    /** Each clause of {@code text} as its id, type and text, and its parent's id after "in". */
    private static List<String> clauses(String text) {
        return ClauseSegmenter.clausesOf(text, null).stream().map(c -> c.id() + " " + c.type() + " ["
                + c.range().surfaceIn(text) + "]" + (c.parent() == null ? "" : " in " + c.parent())).toList();
    }

    /** The clause of {@code text} with the id {@code id}. */
    private static Clause clause(String text, String id) {
        return ClauseSegmenter.clausesOf(text, null).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** The word of the head of clause {@code id} in {@code text}, or null. */
    private static String head(String text, String id) {
        String head = clause(text, id).head();
        return head == null ? null : ConceptRecognizers.input(text, null).tokens().stream()
                .filter(t -> t.id().equals(head)).findFirst().orElseThrow().range().surfaceIn(text);
    }

    @Test
    void aSentenceIsAMainClauseWithItsVerbAsHead() {
        String text = "The build passed.";
        assertEquals(List.of("c1 MAIN [The build passed.]"), clauses(text));
        Clause clause = clause(text, "c1");
        assertEquals("passed", head(text, "c1"));
        assertTrue(clause.finite());
        assertEquals(Clause.Asserted.ASSERTED, clause.asserted());
        assertEquals(List.of("t1", "t2", "t3", "t4"), clause.tokenIds());
    }

    @Test
    void aSentenceWithoutAVerbIsVerbless() {
        String text = "Two red tests and one green test.";
        assertEquals(List.of("c1 VERBLESS [Two red tests and one green test.]"), clauses(text));
        assertNull(clause(text, "c1").head());
        assertFalse(clause(text, "c1").finite());
    }

    @Test
    void aSemicolonCutsACoordinateClause() {
        assertEquals(List.of("c1 MAIN [The build passed;]", "c2 COORDINATE [the tests failed.]"),
                clauses("The build passed; the tests failed."));
    }

    @Test
    void aConjunctionBetweenTwoClausesCuts() {
        assertEquals(List.of("c1 MAIN [The build passed]", "c2 COORDINATE [and the tests failed.]"),
                clauses("The build passed and the tests failed."));
        assertEquals(List.of("c1 MAIN [I reviewed every plan]", "c2 COORDINATE [and rejected two of them.]"),
                clauses("I reviewed every plan and rejected two of them."), "a shared subject");
        assertEquals(List.of("c1 MAIN [No test failed,]", "c2 COORDINATE [so the build can ship.]"),
                clauses("No test failed, so the build can ship."), "so after a comma");
    }

    @Test
    void aColonBetweenTwoClausesCutsTwoMainClauses() {
        assertEquals(List.of("c1 MAIN [One change came late:]", "c2 MAIN [the model now reads the prompt.]"),
                clauses("One change came late: the model now reads the prompt."));
        assertEquals(List.of("c1 MAIN [Running: t120 (supervised by it)]"), clauses("Running: t120 (supervised by it)"),
                "no subject and verb after the colon");
    }

    @Test
    void anOpeningSubordinateClauseEndsAtItsComma() {
        String text = "If the build fails, the chain waits. The tests passed.";
        assertEquals(List.of("c1 MAIN [If the build fails, the chain waits.]",
                "c2 SUBORDINATE [If the build fails,] in c1", "c3 MAIN [The tests passed.]"), clauses(text));
        assertEquals("waits", head(text, "c1"));
        assertEquals("fails", head(text, "c2"));
    }

    @Test
    void aRelativeClauseInTheSubjectEndsBeforeTheOuterVerb() {
        String text = "The step that ran and failed is stored.";
        assertEquals(List.of("c1 MAIN [The step that ran and failed is stored.]",
                "c2 RELATIVE [that ran and failed] in c1"), clauses(text));
        assertEquals("stored", head(text, "c1"));
        String who = "A reviewer who refused the answer did its job.";
        assertEquals(List.of("c1 MAIN [A reviewer who refused the answer did its job.]",
                "c2 RELATIVE [who refused the answer] in c1"), clauses(who));
    }

    @Test
    void aRelativeClauseMayHaveNoPronoun() {
        assertEquals(List.of("c1 MAIN [Keep the plans I wrote.]", "c2 RELATIVE [I wrote.] in c1"),
                clauses("Keep the plans I wrote."));
    }

    @Test
    void aParticipleClauseEndsBeforeTheNextSubordinateClause() {
        String text = "The step runs as written unless it holds two steps.";
        assertEquals(List.of("c1 MAIN [The step runs as written unless it holds two steps.]",
                "c2 SUBORDINATE [as written] in c1", "c3 SUBORDINATE [unless it holds two steps.] in c1"),
                clauses(text));
        assertFalse(clause(text, "c2").finite());
        assertTrue(clause(text, "c3").finite());
    }

    @Test
    void questionWordsOpenSubordinateClauses() {
        assertEquals(List.of("c1 MAIN [Sections 1 and 3 describe what exists today;]",
                "c2 SUBORDINATE [what exists today] in c1", "c3 COORDINATE [the others describe what would be built.]",
                "c4 SUBORDINATE [what would be built.] in c3"),
                clauses("Sections 1 and 3 describe what exists today; the others describe what would be built."));
    }

    @Test
    void tableCellsAreClauses() {
        assertEquals(List.of("c1 VERBLESS [| 7 |]", "c2 CELL [The run failed |]", "c3 VERBLESS [none |]"),
                clauses("| 7 | The run failed | none |"));
    }

    @Test
    void theHeadIsTheLexicalVerbOrTheCopula() {
        assertEquals("committed", head("The tests have been committed.", "c1"));
        assertEquals("is", head("The suite is green.", "c1"));
    }

    @Test
    void quotedAndAttributedClausesAreNotAsserted() {
        assertEquals(Clause.Asserted.ATTRIBUTED, clause("He said that the build failed.", "c2").asserted());
        assertEquals(Clause.Asserted.ASSERTED, clause("He said that the build failed.", "c1").asserted());
        assertEquals(Clause.Asserted.QUOTED, clause("\"The build failed.\"", "c1").asserted());
    }

    @Test
    void subjectAndVerbReadsAPhraseThenAVerb() {
        String text = "only its last step runs the test";
        List<Token> tokens = ConceptRecognizers.input(text, null).tokens();
        assertTrue(ClauseSegmenter.subjectAndVerb(text, tokens, 0, tokens.size() - 1));
        assertFalse(ClauseSegmenter.subjectAndVerb(text, tokens, 4, tokens.size() - 1), "a verb first");
        String quoted = "t120 (supervised by it)";
        List<Token> others = ConceptRecognizers.input(quoted, null).tokens();
        assertFalse(ClauseSegmenter.subjectAndVerb(quoted, others, 0, others.size() - 1), "the group is passed over");
    }

    @Test
    void openerNamesTheClauseATokenOpens() {
        String text = "The plan that failed is stored.";
        List<Token> tokens = ConceptRecognizers.input(text, null).tokens();
        assertEquals(Clause.Type.RELATIVE, ClauseSegmenter.opener(text, tokens, 2, 0, tokens.size() - 1));
        assertNull(ClauseSegmenter.opener(text, tokens, 0, 0, tokens.size() - 1));
        String said = "He said that it failed.";
        List<Token> others = ConceptRecognizers.input(said, null).tokens();
        assertEquals(Clause.Type.SUBORDINATE, ClauseSegmenter.opener(said, others, 2, 0, others.size() - 1));
        String kept = "Keep the plans I wrote.";
        List<Token> third = ConceptRecognizers.input(kept, null).tokens();
        assertEquals(Clause.Type.RELATIVE, ClauseSegmenter.opener(kept, third, 3, 0, third.size() - 1));
        assertNull(ClauseSegmenter.opener(kept, third, 3, 3, third.size() - 1), "no token before it in the clause");
    }
}
