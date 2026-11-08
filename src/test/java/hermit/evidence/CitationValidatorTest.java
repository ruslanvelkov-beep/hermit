package hermit.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    static Path root;

    @BeforeAll
    static void workbench() throws Exception {
        root = Files.createTempDirectory("hermit-citations");
        Path loans = Files.createDirectories(root.resolve("service/src/main/java/app/loans"));
        Files.writeString(loans.resolve("AbstractLoanStep.java"), """
                package app.loans;

                public abstract class AbstractLoanStep {
                    protected boolean canRenew(Object loan) {
                        return true;
                    }
                }
                """);
        Files.writeString(loans.resolve("ReturnLoanStep.java"), """
                package app.loans;

                import java.util.Optional;

                public class ReturnLoanStep extends AbstractLoanStep {
                    public void apply(Object context) {
                        Object cardNumber = Optional.empty();
                        requireCard(cardNumber);
                    }

                    private void requireCard(Object card) {}
                }
                """);
        StringBuilder rules = new StringBuilder("package app.loans;\n\npublic final class LoanRules {\n");
        for (int i = 4; i < 12; i++) {
            rules.append("    // filler ").append(i).append('\n');
        }
        rules.append("    static Object borrower(Object memberId) { return borrowerIdOrElse(memberId); }\n");
        rules.append("    static Object borrowerIdOrElse(Object fallback) { return fallback; }\n}\n");
        Files.writeString(loans.resolve("LoanRules.java"), rules.toString());
        Files.writeString(loans.resolve("RemoteCatalog.java"), """
                package app.loans;

                import org.library.Transport;

                public class RemoteCatalog implements Transport {}
                """);
        Files.writeString(root.resolve("service/src/main/java/app/Loan.java"), "package app;\n\nclass Loan {}\n");
        Path generated = Files.createDirectories(root.resolve("api/build/generated/source/main/java/app/state"));
        Files.writeString(generated.resolve("Loan.java"),
                "package app.state;\n\npublic record Loan(Object borrowerId) {}\n");
    }

    private static List<String> missing(String text) {
        return CitationValidator.missing(text, CitationValidator.repoFor(root)).stream()
                .map(m -> m.citation().text()).toList();
    }

    @Test
    void readsTheWaysAnAnswerCitesAPlace() {
        List<CitationValidator.Citation> cited = CitationValidator.extract("see `LoanRules.java` lines 73-77, then "
                + "line 201 of ReturnLoanStep.java, AbstractLoanStep.java (lines 4-5), loans/X.java:L12 and "
                + "LoanRules:201");
        assertEquals(List.of("LoanRules.java:73-77", "ReturnLoanStep.java:201", "AbstractLoanStep.java:4-5",
                "loans/X.java:12", "LoanRules:201"), cited.stream().map(CitationValidator.Citation::text).toList());
        assertTrue(CitationValidator.extract("https://github.com/org/repo/blob/main/x/Foo.java").isEmpty(),
                "a URL is not a workbench file");
    }

    @Test
    void aFileThatDoesNotExistIsDismissed() {
        assertEquals(List.of("LoanRenewService.java:40", "LoanRenewService"),
                missing("Renewal is gated in LoanRenewService.java:40, so the LoanRenewService blocks it."));
        assertEquals(List.of(), missing("The borrower default is in LoanRules.java:12 and in loans/LoanRules.java."));
        assertEquals(List.of("other/LoanRules.java"), missing("See other/LoanRules.java for the borrower."));
        assertEquals(List.of(),
                missing("Read " + root.resolve("service/src/main/java/app/loans/LoanRules.java") + ":3."));
        assertEquals(List.of("/no/such/Thing.java"), missing("Read /no/such/Thing.java first."));
    }

    @Test
    void aLooseNameForARealFamilyOfTypesIsNotAnInvention() {
        assertEquals(List.of(), missing("The plan guesses the index instead of reading the `LoanStep` implementation."),
                "LoanStep is part of AbstractLoanStep and ReturnLoanStep");
        assertEquals(List.of("`LoansByMember`"), missing("It is defined via the lookup map `LoansByMember`.")
                .stream().map(n -> "`" + n + "`").toList(), "a word from a comment is not a type");
        assertFalse(CitationValidator.WorkbenchRepo.partOf("ShelfIndex", "ShelfIndexes"));
        assertTrue(CitationValidator.WorkbenchRepo.partOf("ShelfIndex", "CachedShelfIndexImpl"));
    }

    @Test
    void aLinePastTheEndOfTheFileIsDismissed() {
        List<CitationValidator.Missing> gone = CitationValidator.missing(
                "The borrower defaults at LoanRules.java:900.", CitationValidator.repoFor(root));
        assertEquals(1, gone.size());
        assertTrue(gone.get(0).why().contains("has 14 lines"), gone.get(0).why());
    }

    @Test
    void codeQuotedAtALineMustBeThereNotInAnotherFile() {
        assertEquals(List.of(), missing("- LoanRules:12 `borrowerIdOrElse(memberId)` defaults the borrower"));
        assertEquals(List.of("ReturnLoanStep:7"),
                missing("- ReturnLoanStep:7 `borrowerIdOrElse(memberId)` defaults the borrower"),
                "the line exists but the quoted code is in another file");
        assertEquals(List.of(), missing("- LoanRules:12 `borrowerIdOrElse(...)` is abbreviated, not quoted"));
    }

    @Test
    void aBareMethodMentionIsNotAQuotation() {
        assertEquals(List.of(), missing("2. Code Evidence (`ReturnLoanStep.java:6-8`): when an overdue loan is found "
                + "via `borrowerIdOrElse()`, the step records the existing ID."));
        assertEquals(List.of("ReturnLoanStep.java:7"), missing("* In `ReturnLoanStep.java` line 7: "
                + "`borrowerIdOrElse(memberId)`."), "with its arguments it is still a quotation, and checked");
    }

    @Test
    void aQuoteNextToSeveralLineReferencesIsNotAttributed() {
        assertEquals(List.of(), missing("2. The `apply()` method (lines 6-9) uses `borrowerIdOrElse(memberId)` "
                + "(line 12, which reads from `ReturnLoanStep.java` lines 6-8, also omitting the borrower)."));
        assertEquals(List.of("ReturnLoanStep.java:7"), missing("* In `ReturnLoanStep.java` line 7: "
                + "`borrowerIdOrElse(memberId)`."), "one place, one line reference: still checked");
    }

    @Test
    void membersAreCheckedInTheTypeAndItsSupertypes() {
        assertEquals(List.of(),
                missing("`ReturnLoanStep#canRenew` is inherited and `ReturnLoanStep.requireCard()` is its own."));
        assertEquals(List.of("ReturnLoanStep#closeAll"),
                missing("It calls `ReturnLoanStep#closeAll` for the borrower."));
        assertEquals(List.of(), missing("`RemoteCatalog#send` may come from a library supertype."),
                "a library supertype is not a proven absence");
        assertEquals(List.of(), missing("`Loan#borrowerId` is a generated record component."),
                "a generated type is undecided");
        assertEquals(List.of(), missing("`Optional.empty()` and `ConcurrentHashMap` are the JDK's."));
    }

    @Test
    void sentencesThatDenyOrProposeAnObjectAreNotClaimsItExists() {
        assertEquals(List.of(), missing("There is no LoanRenewService — that class does not exist."));
        assertEquals(List.of(), missing("A regression test could be added, such as `LoanOverlapTest`."));
        assertNull(CitationValidator.issue("The reporter cites LoanRenewService.java:40 for the gate.", root,
                "Location: LoanRenewService.java:40"), "a place attributed to the report is the report's");
        assertNotNull(CitationValidator.issue("The gate is in LoanRenewService.java:40.", root,
                "Location: LoanRenewService.java:40"), "asserted as fact, it is the answer's own claim");
    }

    @Test
    void anIssueRestingOnAMissingObjectIsDropped() {
        assertTrue(CitationValidator.citesMissing(
                "The plan ignores `LoanRules#resolveBorrower`, which sets the borrower.", root, "{}"));
        assertFalse(CitationValidator.citesMissing(
                "The plan ignores `LoanRules#borrowerIdOrElse`, which sets the borrower.", root, "{}"));
        assertFalse(CitationValidator.citesMissing("`LoanRules#resolveBorrower` is the method the plan creates.", root,
                "{\"actions\":[{\"tool\":\"Edit\",\"method\":\"resolveBorrower\"}]}") ,
                "the plan's own new objects are judged by the plan's gates");
    }

    @Test
    void theNoteListsWhatIsMissingWhenTheRefusalsAreSpent() {
        String noted = CitationValidator.withNote("NOT CONFIRMED: LoanRenewService.java:40 gates it.", root, "");
        assertTrue(noted.contains("[Hermit citation note") && noted.contains("LoanRenewService.java:40"), noted);
        assertEquals("NOT CONFIRMED: LoanRules.java:12.",
                CitationValidator.withNote("NOT CONFIRMED: LoanRules.java:12.", root, ""));
        assertNull(CitationValidator.issue("LoanRenewService.java:40", null, ""),
                "no workbench, no oracle, no refusal");
    }

    @Test
    void withTheOptInATypeTheRequestNamesIsNeverJudged() {
        String request = "Port the tests to a new class, app.loans.TaskSelectorTest, in a later task.";
        String answer = "The contract lists the five methods TaskSelectorTest will hold.";
        assertNotNull(CitationValidator.issue(answer, root, request), "default: a type that does not exist is refused");
        System.setProperty("hermit.citations.requestNamesExempt", "true");
        try {
            assertNull(CitationValidator.issue(answer, root, request), "the request names it: a type still to be created");
            assertNotNull(CitationValidator.issue("The gate is in LoanQueueService.java:40.", root, request),
                    "a name the request does not mention is still checked");
        } finally {
            System.clearProperty("hermit.citations.requestNamesExempt");
        }
    }
}
