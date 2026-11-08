package hermit.checkpoint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import hermit.claims.Claim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The COMPLETE state of one agent turn between two steps, as plain data. The agent loop keeps its
 * counters, flags and collections in one of these (instead of forty locals), so a step boundary can write it
 * to disk and a later process can resume the turn exactly there. Everything in it is JSON: strings, numbers,
 * booleans, lists and maps. Objects the loop rebuilds from configuration (tool registry, model clients, the
 * code graph) are not state and are not here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TurnSnapshot {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    // ---- identity of the step ----
    public String request;
    public int turn;                       // the driver's turn number (multi-prompt sessions)
    public int iteration = 1;              // the iteration to run NEXT when resumed
    public String seam;                    // where the state was written: plan | executed | answer
    public String createdAt;
    public String hermitVersion;

    // ---- the conversation ----
    public String contextLog = "";
    public List<String> history = new ArrayList<>();
    public String successfulResults = "";
    public List<String> succeededTools = new ArrayList<>();
    public String lastSuccessTool;
    public Map<String, Object> lastSuccessResult;
    public String lastRefusalHint;
    public String nodeName = "Root";
    public Map<String, Object> nodeContext = new LinkedHashMap<>();

    // ---- ground truth observed this turn ----
    public boolean committedThisSession;
    public boolean testOutcomeObserved;
    public boolean buildBroken;
    public boolean haveSuccess;
    public String generatedFile;
    public String generatedMethod;
    public boolean autoCommitted;
    public String pendingExpertProgram;
    public String lastMemoryProgram;

    // ---- bounded gates and guards ----
    public int planReviews;
    public int jsonRepairs;
    public int answerGates;
    public int deterministicGates;
    public int uncommittedRefusals;
    public int regenerationRefusals;
    public int clarifyGates;
    public int forbiddenGates;
    public int planShapedGates;
    public int workingNoteGates;           // answers refused as working notes
    public int citationGates;              // answers refused for citing code objects that do not exist
    public int claimGates;                 // claims ledger: answers refused for resting on open or refuted claims
    public List<Claim> claims = new ArrayList<>();   // the claims of the turn and their proofs (ClaimInspector)
    public int repeatGates;
    public int noToolGates;
    public int webReadGates;
    public int authoredPlans;
    public int programConsults;
    public int fabricationGates;
    public int surrenderGates;
    public int repeatCount;
    public int unparsedPlans;              // consecutive plans the parser rejected
    public int unknownTools;               // plans naming a tool that does not exist
    public int verdictCritics;             // verdicts a second reviewer (another model) has disputed
    public int synthesizedAnswers;         // answers synthesized from the results after covered lookups ran out
    public boolean lastRefusalWasCoveredRead;   // the last plan refusal was a covered read - any overlap is covered next
    public int discardedRewrites;               // refiner rewrites discarded this turn (two switch the refiner off)
    public List<String> failedSteps = new ArrayList<>();   // refused plans/answers since the last success - "<plan> - <why>"
    // claims a second reviewer disputed this turn (checked again when no reviewer is left)
    public List<String> disputedClaims = new ArrayList<>();
    public String lastActionSig = "";
    public boolean webSearchRan;
    public boolean webReadRan;
    public boolean planEscalationProbed;
    public Map<String, String> executedSignatures = new LinkedHashMap<>();
    public List<String> triedBranches = new ArrayList<>();
    public Map<String, Integer> issueRounds = new LinkedHashMap<>();

    // ---- the task plan (user, 2026-10-21): numbered steps written first, the planner working the current one ----
    public List<TaskStep> taskPlan = new ArrayList<>();
    public int currentStep;                // index of the step being worked; taskPlan.size() once every step is done
    public int stepIterations;             // iterations spent on the current step
    public int planRevisions;              // times the current and later steps were rewritten
    public Set<Integer> exactRoundsRun = new TreeSet<>();   // the request's exact rounds whose items ran

    /** One step of the task plan: its goal, and once done, what came of it. */
    public static class TaskStep {
        public String goal = "";
        public boolean done;
        public String outcome;

        public TaskStep() {
        }

        public TaskStep(String goal) {
            this.goal = goal;
        }
    }

    // ---- the frame stack (the owner, 2026-10-26): the request, its task steps, each step's plans ----
    public List<Frame> frames = new ArrayList<>();

    /** One frame: what it was for, where it sits, which iterations ran in it, and what it returned when it closed. */
    public static class Frame {
        public String kind = "";               // request, step or plan
        public String goal = "";
        public int parent = -1;                // index in frames; -1 for the request
        public int step = -1;                  // a step frame's index in the task plan
        public int firstIteration;
        public int lastIteration;
        public boolean open = true;
        public String returned;                // the files and symbols it touched and its calls' outcomes, once closed

        public Frame() {
        }

        public Frame(String kind, String goal, int parent) {
            this.kind = kind;
            this.goal = goal;
            this.parent = parent;
        }
    }

    // ---- state that lives in tools ----
    public Map<String, String> editOriginals = new LinkedHashMap<>();   // TreeEdit's pre-edit file contents

    // ---- the workbench ----
    public String workbenchDir;
    public String workbenchTree;           // git tree object of the workbench at this step (see Checkpoint)
    public String workbenchHead;           // the workbench's HEAD commit when that tree was taken (see Checkpoint)

    /**
     * A JSON-shaped copy of a tool result: maps, lists, strings, numbers and booleans pass through; anything else
     * (a Path, an enum, an exception) becomes its string form, so the state serializes and reads back the same.
     */
    @SuppressWarnings("unchecked")
    public static Object jsonSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), jsonSafe(e.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable<?> it && !(value instanceof Path)) {
            List<Object> out = new ArrayList<>();
            for (Object o : it) {
                out.add(jsonSafe(o));
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            List<Object> out = new ArrayList<>();
            for (Object o : arr) {
                out.add(jsonSafe(o));
            }
            return out;
        }
        return String.valueOf(value);
    }

    /** Any JSON-shaped value as indented JSON (for the step artifacts). */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            return String.valueOf(value);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize the turn state", e);
        }
    }

    public static TurnSnapshot fromJson(String json) {
        try {
            return MAPPER.readValue(json, TurnSnapshot.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a turn state: " + e.getMessage(), e);
        }
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson());
    }

    public static TurnSnapshot read(Path file) throws IOException {
        return fromJson(Files.readString(file));
    }

    /** A deep copy through JSON - the state as it is right now, detached from the live one. */
    public TurnSnapshot copy() {
        return fromJson(toJson());
    }
}
