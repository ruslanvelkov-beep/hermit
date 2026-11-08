package hermit.tool.helper;

/** Test access to HelperModelTool's package-private parsing. */
public final class HelperModelToolTestAccess {
    private HelperModelToolTestAccess() {}

    public static String completionContent(String json) {
        return HelperModelTool.completionContent(json);
    }
}
