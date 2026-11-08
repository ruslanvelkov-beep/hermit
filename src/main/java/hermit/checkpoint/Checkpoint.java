package hermit.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Step checkpoints for the agent loop - "stepping mode".
 *
 * <p>Configured with system properties:
 * <ul>
 *   <li>{@code -Dhermit.step.dir=<dir>} - write a step directory at every seam (plan / executed / answer). Off when
 *       unset.</li>
 *   <li>{@code -Dhermit.step.pause=true} - after writing the {@code executed} and {@code answer} steps, block until
 *       {@code <step>/control} appears with one of {@code continue}, {@code retry}, {@code stop}.</li>
 *   <li>{@code -Dhermit.step.workbench=<repo>} - a git working tree whose content is snapshotted as a tree object at
 *       every {@code plan} step and restored on {@code retry} / resume.</li>
 *   <li>{@code -Dhermit.step.stopAt=<seam>:<iteration>} - write the step and stop the turn there without waiting
 *       (for tests and scripted drives), e.g. {@code executed:1}.</li>
 * </ul>
 *
 * <p>A step directory holds {@code state.json} (the {@link TurnSnapshot}) plus whatever the loop notes there:
 * {@code prompt.txt}, {@code llm-raw.txt}, {@code plan.json}, {@code results.json}, {@code answer.txt}. The
 * directory name is {@code step-NNNN-<seam>}, numbered across the whole run so the order on disk is the order
 * of the loop.
 */
public final class Checkpoint {

    /** Thrown out of the loop when a step's control says {@code stop} (or the configured stopAt is reached). */
    public static final class Stop extends RuntimeException {
        public final Path step;

        public Stop(Path step) {
            super("stopped at checkpoint " + step);
            this.step = step;
        }
    }

    public enum Control { CONTINUE, RETRY, STOP, REJECT }

    /** A control verb plus the one-sentence note a `reject` carries (why the selected plan is wrong). */
    public record Decision(Control control, String note) {
        static final Decision CONTINUE = new Decision(Control.CONTINUE, null);
    }

    /** Seams that pause when {@code -Dhermit.step.pause=true}: default executed,answer; F327 adds `selected`. */
    private final Set<String> pauseAt;

    private final Path dir;
    private final boolean pause;
    private final Path workbench;
    private final String stopAt;
    private int counter;

    private Checkpoint(Path dir, boolean pause, Path workbench, String stopAt, String pauseAt) {
        this.dir = dir;
        this.pause = pause;
        this.workbench = workbench;
        this.stopAt = stopAt;
        this.counter = dir == null ? 0 : nextNumber(dir);
        this.pauseAt = new HashSet<>(Arrays.asList(
                (pauseAt == null || pauseAt.isBlank() ? "executed,answer" : pauseAt).split("\\s*,\\s*")));
    }

    public static Checkpoint fromSystemProperties() {
        String d = System.getProperty("hermit.step.dir");
        if (d == null || d.isBlank()) {
            return new Checkpoint(null, false, null, null, null);
        }
        String wb = System.getProperty("hermit.step.workbench");
        return new Checkpoint(Path.of(d), Boolean.getBoolean("hermit.step.pause"),
                wb == null || wb.isBlank() ? null : Path.of(wb), System.getProperty("hermit.step.stopAt"),
                System.getProperty("hermit.step.pauseAt"));
    }

    /** A checkpoint writing under {@code dir} (tests). */
    public static Checkpoint at(Path dir, boolean pause, Path workbench, String stopAt) {
        return new Checkpoint(dir, pause, workbench, stopAt, null);
    }

    public static Checkpoint at(Path dir, boolean pause, Path workbench, String stopAt, String pauseAt) {
        return new Checkpoint(dir, pause, workbench, stopAt, pauseAt);
    }

    public static Checkpoint off() {
        return new Checkpoint(null, false, null, null, null);
    }

    public boolean enabled() {
        return dir != null;
    }

    public Path dir() {
        return dir;
    }

    public Path workbench() {
        return workbench;
    }

    /** Writes {@code state} as the next step directory for {@code seam}; returns the directory, or null when off. */
    public Path write(TurnSnapshot state, String seam) {
        if (dir == null) {
            return null;
        }
        try {
            Path step = dir.resolve(String.format(Locale.ROOT, "step-%04d-%s", ++counter, seam));
            Files.createDirectories(step);
            state.seam = seam;
            state.createdAt = Instant.now().toString();
            if (workbench != null) {
                // every seam records the tree AS IT IS NOW: a resume from an "executed" step must keep the edits that
                // iteration made (a retry re-enters from the "plan" step's tree, which is the tree before them)
                state.workbenchDir = workbench.toString();
                state.workbenchTree = snapshot(workbench);
                state.workbenchHead = head(workbench);
            }
            state.write(step.resolve("state.json"));
            return step;
        } catch (IOException e) {
            throw new IllegalStateException("cannot write checkpoint " + seam, e);
        }
    }

    /** Adds a text artifact to a step directory (no-op when off). */
    public void note(Path step, String name, String text) {
        if (step == null || text == null) {
            return;
        }
        try {
            Files.writeString(step.resolve(name), text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot note " + name + " in " + step, e);
        }
    }

    /** Whether the configured stopAt names this seam and iteration. */
    public boolean stopsAt(String seam, int iteration) {
        return stopAt != null && stopAt.equals(seam + ":" + iteration);
    }

    /**
     * After the {@code executed} / {@code answer} seams: honours stopAt, then (when pausing) waits for the control
     * file. Returns CONTINUE when neither applies.
     */
    public Control control(Path step, String seam, int iteration) {
        return decide(step, seam, iteration).control();
    }

    /**
     * Like {@link #control} but returns the note of a {@code reject <sentence>} - the supervisor's one-line reason a
     * selected plan is wrong, fed back to the planner as a review issue.
     */
    private Decision lastDecision;

    /** The most recent decision {@link #decide} returned (its note carries a reject's reason). */
    public Decision lastDecision() {
        return lastDecision;
    }

    public Decision decide(Path step, String seam, int iteration) {
        Decision d = decideInner(step, seam, iteration);
        lastDecision = d;
        return d;
    }

    private Decision decideInner(Path step, String seam, int iteration) {
        if (step == null) {
            return Decision.CONTINUE;
        }
        if (stopsAt(seam, iteration)) {
            note(step, "stopped", "stopAt " + stopAt + " at " + Instant.now());
            return new Decision(Control.STOP, null);
        }
        if (!pause || !pauseAt.contains(seam)) {
            return Decision.CONTINUE;
        }
        return waitForDecision(step, Duration.ofDays(365));
    }

    /** Blocks until {@code <step>/control} holds a verb (continue | retry | stop), polling four times a second. */
    public static Control waitForControl(Path step, Duration timeout) {
        return waitForDecision(step, timeout).control();
    }

    /** Blocks until {@code <step>/control} holds {@code continue | retry | stop | reject <one sentence>}. */
    public static Decision waitForDecision(Path step, Duration timeout) {
        Path control = step.resolve("control");
        Path waiting = step.resolve("waiting");
        try {
            Files.writeString(waiting, "waiting for control since " + Instant.now()
                    + "\nwrite one of: continue | retry | stop  into " + control);
        } catch (IOException ignored) {
            // informational
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(control)) {
                    String raw = Files.readString(control).strip();
                    String verb = raw.toLowerCase(Locale.ROOT);
                    if (!verb.isEmpty()) {
                        Files.deleteIfExists(waiting);
                        Files.writeString(step.resolve("controlled"), raw + " at " + Instant.now());
                        if (verb.startsWith("reject")) {
                            String note = raw.substring("reject".length()).replaceFirst("^[:\\s]+", "").strip();
                            return new Decision(Control.REJECT, note.isEmpty() ? "the selected plan does not advance the task" : note);
                        }
                        switch (verb) {
                            case "retry":
                                return new Decision(Control.RETRY, null);
                            case "stop":
                                return new Decision(Control.STOP, null);
                            default:
                                return Decision.CONTINUE;
                        }
                    }
                }
                TimeUnit.MILLISECONDS.sleep(250);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + control, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Decision(Control.STOP, null);
            }
        }
        return Decision.CONTINUE;
    }

    /** Writes the control verb for a step (the other side of {@link #waitForControl}). */
    public static void control(Path step, Control verb) throws IOException {
        Files.writeString(step.resolve("control"), verb.name().toLowerCase(Locale.ROOT),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Rejects the selected plan of a waiting step with a one-sentence reason. */
    public static void reject(Path step, String why) throws IOException {
        Files.writeString(step.resolve("control"), "reject " + why,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ---- workbench snapshots (git plumbing, never touching the repo's own index or refs) ----

    /** The tree object of the working tree's current content (tracked and untracked, ignored files excluded). */
    public static String snapshot(Path repo) {
        try {
            Path index = Files.createTempFile("hermit-step-index", "");
            Files.delete(index);
            git(repo, index, "add", "-A", ".");
            String tree = git(repo, index, "write-tree").strip();
            Files.deleteIfExists(index);
            return tree;
        } catch (IOException e) {
            throw new IllegalStateException("cannot snapshot " + repo, e);
        }
    }

    /** The repository's HEAD commit, or null when it has none (or is not a repository). */
    static String head(Path repo) {
        try {
            String h = git(repo, null, "rev-parse", "-q", "--verify", "HEAD").strip();
            return h.isEmpty() ? null : h;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Makes the working tree equal to {@code tree}, except for what was committed after it was taken. Rewrite drive,
     * task 043t1b: a resume restored the whole tree of a seam and so reverted a document the supervisor had committed
     * after that seam. A path that a commit since {@code head} changed, and whose working copy is that commit's, is
     * kept; every other difference (the abandoned run's uncommitted edits, files it added or deleted) is undone.
     * Without {@code head}, or with HEAD where it was, the whole tree is restored.
     */
    public static void restore(Path repo, String tree, String head) {
        if (tree == null || tree.isBlank()) {
            return;
        }
        String headNow = head(repo);
        if (head == null || headNow == null || head.equals(headNow)) {
            restore(repo, tree);
            return;
        }
        String now = snapshot(repo);
        Set<String> kept;
        try {
            kept = new LinkedHashSet<>(paths(git(repo, null, "diff-tree", "-r", "--name-only", "-z",
                    "--no-renames", head, headNow)));
            kept.removeAll(paths(git(repo, null, "diff-tree", "-r", "--name-only", "-z", "--no-renames",
                    headNow + "^{tree}", now)));
        } catch (IOException e) {
            restore(repo, tree);   // the seam's HEAD is gone (history rewritten): nothing tells what was committed
            return;
        }
        try {
            restore(repo, tree);
            if (kept.isEmpty()) {
                return;
            }
            Set<String> inNow = new HashSet<>(paths(git(repo, null, "ls-tree", "-r", "--name-only",
                    "-z", now)));
            List<String> back = new ArrayList<>();
            for (String f : kept) {
                if (inNow.contains(f)) {
                    back.add(f);
                } else {
                    Files.deleteIfExists(repo.resolve(f));   // a commit since the seam deleted it
                }
            }
            if (!back.isEmpty()) {
                Path index = Files.createTempFile("hermit-step-index", "");
                Files.delete(index);
                git(repo, index, "read-tree", now);
                List<String> args = new ArrayList<>(List.of("checkout-index", "-f", "--"));
                args.addAll(back);
                git(repo, index, args.toArray(String[]::new));
                Files.deleteIfExists(index);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot restore " + repo + " to " + tree, e);
        }
    }

    private static List<String> paths(String nulSeparated) {
        List<String> out = new ArrayList<>();
        for (String f : nulSeparated.split("\0")) {
            if (!f.isBlank()) {
                out.add(f);
            }
        }
        return out;
    }

    /** Makes the working tree equal to {@code tree}: files differing are rewritten, files absent in the tree removed. */
    public static void restore(Path repo, String tree) {
        if (tree == null || tree.isBlank()) {
            return;
        }
        try {
            // what is in the working tree now (as a tree), to know which files to delete
            String now = snapshot(repo);
            if (now.equals(tree)) {
                return;
            }
            Path index = Files.createTempFile("hermit-step-index", "");
            Files.delete(index);
            git(repo, index, "read-tree", tree);
            git(repo, index, "checkout-index", "-a", "-f");
            // files present now but absent in the snapshot
            String removed = git(repo, null, "diff-tree", "-r", "--name-only", "--diff-filter=A", tree, now);
            for (String f : removed.split("\n")) {
                if (!f.isBlank()) {
                    Files.deleteIfExists(repo.resolve(f.strip()));
                }
            }
            Files.deleteIfExists(index);
        } catch (IOException e) {
            throw new IllegalStateException("cannot restore " + repo + " to " + tree, e);
        }
    }

    private static String git(Path repo, Path index, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (index != null) {
            pb.environment().put("GIT_INDEX_FILE", index.toString());
        }
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + String.join(" ", args) + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out.strip());
        }
        return out;
    }

    // ---- reading steps back ----

    public static TurnSnapshot read(Path step) throws IOException {
        Path f = Files.isDirectory(step) ? step.resolve("state.json") : step;
        return TurnSnapshot.read(f);
    }

    /** The step directories under {@code runDir}, in order. */
    public static List<Path> steps(Path runDir) throws IOException {
        if (!Files.isDirectory(runDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(runDir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("step-")).sorted().toList();
        }
    }

    /** The most recent {@code plan} step at or before {@code step} - the point a retry re-enters the iteration. */
    public static Path planStepBefore(Path step) throws IOException {
        List<Path> all = steps(step.getParent());
        Path best = null;
        for (Path p : all) {
            if (p.getFileName().toString().compareTo(step.getFileName().toString()) > 0) {
                break;
            }
            if (p.getFileName().toString().endsWith("-plan")) {
                best = p;
            }
        }
        return best;
    }

    private static int nextNumber(Path runDir) {
        try {
            int max = 0;
            for (Path p : steps(runDir)) {
                String n = p.getFileName().toString();
                int a = n.indexOf('-') + 1;
                int b = n.indexOf('-', a);
                if (b > a) {
                    max = Math.max(max, Integer.parseInt(n.substring(a, b)));
                }
            }
            return max;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }
}
