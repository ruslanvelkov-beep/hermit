package hermit.tool.consult;

import hermit.model.EndpointPolicy;
import hermit.model.HelperChain;
import hermit.model.HostedBudget;
import hermit.model.HostedChatClient;
import hermit.model.HostedPlatforms;
import hermit.model.HostedPlatforms.CloudModel;
import hermit.model.ModelRouter;
import hermit.model.UsageLedger;
import hermit.model.escalation.EscalationLadder;
import hermit.tool.api.OptionalTool;
import hermit.tool.api.Tool;
import hermit.tool.helper.HelperModelTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Consults a big cloud "brain" - Cerebras or Groq - for hard programming, debugging,
 * or planning that the small local brain shouldn't tackle alone. Both platforms are
 * OpenAI-compatible. This is a CONSULTANT, not the agentic driver: use it sparingly
 * (free-tier tokens are limited) for a second opinion, a plan refinement, or a
 * sceptical critique - then act locally on its advice.
 *
 * <p>Routing: with {@code platform=auto} (default) it picks the most coding-capable
 * model whose platform still has token headroom, breaking ties toward the platform
 * with MORE remaining tokens. Context is trimmed to a budget so a big prompt doesn't
 * burn tokens needlessly (but not so hard that the model loses precision).
 *
 * <p>Every call reports token usage (this call + session totals + remaining) via
 * {@link UsageLedger}.
 */
public class ConsultTool implements Tool, OptionalTool {

    private static final ModelRouter ROUTER = new ModelRouter();

    private static final int DEFAULT_MAX_CONTEXT_CHARS = 24_000;  // ~6K tokens
    // Reasoning models (GLM/Qwen3/gpt-oss) spend a big slice of the completion budget
    // THINKING before emitting the answer, so the default needs headroom to finish both.
    private static final int DEFAULT_MAX_TOKENS = 3000;
    private static final int GOOD_CODER_SCORE = 7;                // "big brain, good at code"
    /** The model BigBrother asks when neither the call nor -Dhermit.bigbrother.model names one, and no platform is named. */
    static final String DEFAULT_MODEL = "openai/gpt-oss-120b:preferred";   // for now; Qwen/Qwen3.8-27B:preferred before

    @Override
    public String endpointLabel() {
        StringBuilder sb = new StringBuilder("BigBrother [");
        List<String> names = new ArrayList<>();
        for (String p : new String[] {HostedPlatforms.HUGGINGFACE, HostedPlatforms.GROQ, HostedPlatforms.DEEPSEEK,
                HostedPlatforms.CEREBRAS}) {
            if (HostedPlatforms.hasKey(p)) {
                names.add(HostedPlatforms.HUGGINGFACE.equals(p) ? "HuggingFace"
                        : Character.toUpperCase(p.charAt(0)) + p.substring(1));
            }
        }
        sb.append(names.isEmpty() ? "no keys" : String.join("+", names));
        return sb.append(']').toString();
    }

    /** Available when at least one platform key is configured. */
    @Override
    public boolean isAvailable() {
        return EscalationLadder.shared().enabled()
        || HostedPlatforms.hasKey(HostedPlatforms.HUGGINGFACE)
        || HostedPlatforms.hasKey(HostedPlatforms.CEREBRAS)
        || HostedPlatforms.hasKey(HostedPlatforms.GROQ)
        || HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK);
    }

    private static final AtomicInteger AUTO_CALLS = new AtomicInteger();
    /** How long Hugging Face is left alone after an outage before it is asked again. */
    static final long OUTAGE_PAUSE_MS = 10 * 60 * 1000L;
    /** Why Hugging Face was last set aside this run, for the routing note of the calls that skip it. */
    private static volatile String huggingFaceDown = "Hugging Face was set aside";

    /**
     * (the user's routing rule, 12 September): with {@code platform=auto}, Groq (free) is first and DeepSeek
     * (paid) is the fallback - taken when every free platform with a key is exhausted or missing - and every TENTH
     * automatic call goes to DeepSeek regardless, so the paid model is exercised and compared without being the default.
     * Cerebras stays a free candidate behind Groq. Returns the platform to filter on, or null for "any free".
     */
    public static String platformForAutoCall(int callNo, boolean groqUsable, boolean cerebrasUsable, boolean deepseekKey) {
        if (deepseekKey && callNo % 10 == 0) {
            return HostedPlatforms.DEEPSEEK;
        }
        if (groqUsable) {
            return HostedPlatforms.GROQ;
        }
        if (cerebrasUsable) {
            return HostedPlatforms.CEREBRAS;
        }
        return deepseekKey ? HostedPlatforms.DEEPSEEK : null;
    }

    /**
     * The user's rule: of DeepSeek's models only deepseek-flash, never the premium ones (v4-pro, reasoner, chat), unless
     * -Dhermit.cloud.models names one. A test query without that list once auto-routed to deepseek-v4-pro.
     */
    static boolean premiumDeepSeek(CloudModel m) {
        if (!HostedPlatforms.DEEPSEEK.equals(m.platform()) || "deepseek-flash".equals(m.id())) {
            return false;
        }
        String allowed = System.getProperty("hermit.cloud.models");
        return allowed == null || Arrays.stream(allowed.split(",")).map(String::trim).noneMatch(m.id()::equals);
    }

    /** OpenAI's models are reached only through the escalation ladder: BigBrother never picks one by itself. */
    static boolean ladderOnly(CloudModel m) {
        return HostedPlatforms.OPENAI.equals(m.platform());
    }

    private static boolean usable(String platform) {
        return HostedPlatforms.hasKey(platform) && !UsageLedger.isExhausted(platform);
    }

    @Override
    public String getDocumentation() {
        if (viaLadder(EscalationLadder.shared().enabled())) {
            return "BigBrother: ask a stronger hosted model for help with a hard programming/debugging/planning problem "
                    + "the local model can't do well. It is a CONSULTANT for second opinions / plan refinement / critique — "
                    + "NOT the main driver; every call is paid, so call it only when a task is genuinely complex, and pass "
                    + "only the most relevant context.\n"
                    + "Parameters: {\"task\": \"<the question/instruction>\", "
                    + "\"mode\": \"answer\"|\"refinePlan\"|\"critique\" (optional, default answer), "
                    + "\"context\": \"<the most relevant code/plan/error — trimmed to a budget>\" (optional), "
                    + "\"max_tokens\": <int> (optional)}. The model is chosen by Hermit's escalation ladder ("
                    + EscalationLadder.shared() + "); a platform or model parameter is ignored.";
        }
        StringBuilder models = new StringBuilder();
        for (CloudModel m : HostedPlatforms.models(null)) {
            if (HostedPlatforms.hasKey(m.platform()) && !ladderOnly(m) && !premiumDeepSeek(m)) {
                models.append("\n  - ").append(m.platform()).append('/').append(m.id())
                        .append(" (code ").append(m.codingScore()).append("/10) ").append(m.note());
            }
        }
        return "BigBrother: ask a BIG cloud model (Cerebras or Groq) for help with a hard "
                + "programming/debugging/planning problem the small local brain can't do well. "
                + "It is a CONSULTANT for second opinions / plan refinement / critique — NOT the main "
                + "driver; free-tier tokens are limited, so call it only when a task is genuinely complex, "
                + "and pass only the most relevant context (enough for precision, no more). Token usage is "
                + "reported after every call.\n"
                + "Parameters: {\"task\": \"<the question/instruction>\", "
                + "\"mode\": \"answer\"|\"refinePlan\"|\"critique\" (optional, default answer), "
                + "\"context\": \"<the most relevant code/plan/error — trimmed to a budget>\" (optional), "
                + "\"platform\": \"auto\"|\"huggingface\"|\"groq\"|\"deepseek\"|\"cerebras\" (optional, default auto: the "
                + "default model " + DEFAULT_MODEL + " on Hugging Face when its key is set; else Groq first, DeepSeek as the "
                + "paid fallback and on every tenth call), "
                + "\"model\": \"<exact id>\" (optional; overrides auto-routing), "
                + "\"max_tokens\": <int> (optional, default " + DEFAULT_MAX_TOKENS + ")}."
                + "\nAvailable models:" + (models.length() == 0 ? " (no API keys configured)" : models);
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String task = firstNonBlank(str(parameters.get("task")), str(parameters.get("question")));
        if (task == null || task.isBlank()) {
            return err("task is required.");
        }
        if (!isAvailable()) {
            return err("No cloud API keys configured (Cerebras/Groq). Add them to "
                    + ".secrets/cloud-keys.properties.");
        }
        String mode = str(parameters.getOrDefault("mode", "answer"));
        String context = str(parameters.get("context"));
        String requestedPlatform = str(parameters.getOrDefault("platform", "auto"));
        String requestedModel = requestedModel(str(parameters.get("model")), requestedPlatform);
        int maxTokens = Math.max(intParam(parameters.get("max_tokens"), DEFAULT_MAX_TOKENS),
                HelperModelTool.hostedMinTokens());
        int maxCtx = intParam(parameters.get("max_context_chars"), Math.max(DEFAULT_MAX_CONTEXT_CHARS,
                HostedBudget.charsFor(context)));   // the hosted budget, case by case
        HostedBudget.note("BIGBROTHER_CALL", context, maxCtx);
        boolean ladder = viaLadder(EscalationLadder.shared().enabled());
        CloudModel chosen = ladder ? null : selectModel(requestedPlatform, requestedModel);
        if (chosen == null && !ladder) {
            return err("No suitable cloud model available (check API keys / platform name).");
        }
        String trimmed = trimContext(context, maxCtx);
        boolean wasTrimmed = context != null && trimmed.length() < context.length();
        String prompt = buildPrompt(mode, task, trimmed);
        // Model router: in jev-main mode Jev may answer the planner's call with the local model instead (rules: hosted)
        if (EndpointPolicy.localOnly()) {
            HelperChain local = HelperChain.shared();
            ModelRouter.Decision d = ROUTER.decide(
                    ModelRouter.forBigBrotherCall(local.anyAvailable(), true));
            if (d.route() == ModelRouter.Route.LOCAL) {
                HelperChain.Answer a = local.consult(prompt, mode, null);
                if (a != null) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "Success");
                    result.put("platform", "local (" + a.source() + ")");
                    result.put("routing", "model router: " + d.by() + " — " + d.why());
                    result.put("answer", stripThink(a.text()));
                    return result;
                }
            }
        }
        if (ladder) {   // the escalation ladder picks the model; the planner's platform/model parameters are ignored
            EscalationLadder.Reply reply = EscalationLadder.shared()
                    .ask(EscalationLadder.Job.BIGBROTHER, 0, 0, prompt, maxTokens);
            if (reply == null) {
                return err("No rung of the escalation ladder answered: " + EscalationLadder.shared().lastFailure());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "Success");
            result.put("model", reply.model());
            result.put("tier", reply.tier());
            result.put("mode", mode);
            result.put("routing", "escalation ladder: " + reply.why());
            if (wasTrimmed) {
                result.put("note", "context trimmed to ~" + maxCtx + " chars to save tokens");
            }
            result.put("answer", stripThink(reply.text()));
            return result;
        }
        boolean huggingFace = HostedPlatforms.HUGGINGFACE.equals(chosen.platform());
        if (huggingFace && UsageLedger.isExhausted(HostedPlatforms.HUGGINGFACE)) {
            return fallBack(huggingFaceDown + " earlier", "skipped", mode, prompt, maxTokens, wasTrimmed, maxCtx);
        }
        ModelRouter.countCloudCall();
        HostedChatClient client = new HostedChatClient(chosen.platform());
        HostedChatClient.Result r = client.chat(chosen.id(), prompt, maxTokens);
        if (!r.ok() && huggingFace && (outOfFunds(r) || outage(r))) {
            // funds do not come back this run; an outage is given a few minutes before Hugging Face is asked again
            boolean funds = outOfFunds(r);
            huggingFaceDown = funds ? "Hugging Face is out of funds"
                    : "Hugging Face is unavailable ("
                    + (r.statusCode() == 0 ? "no connection" : "HTTP " + r.statusCode()) + ")";
            UsageLedger.markExhausted(HostedPlatforms.HUGGINGFACE,
                    funds ? Long.MAX_VALUE : System.currentTimeMillis() + OUTAGE_PAUSE_MS);
            return fallBack(huggingFaceDown, r.error(), mode, prompt, maxTokens, wasTrimmed, maxCtx);
        }
        if (!r.ok()) {
            Map<String, Object> e = err("Cloud call failed: " + r.error());
            e.put("platform", chosen.platform());
            e.put("model", chosen.id());
            return e;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Success");
        result.put("platform", chosen.platform());
        result.put("model", chosen.id());
        result.put("mode", mode);
        result.put("routing",
                str(parameters.get("model")) == null && System.getProperty("hermit.bigbrother.model") == null
                        && DEFAULT_MODEL.equals(chosen.id()) ? "default model"
                        : "auto".equalsIgnoreCase(requestedPlatform) && requestedModel == null
                        ? "auto (coding-fit + most tokens remaining)" : "explicit");
        if (wasTrimmed) {
            result.put("note", "context trimmed to ~" + maxCtx + " chars to save tokens");
        }
        result.put("answer", stripThink(r.answer()));
        result.put("usage", UsageLedger.formatCall(r.usage()));
        result.put("session_usage", UsageLedger.sessionSummary());
        return result;
    }

    /**
     * Whether a call goes to the escalation ladder: when the ladder is on, unless -Dhermit.bigbrother.direct=true and
     * -Dhermit.bigbrother.model names a model, which is then called directly (the rewrite runs keep the ladder for
     * plan authoring and give BigBrother one model of its own).
     */
    static boolean viaLadder(boolean ladderEnabled) {
        String configured = System.getProperty("hermit.bigbrother.model");
        boolean direct = Boolean.getBoolean("hermit.bigbrother.direct") && configured != null && !configured.isBlank();
        return ladderEnabled && !direct;
    }

    /**
     * The model a call asks for: the one it names, else -Dhermit.bigbrother.model, else - when it names no platform, or
     * names Hugging Face - the default model. A call that names another platform keeps that platform's routing. When
     * the default model has no key, {@link #selectModel} falls back to auto-routing.
     */
    static String requestedModel(String named, String requestedPlatform) {
        if (named != null && !named.isBlank()) {
            return named;
        }
        String configured = System.getProperty("hermit.bigbrother.model");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        boolean auto = requestedPlatform == null || requestedPlatform.isBlank()
                || "auto".equalsIgnoreCase(requestedPlatform);
        return auto || HostedPlatforms.HUGGINGFACE.equalsIgnoreCase(requestedPlatform) ? DEFAULT_MODEL : null;
    }

    /**
     * Pick a model. Explicit model/platform wins; otherwise choose the most
     * coding-capable model among platforms with keys, preferring the platform with
     * more remaining tokens when several strong coders are available.
     */
    static CloudModel selectModel(String requestedPlatform, String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            CloudModel m = HostedPlatforms.modelById(requestedModel.trim());
            // Honour an explicit model only if it has a key AND is still served (not archived).
            if (m != null && !ladderOnly(m) && !premiumDeepSeek(m) && HostedPlatforms.hasKey(m.platform())
                    && HostedPlatforms.isModelLive(m.platform(), m.id())) {
                return m;
            }
        }
        boolean auto = requestedPlatform == null || requestedPlatform.isBlank()
                || "auto".equalsIgnoreCase(requestedPlatform);
        String platformFilter = auto
                ? platformForAutoCall(AUTO_CALLS.incrementAndGet(), usable(HostedPlatforms.GROQ),
                        usable(HostedPlatforms.CEREBRAS), HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK))
                : requestedPlatform.toLowerCase();
        // availableModels = catalog ∩ live, so an archived model is never chosen.
        List<CloudModel> all = HostedPlatforms.availableModels(platformFilter);
        CloudModel best = null;
        long bestRemaining = Long.MIN_VALUE;
        int bestScore = Integer.MIN_VALUE;
        for (CloudModel m : all) {
            if (!HostedPlatforms.hasKey(m.platform()) || ladderOnly(m) || premiumDeepSeek(m)) {
                continue;
            }
            // Unknown remaining => treat as very high so the platform gets tried and learned.
            Long rem = UsageLedger.lastRemaining(m.platform());
            long remaining = rem == null ? Long.MAX_VALUE / 4 : rem;
            boolean strong = m.codingScore() >= GOOD_CODER_SCORE;
            boolean bestStrong = best != null && best.codingScore() >= GOOD_CODER_SCORE;
            if (best == null) {
                best = m; bestRemaining = remaining; bestScore = m.codingScore(); continue;
            }
            // Among "strong coders", prefer more remaining tokens; else prefer higher score.
            if (strong && bestStrong) {
                if (remaining > bestRemaining) {
                    best = m; bestRemaining = remaining; bestScore = m.codingScore();
                }
            } else if (m.codingScore() > bestScore
                    || (m.codingScore() == bestScore && remaining > bestRemaining)) {
                best = m; bestRemaining = remaining; bestScore = m.codingScore();
            }
        }
        return best;
    }

    /**
     * The user's rule (21 October): when the Hugging Face model is out of funds or unavailable, BigBrother falls back
     * to DeepSeek (flash), and when DeepSeek cannot answer either, to the escalation ladder. A platform found out of
     * funds is not asked again this run; after an outage Hugging Face is asked again after {@link #OUTAGE_PAUSE_MS}.
     */
    private Map<String, Object> fallBack(String why, String huggingFaceError, String mode, String prompt, int maxTokens,
            boolean wasTrimmed, int maxCtx) {
        List<String> failures = new ArrayList<>(List.of("huggingface: " + huggingFaceError));
        CloudModel flash = HostedPlatforms.modelById("deepseek-flash");
        if (flash != null && HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK)
                && !UsageLedger.isExhausted(HostedPlatforms.DEEPSEEK)) {
            ModelRouter.countCloudCall();
            HostedChatClient.Result d = new HostedChatClient(HostedPlatforms.DEEPSEEK).chat(flash.id(), prompt, maxTokens);
            if (d.ok()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "Success");
                result.put("platform", HostedPlatforms.DEEPSEEK);
                result.put("model", flash.id());
                result.put("mode", mode);
                result.put("routing", "fallback: " + why + "; DeepSeek");
                if (wasTrimmed) {
                    result.put("note", "context trimmed to ~" + maxCtx + " chars to save tokens");
                }
                result.put("answer", stripThink(d.answer()));
                result.put("usage", UsageLedger.formatCall(d.usage()));
                result.put("session_usage", UsageLedger.sessionSummary());
                return result;
            }
            if (outOfFunds(d)) {
                UsageLedger.markExhausted(HostedPlatforms.DEEPSEEK, Long.MAX_VALUE);
            }
            failures.add("deepseek: " + d.error());
            why += ", and DeepSeek did not answer";
        }
        EscalationLadder ladder = EscalationLadder.shared();
        if (ladder.enabled()) {
            EscalationLadder.Reply reply = ladder.ask(EscalationLadder.Job.BIGBROTHER, 0, 0,
                    prompt, maxTokens);
            if (reply != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "Success");
                result.put("model", reply.model());
                result.put("tier", reply.tier());
                result.put("mode", mode);
                result.put("routing", "fallback: " + why + "; escalation ladder: " + reply.why());
                if (wasTrimmed) {
                    result.put("note", "context trimmed to ~" + maxCtx + " chars to save tokens");
                }
                result.put("answer", stripThink(reply.text()));
                return result;
            }
            failures.add("ladder: " + ladder.lastFailure());
        }
        return err(why + ", and no fallback answered: " + String.join("; ", failures));
    }

    /**
     * An outage: a server error, a timeout, a rate limit, or a call that never got an answer (status 0 with "call
     * failed"; a missing key or a host outside the run's boundary is no outage).
     */
    static boolean outage(HostedChatClient.Result r) {
        int code = r.statusCode();
        return (code == 0 && r.error() != null && r.error().contains("call failed")) || code >= 500 || code == 408
                || code == 429;
    }

    /** A call refused for want of funds or credits (402, or the providers' wording for it). */
    static boolean outOfFunds(HostedChatClient.Result r) {
        return r.statusCode() == 402 || (r.error() != null && OUT_OF_FUNDS.matcher(r.error()).matches());
    }

    /** The providers' wording for a call refused for want of funds or credits. */
    public static final Pattern OUT_OF_FUNDS = Pattern.compile("(?is).*(insufficient (balance|credits?|funds)"
            + "|exceeded your (monthly )?included credits|payment required|out of credits).*");

    /** Keep head + tail within a char budget so we save tokens but retain precision. */
    static String trimContext(String context, int maxChars) {
        if (context == null) {
            return "";
        }
        if (context.length() <= maxChars) {
            return context;
        }
        int head = (int) (maxChars * 0.7);
        int tail = maxChars - head - 40;
        return context.substring(0, head)
                + "\n\n[… " + (context.length() - head - tail) + " chars trimmed to save tokens …]\n\n"
                + context.substring(context.length() - tail);
    }

    /**
     * Reasoning models (GLM, Qwen3) emit chain-of-thought in a {@code <think>…</think>}
     * block before the answer. Strip it so the surfaced answer is the clean final text;
     * if only reasoning is present (e.g. truncated), keep it rather than return empty.
     */
    static String stripThink(String answer) {
        if (answer == null) {
            return "";
        }
        String cleaned = answer.replaceAll("(?s)<think>.*?</think>", "").strip();
        // Unclosed <think> (truncated by max_tokens): keep everything after it if any.
        if (cleaned.contains("<think>")) {
            int end = cleaned.lastIndexOf("</think>");
            cleaned = (end >= 0 ? cleaned.substring(end + 8) : cleaned.replace("<think>", "")).strip();
        }
        return cleaned.isEmpty() ? answer.strip() : cleaned;
    }

    static String buildPrompt(String mode, String task, String context) {
        String ctx = (context == null || context.isBlank()) ? "" : "\n\nRELEVANT CONTEXT:\n" + context;
        return switch (mode == null ? "answer" : mode) {
            case "refinePlan" -> "You are an expert software planning reviewer. Improve the plan for the "
                    + "task below: fix gaps, remove unnecessary steps, make each step concrete and "
                    + "executable, and flag risks. Return the improved plan only.\n\nTASK: " + task + ctx;
            case "critique" -> "You are a rigorous sceptical code/plan critic. Try hard to REFUTE or find "
                    + "concrete flaws (bugs, edge cases, wrong assumptions) in the target below. List each "
                    + "concrete problem with a fix; if none, say APPROVED.\n\nTARGET: " + task + ctx;
            default -> "You are a senior engineer helping a smaller local agent. Answer precisely and "
                    + "concretely for the task below.\n\nTASK: " + task + ctx;
        };
    }

    private Map<String, Object> err(String message) {
        return new LinkedHashMap<>(Map.of("status", "Error", "message", message));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static int intParam(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return (o instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
