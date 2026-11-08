package hermit.model.escalation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI's models through the Codex CLI under the user's ChatGPT login (user, 2026-10-25): with
 * {@code -Dhermit.openai.via=codex} every OpenAI rung of the {@link EscalationLadder} runs as {@code codex exec}, so
 * its calls count against the plan's limits instead of being billed by the API.
 *
 * <ul>
 *   <li>A call runs {@code codex exec} read-only, in an empty directory, without the user's Codex configuration and
 *       without persisting a session; the prompt goes on stdin and short instructions replace Codex's own, so the model
 *       answers from the prompt alone. The reply is the last agent message of the {@code --json} events.</li>
 *   <li>A rung is asked on its tier's newest model the plan serves: the tier is the last part of the id
 *       ({@code luna}, {@code sol}, {@code astra}) and its newest model the one with the highest version that
 *       {@code codex debug models} lists. {@code -Dhermit.codex.models=sol:gpt-5.6-sol,…} pins one.
 *       {@code -Dhermit.codex.effort} sets the reasoning effort (default {@code low}; one effort, or
 *       {@code tier:effort} pairs).</li>
 *   <li>The plan's limits come from the Codex app server ({@code account/rateLimits/read}): before a call, at most
 *       every five minutes, and after every failed call. While a window is used up the limit holds until a minute past
 *       its reset: the ladder hands the call to the tier's stand-in ({@code -Dhermit.codex.standIn}, default
 *       {@code luna:deepseek-flash,sol:deepseek-v4-pro,astra:deepseek-v4-pro}), then, when that one cannot answer, to
 *       {@code -Dhermit.codex.standInLast} (default GPT-OSS-120B on the Hugging Face router; empty for none); it waits
 *       for the reset only when no stand-in answers. Hermit never spends the plan's reset credits.</li>
 *   <li>A call that meets Codex's limit on requests in a short time (429 Too Many Requests) is asked again on the same
 *       model after a pause, up to {@code -Dhermit.codex.busyRetries} times (3), the pause
 *       {@code -Dhermit.codex.busyWaitSec} (30 s) doubled each time; only then does it fail.</li>
 *   <li>No call waits longer than {@code -Dhermit.codex.timeoutSec} (default and ceiling: the ladder's 4 minutes).</li>
 * </ul>
 */
public final class CodexCliClient {

    /** Where Codex sends a ChatGPT login's calls: a local-only run must name its host in the allow-list. */
    public static final String ENDPOINT = "https://chatgpt.com/backend-api/codex";

    static final LongSupplier SYSTEM_CLOCK = () -> System.currentTimeMillis() / 1000;
    static final LongConsumer SYSTEM_SLEEPER = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
    /** Seconds since the epoch, and how a wait sleeps (milliseconds); tests replace both. */
    static volatile LongSupplier clock = SYSTEM_CLOCK;
    static volatile LongConsumer sleeper = SYSTEM_SLEEPER;

    // the user (2026-10-25): DeepSeek Pro for Sol and Astra, Flash for Luna
    static final String DEFAULT_STAND_IN = "luna:deepseek-flash,sol:deepseek-v4-pro,astra:deepseek-v4-pro";
    // the user (2026-10-26): when DeepSeek cannot be reached, GPT-OSS-120B on the Hugging Face router
    static final String DEFAULT_STAND_IN_LAST = "openai/gpt-oss-120b:preferred";
    static final int LIMITS_EVERY_SECONDS = 300;
    static final int UNKNOWN_RESET_SECONDS = 900;   // a limit Codex names without the app server telling when it resets
    static final String INSTRUCTIONS = "You answer the request you are given from its text alone. Do not run commands, "
            + "read files or call tools. Reply with the answer only, in the form the request asks for.\n";
    private static final Pattern LIMIT_MESSAGE = Pattern.compile("(?i)usage limit|usage_limit|rate_limit_reached");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One call's outcome: the reply and its token counts, or the error and whether a used-up limit caused it. */
    public record Outcome(String text, String model, JsonNode usage, String error, boolean limit) {}

    /** The plan's limits: each window's use in percent and its reset (seconds since the epoch). */
    public record Limits(Boolean allowed, int primaryPercent, long primaryResetsAt, int secondaryPercent,
                         long secondaryResetsAt) {

        /** When ordinary use is allowed again: 0 when it is now, -1 when it is not and no reset is known. */
        public long blockedUntil(long now) {
            long until = 0;
            if (primaryPercent >= 100) {
                until = Math.max(until, primaryResetsAt);
            }
            if (secondaryPercent >= 100) {
                until = Math.max(until, secondaryResetsAt);
            }
            if (until == 0 && Boolean.FALSE.equals(allowed)) {
                until = primaryResetsAt > now ? primaryResetsAt : -1;
            }
            return until > now || until < 0 ? until : 0;
        }
    }

    private final String bin;
    private final Map<String, String> pinned;
    private final Map<String, String> efforts;
    private final String effort;
    private final Map<String, String> standIns;
    private final String standInLast;
    private final int timeoutSeconds;
    private Map<String, String> newest;   // tier → its newest listed model, read once
    private Path home;                    // the empty working directory and the instructions file
    private long blocked;                 // a used-up limit holds until then (seconds since the epoch)
    private long limitsReadAt;            // when they were last read (0: never)
    private Limits lastLimits;
    private long waitAnnounced;           // the time the last "waiting" line named

    CodexCliClient(String bin, Map<String, String> pinned, String effortSpec, Map<String, String> standIns,
                   String standInLast, int timeoutSeconds) {
        this.bin = bin;
        this.pinned = Map.copyOf(pinned);
        Map<String, String> byTier = new HashMap<>();
        String plain = "low";
        for (String part : effortSpec.split(",")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                byTier.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            } else if (!part.isBlank()) {
                plain = part.strip();
            }
        }
        this.efforts = Map.copyOf(byTier);
        this.effort = plain;
        this.standIns = Map.copyOf(standIns);
        this.standInLast = standInLast == null ? "" : standInLast.strip();
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The run's client, or null when OpenAI is asked through its API ({@code -Dhermit.openai.via} unset or api). */
    public static CodexCliClient fromSystemProperties() {
        if (!"codex".equalsIgnoreCase(System.getProperty("hermit.openai.via", "api").strip())) {
            return null;
        }
        int timeout = Integer.getInteger("hermit.codex.timeoutSec", EscalationLadder.TIMEOUT_SECONDS);
        return new CodexCliClient(System.getProperty("hermit.codex.bin", "codex").strip(),
                pairs(System.getProperty("hermit.codex.models", "")), System.getProperty("hermit.codex.effort", "low"),
                pairs(System.getProperty("hermit.codex.standIn", DEFAULT_STAND_IN)),
                System.getProperty("hermit.codex.standInLast", DEFAULT_STAND_IN_LAST),
                Math.max(1, Math.min(timeout, EscalationLadder.TIMEOUT_SECONDS)));
    }

    private static Map<String, String> pairs(String csv) {
        Map<String, String> out = new HashMap<>();
        for (String part : csv.split(",")) {
            int colon = part.indexOf(':');
            if (colon > 0) {
                out.put(part.substring(0, colon).strip(), part.substring(colon + 1).strip());
            }
        }
        return out;
    }

    /** A model id's tier, its last part: {@code gpt-6.1-sol} → {@code sol}. */
    static String tier(String model) {
        return model.substring(model.lastIndexOf('-') + 1).toLowerCase(Locale.ROOT);
    }

    /** Whether {@code a}'s version numbers come after {@code b}'s: {@code gpt-6-astra} after {@code gpt-5.6-astra}. */
    static boolean newer(String a, String b) {
        List<Integer> va = versionOf(a);
        List<Integer> vb = versionOf(b);
        for (int i = 0; i < Math.max(va.size(), vb.size()); i++) {
            int x = i < va.size() ? va.get(i) : 0;
            int y = i < vb.size() ? vb.get(i) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static List<Integer> versionOf(String model) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(model);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        return out;
    }

    /** The Codex model a rung is asked on: pinned, else its tier's newest listed model, else its own id. */
    public synchronized String codexModel(String model) {
        String t = tier(model);
        String pin = pinned.getOrDefault(model, pinned.get(t));
        if (pin != null) {
            return pin;
        }
        if (newest == null) {
            newest = listedTiers();
        }
        return newest.getOrDefault(t, model);
    }

    private Map<String, String> listedTiers() {
        Map<String, String> out = new HashMap<>();
        try {
            Process p = new ProcessBuilder(bin, "debug", "models").redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            JsonNode root = MAPPER.readTree(p.getInputStream());
            p.waitFor(30, TimeUnit.SECONDS);
            for (JsonNode m : root.path("models")) {
                String slug = m.path("slug").asText("");
                String t = tier(slug);
                if (!"list".equals(m.path("visibility").asText()) || !t.matches("[a-z]+") || t.equals(slug)) {
                    continue;
                }
                String held = out.get(t);
                if (held == null || newer(slug, held)) {
                    out.put(t, slug);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Codex] the model list could not be read (" + e.getMessage()
                    + "): each rung keeps its id");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** The models that answer for this rung while the plan's limit holds, in order: the tier's own, then the last. */
    public List<String> standIns(String model) {
        List<String> out = new ArrayList<>();
        String own = standIns.get(tier(model));
        if (own != null && !own.isBlank()) {
            out.add(own);
        }
        if (!standInLast.isEmpty() && !out.contains(standInLast)) {
            out.add(standInLast);
        }
        return out;
    }

    /**
     * When the used-up limit lets the next call through (seconds since the epoch), 0 when nothing holds it. The limits
     * are read again when the last read is five minutes old, also while a hold lasts: when they show room before the
     * hold's time (a reset credit applied meanwhile), the hold ends.
     */
    public long blockedUntil() {
        long now = clock.getAsLong();
        synchronized (this) {
            if (now - limitsReadAt < LIMITS_EVERY_SECONDS) {
                return blocked > now ? blocked : 0;
            }
            limitsReadAt = now;
        }
        Limits limits = readLimits();
        synchronized (this) {
            if (limits != null && limits.blockedUntil(now) == 0 && blocked > now) {
                blocked = 0;
                System.err.println("[Codex] the plan's limits show room again" + describe(limits) + ": calls go on");
            }
            hold(limits, now, false);
            return blocked > now ? blocked : 0;
        }
    }

    /** Sets the hold from fresh limits; a failure whose message names a limit holds for 15 minutes when they don't. */
    private void hold(Limits limits, long now, boolean limitNamed) {
        lastLimits = limits != null ? limits : lastLimits;
        long until = limits == null ? 0 : limits.blockedUntil(now);
        if (until == 0 && limitNamed || until < 0) {
            until = now + UNKNOWN_RESET_SECONDS;
        } else if (until > 0) {
            until += 60;   // a minute past the reset
        }
        if (until > blocked) {
            blocked = until;
            System.err.println("[Codex] the plan's limit is used up" + describe(limits) + ": calls wait until "
                    + clockTime(blocked) + " or go to a stand-in");
        }
    }

    private static String describe(Limits l) {
        return l == null ? "" : " (5-hour window " + l.primaryPercent() + "%, weekly " + l.secondaryPercent() + "%)";
    }

    /** The last limits read, or null. */
    public synchronized Limits lastLimits() {
        return lastLimits;
    }

    /**
     * Whether a failed call met Codex's limit on requests in a short time (429 Too Many Requests) rather than the plan's
     * limit: worth asking the same model again after a pause (the owner, 2026-10-27: bursts of the five review lenses
     * met it, and each one moved the job off Luna for the rest of the run).
     */
    static boolean busy(String error) {
        return error != null && (error.contains("429") || error.contains("Too Many Requests"));
    }

    /** How many times a busy call is asked again ({@code -Dhermit.codex.busyRetries}, default 3). */
    static int busyRetries() {
        return Integer.getInteger("hermit.codex.busyRetries", 3);
    }

    /** The pause in seconds before the {@code n}th time (from 0): {@code -Dhermit.codex.busyWaitSec} (30), doubled. */
    static long busyWait(int n) {
        return Long.getLong("hermit.codex.busyWaitSec", 30L) << n;
    }

    /** Sleeps {@code seconds} before a busy call is asked again. */
    public void pause(long seconds) {
        System.err.println("[Codex] too many requests; asking again in " + seconds + " s");
        sleeper.accept(seconds * 1000);
    }

    /**
     * Sleeps towards that time (seconds since the epoch), five minutes at most: the caller asks {@link #blockedUntil}
     * again, which reads the limits again, so a reset that comes early ends the wait.
     */
    public void waitUntil(long until) {
        long ms = (until - clock.getAsLong()) * 1000;
        if (ms > 0) {
            synchronized (this) {
                if (until != waitAnnounced) {
                    waitAnnounced = until;
                    System.err.println("[Codex] waiting for the plan's window until " + clockTime(until));
                }
            }
            sleeper.accept(Math.min(ms, LIMITS_EVERY_SECONDS * 1000L));
        }
    }

    static String clockTime(long epochSeconds) {
        return DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds));
    }

    /** The plan's limits from the Codex app server, or null when they could not be read. */
    public Limits readLimits() {
        Process p = null;
        try {
            p = new ProcessBuilder(bin, "app-server", "-c", "mcp_servers={}")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":"
                        + "{\"name\":\"hermit\",\"version\":\"1\"}}}\n");
                w.write("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}\n");
                w.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"account/rateLimits/read\"}\n");
                w.flush();
                Process proc = p;
                CompletableFuture<JsonNode> reply = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            JsonNode n = line.isBlank() ? null : MAPPER.readTree(line);
                            if (n != null && n.path("id").asInt() == 2) {
                                return n;
                            }
                        }
                    } catch (IOException e) {
                        return null;
                    }
                    return null;
                });
                JsonNode n = reply.get(30, TimeUnit.SECONDS);
                if (n == null || !n.has("result")) {
                    return null;
                }
                JsonNode res = n.path("result");
                JsonNode rl = res.path("rateLimits");
                JsonNode ordinary = res.path("ordinaryUsageAllowed");
                Boolean allowed = ordinary.isBoolean() ? ordinary.asBoolean() : null;
                return new Limits(allowed, rl.path("primary").path("usedPercent").asInt(),
                        rl.path("primary").path("resetsAt").asLong(), rl.path("secondary").path("usedPercent").asInt(),
                        rl.path("secondary").path("resetsAt").asLong());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[Codex] the plan's limits could not be read: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }
    }

    /** One call: the prompt to the rung's Codex model. A failure that a used-up limit explains sets the hold. */
    public Outcome ask(String model, String prompt) {
        String codexModel = codexModel(model);
        Process p = null;
        try {
            Path dir = home();
            List<String> cmd = List.of(bin, "exec", "--ephemeral", "--ignore-user-config", "--ignore-rules",
                    "-s", "read-only", "--skip-git-repo-check", "-C", dir.resolve("work").toString(), "-m", codexModel,
                    "-c", "model_reasoning_effort=\"" + efforts.getOrDefault(tier(model), effort) + "\"",
                    "-c", "model_instructions_file=" + tomlString(dir.resolve("instructions.md").toString()),
                    "--json", "-");
            p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process proc = p;
            List<String> lines = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // the process was stopped
                }
            }, "codex-exec-reader");
            reader.setDaemon(true);
            reader.start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(p);
                return new Outcome(null, codexModel, null, "Codex timed out after " + timeoutSeconds + " s", false);
            }
            reader.join(5000);
            return outcome(codexModel, lines, p.exitValue());
        } catch (IOException e) {
            return new Outcome(null, codexModel, null, "Codex could not run: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, codexModel, null, "interrupted", false);
        } finally {
            if (p != null && p.isAlive()) {
                stop(p);
            }
        }
    }

    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private Outcome outcome(String codexModel, List<String> lines, int exit) {
        String text = null;
        String error = null;
        JsonNode usage = null;
        for (String line : lines) {
            JsonNode e;
            try {
                e = MAPPER.readTree(line);
            } catch (IOException ex) {
                continue;
            }
            String type = e.path("type").asText();
            JsonNode item = e.path("item");
            if ("item.completed".equals(type) && "agent_message".equals(item.path("type").asText())) {
                text = item.path("text").asText("");
            } else if ("turn.completed".equals(type)) {
                usage = usage(e.path("usage"));
            } else if ("turn.failed".equals(type)) {
                error = message(e.path("error").path("message").asText(""));
            } else if ("error".equals(type) && error == null) {
                error = message(e.path("message").asText(""));
            }
        }
        if (error == null && text != null && !text.isBlank()) {
            return new Outcome(text, codexModel, usage, null, false);
        }
        String why = error != null ? error : text != null ? "empty reply" : "no reply (exit " + exit + ")";
        long now = clock.getAsLong();
        Limits limits = readLimits();
        boolean named = LIMIT_MESSAGE.matcher(why).find();
        boolean limit;
        synchronized (this) {
            limitsReadAt = now;
            hold(limits, now, named);
            limit = blocked > now;
        }
        return new Outcome(null, codexModel, null, why, limit);
    }

    /** A Codex error's message: the provider's own when the message is its JSON. */
    private static String message(String raw) {
        try {
            JsonNode n = MAPPER.readTree(raw);
            String inner = n.path("error").path("message").asText("");
            if (!inner.isBlank()) {
                return inner;
            }
        } catch (IOException | RuntimeException ignored) {
            // plain text
        }
        return raw;
    }

    /** Codex's token counts in the chat-completions shape the ladder's trace reads. */
    private static JsonNode usage(JsonNode u) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("prompt_tokens", u.path("input_tokens").asInt());
        out.putObject("prompt_tokens_details").put("cached_tokens", u.path("cached_input_tokens").asInt());
        out.put("completion_tokens", u.path("output_tokens").asInt());
        out.putObject("completion_tokens_details").put("reasoning_tokens", u.path("reasoning_output_tokens").asInt());
        return out;
    }

    private synchronized Path home() throws IOException {
        if (home == null) {
            Path dir = Files.createTempDirectory("hermit-codex");
            Files.createDirectories(dir.resolve("work"));
            Files.writeString(dir.resolve("instructions.md"), INSTRUCTIONS);
            dir.toFile().deleteOnExit();
            home = dir;
        }
        return home;
    }

    private static String tomlString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
