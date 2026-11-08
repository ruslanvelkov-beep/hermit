package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Marker;
import hermit.text.model.Token;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkerRecognizerTest {

    private static final AnnotationDocument.Context CONTEXT =
            new AnnotationDocument.Context(Instant.parse("2026-10-28T20:00:00Z"), ZoneId.of("UTC"), "en");

    /** Each marker as {@code CLASS kind words}, then {@code -> words} when its scope is a token list. */
    private static List<String> found(String text) {
        Map<String, Token> tokens = new HashMap<>();
        for (Token token : ConceptRecognizers.input(text, CONTEXT).tokens()) {
            tokens.put(token.id(), token);
        }
        return MarkerRecognizer.markersOf(text, CONTEXT).stream().map(marker -> {
            String line = marker.markerClass() + " " + marker.kind() + " "
                    + text.substring(marker.range().start(), marker.range().end());
            List<String> scope = marker.scope().tokenIds();
            if (scope != null) {
                line += " -> " + text.substring(tokens.get(scope.get(0)).range().start(),
                        tokens.get(scope.get(scope.size() - 1)).range().end());
            }
            return line;
        }).toList();
    }

    /** The index of the token {@code word} (the first one written so). */
    private static int at(ConceptRecognizer.Input input, String word) {
        for (int i = 0; i < input.tokens().size(); i++) {
            Token token = input.tokens().get(i);
            if (input.text().substring(token.range().start(), token.range().end()).equals(word)) {
                return i;
            }
        }
        throw new AssertionError(word);
    }

    private static String surface(ConceptRecognizer.Input input, int first, int last) {
        return input.text().substring(input.tokens().get(first).range().start(),
                input.tokens().get(last).range().end());
    }

    @Test
    void noBeforeNounIsDeterminerNegation() {
        assertEquals(List.of("NEGATION determiner No -> No test", "QUANTIFIER negative No -> No test"),
                found("No test failed."));
    }

    @Test
    void notOnlyIsNoNegationAndNoExclusive() {
        assertEquals(List.of(), found("The tool reads not only the header but the body."));
    }

    @Test
    void neverIsANegationAndAFrequency() {
        assertEquals(List.of("NEGATION clausal never", "FREQUENCY never never", "NEGATION clausal not"),
                found("The parser never stops, and the cache does not grow."));
    }

    @Test
    void cannotIsANegationAndAnAbility() {
        assertEquals(List.of("NEGATION clausal cannot", "MODALITY ability cannot -> open"),
                found("The tool cannot open the file."));
    }

    @Test
    void pronounNegationsAreNegativeQuantifiers() {
        assertEquals(List.of("NEGATION pronoun Nothing", "QUANTIFIER negative Nothing",
                "NEGATION pronoun none -> none", "QUANTIFIER negative none -> none"),
                found("Nothing changed. The list holds none."));
    }

    @Test
    void withoutScopesOverItsNounPhraseOnly() {
        assertEquals(List.of("NEGATION prepositional without -> without a lock"),
                found("The job runs without a lock."));
        assertEquals(List.of("NEGATION prepositional without"), found("The job returns without checking the cache."));
    }

    @Test
    void quantifiersScopeOverTheirNounPhrase() {
        assertEquals(List.of("QUANTIFIER universal Every -> Every user", "QUANTIFIER universal each -> each group",
                "QUANTIFIER universal all -> all the old settings"),
                found("Every user and each group keep all the old settings."));
    }

    @Test
    void anyIsExistentialAfterANegation() {
        assertEquals(List.of("NEGATION clausal not", "QUANTIFIER existential any -> any file"),
                found("The script did not change any file."));
        assertEquals(List.of("QUANTIFIER universal Any -> Any file", "MODALITY ability can -> change"),
                found("Any file can change."));
    }

    @Test
    void onlyIsExclusiveOverThePhraseItBoundsOrEnds() {
        assertEquals(List.of("QUANTIFIER exclusive only -> only the first page"),
                found("The job reads only the first page."));
        assertEquals(List.of("QUANTIFIER exclusive only -> Network access"), found("| Network access only |"));
    }

    @Test
    void modalsHaveAKindAndScopeOverTheVerbTheyGovern() {
        assertEquals(List.of("MODALITY necessity must -> pass", "MODALITY advice should -> grow",
                "MODALITY possibility may -> fail"),
                found("The test must pass, the cache should grow, and the build may fail."));
        assertEquals(List.of(), found("The job will run tomorrow."));
    }

    @Test
    void haveToAndBeAbleToAreModalities() {
        assertEquals(List.of("MODALITY necessity has to -> wait", "MODALITY ability is able to -> cancel"),
                found("The worker has to wait, and the user is able to cancel."));
    }

    @Test
    void sinceIsTemporalBeforeAPointInTimeAndCausalBeforeAClause() {
        assertEquals(List.of("TEMPORAL null since"), found("The cache has grown since 2026-10-01."));
        assertEquals(List.of("CAUSAL null since"), found("The run stopped, since the disk was full."));
        assertEquals(List.of("TEMPORAL null since"), found("Files added since the seam were kept."));
    }

    @Test
    void whenStatingAConditionIsAlsoConditional() {
        assertEquals(List.of("TEMPORAL null when", "CONDITIONAL null when"),
                found("It returns zero when the list is empty."));
        assertEquals(List.of("TEMPORAL null When"), found("When the job ends, it writes a log."));
    }

    @Test
    void beforeAPlaceIsNoTemporal() {
        assertEquals(List.of(), found("Put a blank line before the comment."));
        assertEquals(List.of(), found("The note stays before it."));
        assertEquals(List.of("TEMPORAL null before"), found("Save the file before the build."));
    }

    @Test
    void causesAndConditions() {
        assertEquals(List.of("CAUSAL null so", "CAUSAL null because"),
                found("The test failed, so the build stopped because the disk was full."));
        assertEquals(List.of("CONDITIONAL null if", "CONDITIONAL null unless"),
                found("It runs if the flag is set unless a lock is held."));
        assertEquals(List.of("CONDITIONAL null Otherwise"), found("Otherwise the job waits."));
        assertEquals(List.of(), found("The output is otherwise the same."));
    }

    @Test
    void frequenciesCountOrQualify() {
        assertEquals(List.of("FREQUENCY always always", "FREQUENCY count twice", "FREQUENCY sometimes sometimes"),
                found("The job always retries twice and sometimes stops."));
        assertEquals(List.of("FREQUENCY count once"), found("The answer states its result once."));
    }

    @Test
    void hedgesQualifyAnAmount() {
        assertEquals(List.of("HEDGE null about", "HEDGE null probably"),
                found("It takes about 20 minutes and probably less."));
        assertEquals(List.of("HEDGE null ~"), found("Keep ~5 runs."));
    }

    @Test
    void aVerbOfSayingBeforeAQuotationIsAnAttribution() {
        assertEquals(List.of("ATTRIBUTION null says -> \"disk full\"", "QUOTATION quote \"disk full\" -> disk full",
                "FREQUENCY count again"),
                found("The log says \"disk full\" again."));
    }

    @Test
    void quotationsPairTheirMarks() {
        assertEquals(List.of("QUOTATION quote 'build green' -> build green"),
                found("The team's tool printed 'build green'."));
        assertEquals(List.of("ATTRIBUTION null answered -> \"not found\"", "QUOTATION quote \"not found\" -> not found",
                "NEGATION clausal not -> not found"), found("It answered with \"not found\"."));
    }

    @Test
    void aWordAloneInQuotationMarksIsMentioned() {
        assertEquals(List.of("QUOTATION quote \"not\" -> not"), found("Write \"not\" in the field."));
    }

    @Test
    void wordsInCodeSpansAreNoMarkers() {
        assertEquals(List.of(), found("Set `never` as the policy."));
    }

    @Test
    void markersComeInTextOrderWithIdsAndRules() {
        List<Marker> markers = MarkerRecognizer.markersOf("No file is never read.", CONTEXT);
        assertEquals(List.of("m1 NEGATION", "m2 QUANTIFIER", "m3 NEGATION", "m4 FREQUENCY"),
                markers.stream().map(m -> m.id() + " " + m.markerClass()).toList());
        assertEquals("marker:never", markers.get(3).provenance().rule());
    }

    @Test
    void clauseScopesStayEmptyUntilClausesExist() {
        Marker.Scope scope = MarkerRecognizer.markersOf("If the cache is cold, the job waits.", CONTEXT).get(0).scope();
        assertEquals(new Marker.Scope(null, null, null, null, null), scope);
        assertNull(scope.clause());
    }

    @Test
    void aNounPhraseGoesOnOverACoordination() {
        ConceptRecognizer.Input input = ConceptRecognizers.input("It keeps any old code or new files here.", CONTEXT);
        int any = at(input, "any");
        assertEquals("old code or new files", surface(input, any + 1,
                MarkerRecognizer.nounPhraseEnd(input.text(), input.tokens(), any + 1, input.tokens().size() - 1)));
        input = ConceptRecognizers.input("It keeps any old code or the files.", CONTEXT);
        any = at(input, "any");
        assertEquals("old code", surface(input, any + 1,
                MarkerRecognizer.nounPhraseEnd(input.text(), input.tokens(), any + 1, input.tokens().size() - 1)));
        input = ConceptRecognizers.input("It drops flags, notes or logs.", CONTEXT);
        int flags = at(input, "flags");
        assertEquals("flags, notes or logs", surface(input, flags,
                MarkerRecognizer.nounPhraseEnd(input.text(), input.tokens(), flags, input.tokens().size() - 1)));
        input = ConceptRecognizers.input("It waits for each call and the reply arrives.", CONTEXT);
        int each = at(input, "each");
        assertEquals("each call", surface(input, each,
                MarkerRecognizer.nounPhraseEnd(input.text(), input.tokens(), each, input.tokens().size() - 1)));
        assertEquals(-1, MarkerRecognizer.nounPhraseEnd(input.text(), input.tokens(), at(input, "waits"),
                input.tokens().size() - 1));
    }

    @Test
    void aModalGovernsTheVerbAfterItsAuxiliariesOrItsCopula() {
        ConceptRecognizer.Input input = ConceptRecognizers.input("It would have been replaced.", CONTEXT);
        int end = input.tokens().size() - 1;
        assertEquals("replaced", surface(input, MarkerRecognizer.governedVerb(input.text(), input.tokens(),
                at(input, "would"), 0, end), MarkerRecognizer.governedVerb(input.text(), input.tokens(),
                at(input, "would"), 0, end)));
        input = ConceptRecognizers.input("Can this tool help?", CONTEXT);
        int help = MarkerRecognizer.governedVerb(input.text(), input.tokens(), 0, 0, input.tokens().size() - 1);
        assertEquals("help", surface(input, help, help));
        input = ConceptRecognizers.input("It must be in the list.", CONTEXT);
        int be = MarkerRecognizer.governedVerb(input.text(), input.tokens(), at(input, "must"), 0,
                input.tokens().size() - 1);
        assertEquals("be", surface(input, be, be));
    }
}
