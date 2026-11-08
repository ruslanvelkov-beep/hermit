package hermit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import hermit.answer.AnswerGate;
import hermit.api.Action;
import hermit.api.AgentListener;
import hermit.checkpoint.Checkpoint;
import hermit.checkpoint.TurnReplay;
import hermit.checkpoint.TurnSnapshot;
import hermit.model.ChatClient;
import hermit.model.ModelEndpoint;
import hermit.model.escalation.EscalationLadder;
import hermit.plan.PlanGate;
import hermit.plan.RepeatGuard;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The whole loop stops at a checkpoint in one agent and continues in ANOTHER agent (a new process in real use) from the
 * same state - the second agent never re-runs iteration 1, sees its results in context, and answers. The model is a
 * scripted local HTTP server: a Bash plan while nothing has run, a final answer once iteration 1's results are in the
 * context, and "approved" for every review or gate prompt.
 */
class AgentResumeTest {

    static HttpServer server;
    static final AtomicInteger planningCalls = new AtomicInteger();
    static final AtomicInteger authorCalls = new AtomicInteger();
    static final List<String> authorPrompts = new ArrayList<>();
    static final AtomicInteger repeatPlans = new AtomicInteger();
    static volatile String repeatFile = "/nonexistent";
    static String scriptedUrl;
    static final List<String> prompts = new ArrayList<>();
    static final List<String> shadowStates = new ArrayList<>();
    static final List<String> plannerModels = new ArrayList<>();
    static final List<String> initialPlanModels = new ArrayList<>();
    static final AtomicInteger hostedCritics = new AtomicInteger();
    static final List<String> reviewedPlans = new ArrayList<>();
    static final List<String> taskPlanCalls = new ArrayList<>();   // "<job>:<model>" for each task-plan call
    static final List<String> taskPlanReviews = new ArrayList<>();   // the review prompts of a task-plan turn

    static String json(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
    }

    @BeforeAll
    static void scriptedModel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            Matcher asked = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            if (body.contains("Shape A") && asked.find()) {
                synchronized (plannerModels) {
                    plannerModels.add(asked.group(1));
                }
            }
            if (body.contains("critical reviewer of an agent's PLAN") && asked.find(0) && asked.group(1).equals("gpt-6-luna")) {
                hostedCritics.incrementAndGet();   // the hosted critic of a complex plan
            }
            if (body.contains("taskplan-marker") && body.contains("critical reviewer of an agent's PLAN")) {
                synchronized (taskPlanReviews) {
                    taskPlanReviews.add(body);
                }
            }
            if (body.contains("taskplan-marker")) {   // the task plan: written, judged, looked ahead, revised
                String job = body.contains("Write the TASK PLAN") ? "plan" : body.contains("Is the CURRENT step done") ? "done"
                        : body.contains("Check the steps ahead") ? "ahead" : body.contains("Rewrite the CURRENT step") ? "revise"
                        : body.contains("Shape A") ? "step" : null;
                if (job != null && !job.equals("step")) {
                    synchronized (taskPlanCalls) {
                        taskPlanCalls.add(job + ":" + (asked.find(0) ? asked.group(1) : "?")
                                + (body.contains("Supervisor refused the task plan") ? ":after-refusal" : ""));
                    }
                    String r = switch (job) {
                        case "plan" -> "{\"steps\": [{\"goal\": \"Run the echo\"}, {\"goal\": \"Report what it printed\"}]}";
                        case "done" -> body.contains("stuck-marker") ? "{\"done\": false, \"why\": \"nothing new came back\"}"
                                : "{\"done\": true, \"why\": \"the echo printed resume-marker-42\"}";
                        case "ahead" -> "{\"steps\": [{\"goal\": \"Report what it printed\"}]}";
                        default -> "{\"steps\": [{\"goal\": \"Run the echo once more, with its output\"}, {\"goal\": \"Report it\"}]}";
                    };
                    byte[] out = json(r).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                    return;
                }
            }
            if (body.contains("failcall-marker")) {   // the step planner's call fails; the author writes the plan
                if (body.contains("critical reviewer of an agent's PLAN")) {
                    synchronized (reviewedPlans) {
                        reviewedPlans.add(body);
                    }
                } else if (body.contains("you must author the corrective next step")) {
                    String plan = "{\"branches\": [{\"name\": \"authored\", \"confidence\": 0.95, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
                    byte[] out = json(plan).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                    return;
                } else if (body.contains("Shape A") && !body.contains("Executed branch")) {
                    byte[] err = "{\"error\":{\"message\":\"Tool choice is none, but model called a tool\"}}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(400, err.length);
                    ex.getResponseBody().write(err);
                    ex.close();
                    return;
                }
            }
            if (body.contains("Shape A") && body.contains("weekday-marker")) {   // a wrong weekday, then the fix
                planningCalls.incrementAndGet();
                reply = body.contains("a Tuesday, not a Monday")
                        ? "{\"final_answer\": \"The command printed resume-marker-42 (Bash exit 0) on Tue Oct 20, 2026.\"}"
                        : body.contains("Executed branch")
                        ? "{\"final_answer\": \"The command printed resume-marker-42 (Bash exit 0) on Mon Oct 20, 2026.\"}"
                        : "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
                byte[] out = json(reply).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
                return;
            }
            if (body.contains("Write the FIRST plan") && body.contains("initial-marker")) {   // the task's first plan
                synchronized (initialPlanModels) {
                    initialPlanModels.add(asked.find(0) ? asked.group(1) : "?");
                }
                reply = "{\"branches\": [{\"name\": \"first\", \"confidence\": 0.9, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
            } else if (body.contains("SECOND REVIEWER") && body.contains("evidence-marker")) {         // the escalation model reviews
                reply = body.contains("TRUE POSITIVE")
                        ? "{\"unsupported_claims\": [\"the first reader sees a foreign page — the locale is "
                        + "compared equal\"], \"verdict_follows\": false, \"note\": \"nothing the reader sees "
                        + "remains\"}"
                        : "{\"unsupported_claims\": [], \"verdict_follows\": true, \"note\": \"follows\"}";
            } else if (body.contains("Shape A") && body.contains("prose-marker")) {                // F373 script
                planningCalls.incrementAndGet();
                if (body.contains("Executed branch")) {
                    reply = "Verdict: FALSE POSITIVE. The echo printed evidence-marker (Bash exit 0); with the same "
                            + "locale and path the first reader sees nothing the second does not, so the finding "
                            + "describes no leak.";
                } else {
                    reply = "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo evidence-marker\"}}]}]}";
                }
            } else if (body.contains("Shape A") && body.contains("verdict-marker")) {              // F357 script
                planningCalls.incrementAndGet();
                if (body.contains("A second reviewer")) {
                    reply = "{\"final_answer\": \"Verdict: FALSE POSITIVE. The echo printed evidence-marker "
                            + "(Bash exit 0); the compared locale gives each reader their own page.\"}";
                } else if (body.contains("Executed branch")) {
                    reply = "{\"final_answer\": \"Verdict: TRUE POSITIVE. The echo printed evidence-marker "
                            + "(Bash exit 0); the first reader sees a foreign page.\"}";
                } else {
                    reply = "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo evidence-marker\"}}]}]}";
                }
            } else if (body.contains("Reviewer rejected the local plan") && body.contains("atonce-marker")) {   // the author answers
                authorCalls.incrementAndGet();
                reply = "{\"final_answer\": \"The command printed atonce-42 (Bash exit 0).\"}";
            } else if (body.contains("Shape A") && body.contains("named-marker")) {   // an answer at once; after the named read, the grounded answer
                planningCalls.incrementAndGet();
                synchronized (prompts) {
                    prompts.add(body);
                }
                reply = body.contains("refusal-named read")
                        ? "{\"final_answer\": \"The file's second line reads named-42-second (sed printed it).\"}"
                        : body.contains("Executed branch")
                        ? "{\"final_answer\": \"The echo printed named-marker-run; the file says something I did not read.\"}"
                        : "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo named-marker-run\"}}]}]}";
            } else if (body.contains("Shape A") && body.contains("cut-marker")) {   // a two-step plan, then the answer
                planningCalls.incrementAndGet();
                reply = body.contains("Executed branch")
                        ? "{\"final_answer\": \"The command printed cut-42 (Bash exit 0).\"}"
                        : "{\"branches\": [{\"name\": \"two\", \"confidence\": 0.9, \"actions\": ["
                        + "{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo cut-42\"}}, "
                        + "{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo wrong-marker\"}}]}]}";
            } else if (body.contains("Shape A") && body.contains("atonce-marker")) {   // the local planner repeats its success verbatim, forever
                planningCalls.incrementAndGet();
                reply = "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo atonce-42\"}}]}]}";
            } else if (body.contains("Reviewer rejected the local plan") && body.contains("repeat-marker")) {   // the author
                authorCalls.incrementAndGet();                                    // only ever writes another covered read
                int n = repeatPlans.incrementAndGet();
                reply = "{\"branches\": [{\"name\": \"authored" + n + "\", \"confidence\": 0.95, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"sed -n '" + (2 + n) + "," + (12 + n) + "p' " + repeatFile + "\"}}]}]}";
            } else if (body.contains("Reviewer rejected the local plan")) {           // the escalated author's prompt
                authorCalls.incrementAndGet();
                synchronized (authorPrompts) {
                    authorPrompts.add(body);
                }
                // the author repeats, verbatim, the command iteration 1 already ran - a plan the repeat guard must see
                reply = "{\"branches\": [{\"name\": \"authored\", \"confidence\": 0.95, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
            } else if (body.contains("You have ALREADY gathered what you need") && body.contains("escalate-marker")) {
                // the local model answers the synthesis with a tool call; the escalation model (a different
                // "model" field in the request) answers in prose
                reply = body.contains("\"model\":\"scripted-plan\"") || body.contains("\"model\" : \"scripted-plan\"")
                        ? "The first 50 lines of Fifty.txt were printed by Bash (exit 0): line 1 through line 50."
                        : "<|channel|>commentary to=repo_browser.bash code<|message|>sed -n '1,50p' " + repeatFile;
            } else if (body.contains("You have ALREADY gathered what you need")
                    && (body.contains("repeat-marker") || body.contains("repeat-halt-marker"))) {
                // the synthesis prompt - an answer from the results (blank for the halt variant)
                reply = body.contains("repeat-halt-marker") ? ""
                        : "The first 50 lines of Fifty.txt were printed by Bash (exit 0): line 1 through line 50.";
            } else if (body.contains("Shape A") && (body.contains("repeat-marker") || body.contains("repeat-halt-marker") || body.contains("escalate-marker"))) {   // covered reads forever
                planningCalls.incrementAndGet();
                int n = repeatPlans.incrementAndGet();
                // the first plan reads lines 1-50; every later plan reads a DIFFERENT range inside them (never the
                // same signature twice, so the exact-repeat loop guard stays quiet; every one is a covered read)
                String range = n == 1 ? "1,50p" : (5 + n) + "," + (15 + n) + "p";
                reply = "{\"branches\": [{\"name\": \"read" + n + "\", \"confidence\": 0.9, \"actions\": "
                        + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"sed -n '" + range + "' " + repeatFile + "\"}}]}]}";
            } else if (body.contains("Shape A") && body.contains("authored-marker")) {   // F342 script
                planningCalls.incrementAndGet();
                if (body.contains("Plan REJECTED — repeat")) {
                    reply = "{\"final_answer\": \"The command printed resume-marker-42 (Bash exit 0).\"}";
                } else if (body.contains("Executed branch")) {          // after the first run the planner keeps going wrong
                    reply = "{\"branches\": [{\"name\": \"wrong\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo wrong-marker\"}}]}]}";
                } else {
                    reply = "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
                }
            } else if (body.contains("Shape A") && body.contains("garble-marker")) {   // never a parseable plan
                planningCalls.incrementAndGet();
                reply = "I would compose the test but here is prose instead of a plan.";
            } else if (body.contains("Shape A")) {                // a planning prompt
                planningCalls.incrementAndGet();
                synchronized (prompts) {
                    prompts.add(body);
                }
                if (body.contains("Executed branch")) {          // iteration 1's results are in the context
                    reply = "{\"final_answer\": \"The command printed resume-marker-42 (Bash exit 0).\"}";
                } else if (body.contains("steer-marker") && !body.contains("REJECTED your last plan")) {
                    reply = "{\"branches\": [{\"name\": \"wrong\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo wrong-marker\"}}]}]}";
                } else if (body.contains("steer-marker")) {
                    reply = "{\"branches\": [{\"name\": \"right\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
                } else {
                    reply = "{\"branches\": [{\"name\": \"echo\", \"confidence\": 0.9, \"actions\": "
                            + "[{\"tool\": \"Bash\", \"parameters\": {\"command\": \"echo resume-marker-42\"}}]}]}";
                }
            } else {                                              // a review lens or an answer gate
                reply = "{\"approved\": true, \"issues\": []}";
            }
            byte[] out = json(reply).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/v1/systemone", ex -> {   // the shadow critic's local decision model
            synchronized (shadowStates) {
                shadowStates.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] out = ("{\"model\":\"scripted\",\"answers\":{\"grounding\":{\"type\":\"score\",\"score\":2.5,\"confidence\":0.6,"
                    + "\"probabilities\":{\"0\":0.0,\"1\":0.0,\"2\":0.5,\"3\":0.5}}}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/v1/models", ex -> {   // the plan-escalation probe
            byte[] out = "{\"data\":[{\"id\":\"scripted\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        scriptedUrl = url;
        ModelEndpoint.setBaseUrl(url);
        ModelEndpoint.setModel("scripted");
        System.setProperty("hermit.chain.disabled", "true");
        System.setProperty("hermit.snippets.enabled", "false");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("hermit.step.dir");
        System.clearProperty("hermit.step.stopAt");
        // set in start(): left behind, they disabled the helper chain and RemoteAgent for every test class run after this one
        System.clearProperty("hermit.chain.disabled");
        System.clearProperty("hermit.snippets.enabled");
    }

    static Path fiftyLines(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return Files.writeString(dir.resolve("Fifty.txt"), sb.toString());
    }

    static final class Iterations implements AgentListener {
        final List<Integer> seen = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int max;
        int toolResults;
        int refusals;

        @Override
        public void onIteration(int i, int max) {
            seen.add(i);
            this.max = max;
        }

        @Override
        public void onToolResults(Map<String, Object> results) {
            toolResults++;
        }

        @Override
        public void onAnswerGate(boolean approved, List<String> issues) {
            if (!approved) {
                refusals++;
            }
        }

        @Override
        public void onPlanRefined(boolean changed, String note) {
            notes.add(note);
        }
    }

    @Test
    void aTurnStoppedAtAnExecutedStepResumesInAnotherAgentWithoutRedoingTheStep() throws Exception {
        Path run = Files.createTempDirectory("hermit-steps");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "executed:1");
        String request = "Run the shell command `echo resume-marker-42` with Bash and report what it printed.";

        Iterations first = new Iterations();
        AgentLoop agent1 = new AgentLoop(first);
        Path stoppedAt = null;
        try {
            agent1.processRequest(request);
            fail("the turn should have stopped at the executed step of iteration 1");
        } catch (Checkpoint.Stop stop) {
            stoppedAt = stop.step;
        }
        assertNotNull(stoppedAt);
        assertEquals("step-0003-executed", stoppedAt.getFileName().toString());   // plan, selected, executed
        assertEquals(List.of(1), first.seen);
        TurnSnapshot saved = Checkpoint.read(stoppedAt);
        assertEquals(2, saved.iteration, "the executed step names the NEXT iteration");
        assertEquals(1, saved.turn);
        assertTrue(saved.haveSuccess);
        assertTrue(saved.succeededTools.contains("Bash"), saved.succeededTools.toString());
        assertTrue(saved.contextLog.contains("resume-marker-42"), "the folded results are in the saved context");
        assertTrue(Files.exists(run.resolve("step-0001-plan/prompt.txt")));
        assertTrue(Files.exists(run.resolve("step-0001-plan/llm-raw.txt")));
        assertTrue(Files.exists(stoppedAt.resolve("results.json")));
        int callsBeforeResume = planningCalls.get();

        // a NEW agent (a new process in real use) picks the turn up where it stopped
        System.setProperty("hermit.step.stopAt", "");
        Iterations second = new Iterations();
        AgentLoop agent2 = new AgentLoop(second);
        String answer = agent2.resume(stoppedAt);

        assertTrue(answer.contains("resume-marker-42"), answer);
        assertEquals(List.of(2), second.seen, "the resumed agent starts at iteration 2 and finishes there");
        assertEquals(callsBeforeResume + 1, planningCalls.get(), "exactly one more plan was asked for");
        assertTrue(second.notes.stream().anyMatch(n -> n.startsWith("RESUMED from step-0003-executed")), second.notes.toString());
        String resumedPrompt;
        synchronized (prompts) {
            resumedPrompt = prompts.get(prompts.size() - 1);
        }
        assertTrue(resumedPrompt.contains("resume-marker-42"), "the resumed plan saw iteration 1's results");
        // the run directory now holds the resumed plan and the answer steps too, numbered on
        List<Path> steps = Checkpoint.steps(run);
        assertEquals(List.of("step-0001-plan", "step-0002-selected", "step-0003-executed", "step-0004-plan", "step-0005-answer"),
                steps.stream().map(p -> p.getFileName().toString()).toList());
        assertTrue(Files.readString(run.resolve("step-0005-answer/answer.txt")).contains("resume-marker-42"));
    }

    // the shadow critic scores the answer at its seam and records the score beside the decision; the turn never sees it
    @Test
    void theShadowCriticRecordsTheAnswersGroundingBesideTheDecisionAndTheTurnNeverSeesIt() throws Exception {
        Path run = Files.createTempDirectory("hermit-shadow");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.shadow.openthai", scriptedUrl + "/systemone");
        try {
            String answer = new AgentLoop(new Iterations()).processRequest(
                    "shadow-check: run the shell command `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            Path step = Checkpoint.steps(run).stream().filter(p -> p.getFileName().toString().endsWith("-answer")).findFirst().orElseThrow();
            JsonNode doc = new ObjectMapper().readTree(step.resolve("shadow.json").toFile());
            assertEquals("continue", doc.path("decision").asText(), doc.toString());
            assertEquals(2.5, doc.path("openthai").path("score").asDouble(), 1e-9, doc.toString());
            String sent;
            synchronized (shadowStates) {
                sent = shadowStates.get(shadowStates.size() - 1);
            }
            assertTrue(sent.contains("REQUEST:\\nshadow-check") && sent.contains("PROPOSED FINAL ANSWER:") && sent.contains("resume-marker-42"), sent);
            TurnSnapshot last = Checkpoint.read(step);
            assertFalse(last.contextLog.contains("PROPOSED FINAL ANSWER") || answer.contains("grounding"), "the score never enters the turn");
        } finally {
            System.clearProperty("hermit.shadow.openthai");
        }
    }

    // the annotator's document of the answer lies beside its step; nothing in the turn reads it, so a wrong weekday
    // goes through while refusals are off
    @Test
    void annotationWrittenNextToTheAnswer() throws Exception {
        Path run = Files.createTempDirectory("hermit-annotation");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        try {
            String answer = new AgentLoop(new Iterations()).processRequest(
                    "weekday-marker: run the shell command `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("Mon Oct 20, 2026"), answer);
            Path step = Checkpoint.steps(run).stream().filter(p -> p.getFileName().toString().endsWith("-answer"))
                    .findFirst().orElseThrow();
            AnnotationDocument doc = AnnotationDocument.fromJson(Files.readString(step.resolve("annotation.json")));
            assertEquals(Files.readString(step.resolve("answer.txt")), doc.text());
            assertEquals(List.of(Check.Status.INCONSISTENT), doc.checks().stream().map(Check::status).toList(),
                    doc.toJson());
        } finally {
            System.clearProperty("hermit.step.dir");
            System.clearProperty("hermit.step.stopAt");
        }
    }

    // with refusals on, an inconsistent date refuses the ending with the check's own sentence, and the turn answers
    // again with the right weekday
    @Test
    void inconsistentDateRefusesTheAnswer() throws Exception {
        System.setProperty("hermit.annotation.refuse", "true");
        try {
            String answer = new AgentLoop(new Iterations()).processRequest(
                    "weekday-marker: run the shell command `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("Tue Oct 20, 2026"), answer);
        } finally {
            System.clearProperty("hermit.annotation.refuse");
        }
    }

    // a dead model endpoint halts an unattended turn after two failed calls instead of spending
    // every iteration; a stepped turn has no such cap (the next test)
    @Test
    void aDeadModelEndpointHaltsTheTurnAfterTwoFailedCalls() throws Exception {
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dead.createContext("/v1/chat/completions", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        dead.start();
        String live = ModelEndpoint.baseUrl();
        try {
            ModelEndpoint.setBaseUrl("http://127.0.0.1:" + dead.getAddress().getPort() + "/v1");
            System.clearProperty("hermit.step.dir");
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("Run `echo x` with Bash and report the output.");
            assertTrue(answer.startsWith("Agent halted: the model endpoint failed 2 times"), answer);
            assertEquals(List.of(1, 2), seen.seen, "two iterations, not the whole budget");
        } finally {
            ModelEndpoint.setBaseUrl(live);
            dead.stop(0);
        }
    }

    // stepping mode has no caps: failed model calls do not end a stepped turn; the operator does (here, stopAt)
    @Test
    void aSteppedTurnGoesOnPastFailedModelCallsUntilTheOperatorStopsIt() throws Exception {
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dead.createContext("/v1/chat/completions", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        dead.start();
        String live = ModelEndpoint.baseUrl();
        Path run = Files.createTempDirectory("hermit-steps-dead");
        try {
            ModelEndpoint.setBaseUrl("http://127.0.0.1:" + dead.getAddress().getPort() + "/v1");
            System.setProperty("hermit.step.dir", run.toString());
            System.setProperty("hermit.step.stopAt", "plan:4");
            Iterations seen = new Iterations();
            Checkpoint.Stop stop = assertThrows(Checkpoint.Stop.class,
                    () -> new AgentLoop(seen).processRequest("Run `echo x` with Bash and report the output."));
            assertTrue(stop.step.getFileName().toString().endsWith("-plan"), stop.step.toString());
            assertEquals(List.of(1, 2, 3, 4), seen.seen, "on past the two failed calls an unattended turn halts at");
        } finally {
            System.setProperty("hermit.step.stopAt", "");
            ModelEndpoint.setBaseUrl(live);
            dead.stop(0);
        }
    }

    // a supervisor rejects the SELECTED plan before it runs; the note reaches the planner as a review issue
    // (same wording as a critic's), the planner re-plans, and nothing of the rejected plan ever executed
    @Test
    void aRejectedSelectionIsReplannedFromTheNoteBeforeAnythingRuns() throws Exception {
        Path run = Files.createTempDirectory("hermit-steer");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "selected");
        try {
            Thread supervisor = new Thread(() -> {
                try {
                    Path first = run.resolve("step-0002-selected");
                    while (!Files.exists(first.resolve("waiting"))) {
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                    String plan = Files.readString(first.resolve("selected.json"));
                    if (plan.contains("wrong-marker")) {
                        Checkpoint.reject(first, "echoing wrong-marker does not print the requested value; echo resume-marker-42");
                    } else {
                        Checkpoint.control(first, Checkpoint.Control.CONTINUE);
                    }
                    // every later selected step continues
                    for (int i = 3; i < 12; i++) {
                        Path p = run.resolve(String.format("step-%04d-selected", i));
                        long until = System.currentTimeMillis() + 20000;
                        while (!Files.exists(p.resolve("waiting")) && System.currentTimeMillis() < until) {
                            TimeUnit.MILLISECONDS.sleep(50);
                            if (Files.exists(run.resolve(String.format("step-%04d-answer", i)))) {
                                break;
                            }
                        }
                        if (Files.exists(p.resolve("waiting"))) {
                            Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            supervisor.setDaemon(true);
            supervisor.start();
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("steer-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            String first = Files.readString(run.resolve("step-0002-selected/selected.json"));
            assertTrue(first.contains("wrong-marker"), first);
            assertTrue(Files.readString(run.resolve("step-0002-selected/controlled")).startsWith("reject "));
            TurnSnapshot after = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(after.contextLog.contains("REJECTED your last plan"), "the note entered the context as a review issue");
            // the next planning prompt carried the refused plan AND the reason as a ledger at its end, and the
            // ledger closed once a plan ran successfully
            synchronized (prompts) {
                assertTrue(prompts.stream().anyMatch(p -> p.contains("FAILED SINCE YOUR LAST SUCCESS")
                        && p.contains("echo wrong-marker") && p.contains("does not print the requested value")
                        && p.indexOf("FAILED SINCE YOUR LAST SUCCESS") > p.indexOf("CONTEXT SO FAR:")), "the ledger is in the prompt, after the context");
            }
            assertTrue(after.failedSteps.isEmpty(), "a success closed the ledger: " + after.failedSteps);
            assertTrue(after.contextLog.contains("echo resume-marker-42"), after.contextLog);
            assertFalse(after.contextLog.contains("Executed branch 'wrong'"), "the rejected plan never ran");
            assertTrue(Files.exists(run.resolve("step-0002-selected/timing.txt")));
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.step.pauseAt");
        }
    }

    // an unparseable streak with nobody to escalate to halts an unattended turn after three, not at the
    // iteration cap
    @Test
    void anUnparseableStreakHaltsInsteadOfBurningTheBudget() throws Exception {
        System.clearProperty("hermit.step.dir");
        Iterations seen = new Iterations();
        AgentLoop agent = new AgentLoop(seen);
        String answer = agent.processRequest("garble-marker: run `echo x` with Bash and report the output.");
        assertTrue(answer.startsWith("Agent halted: the planner produced 3 unparseable plans in a row"), answer);
        assertEquals(List.of(1, 2, 3), seen.seen, "three iterations, not twelve");
    }

    // stepping mode has no caps: a stepped turn goes on past an unparseable streak until the operator stops it
    @Test
    void aSteppedTurnGoesOnPastAnUnparseableStreakUntilTheOperatorStopsIt() throws Exception {
        Path run = Files.createTempDirectory("hermit-garble");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "plan:5");
        try {
            Iterations seen = new Iterations();
            assertThrows(Checkpoint.Stop.class,
                    () -> new AgentLoop(seen).processRequest("garble-marker: run `echo x` with Bash and report the output."));
            assertEquals(List.of(1, 2, 3, 4, 5), seen.seen, "on past the three an unattended turn halts at");
        } finally {
            System.setProperty("hermit.step.stopAt", "");
        }
    }

    // the plan a stronger author writes after two rejections used to run straight
    // into execution - past the repeat/covered-read/unknown-tool guards and the "selected" seam. It takes the same
    // path as any plan: here the author repeats iteration 1's command verbatim and the repeat guard refuses it.
    @Test
    void anAuthoredCorrectivePlanGoesThroughThePlanGuards() throws Exception {
        Path run = Files.createTempDirectory("hermit-authored");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "selected");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        authorCalls.set(0);
        try {
            Thread supervisor = new Thread(() -> {
                try {
                    long until = System.currentTimeMillis() + 90_000;
                    while (System.currentTimeMillis() < until) {
                        boolean answered;
                        try (var steps = Files.list(run)) {
                            answered = steps.anyMatch(p -> p.getFileName().toString().endsWith("-answer"));
                        }
                        if (answered) {
                            return;
                        }
                        try (var steps = Files.list(run)) {
                            for (Path p : steps.filter(p -> p.getFileName().toString().endsWith("-selected")).sorted().toList()) {
                                if (Files.exists(p.resolve("waiting")) && !Files.exists(p.resolve("controlled"))) {
                                    if (Files.readString(p.resolve("selected.json")).contains("wrong-marker")) {
                                        Checkpoint.reject(p, "echoing wrong-marker prints nothing the request asked for; answer from the run you have");
                                    } else {
                                        Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                                    }
                                }
                            }
                        }
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            supervisor.setDaemon(true);
            supervisor.start();
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("authored-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            assertEquals(1, authorCalls.get(), "the author was asked once, after the second rejection");
            synchronized (authorPrompts) {   // the author saw the ledger with the refused plan in it
                assertTrue(authorPrompts.stream().anyMatch(p -> p.contains("FAILED SINCE YOUR LAST SUCCESS") && p.contains("echo wrong-marker")),
                        "the author's prompt carries the ledger: " + authorPrompts.size());
            }
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("AUTHORED a corrective plan")), seen.notes.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(last.contextLog.contains("[Plan REJECTED — repeat]"), "the authored plan met the repeat guard:\n" + last.contextLog);
            assertTrue(last.contextLog.contains("[Supervisor refused the last plan; this still applies to the next plans] "
                    + "echoing wrong-marker"), "the note that went to the author stays in the context:\n" + last.contextLog);
            int runs = last.contextLog.split("Executed branch", -1).length - 1;
            assertEquals(1, runs, "the echo ran once — the authored repeat never executed:\n" + last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.step.pauseAt");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    // a refusal that names a step cuts the plan there - the steps before it run
    @Test
    void aRefusalNamingAStepCutsThePlanThereAndRunsTheStepsBeforeIt() throws Exception {
        assertEquals(2, ResultSummaries.refusedStepIndex("Step 2 prints wrong-marker, which nothing asked for", 2));
        assertEquals(4, ResultSummaries.refusedStepIndex("Steps 4-6 assume a test class no result has shown", 6));
        assertEquals(-1, ResultSummaries.refusedStepIndex("Step 1 repeats the locate", 3), "the first step refused: nothing to keep");
        assertEquals(-1, ResultSummaries.refusedStepIndex("Step 5 is a guess", 3), "no such step");
        assertEquals(-1, ResultSummaries.refusedStepIndex("the whole plan re-reads what is in context", 3));
        assertEquals(-1, ResultSummaries.refusedStepIndex("step 2 is wrong", 1));
        Path run = Files.createTempDirectory("hermit-cut");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "selected");
        try {
            Thread supervisor = new Thread(() -> {
                try {
                    long until = System.currentTimeMillis() + 90_000;
                    while (System.currentTimeMillis() < until) {
                        boolean answered;
                        try (var steps = Files.list(run)) {
                            answered = steps.anyMatch(p -> p.getFileName().toString().endsWith("-answer"));
                        }
                        if (answered) {
                            return;
                        }
                        try (var steps = Files.list(run)) {
                            for (Path p : steps.filter(p -> p.getFileName().toString().endsWith("-selected")).sorted().toList()) {
                                if (Files.exists(p.resolve("waiting")) && !Files.exists(p.resolve("controlled"))) {
                                    if (Files.readString(p.resolve("selected.json")).contains("wrong-marker")) {
                                        Checkpoint.reject(p, "Step 2 prints wrong-marker, which nothing asked for — run step 1 alone.");
                                    } else {
                                        Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                                    }
                                }
                            }
                        }
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            supervisor.setDaemon(true);
            supervisor.start();
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("cut-marker: run `echo cut-42` with Bash and report what it printed.");
            assertTrue(answer.contains("cut-42"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("cut to its first 1 step")), seen.notes.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(last.contextLog.contains("[Plan CUT at step 2]"), last.contextLog);
            assertTrue(last.contextLog.contains("echo cut-42"), "the first step ran:\n" + last.contextLog);
            assertFalse(last.contextLog.matches("(?s).*Executed branch[^\\n]*wrong-marker.*"), "the refused step never ran:\n" + last.contextLog);
            assertEquals(1, last.contextLog.split("Executed branch", -1).length - 1, "one execution:\n" + last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.step.pauseAt");
        }
    }

    // an answer authored at the last iteration meets the gates instead of the halt
    // (an unattended turn; a stepped turn has no iteration limit)
    @Test
    void anAnswerAuthoredAtTheLastIterationStillMeetsTheGates() throws Exception {
        System.clearProperty("hermit.step.dir");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        System.setProperty("hermit.maxIterations", "2");   // iteration 1 runs the echo, iteration 2 is the repeat → the author's answer is pending at the limit
        authorCalls.set(0);
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("atonce-marker: run `echo atonce-42` with Bash and report what it printed.");
            assertTrue(answer.contains("atonce-42"), "the pending answer met the gates in a grace iteration, not the halt: " + answer);
            assertFalse(answer.contains("maximum iteration limit"), answer);
            assertEquals(1, authorCalls.get());
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("one more iteration for the gates")), seen.notes.toString());
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.remote.minIntervalMs");
            System.clearProperty("hermit.maxIterations");
        }
    }

    // stepping mode has no iteration limit: a stepped turn limited to one iteration answers in its second, and the
    // listener is told there is no cap
    @Test
    void aSteppedTurnGoesOnPastTheIterationLimit() throws Exception {
        Path run = Files.createTempDirectory("hermit-nolimit");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.maxIterations", "1");
        try {
            Iterations seen = new Iterations();
            String answer = new AgentLoop(seen).processRequest(
                    "Run the shell command `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            assertEquals(List.of(1, 2), seen.seen);
            assertEquals(-1, seen.max, "no cap to report");
        } finally {
            System.clearProperty("hermit.maxIterations");
        }
    }

    // a refusal that names a concrete read runs it before the next plan
    @Test
    void aRefusalNamingAReadRunsItBeforeTheNextPlan() throws Exception {
        Path run = Files.createTempDirectory("hermit-named");
        Path file = Files.createTempDirectory("hermit-named-src").resolve("Notes.txt");
        Files.writeString(file, "first line\nnamed-42-second\nthird line\n");
        assertNotNull(RepeatGuard.readNamedIn("read it: sed -n '1,3p' " + file + " and cite it", null));
        assertEquals("grep -n -e MAX_WIDTH " + file,
                RepeatGuard.readNamedIn("grep -n MAX_WIDTH in " + file + " shows it", null).parameters()
                        .get("command"));
        assertNull(RepeatGuard.readNamedIn("sed -n '1,3p' /no/such/Missing.java", null), "a file that does not exist is not run");
        assertNull(RepeatGuard.readNamedIn("read the located ImageResizerImpl around its MAX_WIDTH case", null),
                "no literal command");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "answer");
        try {
            List<String> decisions = Collections.synchronizedList(new ArrayList<>());
            Thread supervisor = new Thread(() -> {
                try {
                    long until = System.currentTimeMillis() + 90_000;
                    boolean rejectedOnce = false;
                    Set<Path> handled = new HashSet<>();   // one decision per step: the agent reads the control file a quarter-second later
                    while (System.currentTimeMillis() < until) {
                        try (var steps = Files.list(run)) {
                            for (Path p : steps.filter(p -> p.getFileName().toString().endsWith("-answer")).sorted().toList()) {
                                if (Files.exists(p.resolve("waiting")) && !Files.exists(p.resolve("controlled")) && handled.add(p)) {
                                    if (!rejectedOnce) {
                                        rejectedOnce = true;
                                        decisions.add("reject " + p.getFileName());
                                        Checkpoint.reject(p, "The answer quotes nothing read — sed -n '1,3p' " + file + " first, then cite the line.");
                                    } else {
                                        decisions.add("continue " + p.getFileName());
                                        Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                                        return;
                                    }
                                }
                            }
                        }
                        TimeUnit.MILLISECONDS.sleep(50);
                    }
                } catch (Exception e) {
                    decisions.add("thread died: " + e);
                    throw new RuntimeException(e);
                }
            });
            supervisor.setDaemon(true);
            supervisor.start();
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("named-marker: run `echo named-marker-run` with Bash, then report the second line of the notes file.");
            TurnSnapshot lastState = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            String diag;
            synchronized (prompts) {
                diag = prompts.stream().filter(p -> p.contains("named-marker")).map(p -> "PROMPT[" + (p.contains("refusal-named read") ? "HAS-READ" : "no-read")
                        + (p.contains("REJECTED your ending") ? ",REJECTED" : "") + "]").collect(Collectors.joining(" "));
            }
            assertTrue(answer.contains("named-42-second"), answer + "\nDECISIONS: " + decisions + "\nNOTES: " + seen.notes + "\n" + diag + "\nCONTEXT: " + lastState.contextLog);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("the refusal names a read — it runs now")), seen.notes.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(last.contextLog.contains("refusal-named read") && last.contextLog.contains("named-42-second"), last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.step.pauseAt");
        }
    }

    // a refusal naming three single-quoted greps runs all three, unquoted correctly
    @Test
    void aRefusalNamingSeveralReadsRunsEachWithItsPatternIntact() throws Exception {
        Path h = Files.createTempDirectory("hermit-named-many");
        Path math = Files.writeString(h.resolve("ZoneMath.java"), "class ZoneMath {\n  setpointCelsius x;\n}\n");
        Path heater = Files.writeString(h.resolve("HeaterSwitch.java"), "class HeaterSwitch {\n  requirePower();\n}\n");
        Path zone = Files.writeString(h.resolve("AbstractZone.java"), "class AbstractZone {\n  targetTemp();\n}\n");
        String note = "reject No read was made since the last refusal. Run these reads and cite their numbered lines: "
                + "grep -n 'setpointCelsius' " + math + " ; grep -n -A3 'requirePower\\|powerFor' " + heater
                + " ; grep -n -A4 'setpointOrDefault\\|targetTemp()\\|private boolean canHeat' "
                + zone + " . Then answer in clean Shape B.";
        List<Action> reads = RepeatGuard.readsNamedIn(note, null);
        List<String> commands = reads.stream().map(a -> String.valueOf(a.parameters().get("command"))).toList();
        assertEquals(List.of(
                "grep -n -e setpointCelsius " + math,
                "grep -n -A3 -e 'requirePower\\|powerFor' " + heater,
                "grep -n -A4 -e 'setpointOrDefault\\|targetTemp()\\|private boolean canHeat' " + zone), commands);
        for (String command : commands) {
            Process run = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start();
            String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, run.waitFor(), command + " → " + out);
            assertTrue(out.startsWith("2:"), command + " → " + out);
        }
        assertEquals("sed -n '1,2p' " + math,
                RepeatGuard.readsNamedIn("read `sed -n \"1,2p\" " + math + "` first", null).get(0)
                .parameters().get("command"), "a backtick ends the command; double quotes are removed");
        assertEquals(List.of(), RepeatGuard.readsNamedIn("grep -n --exec=rm 'x' " + math, null),
                "a flag outside the read-only set is not run");
        assertEquals(List.of(), RepeatGuard.readsNamedIn("grep -n 'x' " + h.resolve("Missing.java"), null), "a file that does not exist is not run");
        assertEquals("grep -n -e 'a b' " + math,
                RepeatGuard.readsNamedIn("grep -n \"a b\" in " + math + ".", null).get(0)
                .parameters().get("command"), "a path that ends a sentence still counts");
        String many = ("grep -n 'setpointCelsius' " + math + " ; ").repeat(1) + ("grep -n 'k' " + heater + " ; ")
                + ("grep -n 'p' " + zone + " ; ") + ("grep -n 'q' " + math + " ; ") + ("grep -n 'r' " + math);
        assertEquals(RepeatGuard.MAX_NAMED_READS, RepeatGuard.readsNamedIn(many, null).size());
    }

    // the type of a file a successful read ran on is not an invented name
    @Test
    void aFileReadThisTurnGroundsItsTypeName() {
        Map<String, String> ran = new LinkedHashMap<>();
        ran.put("Bash|{command=grep -n -A12 -e 'static String slugOf' /wb/impl/PageSlugUtility.java}", "Success");
        ran.put("Bash|{command=grep -n -e x /wb/impl/handlers/MissingHandler.java}", "Error");
        ran.put("Bash|{command=find /wb -name InventedUtility.java}", "Success");
        ran.put("LocateFile|{name=OtherUtility.java}", "Success");
        String evidence = RepeatGuard.withReadFiles("36:    public static String slugOf(...)", ran);
        assertTrue(evidence.contains("PageSlugUtility"), evidence);
        assertFalse(evidence.contains("MissingHandler") || evidence.contains("InventedUtility") || evidence.contains("OtherUtility"), evidence);
        String answer = "The page slug comes from PageSlugUtility (lines 36-45) and drops the accents.";
        assertNotNull(AnswerGate.fabricatedIdentifiersIssue(answer, "36:    public static String slugOf(...)", ""),
                "without the read, the name is unseen");
        assertNull(AnswerGate.fabricatedIdentifiersIssue(answer, evidence, ""));
    }

    // a verbatim repeat of a succeeded call goes to the author at the FIRST refusal
    @Test
    void aVerbatimRepeatOfASucceededCallIsEscalatedAtTheFirstRefusal() throws Exception {
        Path run = Files.createTempDirectory("hermit-atonce");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        authorCalls.set(0);
        int plansBefore = planningCalls.get();
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("atonce-marker: run `echo atonce-42` with Bash and report what it printed.");
            assertTrue(answer.contains("atonce-42"), answer);
            assertEquals(1, authorCalls.get(), "the author was asked once — right after the first verbatim repeat");
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("critics skipped")), "F402: the repeat never reached the critics: " + seen.notes);
            assertEquals(2, planningCalls.get() - plansBefore, "two local plans: the first ran, the second was the repeat; no third");
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertEquals(1, last.repeatGates, "one repeat refusal, not two, before the author was asked");
            assertEquals(1, last.contextLog.split("Executed branch", -1).length - 1, "the echo ran once:\n" + last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    // past the budget, with no author to ask, the answer is synthesized from the results
    // and goes through the answer gates instead of the turn halting on evidence it already had
    @Test
    void aPlannerThatKeepsRepeatingACoveredCallIsAnsweredFromItsResults() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        Path file = run.resolve("Fifty.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(file, sb.toString());
        repeatFile = file.toString();
        repeatPlans.set(0);
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("repeat-marker: print the first 50 lines of " + file + " with Bash and report them.");
            assertTrue(answer.contains("line 1 through line 50"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("synthesized from the results")), seen.notes.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            int runs = last.contextLog.split("Executed branch", -1).length - 1;
            assertEquals(1, runs, "the read ran exactly once:\n" + last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
        }
    }

    // the planner's model is picked each iteration from -Dhermit.planner.models: the lighter one for a routine step, the
    // stronger one once a plan or an ending was refused (one choice in ten tries the other model; none here: explore=0)
    @Test
    void theStepPlannerIsTheLighterModelUntilAPlanIsRefused() throws Exception {
        Path run = Files.createTempDirectory("hermit-models");
        repeatFile = fiftyLines(run).toString();
        repeatPlans.set(0);
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.planner.models", "light-model,heavy-model");
        System.setProperty("hermit.jev.baseUrl", scriptedUrl + "/systemone");
        System.setProperty("hermit.router.explore", "0");
        synchronized (plannerModels) {
            plannerModels.clear();
        }
        try {
            String answer = new AgentLoop(new Iterations())
                    .processRequest("repeat-marker: print the first 50 lines of " + repeatFile + " with Bash and report them.");
            assertTrue(answer.contains("line 1 through line 50"), answer);
            List<String> asked;
            synchronized (plannerModels) {
                asked = new ArrayList<>(plannerModels);
            }
            assertEquals(List.of("light-model", "light-model"), asked.subList(0, 2), "the read, then the first repeat: " + asked);
            assertEquals("heavy-model", asked.get(2), "after the refused repeat: " + asked);
        } finally {
            System.clearProperty("hermit.planner.models");
            System.clearProperty("hermit.jev.baseUrl");
            System.clearProperty("hermit.router.explore");
        }
    }

    // one refused plan handed every later step of the turn to the stronger planner; it takes the
    // plans after a refusal only until one of them runs, and the lighter planner takes the next routine step again
    @Test
    void aRefusalHandsThePlansToTheStrongerModelOnlyUntilOneRuns() {
        String ran = "\n[Iteration 1] Executed branch 'read' — what ran: Bash (1 action). Tool results: {}";
        String refused = "\n[Plan review REJECTED your last plan] Fix ALL of these: write the test first";
        assertFalse(ResultSummaries.refusedSinceLastRun(""));
        assertFalse(ResultSummaries.refusedSinceLastRun(ran));
        assertTrue(ResultSummaries.refusedSinceLastRun(ran + refused));
        assertTrue(ResultSummaries.refusedSinceLastRun(ran + "\n[Answer gate REJECTED your ending] cite the lines"));
        assertTrue(ResultSummaries.refusedSinceLastRun(ran + "\n[Plan REJECTED — read first] read it"));
        assertFalse(ResultSummaries.refusedSinceLastRun(ran + refused + ran.replace("Iteration 1", "Iteration 2")));
    }

    // the task's first plan is written by the first hosted model of -Dhermit.ladder.job.initial_plan (the strongest),
    // and goes through the plan guards and seams; the step planner is asked only from the second iteration on
    @Test
    void theFirstPlanOfATaskIsWrittenByTheInitialPlanModel() throws Exception {
        Path run = Files.createTempDirectory("hermit-initial");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.plan.initial", "true");
        System.setProperty("hermit.openai.baseUrl", scriptedUrl);
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.ladder", "gpt-6-luna");
        System.setProperty("hermit.ladder.job.initial_plan", "gpt-6-astra,gpt-6-luna");
        System.setProperty("hermit.ladder.switch", "");   // Astra writes it every time
        System.setProperty("hermit.jev.baseUrl", scriptedUrl + "/systemone");
        System.setProperty("hermit.router.explore", "0");
        EscalationLadder.resetShared();
        synchronized (initialPlanModels) {
            initialPlanModels.clear();
        }
        int plansBefore = planningCalls.get();
        try {
            Iterations seen = new Iterations();
            String answer = new AgentLoop(seen).processRequest(
                    "initial-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            assertEquals(List.of("gpt-6-astra"), initialPlanModels);
            assertEquals(1, planningCalls.get() - plansBefore, "the step planner planned only the answer");
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("first plan was written by")), seen.notes.toString());
            assertTrue(Files.readString(run.resolve("step-0002-selected/plan.json")).contains("resume-marker-42"),
                    "the first plan reached the selected seam");
        } finally {
            for (String p : List.of("hermit.plan.initial", "hermit.openai.baseUrl", "hermit.openai.apiKey", "hermit.ladder",
                    "hermit.ladder.job.initial_plan", "hermit.ladder.switch", "hermit.jev.baseUrl", "hermit.router.explore")) {
                System.clearProperty(p);
            }
            EscalationLadder.resetShared();
        }
    }

    // a complex plan (here: one action is enough, by -Dhermit.review.hostedCriticActions) is also reviewed by the hosted
    // critic of -Dhermit.ladder.job.plan_review; a simpler one only by the run's own panel
    @Test
    void aComplexPlanIsAlsoReviewedByTheHostedCritic() throws Exception {
        Path run = Files.createTempDirectory("hermit-critic");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.openai.baseUrl", scriptedUrl);
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.ladder", "gpt-6-luna");
        System.setProperty("hermit.ladder.job.plan_review", "gpt-6-luna");
        System.setProperty("hermit.jev.baseUrl", scriptedUrl + "/systemone");
        EscalationLadder.resetShared();
        hostedCritics.set(0);
        String request = "Run the shell command `echo resume-marker-42` with Bash and report what it printed.";
        try {
            assertTrue(new AgentLoop(new Iterations()).processRequest(request).contains("resume-marker-42"));
            assertEquals(0, hostedCritics.get(), "one action is not complex by default");
            System.setProperty("hermit.review.hostedCriticActions", "1");
            assertTrue(new AgentLoop(new Iterations()).processRequest(request).contains("resume-marker-42"));
            assertEquals(1, hostedCritics.get(), "the plan was also reviewed by Luna");
            assertTrue(PlanGate.planIsComplex("{\"branches\":[{\"actions\":[{\"tool\": \"TreeEdit\"}]}]}"), "an edit is complex");
        } finally {
            for (String p : List.of("hermit.openai.baseUrl", "hermit.openai.apiKey", "hermit.ladder", "hermit.ladder.job.plan_review",
                    "hermit.jev.baseUrl", "hermit.review.hostedCriticActions")) {
                System.clearProperty(p);
            }
            EscalationLadder.resetShared();
        }
    }

    // the step planner's call failed (the provider refused a tool call GPT-OSS made), the author
    // wrote the plan, and the review panel read the failure marker LLM_CALL_FAILED as the plan and refused it
    @Test
    void theReviewPanelReadsThePlanTheAuthorWroteAfterAFailedPlannerCall() throws Exception {
        System.clearProperty("hermit.step.dir");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        System.setProperty("hermit.jev.baseUrl", scriptedUrl + "/no-jev");   // no guardrail skips the panel
        synchronized (reviewedPlans) {
            reviewedPlans.clear();
        }
        try {
            String answer = new AgentLoop(new Iterations()).processRequest(
                    "failcall-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            synchronized (reviewedPlans) {
                assertFalse(reviewedPlans.isEmpty(), "the authored plan was reviewed");
                for (String review : reviewedPlans) {
                    assertFalse(review.contains("(JSON):\\n" + ChatClient.LLM_CALL_FAILED), "a failure marker is no plan");
                    assertTrue(review.contains("echo resume-marker-42"), "the panel read the authored plan");
                }
            }
        } finally {
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.remote.minIntervalMs");
            System.clearProperty("hermit.jev.baseUrl");
        }
    }

    static void taskPlanLadder() {
        System.setProperty("hermit.plan.task", "true");
        System.setProperty("hermit.openai.baseUrl", scriptedUrl);
        System.setProperty("hermit.openai.apiKey", "test-key");
        System.setProperty("hermit.ladder", "gpt-6-luna");
        System.setProperty("hermit.ladder.job.task_plan", "gpt-6-astra,gpt-6-luna");
        System.setProperty("hermit.ladder.job.step_done", "gpt-6-luna");
        System.setProperty("hermit.ladder.job.plan_ahead", "gpt-6-luna,gpt-6-astra");
        System.setProperty("hermit.ladder.job.plan_revise", "gpt-6-luna,gpt-6-astra");
        System.setProperty("hermit.ladder.switch", "");   // Astra writes when picked
        System.setProperty("hermit.jev.baseUrl", scriptedUrl + "/systemone");
        System.setProperty("hermit.router.explore", "0");
        EscalationLadder.resetShared();
        synchronized (taskPlanCalls) {
            taskPlanCalls.clear();
        }
        synchronized (taskPlanReviews) {
            taskPlanReviews.clear();
        }
    }

    static void clearTaskPlanLadder() {
        for (String p : List.of("hermit.plan.task", "hermit.openai.baseUrl", "hermit.openai.apiKey", "hermit.ladder",
                "hermit.ladder.job.task_plan", "hermit.ladder.job.step_done", "hermit.ladder.job.plan_ahead",
                "hermit.ladder.job.plan_revise", "hermit.ladder.switch", "hermit.jev.baseUrl", "hermit.router.explore",
                "hermit.plan.reviseAfter",
                "hermit.step.pause", "hermit.step.pauseAt")) {
            System.clearProperty(p);
        }
        EscalationLadder.resetShared();
    }

    // Stage 2 (user, 2026-10-21): the strongest model writes the task's steps first; the supervisor reviews them at the
    // "composed" seam; the step planner plans the current step only; GPT judges when a step is done; the steps ahead are
    // checked again; the plan travels with the state
    @Test
    void aTaskPlanIsWrittenFirstAndTheStepPlannerWorksItsCurrentStep() throws Exception {
        Path run = Files.createTempDirectory("hermit-taskplan");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "composed");
        taskPlanLadder();
        Thread supervisor = new Thread(() -> {
            try {
                for (int i = 0; i < 1200; i++) {
                    try (var steps = Files.list(run)) {
                        for (Path p : steps.filter(p -> p.getFileName().toString().endsWith("-composed")).toList()) {
                            if (Files.exists(p.resolve("waiting")) && !Files.exists(p.resolve("controlled"))) {
                                Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                                return;
                            }
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        supervisor.setDaemon(true);
        supervisor.start();
        int promptsBefore;
        synchronized (prompts) {
            promptsBefore = prompts.size();
        }
        try {
            String answer = new AgentLoop(new Iterations()).processRequest(
                    "taskplan-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            assertTrue(answer.contains("resume-marker-42"), answer);
            List<String> calls;
            synchronized (taskPlanCalls) {
                calls = new ArrayList<>(taskPlanCalls);
            }
            assertEquals("plan:gpt-6-astra", calls.get(0), "the strongest model of the order writes it: " + calls);
            assertTrue(calls.contains("done:gpt-6-luna") && calls.contains("ahead:gpt-6-luna"), calls.toString());
            String firstPlanning;
            synchronized (prompts) {
                firstPlanning = prompts.get(promptsBefore);
            }
            assertTrue(firstPlanning.contains("1. [CURRENT] Run the echo") && firstPlanning.contains("2. Report what it printed"),
                    "the step planner sees the plan and its current step");
            Path composed;
            try (var steps = Files.list(run)) {
                composed = steps.filter(p -> p.getFileName().toString().endsWith("-composed")).findFirst().orElseThrow();
            }
            assertTrue(Files.readString(composed.resolve("taskplan.md")).contains("1. Run the echo"));
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(last.taskPlan.get(0).done, "step 1 was judged done");
            assertTrue(last.contextLog.contains("[Task plan] step 1 done: the echo printed resume-marker-42"), last.contextLog);
            // the panel refused a plan for the current step for leaving out the request's later steps
            synchronized (taskPlanReviews) {
                assertTrue(taskPlanReviews.stream().anyMatch(r -> r.contains("1. [CURRENT] Run the echo")
                        && r.contains("this plan is for the CURRENT step only")), "the panel judges against the current step");
            }
        } finally {
            clearTaskPlanLadder();
        }
    }

    // the supervisor refuses the task plan at the "composed" seam: it is written again with the note, and reviewed again
    @Test
    void aTaskPlanRefusedAtItsSeamIsWrittenAgainWithTheNote() throws Exception {
        Path run = Files.createTempDirectory("hermit-taskplan-refused");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "plan:1");
        System.setProperty("hermit.step.pause", "true");
        System.setProperty("hermit.step.pauseAt", "composed");
        taskPlanLadder();
        Thread supervisor = new Thread(() -> {
            try {
                Set<Path> handled = new HashSet<>();
                for (int i = 0; i < 1200 && handled.size() < 2; i++) {
                    try (var steps = Files.list(run)) {
                        for (Path p : steps.filter(p -> p.getFileName().toString().endsWith("-composed")).sorted().toList()) {
                            if (Files.exists(p.resolve("waiting")) && handled.add(p)) {
                                if (handled.size() == 1) {
                                    Checkpoint.reject(p, "split the first step into the echo and a check of its output");
                                } else {
                                    Checkpoint.control(p, Checkpoint.Control.CONTINUE);
                                }
                            }
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        supervisor.setDaemon(true);
        supervisor.start();
        try {
            assertThrows(Checkpoint.Stop.class, () -> new AgentLoop(new Iterations()).processRequest(
                    "taskplan-marker: run `echo resume-marker-42` with Bash and report what it printed."));
            List<String> calls;
            synchronized (taskPlanCalls) {
                calls = new ArrayList<>(taskPlanCalls);
            }
            assertEquals(List.of("plan:gpt-6-astra", "plan:gpt-6-astra:after-refusal"), calls.subList(0, 2), calls.toString());
            long composedSteps;
            try (var steps = Files.list(run)) {
                composedSteps = steps.filter(p -> p.getFileName().toString().endsWith("-composed")).count();
            }
            assertEquals(2, composedSteps, "the plan written again is reviewed again");
        } finally {
            clearTaskPlanLadder();
            System.clearProperty("hermit.step.stopAt");
        }
    }

    // when the current step is not done after -Dhermit.plan.reviseAfter iterations, the current and later steps are
    // rewritten by the first model of the revision order (the next one up on a later revision)
    @Test
    void aStepThatBringsNothingIsRevised() throws Exception {
        Path run = Files.createTempDirectory("hermit-taskplan-revise");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "plan:3");
        taskPlanLadder();
        System.setProperty("hermit.plan.reviseAfter", "1");
        try {
            try {
                new AgentLoop(new Iterations()).processRequest(
                        "taskplan-marker stuck-marker: run `echo resume-marker-42` with Bash and report what it printed.");
            } catch (Checkpoint.Stop stopped) {
                // the turn may end with its answer, or at the bound
            }
            List<String> calls;
            synchronized (taskPlanCalls) {
                calls = new ArrayList<>(taskPlanCalls);
            }
            assertTrue(calls.contains("revise:gpt-6-luna"), calls.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertEquals("Run the echo once more, with its output", last.taskPlan.get(0).goal);
            assertEquals(1, last.planRevisions);
            assertTrue(last.contextLog.contains("[Task plan] the current and later steps were rewritten"), last.contextLog);
        } finally {
            clearTaskPlanLadder();
            System.clearProperty("hermit.step.stopAt");
        }
    }

    // a repeat never runs; past the budget, with no author to ask and nothing to
    // synthesize, an unattended turn halts
    @Test
    void aPlannerThatKeepsRepeatingACoveredCallHaltsInsteadOfRunningIt() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        Path file = run.resolve("Fifty.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(file, sb.toString());
        repeatFile = file.toString();
        repeatPlans.set(0);
        System.clearProperty("hermit.step.dir");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("repeat-halt-marker: print the first 50 lines of " + file + " with Bash and report them.");
            assertTrue(answer.startsWith("Agent halted: the planner repeated lookups"), answer);
            assertEquals(1, seen.toolResults, "the read ran exactly once");
            assertTrue(seen.refusals >= 5, "the halt came at the fifth refusal: " + seen.refusals);
        } finally {
            System.clearProperty("hermit.step.pause");
        }
    }

    // stepping mode has no caps: each repeat is refused and the stepped turn goes on, past the point an unattended
    // turn halts at, until the operator stops it
    @Test
    void aSteppedTurnGoesOnPastRepeatedLookupsUntilTheOperatorStopsIt() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        repeatFile = fiftyLines(run).toString();
        repeatPlans.set(0);
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "plan:10");
        try {
            Checkpoint.Stop stop = assertThrows(Checkpoint.Stop.class, () -> new AgentLoop(new Iterations())
                    .processRequest("repeat-halt-marker: print the first 50 lines of " + repeatFile + " with Bash and report them."));
            TurnSnapshot last = Checkpoint.read(stop.step);
            assertTrue(last.repeatGates > 5, "on past the fifth refusal: " + last.repeatGates);
            assertEquals(1, last.contextLog.split("Executed branch", -1).length - 1, "the read ran exactly once");
        } finally {
            System.setProperty("hermit.step.stopAt", "");
        }
    }

    // the author's own refused plans drove the repeat count past the old synthesis
    // window (7) before the author ran out - the answer is still synthesized, however many lookups were refused
    @Test
    void theAnswerIsSynthesizedHoweverManyLookupsTheAuthorWasted() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        Path file = run.resolve("Fifty.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(file, sb.toString());
        repeatFile = file.toString();
        repeatPlans.set(0);
        authorCalls.set(0);
        System.clearProperty("hermit.step.dir");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.plan.maxAuthored", "4");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("repeat-marker: print the first 50 lines of " + file + " with Bash and report them.");
            assertTrue(answer.contains("line 1 through line 50"), answer);
            assertEquals(4, authorCalls.get(), "the author was asked up to its budget");
            assertTrue(seen.refusals >= 7, "the refusals reached the old window: " + seen.refusals);
            assertEquals(1, seen.toolResults, "the read ran exactly once");
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.plan.maxAuthored");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    // stepping mode has no caps: the author is asked past its budget
    @Test
    void aSteppedTurnAsksTheAuthorPastItsBudget() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        repeatFile = fiftyLines(run).toString();
        repeatPlans.set(0);
        authorCalls.set(0);
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "plan:9");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.plan.maxAuthored", "1");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        try {
            assertThrows(Checkpoint.Stop.class, () -> new AgentLoop(new Iterations())
                    .processRequest("repeat-marker: print the first 50 lines of " + repeatFile + " with Bash and report them."));
            assertTrue(authorCalls.get() > 1, "a budget of one did not end the author's plans: " + authorCalls.get());
        } finally {
            System.setProperty("hermit.step.stopAt", "");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.plan.maxAuthored");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    // with the helper chain down, the plan-escalation model is the second reviewer
    @Test
    void theEscalationModelReviewsTheVerdictWhenTheHelperChainCannot() throws Exception {
        Path run = Files.createTempDirectory("hermit-verdict");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("verdict-marker: run `echo evidence-marker` with Bash, then rule TRUE or FALSE POSITIVE on it.");
            assertTrue(answer.contains("FALSE POSITIVE"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("second reviewer (") && n.contains("disputes the verdict")), seen.notes.toString());
            TurnSnapshot last = Checkpoint.read(Checkpoint.steps(run).get(Checkpoint.steps(run).size() - 1));
            assertTrue(last.contextLog.contains("A second reviewer") && last.contextLog.contains("foreign page"),
                    last.contextLog);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    @Test
    void withTheEscalationLadderOnTheVerdictIsReviewedOnItsLowestRungAndTraced() throws Exception {
        Path run = Files.createTempDirectory("hermit-ladder");
        Path trace = run.resolve("ladder.jsonl");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        System.setProperty("hermit.ladder", "gpt-6-luna,deepseek-flash");
        System.setProperty("hermit.ladder.trace", trace.toString());
        System.setProperty("hermit.openai.baseUrl", scriptedUrl);
        System.setProperty("hermit.openai.apiKey", "test-key");
        EscalationLadder.resetShared();
        try {
            Iterations seen = new Iterations();
            String answer = new AgentLoop(seen).processRequest(
                    "verdict-marker: run `echo evidence-marker` with Bash, then rule TRUE or FALSE POSITIVE on it.");
            assertTrue(answer.contains("FALSE POSITIVE"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("second reviewer (gpt-6-luna") && n.contains("disputes the verdict")),
                    seen.notes.toString());
            List<String> rows = Files.readAllLines(trace);
            assertTrue(rows.stream().anyMatch(r -> r.contains("\"job\":\"VERDICT_REVIEW\"") && r.contains("\"model\":\"gpt-6-luna\"")
                    && r.contains("\"ok\":true")), String.join("\n", rows));
        } finally {
            for (String p : List.of("hermit.step.pause", "hermit.ladder", "hermit.ladder.trace", "hermit.openai.baseUrl", "hermit.openai.apiKey")) {
                System.clearProperty(p);
            }
            EscalationLadder.resetShared();
        }
    }

    // when the local model answers the synthesis with a tool call, the escalation
    // model is asked, and the answer it gives is the answer
    @Test
    void theEscalationModelSynthesizesWhenTheLocalOneEmitsAToolCall() throws Exception {
        Path run = Files.createTempDirectory("hermit-repeat");
        Path file = run.resolve("Fifty.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(file, sb.toString());
        repeatFile = file.toString();
        repeatPlans.set(0);
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        System.setProperty("hermit.plan.baseUrl", scriptedUrl);
        System.setProperty("hermit.plan.model", "scripted-plan");
        System.setProperty("hermit.plan.maxAuthored", "0");
        System.setProperty("hermit.remote.minIntervalMs", "0");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("escalate-marker: print the first 50 lines of " + file + " with Bash and report them.");
            assertTrue(answer.contains("line 1 through line 50"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("synthesized by plan @")), "the escalation endpoint answered: " + seen.notes);
            // the escalation model is asked FIRST, so the helper chain is never reached when it answers
            assertFalse(seen.notes.stream().anyMatch(n -> n.startsWith("synthesis: the helper chain")), "the helper chain was not needed: " + seen.notes);
        } finally {
            System.clearProperty("hermit.step.pause");
            System.clearProperty("hermit.plan.baseUrl");
            System.clearProperty("hermit.plan.model");
            System.clearProperty("hermit.plan.maxAuthored");
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }

    // a ruling written in prose is the final answer - it is never sent for JSON repair
    @Test
    void aProseRulingIsAcceptedAsTheFinalAnswer() throws Exception {
        Path run = Files.createTempDirectory("hermit-prose");
        System.setProperty("hermit.step.dir", run.toString());
        System.setProperty("hermit.step.stopAt", "");
        System.setProperty("hermit.step.pause", "false");
        System.clearProperty("hermit.plan.baseUrl");
        try {
            Iterations seen = new Iterations();
            AgentLoop agent = new AgentLoop(seen);
            String answer = agent.processRequest("prose-marker: run `echo evidence-marker` with Bash, then rule TRUE or FALSE POSITIVE on it.");
            assertTrue(answer.contains("FALSE POSITIVE"), answer);
            assertTrue(seen.notes.stream().anyMatch(n -> n.contains("prose ruling is a final answer")), seen.notes.toString());
        } finally {
            System.clearProperty("hermit.step.pause");
        }
    }

    @org.junit.jupiter.api.Test
    void proseAfterANamedReadDoesNotSpoilIt() throws Exception {
        Path h = Files.createTempDirectory("hermit-named-prose");
        Path timer = Files.writeString(h.resolve("TimerHandler.java"), "class TimerHandler {\n  void handle() {}\n}\n");
        String note = "Right question, but a locate reads nothing. Read the body directly: sed -n '1,2p' " + timer
                + " (how a timer adds listeners and when it fires).";
        List<Action> reads = RepeatGuard.readsNamedIn(note, null);
        assertEquals(List.of("sed -n '1,2p' " + timer),
                reads.stream().map(a -> String.valueOf(a.parameters().get("command"))).toList());
    }

    @Test
    void replayingTheSelectedSeamsOwnPlanIsNotARepeatOfIt() {
        // a turn resumed at a "selected" seam replays the plan the seam
        // holds, and the repeat guard counted the replay as one more repeat of that plan and had it replaced
        TurnSnapshot once = new TurnSnapshot();
        once.seam = "selected";
        once.lastActionSig = "TreeEdit:addType";
        once.repeatCount = 0;
        TurnSnapshot replay = TurnReplay.replayState(once);
        assertEquals("", replay.lastActionSig, "the replay counts as the plan's first selection");
        TurnSnapshot twice = new TurnSnapshot();
        twice.seam = "selected";
        twice.lastActionSig = "TreeEdit:addType";
        twice.repeatCount = 1;
        assertEquals(0, TurnReplay.replayState(twice).repeatCount);
        assertEquals("TreeEdit:addType", TurnReplay.replayState(twice).lastActionSig);
        TurnSnapshot executed = new TurnSnapshot();
        executed.seam = "executed";
        executed.lastActionSig = "Bash:echo";
        executed.repeatCount = 1;
        assertEquals(1, TurnReplay.replayState(executed).repeatCount, "only a selected seam replays its plan");
    }
}
