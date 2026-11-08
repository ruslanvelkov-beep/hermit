package hermit.analysis.sym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceIndexTest {

    @TempDir
    Path dir;

    private SourceIndex indexOver(String... nameAndSource) throws IOException {
        for (int i = 0; i < nameAndSource.length; i += 2) {
            Files.writeString(dir.resolve(nameAndSource[i]), nameAndSource[i + 1]);
        }
        return SourceIndex.build(List.of(dir), ClasspathIndex.system());
    }

    private static List<String> deepValidate(ParseTree tree, SourceIndex sources) {
        SymbolResolver r = SymbolResolver.resolve(tree, ClasspathIndex.system(), sources);
        List<String> problems = new ArrayList<>(r.validate());
        problems.addAll(r.typeInference().validate(tree.root()));
        return problems;
    }

    @Test
    void siblingFilesResolveAcrossAnUnknownPackage() throws IOException {
        SourceIndex si = indexOver(
                "Target.java", """
                        package t;

                        class Target {
                            int value() { return 7; }
                        }
                        """,
                "A.java", """
                        package t;

                        class A {
                            int use() { return new Target().value(); }
                        }
                        """);
        assertTrue(si.hasPackage("t"));
        assertEquals(2, si.typesInPackage("t").size());
        ParseTree a = si.treeOf(dir.resolve("A.java"));
        assertEquals(List.of(), deepValidate(a, si));
        // and the chain is TYPED across files: new Target().value() infers int
        Node call = a.findMethod("A", "use").descendants(NodeType.METHOD_CALL).get(0);
        assertEquals(int.class, call.inferredClass(), "cross-file receiver typed via the source table");
    }

    @Test
    void generatedSourcesUnderBuildGeneratedAreIndexed() throws IOException {
        // F67: code generators (protobuf codegen) emit real enums/records under build/generated — a switch
        // over such an enum is unresolvable unless the index admits them. build/classes stays excluded.
        Path gen = dir.resolve("build/generated/source/proto/main/java/gen");
        Files.createDirectories(gen);
        Files.writeString(gen.resolve("GenKind.java"), """
                package gen;

                public enum GenKind {
                    PREPARE,
                    EXECUTE,
                    UNRECOGNIZED;

                    public static GenKind fromOrdinal(int o) {
                        return values()[o];
                    }
                }
                """);
        Path decoy = dir.resolve("build/classes/java/main");
        Files.createDirectories(decoy);
        Files.writeString(decoy.resolve("Junk.java"), "package junk; class Junk { unparseable!!! }");
        Path src = dir.resolve("src/main/java/app");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Use.java"), """
                package app;

                import gen.GenKind;

                class Use {
                    String label(GenKind k) {
                        return switch (k) {
                            case PREPARE -> "p";
                            case EXECUTE -> "e";
                            default -> "?";
                        };
                    }
                }
                """);
        SourceIndex si = SourceIndex.build(List.of(dir), ClasspathIndex.system());
        assertTrue(si.hasPackage("gen"), "the generated package is indexed");
        assertTrue(si.fqnsOf("GenKind").contains("gen.GenKind"), si.fqnsOf("GenKind").toString());
        assertTrue(si.files().stream().noneMatch(f -> f.toString().contains("build/classes")),
                "build/classes stays excluded");
        // the hand-written file resolves the generated enum: validation is quiet
        assertEquals(List.of(), deepValidate(si.treeOf(src.resolve("Use.java")), si));
    }

    @Test
    void crossFileHallucinationsBecomeProvable() throws IOException {
        SourceIndex si = indexOver(
                "Target.java", """
                        package t;

                        class Target {
                            int value() { return 7; }
                        }
                        """,
                "B.java", """
                        package t;

                        class B {
                            void go() {
                                new Target().nosuchMethod();
                                Missing m = null;
                                int x = new Target().value(1, 2);
                            }
                        }
                        """);
        List<String> problems = deepValidate(si.treeOf(dir.resolve("B.java")), si);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("unknown type 'Missing'"), problems.get(0));
        assertTrue(problems.get(1).contains("Target has no method 'nosuchMethod'"), problems.get(1));
        assertTrue(problems.get(2).contains("no overload of 'value' on Target takes 2 arguments"),
                problems.get(2));
    }

    @Test
    void crossFileInheritanceAndImports() throws IOException {
        Files.createDirectories(dir.resolve("base"));
        SourceIndex si = indexOver(
                "base/Base.java", """
                        package t.base;

                        public class Base {
                            protected int shared;
                            protected int ping() { return shared; }
                        }
                        """,
                "Kid.java", """
                        package t;

                        import t.base.Base;

                        class Kid extends Base {
                            int go() { return ping() + shared; }
                        }
                        """);
        assertEquals(List.of(), deepValidate(si.treeOf(dir.resolve("Kid.java")), si),
                "cross-file supertype members resolve through the source table");
    }

    @Test
    void refreshTracksEdits() throws IOException {
        SourceIndex si = indexOver(
                "Target.java", """
                        package t;

                        class Target {
                            int value() { return 7; }
                        }
                        """,
                "A.java", """
                        package t;

                        class A {
                            int use() { return new Target().value(); }
                        }
                        """);
        assertEquals(List.of(), deepValidate(si.treeOf(dir.resolve("A.java")), si));
        // rename the method in Target — A's call becomes provably wrong after refresh
        Files.writeString(dir.resolve("Target.java"), """
                package t;

                class Target {
                    int renamed() { return 7; }
                }
                """);
        si.refresh(dir.resolve("Target.java"));
        si.refresh(dir.resolve("A.java"));   // re-register A so its tree resolves against the new table
        List<String> problems = deepValidate(si.treeOf(dir.resolve("A.java")), si);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("Target has no method 'value'"));
    }

    @Test
    void hermitSourcesDeepValidateQuietlyWithTheSourceTable() throws IOException {
        Path root = Path.of("src");
        assumeTrue(Files.isDirectory(root), "run from the hermit project dir");
        SourceIndex si = SourceIndex.build(List.of(root), ClasspathIndex.system());
        assertFalse(si.files().isEmpty());
        StringBuilder all = new StringBuilder();
        for (Path f : si.files()) {
            SymbolResolver r = si.resolverFor(f);
            assertNotNull(r, "resolver for " + f);
            List<String> problems = new ArrayList<>(r.validate());
            problems.addAll(r.typeInference().validate(si.treeOf(f).root()));
            for (String p : problems) {
                all.append(f).append(' ').append(p).append('\n');
            }
        }
        assertEquals("", all.toString());
    }
}
