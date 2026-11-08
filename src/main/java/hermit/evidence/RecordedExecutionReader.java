package hermit.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured queries over a recording of a test, through the recording reader's
 * {@code dump} and {@code find} subcommands. The recording reader's dump prints one event
 * per line, followed by arguments, lines, locals and conditions, nested calls one indent
 * deeper, and a return at the end.
 */
public final class RecordedExecutionReader {
    private final Path reader;
    private final Path dir;
    private static final Pattern EVENT = Pattern.compile("^\\[seq (\\d+) t\\d+\\]( *)(.*)$");
    private static final Pattern CALL = Pattern.compile("^([\\w$.]+)\\.([\\w$<>]+)\\((\\d+) args?\\)"
            + "(?: this=(\\S+))?(?: \\(([^)]+)\\))?\\s*$");
    private static final Pattern ARG = Pattern.compile("^arg(\\d+) = (.*)$");
    private static final Pattern RETURN = Pattern.compile("^return (.*?)(?: \\[([\\w$.]+)\\.([\\w$<>]+)\\])?$");
    private static final Pattern THROW = Pattern.compile("^throw (.*?)(?: \\[([\\w$.]+)\\.([\\w$<>]+)\\])?$");

    public RecordedExecutionReader(Path reader, Path recordingDir) {
        this.reader = reader;
        this.dir = recordingDir;
    }

    /** One recorded invocation. */
    public record Call(long seq, int depth, String owner, String method, String receiver, String location,
            List<String> args, String returned, String threw) {
        public String render() {
            StringBuilder sb = new StringBuilder(owner).append('.').append(method).append('(')
                    .append(String.join(", ", args)).append(')');
            if (receiver != null) {
                sb.append(" on ").append(receiver);
            }
            if (location != null) {
                sb.append(" at ").append(location);
            }
            if (returned != null) {
                sb.append(" → returned ").append(returned);
            }
            if (threw != null) {
                sb.append(" → threw ").append(threw);
            }
            return sb.toString();
        }
    }

    /** Calls whose method (or Owner.method) contains {@code methodFilter}, with their arguments and return values. */
    public List<Call> calls(String methodFilter, int limit) throws IOException {
        return parseCalls(run("dump", "--dir", dir.toString(), "--method", methodFilter,
                "--limit", String.valueOf(Math.max(1, limit) * 40)), limit);
    }

    /** The events inside one call: its lines, locals, conditions, nested calls and return, up to {@code max} events. */
    public List<String> inside(Call call, int max) throws IOException {
        return insideOf(run("dump", "--dir", dir.toString(), "--from-seq", String.valueOf(call.seq()),
                "--limit", String.valueOf(max * 4)), call.seq(), max);
    }

    /** The recorded state of an object (Class#id), as the reader prints it. */
    public String object(String ref) throws IOException {
        String id = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : ref;
        String out = run("find", "--dir", dir.toString(), "--object", id, "--limit", "6");
        return out.length() > 2000 ? out.substring(0, 2000) + "…" : out;
    }

    /** Parses a dump into calls; arguments, returns and throws attach to the open call they belong to. */
    static List<Call> parseCalls(String dump, int limit) {
        List<Call> out = new ArrayList<>();
        // {seq, depth, owner, method, receiver, location, args, returned, threw}
        List<Object[]> open = new ArrayList<>();
        for (String line : dump.split("\n")) {
            Matcher e = EVENT.matcher(line);
            if (!e.matches()) {
                continue;
            }
            long seq = Long.parseLong(e.group(1));
            int depth = e.group(2).length();
            String body = e.group(3).strip();
            Matcher c = CALL.matcher(body);
            if (c.matches()) {
                open.add(new Object[] {seq, depth, c.group(1), c.group(2), c.group(4), c.group(5),
                        new ArrayList<String>(), null, null});
                continue;
            }
            Matcher a = ARG.matcher(body);
            if (a.matches() && !open.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> args = (List<String>) open.get(open.size() - 1)[6];
                args.add(a.group(2));
                continue;
            }
            Matcher r = RETURN.matcher(body);
            Matcher t = THROW.matcher(body);
            boolean isReturn = r.matches(), isThrow = !isReturn && t.matches();
            if (isReturn || isThrow) {
                Matcher m = isReturn ? r : t;
                Object[] call = closing(open, m.group(2), m.group(3));
                if (call != null) {
                    call[isReturn ? 7 : 8] = m.group(1);
                    open.remove(call);
                    out.add(toCall(call));
                }
            }
        }
        for (Object[] call : open) {
            out.add(toCall(call));
        }
        out.sort((x, y) -> Long.compare(x.seq(), y.seq()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static Object[] closing(List<Object[]> open, String owner, String method) {
        for (int i = open.size() - 1; i >= 0; i--) {
            Object[] c = open.get(i);
            if (method == null || (method.equals(c[3]) && (owner == null || owner.endsWith(String.valueOf(c[2]))
                    || String.valueOf(c[2]).endsWith(owner)))) {
                return c;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Call toCall(Object[] c) {
        return new Call((Long) c[0], (Integer) c[1], (String) c[2], (String) c[3], (String) c[4], (String) c[5],
                List.copyOf((List<String>) c[6]), (String) c[7], (String) c[8]);
    }

    /**
     * The events from {@code seq} on that are inside the call at {@code seq}
     * (deeper than its indent), up to its return.
     */
    static List<String> insideOf(String dump, long seq, int max) {
        List<String> out = new ArrayList<>();
        int callDepth = -1;
        for (String line : dump.split("\n")) {
            Matcher e = EVENT.matcher(line);
            if (!e.matches()) {
                continue;
            }
            long s = Long.parseLong(e.group(1));
            int depth = e.group(2).length();
            String body = e.group(3).strip();
            if (s < seq) {
                continue;
            }
            if (callDepth < 0) {
                callDepth = depth;
                out.add(body);
                continue;
            }
            if (body.startsWith("first-seen") || body.startsWith("stack-snapshot")) {
                continue;
            }
            if (depth <= callDepth) {
                if (body.startsWith("return") || body.startsWith("throw")) {
                    out.add(body);
                }
                break;
            }
            out.add("  ".repeat(Math.max(0, (depth - callDepth) / 2)) + body);
            if (out.size() >= max) {
                out.add("…");
                break;
            }
        }
        return out;
    }

    private String run(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(reader.toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("reader timed out: " + String.join(" ", args));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
