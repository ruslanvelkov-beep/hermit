package hermit.claims;

import hermit.model.ChatClient;
import hermit.model.EndpointPolicy;
import hermit.model.HelperEndpoint;
import hermit.model.HostedPlatforms;
import hermit.model.ModelRouter;
import hermit.tool.helper.HelperModelTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The claims of the running turn and the inspector that maps them - one per agent process, bound by the agent at the start
 * of a turn so that the ClaimInspector tool and the answer gate work on the same ledger.
 * Opt-in: {@code -Dhermit.claims.enabled}.
 */
public final class ClaimSession {

    private ClaimSession() {}

    private static volatile List<Claim> ledger = new ArrayList<>();
    private static volatile ClaimInspector inspector;

    public static boolean enabled() {
        return Boolean.getBoolean("hermit.claims.enabled");
    }

    public static int maxClaims() {
        return Integer.getInteger("hermit.claims.maxClaims", 20);
    }

    public static int maxRecordings() {
        return Integer.getInteger("hermit.claims.maxRecordings", 8);
    }

    /** Claims the answer gate inspects at once when an ending rests on unmapped claims. */
    public static int perGate() {
        return Integer.getInteger("hermit.claims.perGate", 3);
    }

    public static void bind(List<Claim> turnLedger) {
        ledger = turnLedger;
    }

    public static List<Claim> ledger() {
        return ledger;
    }

    /** Tests and other runs use their own inspector. */
    public static void use(ClaimInspector custom) {
        inspector = custom;
    }

    public static synchronized ClaimInspector inspector() {
        if (inspector == null) {
            String project = System.getProperty("hermit.projectDir", System.getProperty("hermit.bash.defaultDir", "."));
            Path root = Path.of(project);
            Path worktree = Path.of(System.getProperty("hermit.claims.worktree",
                    System.getProperty("java.io.tmpdir") + "/hermit-claims-" + ProcessHandle.current().pid()));
            String hindsight = System.getProperty("hermit.hindsight.dir", "");
            boolean canRecord = !hindsight.isBlank() && Files.isRegularFile(
                    Path.of(hindsight, "reader", "build", "install", "reader", "bin", "reader"));
            inspector = new ClaimInspector(localModel(), translator(),
                    WorkbenchPorts.code(root, System.getProperty("hermit.classesDir")),
                    canRecord && !Boolean.getBoolean("hermit.claims.noRuntime")
                            ? WorkbenchPorts.runtime(root, worktree, hindsight) : null,
                    Boolean.getBoolean("hermit.claims.writeTests")
                            ? WorkbenchPorts.writer(root, worktree) : null, maxRecordings());
        }
        return inspector;
    }

    /** The run's own model (its profile: Set B, thinking off - the better claim reviewer on the replay set). */
    static Function<String, String> localModel() {
        return prompt -> {
            try {
                return new ChatClient().getResponse(prompt);
            } catch (Exception e) {
                return null;
            }
        };
    }

    /**
     * The hosted model translates claims to code when allowed (-Dhermit.claims.hostedTranslator); else the local one.
     */
    static Function<String, String> translator() {
        if (!Boolean.getBoolean("hermit.claims.hostedTranslator")
                || !EndpointPolicy.allowed(HostedPlatforms.DEEPSEEK_BASE_URL)
                || !HostedPlatforms.hasKey(HostedPlatforms.DEEPSEEK)) {
            return null;
        }
        HelperEndpoint cfg = HelperEndpoint.named("plan2", "DeepSeek-plan",
                HostedPlatforms.DEEPSEEK_BASE_URL, "deepseek-chat");
        if (cfg == null) {
            return null;
        }
        HelperModelTool tool = new HelperModelTool(cfg);
        Function<String, String> local = localModel();
        return prompt -> {
            ModelRouter.countCloudCall();
            String reply = tool.complete(prompt, 4000);
            return reply != null ? reply : local.apply(prompt);
        };
    }
}
