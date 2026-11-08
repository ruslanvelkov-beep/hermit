package hermit.checkpoint;

import hermit.api.Action;
import hermit.plan.Branch;
import hermit.plan.ParsedPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resuming a turn at a seam: the state a resumed turn starts from, the plan a "selected" seam holds, plans and branches
 * written as plain data for the seam files, and the context dump a fresh session starts.
 */
public final class TurnReplay {

    private TurnReplay() {
    }

    /**
     * A turn resumed at a "selected" seam replays the plan the seam holds; the repeat guard counted that replay as one
     * more selection of the same plan and had it replaced by a different, partial one. The replay takes the place of
     * the seam's own selection, so it is not counted again.
     */
    public static TurnSnapshot replayState(TurnSnapshot state) {
        TurnSnapshot resumed = state.copy();
        if ("selected".equals(state.seam)) {
            if (resumed.repeatCount > 0) {
                resumed.repeatCount--;
            } else {
                resumed.lastActionSig = "";
            }
        }
        return resumed;
    }

    /**
     * The supervisor stopped a turn at an approved plan to run it with a fixed tool, and the resume planned anew; the
     * new plan brought an invented API back. A resume from a "selected" step puts that step's plan up again (it passes
     * the plan guards and the step's seam as before); other seams plan anew. Null when there is nothing to replay.
     */
    public static String replayedPlan(Path stepDir, String seam) {
        Path selected = stepDir.resolve("selected.json");
        Path raw = stepDir.resolve("llm-raw.txt");
        try {
            if ("selected".equals(seam) && Files.isRegularFile(selected)) {
                return "{\"branches\": [" + Files.readString(selected).strip() + "]}";
            }
            // task 040a: the reply of a "plan" step did not parse (prose before the plan); after a parser fix the
            // same reply is read again instead of asking the model for a new one
            if ("plan".equals(seam) && Files.isRegularFile(raw)) {
                String text = Files.readString(raw);
                return text.isBlank() ? null : text;
            }
        } catch (IOException unreadable) {
            return null;
        }
        return null;
    }

    /** A plan's branches as plain data (name, confidence, actions with tool and parameters). */
    public static List<Map<String, Object>> planAsData(ParsedPlan plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (plan == null || plan.branches() == null) {
            return out;
        }
        for (Branch b : plan.branches()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name());
            m.put("confidence", b.confidence());
            List<Map<String, Object>> acts = new ArrayList<>();
            for (Action act : b.actions()) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("tool", act.toolName());
                am.put("parameters", act.parameters());
                acts.add(am);
            }
            m.put("actions", acts);
            out.add(m);
        }
        return out;
    }

    /** One branch as plain data. */
    public static Map<String, Object> branchAsData(Branch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.name());
        m.put("confidence", b.confidence());
        List<Map<String, Object>> acts = new ArrayList<>();
        for (Action act : b.actions()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("tool", act.toolName());
            am.put("parameters", act.parameters());
            acts.add(am);
        }
        m.put("actions", acts);
        return m;
    }

    /** Truncate the context-dump file for a fresh session; returns its path (or null on error). */
    public static Path initContextDump() {
        try {
            Path dir = Paths.get(System.getProperty("user.dir"), "logs");
            Files.createDirectories(dir);
            Path f = Path.of(System.getProperty("hermit.contextDump", dir.resolve("hermit-context.log").toString()));
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, "=== hermit context dump (planning prompt per iteration) ===\n");
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
