package hermit.analysis.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.tree.ParseTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestReachTest {

    @TempDir
    Path dir;

    private CodeGraph graph() throws IOException {
        Path main = dir.resolve("src/main/java");
        Path test = dir.resolve("src/test/java");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("Svc.java"), """
                class Svc {
                    void work() { inner(); }
                    void inner() { }
                    void unused() { }
                }
                """);
        Files.writeString(test.resolve("SvcTest.java"), """
                class SvcTest {
                    @Test
                    void coversWork() { new Svc().work(); }

                    @org.junit.jupiter.params.ParameterizedTest
                    void coversInnerDirectly() { new Svc().inner(); }

                    void helperOnly() { new Svc().inner(); }

                    @Test
                    void unrelated() { new Svc().unused(); }
                }
                """);
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void listsTheTestsThatTransitivelyExerciseAMethod() throws IOException {
        // the reverse walk from a method lists the annotated test methods that reach it, directly or through callers
        TestReach.Result r = TestReach.of(graph(), "Svc#inner", 8);
        assertTrue(r.exercised(), r.toString());
        List<String> tests = r.tests().stream().map(TestReach.Route::testMethod).toList();
        assertTrue(tests.contains("SvcTest#coversWork"), tests.toString());
        assertTrue(tests.contains("SvcTest#coversInnerDirectly"), tests.toString());
        assertFalse(tests.contains("SvcTest#unrelated"), tests.toString());
        assertFalse(tests.contains("SvcTest#helperOnly"), tests.toString());
        // the un-annotated test-source method is reported as a helper, not a test
        assertTrue(r.testHelpers().contains("SvcTest#helperOnly"), r.testHelpers().toString());
        // ranking: the test that shares the target's vocabulary sorts first
        assertTrue(r.tests().get(0).score() >= r.tests().get(r.tests().size() - 1).score(),
                r.tests().toString());
        // paths run callee -> caller
        TestReach.Route viaWork = r.tests().stream()
                .filter(t -> t.testMethod().equals("SvcTest#coversWork")).findFirst().get();
        assertEquals(List.of("Svc#inner", "Svc#work", "SvcTest#coversWork"), viaWork.path());
    }

    @Test
    void overlapRanksTheTestsThatShareTheTargetsVocabulary() throws IOException {
        // both tests reach the target through the same entry point, but only one of them can run it (the other
        // passes an empty list, so the loop never calls it); the test that touches the target's collaborator ranks
        // first
        Path main = dir.resolve("src/main/java");
        Path test = dir.resolve("src/test/java");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("Library.java"), """
                import java.util.List;

                class Library {
                    Shelf shelf = new Shelf();
                    Catalog catalog = new Catalog();

                    void acceptReturns(List<String> titles) {
                        for (String title : titles) {
                            shelf.reorder(catalog);
                        }
                    }
                }

                class Shelf {
                    void reorder(Catalog catalog) { catalog.sortByAuthor(); }
                }

                class Catalog {
                    void sortByAuthor() { }
                }
                """);
        Files.writeString(test.resolve("LibraryTest.java"), """
                import java.util.List;

                class LibraryTest {
                    Library library = new Library();
                    Catalog catalog = new Catalog();

                    @Test
                    void acceptsAnEmptyBatch() { library.acceptReturns(List.of()); }

                    @Test
                    void returnedBooksAreSortedByAuthor() {
                        catalog.sortByAuthor();
                        library.acceptReturns(List.of("first", "second"));
                    }
                }
                """);
        TestReach.Result r = TestReach.of(CodeGraph.build(List.of(dir), ClasspathIndex.system()), "Shelf#reorder", 8);
        assertEquals(2, r.tests().size(), r.toString());
        assertEquals("LibraryTest#returnedBooksAreSortedByAuthor", r.tests().get(0).testMethod(),
                r.tests().toString());
        assertTrue(r.tests().get(0).score() > r.tests().get(1).score(), r.tests().toString());
        assertTrue(r.tests().get(0).shared().contains("catalog") || r.tests().get(0).shared().contains("sortByAuthor"),
                r.tests().get(0).shared().toString());
    }

    @Test
    void rareSharedIdentifiersOutrankCommonSetup() throws IOException {
        // three tests share the target's configuration fields; one test shares only the target's collaborator and
        // the method it calls: fewer shared words, yet a higher score
        Path main = dir.resolve("src/main/java");
        Path test = dir.resolve("src/test/java");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("Warehouse.java"), """
                class Warehouse {
                    int aisleCount;
                    int shelfDepth;
                    int reserveSlots;
                    Conveyor conveyor = new Conveyor();

                    int rebalance() {
                        int capacity = aisleCount * shelfDepth + reserveSlots;
                        conveyor.shiftPallets(capacity);
                        return capacity;
                    }
                }

                class Conveyor {
                    void shiftPallets(int count) { }
                }

                class LoadingDock {
                    int unload(Warehouse warehouse, int crates) {
                        return crates > 0 ? warehouse.rebalance() : 0;
                    }
                }
                """);
        Files.writeString(test.resolve("LoadingDockTest.java"), """
                class LoadingDockTest {
                    Warehouse warehouse = new Warehouse();
                    LoadingDock dock = new LoadingDock();
                    Conveyor conveyor = new Conveyor();

                    @Test
                    void smallSite() {
                        warehouse.aisleCount = 2;
                        warehouse.shelfDepth = 3;
                        warehouse.reserveSlots = 1;
                        dock.unload(warehouse, 5);
                    }

                    @Test
                    void wideSite() {
                        warehouse.aisleCount = 9;
                        warehouse.shelfDepth = 3;
                        warehouse.reserveSlots = 1;
                        dock.unload(warehouse, 5);
                    }

                    @Test
                    void deepSite() {
                        warehouse.aisleCount = 2;
                        warehouse.shelfDepth = 8;
                        warehouse.reserveSlots = 4;
                        dock.unload(warehouse, 5);
                    }

                    @Test
                    void beltMoves() {
                        conveyor.shiftPallets(1);
                        dock.unload(warehouse, 7);
                    }
                }
                """);
        TestReach.Result r = TestReach.of(CodeGraph.build(List.of(dir), ClasspathIndex.system()),
                "Warehouse#rebalance", 8);
        assertEquals(4, r.tests().size(), r.toString());
        assertEquals("LoadingDockTest#beltMoves", r.tests().get(0).testMethod(), r.tests().toString());
        assertTrue(r.tests().get(0).overlap() < r.tests().get(1).overlap(),
                "fewer shared words, yet first: " + r.tests());
        // the collaborators of the target (its callee names and receiver types) sort ahead of setup vocabulary
        assertTrue(List.of("conveyor", "shiftPallets").contains(r.tests().get(0).shared().get(0)),
                "a collaborator first: " + r.tests().get(0).shared());
    }

    @Test
    void reportsWhenNoTestReachesTheMethod() throws IOException {
        Files.writeString(dir.resolve("Lonely.java"), "class Lonely { void alone() { } }\n");
        TestReach.Result r = TestReach.of(CodeGraph.build(List.of(dir), ClasspathIndex.system()), "Lonely#alone", 8);
        assertFalse(r.exercised());
        assertTrue(r.tests().isEmpty());
    }

    @Test
    void aJavadoccedTestMethodIsStillATest() throws IOException {
        // a Javadoc or block comment above @Test is leading trivia of the annotation node; the method is still a
        // test, not a helper
        Path main = dir.resolve("src/main/java");
        Path test = dir.resolve("src/test/java");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("Svc2.java"), """
                class Svc2 {
                    void work() { inner(); }
                    void inner() { }
                }
                """);
        Files.writeString(test.resolve("Svc2Test.java"), """
                class Svc2Test {
                    /**
                     * A documented test.
                     *
                     * <p>Second paragraph, with {@code inline code}.
                     */
                    @Test
                    void documentedCoversInner() { new Svc2().inner(); }

                    /* a plain block comment */
                    @Test
                    @DisplayName("annotated twice")
                    void alsoCoversInner() { new Svc2().inner(); }
                }
                """);
        TestReach.Result r = TestReach.of(CodeGraph.build(List.of(dir), ClasspathIndex.system()), "Svc2#inner", 8);
        List<String> tests = r.tests().stream().map(TestReach.Route::testMethod).toList();
        assertTrue(tests.contains("Svc2Test#documentedCoversInner"),
                "javadoc'd test: " + tests + " helpers=" + r.testHelpers());
        assertTrue(tests.contains("Svc2Test#alsoCoversInner"), "block-commented test: " + tests);
        assertTrue(r.testHelpers().isEmpty(), "neither is a helper: " + r.testHelpers());
    }

    @Test
    void annotationNamesAreSimpleNames() {
        var tree = ParseTree.parse("""
                class T {
                    @org.junit.jupiter.api.Test @Deprecated void m() { @SuppressWarnings("x") int v = 1; }
                }
                """);
        var m = tree.findMethod("T", "m");
        assertEquals(List.of("Test", "Deprecated"), TestReach.annotationNames(m));
        // leading trivia on the first annotation must not hide it
        var doc = ParseTree.parse("""
                class D {
                    /** doc */
                    @org.junit.jupiter.api.Test
                    void m() { }
                }
                """);
        assertEquals(List.of("Test"), TestReach.annotationNames(doc.findMethod("D", "m")));
    }

    @Test
    void directTestsOfTheTargetsOwnClassOutrankLongRoutesWithCoincidentalVocabulary() throws IOException {
        // a test of the target's own class reaches it in two hops through that class's entry point; a test of another
        // class reaches it through a chain of other types and shares more of the target's common library calls. The
        // direct route sorts first, and the vocabulary of the fixture method the direct test calls counts for it.
        Path main = dir.resolve("src/main/java");
        Path test = dir.resolve("src/test/java");
        Files.createDirectories(main);
        Files.createDirectories(test);
        Files.writeString(main.resolve("PageLayout.java"), """
                import java.util.List;

                class PageLayout {
                    GlyphCache glyphCache = new GlyphCache();
                    Hyphenator hyphenator = new Hyphenator();

                    String paginate(String text) { return fitColumns(text); }

                    String fitColumns(String text) {
                        int[] widths = glyphCache.measureRun(text);
                        String[] syllables = hyphenator.breakSyllables(text);
                        int columns = Math.max(1, Math.min(widths.length, syllables.length));
                        return String.join("\\n", List.of(syllables).subList(0, columns));
                    }
                }

                class GlyphCache {
                    int[] measureRun(String text) { return new int[0]; }
                }

                class Hyphenator {
                    String[] breakSyllables(String text) { return new String[0]; }
                }
                """);
        Files.writeString(main.resolve("PrintShop.java"), """
                class PrintShop {
                    Typesetter typesetter = new Typesetter();

                    void acceptOrder(String manuscript) {
                        if (!manuscript.isEmpty()) {
                            typesetter.setPages(manuscript);
                        }
                    }
                }

                class Typesetter {
                    String setPages(String manuscript) { return Bindery.collate(manuscript); }
                }

                final class Bindery {
                    static String collate(String manuscript) { return new PageLayout().paginate(manuscript); }
                }
                """);
        Files.writeString(test.resolve("PageLayoutTest.java"), """
                class PageLayoutTest {
                    String sample = "a short paragraph";

                    @Test
                    void paginatesOneParagraph() {
                        loadFonts();
                        new PageLayout().paginate(sample);
                    }

                    void loadFonts() {
                        new GlyphCache().measureRun(sample);
                        new Hyphenator().breakSyllables(sample);
                    }
                }
                """);
        Files.writeString(test.resolve("PrintShopTest.java"), """
                import java.util.List;

                class PrintShopTest {
                    @Test
                    void rejectsAnEmptyOrder() {
                        int columns = Math.max(1, Math.min(4, 2));
                        String.join("\\n", List.of("left", "right"));
                        new PrintShop().acceptOrder("");
                    }
                }
                """);
        CodeGraph g = CodeGraph.build(List.of(dir), ClasspathIndex.system());
        TestReach.Result r = TestReach.of(g, "PageLayout#fitColumns", 10);
        List<String> tests = r.tests().stream().map(TestReach.Route::testMethod).toList();
        assertEquals(List.of("PageLayoutTest#paginatesOneParagraph", "PrintShopTest#rejectsAnEmptyOrder"), tests);
        TestReach.Route top = r.tests().get(0);
        assertTrue(top.direct(), top.toString());
        assertEquals(2, top.hops());
        TestReach.Route far = r.tests().get(1);
        assertFalse(far.direct(), far.toString());
        assertEquals(5, far.hops());
        // the fixture helper's vocabulary counts for the test that delegates its arrangement to it
        assertTrue(top.shared().contains("measureRun"), top.shared().toString());
        assertTrue(top.shared().contains("breakSyllables"), top.shared().toString());
        // and the far test still carries the higher raw overlap — directness, not vocabulary, decides the tier
        assertTrue(far.overlap() >= top.overlap() || far.score() < top.score(), r.tests().toString());
    }

    @Test
    void testClassesNamedAfterTheTargetTypeAndDirectRoutes() {
        assertTrue(TestReach.namedAfter("com.example.PageLayoutTest", Set.of("PageLayoutEngine")));
        assertTrue(TestReach.namedAfter("InvoiceLedgerTests", Set.of("InvoiceLedger")));
        assertTrue(TestReach.namedAfter("PageLayoutEngineTest", Set.of("PageLayoutEngine")));
        assertFalse(TestReach.namedAfter("SpoolQueueTest", Set.of("PageLayoutEngine")));
        assertFalse(TestReach.namedAfter("BoxTest", Set.of("Box")));
        List<CodeGraph.MethodRef> targets =
                List.of(new CodeGraph.MethodRef("Gauge", "read", Path.of("Gauge.java"), 1, null));
        assertTrue(TestReach.isDirect(List.of("Gauge#read", "Gauge#poll", "GaugeTest#t"), targets));
        assertTrue(TestReach.isDirect(List.of("Gauge#read", "GaugeTest#t"), targets));
        assertFalse(TestReach.isDirect(List.of("Gauge#read", "Panel#refresh", "GaugeTest#t"), targets));
        assertFalse(TestReach.isDirect(List.of("Gauge#read", "Gauge#poll", "Gauge#start", "GaugeTest#t"), targets));
    }
}
