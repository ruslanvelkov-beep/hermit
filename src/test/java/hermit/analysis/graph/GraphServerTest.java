package hermit.analysis.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphServerTest {

    @TempDir
    Path dir;

    private GraphServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { B.helper(); }
                }
                """);
        Files.writeString(dir.resolve("B.java"), """
                class B {
                    static void helper() { new C().target(); }
                }
                """);
        Files.writeString(dir.resolve("C.java"), """
                class C {
                    void target() { }
                }
                """);
        ClasspathIndex index = ClasspathIndex.system();
        server = new GraphServer(CodeGraph.build(List.of(dir), index), index, 0, 0);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheQuerySurfaceWithColonSpecs() throws Exception {
        HttpResponse<String> stats = get("/stats");
        assertEquals(200, stats.statusCode());
        assertTrue(stats.body().contains("3 files"), stats.body());

        HttpResponse<String> reach = get("/reach?from=A:run&to=C:target");
        assertEquals(200, reach.statusCode());
        assertTrue(reach.body().contains("REACHABLE: A#run -> B#helper -> C#target"), reach.body());

        assertEquals(404, get("/reach?from=C:target&to=A:run").statusCode(), "no reverse edge");
        assertEquals(200, get("/callers?spec=C:target").statusCode());
        assertEquals(400, get("/reach?from=A:run").statusCode(), "missing 'to'");
        assertEquals(400, get("/nope").statusCode());
        assertEquals(200, get("/validate").statusCode(), "clean fixture");
    }

    @Test
    void refreshPicksUpAnEditWithoutReindexingTheWorld() throws Exception {
        assertEquals(200, get("/reach?from=A:run&to=C:target").statusCode());
        Files.writeString(dir.resolve("A.java"), """
                class A {
                    void run() { }
                }
                """);
        assertEquals(200, get("/refresh?file=" + dir.resolve("A.java")).statusCode());
        assertEquals(404, get("/reach?from=A:run&to=C:target").statusCode(), "edge went away");
        assertEquals(200, get("/reach?from=B:helper&to=C:target").statusCode(), "others intact");
    }

    @Test
    void shutdownStopsTheServer() throws Exception {
        assertEquals(200, get("/shutdown").statusCode());
        server.awaitShutdown();
        try {
            get("/stats");
            throw new AssertionError("server still answering after /shutdown");
        } catch (IOException expected) {
            // connection refused — the service is gone
        }
    }

    @Test
    void reachHonoursMaxDepthAndEchoesTheDepthUsed() throws Exception {
        // maxDepth sets the depth of the search, and the message names the depth that was used
        HttpResponse<String> deep = get("/reach?from=A:run&to=C:target&maxDepth=5");
        assertTrue(deep.body().contains("REACHABLE: A#run -> B#helper -> C#target"), deep.body());
        HttpResponse<String> shallow = get("/reach?from=A:run&to=C:target&maxDepth=1");
        assertEquals(404, shallow.statusCode(), shallow.body());
        assertTrue(shallow.body().contains("depth 1"), "the message must echo the depth actually used: " + shallow.body());
        assertTrue(shallow.body().contains("frontier still open") || shallow.body().contains("raise maxDepth"),
                "a depth-bound stop is not 'no path': " + shallow.body());
        HttpResponse<String> alias = get("/reach?from=A:run&to=C:target&depth=1");
        assertEquals(404, alias.statusCode(), alias.body());
    }

    @Test
    void serverExposesTheTestsEndpoint() throws Exception {
        // the server answers the tests query, and its help text lists it
        HttpResponse<String> r = get("/tests?spec=C:target");
        assertEquals(404, r.statusCode(), "no test reaches C#target in this fixture: " + r.body());
        HttpResponse<String> help = get("/nope");
        assertTrue(help.body().contains("/tests"), help.body());
    }
}
