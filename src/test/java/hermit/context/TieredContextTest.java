package hermit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hermit.plan.PlanPromptBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The owner, 2026-10-26: older history summarised instead of dropped. Measured on 259 recorded planning steps, a 16k
 * context kept 57% of the earlier results a plan needed when it dropped the oldest iterations, and 78% when the newest
 * third stayed whole and the older chunks went in as summaries of about 1,500 and under 1,000 characters.
 */
class TieredContextTest {

    private static String iteration(int n, char fill) {
        return "[Iteration " + n + "] Executed branch 'b" + n + "' Tool results: {Bash={output=" + String.valueOf(fill).repeat(5000)
                + " File" + n + ".java}}\n";
    }

    private static String context(int iterations) {
        StringBuilder s = new StringBuilder("Current request: port the files\nSteps executed for the current request follow.\n");
        for (int i = 1; i <= iterations; i++) {
            s.append(iteration(i, (char) ('A' + i)));   // upper case: never a summary's letters
        }
        return s.toString();
    }

    /** A summary that names its chunk's last file, as a summariser told to keep every name would. */
    private static String[] summary(String chunk) {
        Matcher m = Pattern.compile("File\\d+\\.java").matcher(chunk);
        String name = m.find() ? m.group() : "none";
        return new String[] {"MEDIUM about " + name + " " + "m".repeat(400), "SHORT about " + name + " " + "s".repeat(200)};
    }

    @Test
    void aContextThatFitsIsUnchanged() {
        String small = context(2);
        assertSame(small, new TieredContext(TieredContextTest::summary).fit(small, 100_000));
    }

    @Test
    void theNewestHalfStaysWholeAndOlderChunksGoInAsSummaries() {
        String big = context(12);   // about 61,000 characters
        String fitted = new TieredContext(TieredContextTest::summary).fit(big, 20_000);
        assertTrue(fitted.length() <= 20_000, "within the budget: " + fitted.length());
        assertTrue(fitted.startsWith("Current request: port the files"), "the request stays first");
        assertTrue(fitted.contains(String.valueOf('m').repeat(5)) && fitted.contains("MEDIUM about File"), "medium summaries");
        assertTrue(fitted.contains("SHORT about File"), "short summaries for the oldest");
        assertTrue(fitted.contains("M".repeat(2000)), "the newest iteration's text is whole");
        assertTrue(fitted.contains("File12.java"), "the newest file is named");
        assertTrue(fitted.contains("[summary of iteration"), "a summary says it is one, with its iteration");
        // more of the history is named than when the oldest iterations are dropped whole
        long named = IntStream.rangeClosed(1, 12).filter(i -> fitted.contains("File" + i + ".java")).count();
        String dropped = PlanPromptBuilder.fitContext(big, 20_000);
        long namedDropped = IntStream.rangeClosed(1, 12).filter(i -> dropped.contains("File" + i + ".java")).count();
        assertTrue(named > namedDropped, named + " files named against " + namedDropped);
    }

    @Test
    void theHistoryStaysInTheOrderItRan() {
        String fitted = new TieredContext(TieredContextTest::summary).fit(context(12), 20_000);
        List<Integer> order = new ArrayList<>();
        Matcher m = Pattern.compile("iteration (\\d+)|\\[Iteration (\\d+)\\]").matcher(fitted);
        while (m.find()) {
            order.add(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        List<Integer> sorted = new ArrayList<>(order);
        sorted.sort(null);
        assertEquals(sorted, order);
    }

    @Test
    void aChunkIsSummarisedOnceAndAFailedSummaryKeepsTheChunksStart() {
        AtomicInteger calls = new AtomicInteger();
        TieredContext tiered = new TieredContext(chunk -> {
            calls.incrementAndGet();
            return null;   // the summariser failed
        });
        String big = context(12);
        String first = tiered.fit(big, 20_000);
        int afterFirst = calls.get();
        tiered.fit(big, 20_000);
        assertEquals(afterFirst, calls.get(), "each chunk is asked about once");
        assertTrue(first.contains("[start of iteration"), "a chunk without a summary keeps its start, marked: " + first.length());
        assertTrue(first.length() <= 20_000);
    }

    @Test
    void theHostedSummaryIsReadFromItsJsonAndCutToItsLength() {
        String[] s = TieredContext.parseSummary("Sure. {\"medium\": \"" + "x".repeat(2000) + "\", \"short\": \"y\"}");
        assertEquals(1500, s[0].length());
        assertEquals("y", s[1]);
        assertEquals(null, TieredContext.parseSummary("no json here"));
    }

    // the owner, 2026-10-27: overnight GPT-OSS-20B is reached on the local server, never through the router; the
    // summaries were sent only where a hosted platform's key was found, so a local URL summarised nothing
    @Test
    void aLocalServerSummarisesWithoutAKey() throws Exception {
        String[] seen = new String[2];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seen[0] = ex.getRequestHeaders().getFirst("Authorization");
            seen[1] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"medium\\\": \\\"m\\\", "
                    + "\\\"short\\\": \\\"s\\\"}\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            System.setProperty("hermit.context.summaryUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            System.setProperty("hermit.context.summaryModel", "gpt-oss-20b");
            String[] summary = TieredContext.hostedSummary("[Iteration 1] a chunk");
            assertEquals("m", summary[0]);
            assertEquals("s", summary[1]);
            assertEquals(null, seen[0], "no key goes to a local server");
            assertTrue(seen[1].contains("\"model\":\"gpt-oss-20b\""), seen[1]);
            assertTrue(seen[1].contains("\"reasoning_effort\":\"low\""), seen[1]);
            // a model that thinks at any effort but "none" summarises with "none"
            System.setProperty("hermit.context.summaryReasoningEffort", "none");
            TieredContext.hostedSummary("[Iteration 2] another chunk");
            assertTrue(seen[1].contains("\"reasoning_effort\":\"none\""), seen[1]);
        } finally {
            System.clearProperty("hermit.context.summaryUrl");
            System.clearProperty("hermit.context.summaryModel");
            System.clearProperty("hermit.context.summaryReasoningEffort");
            server.stop(0);
        }
    }
}
