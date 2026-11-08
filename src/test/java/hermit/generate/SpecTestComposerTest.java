package hermit.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.SpecReach;
import hermit.analysis.graph.SpecReachTest;
import hermit.analysis.sym.ClasspathIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Composing a spec test member into a suite from verbs, with import resolution and the vacuity guard. */
class SpecTestComposerTest {

    @TempDir
    Path dir;

    private final SpecReachTest shape = new SpecReachTest();

    @BeforeEach
    void fixture() throws IOException {
        shape.dir = dir;
        shape.fixture();
        // the spec framework bits a suite needs, at the names the default conventions give them
        Files.writeString(dir.resolve("SpecTest.java"), "package example.spec.junit;\npublic @interface SpecTest {}\n");
        Files.writeString(dir.resolve("DynamicTest.java"), "package org.junit.jupiter.api;\npublic class DynamicTest {}\n");
        Files.writeString(dir.resolve("SpecRunner.java"), "package example.spec;\n"
                + "import java.util.stream.Stream;\nimport org.junit.jupiter.api.DynamicTest;\n"
                + "public class SpecRunner {\n    public static Stream<DynamicTest> specTest(Object... ops) { return null; }\n}\n");
        Files.writeString(dir.resolve("SpecSuite.java"), "package example.spec.suites;\n"
                + "public class SpecSuite {\n    public static final String DEFAULT_USER = \"reader\";\n}\n");
        Files.writeString(dir.resolve("LoanSuite.java"), "package example.spec.suites.loans;\n\n"
                + "class LoanSuite {\n}\n");
    }

    private CodeGraph graph() throws IOException {
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void composesValidatedMemberWithStaticImportsAndDispatchReach() throws IOException {
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite,
                "checkoutStampsDueDate",
                List.of("checkoutBook().byMember(DEFAULT_USER)", "getShelf(\"42\").hasShelf(\"x\")"),
                "LoanLedger#stampDueDate");
        assertTrue(c.ok(), String.valueOf(c.problems()));
        String out = c.rendered();
        assertTrue(out.contains("@SpecTest"), out);
        assertTrue(out.contains("final Stream<DynamicTest> checkoutStampsDueDate()"), out);
        assertTrue(out.contains("import static spec.CommandVerbs.checkoutBook;"), out);
        assertTrue(out.contains("import static spec.LookupVerbs.getShelf;"), out);
        assertTrue(out.contains("import static example.spec.suites.SpecSuite.DEFAULT_USER;"), out);
        assertTrue(out.contains("import static example.spec.SpecRunner.specTest;"), out);
        assertTrue(out.contains("import example.spec.junit.SpecTest;"), out);
        // dispatch: the checkout verb reaches the target through CheckoutHandler; the lookup verb does not
        assertTrue(c.verbDispatch().get("checkoutBook").startsWith("Checkout → CheckoutHandler"),
                c.verbDispatch().toString());
        assertTrue(c.verbDispatch().get("getShelf").startsWith("ShelfLookup → ShelfLookupHandler"),
                c.verbDispatch().toString());
        assertEquals(1, c.reaching().size(), c.reaching().toString());
        assertTrue(c.reaching().get(0).startsWith("checkoutBook → CheckoutHandler#handle -> "), c.reaching().get(0));
        assertEquals(List.of("getShelf"), c.unreached());
        // the whole edited file re-parses and re-validates (no new problems)
        assertTrue(c.problems().isEmpty());
    }

    @Test
    void vacuousTestIsRefusedAndNothingRendered() throws IOException {
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite,
                "onlyLookups", List.of("getShelf(\"42\")"), "LoanLedger#stampDueDate");
        assertFalse(c.ok());
        assertTrue(c.problems().get(0).startsWith("VACUOUS"), c.problems().toString());
        assertEquals(null, c.rendered());
    }

    @Test
    void unknownVerbIsRejectedBeforeSplicing() throws IOException {
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite,
                "bogus", List.of("frobnicate()"), null);
        assertFalse(c.ok());
        assertTrue(c.problems().get(0).startsWith("no known spec verb"), c.problems().toString());
    }

    @Test
    void mistypedChainMethodIsCaughtByWholeProgramValidation() throws IOException {
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite,
                "typo", List.of("checkoutBook().byMemberr(DEFAULT_USER)"), null);
        assertFalse(c.ok(), c.rendered());
        assertTrue(String.join("\n", c.problems()).contains("byMemberr"), c.problems().toString());
    }

    @Test
    void newSuiteSkeletonAcceptsAComposition() throws IOException {
        CodeGraph g = graph();
        String skeleton = SpecTestComposer.newSuiteSource("example.spec.suites.loans", "NewSuite");
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), skeleton,
                "fresh", List.of("checkoutCopy()"), null);
        assertTrue(c.ok(), String.valueOf(c.problems()));
        assertTrue(c.rendered().contains("class NewSuite {"), c.rendered());
        assertTrue(c.rendered().contains("checkoutCopy()"), c.rendered());
        // the skeleton already imports the framework - only the verb import is new
        assertEquals(List.of("static spec.CommandVerbs.checkoutCopy"), c.imports());
    }

    @Test
    void aSourceRootThatAlreadyEndsWithThePackageIsThePackageDirectory() {
        // dir=…/spec/suites + package example.spec.suites → doubled path
        Path pkgDir = Path.of("/w/spec-tests/src/main/java/example/spec/suites");
        assertEquals(pkgDir.resolve("OverdueNoticeTest.java"),
                SpecTestComposer.suitePath(pkgDir, "example.spec.suites", "OverdueNoticeTest"));
        Path root = Path.of("/w/spec-tests/src/main/java");
        assertEquals(pkgDir.resolve("OverdueNoticeTest.java"),
                SpecTestComposer.suitePath(root, "example.spec.suites", "OverdueNoticeTest"));
    }

    @Test
    void anUnknownVerbNamesItsClosestKnownVerbs() {
        // shelfGet(42) is not a verb; the lookup verb is getShelf
        List<String> closest = SpecTestComposer.closestVerbs("shelfGet",
                List.of("getShelf", "shelfMove", "shelfLabel", "getMemberInfo", "bookReturn"), 3);
        assertEquals("getShelf", closest.get(0), closest.toString());
        assertTrue(closest.contains("shelfMove") || closest.contains("shelfLabel"), closest.toString());
    }

    @Test
    void anOpsShapeProblemsAreNamed() {
        // shelfGet(shelf=42), hex('deadbeef') - unknown verb, named argument, single quotes
        List<String> known = List.of("getShelf", "shelfMove", "shelfLabel", "bookCreate");
        List<String> problems = SpecTestComposer.opShapeProblems("shelfGet(shelf=42)", known);
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown verb 'shelfGet'") && p.contains("getShelf")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("named arguments")), problems.toString());
        assertTrue(SpecTestComposer.opShapeProblems("shelfLabel(shelf=42, text=hex('dead'))", known).stream()
                .anyMatch(p -> p.contains("single quotes")));
        assertTrue(SpecTestComposer.opShapeProblems("getShelf(\"42\").hasShelf(expected)", known).isEmpty());
        assertTrue(SpecTestComposer.opShapeProblems("shelfLabel(\"42\").text(hex(\"dead\"))", known).isEmpty());
    }

    @Test
    void declaredLocalsPrecedeTheSpecTestCall() {
        // expectedLabel stayed an unknown variable for want of a local
        String text = SpecTestComposer.methodText("shelfLabelFollowsTheTitle",
                List.of("getShelf(\"42\").hasShelf(expectedLabel)"),
                List.of("title = \"abc\"", "final var expectedLabel = title.toUpperCase();"));
        assertTrue(text.contains("    final var title = \"abc\";\n    final var expectedLabel = title.toUpperCase();\n"
                + "    return specTest("), text);
        assertEquals(SpecTestComposer.methodText("t", List.of("a()")),
                SpecTestComposer.methodText("t", List.of("a()"), List.of()));
    }

    @Test
    void theRefusalShowsAConcreteOpForTheReachingVerb() {
        // shelfGet, shelfQuery … - four invented names for getShelf
        SpecReach.Verb v = new SpecReach.Verb("getShelf", "LookupVerbs", "com.x.LookupVerbs",
                "getShelf(final String isbn)", "SpecShelfLookup", "ShelfLookup");
        assertEquals("getShelf(\"42\")", SpecTestComposer.exampleOp(v, List.of("bookCreate(42).titled(t)")));
        assertEquals("getShelf(\"1\")", SpecTestComposer.exampleOp(v, List.of()));
        SpecReach.Verb s = new SpecReach.Verb("getShelf", "LookupVerbs", "com.x.LookupVerbs",
                "getShelf(final Supplier<String> supplier)", "SpecShelfLookup", "ShelfLookup");
        assertEquals("getShelf(<Supplier<String>>)", SpecTestComposer.exampleOp(s, List.of()));
    }

    // hex(...) / Payload.of(...) are the suites' idiom - imported like verbs
    @Test
    void specHelpersUsedUnqualifiedAreImported() {
        List<String> imps = SpecTestComposer.specHelperImports(
                "return specTest(getShelf(\"42\").hasShelf(hex(digest(Payload.of(1)))));");
        assertTrue(imps.contains("static example.spec.util.Hex.hex"), imps.toString());
        assertTrue(imps.contains("example.spec.util.Payload"), imps.toString());
        // the spec utilities digest helper the suites use
        assertTrue(SpecTestComposer.specHelperImports("final var h = hex(sha256Of(cover));")
                .contains("static example.spec.util.Digests.sha256Of"));
        assertFalse(imps.contains("static example.spec.util.Hex.unhex"), imps.toString());
        // qualified uses and lookalikes are not helpers
        assertTrue(SpecTestComposer.specHelperImports("Utils.hex(x); hexString(y); myPayload.get()").isEmpty());
    }

    // an overload problem on a spec verb names the verb's real overloads and a concrete op
    @Test
    void overloadProblemOnASpecVerbNamesItsOverloads() {
        SpecReach.Verb v = new SpecReach.Verb("bookCreate", "CommandVerbs", "com.x.CommandVerbs",
                "bookCreate(final String title)", "SpecBookCreate", "BookCreate");
        Map<String, List<SpecReach.Verb>> byName = Map.of("bookCreate", List.of(v));
        List<String> out = SpecTestComposer.withVerbOverloads(
                List.of("[16:17] no overload of 'bookCreate' takes 2 arguments", "[16:57] no visible method 'hex'"),
                byName, List.of("bookCreate(42, \"Moby\")"));
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("bookCreate's overloads: bookCreate(final String title)"), out.get(0));
        assertTrue(out.get(0).contains("e.g. bookCreate(\"42\")"), out.get(0));
        assertTrue(out.get(0).contains("chained after the call"), out.get(0));
        assertEquals("[16:57] no visible method 'hex'", out.get(1));
        // an overload problem on a NON-verb method is left alone
        assertEquals("no overload of 'foo' takes 3 arguments", SpecTestComposer.withVerbOverloads(
                List.of("no overload of 'foo' takes 3 arguments"), byName, List.of()).get(0));
    }

    // moveShelf(...) three plans in a row while the refusal named shelfMove - same words, other order
    @Test
    void anUnknownVerbMadeOfOneCatalogueVerbsWordsIsThatVerb() {
        List<String> known = List.of("shelfMove", "bookCreate", "getShelfLabel", "moveLargeShelf", "moveSpecialShelf");
        assertEquals("shelfMove", SpecTestComposer.sameWordsVerb("moveShelf", known));
        assertEquals("getShelfLabel", SpecTestComposer.sameWordsVerb("shelfLabelGet", known));
        assertNull(SpecTestComposer.sameWordsVerb("shelfGetLabel2", known));   // not the same words
        assertNull(SpecTestComposer.sameWordsVerb("shelfMove", known));        // already a verb
        assertNull(SpecTestComposer.sameWordsVerb("largeMove", known));        // a subset is not a permutation
        // ambiguity: two catalogue verbs with the same words → no guess
        assertNull(SpecTestComposer.sameWordsVerb("ShelfLabel", List.of("shelfLabel", "labelShelf")));
    }

    // the package EXTENDED the directory's package and the path doubled; a relative dir that
    // repeats the project dir's last segment doubled it too
    @Test
    void suitePathAppendsOnlyThePackageSegmentsTheDirectoryLacks() {
        Path suites = Path.of("/p/library-app/spec-tests/src/main/java/example/spec/suites");
        assertEquals(suites.resolve("misc/X.java"),
                SpecTestComposer.suitePath(suites, "example.spec.suites.misc", "X"));
        assertEquals(suites.resolve("X.java"), SpecTestComposer.suitePath(suites, "example.spec.suites", "X"));
        Path root = Path.of("/p/library-app/spec-tests/src/main/java");
        assertEquals(suites.resolve("misc/X.java"),
                SpecTestComposer.suitePath(root, "example.spec.suites.misc", "X"));
        // an unrelated package under a package directory still appends everything
        assertEquals(suites.resolve("org/other/X.java"), SpecTestComposer.suitePath(suites, "org.other", "X"));
    }

    @Test
    void aRelativeDirThatRepeatsTheProjectTailContinuesFromTheProject() {
        Path project = Path.of("/work/checkout/library-app");
        assertEquals(Path.of("/work/checkout/library-app/spec-tests/src/main/java"),
                SpecTestComposer.resolveOverlapping(project, "library-app/spec-tests/src/main/java"));
        assertEquals(Path.of("/work/checkout/library-app/spec-tests/src/main/java"),
                SpecTestComposer.resolveOverlapping(project, "spec-tests/src/main/java"));
        assertEquals(Path.of("/work/checkout/library-app/spec-tests/src/main/java"),
                SpecTestComposer.resolveOverlapping(Path.of("/work/checkout"), "library-app/spec-tests/src/main/java"));
        assertEquals(Path.of("/abs/x"), SpecTestComposer.resolveOverlapping(project, "/abs/x"));
    }

    // the composition that passed validation with an undeclared receiver, a numeric id, a
    // two-argument bookCreate, an unused local and no assertion - each is caught deterministically before the resolver
    @Test
    void soundnessChecksCatchWhatTheResolverCouldNotProve() {
        SpecReach.Verb create = new SpecReach.Verb("bookCreate", "CommandVerbs", "com.x.CommandVerbs",
                "bookCreate(final String title)", "SpecBookCreate", "BookCreate");
        SpecReach.Verb info = new SpecReach.Verb("getShelf", "LookupVerbs", "com.x.LookupVerbs",
                "getShelf(final String isbn)", "SpecShelfLookup", "ShelfLookup");
        Map<String, List<SpecReach.Verb>> byName = Map.of("bookCreate", List.of(create), "getShelf", List.of(info));
        Set<String> verbs = Set.of("bookCreate", "getShelf", "shelfMove");
        List<String> ops = List.of("bookCreate(42, TITLE)", "getShelf(42)", "getShelf(42)");
        List<String> declares = List.of("expectedLabel = hex(catalog.lookupSync(TITLE))");
        List<String> problems = SpecTestComposer.soundnessProblems(ops, declares, Set.of("expectedLabel"), verbs,
                Set.of(), byName);
        String all = String.join("\n", problems);
        assertTrue(all.contains("unknown variable 'catalog'"), all);
        assertTrue(all.contains("does not exist in a spec"), all);
        assertTrue(all.contains("unknown variable 'TITLE'"), all);
        assertTrue(all.contains("'expectedLabel' is declared but never used"), all);
        assertTrue(all.contains("no overload of 'bookCreate' takes 2 arguments"), all);
        assertTrue(all.contains("passes the number 42 where getShelf takes a String id"), all);
        // a sound composition raises nothing
        List<String> good = SpecTestComposer.soundnessProblems(
                List.of("bookCreate(\"42\").titled(title)", "getShelf(\"42\").hasShelf(expectedShelf)"),
                List.of("title = \"Moby\"", "expectedShelf = hex(sha256Of(title.getBytes()))"),
                Set.of("title", "expectedShelf"), verbs, Set.of("DEFAULT_USER"), byName);
        assertTrue(good.isEmpty(), good.toString());
        // helpers: a String literal with a comma is one argument; a chained call is detected
        assertEquals(List.of("\"a,b\"", "x"), SpecTestComposer.topLevelArgs("f(\"a,b\", x).y()", 1));
        assertTrue(SpecTestComposer.hasChainedCall("getShelf(\"42\").hasShelf(s)"));
        assertFalse(SpecTestComposer.hasChainedCall("getShelf(\"42\")"));
        assertEquals(List.of("String", "byte[]..."),
                SpecTestComposer.paramTypes("bookCreate(final String t, final byte[]... c)"));
    }

    @Test
    void aReachingQueryThatAssertsNothingIsRefusedAndUnrelatedConstantsAreNotImported() throws IOException {
        // a lookup verb must assert; a command verb may stand alone
        assertNotNull(SpecTestComposer.assertsNothing("getShelf(\"42\")", "getShelf", "spec.LookupVerbs"));
        assertTrue(SpecTestComposer.assertsNothing("getShelf(\"42\")", "getShelf", "spec.LookupVerbs")
                .contains("asserts nothing"));
        assertNull(SpecTestComposer.assertsNothing("getShelf(\"42\").hasShelf(s)", "getShelf", "spec.LookupVerbs"));
        assertNull(SpecTestComposer.assertsNothing("checkoutBook()", "checkoutBook", "spec.CommandVerbs"));
        assertNull(SpecTestComposer.assertsNothing("shelfMove(\"42\").toShelf(s)", "shelfMove", "spec.CommandVerbs"));
        // COMPACT lives only in an unrelated enum: not imported, reported as unknown
        Files.writeString(dir.resolve("Layout.java"), "package example.spec.infrastructure.display;\n"
                + "public class Layout { public enum Mode { COMPACT } }\n");
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite, "withConstant",
                List.of("checkoutBook().byMember(COMPACT)", "getShelf(\"42\").hasShelf(\"x\")"),
                "LoanLedger#stampDueDate");
        assertFalse(c.ok());
        assertTrue(String.join(" ", c.problems()).contains("unknown variable 'COMPACT'"), c.problems().toString());
        assertFalse(c.imports().stream().anyMatch(i -> i.contains("Mode.COMPACT")), c.imports().toString());
    }

    // an invented assertion method on the op class is refused WITH the class's real methods
    @Test
    void anUnknownChainedMethodListsTheOpClassesChainableMethods() throws IOException {
        CodeGraph g = graph();
        String suite = Files.readString(dir.resolve("LoanSuite.java"));
        SpecTestComposer.Composition c = SpecTestComposer.compose(g, ClasspathIndex.system(), suite, "invented",
                List.of("checkoutBook().byMember(DEFAULT_USER)", "getShelf(\"42\").assertShelfEquals(\"x\")"),
                "LoanLedger#stampDueDate");
        assertFalse(c.ok());
        String all = String.join(" | ", c.problems());
        assertTrue(all.contains("has no method 'assertShelfEquals'"), all);
        assertTrue(all.contains("chainable methods"), all);
        assertTrue(all.contains("hasShelf("), all);
        assertTrue(SpecTestComposer.methodsOf(g, "SpecShelfLookup").get(0).startsWith("hasShelf("),
                SpecTestComposer.methodsOf(g, "SpecShelfLookup").toString());
        assertTrue(SpecTestComposer.methodsOf(g, "NoSuchClass").isEmpty());
    }
}
