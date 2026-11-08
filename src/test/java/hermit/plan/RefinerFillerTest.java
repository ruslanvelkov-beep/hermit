package hermit.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The answer-refiner must recognise generic "provide your question" filler and reject it. */
class RefinerFillerTest {

    @Test
    void detectsGenericNonAnswers() {
        assertTrue(PlanGate.isRefinerFiller(
                "I'm ready. Please provide the exact technical question or task you need answered."));
        assertTrue(PlanGate.isRefinerFiller(
                "You haven't provided a question or problem statement. Please supply the details."));
        assertTrue(PlanGate.isRefinerFiller("No task was provided — what is your question?"));
    }

    @Test
    void passesRealAnswers() {
        assertFalse(PlanGate.isRefinerFiller("The current BTC price in USD is $64,264."));
        assertFalse(PlanGate.isRefinerFiller(
                "The weather in Sofia is 22°C, partly cloudy, wind 10 km/h."));
        assertFalse(PlanGate.isRefinerFiller(
                "Use requests.get('https://api.coingecko.com/...').json() to fetch the price."));
    }
}
