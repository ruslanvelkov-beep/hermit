package hermit.review;

import java.util.List;
import java.util.Map;

/**
 * A reviewer that answers typed questions about a piece of state: a yes/no probability, one of a set of labelled options
 * with a confidence distribution, or a rung on an ordered rubric - no generated prose to parse. The rewrite ships no
 * client of its own (the master plan): the plan guard and the other optional reviewers use one when it is configured
 * and do nothing without it.
 */
public interface StructuredReviewer {

    /** One question to ask about a shared piece of state. */
    sealed interface Question {
        String instructions();

        /** Yes/no probability. */
        record YesNo(String instructions) implements Question {}

        /** Pick one labelled option; {@code criteria} maps option -> description. */
        record Choice(String instructions, Map<String, String> criteria) implements Question {}

        /** Pick a rung on an ordered rubric (2-10 levels, low to high). */
        record Score(String instructions, List<String> criteria) implements Question {}
    }

    /** The typed answer to one question. Unset fields for the other types are null. */
    record Answer(String type, Double yes, String choice, Integer score, Map<String, Double> probabilities,
            Double confidence) {
        /** For a yes/no answer: true once the yes-probability clears {@code threshold}. */
        public boolean isYes(double threshold) {
            return yes != null && yes >= threshold;
        }
    }

    /** Outcome of one call: answers keyed by the question id, or an error. */
    record Result(Map<String, Answer> answers, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Ask one or more questions about the same state in a single call. */
    Result decide(String state, Map<String, Question> questions);

    /** A single yes/no question: its probability, or null if the call failed. */
    default Double yes(String state, String instructions) {
        Result r = decide(state, Map.of("q", new Question.YesNo(instructions)));
        Answer a = r.answers().get("q");
        return a == null ? null : a.yes();
    }
}
