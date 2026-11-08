package hermit.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.checkpoint.TurnSnapshot;
import hermit.model.EndpointPolicy;
import hermit.model.HostedPlatforms;
import hermit.model.InferenceTrace;
import hermit.model.ProviderRoute;
import hermit.plan.PlanPromptBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The planner's history fitted to its budget in tiers (the owner, 2026-10-26): the newest half of the budget holds the
 * latest iterations whole ({@code -Dhermit.context.wholeShare}, 0.5), the budget up to 80% summaries of about 1,500
 * characters ({@code -Dhermit.context.mediumShare}, 0.8), the rest summaries of under 1,000.
 * Measured on 259 recorded planning steps, a 16k context kept 57% of the earlier results a plan needed when the oldest
 * iterations were dropped, and 78% in tiers.
 *
 * <p>The iterations are cut into chunks of 3,000 characters; each chunk is summarised once (cached by its hash, and in
 * {@code -Dhermit.context.summaries} when set, so a resumed run reuses them). A summary says it is one and names its
 * iteration; a chunk the summariser could not summarise keeps its start, marked so. Switched on by
 * {@code -Dhermit.context.tiered=true}; off, the history is fitted as before (oldest iterations dropped).
 */
public final class TieredContext {

    static final int CHUNK = 3000;
    static final int MEDIUM = 1500;
    static final int SHORT = 1000;
    private static final Pattern ITERATION = Pattern.compile("(?m)^\\[Iteration (\\d+)\\]");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] FAILED = new String[0];
    private static volatile TieredContext shared;

    private final Function<String, String[]> summariser;   // a chunk to {medium, short}; null when it failed
    private final Map<String, String[]> cache = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "history-summaries");
        t.setDaemon(true);
        return t;
    });

    TieredContext(Function<String, String[]> summariser) {
        this.summariser = summariser;
    }

    /** Whether the run fits its history in tiers. */
    public static boolean enabled() {
        return Boolean.getBoolean("hermit.context.tiered");
    }

    /** The run's tiered history, summarised by the hosted model of {@code -Dhermit.context.summaryModel}. */
    public static TieredContext shared() {
        if (shared == null) {
            synchronized (TieredContext.class) {
                if (shared == null) {
                    TieredContext t = new TieredContext(TieredContext::hostedSummary);
                    t.load(System.getProperty("hermit.context.summaries"));
                    shared = t;
                }
            }
        }
        return shared;
    }

    /** The context fitted to {@code maxChars}: the same text when it fits. */
    public String fit(String context, int maxChars) {
        return fit(context, maxChars, null);
    }

    /**
     * The context fitted to {@code maxChars}, served from the turn's frame stack when it has task steps
     * ({@link FrameStack}): the current step's work whole, the previous step's whole, the other closed steps' return
     * values, then the rest in tiers, newest first. Without task steps, the tiers alone. The same text when it fits.
     */
    public String fit(String context, int maxChars, TurnSnapshot live) {
        if (context == null || context.length() <= maxChars) {
            return context;
        }
        List<int[]> starts = new ArrayList<>();   // {offset, iteration}
        Matcher m = ITERATION.matcher(context);
        while (m.find()) {
            starts.add(new int[] {m.start(), Integer.parseInt(m.group(1))});
        }
        if (starts.isEmpty()) {
            return PlanPromptBuilder.fitContext(context, maxChars);
        }
        String head = context.substring(0, starts.get(0)[0]);
        head = head.length() > 2000 ? head.substring(0, 2000) + "…\n" : head;
        List<String[]> chunks = new ArrayList<>();   // {text, iteration, part}
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : context.length();
            String it = context.substring(from, to);
            for (int k = 0, part = 1; k < it.length(); k += CHUNK, part++) {
                chunks.add(new String[] {it.substring(k, Math.min(it.length(), k + CHUNK)), String.valueOf(starts.get(i)[1]),
                        String.valueOf(part)});
            }
        }
        int budget = maxChars - head.length();
        // at the runs' 60k budget whole chunks for half and medium summaries up to 80% kept the most (0.95 against 0.92
        // for thirds); at 16k thirds were a point ahead
        double whole = Double.parseDouble(System.getProperty("hermit.context.wholeShare", "0.5"));
        double medium = Double.parseDouble(System.getProperty("hermit.context.mediumShare", "0.8"));
        String[] kept = new String[chunks.size()];
        int used = 0;
        List<TurnSnapshot.Frame> returns = new ArrayList<>();
        List<TurnSnapshot.Frame> steps = live == null ? List.of() : FrameStack.steps(live);
        if (!steps.isEmpty()) {
            Map<Integer, Integer> stepOf = new HashMap<>();
            for (String[] c : chunks) {
                stepOf.computeIfAbsent(Integer.parseInt(c[1]), it -> FrameStack.stepOf(live, it));
            }
            int current = live.currentStep < live.taskPlan.size() ? live.currentStep : steps.get(steps.size() - 1).step;
            int previous = steps.stream().filter(f -> !f.open && f.step != current)
                    .max(Comparator.comparingInt(f -> f.lastIteration)).map(f -> f.step).orElse(-2);
            for (int want : new int[] {current, previous}) {   // the current step and the one before it, whole
                for (int j = chunks.size() - 1; j >= 0; j--) {
                    if (kept[j] == null && stepOf.get(Integer.parseInt(chunks.get(j)[1])) == want) {
                        int size = chunks.get(j)[0].length() + (chunks.get(j)[0].endsWith("\n") ? 0 : 1);
                        if (used + size <= budget) {
                            kept[j] = chunks.get(j)[0];
                            used += size;
                        }
                    }
                }
            }
            List<TurnSnapshot.Frame> closed = new ArrayList<>(steps.stream().filter(f -> !f.open && f.step != current
                    && f.returned != null).toList());
            closed.sort(Comparator.comparingInt((TurnSnapshot.Frame f) -> f.lastIteration).reversed());
            for (TurnSnapshot.Frame f : closed) {   // what the other steps returned, newest first
                if (used + f.returned.length() <= budget) {
                    returns.add(f);
                    used += f.returned.length();
                }
            }
            returns.sort(Comparator.comparingInt(f -> f.step));
        }
        for (int j = chunks.size() - 1; j >= 0; j--) {   // the rest in tiers, newest first
            if (kept[j] != null) {
                continue;
            }
            String[] c = chunks.get(j);
            String form = used < budget * whole ? c[0] : summarised(c, used < budget * medium ? MEDIUM : SHORT);
            int size = form.length() + (form.endsWith("\n") ? 0 : 1);   // a cut chunk gets a line break of its own
            if (used + size <= budget) {
                kept[j] = form;
                used += size;
            }
        }
        StringBuilder out = new StringBuilder(head);
        for (TurnSnapshot.Frame f : returns) {
            out.append(f.returned);
        }
        for (String k : kept) {
            if (k != null) {
                out.append(k);
                if (!k.endsWith("\n")) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    /** Summarises the chunks of an iteration that just ran, in the background, so fitting rarely waits. */
    public void prepare(String iterationText) {
        if (iterationText == null) {
            return;
        }
        for (int k = 0; k < iterationText.length(); k += CHUNK) {
            String chunk = iterationText.substring(k, Math.min(iterationText.length(), k + CHUNK));
            if (chunk.length() > SHORT && !cache.containsKey(hash(chunk))) {
                background.submit(() -> summaryOf(chunk));
            }
        }
    }

    /** The chunk in the form of its tier: a marked summary, or its marked start when no summary could be made. */
    private String summarised(String[] chunk, int limit) {
        if (chunk[0].length() <= limit) {
            return chunk[0];
        }
        String[] s = summaryOf(chunk[0]);
        String mark = "[summary of iteration " + chunk[1] + ", part " + chunk[2] + "] ";
        if (s == null || s.length < 2) {
            String start = "[start of iteration " + chunk[1] + ", part " + chunk[2] + ", the rest cut] ";
            return start + chunk[0].substring(0, Math.max(0, limit - start.length()));
        }
        String text = limit == MEDIUM ? s[0] : s[1];
        return mark + text.substring(0, Math.min(text.length(), limit - mark.length()));
    }

    private String[] summaryOf(String chunk) {
        String key = hash(chunk);
        String[] s = cache.get(key);
        if (s == null) {
            String[] made = summariser.apply(chunk);
            s = made == null ? FAILED : made;
            String[] before = cache.putIfAbsent(key, s);
            if (before == null && s != FAILED) {
                save(key, s);
            }
            s = before != null ? before : s;
        }
        return s == FAILED ? null : s;
    }

    /** The two summaries in a model's reply ({"medium": …, "short": …}), each cut to its length; null without them. */
    static String[] parseSummary(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(reply);
        if (!m.find()) {
            return null;
        }
        try {
            JsonNode d = MAPPER.readTree(m.group());
            String medium = d.path("medium").asText("");
            String brief = d.path("short").asText("");
            if (medium.isBlank() || brief.isBlank()) {
                return null;
            }
            return new String[] {medium.substring(0, Math.min(MEDIUM, medium.length())),
                    brief.substring(0, Math.min(SHORT, brief.length()))};
        } catch (IOException e) {
            return null;
        }
    }

    private static final String PROMPT = "Compress this piece of an agent's work log for its planner, who will read only "
            + "your summary. Keep, character for character, EVERY file name, path, class name, method name (Type#method), "
            + "line number and line range, command, status (Success/Error) and error message; drop prose, repeated output "
            + "and code bodies. Answer with ONE JSON object and nothing else: {\"medium\": \"<at most 1500 characters>\", "
            + "\"short\": \"<at most 900 characters>\"}.\n\nLOG PIECE:\n";

    /**
     * A chunk's summaries from the summary model (on the router, provider after provider; or on a local server, without a
     * key: the owner, 2026-10-27, overnight GPT-OSS-20B runs locally); null when it fails. The reasoning effort is
     * {@code -Dhermit.context.summaryReasoningEffort} (low): "none" for a model that thinks at any other effort.
     */
    static String[] hostedSummary(String chunk) {
        String model = System.getProperty("hermit.context.summaryModel", "openai/gpt-oss-20b:deepinfra");
        String url = System.getProperty("hermit.context.summaryUrl", "https://router.huggingface.co/v1");
        String platform = HostedPlatforms.platformOf(url);
        String key = platform == null ? null : HostedPlatforms.apiKey(platform);
        if (platform != null && key == null || !EndpointPolicy.allowed(url)) {
            return null;
        }
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = ProviderRoute.send(url, model, m -> {
                Map<String, Object> body = Map.of("model", m, "temperature", 0, "max_tokens", 2500,
                        "reasoning_effort", System.getProperty("hermit.context.summaryReasoningEffort", "low"),
                        "messages", List.of(Map.of("role", "user", "content", PROMPT + chunk)));
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(ProviderRoute.timeoutFor(url, m, 2500, Duration.ofSeconds(300)))   // a local server is shared
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
                if (key != null) {
                    req.header("Authorization", "Bearer " + key);
                }
                long started = System.nanoTime();
                HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                InferenceTrace.record(url, m, started, r);
                return r;
            });
            if (resp.statusCode() != 200) {
                return null;
            }
            return parseSummary(MAPPER.readTree(resp.body()).path("choices").path(0).path("message").path("content")
                    .asText(null));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void load(String file) {
        if (file == null || file.isBlank() || !Files.exists(Path.of(file))) {
            return;
        }
        try {
            for (String line : Files.readAllLines(Path.of(file))) {
                JsonNode d = MAPPER.readTree(line);
                cache.put(d.path("hash").asText(), new String[] {d.path("medium").asText(), d.path("short").asText()});
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[TieredContext] summaries not read from " + file + ": " + e.getMessage());
        }
    }

    private static void save(String key, String[] s) {
        String file = System.getProperty("hermit.context.summaries");
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            String line = MAPPER.writeValueAsString(Map.of("hash", key, "medium", s[0], "short", s[1])) + "\n";
            synchronized (TieredContext.class) {
                Files.writeString(Path.of(file), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            System.err.println("[TieredContext] summary not saved: " + e.getMessage());
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
