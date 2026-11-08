package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the decision service, Jev "structured decision" API: given a piece of {@code state}
 * and one or more named {@link Question}s about it, returns a typed {@link Answer} per
 * question (a yes/no probability, a category choice with a confidence distribution, or
 * a rubric score) - no generated prose to parse. See {@link DecisionConfig} for key setup.
 */
public final class DecisionClient {
    /** One question to ask about a shared piece of state. */
    public sealed interface Question {
        String instructions();

        /** Yes/no probability. */
        record Noul(String instructions) implements Question {}

        /** Pick one labeled option; {@code criteria} maps option -> description. */
        record Choice(String instructions, Map<String, String> criteria) implements Question {}

        /** Pick a rung on an ordered rubric (2-10 levels, low to high). */
        record Score(String instructions, List<String> criteria) implements Question {}
    }

    /** The typed answer to one question. Unset fields for the other types are null. */
    public record Answer(String type, Double noul, String choice, Integer score,
                          Map<String, Double> probabilities, Double confidence) {
        /** For a Noul answer: true once the yes-probability clears {@code threshold}. */
        public boolean isYes(double threshold) {
            return noul != null && noul >= threshold;
        }
    }

    /** Outcome of one Jev call: answers keyed by the question id, or an error. */
    public record Result(Map<String, Answer> answers, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final String baseUrl;
    private final boolean keyed;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public DecisionClient() {
        this(DecisionConfig.baseUrl());
    }

    /** Package-visible: lets tests point at a mock server instead of the real API. */
    DecisionClient(String baseUrl) {
        this(baseUrl, true, Duration.ofSeconds(15));
    }

    private DecisionClient(String baseUrl, boolean keyed, Duration timeout) {
        this.baseUrl = baseUrl;
        this.keyed = keyed;
        this.timeout = timeout;
    }

    /**
     * A decision model served on this machine with the same contract (OpenThai-SystemOne): no API key is sent, and only a
     * loopback URL is accepted, so the state never leaves the machine. A local forward pass over a long state takes
     * seconds, hence the longer timeout.
     */
    public static DecisionClient local(String url) {
        if (!isLoopback(url)) {
            throw new IllegalArgumentException("not a loopback URL: " + url);
        }
        return new DecisionClient(url, false, Duration.ofSeconds(60));
    }

    /** Whether {@code url} names this machine: {@code localhost} or a loopback address literal (nothing is resolved). */
    public static boolean isLoopback(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            if (host == null) {
                return false;
            }
            if (host.equalsIgnoreCase("localhost")) {
                return true;
            }
            String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            return literal.matches("[0-9.]+|[0-9a-fA-F:]+") && InetAddress.getByName(literal).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Ask one or more questions about the same state in a single call. */
    public Result decide(String state, Map<String, Question> questions) {
        String key = keyed ? DecisionConfig.apiKey() : null;
        if (keyed && key == null) {
            return new Result(Map.of(), "No Jev API key configured");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("state", state);
            body.put("model", DecisionConfig.DEFAULT_MODEL);
            Map<String, Object> qBody = new LinkedHashMap<>();
            for (Map.Entry<String, Question> e : questions.entrySet()) {
                qBody.put(e.getKey(), questionBody(e.getValue()));
            }
            body.put("questions", qBody);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (key != null) {
                builder.header("Authorization", "Bearer " + key);
            }
            HttpRequest req = builder.build();

            long started = System.nanoTime();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            InferenceTrace.record(baseUrl, DecisionConfig.DEFAULT_MODEL, started, resp);

            if (resp.statusCode() != 200) {
                return new Result(Map.of(), "Jev HTTP " + resp.statusCode() + ": " + tail(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            Map<String, Answer> answers = new LinkedHashMap<>();
            root.path("answers").properties().forEach(e -> answers.put(e.getKey(), parseAnswer(e.getValue())));
            return new Result(answers, null);
        } catch (Exception e) {
            return new Result(Map.of(), "Jev call failed: " + e.getMessage());
        }
    }

    /** Convenience: a single yes/no question. Null if the call failed. */
    public Double noul(String state, String instructions) {
        Result r = decide(state, Map.of("q", new Question.Noul(instructions)));
        Answer a = r.answers().get("q");
        return a == null ? null : a.noul();
    }

    private static Map<String, Object> questionBody(Question q) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (q) {
            case Question.Noul n -> {
                m.put("type", "noul");
                m.put("instructions", n.instructions());
            }
            case Question.Choice c -> {
                m.put("type", "choice");
                m.put("instructions", c.instructions());
                m.put("criteria", c.criteria());
            }
            case Question.Score s -> {
                m.put("type", "score");
                m.put("instructions", s.instructions());
                m.put("criteria", s.criteria());
            }
        }
        return m;
    }

    static Answer parseAnswer(JsonNode node) {
        String type = node.path("type").asText("");
        Double noul = node.has("noul") ? node.path("noul").asDouble() : null;
        String choice = node.has("choice") ? node.path("choice").asText() : null;
        Integer score = node.has("score") ? node.path("score").asInt() : null;
        Map<String, Double> probs = new LinkedHashMap<>();
        node.path("probabilities").properties().forEach(e -> probs.put(e.getKey(), e.getValue().asDouble()));
        Double confidence = node.has("confidence") ? node.path("confidence").asDouble() : null;
        return new Answer(type, noul, choice, score, probs, confidence);
    }

    private static String tail(String s) {
        return s == null ? "" : (s.length() > 400 ? s.substring(s.length() - 400) : s);
    }
}
