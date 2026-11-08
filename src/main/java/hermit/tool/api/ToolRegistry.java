package hermit.tool.api;

import hermit.claims.ClaimSession;
import hermit.model.EndpointPolicy;
import hermit.tool.analysis.SyntaxCheckTool;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.claims.ClaimInspectorTool;
import hermit.tool.codehost.CodeHostSkill;
import hermit.tool.consult.ConsultTool;
import hermit.tool.files.FileClassifier;
import hermit.tool.files.LocateFileTool;
import hermit.tool.files.WriteProgramTool;
import hermit.tool.generate.SpecTestComposerTool;
import hermit.tool.generate.TreeGenerationTool;
import hermit.tool.graph.CodeGraphTool;
import hermit.tool.helper.HelperModelTool;
import hermit.tool.helper.LlmQueryTool;
import hermit.tool.process.BashTool;
import hermit.tool.process.ProcessInspectTool;
import hermit.tool.process.RunProgramTool;
import hermit.tool.record.ExecutionRecorderTool;
import hermit.tool.text.TextAnalysisTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ToolRegistry {

    // LinkedHashMap: preserve registration order for a stable tool listing.
    private final Map<String, Tool> availableTools = new LinkedHashMap<>();

    public ToolRegistry() {
        // Register all known tools upon initialization
        availableTools.put("HindsightDebugger", new ExecutionRecorderTool());
        if (ClaimSession.enabled()) {   // claim-to-code mapping (opt-in: -Dhermit.claims.enabled)
            availableTools.put("ClaimInspector", new ClaimInspectorTool());
        }
        availableTools.put("FileClassifier", new FileClassifier());
        availableTools.put("CodeGraph", new CodeGraphTool());
        if (Boolean.getBoolean("hermit.textlens.tool")) {   // TextLens over a text or a file (opt-in)
            availableTools.put("TextAnalysis", new TextAnalysisTool());
        }
        availableTools.put("TreeEdit", new TreeEditTool());
        availableTools.put("TreeCodegen", new TreeGenerationTool());
        availableTools.put("SpecTestComposer", new SpecTestComposerTool());
        availableTools.put("Bash", new BashTool());
        availableTools.put("LocateFile", new LocateFileTool());
        availableTools.put("SyntaxCheck", new SyntaxCheckTool());
        availableTools.put("WriteProgram", new WriteProgramTool());
        availableTools.put("RunProgram", new RunProgramTool());
        availableTools.put("ProcessInspect", new ProcessInspectTool());
        availableTools.put("LlmQuery", new LlmQueryTool());
        // an isolated local-only evaluation keeps the analysis off the issue tracker (it holds the expected
        // answer in the ticket's comments) unless explicitly allowed with -Dhermit.github.enabled=true.
        if (!EndpointPolicy.localOnly() || Boolean.getBoolean("hermit.github.enabled")) {
            availableTools.put("GitHub", new CodeHostSkill());
        }
        // Single-model mode (-Dhermit.chain.disabled=true): omit the external-LLM tools so the local
        // driver cannot offload reasoning to a remote/cloud model - for an honest single-model run.
        // (LlmQuery stays: it defaults to the same local model server as the driver.)
        if (!Boolean.parseBoolean(System.getProperty("hermit.chain.disabled", "false"))) {
            availableTools.put("RemoteAgent", new HelperModelTool());
            // hosted-assisted local-only runs: BigBrother when an allow-listed provider has a key
            // (-Dhermit.inference.allowHosted)
            ConsultTool bigBrother = new ConsultTool();
            if (!EndpointPolicy.localOnly() || bigBrother.isAvailable()) availableTools.put("BigBrother", bigBrother);
        }
    }

    /**
     * Retrieves a tool by its registered key.
     * @param toolKey The unique identifier (e.g., "Bash").
     * @return The Tool implementation, or null if not found.
     */
    public Tool getTool(String toolKey) {
        return availableTools.get(toolKey);
    }

    /**
     * Lists all tools currently available to the Agent.
     */
    public int getAvailableToolCount() {
        return availableTools.size();
    }

    /** Returns the map of all registered tools. */
    public Map<String, Tool> getAllTools() {
        return availableTools;
    }

    /**
     * The escalated plan author (a cloud model) designed the right fix and then invented
     * its own TreeEdit parameters (`edits`, `position`) - the corrective-plan prompt showed it 60-character glosses.
     * The FULL documentation of the tools named in {@code text} (the request and the context tail), each capped.
     */
    public String documentationFor(String text, int capEach) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        availableTools.forEach((name, tool) -> {
            // The request said "the test composer", never "TestComposer", so the contract was not attached
            // and the escalated author invented parameters again - a tool is named when its exact name
            // appears OR when the first and last of its camel-case words do ("test … composer").
            String[] words = name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("\\s+");
            boolean byWords = words.length >= 2 && words[0].length() >= 3 && words[words.length - 1].length() >= 3
                    && lower.matches("(?s).*\\b" + Pattern.quote(words[0]) + "\\b.*")
                    && lower.matches("(?s).*\\b" + Pattern.quote(words[words.length - 1]) + "\\b.*");
            if (text.matches("(?s).*\\b" + Pattern.quote(name) + "\\b.*") || byWords) {
                String doc = tool.getDocumentation();
                sb.append("\n--- ").append(name).append(" (full contract) ---\n")
                        .append(doc.length() > capEach ? doc.substring(0, capEach) + "…" : doc).append('\n');
            }
        });
        return sb.toString();
    }

    /** The registered tool names (a critic doubting one of these is "installed" is wrong by definition). */
    public Set<String> names() {
        return Collections.unmodifiableSet(availableTools.keySet());
    }

    /**
     * A compact "name: short purpose" line per tool - small enough to fit a remote
     * critic/refiner's limited context window (unlike the full toolSummaries).
     */
    public String compactToolList() {
        StringBuilder sb = new StringBuilder();
        availableTools.forEach((name, tool) -> {
            String gloss = tool.getDocumentation()
                    .replaceFirst("^" + Pattern.quote(name) + ":?\\s*", "");
            int end = Math.min(gloss.length(), 60);
            int dot = gloss.indexOf(". ");
            if (dot > 0 && dot < end) {
                end = dot;
            }
            sb.append(name).append(": ").append(gloss, 0, end).append("\n");
        });
        return sb.toString();
    }

    /** One "Name - first sentence of its documentation" line per tool, in registration order. */
    public List<String> toolSummaries() {
        List<String> out = new ArrayList<>();
        availableTools.forEach((name, tool) -> {
            String doc = tool.getDocumentation();
            int dot = doc.indexOf(". ");
            int colon = doc.indexOf(": ");
            int cut = dot >= 0 ? dot + 1 : (colon >= 0 ? doc.indexOf(". ", colon) + 1 : -1);
            // First sentence as the summary - NOT length-clamped; the caller wraps it.
            String summary = (cut > 0 && cut <= doc.length()) ? doc.substring(0, cut) : doc;
            out.add(name + " — " + summary.replaceFirst("^" + Pattern.quote(name) + ":?\\s*", ""));
        });
        return out;
    }
}
