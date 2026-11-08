package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The model router: Hermit's rules, Jev as fallback or as the main decision maker, and the limits every mode keeps. */
class ModelRouterTest {

    static HttpServer jev;
    static volatile String jevReply;
    static volatile int jevStatus = 200;
    static volatile String jevRequest;
    static final AtomicInteger jevCalls = new AtomicInteger();

    @BeforeAll
    static void startJev() throws Exception {
        jev = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jev.createContext("/v1/systemone", ex -> {
            jevCalls.incrementAndGet();
            jevRequest = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = jevReply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(jevStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        jev.start();
        System.setProperty("hermit.jev.apiKey", "test-jev");
    }

    @AfterAll
    static void stopJev() {
        jev.stop(0);
        System.clearProperty("hermit.jev.apiKey");
    }

    @AfterEach
    void reset() {
        System.clearProperty("hermit.router.mode");
        System.clearProperty("hermit.router.trace");
        jevStatus = 200;
        jevCalls.set(0);
    }

    static ModelRouter router() {
        return new ModelRouter(new DecisionClient("http://127.0.0.1:" + jev.getAddress().getPort() + "/v1/systemone"));
    }

    static void jevChooses(String choice, double confidence) {
        jevReply = "{\"model\":\"jev-test\",\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"" + choice
                + "\",\"confidence\":" + confidence + ",\"probabilities\":{\"" + choice + "\":" + confidence + "}}}}";
    }

    static ModelRouter.Signals signals(ModelRouter.Point point, int answerRefusals, int planRefusals, int flips) {
        return new ModelRouter.Signals(point, 9, 30, answerRefusals, planRefusals, flips, 2, 25, true, true);
    }

    @Test
    void hermitsRulesDecideTheClearCasesAndLeaveTheRestOpen() {
        assertEquals(ModelRouter.Route.CLOUD, ModelRouter.rules(signals(ModelRouter.Point.FLIP_REVIEW, 0, 0, 0)).route());
        assertEquals(ModelRouter.Route.CLOUD, ModelRouter.rules(signals(ModelRouter.Point.VERDICT_REVIEW, 0, 0, 1)).route(),
                "a verdict that has changed is reviewed by the stronger model");
        assertEquals(ModelRouter.Route.CLOUD, ModelRouter.rules(signals(ModelRouter.Point.VERDICT_REVIEW, 2, 0, 0)).route());
        assertNull(ModelRouter.rules(signals(ModelRouter.Point.VERDICT_REVIEW, 1, 0, 0)).route(), "a first verdict is open");
        assertEquals(ModelRouter.Route.LOCAL, ModelRouter.rules(signals(ModelRouter.Point.PLAN_AUTHOR, 0, 1, 0)).route());
        assertEquals(ModelRouter.Route.CLOUD, ModelRouter.rules(signals(ModelRouter.Point.PLAN_AUTHOR, 0, 3, 0)).route());
        assertNull(ModelRouter.rules(signals(ModelRouter.Point.PLAN_AUTHOR, 1, 2, 0)).route());
        assertNull(ModelRouter.rules(signals(ModelRouter.Point.SYNTHESIS, 1, 0, 0)).route());
        assertEquals(ModelRouter.Route.CLOUD, ModelRouter.rules(signals(ModelRouter.Point.SYNTHESIS, 2, 0, 0)).route());
    }

    @Test
    void theLimitsHoldInEveryModeJevIncluded() {
        jevChooses("cloud", 0.99);
        for (String mode : List.of("rules", "jev-fallback", "jev-main")) {
            System.setProperty("hermit.router.mode", mode);
            var noCloud = new ModelRouter.Signals(ModelRouter.Point.FLIP_REVIEW, 9, 30, 3, 3, 2, 0, 25, true, false);
            assertEquals(ModelRouter.Route.LOCAL, router().decide(noCloud).route(), mode);
            var spent = new ModelRouter.Signals(ModelRouter.Point.FLIP_REVIEW, 9, 30, 3, 3, 2, 25, 25, true, true);
            ModelRouter.Decision d = router().decide(spent);
            assertEquals(ModelRouter.Route.LOCAL, d.route(), mode);
            assertEquals("limit", d.by());
        }
        assertEquals(0, jevCalls.get(), "a limit is never put to Jev");
    }

    @Test
    void offByDefaultKeepsTheEstablishedRuleTheHostedModelWheneverItIsUp() {
        jevChooses("local", 0.99);
        var spentBudget = new ModelRouter.Signals(ModelRouter.Point.PLAN_AUTHOR, 2, 30, 0, 0, 0, 99, 25, true, true);
        assertEquals(ModelRouter.Route.CLOUD, router().decide(spentBudget).route(), "no budget, no rules, no Jev");
        var noCloud = new ModelRouter.Signals(ModelRouter.Point.PLAN_AUTHOR, 2, 30, 0, 0, 0, 0, 25, true, false);
        assertEquals(ModelRouter.Route.LOCAL, router().decide(noCloud).route());
        assertEquals(0, jevCalls.get());
    }

    @Test
    void rulesModeKeepsAnOpenCaseLocalAndNeverAsksJev() {
        System.setProperty("hermit.router.mode", "rules");
        jevChooses("cloud", 0.99);
        assertEquals(ModelRouter.Route.LOCAL, router().decide(signals(ModelRouter.Point.VERDICT_REVIEW, 1, 0, 0)).route());
        assertEquals(0, jevCalls.get());
    }

    @Test
    void jevFallbackAsksJevOnlyWhenTheRulesLeaveTheCaseOpen() {
        System.setProperty("hermit.router.mode", "jev-fallback");
        jevChooses("cloud", 0.9);
        ModelRouter.Decision open = router().decide(signals(ModelRouter.Point.VERDICT_REVIEW, 1, 0, 0));
        assertEquals(ModelRouter.Route.CLOUD, open.route());
        assertEquals("jev", open.by());
        assertEquals(1, jevCalls.get());
        jevChooses("cloud", 0.9);
        ModelRouter.Decision ruled = router().decide(signals(ModelRouter.Point.PLAN_AUTHOR, 0, 1, 0));
        assertEquals(ModelRouter.Route.LOCAL, ruled.route(), "the rules decided: Jev is not asked");
        assertEquals("rules", ruled.by());
        assertEquals(1, jevCalls.get());
    }

    @Test
    void jevMainDecidesEvenWhereTheRulesWouldAndFallsBackToThemWhenUnsure() {
        System.setProperty("hermit.router.mode", "jev-main");
        jevChooses("local", 0.8);
        ModelRouter.Decision overruled = router().decide(signals(ModelRouter.Point.FLIP_REVIEW, 0, 0, 0));
        assertEquals(ModelRouter.Route.LOCAL, overruled.route(), "Jev's choice stands over the rules' CLOUD");
        assertEquals("jev", overruled.by());
        jevChooses("local", 0.52);
        ModelRouter.Decision unsure = router().decide(signals(ModelRouter.Point.FLIP_REVIEW, 0, 0, 0));
        assertEquals(ModelRouter.Route.CLOUD, unsure.route(), "below the confidence floor the rules decide");
        assertTrue(unsure.by().startsWith("rules"), unsure.by());
        jevStatus = 500;
        jevReply = "{}";
        ModelRouter.Decision down = router().decide(signals(ModelRouter.Point.PLAN_AUTHOR, 0, 3, 0));
        assertEquals(ModelRouter.Route.CLOUD, down.route(), "Jev down: the rules decide");
    }

    @Test
    void jevSeesTheSituationButNotTheCaseAndEveryDecisionIsTraced() throws Exception {
        String state = ModelRouter.stateFor(signals(ModelRouter.Point.VERDICT_REVIEW, 2, 3, 1));
        assertTrue(state.contains("second reviewer") && state.contains("refused 2 time(s)") && state.contains("changed 1 time(s)"), state);
        Path trace = Files.createTempFile("router", ".jsonl");
        System.setProperty("hermit.router.trace", trace.toString());
        System.setProperty("hermit.router.mode", "jev-main");
        jevChooses("cloud", 0.95);
        router().decide(signals(ModelRouter.Point.PLAN_AUTHOR, 0, 1, 0));
        String row = Files.readString(trace);
        assertTrue(row.contains("\"point\":\"PLAN_AUTHOR\"") && row.contains("\"route\":\"CLOUD\"") && row.contains("\"by\":\"jev\"")
                && row.contains("\"rulesRoute\":\"LOCAL\""), row);
        assertFalse(row.isBlank());
    }

    // a choice of model, not of route (user, 2026-10-21): no Jev; the rule's model nine times in ten, and one time in ten
    // one of the other models offered, so the drive also sees what the others do; every choice is traced
    @Test
    void theRuleChoosesTheModelAndOneChoiceInTenTriesAnother() throws Exception {
        Path trace = Files.createTempFile("choices", ".jsonl");
        System.setProperty("hermit.router.trace", trace.toString());
        List<String> models = List.of("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna", "openai/gpt-oss-120b:preferred");
        ModelRouter.Signals counts = signals(null, 0, 1, 0);
        try {
            System.setProperty("hermit.router.explore", "0");
            ModelRouter.ModelChoice c = router().choose(ModelRouter.Pick.INITIAL, counts, models, models.get(0), "the job's own order");
            assertEquals("gpt-6-astra", c.model());
            assertEquals("rules", c.by());

            System.setProperty("hermit.router.explore", "1");
            Set<String> others = new HashSet<>();
            for (int i = 0; i < 60; i++) {
                c = router().choose(ModelRouter.Pick.INITIAL, counts, models, models.get(0), "the job's own order");
                assertEquals("exploration", c.by());
                others.add(c.model());
            }
            assertEquals(Set.of("gpt-6.1-sol", "gpt-6-luna", "openai/gpt-oss-120b:preferred"), others,
                    "the others, never the rule's model");

            System.clearProperty("hermit.router.explore");
            int explored = 0;
            for (int i = 0; i < 2000; i++) {
                if ("exploration".equals(router().choose(ModelRouter.Pick.STEP, counts, models.subList(2, 4), models.get(2), "a routine step").by())) {
                    explored++;
                }
            }
            assertTrue(explored > 120 && explored < 280, "about one in ten by default: " + explored);
            assertTrue(Files.readString(trace).contains("\"by\":\"exploration\""), Files.readString(trace));
            assertEquals(0, jevCalls.get(), "Jev is not asked");
        } finally {
            System.clearProperty("hermit.router.explore");
        }
    }
}
