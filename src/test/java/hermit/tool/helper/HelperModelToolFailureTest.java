package hermit.tool.helper;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import hermit.model.HelperEndpoint;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** a failed completion says what the endpoint said instead of a bare null. */
class HelperModelToolFailureTest {

    static HttpServer server;

    @BeforeAll
    static void failingEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            byte[] out = ("{\"error\":{\"message\":\"Request too large for model\","
                    + "\"type\":\"tokens\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(413, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void aFailedCompletionKeepsTheEndpointsAnswer() {
        System.setProperty("hermit.remote.minIntervalMs", "0");
        try {
            HelperModelTool tool = new HelperModelTool(new HelperEndpoint("plan",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "scripted", null));
            assertNull(tool.complete("say something", 50));
            String why = tool.lastFailure();
            assertTrue(why != null && why.startsWith("HTTP 413") && why.contains("Request too large"), why);
        } finally {
            System.clearProperty("hermit.remote.minIntervalMs");
        }
    }
}
