package hermit.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chooses, at each point where Hermit asks another model for help, between the LOCAL model (the helper chain - on
 * the local cluster, the run's own model) and the hosted BigBrother (the escalation endpoint).
 * Modes, {@code -Dhermit.router.mode}:
 * <ul>
 *   <li>{@code off} (default) - the established rule: the hosted model whenever one is configured and up;</li>
 *   <li>{@code rules} - Hermit's own rules decide; a case they leave open stays local;</li>
 *   <li>{@code jev-fallback} - Hermit's rules decide; a case they leave open goes to Jev;</li>
 *   <li>{@code jev-main} - Jev decides every case; Hermit's rules decide only when Jev gives no confident answer.</li>
 * </ul>
 * Two limits hold in every mode: no hosted call when no hosted model is reachable, none once the hosted budget
 * ({@code -Dhermit.router.maxCloudCalls}) is spent. Jev is told the situation (the step, the refusals, the budget), never
 * the case. Every decision is logged and appended to {@code -Dhermit.router.trace} (JSONL).
 */
public final class ModelRouter {

    public enum Route { LOCAL, CLOUD }

    public enum Mode { OFF, RULES, JEV_FALLBACK, JEV_MAIN }

    /** Where Hermit asks another model for help. */
    public enum Point {
        VERDICT_REVIEW("a second reviewer reads the agent's verdict against the evidence before it is accepted"),
        FLIP_REVIEW("a reviewer reads a rewrite that reverses the agent's ruling"),
        PLAN_AUTHOR("another model writes a corrective plan after the agent's own plans were refused"),
        SYNTHESIS("the agent is stuck and another model writes the answer from the evidence gathered so far"),
        BIGBROTHER_CALL("the agent's planner asked for the stronger model's help on a problem");

        final String description;

        Point(String description) {
            this.description = description;
        }
    }

    /** A choice of model rather than of route: the work one of the configured models is picked for. */
    public enum Pick {
        STEP("plan the next command for the current step, or answer a short question"),
        REVISE("revise the plan, because the plans so far brought no useful result"),
        INITIAL("write the first plan of the task"),
        AHEAD("plan the next steps of the task ahead");

        final String description;

        Pick(String description) {
            this.description = description;
        }
    }

    /** A chosen model (a catalogue id), who chose it (rules / exploration) and why; confidence is not used (null). */
    public record ModelChoice(String model, String by, String why, Double confidence) {}

    /** What the decision is made from - counts only, nothing of the case. */
    public record Signals(Point point, int iteration, int maxIterations, int answerRefusals, int planRefusals,
                          int verdictFlips, int cloudCalls, int maxCloudCalls,
                          boolean localAvailable, boolean cloudAvailable) {}

    /** A decision: the route, who made it (rules / jev / limit), why, and Jev's confidence when Jev made it. */
    public record Decision(Route route, String by, String why, Double confidence) {}

    /** Hosted calls made this run (escalation and BigBrother together). */
    private static final AtomicInteger CLOUD_CALLS = new AtomicInteger();

    public static int cloudCalls() {
        return CLOUD_CALLS.get();
    }

    /** The agent's latest signals, for a decision taken outside the agent loop (the BigBrother tool). */
    private static volatile Signals lastTurn;

    public static void noteTurn(Signals signals) {
        lastTurn = signals;
    }

    /** Signals for a BigBrother call: the agent's latest counts, with the current budget. */
    public static Signals forBigBrotherCall(boolean localAvailable, boolean cloudAvailable) {
        Signals t = lastTurn;
        return new Signals(Point.BIGBROTHER_CALL, t == null ? 0 : t.iteration(), t == null ? 0 : t.maxIterations(),
                t == null ? 0 : t.answerRefusals(), t == null ? 0 : t.planRefusals(), t == null ? 0 : t.verdictFlips(),
                cloudCalls(), maxCloudCalls(), localAvailable, cloudAvailable);
    }

    public static void countCloudCall() {
        CLOUD_CALLS.incrementAndGet();
    }

    public static Mode mode() {
        return switch (System.getProperty("hermit.router.mode", "off").toLowerCase(Locale.ROOT)) {
            case "rules" -> Mode.RULES;
            case "jev-fallback", "jev_fallback", "fallback" -> Mode.JEV_FALLBACK;
            case "jev-main", "jev_main", "jev" -> Mode.JEV_MAIN;
            default -> Mode.OFF;
        };
    }

    public static int maxCloudCalls() {
        return Integer.getInteger("hermit.router.maxCloudCalls", 25);
    }

    private static double minJevConfidence() {
        return Double.parseDouble(System.getProperty("hermit.router.minJevConfidence", "0.6"));
    }

    /** The route Hermit's own rules give, with the reason; route null when they leave the case open. */
    public record Rule(Route route, String why) {}

    static Rule limits(Signals s) {
        if (!s.cloudAvailable()) {
            return new Rule(Route.LOCAL, "no hosted model is reachable");
        }
        if (s.cloudCalls() >= s.maxCloudCalls()) {
            return new Rule(Route.LOCAL, "the hosted budget of " + s.maxCloudCalls() + " calls is spent");
        }
        if (!s.localAvailable()) {
            return new Rule(Route.CLOUD, "the local model is not available");
        }
        return null;
    }

    /** Hermit's own rules. */
    static Rule rules(Signals s) {
        return switch (s.point()) {
            case FLIP_REVIEW -> new Rule(Route.CLOUD, "a reversed ruling needs an independent, stronger reader");
            case BIGBROTHER_CALL -> new Rule(Route.CLOUD, "the planner asked for the stronger model");
            case VERDICT_REVIEW -> s.verdictFlips() >= 1
                    ? new Rule(Route.CLOUD, "the verdict has changed " + s.verdictFlips() + " time(s) this turn")
                    : s.answerRefusals() >= 2
                            ? new Rule(Route.CLOUD, s.answerRefusals() + " endings were refused this turn")
                            : new Rule(null, "a first verdict with at most one refusal: either reviewer may do");
            case PLAN_AUTHOR -> s.planRefusals() >= 3
                    ? new Rule(Route.CLOUD, s.planRefusals() + " plans were refused this turn")
                    : s.planRefusals() <= 1 && s.answerRefusals() == 0
                            ? new Rule(Route.LOCAL, "early: at most one refused plan and no refused ending")
                            : new Rule(null, "some plans and endings were refused, but the loop is not stuck yet");
            case SYNTHESIS -> s.answerRefusals() >= 2 || s.verdictFlips() >= 1
                    ? new Rule(Route.CLOUD, "the answer is decisive and the agent's endings were refused or changed")
                    : new Rule(null, "a synthesis with no refused ending yet");
        };
    }

    private final DecisionClient jev;

    public ModelRouter() {
        this(new DecisionClient());
    }

    ModelRouter(DecisionClient jev) {
        this.jev = jev;
    }

    /** The route for this point, by the configured mode; logged and traced. */
    public Decision decide(Signals s) {
        Decision d = decideQuietly(s);
        if (mode() == Mode.OFF) {
            return d;   // not routing: nothing to log or trace
        }
        System.err.println("[Router] " + s.point() + " -> " + d.route() + " by " + d.by()
                + (d.confidence() == null ? "" : String.format(Locale.ROOT, " (%.2f)", d.confidence())) + ": " + d.why());
        trace(s, d);
        return d;
    }

    Decision decideQuietly(Signals s) {
        if (mode() == Mode.OFF) {   // the established behaviour, unchanged: no budget, no rules, no Jev
            return s.cloudAvailable() ? new Decision(Route.CLOUD, "off", "the hosted model whenever one is up", null)
                    : new Decision(Route.LOCAL, "off", "no hosted model is reachable", null);
        }
        Rule limit = limits(s);
        if (limit != null) {
            return new Decision(limit.route(), "limit", limit.why(), null);
        }
        Mode mode = mode();
        Rule rule = rules(s);
        if (mode != Mode.JEV_MAIN && rule.route() != null) {
            return new Decision(rule.route(), "rules", rule.why(), null);
        }
        if (mode == Mode.RULES) {
            return new Decision(Route.LOCAL, "rules", rule.why() + " — open, so local", null);
        }
        Decision byJev = askJev(s);
        if (byJev != null) {
            return byJev;
        }
        if (rule.route() != null) {
            return new Decision(rule.route(), "rules (Jev gave no confident answer)", rule.why(), null);
        }
        return new Decision(Route.LOCAL, "rules (Jev gave no confident answer)", rule.why() + " — open, so local", null);
    }

    /** Jev's choice when it answered with enough confidence, else null. */
    private Decision askJev(Signals s) {
        DecisionClient.Result r = jev.decide(stateFor(s), Map.of("route", new DecisionClient.Question.Choice(
                "Which model should handle this step?",
                Map.of("local", "The local model: enough for routine steps, and costs nothing; it is the model whose "
                                + "mistakes the agent is trying to correct.",
                        "cloud", "The hosted model: stronger and independent of the local one; worth its cost when the "
                                + "step decides the outcome or the local model keeps going wrong."))));
        DecisionClient.Answer a = r.ok() ? r.answers().get("route") : null;
        if (a == null || a.choice() == null) {
            lastJevError = r.error() == null ? "no choice in Jev's answer" : r.error();
            return null;
        }
        Double conf = a.confidence() != null ? a.confidence() : a.probabilities().get(a.choice());
        if (conf != null && conf < minJevConfidence()) {
            lastJevError = String.format(Locale.ROOT, "Jev chose %s at %.2f, below %.2f", a.choice(), conf, minJevConfidence());
            return null;
        }
        Route route = "cloud".equalsIgnoreCase(a.choice()) ? Route.CLOUD : Route.LOCAL;
        return new Decision(route, "jev", "Jev chose " + a.choice(), conf);
    }

    private volatile String lastJevError;

    private static final Random RANDOM = Long.getLong("hermit.router.seed") == null ? new Random()
            : new Random(Long.getLong("hermit.router.seed"));

    /** The share of model choices that try another model than the rule's: -Dhermit.router.explore (default 0.1). */
    static double exploreRate() {
        return Double.parseDouble(System.getProperty("hermit.router.explore", "0.1"));
    }

    /**
     * Which of {@code models} (catalogue ids) does this work (user, 2026-10-21, after Jev answered 68 choices without
     * once reaching its confidence bar): {@code fallback}, the model Hermit's rule gives, nine times in ten; one time in
     * ten, one of the other models, at random, so the drive also sees what they do. Every choice is traced.
     */
    public ModelChoice choose(Pick pick, Signals s, List<String> models, String fallback, String fallbackWhy) {
        List<String> others = models.stream().filter(m -> !m.equals(fallback)).toList();
        ModelChoice c;
        synchronized (RANDOM) {
            c = !others.isEmpty() && RANDOM.nextDouble() < exploreRate()
                    ? new ModelChoice(others.get(RANDOM.nextInt(others.size())), "exploration",
                            "one choice in ten tries another model than the rule's (" + fallback + ": " + fallbackWhy + ")", null)
                    : new ModelChoice(fallback, "rules", fallbackWhy, null);
        }
        System.err.println("[Router] " + pick + " -> " + c.model() + " by " + c.by() + ": " + c.why());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("type", "choice");
        row.put("pick", pick.name());
        row.put("iteration", s.iteration());
        row.put("answerRefusals", s.answerRefusals());
        row.put("planRefusals", s.planRefusals());
        row.put("models", models);
        row.put("model", c.model());
        row.put("by", c.by());
        row.put("why", c.why());
        appendTrace(row);
        return c;
    }

    /** The situation as Jev sees it: the step and the counts, nothing of the case. */
    static String stateFor(Signals s) {
        return "A code-analysis agent is driven by a local 27-billion-parameter model running on two laptops. At this step "
                + "it can use either that local model (free, one to four minutes per answer) or a large hosted model "
                + "(paid per token, stronger, about a minute per answer).\n"
                + "Step: " + s.point().description + ".\n"
                + "Iteration " + s.iteration() + (s.maxIterations() < 0 ? " (stepping mode; no iteration cap)" : " of at most " + s.maxIterations())
                + ". This turn the agent's endings were "
                + "refused " + s.answerRefusals() + " time(s), its verdict changed " + s.verdictFlips() + " time(s), and "
                + s.planRefusals() + " of its plans were refused by review. Hosted calls used so far: " + s.cloudCalls()
                + " of a budget of " + s.maxCloudCalls() + ".";
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A trace row for what a hosted call was sent (HostedBudget). */
    static void traceHostedInput(String point, int fullChars, int sentChars, int sourceFiles) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("type", "hostedInput");
        row.put("point", point);
        row.put("payloadChars", fullChars);
        row.put("sentChars", sentChars);
        row.put("approxTokens", (int) (sentChars / HostedBudget.CHARS_PER_TOKEN));
        row.put("sourceFiles", sourceFiles);
        row.put("cut", fullChars > sentChars);
        appendTrace(row);
    }

    private static void appendTrace(Map<String, Object> row) {
        String path = System.getProperty("hermit.router.trace");
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(path), JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Router] trace not written: " + e.getMessage());
        }
    }

    private void trace(Signals s, Decision d) {
        String path = System.getProperty("hermit.router.trace");
        if (path == null || path.isBlank()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("mode", mode().name());
        row.put("point", s.point().name());
        row.put("iteration", s.iteration());
        row.put("answerRefusals", s.answerRefusals());
        row.put("planRefusals", s.planRefusals());
        row.put("verdictFlips", s.verdictFlips());
        row.put("cloudCalls", s.cloudCalls());
        row.put("route", d.route().name());
        row.put("by", d.by());
        row.put("why", d.why());
        row.put("confidence", d.confidence());
        Rule rule = rules(s);
        row.put("rulesRoute", rule.route() == null ? null : rule.route().name());
        if (d.by().startsWith("rules (Jev")) {
            row.put("jevError", lastJevError);
        }
        try {
            Files.writeString(Path.of(path), JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Router] trace not written: " + e.getMessage());
        }
    }
}
