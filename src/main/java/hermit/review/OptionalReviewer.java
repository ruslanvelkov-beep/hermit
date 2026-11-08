package hermit.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.checkpoint.Checkpoint;
import hermit.model.DecisionClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shadow critic: how well an answer that reaches the supervisor's answer seam is grounded, scored by a decision model and
 * recorded next to the supervisor's decision in the step directory ({@code shadow.json}). It is never acted on: nothing
 * enters the context and no gate reads it. Two sources, each opt-in:
 * <ul>
 *   <li>{@code -Dhermit.shadow.openthai=<url>} - OpenThai-SystemOne served on this machine with the hosted contract
 *       ({@code scripts/openthai-server.py}); a loopback URL only, so the state never leaves the machine;</li>
 *   <li>{@code -Dhermit.shadow.jev=true} - TypeSafe's hosted Jev: it sends the request, the evidence and the answer to
 *       {@code api.typesafe.ai}, in a local-only run only when that host is allow-listed as well.</li>
 * </ul>
 *
 * <p>The question and the state are those of an offline measurement on the 25 answer seams of supervised drive v334,
 * rounds A-F: OpenThai's grounding score separated the answers the supervisor accepted from those refused with AUC 0.91,
 * hosted Jev's with 0.62. The shadow collects seams the score was not chosen on, to confirm it or not before it is given
 * any say. The calls run while the seam waits, and the file is written only after the decision, so the supervisor
 * decides without seeing the score.
 */
public final class OptionalReviewer {

    static final int EVIDENCE_CAP = 14_000;   // what the verdict critic is shown of the evidence
    static final long REPLY_WAIT_SECONDS = 90;
    static final String QUESTION = "grounding";
    static final StructuredReviewer.Question.Score GROUNDING = new StructuredReviewer.Question.Score(
            "How well is each claim of the PROPOSED FINAL ANSWER grounded in a printed line of the EVIDENCE "
                    + "or a SUPPORTED claim of the CLAIMS LEDGER?",
            List.of("most claims have no line behind them or contradict a line",
                    "several claims have no line behind them, or one contradicts a line",
                    "one claim has no line behind it",
                    "every claim rests on a printed line or a SUPPORTED ledger claim"));

    record Source(String name, StructuredReviewer client) {}

    /** The calls started at a seam, joined when its decision is recorded. */
    public record Pending(int stateChars, Map<String, CompletableFuture<Map<String, Object>>> calls) {}

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "shadow-critic");
        t.setDaemon(true);
        return t;
    });

    private final List<Source> sources;
    private final ObjectMapper mapper = new ObjectMapper();

    OptionalReviewer(List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    public static OptionalReviewer fromSystemProperties() {
        List<Source> sources = new ArrayList<>();
        String local = System.getProperty("hermit.shadow.openthai", "").strip();
        if (!local.isEmpty()) {
            if (DecisionClient.isLoopback(local)) {
                sources.add(new Source("openthai", new DecisionReviewer(DecisionClient.local(local))));
            } else {
                System.err.println("[shadow] hermit.shadow.openthai must be a loopback URL; ignored: " + local);
            }
        }
        if (Boolean.getBoolean("hermit.shadow.jev")) {
            sources.add(new Source("jev", new DecisionReviewer(new DecisionClient())));
        }
        return new OptionalReviewer(sources);
    }

    public boolean enabled() {
        return !sources.isEmpty();
    }

    List<String> sourceNames() {
        return sources.stream().map(Source::name).toList();
    }

    /** The measured state: the request, the last {@value #EVIDENCE_CAP} characters of the evidence, the ledger, the answer. */
    public static String state(String request, String contextLog, String ledger, String answer) {
        String evidence = contextLog == null ? "" : contextLog;
        StringBuilder sb = new StringBuilder("REQUEST:\n").append(request == null ? "" : request.strip())
                .append("\n\nEVIDENCE GATHERED (most recent part):\n")
                .append(evidence.length() <= EVIDENCE_CAP ? evidence : evidence.substring(evidence.length() - EVIDENCE_CAP));
        if (ledger != null && !ledger.isBlank()) {
            sb.append("\n\nCLAIMS LEDGER:\n").append(ledger.strip());
        }
        return sb.append("\n\nPROPOSED FINAL ANSWER:\n").append(answer == null ? "" : answer.strip()).toString();
    }

    /** Starts every source's call in the background; {@link #record} joins them. Null when no source is configured. */
    public Pending start(String state) {
        if (!enabled()) {
            return null;
        }
        Map<String, CompletableFuture<Map<String, Object>>> calls = new LinkedHashMap<>();
        for (Source s : sources) {
            calls.put(s.name(), CompletableFuture.supplyAsync(() -> ask(s.client(), state), POOL));
        }
        return new Pending(state.length(), calls);
    }

    static Map<String, Object> ask(StructuredReviewer client, String state) {
        long started = System.nanoTime();
        StructuredReviewer.Result r = client.decide(state, Map.of(QUESTION, GROUNDING));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seconds", Math.round((System.nanoTime() - started) / 1e7) / 100.0);
        StructuredReviewer.Answer a = r.ok() ? r.answers().get(QUESTION) : null;
        if (a == null || a.probabilities().isEmpty()) {
            out.put("error", r.ok() ? "no " + QUESTION + " answer in the reply" : r.error());
            return out;
        }
        out.put("score", Math.round(expectedLevel(a.probabilities()) * 1000) / 1000.0);
        out.put("probabilities", a.probabilities());
        out.put("confidence", a.confidence());
        return out;
    }

    /** The expected level (sum of level x probability): the model's own score, of which {@link StructuredReviewer.Answer} keeps the integer part. */
    static double expectedLevel(Map<String, Double> probabilities) {
        double score = 0;
        for (Map.Entry<String, Double> e : probabilities.entrySet()) {
            score += Integer.parseInt(e.getKey()) * e.getValue();
        }
        return score;
    }

    /** Writes {@code shadow.json} into the step: the supervisor's decision and each source's score, or why it has none. */
    public void record(Path step, Pending pending, Checkpoint.Decision decision) {
        if (step == null || pending == null) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("question", QUESTION);
        doc.put("decision", decision == null ? null : decision.control().name().toLowerCase(Locale.ROOT));
        if (decision != null && decision.note() != null) {
            doc.put("note", decision.note());
        }
        doc.put("stateChars", pending.stateChars());
        pending.calls().forEach((name, call) -> {
            Map<String, Object> reply;
            try {
                reply = call.get(REPLY_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                reply = Map.of("error", "no reply within " + REPLY_WAIT_SECONDS + " s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply = Map.of("error", "interrupted");
            } catch (Exception e) {
                reply = Map.of("error", String.valueOf(e.getCause() == null ? e : e.getCause()));
            }
            doc.put(name, reply);
        });
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(step.resolve("shadow.json").toFile(), doc);
        } catch (IOException e) {
            System.err.println("[shadow] cannot write " + step.resolve("shadow.json") + ": " + e.getMessage());
        }
    }
}
