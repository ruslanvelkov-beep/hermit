package hermit.context;

import hermit.checkpoint.TurnSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The turn's frame stack (the owner, 2026-10-26): the request at the root, the task plan's steps under it, each step's
 * plans (one an iteration) under the step. A step the turn moves on from closes and returns to its parent what it
 * touched: the files and symbols named in its results and how many of its calls succeeded or failed, read from the
 * results without a model. The stack lives in the turn's state, so a seam writes it and a resume reads it back.
 *
 * <p>Measured on 257 recorded planning steps, 71% of the earlier results a plan needed came from earlier sibling steps
 * and 29% from its own; a history of the current and the previous step whole, the other steps' return values and the
 * rest in tiers kept 0.99 of them at a 60k budget, against 0.95 for the tiers alone. Switched on by
 * {@code -Dhermit.context.frames=true}, with the tiered history.
 */
public final class FrameStack {

    private static final Pattern FILE = Pattern.compile("[\\w./-]*?([A-Z]\\w+\\.java)\\b");
    private static final Pattern SYMBOL = Pattern.compile("\\b([A-Z]\\w+)#(\\w+)");
    private static final Pattern ITERATION = Pattern.compile("(?m)^\\[Iteration (\\d+)\\]");

    private FrameStack() {
    }

    /** Whether the planner's history is served from the frame stack. */
    public static boolean enabled() {
        return Boolean.getBoolean("hermit.context.frames");
    }

    /**
     * Records an iteration that just ran: the request's frame first; the current task step's frame (the step before it
     * closed, with what it returned); the iteration's plan frame under the step, or under the request without a task plan.
     */
    public static void record(TurnSnapshot live, String request, int iteration, String plan, String log, String iterationText) {
        if (live.frames.isEmpty()) {
            live.frames.add(new TurnSnapshot.Frame("request", request == null ? "" : request, -1));
        }
        int parent = 0;
        if (live.taskPlan != null && live.currentStep < live.taskPlan.size()) {
            TurnSnapshot.Frame open = openStep(live);
            if (open == null || open.step != live.currentStep) {
                if (open != null) {
                    close(live, open, log);
                }
                open = new TurnSnapshot.Frame("step", live.taskPlan.get(live.currentStep).goal, 0);
                open.step = live.currentStep;
                open.firstIteration = iteration;
                live.frames.add(open);
            }
            open.lastIteration = iteration;
            parent = live.frames.indexOf(open);
        }
        TurnSnapshot.Frame p = new TurnSnapshot.Frame("plan", plan == null ? "" : plan, parent);
        p.firstIteration = iteration;
        p.lastIteration = iteration;
        p.open = false;
        p.returned = returned(List.of(iterationText == null ? "" : iterationText), "iteration " + iteration);
        live.frames.add(p);
    }

    /** The task step an iteration ran in (its index), or -1 when it ran outside a step. */
    static int stepOf(TurnSnapshot live, int iteration) {
        for (TurnSnapshot.Frame f : live.frames) {
            if (f.kind.equals("plan") && f.firstIteration == iteration && f.parent >= 0) {
                TurnSnapshot.Frame parent = live.frames.get(f.parent);
                return parent.kind.equals("step") ? parent.step : -1;
            }
        }
        return -1;
    }

    /** The step frames, in the order they opened. */
    static List<TurnSnapshot.Frame> steps(TurnSnapshot live) {
        return live.frames.stream().filter(f -> f.kind.equals("step")).toList();
    }

    /** What a frame returns: the files and symbols its results name and its calls' outcomes, at most 900 characters. */
    static String returned(List<String> texts, String label) {
        TreeSet<String> files = new TreeSet<>();
        TreeSet<String> symbols = new TreeSet<>();
        int ok = 0;
        int failed = 0;
        for (String t : texts) {
            Matcher f = FILE.matcher(t);
            while (f.find()) {
                files.add(f.group(1));
            }
            Matcher s = SYMBOL.matcher(t);
            while (s.find()) {
                symbols.add(s.group(1) + "#" + s.group(2));
            }
            ok += count(t, "status=Success");
            failed += count(t, "status=Error");
        }
        String d = "[" + label + " returned] files: " + String.join(", ", first(files, 25)) + "; symbols: "
                + String.join(", ", first(symbols, 25)) + "; calls: " + ok + " succeeded, " + failed + " failed\n";
        return d.length() <= 900 ? d : d.substring(0, 899) + "\n";
    }

    private static TurnSnapshot.Frame openStep(TurnSnapshot live) {
        for (int i = live.frames.size() - 1; i >= 0; i--) {
            TurnSnapshot.Frame f = live.frames.get(i);
            if (f.kind.equals("step") && f.open) {
                return f;
            }
        }
        return null;
    }

    private static void close(TurnSnapshot live, TurnSnapshot.Frame step, String log) {
        int index = live.frames.indexOf(step);
        List<Integer> iterations = live.frames.stream().filter(f -> f.kind.equals("plan") && f.parent == index)
                .map(f -> f.firstIteration).toList();
        List<String> texts = new ArrayList<>();
        List<int[]> starts = new ArrayList<>();
        Matcher m = ITERATION.matcher(log == null ? "" : log);
        while (m.find()) {
            starts.add(new int[] {m.start(), Integer.parseInt(m.group(1))});
        }
        for (int i = 0; i < starts.size(); i++) {
            if (iterations.contains(starts.get(i)[1])) {
                texts.add(log.substring(starts.get(i)[0], i + 1 < starts.size() ? starts.get(i + 1)[0] : log.length()));
            }
        }
        step.open = false;
        step.returned = returned(texts, "step " + (step.step + 1));
    }

    private static int count(String text, String what) {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length())) {
            n++;
        }
        return n;
    }

    private static List<String> first(TreeSet<String> set, int n) {
        return set.stream().limit(n).toList();
    }
}
