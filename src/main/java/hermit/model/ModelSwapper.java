package hermit.model;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Model swapper. A 16 GB machine cannot hold both the small DRIVER model
 * (Qwen-7B, big context, plans/answers) and the larger REFINER (Qwen-14B, refines
 * plans) in memory at once. So when running Hermit standalone on that machine, we swap:
 * plan with the 7B, unload it and load the 14B to refine, then unload and reload the
 * 7B to keep driving.
 *
 * <p>Swapping happens transparently at the LLM-call boundary: the driver
 * ({@code ChatClient}) calls {@link #ensureDriver()} before each call and the refiner
 * (the remote agent tool) calls {@link #ensureRefiner()} before each call. A
 * last-loaded cache makes back-to-back calls to the same model a no-op, so we only
 * pay the ~5-11 s load cost at an actual phase change.
 *
 * <p>OFF by default. Enable ONLY on a 16 GB machine with {@code -Dhermit.swap.enabled=true}. On any
 * other host leave it off - the normal distributed setup (driver here, refiner on
 * another machine over the LAN) keeps both models resident on separate machines.
 *
 * <p>Tunables (system properties): {@code hermit.swap.driverModel} / {@code .driverCtx}
 * (default {@code qwen2.5-coder-7b-instruct} / 32768), {@code hermit.swap.refinerModel} /
 * {@code .refinerCtx} (default {@code qwen2.5-coder-14b-instruct-mlx} / 2048),
 * {@code hermit.swap.lms} (path to the local server's command-line tool).
 */
public final class ModelSwapper {

    private static final boolean ENABLED = Boolean.getBoolean("hermit.swap.enabled");
    private static final String DRIVER_MODEL =
            System.getProperty("hermit.swap.driverModel", "qwen2.5-coder-7b-instruct");
    private static final int DRIVER_CTX =
            Integer.getInteger("hermit.swap.driverCtx", 32768);
    private static final String REFINER_MODEL =
            System.getProperty("hermit.swap.refinerModel", "qwen2.5-coder-14b-instruct-mlx");
    private static final int REFINER_CTX =
            Integer.getInteger("hermit.swap.refinerCtx", 2048);

    private static final String LMS = resolveLms();

    /** The model key currently loaded via this swapper (null until first swap). */
    private static String loaded;

    private ModelSwapper() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static String driverModel() {
        return DRIVER_MODEL;
    }

    public static String refinerModel() {
        return REFINER_MODEL;
    }

    /** Ensure the DRIVER (7B, big context) is the loaded model. No-op unless swap mode. */
    public static void ensureDriver() {
        ensure(DRIVER_MODEL, DRIVER_CTX);
    }

    /** Ensure the REFINER (14B) is the loaded model. No-op unless swap mode. */
    public static void ensureRefiner() {
        ensure(REFINER_MODEL, REFINER_CTX);
    }

    private static synchronized void ensure(String model, int ctx) {
        if (!ENABLED || model == null || model.equals(loaded)) {
            return;
        }
        System.out.println("[model-swap] loading " + model + " @ ctx " + ctx
                + " (unloading " + (loaded == null ? "none" : loaded) + ")");
        try {
            // Unload everything first so the 16 GB guardrail lets the target load.
            run(60, LMS, "unload", "--all");
            int code = run(180, LMS, "load", model,
                    "--context-length", String.valueOf(ctx), "--gpu", "max", "-y");
            if (code == 0) {
                loaded = model;
                System.out.println("[model-swap] loaded " + model);
            } else {
                loaded = null;  // unknown state - force a reload next time
                System.err.println("[model-swap] load failed (exit " + code + ") for " + model);
            }
        } catch (Exception e) {
            loaded = null;
            System.err.println("[model-swap] swap error: " + e.getMessage());
        }
    }

    private static int run(int timeoutSeconds, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(
                ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("timed out: " + String.join(" ", cmd));
        }
        return p.exitValue();
    }

    private static String resolveLms() {
        String override = System.getProperty("hermit.swap.lms");
        if (override != null && new File(override).canExecute()) {
            return override;
        }
        String home = System.getProperty("user.home");
        File inHome = new File(home, ".lmstudio/bin/lms");
        if (inHome.canExecute()) {
            return inHome.getAbsolutePath();
        }
        return "lms";  // fall back to PATH
    }
}
