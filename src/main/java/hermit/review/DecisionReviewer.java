package hermit.review;

import hermit.model.DecisionClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link StructuredReviewer} over the decision service's client: each question and answer carried across one to one (a
 * yes/no question is the service's "noul"). The client stays in {@code hermit.model}, where the model router uses it too.
 */
public final class DecisionReviewer implements StructuredReviewer {

    private final DecisionClient client;

    public DecisionReviewer(DecisionClient client) {
        this.client = client;
    }

    @Override
    public Result decide(String state, Map<String, Question> questions) {
        Map<String, DecisionClient.Question> asked = new LinkedHashMap<>();
        questions.forEach((id, q) -> asked.put(id, switch (q) {
            case Question.YesNo y -> new DecisionClient.Question.Noul(y.instructions());
            case Question.Choice c -> new DecisionClient.Question.Choice(c.instructions(), c.criteria());
            case Question.Score s -> new DecisionClient.Question.Score(s.instructions(), s.criteria());
        }));
        DecisionClient.Result r = client.decide(state, asked);
        Map<String, Answer> answers = new LinkedHashMap<>();
        r.answers().forEach((id, a) -> answers.put(id,
                new Answer(a.type(), a.noul(), a.choice(), a.score(), a.probabilities(), a.confidence())));
        return new Result(answers, r.error());
    }
}
