package hermit.analysis.graph;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The resident form of the graph's queries: parse once, then answer any number of queries over plain HTTP on
 * 127.0.0.1 until told to shut down, so the cost of indexing is paid once instead of per query. Start it, query it
 * repeatedly (optionally {@code /refresh} after editing a file), and {@code /shutdown} when done.
 *
 * <p>Endpoints (GET, plain text): {@code /locate?spec=}, {@code /callers?spec=}, {@code /callees?spec=},
 * {@code /reach?from=&to=[&maxDepth=]}, {@code /tests?spec=}, {@code /hierarchy?type=}, {@code /impls?spec=},
 * {@code /methods?type=}, {@code /validate[?file=<suffix>]}, {@code /stats}, {@code /refresh?file=<path>} and
 * {@code /shutdown}. Method specs accept {@code :} in place of {@code #} (URL-friendly: {@code A:run}). Status 200 =
 * found/ok, 404 = not found / problems exist, 400 = bad request.
 *
 * <p>Binds 127.0.0.1 only. Requests are served on ONE thread: queries fill the graph's lazy state and {@code /refresh}
 * changes it, so serving them one at a time keeps that safe. An idle timeout (default 30 min, {@code --idle-minutes 0}
 * to disable) stops forgotten instances.
 */
public final class GraphServer {

    /** Starts serving immediately; {@code port} 0 picks an ephemeral port (see {@link #port()}). */
    public GraphServer(CodeGraph graph, ClasspathIndex index, int port, int idleMinutes)
            throws IOException {
        this.graph = graph;
        this.index = index;
        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "graph-server");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", this::handle);
        http.start();
        if (idleMinutes > 0) {
            long idleNanos = TimeUnit.MINUTES.toNanos(idleMinutes);
            idleWatch = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "graph-idle");
                t.setDaemon(true);
                return t;
            });
            idleWatch.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastUsed > idleNanos) {
                    System.err.println("[graph] idle timeout — shutting down");
                    stop();
                }
            }, 1, 1, TimeUnit.MINUTES);
        } else {
            idleWatch = null;
        }
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
        if (idleWatch != null) {
            idleWatch.shutdownNow();
        }
        stopped.countDown();
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    /** The depth of a search: {@code maxDepth} (the documented name) or its old alias {@code depth}. */
    static int depthParam(Map<String, String> q, int dflt) {
        String v = q.containsKey("maxDepth") ? q.get("maxDepth") : q.get("depth");
        return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
    }

    private final CodeGraph graph;
    private final ClasspathIndex index;
    private final HttpServer http;
    private final ScheduledExecutorService idleWatch;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile long lastUsed = System.nanoTime();

    public static void main(String[] args) throws Exception {
        List<Path> roots = new ArrayList<>();
        List<Path> classes = new ArrayList<>();
        int port = 7461;
        int idleMinutes = 30;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> roots.add(Path.of(args[++i]));
                case "--classes" -> {
                    for (String c : args[++i].split(",")) {
                        classes.add(Path.of(c));
                    }
                }
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--idle-minutes" -> idleMinutes = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown arg " + args[i]
                            + "\nusage: GraphServer --root <srcDir> [--root <more>]"
                            + " [--classes <dir|jar>[,<more>]] [--port 7461] [--idle-minutes 30]");
                    System.exit(2);
                }
            }
        }
        if (roots.isEmpty()) {
            System.err.println("at least one --root is required");
            System.exit(2);
        }
        long t0 = System.nanoTime();
        ClasspathIndex index = classes.isEmpty() ? ClasspathIndex.system()
                : ClasspathIndex.withExtra(classes);
        CodeGraph g = CodeGraph.build(roots, index);
        GraphServer server = new GraphServer(g, index, port, idleMinutes);
        System.out.printf("READY http://127.0.0.1:%d (%d files indexed in %dms)%n",
                server.port(), g.fileCount(), (System.nanoTime() - t0) / 1_000_000);
        server.awaitShutdown();
    }

    private void handle(HttpExchange x) throws IOException {
        lastUsed = System.nanoTime();
        Map<String, String> q = query(x);
        try {
            switch (x.getRequestURI().getPath()) {
                case "/locate" -> reply(x, GraphQueries.locate(graph, spec(q, "spec")));
                case "/callers" -> reply(x, GraphQueries.callers(graph, spec(q, "spec")));
                case "/callees" -> reply(x, GraphQueries.callees(graph, spec(q, "spec")));
                case "/reach" -> {
                    int depth = depthParam(q, 12);
                    reply(x, GraphQueries.reach(graph, spec(q, "from"), spec(q, "to"), depth));
                }
                case "/validate" -> {
                    GraphQueries.Result v = GraphQueries.validate(graph, index, q.get("file"));
                    send(x, v.found() ? 404 : 200, v.text());   // found == problems exist
                }
                case "/hierarchy" -> reply(x, GraphQueries.hierarchy(graph, spec(q, "type")));
                case "/impls" -> reply(x, GraphQueries.impls(graph, spec(q, "spec")));
                case "/methods" -> reply(x, GraphQueries.methods(graph, spec(q, "type")));
                case "/tests" -> {
                    int td = depthParam(q, 10);
                    reply(x, GraphQueries.tests(graph, spec(q, "spec"), td));
                }
                case "/specreach" -> {
                    int sd = depthParam(q, 10);
                    reply(x, GraphQueries.specreach(graph, spec(q, "spec"), sd));
                }
                case "/specverbs" -> reply(x, GraphQueries.specverbs(graph, q.get("q")));
                case "/stats" -> reply(x, GraphQueries.stats(graph));
                case "/refresh" -> {
                    graph.refresh(Path.of(require(q, "file")));
                    send(x, 200, "refreshed\n");
                }
                case "/shutdown" -> {
                    send(x, 200, "bye\n");
                    stop();
                }
                default -> send(x, 400, "unknown endpoint " + x.getRequestURI().getPath()
                        + " — one of /locate /callers /callees /reach /tests /specreach /specverbs /hierarchy"
                        + " /impls /methods /validate /stats /refresh /shutdown\n");
            }
        } catch (IllegalArgumentException e) {
            send(x, 400, e.getMessage() + "\n");
        } catch (Exception e) {
            send(x, 500, "error: " + e + "\n");
        }
    }

    private static void reply(HttpExchange x, GraphQueries.Result r) throws IOException {
        send(x, r.found() ? 200 : 404, r.text());
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Method specs accept ':' for '#' so curl needs no %23 escaping. */
    private static String spec(Map<String, String> q, String key) {
        return require(q, key).replace(':', '#');
    }

    private static String require(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing query parameter '" + key + "'");
        }
        return v;
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new HashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }
}
