package hermit.analysis.sym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Static imports whose owner is a PROJECT SOURCE type (spec verbs, fixture helpers) must resolve to the
 * declared members, so the call's return type infers and mistyped chain methods on it are provable —
 * the shape of a test-support library. Previously the owner's absence from the CLASSPATH made the
 * import an untyped placeholder and opened the unit's statics, silencing every downstream check.
 */
class StaticImportSourceOwnerTest {

    @TempDir
    Path dir;

    private SourceIndex fixture() throws IOException {
        Files.writeString(dir.resolve("Request.java"), """
                package spec;
                public class Request {
                    public Request withOwner(String p) { return this; }
                }
                """);
        Files.writeString(dir.resolve("Verbs.java"), """
                package spec;
                public class Verbs {
                    public static final String DEFAULT_OWNER = "default";
                    public static Request newRequest() { return new Request(); }
                }
                """);
        return SourceIndex.build(List.of(dir), ClasspathIndex.system());
    }

    private List<String> problemsOf(String source, SourceIndex sources) {
        ParseTree tree = ParseTree.parse(source);
        SymbolResolver r = SymbolResolver.resolve(tree, ClasspathIndex.system(), sources);
        List<String> out = new ArrayList<>(r.validate());
        out.addAll(r.typeInference().validate(tree.root()));
        return out;
    }

    @Test
    void chainOnAStaticImportedSourceVerbIsTyped() throws IOException {
        SourceIndex si = fixture();
        List<String> bad = problemsOf("""
                package use;
                import static spec.Verbs.newRequest;
                class Suite {
                    void t() { newRequest().withOwnerh("x"); }
                }
                """, si);
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("Request has no method 'withOwnerh'"), bad.get(0));

        List<String> good = problemsOf("""
                package use;
                import static spec.Verbs.newRequest;
                import static spec.Verbs.DEFAULT_OWNER;
                class Suite {
                    void t() { newRequest().withOwner(DEFAULT_OWNER).withOwner("y"); }
                }
                """, si);
        assertTrue(good.isEmpty(), good.toString());
    }

    @Test
    void wildcardStaticImportOfASourceOwnerAlsoResolves() throws IOException {
        SourceIndex si = fixture();
        List<String> bad = problemsOf("""
                package use;
                import static spec.Verbs.*;
                class Suite {
                    void t() { newRequest().withOwnerh(DEFAULT_OWNER); }
                }
                """, si);
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).contains("withOwnerh"), bad.get(0));
    }

    @Test
    void unloadableNonSourceOwnerStaysOpen() throws IOException {
        SourceIndex si = fixture();
        List<String> ps = problemsOf("""
                package use;
                import static some.lib.Nowhere.magic;
                class Suite {
                    void t() { magic().anything(); }
                }
                """, si);
        assertTrue(ps.isEmpty(), "an owner we cannot see must not produce provable problems: " + ps);
    }
}
