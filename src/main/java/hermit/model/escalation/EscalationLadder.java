package hermit.model.escalation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.model.ChatClient;
import hermit.model.EndpointPolicy;
import hermit.model.HostedPlatforms;
import hermit.model.InferenceTrace;
import hermit.model.ModelBudget;
import hermit.model.ModelRouter;
import hermit.model.ProviderRoute;
import hermit.tool.helper.HelperModelTool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The hosted escalation ladder: hosted models in a fixed order, the cheapest first, and the rules that pick the rung
 * for each job Hermit gives a hosted model. {@code -Dhermit.ladder=<model,…>} turns it on (catalogue ids, see
 * {@link HostedPlatforms}; each must also pass {@code -Dhermit.cloud.models}). With it on, the plan author, the verdict
 * and reversed-ruling reviewers, the stuck synthesis, the BigBrother tool and the hosted code reviewer all go through
 * it.
 *
 * <ul>
 *   <li>Every job starts on the lowest rung. A call that fails (error, timeout, empty reply) is sent again one rung up
 *       at once, and the job stays on that rung for the rest of the turn.</li>
 *   <li>A job whose last output was refused starts its next call one rung above the rung that wrote it: a plan refused
 *       after it was authored, a synthesized answer refused, or a reviewer's approval followed by a refusal.</li>
 *   <li>Plans follow the same rule, so each refused authored plan hands the next one to the rung above: Luna, then
 *       DeepSeek, then Sol, then Astra. {@code -Dhermit.ladder.planFloors} can add a floor by the number of plans
 *       refused this turn (e.g. {@code 3:gpt-6.1-sol}); there is none by default.</li>
 *   <li>A code review starts at least on the rung {@code -Dhermit.ladder.reviewFloors} names for the code's branch
 *       count (default {@code 8:gpt-6.1-sol,15:gpt-6-astra}).</li>
 *   <li>A rung with a per-run limit ({@code -Dhermit.ladder.maxCalls}, default
 *       {@code gpt-6.1-sol:10,gpt-6-astra:3}) that is spent, or a rung this run cannot reach (no key, outside the
 *       hosted allow-list), passes its calls to the next rung down; only when nothing below can answer does the call
 *       go up instead.</li>
 *   <li>A rung named in {@code -Dhermit.ladder.flex} is asked on OpenAI's Flex tier (half price, slower); when Flex
 *       has no capacity (HTTP 429, not billed) the same call is sent again at once on the standard tier.</li>
 *   <li>A picked rung named in {@code -Dhermit.ladder.switch} (default
 *       {@code gpt-6.1-sol:0.5,gpt-6-astra:0.5}) hands the call to {@code -Dhermit.ladder.switchTo} (default
 *       {@code gpt-6-luna}) with that chance, to keep the bills down. The job stays on the picked rung: a switched
 *       answer refused later has the next call pick it again, and a switched call that fails goes to the picked rung
 *       at once. The jobs in {@code -Dhermit.ladder.switchExempt} (default {@code task_plan,initial_plan}) are never
 *       switched. While the file {@code -Dhermit.ladder.switchOff} names exists, no pick is switched; the ladder looks
 *       for it on every call, so a stepping run can turn the switch off and on between two steps.</li>
 *   <li>With {@code -Dhermit.openai.via=codex} every OpenAI rung is asked through the Codex CLI under the user's
 *       ChatGPT plan instead of the API (see {@link CodexCliClient}); Flex does not apply there. While the plan's limit
 *       is used up, a rung's call goes to its stand-in, or waits for the reset.</li>
 * </ul>
 * No call waits longer than {@link #TIMEOUT_SECONDS}. Every call is appended to {@code -Dhermit.ladder.trace} (JSONL)
 * with the job, the rung, why it was chosen, the tier OpenAI reports, the tokens and the estimated cost.
 */
public final class EscalationLadder {
    /** The jobs Hermit gives a hosted model. */
    public enum Job {
        PLAN_AUTHOR, INITIAL_PLAN, PLAN_REVIEW, VERDICT_REVIEW, FLIP_REVIEW, SYNTHESIS, BIGBROTHER, CODE_REVIEW,
        TASK_PLAN, STEP_DONE, PLAN_AHEAD, PLAN_REVISE,
        // the task plan (user, 2026-10-21): written, judged, looked ahead, revised
        FOLLOWER_HELP,   // the corrective plan for an instruction the follower could not carry out (user, 2026-10-25)
        STEP_PLAN, LENS_REVIEW   // the run's own model's jobs (the step plan, the review lenses), hosted only when named
    }

    /** One rung: a catalogue model, its platform, whether it is asked on Flex, and its per-run call limit (0 = none). */
    public record Rung(String model, String platform, boolean flex, int maxCalls) {}

    /** A hosted answer: the text, the model that wrote it, the tier it ran on, and why that rung was chosen. */
    public record Reply(String text, String model, String tier, String why) {}

    public static final int TIMEOUT_SECONDS = 240;   // the user's ceiling for a hosted call: 4 minutes

    static final String DEFAULT_PLAN_FLOORS = "";   // plans climb one rung per refused authored plan, like every job
    static final String DEFAULT_REVIEW_FLOORS = "8:gpt-6.1-sol,15:gpt-6-astra";
    static final String DEFAULT_MAX_CALLS = "gpt-6.1-sol:10,gpt-6-astra:3";
    // the user (2026-10-25): half as often, Luna instead
    static final String DEFAULT_SWITCH = "gpt-6.1-sol:0.5,gpt-6-astra:0.5";
    static final String DEFAULT_SWITCH_TO = "gpt-6-luna";
    // the user (2026-10-25): straight to Astra
    static final String DEFAULT_SWITCH_EXEMPT = "task_plan,initial_plan";

    /** List prices per 1M tokens (input, cached input, output), 2026-10-15; DeepSeek at its peak rate. Flex is half. */
    private static final Map<String, double[]> PRICES = Map.of(
            "gpt-6-luna", new double[] {0.10, 0.01, 0.50},
            "deepseek-flash", new double[] {0.30, 0.006, 1.20},
            "gpt-6.1-sol", new double[] {2.00, 0.10, 10.00},
            "gpt-6-astra", new double[] {10.00, 1.00, 50.00});

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile EscalationLadder shared;

    private final List<Rung> rungs;
    private final Map<Job, List<Rung>> jobRungs;   // a job's own order (-Dhermit.ladder.job.<job>), else the ladder's
    private final TreeMap<Integer, Integer> planFloors;
    private final TreeMap<Integer, Integer> reviewFloors;
    // a picked rung's chance to hand its call to switchTo
    private final Map<String, Double> switchChance;
    private final String switchTo;
    private final Set<Job> switchExempt;
    private final CodexCliClient codex;   // null: OpenAI's rungs are asked through the API
    private final Map<String, Integer> calls = new HashMap<>();
    private final Map<Job, JobState> jobs = new EnumMap<>(Job.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String lastLabel = "the escalation ladder";
    private String lastFailure;

    /** Where a job stands this turn. */
    private static final class JobState {
        int level;                  // raised only by failed calls; sticky for the turn
        int lastUsed = -1;          // the rung picked for the job's last output
        boolean lastSwitched;       // that output was written by the switch target in its place
        int refusalsAtCall;         // the job's refusal count when that output was written
        boolean reviewApproved;     // reviewers: whether the last review approved
        int refusalsAtReview;
    }

    EscalationLadder(List<Rung> rungs, TreeMap<Integer, Integer> planFloors,
                     TreeMap<Integer, Integer> reviewFloors) {
        this(rungs, Map.of(), planFloors, reviewFloors, Map.of(), DEFAULT_SWITCH_TO, Set.of(), null);
    }

    EscalationLadder(List<Rung> rungs, Map<Job, List<Rung>> jobRungs,
                     TreeMap<Integer, Integer> planFloors,
                     TreeMap<Integer, Integer> reviewFloors, Map<String, Double> switchChance,
                     String switchTo, Set<Job> switchExempt, CodexCliClient codex) {
        this.rungs = List.copyOf(rungs);
        this.jobRungs = Map.copyOf(jobRungs);
        this.planFloors = planFloors;
        this.reviewFloors = reviewFloors;
        this.switchChance = Map.copyOf(switchChance);
        this.switchTo = switchTo;
        this.switchExempt = Set.copyOf(switchExempt);
        this.codex = codex;
    }

    /** The run's ladder from the system properties; a disabled ladder when {@code -Dhermit.ladder} is unset. */
    public static EscalationLadder shared() {
        EscalationLadder l = shared;
        if (l == null) {
            synchronized (EscalationLadder.class) {
                if (shared == null) {
                    shared = fromSystemProperties();
                }
                l = shared;
            }
        }
        return l;
    }

    /** Tests: drop the shared ladder so the next {@link #shared()} reads the properties again. */
    public static synchronized void resetShared() {
        shared = null;
    }

    static EscalationLadder fromSystemProperties() {
        List<Rung> rungs = new ArrayList<>();
        String spec = System.getProperty("hermit.ladder", "");
        List<String> flex = names(System.getProperty("hermit.ladder.flex", ""));
        Map<String, Integer> limits = new HashMap<>();
        for (String[] kv : pairs(System.getProperty("hermit.ladder.maxCalls", DEFAULT_MAX_CALLS))) {
            limits.put(kv[0], Integer.parseInt(kv[1]));
        }
        rungs.addAll(rungs(spec, flex, limits));
        Map<Job, List<Rung>> jobRungs = new EnumMap<>(Job.class);
        for (Job job : Job.values()) {
            List<Rung> own = rungs(System.getProperty(
                    "hermit.ladder.job." + job.name().toLowerCase(Locale.ROOT), ""), flex, limits);
            if (!own.isEmpty()) {
                jobRungs.put(job, own);
            }
        }
        Map<String, Double> switchChance = new HashMap<>();
        for (String[] kv : pairs(System.getProperty("hermit.ladder.switch", DEFAULT_SWITCH))) {
            switchChance.put(kv[0], Double.parseDouble(kv[1]));
        }
        Set<Job> switchExempt = EnumSet.noneOf(Job.class);
        for (String name : names(System.getProperty("hermit.ladder.switchExempt", DEFAULT_SWITCH_EXEMPT))) {
            switchExempt.add(Job.valueOf(name.toUpperCase(Locale.ROOT)));
        }
        return new EscalationLadder(rungs, jobRungs,
                floors(System.getProperty("hermit.ladder.planFloors", DEFAULT_PLAN_FLOORS),
                        jobRungs.getOrDefault(Job.PLAN_AUTHOR, rungs)),
                floors(System.getProperty("hermit.ladder.reviewFloors", DEFAULT_REVIEW_FLOORS),
                        jobRungs.getOrDefault(Job.CODE_REVIEW, rungs)),
                switchChance, System.getProperty("hermit.ladder.switchTo", DEFAULT_SWITCH_TO).strip(), switchExempt,
                CodexCliClient.fromSystemProperties());
    }

    private static List<Rung> rungs(String csv, List<String> flex, Map<String, Integer> limits) {
        List<Rung> out = new ArrayList<>();
        for (String id : names(csv)) {
            HostedPlatforms.CloudModel m = HostedPlatforms.modelById(id);
            if (m == null) {
                System.err.println("[Ladder] " + id
                        + " is not in the hosted catalogue or not allowed by -Dhermit.cloud.models — left out");
                continue;
            }
            out.add(new Rung(id, m.platform(), flex.contains(id), limits.getOrDefault(id, 0)));
        }
        return out;
    }

    private static List<String> names(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    private static List<String[]> pairs(String csv) {
        List<String[]> out = new ArrayList<>();
        for (String s : names(csv)) {
            int colon = s.indexOf(':');
            if (colon > 0) {
                out.add(new String[] {s.substring(0, colon).strip(), s.substring(colon + 1).strip()});
            }
        }
        return out;
    }

    /** "3:gpt-6.1-sol,4:gpt-6-astra" → {3 → index of sol, 4 → index of astra}; a model not on the ladder is ignored. */
    static TreeMap<Integer, Integer> floors(String csv, List<Rung> rungs) {
        TreeMap<Integer, Integer> out = new TreeMap<>();
        for (String[] kv : pairs(csv)) {
            for (int i = 0; i < rungs.size(); i++) {
                if (rungs.get(i).model().equals(kv[1])) {
                    out.put(Integer.parseInt(kv[0]), i);
                }
            }
        }
        return out;
    }

    public boolean enabled() {
        return !rungs.isEmpty();
    }

    public List<Rung> rungs() {
        return rungs;
    }

    /** The order a job walks: its own (-Dhermit.ladder.job.<job>) when set, else the ladder's. */
    public List<Rung> rungsFor(Job job) {
        return jobRungs.getOrDefault(job, rungs);
    }

    public boolean hasOwnRungs(Job job) {
        return jobRungs.containsKey(job);
    }

    /**
     * A job the run's own model does ({@link Job#STEP_PLAN}, {@link Job#LENS_REVIEW}), asked of the job's own rungs
     * instead when {@code -Dhermit.ladder.job.<job>} names some (the owner, 2026-10-27: Luna through Codex in place of the
     * router). Null when the job names none or none of them answered: the run's model does it then.
     */
    public Reply askInstead(Job job, int refusals, String prompt, int maxTokens) {
        return enabled() && hasOwnRungs(job) ? ask(job, refusals, 0, prompt, maxTokens) : null;
    }

    /** The index of a model in the job's order, or -1. */
    public int rungIndex(Job job, String model) {
        List<Rung> rs = rungsFor(job);
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i).model().equals(model)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The user (2026-10-26): the switch stays on by default and can be held off between steps: while the file
     * {@code -Dhermit.ladder.switchOff} names exists, no pick is switched.
     */
    static boolean switchHeldOff() {
        String path = System.getProperty("hermit.ladder.switchOff", "");
        return !path.isBlank() && Files.exists(Path.of(path.strip()));
    }

    /** The chance that a call picked for this model goes to the switch target instead (0 when it is not named). */
    public double switchChance(String model) {
        return switchChance.getOrDefault(model, 0.0);
    }

    /** The switch target's rung: from the job's own order, else from the ladder; null when neither has it. */
    private Rung switchRung(Job job) {
        for (List<Rung> rs : List.of(rungsFor(job), rungs)) {
            for (Rung r : rs) {
                if (r.model().equals(switchTo)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** A new turn: every job starts on the lowest rung again (the per-run limits carry over). */
    public synchronized void newTurn() {
        jobs.clear();
    }

    /** The lowest rung a plan is written on after this many refused plans this turn. */
    public int planFloor(int planRefusals) {
        Map.Entry<Integer, Integer> e = planFloors.floorEntry(planRefusals);
        return e == null ? 0 : e.getValue();
    }

    /** The lowest rung code with this many branches is reviewed on. */
    public int reviewFloor(int branches) {
        Map.Entry<Integer, Integer> e = reviewFloors.floorEntry(branches);
        return e == null ? 0 : e.getValue();
    }

    /** A reviewer's verdict, so an approval that is refused later moves the next review up a rung. */
    public synchronized void reviewed(Job job, boolean approved, int refusals) {
        JobState st = jobs.computeIfAbsent(job, j -> new JobState());
        st.reviewApproved = approved;
        st.refusalsAtReview = refusals;
    }

    public synchronized String lastLabel() {
        return lastLabel;
    }

    public synchronized String lastFailure() {
        return lastFailure;
    }

    /** Calls made on this rung this run. */
    public synchronized int callsOn(String model) {
        return calls.getOrDefault(model, 0);
    }

    /**
     * Asks the ladder. {@code refusals} is the job's refusal count this turn (plans refused for the plan author,
     * endings refused for the others); {@code floor} the lowest rung the job's own rule allows. Null when no rung
     * answered.
     */
    public Reply ask(Job job, int refusals, int floor, String prompt, int maxTokens) {
        if (!enabled()) {
            return null;
        }
        List<Rung> rungs = rungsFor(job);   // the job's own order, or the ladder's
        int level;
        String why;
        Rung switched = null;
        synchronized (this) {
            JobState st = jobs.computeIfAbsent(job, j -> new JobState());
            level = st.level;
            why = level > 0 ? "the job moved up after a failed call" : "the lowest rung";
            boolean refused = job == Job.VERDICT_REVIEW || job == Job.FLIP_REVIEW
                    ? st.reviewApproved && refusals > st.refusalsAtReview
                    : st.lastUsed >= 0 && refusals > st.refusalsAtCall;
            int next = st.lastSwitched ? st.lastUsed : st.lastUsed + 1;
            // a switched answer refused: the same pick again
            if (refused && next > level) {
                level = next;
                why = "its last output (" + (st.lastSwitched ? switchTo + " for " : "")
                        + rungs.get(st.lastUsed).model() + ") was refused";
            }
            if (floor > level) {
                level = floor;
                why = job == Job.PLAN_AUTHOR ? refusals + " plans were refused this turn"
                        : job == Job.CODE_REVIEW ? "the code's complexity" : "the job's floor";
            }
            level = Math.min(level, rungs.size() - 1);
            int down = level;
            while (down >= 0 && !usable(rungs.get(down))) {
                down--;
            }
            int up = level + 1;
            while (down < 0 && up < rungs.size() && !usable(rungs.get(up))) {
                up++;
            }
            if (down != level) {
                why += "; " + rungs.get(level).model()
                        + (spent(rungs.get(level)) ? " has spent its calls" : " is not reachable");
                level = down >= 0 ? down : up < rungs.size() ? up : -1;
                // down first; up only when nothing below answers
            }
            if (level < 0) {
                lastFailure = "no rung of the ladder is reachable or within its limit";
                return null;
            }
            double chance = switchExempt.contains(job) ? 0 : switchChance(rungs.get(level).model());
            if (chance > 0 && switchHeldOff()) {
                chance = 0;
                why += "; the switch is off (" + System.getProperty("hermit.ladder.switchOff").strip() + " exists)";
            }
            Rung target = chance > 0 ? switchRung(job) : null;
            if (target != null && !target.model().equals(rungs.get(level).model()) && usable(target)
                    && ThreadLocalRandom.current().nextDouble() < chance) {
                switched = target;
                why += "; " + rungs.get(level).model() + " switched to " + target.model() + " ("
                        + Math.round(chance * 100) + "% of its picks)";
            }
        }
        if (switched != null) {
            Reply reply = call(job, switched, prompt, maxTokens, why);
            if (reply != null) {
                synchronized (this) {
                    JobState st = jobs.get(job);
                    st.lastUsed = level;
                    st.lastSwitched = true;
                    st.refusalsAtCall = refusals;
                    lastLabel = switched.model() + " (" + reply.tier() + ", for "
                            + rungs.get(level).model() + ")";
                }
                return reply;
            }
            why = switched.model() + " failed in " + rungs.get(level).model() + "'s place ("
                    + lastFailure() + ")";
        }
        for (int i = level; i < rungs.size(); i++) {
            Rung r = rungs.get(i);
            if (i > level && !usable(r)) {
                continue;
            }
            Reply reply = call(job, r, prompt, maxTokens, why);
            if (reply != null) {
                synchronized (this) {
                    JobState st = jobs.get(job);
                    if (i > level) {
                        st.level = Math.max(st.level, i);
                        // a failed call moves the job up for the rest of the turn
                    }
                    st.lastUsed = i;
                    st.lastSwitched = false;
                    st.refusalsAtCall = refusals;
                    lastLabel = r.model() + " (" + reply.tier() + ")";
                }
                return reply;
            }
            why = r.model() + " failed (" + lastFailure() + ")";
        }
        return null;
    }

    private synchronized boolean spent(Rung r) {
        return r.maxCalls() > 0 && calls.getOrDefault(r.model(), 0) >= r.maxCalls();
    }

    private boolean usable(Rung r) {
        if (viaCodex(r)) {
            return !spent(r) && EndpointPolicy.allowed(CodexCliClient.ENDPOINT);   // the plan's login, no API key
        }
        return !spent(r) && HostedPlatforms.apiKey(r.platform()) != null
        && EndpointPolicy.allowed(HostedPlatforms.baseUrl(r.platform()));
    }

    private boolean viaCodex(Rung r) {
        return codex != null && HostedPlatforms.OPENAI.equals(r.platform());
    }

    /** One call on one rung (Flex first when the rung asks for it, standard when Flex has no capacity). */
    private Reply call(Job job, Rung r, String prompt, int maxTokens, String why) {
        if (viaCodex(r)) {
            return callCodex(job, r, prompt, maxTokens, why);
        }
        synchronized (this) {
            calls.merge(r.model(), 1, Integer::sum);
        }
        ModelRouter.countCloudCall();
        boolean openai = HostedPlatforms.OPENAI.equals(r.platform());
        String url = HostedPlatforms.baseUrl(r.platform()) + "/chat/completions";
        int tokens = Math.max(maxTokens, HelperModelTool.hostedMinTokens());
        String sent = ModelBudget.fit(r.model(), prompt);   // a rung with an input budget of its own (GPT-OSS) gets no more
        long started = System.nanoTime();
        try {
            EndpointPolicy.requireAllowed(url);
            HttpResponse<String> resp = post(url, r, sent, tokens, openai && r.flex());
            String tierNote = null;
            if (resp.statusCode() == 429 && openai && r.flex()) {
                tierNote = "flex had no capacity";
                resp = post(url, r, sent, tokens, false);
            }
            if (ChatClient.refusedToolCall(resp)) {   // GPT-OSS answered with a tool call; ask once more
                resp = post(url, r, sent, tokens, openai && r.flex() && tierNote == null);
            }
            if (resp.statusCode() != 200) {
                return fail(job, r, why, started, "HTTP " + resp.statusCode() + ": " + excerpt(resp.body()));
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode choice = root.path("choices").path(0);
            String text = choice.path("message").path("content").asText("");
            String tier = openai ? root.path("service_tier").asText(r.flex() && tierNote == null ? "flex" : "default") : "standard";
            if (tierNote != null) {
                tier += " (" + tierNote + ")";
            }
            JsonNode usage = root.path("usage");
            if (text.isBlank()) {
                return fail(job, r, why, started, emptyReply(choice));
            }
            trace(job, r, why, tier, started, true, null, usage);
            System.err.println("[Ladder] " + job + " -> " + r.model() + " (" + tier + "): " + why);
            return new Reply(text, r.model(), tier, why);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fail(job, r, why, started, e.getClass().getSimpleName() + ": " + excerpt(String.valueOf(e.getMessage())));
        }
    }

    /**
     * One call on an OpenAI rung through Codex, under the plan's limits. While a used-up limit holds, the rung's
     * DeepSeek stand-in answers, or the call waits for the reset when there is none or it cannot be reached.
     */
    private Reply callCodex(Job job, Rung r, String prompt, int maxTokens, String why) {
        int busy = 0;
        while (true) {
            long until = codex.blockedUntil();
            if (until > 0) {
                String held = "the Codex plan limit holds until " + CodexCliClient.clockTime(until);
                for (String standIn : codex.standIns(r.model())) {
                    String platform = HostedPlatforms.catalogPlatform(standIn);
                    Rung stand = platform == null ? null : new Rung(standIn, platform, false, 0);
                    if (stand == null || !usable(stand)) {
                        continue;
                    }
                    Reply reply = call(job, stand, prompt, maxTokens, why + "; " + held + ", so " + standIn + " answers");
                    if (reply != null) {
                        return new Reply(reply.text(), reply.model(), standIn + " standing in, " + reply.tier(), reply.why());
                    }
                    why += "; " + standIn + " failed (" + lastFailure() + ")";
                }
                codex.waitUntil(until);
                continue;
            }
            synchronized (this) {
                calls.merge(r.model(), 1, Integer::sum);
            }
            ModelRouter.countCloudCall();
            long started = System.nanoTime();
            CodexCliClient.Outcome out = codex.ask(r.model(), ModelBudget.fit(r.model(), prompt));
            if (out.limit()) {
                why += "; " + out.model() + " met the plan's limit (" + excerpt(out.error()) + ")";
                trace(job, r, why, "codex " + out.model(), started, false, out.error(), null);
                continue;
            }
            if (CodexCliClient.busy(out.error()) && busy < CodexCliClient.busyRetries()) {
                long wait = CodexCliClient.busyWait(busy++);   // the same rung again, not the next one
                why += "; " + out.model() + " had too many requests, asked again after " + wait + " s";
                trace(job, r, why, "codex " + out.model(), started, false, out.error(), null);
                codex.pause(wait);
                continue;
            }
            if (out.error() != null) {
                return fail(job, r, why, started, "Codex " + out.model() + ": " + excerpt(out.error()));
            }
            String tier = "codex " + out.model();
            InferenceTrace.record(CodexCliClient.ENDPOINT, out.model(), started, 200, out.usage());
            trace(job, r, why, tier, started, true, null, out.usage());
            System.err.println("[Ladder] " + job + " -> " + r.model() + " (" + tier + "): " + why);
            return new Reply(out.text(), r.model(), tier, why);
        }
    }

    private HttpResponse<String> post(String url, Rung r, String prompt, int tokens, boolean flex)
            throws IOException, InterruptedException {
        return ProviderRoute.send(url, r.model(), model -> postAs(url, r, model, prompt, tokens, flex),   // the owner, 2026-10-26
                resp -> {
                    JsonNode choice = MAPPER.readTree(resp.body()).path("choices").path(0);
                    return choice.path("message").path("content").asText("").isBlank() ? emptyReply(choice) : null;
                });
    }

    /**
     * An answer without text, with its finish reason and what its message held instead (a tool call, reasoning), so
     * that the cause of a provider's empty replies shows in the trace (the owner, 2026-10-27).
     */
    static String emptyReply(JsonNode choice) {
        JsonNode message = choice.path("message");
        List<String> held = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            held.add("a tool call (" + call.path("function").path("name").asText("?") + ")");
        }
        for (String field : List.of("reasoning_content", "reasoning")) {
            int chars = message.path(field).asText("").length();
            if (chars > 0) {
                held.add(field + " of " + chars + " characters");
            }
        }
        return "empty reply (finish_reason " + choice.path("finish_reason").asText("?")
                + (held.isEmpty() ? "" : "; it held " + String.join(", ", held)) + ")";
    }

    /** One call of the rung with {@code model}: on the router, the rung's model named with one provider. */
    private HttpResponse<String> postAs(String url, Rung r, String model, String prompt, int tokens, boolean flex)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (HostedPlatforms.OPENAI.equals(r.platform())) {
            body.put("max_completion_tokens", tokens);   // OpenAI's newer models refuse max_tokens and any temperature but 1
            if (flex) {
                body.put("service_tier", "flex");
            }
        } else {
            body.put("max_tokens", tokens);
            if (HostedPlatforms.DEEPSEEK.equals(r.platform())) {
                // DeepSeek thinks by default (high effort) and can spend the whole output budget on it, leaving an empty
                // reply: off unless -Dhermit.ladder.deepseekThinking=enabled
                body.put("thinking", Map.of("type", System.getProperty("hermit.ladder.deepseekThinking", "disabled")));
            }
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + HostedPlatforms.apiKey(r.platform()))
                .timeout(ProviderRoute.timeoutFor(url, model, tokens, Duration.ofSeconds(TIMEOUT_SECONDS)))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        InferenceTrace.record(HostedPlatforms.baseUrl(r.platform()), model, started, resp);
        return resp;
    }

    private Reply fail(Job job, Rung r, String why, long started, String failure) {
        synchronized (this) {
            lastFailure = r.model() + ": " + failure;
        }
        System.err.println("[Ladder] " + job + " -> " + r.model() + " FAILED: " + failure);
        trace(job, r, why, null, started, false, failure, null);
        return null;
    }

    /** The estimated cost of a call at list prices (Flex half); cache writes are not counted. */
    static double estimatedUsd(String model, String tier, int prompt, int cached, int completion) {
        double[] p = PRICES.get(model);
        if (p == null || tier != null && tier.startsWith("codex")) {   // Codex: the plan's allowance, not a bill
            return 0;
        }
        double usd = ((prompt - cached) * p[0] + cached * p[1] + completion * p[2]) / 1_000_000.0;
        return tier != null && tier.startsWith("flex") ? usd / 2 : usd;
    }

    private void trace(Job job, Rung r, String why, String tier, long started, boolean ok, String failure,
                       JsonNode usage) {
        String path = System.getProperty("hermit.ladder.trace");
        if (path == null || path.isBlank()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", Instant.now().toString());
        row.put("job", job.name());
        row.put("model", r.model());
        row.put("why", why);
        row.put("ok", ok);
        row.put("seconds", Math.round((System.nanoTime() - started) / 1e8) / 10.0);
        if (tier != null) {
            row.put("tier", tier);
        }
        if (failure != null) {
            row.put("error", failure);
        }
        if (usage != null && !usage.isMissingNode()) {
            int prompt = usage.path("prompt_tokens").asInt();
            int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt();
            int completion = usage.path("completion_tokens").asInt();
            row.put("promptTokens", prompt);
            row.put("cachedTokens", cached);
            row.put("completionTokens", completion);
            row.put("reasoningTokens", usage.path("completion_tokens_details").path("reasoning_tokens").asInt());
            row.put("estimatedUsd", Math.round(estimatedUsd(r.model(), tier, prompt, cached, completion) * 1e6) / 1e6);
        }
        try {
            Files.writeString(Path.of(path), MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Ladder] trace not written: " + e.getMessage());
        }
    }

    private static String excerpt(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    @Override
    public String toString() {
        List<String> names = new ArrayList<>();
        for (Rung r : rungs) {
            names.add(r.model() + (r.flex() ? " (flex)" : "") + (r.maxCalls() > 0 ? " ≤" + r.maxCalls() : ""));
        }
        return String.join(" < ", names).toLowerCase(Locale.ROOT);
    }
}
