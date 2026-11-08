package hermit.model.escalation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hermit.model.escalation.EscalationLadder.Job;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The OpenAI rungs through the Codex CLI under the ChatGPT plan (user, 2026-10-25), against a scripted {@code codex}
 * and a scripted local OpenAI and DeepSeek: nothing here reaches a network.
 */
class CodexCliClientTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static HttpServer server;
    /** The platform and model of every request the scripted HTTP providers received, in order. */
    static final List<String> requests = new CopyOnWriteArrayList<>();
    /** Models that answer HTTP 500. */
    static final Set<String> failing = ConcurrentHashMap.newKeySet();

    /**
     * A stand-in for the Codex CLI. {@code exec} takes its next mode from the file {@code modes} (one per line, "ok"
     * when none is left): ok answers, limit fails as a used-up plan does (and marks the plan's limits used up),
     * notfound fails as a model the plan does not serve, busy fails as too many requests in a short time do (429), slow
     * takes 30 s. {@code debug models} prints
     * {@code models.json}; {@code app-server} answers the limits read with {@code limits.json}. Every call's arguments
     * go to {@code args.log}.
     */
    static final String FAKE_CODEX = """
            #!/bin/bash
            D="$(cd "$(dirname "$0")" && pwd)"
            echo "$*" >> "$D/args.log"
            case "$1" in
              debug) cat "$D/models.json"; exit 0 ;;
              app-server)
                while IFS= read -r line; do
                  case "$line" in
                    *'"initialize"'*) echo '{"id":1,"result":{"userAgent":"fake"}}' ;;
                    *'account/rateLimits/read'*) cat "$D/limits.json"; echo ;;
                  esac
                done
                exit 0 ;;
            esac
            model=""; dir=""
            while [ $# -gt 0 ]; do
              case "$1" in -m) model="$2"; shift ;; -C) dir="$2"; shift ;; esac
              shift
            done
            cat > "$D/last-prompt.txt"
            ls -A "$dir" > "$D/last-dir.txt"
            mode="$(head -n 1 "$D/modes" 2>/dev/null)"
            tail -n +2 "$D/modes" > "$D/modes.next" 2>/dev/null; mv "$D/modes.next" "$D/modes"
            echo '{"type":"thread.started","thread_id":"t1"}'
            echo '{"type":"turn.started"}'
            case "${mode:-ok}" in
              limit) cp "$D/limits-used-up.json" "$D/limits.json"; cat "$D/limit.jsonl"; exit 1 ;;
              notfound) cat "$D/notfound.jsonl"; exit 1 ;;
              busy) cat "$D/busy.jsonl"; exit 1 ;;
              slow) sleep 30 ;;
            esac
            sed "s/MODEL/$model/" "$D/ok.jsonl"
            """;

    /** The events of an answer; {@code MODEL} becomes the model asked. */
    static final String OK_EVENTS = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\","
            + "\"type\":\"agent_message\",\"text\":\"{\\\"approved\\\": false, \\\"issues\\\": "
            + "[\\\"from MODEL\\\"]}\"}}\n"
            + "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":15000,\"cached_input_tokens\":9000,"
            + "\"output_tokens\":40,\"reasoning_output_tokens\":12}}\n";
    /** The events of a call the used-up plan refuses. */
    static final String LIMIT_EVENTS = "{\"type\":\"error\",\"message\":"
            + "\"You've hit your usage limit. Try again later.\"}\n"
            + "{\"type\":\"turn.failed\",\"error\":{\"message\":"
            + "\"You've hit your usage limit. Try again later.\"}}\n";
    /** The events of a call on a model the plan does not serve. */
    static final String NOT_FOUND_EVENTS = "{\"type\":\"turn.failed\",\"error\":{\"message\":"
            + "\"{\\\"type\\\":\\\"error\\\",\\\"status\\\":400,\\\"error\\\":{\\\"message\\\":"
            + "\\\"The model is not supported when using Codex with a ChatGPT account.\\\","
            + "\\\"code\\\":\\\"model_not_found\\\"}}\"}}\n";

    /** The events of a call that met Codex's limit on requests in a short time, as Codex reports it. */
    static final String BUSY_EVENTS = "{\"type\":\"error\",\"message\":"
            + "\"exceeded retry limit, last status: 429 Too Many Requests\"}\n"
            + "{\"type\":\"turn.failed\",\"error\":{\"message\":"
            + "\"exceeded retry limit, last status: 429 Too Many Requests\"}}\n";

    static final String MODELS = """
            {"models":[
             {"slug":"gpt-6-astra","visibility":"list"},
             {"slug":"gpt-5.4-sol","visibility":"list"},
             {"slug":"gpt-5.6-sol","visibility":"list"},
             {"slug":"gpt-7-sol","visibility":"hide"},
             {"slug":"gpt-5.6-luna","visibility":"list"},
             {"slug":"gpt-5.2","visibility":"list"}]}
            """;

    @TempDir
    Path fake;
    final AtomicLong now = new AtomicLong(1_800_000_000L);
    final List<Long> slept = new ArrayList<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/chat/completions", ex -> answer(ex, "openai"));
        server.createContext("/deepseek/chat/completions", ex -> answer(ex, "deepseek"));
        server.createContext("/huggingface/chat/completions", ex -> answer(ex, "huggingface"));
        server.start();
    }

    static void answer(HttpExchange ex, String platform) throws IOException {
        String model = MAPPER.readTree(ex.getRequestBody().readAllBytes()).path("model").asText();
        requests.add(platform + ":" + model);
        if (failing.contains(model)) {
            byte[] err = "{\"error\":{\"message\":\"scripted\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, err.length);
            ex.getResponseBody().write(err);
            ex.close();
            return;
        }
        byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"from " + model + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}").getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() throws IOException {
        Path bin = fake.resolve("codex");
        Files.writeString(bin, FAKE_CODEX);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(fake.resolve("models.json"), MODELS);
        Files.writeString(fake.resolve("ok.jsonl"), OK_EVENTS);
        Files.writeString(fake.resolve("limit.jsonl"), LIMIT_EVENTS);
        Files.writeString(fake.resolve("notfound.jsonl"), NOT_FOUND_EVENTS);
        Files.writeString(fake.resolve("busy.jsonl"), BUSY_EVENTS);
        Files.writeString(fake.resolve("modes"), "");
        limits("limits.json", 20, 5);
        limits("limits-used-up.json", 100, 40);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setProperty("hermit.openai.via", "codex");
        System.setProperty("hermit.codex.bin", bin.toString());
        System.setProperty("hermit.openai.baseUrl", base + "/openai");
        System.setProperty("hermit.deepseek.baseUrl", base + "/deepseek");
        System.setProperty("hermit.huggingface.baseUrl", base + "/huggingface");
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.deepseek.apiKey", "test-key");
        System.setProperty("hermit.huggingface.apiKey", "test-key");
        System.setProperty("hermit.ladder", "gpt-6-luna,deepseek-flash,gpt-6.1-sol,gpt-6-astra");
        System.setProperty("hermit.ladder.switch", "");
        System.setProperty("hermit.remote.hostedMinTokens", "0");
        requests.clear();
        failing.clear();
        CodexCliClient.clock = now::get;
        CodexCliClient.sleeper = ms -> {
            slept.add(ms);
            now.addAndGet(ms / 1000);
        };
        EscalationLadder.resetShared();
    }

    @AfterEach
    void clearProperties() {
        for (String p : List.of("hermit.openai.via", "hermit.codex.bin", "hermit.codex.models", "hermit.codex.effort",
                "hermit.codex.standIn", "hermit.codex.standInLast", "hermit.codex.timeoutSec", "hermit.openai.baseUrl",
                "hermit.deepseek.baseUrl", "hermit.huggingface.baseUrl", "hermit.openai.apiKey", "hermit.deepseek.apiKey",
                "hermit.huggingface.apiKey", "hermit.ladder", "hermit.ladder.switch",
                "hermit.ladder.trace", "hermit.remote.hostedMinTokens", "hermit.inference.localOnly",
                "hermit.inference.allowHosted", "hermit.cloud.providers")) {
            System.clearProperty(p);
        }
        CodexCliClient.clock = CodexCliClient.SYSTEM_CLOCK;
        CodexCliClient.sleeper = CodexCliClient.SYSTEM_SLEEPER;
        EscalationLadder.resetShared();
    }

    /** The app server's limits read: the 5-hour window at {@code primary}% and the weekly one at {@code weekly}%. */
    void limits(String file, int primary, int weekly) throws IOException {
        Files.writeString(fake.resolve(file), "{\"id\":2,\"result\":{\"ordinaryUsageAllowed\":"
                + (primary < 100 && weekly < 100) + ",\"rateLimits\":{\"limitId\":\"codex\",\"primary\":"
                + "{\"usedPercent\":" + primary
                + ",\"windowDurationMins\":300,\"resetsAt\":" + (now.get() + 3600) + "},\"secondary\":{\"usedPercent\":"
                + weekly + ",\"windowDurationMins\":10080,\"resetsAt\":" + (now.get() + 86400) + "}}}}");
    }

    void modes(String... modes) throws IOException {
        Files.writeString(fake.resolve("modes"), String.join("\n", modes) + "\n");
    }

    List<String> execCalls() throws IOException {
        Path log = fake.resolve("args.log");
        return Files.exists(log)
                ? Files.readAllLines(log).stream().filter(l -> l.startsWith("exec ")).toList() : List.of();
    }

    @Test
    void aCallRunsCodexExecReadOnlyInAnEmptyDirectoryWithThePromptOnStdin() throws IOException {
        CodexCliClient codex = CodexCliClient.fromSystemProperties();
        CodexCliClient.Outcome out = codex.ask("gpt-6-luna", "Review this plan.");
        assertNull(out.error());
        assertEquals("{\"approved\": false, \"issues\": [\"from gpt-5.6-luna\"]}", out.text());
        assertEquals("gpt-5.6-luna", out.model());
        assertEquals(15000, out.usage().path("prompt_tokens").asInt());
        assertEquals(9000, out.usage().path("prompt_tokens_details").path("cached_tokens").asInt());
        assertEquals(40, out.usage().path("completion_tokens").asInt());
        String args = execCalls().get(0);
        for (String flag : List.of("--ephemeral", "--ignore-user-config", "--ignore-rules", "-s read-only",
                "--skip-git-repo-check", "-m gpt-5.6-luna", "model_reasoning_effort=\"low\"",
                "model_instructions_file=", "--json")) {
            assertTrue(args.contains(flag), flag + " in " + args);
        }
        assertTrue(args.endsWith(" -"), "the prompt is read from stdin: " + args);
        assertEquals("Review this plan.", Files.readString(fake.resolve("last-prompt.txt")));
        assertEquals("", Files.readString(fake.resolve("last-dir.txt")), "Codex works in an empty directory");
    }

    // the user (2026-10-25): the newest models; the plan serves only the ones Codex lists, so a tier is its newest
    // listed model
    @Test
    void eachTierIsItsNewestListedModelUnlessPinned() {
        CodexCliClient codex = CodexCliClient.fromSystemProperties();
        assertEquals("gpt-5.6-luna", codex.codexModel("gpt-6-luna"));
        assertEquals("gpt-5.6-sol", codex.codexModel("gpt-6.1-sol"), "a hidden model is left out");
        assertEquals("gpt-6-astra", codex.codexModel("gpt-6-astra"));
        assertEquals("gpt-6-terra", codex.codexModel("gpt-6-terra"), "a tier Codex does not list keeps its id");
        System.setProperty("hermit.codex.models", "sol:gpt-5.4-sol");
        assertEquals("gpt-5.4-sol", CodexCliClient.fromSystemProperties().codexModel("gpt-6.1-sol"));
    }

    @Test
    void theNewestOfATierIsOrderedByItsVersionNumbers() {
        assertTrue(CodexCliClient.newer("gpt-6-astra", "gpt-5.6-astra"));
        assertTrue(CodexCliClient.newer("gpt-5.10-sol", "gpt-5.9-sol"));
        assertFalse(CodexCliClient.newer("gpt-5.6-sol", "gpt-5.6-sol"));
        assertEquals("sol", CodexCliClient.tier("gpt-6.1-sol"));
    }

    @Test
    void theLimitsComeFromTheAppServer() throws IOException {
        CodexCliClient codex = CodexCliClient.fromSystemProperties();
        CodexCliClient.Limits open = codex.readLimits();
        assertNotNull(open);
        assertEquals(20, open.primaryPercent());
        assertEquals(0, open.blockedUntil(now.get()));
        limits("limits.json", 100, 40);
        assertEquals(now.get() + 3600, codex.readLimits().blockedUntil(now.get()), "the 5-hour window's reset");
        limits("limits.json", 100, 100);
        assertEquals(now.get() + 86400, codex.readLimits().blockedUntil(now.get()), "both used up: the later reset");
    }

    @Test
    void withTheSwitchOnEveryOpenAIRungGoesThroughCodexAndNothingReachesTheApi() throws IOException {
        Path trace = fake.resolve("ladder.jsonl");
        System.setProperty("hermit.ladder.trace", trace.toString());
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply luna = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        EscalationLadder.Reply astra = ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100);
        assertEquals("gpt-6-luna", luna.model());
        assertEquals("codex gpt-5.6-luna", luna.tier());
        assertTrue(luna.text().contains("from gpt-5.6-luna"));
        assertEquals("codex gpt-6-astra", astra.tier());
        assertEquals(2, execCalls().size());
        assertTrue(requests.isEmpty(), "no API request: " + requests);
        JsonNode row = MAPPER.readTree(Files.readAllLines(trace).get(0));
        assertEquals("codex gpt-5.6-luna", row.path("tier").asText());
        assertEquals(15000, row.path("promptTokens").asInt());
        assertEquals(0.0, row.path("estimatedUsd").asDouble(), "the plan's allowance, not the API's bill");
    }

    // the user (2026-10-25): when the plan's limit is reached, Sol and Astra fall back to DeepSeek Pro and Luna to
    // DeepSeek Flash until the window resets; the API is never asked
    @Test
    void aUsedUpLimitHandsSolAndAstraToDeepSeekProAndLunaToFlashUntilTheReset() throws IOException {
        modes("limit");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply luna = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", luna.model());
        assertTrue(luna.why().contains("Codex plan limit"), luna.why());
        EscalationLadder.Reply astra = ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100);
        EscalationLadder.Reply sol = ladder.ask(Job.VERDICT_REVIEW, 0, 2, "p", 100);
        assertEquals("deepseek-v4-pro", astra.model());
        assertEquals("deepseek-v4-pro", sol.model());
        assertEquals(1, execCalls().size(), "while the limit holds, Codex is not asked again");
        assertEquals(List.of("deepseek:deepseek-flash", "deepseek:deepseek-v4-pro", "deepseek:deepseek-v4-pro"),
                requests);
        assertTrue(slept.isEmpty(), "nothing waits while DeepSeek stands in");

        limits("limits.json", 0, 40);
        now.addAndGet(3700);   // past the 5-hour window's reset
        EscalationLadder.Reply again = ladder.ask(Job.PLAN_AHEAD, 0, 0, "p", 100);
        assertEquals("gpt-6-luna", again.model());
        assertEquals("codex gpt-5.6-luna", again.tier());
        assertEquals(2, execCalls().size());
    }

    @Test
    void limitsAlreadyUsedUpAtTheStartSkipCodexAltogether() throws IOException {
        limits("limits.json", 30, 100);
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", reply.model());
        assertTrue(execCalls().isEmpty());
    }

    @Test
    void withoutAStandInTheCallWaitsForTheResetAndGoesOn() throws IOException {
        System.setProperty("hermit.codex.standIn", "");
        System.setProperty("hermit.codex.standInLast", "");
        modes("limit");
        CodexCliClient.sleeper = ms -> {
            slept.add(ms);
            now.addAndGet(ms / 1000);
            try {
                limits("limits.json", 0, 40);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("gpt-6-luna", reply.model());
        assertEquals("codex gpt-5.6-luna", reply.tier());
        assertEquals(List.of(300_000L), slept, "five minutes, then the limits read again show room");
        assertEquals(2, execCalls().size());
        assertTrue(requests.isEmpty());
    }

    // a reset credit applied while a call waits ends the wait at the next read of the limits
    @Test
    void aResetThatComesEarlyEndsTheWaitAtTheNextReadOfTheLimits() throws IOException {
        System.setProperty("hermit.codex.standIn", "");
        System.setProperty("hermit.codex.standInLast", "");
        modes("limit");
        CodexCliClient.sleeper = ms -> {
            slept.add(ms);
            now.addAndGet(ms / 1000);
            if (slept.size() == 2) {
                try {
                    limits("limits.json", 0, 40);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("codex gpt-5.6-luna", reply.tier());
        assertEquals(List.of(300_000L, 300_000L), slept, "five minutes at a time, then on at the first read with room");
        assertEquals(2, execCalls().size());
        assertTrue(requests.isEmpty());
    }

    @Test
    void withNoStandInReachableTheCallWaits() throws IOException {
        System.setProperty("hermit.cloud.providers", "openai");   // neither DeepSeek nor the router has a key in this run
        modes("limit");
        CodexCliClient.sleeper = ms -> {
            slept.add(ms);
            now.addAndGet(ms / 1000);
        };
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("codex gpt-5.6-luna", reply.tier());
        assertEquals(12, slept.size(), "five minutes at a time");
        assertTrue(slept.stream().allMatch(ms -> ms == 300_000L));
        assertEquals(3_600_000L, slept.stream().mapToLong(Long::longValue).sum(), "until the reset the limits name");
        assertTrue(requests.isEmpty());
    }

    // the user (2026-10-26): when DeepSeek cannot be reached, GPT-OSS-120B on the Hugging Face router answers
    @Test
    void whenDeepSeekCannotAnswerGptOssOnTheRouterStandsIn() throws IOException {
        modes("limit");
        failing.add("deepseek-v4-pro");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply astra = ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100);
        assertEquals("openai/gpt-oss-120b:preferred", astra.model(), "DeepSeek Pro failed: the router's GPT-OSS answers");
        assertEquals(List.of("deepseek:deepseek-v4-pro", "huggingface:openai/gpt-oss-120b:preferred"), requests);
        System.setProperty("hermit.cloud.providers", "openai,huggingface");   // and with no DeepSeek key at all
        requests.clear();
        assertEquals("openai/gpt-oss-120b:preferred", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)));
        assertEquals(List.of("huggingface:openai/gpt-oss-120b:preferred"), requests);
        assertTrue(slept.isEmpty());
        System.setProperty("hermit.codex.standInLast", "");
        requests.clear();
        CodexCliClient.sleeper = ms -> {
            slept.add(ms);
            now.addAndGet(ms / 1000);
            try {
                limits("limits.json", 0, 40);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        EscalationLadder.resetShared();
        EscalationLadder waiting = EscalationLadder.fromSystemProperties();
        assertEquals("codex gpt-5.6-luna", waiting.ask(Job.SYNTHESIS, 0, 0, "p", 100).tier(), "no last stand-in: it waits");
        assertEquals(1, slept.size());
    }

    static String model(EscalationLadder.Reply r) {
        return r == null ? null : r.model();
    }

    @Test
    void aTimeoutFailsTheCallAndTheJobMovesUpARung() throws IOException {
        System.setProperty("hermit.codex.timeoutSec", "1");
        modes("slow");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply reply = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", reply.model(), "the ladder's next rung");
        assertTrue(ladder.lastFailure().contains("timed out"), ladder.lastFailure());
        assertTrue(slept.isEmpty());
    }

    // the owner, 2026-10-27: bursts of review-lens calls met Codex's 429, and each one moved the job off Luna for the
    // rest of the run
    @Test
    void tooManyRequestsWaitAndAskTheSameModelAgain() throws IOException {
        modes("busy", "busy");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply reply = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("gpt-6-luna", reply.model());
        assertEquals("codex gpt-5.6-luna", reply.tier());
        assertTrue(reply.why().contains("had too many requests"), reply.why());
        assertEquals(List.of(30_000L, 60_000L), slept, "30 s, then twice as long");
        assertEquals(3, execCalls().size());
        assertTrue(requests.isEmpty(), "no other rung was asked");
        assertEquals("codex gpt-5.6-luna", ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100).tier(), "the job stays on Luna");
    }

    @Test
    void tooManyRequestsAfterTheLastRetryFailTheCall() throws IOException {
        modes("busy", "busy", "busy", "busy");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply reply = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", reply.model(), "the ladder's next rung");
        assertTrue(ladder.lastFailure().contains("429 Too Many Requests"), ladder.lastFailure());
        assertEquals(List.of(30_000L, 60_000L, 120_000L), slept);
        assertEquals(4, execCalls().size());
    }

    @Test
    void aModelThePlanDoesNotServeIsAFailureNotALimit() throws IOException {
        modes("notfound");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply reply = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", reply.model());
        assertTrue(ladder.lastFailure().contains("not supported when using Codex"), ladder.lastFailure());
        assertEquals(List.of("deepseek:deepseek-flash"), requests);
        EscalationLadder.Reply next = ladder.ask(Job.PLAN_AHEAD, 0, 0, "p", 100);
        assertEquals("codex gpt-5.6-luna", next.tier(), "no limit was set");
    }

    @Test
    void inALocalOnlyRunCodexNeedsChatgptOnTheAllowList() {
        System.setProperty("hermit.inference.localOnly", "true");
        System.setProperty("hermit.inference.allowHosted", "127.0.0.1");
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", reply.model());
        System.setProperty("hermit.inference.allowHosted", "127.0.0.1,chatgpt.com");
        EscalationLadder.resetShared();
        EscalationLadder.Reply reply2 = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("codex gpt-5.6-luna", reply2.tier());
    }

    @Test
    void theApiStaysTheDefault() {
        System.clearProperty("hermit.openai.via");
        assertNull(CodexCliClient.fromSystemProperties());
        EscalationLadder.Reply reply = EscalationLadder.fromSystemProperties().ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("gpt-6-luna", reply.model());
        assertEquals(List.of("openai:gpt-6-luna"), requests);
    }
}
