package hermit.tool.analysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import org.junit.jupiter.api.Test;

/**
 * a ported method used TokenType.ELLIPSIS in a file that did not import TokenType. The
 * problem was "unknown variable 'TokenType'", and the auto-import, which handled only "unknown type", added nothing.
 */
class QualifierImportTest {

    @Test
    void aTypeUsedAsAQualifierIsImportedLikeAnUnknownType() {
        String source = "class Q {\n    int n;\n}\n";
        TreeEditTool.Outcome out = TreeEditTool.apply(source, ClasspathIndex.system(), null, "insertMember", null, "Q", null,
                "java.util.List<String> none() {\n    return Collections.emptyList();\n}");
        assertTrue(out.ok(), String.valueOf(out.problems()));
        assertTrue(out.rendered().contains("import java.util.Collections;"), out.rendered());
    }
}
