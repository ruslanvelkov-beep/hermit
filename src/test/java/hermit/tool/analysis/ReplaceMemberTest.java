package hermit.tool.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import org.junit.jupiter.api.Test;

/**
 * a port had to make a private method package-private. replaceBody with the whole method kept
 * the old declaration and said nothing, and no op changed a member's declaration.
 */
class ReplaceMemberTest {

    private static final String SOURCE = """
            class R {
                int a;

                // The scope a node owns.
                private int own(int n) {
                    return n;
                }

                int b() {
                    return own(1);
                }
            }
            """;

    private static TreeEditTool.Outcome apply(String op, String method, String code) {
        return TreeEditTool.apply(SOURCE, ClasspathIndex.system(), null, op, method, null, null, code);
    }

    @Test
    void replaceMemberChangesTheDeclarationInPlaceAndKeepsTheLayout() {
        TreeEditTool.Outcome out = apply("replaceMember", "R#own",
                "// The scope a node owns, for the other classes of the package.\nint own(int n) {\n    return n + 0;\n}");
        assertTrue(out.ok(), String.valueOf(out.problems()));
        assertEquals("""
                class R {
                    int a;

                    // The scope a node owns, for the other classes of the package.
                    int own(int n) {
                        return n + 0;
                    }

                    int b() {
                        return own(1);
                    }
                }
                """, out.rendered());
    }

    @Test
    void replaceBodyKeepsTodaysDeclarationEvenWhenTheCodeWritesAnother() {
        // the lenient path the planner relies on (TreeEditToolTest.aReplaceBodyWithoutMethodTakesItsTargetFrom...)
        TreeEditTool.Outcome out = apply("replaceBody", "R#own", "int own(int n) {\n    return n + 0;\n}");
        assertTrue(out.ok(), String.valueOf(out.problems()));
        assertTrue(out.rendered().contains("private int own(int n) {\n        return n + 0;"), out.rendered());
    }
}
