package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.model.Span;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CheckersTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("UTC"), "en");
    /** One statement for each standard checker, in another order. */
    private static final String TEXT = "Mon Oct 20 we shipped. We computed 2 + 2 = 5. Version 10 exceeds version 9. "
            + "It took 2 minutes and 1 minute, 3 minutes in total. These 3 steps:\n1. build it\n2. test it\n3. ship it";

    @Test
    void theStandardCheckersComeInOrder() {
        assertEquals(List.of("date", "arithmetic", "count", "version", "unit"), Checkers.standard().names());
    }

    @Test
    void checksOfRunsEveryChecker() {
        List<Check> checks = Checkers.checksOf(TEXT, CONTEXT);
        assertEquals(List.of("date", "arithmetic", "count", "version", "unit"),
                checks.stream().map(Check::checker).toList());
        assertEquals(List.of(Check.Status.INCONSISTENT, Check.Status.INCONSISTENT, Check.Status.CONSISTENT,
                Check.Status.CONSISTENT, Check.Status.CONSISTENT), checks.stream().map(Check::status).toList());
    }

    @Test
    void everyTargetIsASpanOfTheInput() {
        Checker.Input input = Checkers.input(TEXT, CONTEXT);
        Set<String> ids = input.spans().stream().map(Span::id).collect(Collectors.toSet());
        for (Check check : Checkers.standard().check(input)) {
            assertTrue(ids.containsAll(check.targets()), check.toString());
        }
    }

    @Test
    void theInputHoldsTheMergedSpans() {
        Checker.Input input = Checkers.input("Today is Thursday.", CONTEXT);
        assertEquals("Today is Thursday.", input.text());
        assertEquals(List.of(Span.Concept.DATE, Span.Concept.WEEKDAY),
                input.spans().stream().map(Span::concept).toList());
        assertEquals(4, input.tokens().size());
        assertEquals(1, input.sentences().size());
        assertSame(CONTEXT, input.context());
    }

    @Test
    void aStageRunsOnlyItsCheckers() {
        Checkers arithmetic = new Checkers(List.of(new ArithmeticChecker()));
        assertEquals(List.of("arithmetic"), arithmetic.names());
        assertEquals(List.of("arithmetic"),
                arithmetic.check(Checkers.input(TEXT, CONTEXT)).stream().map(Check::checker).toList());
    }
}
