package hermit.text.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.TextLens;
import hermit.text.check.Findings;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Token;
import hermit.text.pos.Reranker;
import hermit.text.pos.SearchModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * TextLens on the command line: the text on standard input, the answer on standard output. Each call starts afresh and
 * loads the tagger's models; {@code serve} starts a {@link TextLensServer} that keeps them loaded, and
 * {@code --server} sends the text to one already running. {@code hook claude-code} runs {@link ClaudeCodeHook}.
 */
public final class TextLensCommand {

    static final List<String> COMMANDS = List.of("annotate", "check", "tag", "serve");
    static final List<String> OPTIONS = List.of("--clock", "--zone", "--source", "--weights", "--search", "--server",
            "--port", "--idle-minutes");
    static final String USAGE = """
            usage: textlens <command> [options] < text
              annotate            the annotation document, as JSON
              check               the findings, what would refuse the text, one per line (checker: sentence)
              tag                 each token and its tag, one per line, separated by a tab
              serve               the resident server on 127.0.0.1 (POST /annotate and /check, GET /stats, /shutdown)
              hook claude-code    the Claude Code Stop hook: logs the findings of the last answer (its own options)
            options:
              --clock <instant>     the clock, an ISO-8601 instant (default now)
              --zone <id>           the time zone (default the system's)
              --source <file>       a text the input was written from, for the years of its dates (repeatable)
              --weights <file>      the tagger's reranker weights (the property hermit.pos.weights)
              --search <file>       the tagger's sentence search model (the property hermit.pos.search)
              --server <port>       annotate on the server running at that port
              --port <port>         serve: the port (default 7471; 0 picks a free one)
              --idle-minutes <n>    serve: stop after n minutes without a request (default 30; 0 never)
            exit: 0 done, 1 check found a finding, 2 a usage error, 3 the annotation failed
            """;

    /** The command line read: the command and its options; {@code server} is null when no server is named. */
    record Options(String command, Instant clock, ZoneId zone, List<Path> sources, Path weights, Path search,
            Integer server, int port, int idleMinutes) {}

    private TextLensCommand() {
    }

    /**
     * {@code args} read as {@link Options}: the first argument is one of {@link #COMMANDS}, every other is an option
     * of {@link #USAGE} followed by its value. Defaults: the clock now, the system's zone, no sources, models and
     * server, port {@link TextLensServer#DEFAULT_PORT}, 30 idle minutes. Throws IllegalArgumentException naming the
     * problem: no command, an unknown command or option, an option without its value, or a value that does not parse.
     */
    static Options parse(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("no command");
        }
        String command = args.get(0);
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("unknown command: " + command);
        }
        Instant clock = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        List<Path> sources = new ArrayList<>();
        Path weights = null;
        Path search = null;
        Integer server = null;
        int port = TextLensServer.DEFAULT_PORT;
        int idleMinutes = 30;
        for (int i = 1; i < args.size(); i += 2) {
            String option = args.get(i);
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException("unknown option: " + option);
            }
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            String value = args.get(i + 1);
            try {
                switch (option) {
                    case "--clock" -> clock = Instant.parse(value);
                    case "--zone" -> zone = ZoneId.of(value);
                    case "--source" -> sources.add(Path.of(value));
                    case "--weights" -> weights = Path.of(value);
                    case "--search" -> search = Path.of(value);
                    case "--server" -> server = Integer.valueOf(value);
                    case "--port" -> port = Integer.parseInt(value);
                    case "--idle-minutes" -> idleMinutes = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException(option + ": cannot read " + value, failure);
            }
        }
        return new Options(command, clock, zone, List.copyOf(sources), weights, search, server, port, idleMinutes);
    }

    /**
     * Runs {@code args} over the text read from {@code in} (all of it, as UTF-8; {@code serve} reads none), writes the
     * answer to {@code out} and a problem to {@code err}, and returns the exit code. A usage error writes the problem
     * and {@link #USAGE} and returns 2. The models named by {@code --weights} and {@code --search} are set as their
     * system properties before the first annotation. {@code annotate} writes the document's JSON and a line break,
     * {@code check} each finding as its checker, a colon, a space and its sentence on a line of its own and returns 1
     * when there is one, {@code tag} each token as written, a tab and its tag ({@link Token#tag()}) on a line of its
     * own. With {@code --server} the text, the sources' contents, the clock and the zone go as JSON to the server's
     * {@code /annotate} ({@code annotate}, {@code tag}) or {@code /check} ({@code check}) and the answer is written the
     * same way. {@code serve} writes {@code textlens serving on 127.0.0.1:<port>} and returns 0 once the server stops.
     * A file that cannot be read, a server that does not answer 200 or an annotation that throws writes the problem
     * and returns 3.
     */
    static int run(List<String> args, InputStream in, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException failure) {
            err.println("textlens: " + failure.getMessage());
            err.print(USAGE);
            return 2;
        }
        try {
            if (options.weights() != null) {
                System.setProperty("hermit.pos.weights", options.weights().toString());
            }
            if (options.search() != null) {
                System.setProperty("hermit.pos.search", options.search().toString());
            }
            if (options.command().equals("serve")) {
                TextLensServer server = new TextLensServer(
                        TextLens.standard(), options.port(), options.idleMinutes());
                out.println("textlens serving on 127.0.0.1:" + server.port());
                server.awaitShutdown();
                return 0;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> sources = new ArrayList<>();
            for (Path source : options.sources()) {
                sources.add(Files.readString(source, StandardCharsets.UTF_8));
            }
            if (options.server() != null) {
                String path = options.command().equals("check") ? "/check" : "/annotate";
                String answer = post(options, path, text, sources);
                if (options.command().equals("check")) {
                    JsonNode findings = TextLensServer.JSON.readTree(answer).path("findings");
                    for (JsonNode finding : findings) {
                        out.println(finding.path("checker").asText() + ": "
                                + finding.path("sentence").asText());
                    }
                    return findings.isEmpty() ? 0 : 1;
                }
                AnnotationDocument annotation = AnnotationDocument.fromJson(answer);
                if (options.command().equals("annotate")) {
                    out.println(annotation.toJson());
                    return 0;
                }
                for (Token token : annotation.tokens()) {
                    out.println(token.range().surfaceIn(annotation.text()) + "\t" + token.tag());
                }
                return 0;
            }
            TextLens lens = TextLens.standard();
            AnnotationDocument annotation = lens.annotate(text, sources, options.clock(), options.zone());
            if (options.command().equals("annotate")) {
                out.println(annotation.toJson());
                return 0;
            }
            if (options.command().equals("check")) {
                List<Findings.Finding> findings = lens.findings(annotation);
                for (Findings.Finding finding : findings) {
                    out.println(finding.checker() + ": " + finding.sentence());
                }
                return findings.isEmpty() ? 0 : 1;
            }
            for (Token token : annotation.tokens()) {
                out.println(token.range().surfaceIn(annotation.text()) + "\t" + token.tag());
            }
            return 0;
        } catch (Exception failure) {
            err.println("textlens: " + failure.getMessage());
            return 3;
        }
    }

    /** Runs the command line: {@code hook claude-code} by {@link ClaudeCodeHook#run}, any other by {@link #run}. */
    public static void main(String[] args) {
        List<String> line = List.of(args);
        System.exit(line.size() >= 2 && line.get(0).equals("hook") && line.get(1).equals("claude-code")
                ? ClaudeCodeHook.run(line.subList(2, line.size()), System.in, System.out, System.err)
                : run(line, System.in, System.out, System.err));
    }

    private static String post(Options options, String path, String text, List<String> sources)
            throws IOException, InterruptedException {
        ObjectNode body = TextLensServer.JSON.createObjectNode();
        body.put("text", text);
        ArrayNode sourceValues = body.putArray("sources");
        sources.forEach(sourceValues::add);
        body.put("clock", options.clock().toString());
        body.put("zone", options.zone().getId());
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + options.server() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("server answered " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
