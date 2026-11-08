package hermit.model;

import hermit.tool.consult.ConsultTool;
import hermit.tool.helper.HelperModelTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ordered fallback chain of outer "helper" LLMs that Hermit's small local brain can
 * consult (refine a plan, author a corrective plan, fix a failing program, critique).
 * Default order: <b>lan → tunnel → Groq → Cerebras</b>. Availability is probed at
 * construction and re-checked every 5 minutes on a background daemon; {@link #consult}
 * walks the chain and returns the first working answer, failing over on any error
 * (e.g. Cerebras 402 quota → Groq) even between refreshes.
 */
public final class HelperChain implements AutoCloseable {

    /** One outer LLM in the chain. */
    public interface Helper {
        String name();
        boolean probe();                                       // network/config availability
        String ask(String task, String mode, String context); // answer, or null on failure
    }

    /** A successful consult: which helper answered, and its text. */
    public record Answer(String source, String text) {}

    private final List<Helper> helpers;
    private final Map<String, Boolean> status = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public HelperChain(List<Helper> helpers) {
        this(helpers, 5);
    }

    public HelperChain(List<Helper> helpers, long refreshMinutes) {
        this.helpers = List.copyOf(helpers);
        if (refreshMinutes > 0) {
            // Live use: probe on a daemon at delay 0 (never block construction) and
            // then every refreshMinutes. Statuses read 'down' until the first probe lands.
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "helper-chain-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::refresh, 0, refreshMinutes, TimeUnit.MINUTES);
        } else {
            // Tests: probe synchronously once, no scheduler.
            this.scheduler = null;
            refresh();
        }
    }

    private static volatile HelperChain shared;

    /** Process-wide chain (one background refresher), built lazily from the default config. */
    public static HelperChain shared() {
        HelperChain c = shared;
        if (c == null) {
            synchronized (HelperChain.class) {
                if (shared == null) {
                    shared = defaultChain();
                }
                c = shared;
            }
        }
        return c;
    }

    /** Re-probe every helper's availability (called at startup + periodically). */
    public void refresh() {
        for (Helper h : helpers) {
            boolean up;
            try {
                up = h.probe();
            } catch (RuntimeException e) {
                up = false;
            }
            status.put(h.name(), up);
        }
    }

    public boolean anyAvailable() {
        return status.containsValue(Boolean.TRUE);
    }

    /** e.g. ["lan: up", "tunnel: down", "Groq: up", "Cerebras: down"] - for logging/status. */
    public List<String> statusReport() {
        List<String> out = new ArrayList<>();
        for (Helper h : helpers) {
            out.add(h.name() + ": " + (Boolean.TRUE.equals(status.get(h.name())) ? "up" : "down"));
        }
        return out;
    }

    /**
     * Consult the chain: first available helper that returns a non-blank answer wins.
     * A helper that errors/blanks is marked down and the next is tried. Null if none
     * could answer.
     */
    public Answer consult(String task, String mode, String context) {
        // Hard off-switch for supervised "single-model" runs (-Dhermit.chain.disabled=true): the whole
        // outer-LLM fallback chain becomes a no-op, so ALL planning/reasoning stays with the local driver
        // and nothing is silently authored by a remote helper. Callers already handle a null Answer.
        if (Boolean.parseBoolean(System.getProperty("hermit.chain.disabled", "false"))) {
            return null;
        }
        boolean anyUp = false;
        for (Helper h : helpers) {
            if (!Boolean.TRUE.equals(status.get(h.name()))) {
                continue;
            }
            anyUp = true;
            String ans;
            String reason;
            long started = System.currentTimeMillis();
            try {
                ans = h.ask(task, mode, context);
                reason = ans == null ? "no answer" : ans.isBlank() ? "blank answer" : null;
            } catch (RuntimeException e) {
                ans = null;
                reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            if (ans != null && !ans.isBlank()) {
                return new Answer(h.name(), ans.strip());
            }
            // The LAN machine dropped out of the second-reviewer and synthesis roles without a word - a helper
            // that fails is marked down until the next refresh, and now the log says which one, why,
            // and after how long.
            System.err.println("[HelperChain] " + h.name() + " failed after "
                    + (System.currentTimeMillis() - started)
                    + " ms (" + reason + ") — marked down until the next refresh");
            status.put(h.name(), false);   // it was 'up' but failed now → mark down until next refresh
        }
        if (!anyUp) {
            System.err.println("[HelperChain] no helper is up: " + statusReport());
        }
        return null;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ---- default chain: lan → tunnel → Groq → Cerebras ----

    /** Build the standard chain from config + .secrets (helpers without config are omitted). */
    public static HelperChain defaultChain() {
        return new HelperChain(defaultHelpers());
    }

    public static String lanBaseUrl() {
        return System.getProperty("hermit.chain.lan.baseUrl",
        System.getProperty("hermit.remote.baseUrl", "http://127.0.0.1:1234/v1"));
    }

    static String lanModel() {
        // DeepSeek-Coder-V2-Lite-AWQ (8.87 GB) left too little headroom on the LAN machine's 16 GB RAM: a burst of 4
        // concurrent requests reliably crashed its Metal backend (kIOGPUCommandBufferCallbackErrorOutOfMemory) and
        // left it silently returning corrupted output, then permanently erroring, until manually unloaded - verified
        // live 2026-09-27. qwen2.5-coder-14b-instruct (5.94 GB) survives the same burst; it must be loaded on the
        // LAN machine with `lms load qwen2.5-coder-14b-instruct --parallel 3` (not the default parallel=4, which
        // still hits a clean-but-disruptive decode error at exactly 4 concurrent - parallel is a load-time setting,
        // not something this property can express) for Hermit's own chain concurrency to never trigger it.
        return System.getProperty("hermit.chain.lan.model",
                System.getProperty("hermit.remote.model", "qwen2.5-coder-14b-instruct"));
    }

    /**
     * The prompt the chain's first helper may be sent: the limit set for it
     * (-Dhermit.chain.lan.maxPromptChars), else, on a hosted router, its model's input budget (the hosted one when
     * the model has none of its own), else 6000 characters, what the LAN machine's 16 GB RAM has room for.
     */
    public static int lanPromptChars() {
        Integer set = Integer.getInteger("hermit.chain.lan.maxPromptChars");
        if (set != null) {
            return set;
        }
        return HostedPlatforms.platformOf(lanBaseUrl()) != null
                ? ModelBudget.chars(lanModel(), HostedBudget.baseTokens()) : 6000;
    }

    /** The chain's first helper; on a hosted router (Hugging Face, OpenAI) it takes that platform's key. */
    static HelperEndpoint lanConfig() {
        return new HelperEndpoint("lan", lanBaseUrl(), lanModel(), HelperEndpoint.cloudKeyFor(lanBaseUrl()));
    }

    public static List<Helper> defaultHelpers() {
        List<Helper> hs = new ArrayList<>();
        // 1) lan (LAN). The chain's lan entry defaulted to the Wi-Fi address and a model name the LAN machine no
        // longer served, while the RemoteAgent tool was configured (-Dhermit.remote.*) to the tunnel and DeepSeek - so
        // the "second opinion from a different model" never came from the LAN machine; every helper answer was Groq's.
        // The chain's lan follows the RemoteAgent configuration unless -Dhermit.chain.lan.* says otherwise.
        hs.add(new RemoteAgentHelper("lan", lanConfig()));
        if (EndpointPolicy.localOnly()) return hs;
        // 2) tunnel (a tunnelled remote endpoint behind an authenticating proxy), from .secrets/tunnel-refiner.env
        Map<String, String> tun = readEnv(Path.of(".secrets", "tunnel-refiner.env"));
        String tunUrl = tun.getOrDefault("HERMIT_REMOTE_BASEURL",
                System.getProperty("hermit.chain.tunnel.baseUrl", ""));
        String tunKey = tun.getOrDefault("HERMIT_REMOTE_KEY",
                System.getProperty("hermit.chain.tunnel.apiKey", ""));
        String tunModel = tun.getOrDefault("HERMIT_REMOTE_MODEL",
                System.getProperty("hermit.chain.tunnel.model", "qwen-14b-iq3xs:latest"));
        if (!tunUrl.isBlank() && !tunKey.isBlank() && System.getProperty("hermit.cloud.providers") == null) {
            hs.add(new RemoteAgentHelper("tunnel",
                    new HelperEndpoint("tunnel", tunUrl, tunModel, tunKey)));
        }
        // 3) Groq, 4) Cerebras (only if a key is configured). Groq is ahead of Cerebras:
        // Groq's per-minute budget is always replenishing, while Cerebras is billing-walled.
        if (HostedPlatforms.hasKey(HostedPlatforms.GROQ)) {
            hs.add(new BigBrotherHelper("Groq", HostedPlatforms.GROQ));
        }
        if (HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK)) {   // the paid fallback, right behind Groq
            hs.add(new BigBrotherHelper("DeepSeek", HostedPlatforms.DEEPSEEK));
        }
        if (HostedPlatforms.hasKey(HostedPlatforms.CEREBRAS)) {
            hs.add(new BigBrotherHelper("Cerebras", HostedPlatforms.CEREBRAS));
        }
        return hs;
    }

    /** A LAN/tunnel Qwen reached via the HelperModelTool (OpenAI-compatible) tool. */
    private static final class RemoteAgentHelper implements Helper {
        private final String name;
        private final HelperModelTool tool;

        RemoteAgentHelper(String name, HelperEndpoint cfg) {
            this.name = name;
            this.tool = new HelperModelTool(cfg);
        }

        @Override public String name() {
            return name;
        }

        @Override public boolean probe() {
            return tool.isAvailable();
        }

        @Override public String ask(String task, String mode, String context) {
            // A LAN helper gets a prompt that fits its budget: the opening and the end are kept,
            // the middle is cut.
            Map<String, Object> r = tool.execute(Map.of(
                    "task", fitToBudget(task, maxPromptChars()),
                    "mode", mode == null ? "answer" : mode,
                    "context", fitToBudget(context == null ? "" : context, maxPromptChars() / 2)));
            if (!"Success".equals(String.valueOf(r.get("status")))) {
                throw new IllegalStateException(String.valueOf(r.getOrDefault("message", r.get("status"))));   // the reason travels
            }
            return str(r.get("answer"));
        }

        int maxPromptChars() {
            return "lan".equals(name) ? lanPromptChars()
            : Integer.getInteger("hermit.chain." + name.toLowerCase(Locale.ROOT) + ".maxPromptChars", 6000);
        }
    }

    /** A text no longer than {@code max} chars - its opening and its end kept, the middle marked as cut. */
    static String fitToBudget(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int head = Math.min(1500, max / 3);
        String cut = "\n…[" + (text.length() - max) + " chars cut to fit this helper's memory]…\n";
        int tail = Math.max(0, max - head - cut.length());
        return text.substring(0, head) + cut + text.substring(text.length() - tail);
    }

    /** A cloud platform (Cerebras/Groq) via the ConsultTool, platform forced. */
    private static final class BigBrotherHelper implements Helper {
        private final String name;
        private final String platform;
        private final ConsultTool tool = new ConsultTool();

        BigBrotherHelper(String name, String platform) {
            this.name = name;
            this.platform = platform;
        }

        @Override public String name() {
            return name;
        }

        @Override public boolean probe() {
            // Config-level: a key is set and the platform lists at least one live model.
            return HostedPlatforms.hasKey(platform) && !HostedPlatforms.availableModels(platform).isEmpty();
        }

        @Override public String ask(String task, String mode, String context) {
            Map<String, Object> r = tool.execute(Map.of(
                    "task", task,
                    "mode", mode == null ? "answer" : mode,
                    "context", context == null ? "" : context,
                    "platform", platform));
            return "Success".equals(String.valueOf(r.get("status"))) ? str(r.get("answer")) : null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Parse a KEY=value (optionally quoted) env file into a map; empty if unreadable. */
    static Map<String, String> readEnv(Path p) {
        Map<String, String> m = new HashMap<>();
        if (!Files.isReadable(p)) {
            return m;
        }
        try {
            for (String line : Files.readAllLines(p)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                int eq = s.indexOf('=');
                String k = s.substring(0, eq).strip();
                String v = s.substring(eq + 1).strip().replaceAll("^\"|\"$", "");
                m.put(k, v);
            }
        } catch (IOException ignored) {
            // best effort
        }
        return m;
    }
}
