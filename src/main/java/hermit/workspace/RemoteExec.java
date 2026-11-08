package hermit.workspace;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Distributed execution: run Hermit (brain + LLM) on one box but execute its shell-based tools on
 * ANOTHER box over SSH. A tool goes remote when EITHER its call carries a {@code "remote"} param
 * ("user@host:/abs/dir" or "user@host") - set by the model when instructed to use the tool over SSH -
 * OR the global {@code -Dhermit.exec.host} default is set. No remote target => everything runs locally
 * exactly as before.
 *
 * <p>The remote command runs under a login shell ({@code bash -lc}) so PATH (gh, codeql, gradle, java,…)
 * is set up as on an interactive login, and the working directory is folded into the command. Paths given
 * to a remote tool must be paths on the REMOTE host. Files a tool GENERATES (a templated .ql, a .btm rule,
 * an init-script, a test source) are written on the remote host via {@link #writeText}/{@link #mkdtemp};
 * artifacts to inspect are copied back with {@link #fetch}.
 */
public final class RemoteExec {

    private RemoteExec() {}

    /** A remote target: {@code user@host} + an optional working directory. */
    public record Spec(String host, String dir) {}

    private static final String[] SSH_OPTS =
            {"-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10"};

    /** Global default host from {@code -Dhermit.exec.host} ("" = none). */
    public static String defaultHost() {
        return System.getProperty("hermit.exec.host", "").trim();
    }

    public static boolean isRemote(Spec s) {
        return s != null && s.host() != null && !s.host().isEmpty();
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "." : path.substring(0, slash);
    }

    private static List<String> sshBase(Spec spec) {
        List<String> a = new ArrayList<>();
        a.add("ssh");
        for (String o : SSH_OPTS) {
            a.add(o);
        }
        a.add(spec.host());
        return a;
    }

    /** POSIX single-quote a string so it survives one level of shell parsing. */
    public static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String joinQuoted(List<String> args) {
        StringBuilder b = new StringBuilder();
        for (String a : args) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(sq(a));
        }
        return b.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Resolve the remote target for a tool call: the call's {@code "remote"} param
     * ("user@host:/abs/dir" or "user@host") overrides the global {@code -Dhermit.exec.host}.
     * {@code fallbackDir} supplies the working dir when the spec omits one. null => run locally.
     */
    public static Spec spec(Map<String, Object> params, String fallbackDir) {
        String raw = params == null ? null : str(params.get("remote"));
        String host = null;
        String dir = fallbackDir;
        if (raw != null && !raw.isBlank()) {
            int sep = raw.indexOf(":/");
            if (sep >= 0) {
                host = raw.substring(0, sep).trim();
                dir = raw.substring(sep + 1).trim();
            } else {
                host = raw.trim();
            }
        } else if (!defaultHost().isEmpty()) {
            host = defaultHost();
        }
        // A host-less Spec still carries the local working dir so shell()/command() apply it; isRemote()
        // gates on host so this stays LOCAL. Return null only when there's nothing to carry (plain cwd).
        if (host == null || host.isEmpty()) {
            return (dir == null || dir.isBlank()) ? null : new Spec(null, dir);
        }
        return new Spec(host, dir);
    }

    /**
     * Like {@link #spec} but ONLY the call's {@code "remote"} param counts - the global
     * {@code -Dhermit.exec.host} default is IGNORED. Use for a tool whose binary/credentials live on the
     * BRAIN host (e.g. {@code gh}, whose auth is on the machine Hermit runs on), so a global remote default
     * meant for the code/build tools does not sweep it onto the wrong machine.
     */
    public static Spec specExplicit(Map<String, Object> params, String fallbackDir) {
        String raw = params == null ? null : str(params.get("remote"));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int sep = raw.indexOf(":/");
        if (sep >= 0) {
            return new Spec(raw.substring(0, sep).trim(), raw.substring(sep + 1).trim());
        }
        return new Spec(raw.trim(), fallbackDir);
    }

    /** ProcessBuilder for a shell COMMAND string - local, or over SSH when spec is remote. */
    public static ProcessBuilder shell(String cmd, Spec spec) {
        if (isRemote(spec)) {
            String remote = (spec.dir() != null && !spec.dir().isBlank())
                    ? "cd " + sq(spec.dir()) + " && " + cmd : cmd;
            List<String> a = sshBase(spec);
            a.add("bash -lc " + sq(remote));
            return new ProcessBuilder(a);
        }
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        if (spec != null && spec.dir() != null && !spec.dir().isBlank()) {
            pb.directory(new File(spec.dir()));
        }
        return pb;
    }

    /** ProcessBuilder for a binary+args command - local, or over SSH when spec is remote. */
    public static ProcessBuilder command(List<String> args, Spec spec) {
        if (isRemote(spec)) {
            return shell(joinQuoted(args), spec);
        }
        ProcessBuilder pb = new ProcessBuilder(args);
        if (spec != null && spec.dir() != null && !spec.dir().isBlank()) {
            pb.directory(new File(spec.dir()));
        }
        return pb;
    }

    /** Create a temp directory (returns its absolute path) locally or on the remote host. */
    public static String mkdtemp(Spec spec, String prefix) throws Exception {
        if (isRemote(spec)) {
            List<String> a = sshBase(spec);
            a.add("mktemp -d -t " + prefix);
            Process p = new ProcessBuilder(a).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(20, TimeUnit.SECONDS);
            return out;
        }
        return Files.createTempDirectory(prefix).toString();
    }

    /** Write text to {@code path} (returns it), creating parent dirs, locally or on the remote host. */
    public static String writeText(Spec spec, String content, String path) throws Exception {
        if (isRemote(spec)) {
            List<String> a = sshBase(spec);
            // mkdir -p the parent then stream the content into the file, so a nested target path works.
            a.add("mkdir -p " + sq(parentOf(path)) + " && cat > " + sq(path));
            Process p = new ProcessBuilder(a).redirectErrorStream(true).start();
            p.getOutputStream().write(content.getBytes());
            p.getOutputStream().close();
            p.waitFor(30, TimeUnit.SECONDS);
            return path;
        }
        Path local = Path.of(path);
        if (local.getParent() != null) {
            Files.createDirectories(local.getParent());
        }
        Files.writeString(local, content);
        return path;
    }

    /** Read a file's text locally or from the remote host; null if it can't be read. */
    public static String readText(Spec spec, String path) {
        try {
            if (isRemote(spec)) {
                List<String> a = sshBase(spec);
                a.add("cat " + sq(path));
                Process p = new ProcessBuilder(a).start();
                String out = new String(p.getInputStream().readAllBytes());
                return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
            }
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return null;
        }
    }

    /** True if {@code path} exists locally or on the remote host. */
    public static boolean exists(Spec spec, String path) {
        try {
            if (isRemote(spec)) {
                List<String> a = sshBase(spec);
                a.add("test -e " + sq(path));
                Process p = new ProcessBuilder(a).start();
                return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
            return Files.exists(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    /** Best-effort delete (recursive) of a path locally or on the remote host. */
    public static void delete(Spec spec, String path) {
        try {
            if (isRemote(spec)) {
                List<String> a = sshBase(spec);
                a.add("rm -rf " + sq(path));
                new ProcessBuilder(a).start().waitFor(20, TimeUnit.SECONDS);
                return;
            }
            Path local = Path.of(path);
            if (Files.isDirectory(local)) {
                try (var walk = Files.walk(local)) {
                    walk.sorted((x, y) -> y.getNameCount() - x.getNameCount()).forEach(q -> {
                        try {
                            Files.deleteIfExists(q);
                        } catch (Exception ignored) {
                        }
                    });
                }
            } else {
                Files.deleteIfExists(local);
            }
        } catch (Exception ignored) {
        }
    }

    /** Copy a remote-created path back to a local path (recursive) for inspection. false if local. */
    public static boolean fetch(Spec spec, String remotePath, Path localDest) {
        if (!isRemote(spec)) {
            return false;
        }
        try {
            if (localDest.getParent() != null) {
                Files.createDirectories(localDest.getParent());
            }
            Process p = new ProcessBuilder("scp", "-r", "-o", "BatchMode=yes",
                    spec.host() + ":" + remotePath, localDest.toString()).redirectErrorStream(true).start();
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
