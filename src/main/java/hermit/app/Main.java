package hermit.app;

/** Entry point for the Hermit coding agent. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && isHelpOption(args[0]))) {
            printUsage();
            return;
        }

        String request = String.join(" ", args);
        System.out.println("The agent is not implemented yet; request: " + request);
        System.exit(2);
    }

    private static boolean isHelpOption(String argument) {
        return "--help".equals(argument) || "-h".equals(argument);
    }

    private static void printUsage() {
        System.out.println("Hermit is a general-purpose Java coding agent.");
        System.out.println("usage: hermit [--help] <request>");
        System.out.println("The agent is not implemented yet.");
    }
}
