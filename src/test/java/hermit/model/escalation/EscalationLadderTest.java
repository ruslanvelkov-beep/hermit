package hermit.model.escalation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hermit.model.escalation.EscalationLadder.Job;
import hermit.review.HostedCodeReview;
import hermit.tool.consult.ConsultTool;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Network-free tests for the hosted escalation ladder, against a scripted local OpenAI and DeepSeek. */
class EscalationLadderTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static HttpServer server;
    /** Every request body the scripted providers received, in order. */
    static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** Models that answer HTTP 500; models whose Flex tier has no capacity. */
    static final Set<String> failing = ConcurrentHashMap.newKeySet();
    static final Set<String> flexFull = ConcurrentHashMap.newKeySet();
    /** Models whose next answer is a tool call the provider refuses with a 400. */
    static final Set<String> toolCallOnce = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/chat/completions", ex -> answer(ex, true));
        server.createContext("/deepseek/chat/completions", ex -> answer(ex, false));
        server.start();
    }

    static void answer(HttpExchange ex, boolean openai) throws IOException {
        JsonNode body = MAPPER.readTree(ex.getRequestBody().readAllBytes());
        requests.add(body);
        String model = body.path("model").asText();
        boolean flex = "flex".equals(body.path("service_tier").asText());
        boolean toolCall = toolCallOnce.remove(model);
        int status = toolCall ? 400 : failing.contains(model) ? 500 : flex && flexFull.contains(model) ? 429 : 200;
        String reply = toolCall
                ? "{\"error\":{\"message\":\"Tool choice is none, but model called a tool\",\"code\":\"tool_use_failed\"}}"
                : status != 200 ? "{\"error\":{\"message\":\"scripted\"}}"
                : "{" + (openai ? "\"service_tier\":\"" + (flex ? "flex" : "default") + "\"," : "")
                + "\"choices\":[{\"message\":{\"content\":\"{\\\"approved\\\": false, \\\"issues\\\": [\\\"from " + model
                + "\\\"]}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":6000,\"completion_tokens\":4000,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1000},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":3000}}}";
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void ladder() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setProperty("hermit.openai.baseUrl", base + "/openai");
        System.setProperty("hermit.deepseek.baseUrl", base + "/deepseek");
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.deepseek.apiKey", "test-key");
        System.setProperty("hermit.ladder", "gpt-6-luna,deepseek-flash,gpt-6.1-sol,gpt-6-astra");
        System.setProperty("hermit.ladder.flex", "gpt-6.1-sol,gpt-6-astra");
        System.setProperty("hermit.remote.hostedMinTokens", "0");
        System.setProperty("hermit.ladder.switch", "");   // no switch to Luna unless a test asks for one
        requests.clear();
        failing.clear();
        toolCallOnce.clear();
        flexFull.clear();
        EscalationLadder.resetShared();
    }

    @AfterEach
    void clearProperties() {
        for (String p : List.of("hermit.openai.baseUrl", "hermit.deepseek.baseUrl", "hermit.openai.apiKey",
                "hermit.deepseek.apiKey", "hermit.ladder", "hermit.ladder.flex", "hermit.ladder.maxCalls",
                "hermit.ladder.trace", "hermit.remote.hostedMinTokens", "hermit.inference.localOnly",
                "hermit.inference.allowHosted", "hermit.cloud.models", "hermit.model.inputTokens",
                "hermit.ladder.switch", "hermit.ladder.switchTo", "hermit.ladder.switchExempt",
                "hermit.ladder.job.step_plan", "hermit.ladder.job.lens_review")) {
            System.clearProperty(p);
        }
        EscalationLadder.resetShared();
    }

    static String model(EscalationLadder.Reply r) {
        return r == null ? null : r.model();
    }

    @Test
    void itIsOffWithoutTheLadderProperty() {
        System.clearProperty("hermit.ladder");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertFalse(ladder.enabled());
        assertNull(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100));
        assertTrue(requests.isEmpty());
    }

    // the owner, 2026-10-27: the step plan and the review lenses stay with the run's own model unless their job names
    // rungs of its own, so a drive can hand them to Luna through Codex
    @Test
    void theRunsOwnJobsGoToTheLadderOnlyWhenTheyNameRungs() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertNull(ladder.askInstead(Job.LENS_REVIEW, 0, "p", 100));
        assertNull(ladder.askInstead(Job.STEP_PLAN, 0, "p", 100));
        assertTrue(requests.isEmpty(), "no job named rungs, so nothing was asked");
        System.setProperty("hermit.ladder.job.lens_review", "gpt-6-luna");
        EscalationLadder.resetShared();
        ladder = EscalationLadder.fromSystemProperties();
        assertEquals("gpt-6-luna", model(ladder.askInstead(Job.LENS_REVIEW, 0, "p", 100)));
        assertNull(ladder.askInstead(Job.STEP_PLAN, 0, "p", 100), "only the job that names rungs goes to them");
        assertEquals(1, requests.size());
        failing.add("gpt-6-luna");
        assertNull(ladder.askInstead(Job.LENS_REVIEW, 0, "p", 100), "no rung answered: the run's model does the job");
    }

    // a refused step plan is asked of the next rung of the job's own order, and the first rung again once a plan ran
    @Test
    void aRefusedStepPlanIsAskedOfTheNextRung() {
        System.setProperty("hermit.ladder.job.step_plan", "gpt-6-luna,deepseek-flash");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals("gpt-6-luna", model(ladder.askInstead(Job.STEP_PLAN, 0, "p", 100)));
        assertEquals("deepseek-flash", model(ladder.askInstead(Job.STEP_PLAN, 1, "p", 100)));
        assertEquals("gpt-6-luna", model(ladder.askInstead(Job.STEP_PLAN, 1, "p", 100)));
    }

    // the hosted budget lets a source-heavy prompt reach 96k tokens; a rung with an input budget of its own (GPT-OSS:
    // 32k tokens to start with) is sent the prompt cut to it, and the next rung up still gets it whole
    @Test
    void aRungWithAnInputBudgetOfItsOwnIsSentThePromptCutToIt() {
        System.setProperty("hermit.model.inputTokens", "gpt-6-luna=1000");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        String prompt = "HEAD " + "x".repeat(10_000) + " TAIL";
        failing.add("gpt-6-luna");
        ladder.ask(Job.SYNTHESIS, 0, 0, prompt, 100);
        String toLuna = requests.get(0).path("messages").path(0).path("content").asText();
        assertTrue(toLuna.length() <= 3200 && toLuna.startsWith("HEAD ") && toLuna.endsWith(" TAIL"), toLuna.length() + " chars");
        assertEquals(prompt, requests.get(1).path("messages").path(0).path("content").asText(),
                "deepseek-flash has no budget of its own");
    }

    // GPT-OSS answered the plan author with a tool call, the provider refused it with a 400, and the
    // job moved up a rung; the same rung asked once more answers
    @Test
    void aReplyRefusedForAToolCallIsAskedForOnceMoreOnTheSameRung() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        toolCallOnce.add("gpt-6-luna");
        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)));
        assertEquals(2, requests.size(), "the refused reply and the one asked again");
    }

    // the owner, 2026-10-27: GPT-OSS-120B's empty replies used hundreds of output tokens; the trace now says where
    @Test
    void anEmptyReplySaysWhatItsMessageHeldInstead() throws Exception {
        assertEquals("empty reply (finish_reason stop; it held a tool call (TreeEdit), reasoning of 5 characters)",
                EscalationLadder.emptyReply(MAPPER.readTree("{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"TreeEdit\",\"arguments\":\"{}\"}}],"
                        + "\"reasoning\":\"think\"}}")));
        assertEquals("empty reply (finish_reason length)",
                EscalationLadder.emptyReply(MAPPER.readTree("{\"finish_reason\":\"length\",\"message\":{}}")));
    }

    @Test
    void openAiGetsItsOwnFieldsAndFlexAndEveryCallIsTracedWithItsCost() throws Exception {
        Path trace = Files.createTempFile("ladder", ".jsonl");
        System.setProperty("hermit.ladder.trace", trace.toString());
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();

        assertEquals("gpt-6-luna", model(ladder.ask(Job.BIGBROTHER, 0, 0, "p", 1500)));
        JsonNode luna = requests.get(0);
        assertEquals(1500, luna.path("max_completion_tokens").asInt(), "OpenAI refuses max_tokens");
        assertTrue(luna.path("max_tokens").isMissingNode());
        assertTrue(luna.path("temperature").isMissingNode(), "OpenAI's newer models take only the default temperature");
        assertTrue(luna.path("service_tier").isMissingNode(), "Luna is not on Flex");

        assertEquals("gpt-6.1-sol", model(ladder.ask(Job.CODE_REVIEW, 0, 2, "p", 1500)));
        assertEquals("flex", requests.get(1).path("service_tier").asText());

        failing.add("gpt-6-luna");
        assertEquals("deepseek-flash", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 1500)));
        JsonNode deepseek = requests.get(3);
        assertEquals(1500, deepseek.path("max_tokens").asInt());
        assertTrue(deepseek.path("max_completion_tokens").isMissingNode());
        assertEquals("disabled", deepseek.path("thinking").path("type").asText(),
                "DeepSeek's thinking would eat the output budget");
        assertTrue(luna.path("thinking").isMissingNode());

        List<String> rows = Files.readAllLines(trace);
        assertEquals(4, rows.size(), String.join("\n", rows));
        JsonNode sol = MAPPER.readTree(rows.get(1));
        assertEquals("flex", sol.path("tier").asText());
        // (5000 × $2 + 1000 × $0.10 + 4000 × $10) / 1M, halved on Flex
        assertEquals(0.02505, sol.path("estimatedUsd").asDouble(), 1e-9);
        assertEquals(3000, sol.path("reasoningTokens").asInt());
        assertFalse(MAPPER.readTree(rows.get(2)).path("ok").asBoolean(), "the failed Luna call is traced too");
    }

    @Test
    void whenFlexHasNoCapacityTheSameCallIsSentAgainOnTheStandardTierAtOnce() {
        flexFull.add("gpt-6-astra");
        EscalationLadder.Reply r = EscalationLadder.fromSystemProperties().ask(Job.CODE_REVIEW, 0, 3, "p", 100);
        assertEquals("gpt-6-astra", model(r));
        assertEquals("default (flex had no capacity)", r.tier());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).path("service_tier").isMissingNode());
    }

    @Test
    void aFailedCallClimbsOneRungAtOnceAndTheJobStaysThereForTheTurn() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        failing.add("gpt-6-luna");
        assertEquals("deepseek-flash", model(ladder.ask(Job.VERDICT_REVIEW, 0, 0, "p", 100)));
        failing.clear();
        assertEquals("deepseek-flash", model(ladder.ask(Job.VERDICT_REVIEW, 0, 0, "p", 100)), "sticky for the turn");
        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)), "another job starts at the bottom");
        ladder.newTurn();
        assertEquals("gpt-6-luna", model(ladder.ask(Job.VERDICT_REVIEW, 0, 0, "p", 100)), "a new turn starts at the bottom");
    }

    @Test
    void anOutputRefusedLaterMovesTheJobsNextCallOneRungUp() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)));
        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)), "nothing refused: same rung");
        assertEquals("deepseek-flash", model(ladder.ask(Job.SYNTHESIS, 1, 0, "p", 100)), "the Luna answer was refused");
        assertEquals("gpt-6.1-sol", model(ladder.ask(Job.SYNTHESIS, 2, 0, "p", 100)));
    }

    @Test
    void aReviewerMovesUpOnlyWhenAnAnswerItApprovedIsRefusedAfterwards() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals("gpt-6-luna", model(ladder.ask(Job.VERDICT_REVIEW, 0, 0, "p", 100)));
        ladder.reviewed(Job.VERDICT_REVIEW, false, 1);   // it refused the answer itself
        assertEquals("gpt-6-luna", model(ladder.ask(Job.VERDICT_REVIEW, 1, 0, "p", 100)), "its own refusal is not its failure");
        ladder.reviewed(Job.VERDICT_REVIEW, true, 1);    // it approved; the supervisor then refused the answer
        assertEquals("deepseek-flash", model(ladder.ask(Job.VERDICT_REVIEW, 2, 0, "p", 100)));
    }

    @Test
    void eachRefusedAuthoredPlanHandsTheNextOneToTheRungAbove() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals(0, ladder.planFloor(9), "no plan floor by default");
        assertEquals("gpt-6-luna", model(ladder.ask(Job.PLAN_AUTHOR, 2, ladder.planFloor(2), "p", 100)));
        assertEquals("deepseek-flash", model(ladder.ask(Job.PLAN_AUTHOR, 3, ladder.planFloor(3), "p", 100)));
        assertEquals("gpt-6.1-sol", model(ladder.ask(Job.PLAN_AUTHOR, 4, ladder.planFloor(4), "p", 100)));
        assertEquals("gpt-6-astra", model(ladder.ask(Job.PLAN_AUTHOR, 5, ladder.planFloor(5), "p", 100)));
    }

    @Test
    void aPlanFloorCanBeSetByTheNumberOfRefusedPlans() {
        System.setProperty("hermit.ladder.planFloors", "3:gpt-6.1-sol");
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            assertEquals("gpt-6-luna", model(ladder.ask(Job.PLAN_AUTHOR, 2, ladder.planFloor(2), "p", 100)));
            assertEquals("gpt-6.1-sol", model(ladder.ask(Job.PLAN_AUTHOR, 3, ladder.planFloor(3), "p", 100)));
        } finally {
            System.clearProperty("hermit.ladder.planFloors");
        }
    }

    // a job can have its own order: the first plan of a task from the strongest model down, a revision from the cheapest
    // up; with no answer the next rung of the job's own order answers, and a floor is an index in that order
    @Test
    void aJobWithItsOwnOrderWalksThatOrder() {
        System.setProperty("hermit.ladder.job.initial_plan", "gpt-6-astra,gpt-6.1-sol,gpt-6-luna");
        System.setProperty("hermit.ladder.job.plan_author", "deepseek-flash,gpt-6-luna,gpt-6.1-sol");
        System.setProperty("hermit.ladder.planFloors", "3:gpt-6.1-sol");
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            assertTrue(ladder.hasOwnRungs(Job.INITIAL_PLAN));
            assertFalse(ladder.hasOwnRungs(Job.SYNTHESIS));
            assertEquals("gpt-6-astra", model(ladder.ask(Job.INITIAL_PLAN, 0, 0, "p", 100)));
            ladder.newTurn();
            failing.add("gpt-6-astra");
            assertEquals("gpt-6.1-sol", model(ladder.ask(Job.INITIAL_PLAN, 0, 0, "p", 100)), "no answer from Astra: Sol");
            ladder.newTurn();
            failing.add("gpt-6.1-sol");
            assertEquals("gpt-6-luna", model(ladder.ask(Job.INITIAL_PLAN, 0, 0, "p", 100)));
            failing.clear();
            ladder.newTurn();
            assertEquals(2, ladder.rungIndex(Job.INITIAL_PLAN, "gpt-6-luna"));
            assertEquals(-1, ladder.rungIndex(Job.INITIAL_PLAN, "deepseek-flash"));
            assertEquals("gpt-6-luna", model(ladder.ask(Job.INITIAL_PLAN, 0, 2, "p", 100)), "the floor 2 is Luna in this order");
            assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)), "other jobs keep the ladder's order");
            assertEquals("deepseek-flash", model(ladder.ask(Job.PLAN_AUTHOR, 0, ladder.planFloor(0), "p", 100)));
            assertEquals(2, ladder.planFloor(3), "the plan floor names Sol, the third of the plan author's own order");
        } finally {
            System.clearProperty("hermit.ladder.job.initial_plan");
            System.clearProperty("hermit.ladder.job.plan_author");
            System.clearProperty("hermit.ladder.planFloors");
        }
    }

    @Test
    void codeIsReviewedOnTheRungItsBranchCountAsksFor() {
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals(0, ladder.reviewFloor(7));
        assertEquals(2, ladder.reviewFloor(8));
        assertEquals(2, ladder.reviewFloor(14));
        assertEquals(3, ladder.reviewFloor(15));
    }

    @Test
    void aSpentRungPassesItsCallsDown() {
        System.setProperty("hermit.ladder.maxCalls", "gpt-6-astra:1");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals("gpt-6-astra", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)));
        EscalationLadder.Reply second = ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100);
        assertEquals("gpt-6.1-sol", model(second));
        assertTrue(second.why().contains("gpt-6-astra has spent its calls"), second.why());
        assertEquals(1, ladder.callsOn("gpt-6-astra"));
    }

    @Test
    void aLocalOnlyRunReachesOnlyTheAllowListedProviders() {
        System.clearProperty("hermit.openai.baseUrl");   // the real api.openai.com, which this run does not allow
        System.setProperty("hermit.inference.localOnly", "true");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply r = ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100);
        assertEquals("deepseek-flash", model(r), "Luna is outside the boundary; nothing below it, so one rung up");
        assertTrue(r.why().contains("gpt-6-luna is not reachable"), r.why());
        assertEquals("deepseek-flash", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)),
                "Astra and Sol are outside the boundary too: down to DeepSeek");
        assertTrue(requests.stream().allMatch(b -> b.path("model").asText().startsWith("deepseek")));
    }

    @Test
    void aModelOutsideTheHostedAllowListNeverBecomesARung() {
        System.setProperty("hermit.cloud.models", "gpt-6-luna,deepseek-flash");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals(List.of("gpt-6-luna", "deepseek-flash"),
                ladder.rungs().stream().map(EscalationLadder.Rung::model).toList());
        assertEquals(0, ladder.reviewFloor(20), "the default floors name rungs that are not there: no floor");
        assertEquals("gpt-6-luna", model(ladder.ask(Job.CODE_REVIEW, 0, ladder.reviewFloor(20), "p", 100)));
    }

    @Test
    void theCostEstimateUsesListPricesAndHalvesFlex() {
        assertEquals(0.0066, EscalationLadder.estimatedUsd("deepseek-flash", "standard", 6000, 0, 4000), 1e-9);
        assertEquals(0.26, EscalationLadder.estimatedUsd("gpt-6-astra", "default", 6000, 0, 4000), 1e-9);
        assertEquals(0.13, EscalationLadder.estimatedUsd("gpt-6-astra", "flex", 6000, 0, 4000), 1e-9);
        assertEquals(0, EscalationLadder.estimatedUsd("unknown", "default", 6000, 0, 4000));
    }

    @Test
    void theRungsComeFromThePropertiesInTheirOrder() {
        Map<String, Boolean> flex = new LinkedHashMap<>();
        EscalationLadder.fromSystemProperties().rungs().forEach(r -> flex.put(r.model(), r.flex()));
        assertEquals(Map.of("gpt-6-luna", false, "deepseek-flash", false, "gpt-6.1-sol", true, "gpt-6-astra", true),
                flex);
        assertEquals(List.of("gpt-6-luna", "deepseek-flash", "gpt-6.1-sol", "gpt-6-astra"),
                EscalationLadder.fromSystemProperties().rungs().stream().map(EscalationLadder.Rung::model).toList());
        assertEquals(List.of(0, 0, 10, 3),
                EscalationLadder.fromSystemProperties().rungs().stream().map(EscalationLadder.Rung::maxCalls).toList());
    }

    @Test
    void theBigBrotherToolGoesThroughTheLadderWhateverModelThePlannerNames() {
        ConsultTool tool = new ConsultTool();
        assertTrue(tool.isAvailable());
        String doc = tool.getDocumentation();
        assertTrue(doc.contains("escalation ladder (gpt-6-luna < deepseek-flash < gpt-6.1-sol (flex) ≤10 < gpt-6-astra (flex) ≤3)"), doc);
        Map<String, Object> r = tool.execute(Map.of("task", "why does it fail?", "model", "gpt-6-astra", "platform", "openai"));
        assertEquals("Success", r.get("status"), r.toString());
        assertEquals("gpt-6-luna", r.get("model"), "the planner cannot pick Astra by name");
        assertEquals("gpt-6-luna", requests.get(0).path("model").asText());
    }

    @Test
    void theHostedReviewerCountsBranchesFromTheParseTreeAndAsksTheRungTheyCallFor() {
        String simple = "int f(int a) { if (a > 0) { return a; } return -a; }";
        assertEquals(new HostedCodeReview.Complexity(1, 1, true), HostedCodeReview.complexity(simple));
        String dense = "int g(int[] xs, boolean s) { int n = 0;\n"
                + "for (int x : xs) { if (x > 0 && s || x < -5) { n += x > 9 ? 2 : 1; } else if (x == 0) { n--; } }\n"
                + "while (n > 100) { n /= 2; }\n"
                + "try { n = Math.addExact(n, 1); } catch (ArithmeticException e) { n = 0; }\n"
                + "switch (n) { case 1 -> n = 2; case 2 -> n = 3; default -> n = 4; }\n"
                + "return n; }";
        HostedCodeReview.Complexity c = HostedCodeReview.complexity(dense);
        // for, if, &&, ||, ternary, else-if, while, catch, 3 cases (default included)
        assertEquals(11, c.branches(), c.toString());
        assertEquals(2, c.depth(), "the else-if continues its chain");
        assertTrue(c.parsed());
        assertFalse(HostedCodeReview.complexity("REPLACE:\nif (a) {\nWITH:\nif (a && b) {").parsed(), "a fragment is counted from the text");

        HostedCodeReview.Verdict v = HostedCodeReview.review("make g count", dense, null);
        assertEquals("gpt-6.1-sol", v.model(), "11 branches: Sol");
        assertFalse(v.approved());
        assertEquals(List.of("from gpt-6.1-sol"), v.issues());
        assertEquals("gpt-6-luna", HostedCodeReview.review("make f", simple, null).model());
    }

    // the user (2026-10-25): Sol and Astra half as often, to keep the OpenAI bills down; each time either is
    // picked, the call goes to Luna instead with that chance
    @Test
    void solAndAstraAreSwitchedToLunaOnHalfTheirPicksByDefault() {
        System.clearProperty("hermit.ladder.switch");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        assertEquals(0.5, ladder.switchChance("gpt-6.1-sol"));
        assertEquals(0.5, ladder.switchChance("gpt-6-astra"));
        assertEquals(0, ladder.switchChance("gpt-6-luna"));
        assertEquals(0, ladder.switchChance("deepseek-flash"));
    }

    @Test
    void aSwitchedPickGoesToLunaAndItsRefusedAnswerPicksTheSameRungAgain() {
        System.setProperty("hermit.ladder.switch", "gpt-6.1-sol:1");
        EscalationLadder ladder = EscalationLadder.fromSystemProperties();
        EscalationLadder.Reply r = ladder.ask(Job.CODE_REVIEW, 0, 2, "p", 100);
        assertEquals("gpt-6-luna", model(r));
        assertTrue(r.why().endsWith("; gpt-6.1-sol switched to gpt-6-luna (100% of its picks)"), r.why());
        assertEquals("gpt-6-luna (default, for gpt-6.1-sol)", ladder.lastLabel());
        assertEquals(0, ladder.callsOn("gpt-6.1-sol"));
        assertTrue(requests.get(0).path("service_tier").isMissingNode(),
                "Luna is asked as Luna, not on Sol's Flex");

        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 0, 0, "p", 100)));
        assertEquals("deepseek-flash", model(ladder.ask(Job.SYNTHESIS, 1, 0, "p", 100)));
        assertEquals("gpt-6-luna", model(ladder.ask(Job.SYNTHESIS, 2, 0, "p", 100)), "Sol picked, Luna asked");
        EscalationLadder.Reply again = ladder.ask(Job.SYNTHESIS, 3, 0, "p", 100);
        assertEquals("gpt-6-luna", model(again),
                "the refused switched answer leaves the job on Sol, not one rung above Luna");
        assertTrue(again.why().startsWith("its last output (gpt-6-luna for gpt-6.1-sol) was refused;"),
                again.why());
        assertEquals("gpt-6-astra", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)),
                "Astra is not named: never switched");
    }

    // a switched call that gets no answer goes to the picked rung at once; a job whose own order has no Luna
    // is switched to the ladder's Luna
    @Test
    void aSwitchedCallWithoutAnAnswerGoesToThePickedRungAtOnce() {
        System.setProperty("hermit.ladder.switch", "gpt-6-astra:1");
        System.setProperty("hermit.ladder.job.plan_revise", "gpt-6-astra,gpt-6.1-sol");
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            assertEquals("gpt-6-luna", model(ladder.ask(Job.PLAN_REVISE, 0, 0, "p", 100)));
            ladder.newTurn();
            failing.add("gpt-6-luna");
            EscalationLadder.Reply r = ladder.ask(Job.PLAN_REVISE, 0, 0, "p", 100);
            assertEquals("gpt-6-astra", model(r));
            assertTrue(r.why().startsWith("gpt-6-luna failed in gpt-6-astra's place"), r.why());
            assertEquals(List.of("gpt-6-luna", "gpt-6-luna", "gpt-6-astra"),
                    requests.stream().map(b -> b.path("model").asText()).toList());
        } finally {
            System.clearProperty("hermit.ladder.job.plan_revise");
        }
    }

    // the user (2026-10-25): the task plan and the initial plan go straight to Astra, with no switch
    @Test
    void theTaskPlanAndTheInitialPlanAreNeverSwitched() {
        System.setProperty("hermit.ladder.switch", "gpt-6-astra:1");
        System.setProperty("hermit.ladder.job.task_plan", "gpt-6-astra,gpt-6.1-sol,gpt-6-luna");
        System.setProperty("hermit.ladder.job.initial_plan", "gpt-6-astra,gpt-6.1-sol,gpt-6-luna");
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            assertEquals("gpt-6-astra", model(ladder.ask(Job.TASK_PLAN, 0, 0, "p", 100)));
            assertEquals("gpt-6-astra", model(ladder.ask(Job.INITIAL_PLAN, 0, 0, "p", 100)));
            assertEquals("gpt-6-luna", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)),
                    "other jobs are switched");
            System.setProperty("hermit.ladder.switchExempt", "");
            assertEquals("gpt-6-luna",
                    model(EscalationLadder.fromSystemProperties().ask(Job.TASK_PLAN, 0, 0, "p", 100)),
                    "an empty exemption list exempts no job");
        } finally {
            System.clearProperty("hermit.ladder.job.task_plan");
            System.clearProperty("hermit.ladder.job.initial_plan");
        }
    }

    // the user (2026-10-26): the switch stays on by default and can be turned off and on between two steps of a run, by a
    // marker file the ladder looks for on every call
    @Test
    void aMarkerFileHoldsTheSwitchOffWhileItExists() throws IOException {
        Path off = Files.createTempFile("ladder-switch-off", ".txt");
        System.setProperty("hermit.ladder.switch", "gpt-6-astra:1");
        System.setProperty("hermit.ladder.switchOff", off.toString());
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            EscalationLadder.Reply held = ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100);
            assertEquals("gpt-6-astra", model(held), "the marker is there: no switch");
            assertTrue(held.why().contains("the switch is off"), held.why());
            Files.delete(off);
            ladder.newTurn();
            assertEquals("gpt-6-luna", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)), "the marker is gone: the same ladder switches");
            Files.writeString(off, "");
            ladder.newTurn();
            assertEquals("gpt-6-astra", model(ladder.ask(Job.CODE_REVIEW, 0, 3, "p", 100)), "and back off at the next call");
        } finally {
            System.clearProperty("hermit.ladder.switchOff");
            Files.deleteIfExists(off);
        }
    }

    // the instruction follower's helper asks DeepSeek Flash alone: no answer from it is no answer
    @Test
    void theFollowersHelperAsksOnlyItsOwnOrder() {
        System.setProperty("hermit.ladder.job.follower_help", "deepseek-flash");
        try {
            EscalationLadder ladder = EscalationLadder.fromSystemProperties();
            assertEquals("deepseek-flash", model(ladder.ask(Job.FOLLOWER_HELP, 0, 0, "p", 100)));
            failing.add("deepseek-flash");
            assertNull(ladder.ask(Job.FOLLOWER_HELP, 0, 0, "p", 100));
            assertTrue(ladder.lastFailure().startsWith("deepseek-flash: HTTP 500"), ladder.lastFailure());
            assertEquals(List.of("deepseek-flash", "deepseek-flash"),
                    requests.stream().map(b -> b.path("model").asText()).toList());
        } finally {
            System.clearProperty("hermit.ladder.job.follower_help");
        }
    }
}
