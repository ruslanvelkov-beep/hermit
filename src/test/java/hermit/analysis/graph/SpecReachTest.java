package hermit.analysis.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Spec reachability over a miniature of a request-dispatching service: dispatcher switch + handlers record +
 *  spec op classes with type() + verb factories, named as the default conventions name them. */
public class SpecReachTest {

    @TempDir
    public Path dir;

    static final String FIXTURE_PKG = "package fx;\n";

    @BeforeEach
    public void fixture() throws IOException {
        write("OperationKind.java", "package fx;\npublic enum OperationKind { Checkout, Renewal, ShelfLookup }\n");
        write("CommandHandlers.java", FIXTURE_PKG
                + "public record CommandHandlers(CheckoutHandler checkoutHandler, RenewalHandler renewalHandler) {}\n");
        write("LookupHandlers.java", FIXTURE_PKG
                + "public record LookupHandlers(ShelfLookupHandler shelfLookupHandler) {}\n");
        write("CommandDispatcher.java", FIXTURE_PKG + """
                public class CommandDispatcher {
                    CommandHandlers handlers;
                    Object getHandler(Body b) {
                        return switch (b.kind()) {
                            case CHECKOUT -> handlers.checkoutHandler();
                            case RENEWAL -> handlers.renewalHandler();
                            default -> throw new UnsupportedOperationException();
                        };
                    }
                }
                """);
        write("LookupDispatcher.java", FIXTURE_PKG + """
                public class LookupDispatcher {
                    LookupHandlers handlers;
                    Object getHandler(Body b) {
                        return switch (b.kind()) {
                            case SHELF_LOOKUP -> handlers.shelfLookupHandler();
                            default -> throw new UnsupportedOperationException();
                        };
                    }
                }
                """);
        write("Body.java", FIXTURE_PKG + "public class Body { Object kind() { return null; } }\n");
        write("CheckoutHandler.java", FIXTURE_PKG + """
                public class CheckoutHandler {
                    public void handle(Object ctx) { checkLimit(); new LoanLedger().open(); }
                    private void checkLimit() { }
                }
                """);
        write("RenewalHandler.java", FIXTURE_PKG
                + "public class RenewalHandler { public void handle(Object c) { } }\n");
        write("LoanLedger.java", FIXTURE_PKG + """
                public class LoanLedger {
                    void open() { stampDueDate(); }
                    void stampDueDate() { }
                }
                """);
        write("ShelfLookupHandler.java", FIXTURE_PKG + """
                public class ShelfLookupHandler {
                    public Object respond(Object c) { return shelfFor(); }
                    private Object shelfFor() { return null; }
                }
                """);
        write("Orphan.java", FIXTURE_PKG + "public class Orphan { void work() { } }\n");
        write("Main.java", FIXTURE_PKG + "public class Main { void run() { new Orphan().work(); } }\n");
        // spec side
        write("SpecCheckout.java", "package spec;\nimport fx.OperationKind;\n"
                + "public class SpecCheckout {\n    public OperationKind type() { return OperationKind.Checkout; }\n"
                + "    public SpecCheckout byMember(String m) { return this; }\n}\n");
        write("SpecShelfLookup.java", "package spec;\nimport static fx.OperationKind.ShelfLookup;\n"
                + "import fx.OperationKind;\n"
                + "public class SpecShelfLookup {\n    public OperationKind type() { return ShelfLookup; }\n"
                + "    public SpecShelfLookup hasShelf(String s) { return this; }\n}\n");
        write("CommandVerbs.java", "package spec;\npublic class CommandVerbs {\n"
                + "    public static SpecCheckout checkoutBook() { return new SpecCheckout(); }\n"
                + "    public static SpecCheckout checkoutCopy() { return new SpecCheckout(); }\n"
                + "    public static Object pauseFor(long ms) { return null; }\n}\n");
        write("LookupVerbs.java", "package spec;\npublic class LookupVerbs {\n"
                + "    public static SpecShelfLookup getShelf(final String isbn) {\n"
                + "        return new SpecShelfLookup();\n    }\n}\n");
    }

    private void write(String name, String src) throws IOException {
        Files.writeString(dir.resolve(name), src);
    }

    private CodeGraph graph() throws IOException {
        return CodeGraph.build(List.of(dir), ClasspathIndex.system());
    }

    @Test
    void commandPathResolvesToHandlerKindAndVerbs() throws IOException {
        SpecReach.Result r = SpecReach.of(graph(), "LoanLedger#stampDueDate", 10);
        assertTrue(r.specReachable(), r.render());
        assertEquals(1, r.routes().size(), r.render());
        SpecReach.Route route = r.routes().get(0);
        assertEquals("CheckoutHandler#handle", route.handlerMethod());
        assertEquals("CHECKOUT", route.kind());
        assertEquals(List.of("LoanLedger#stampDueDate", "LoanLedger#open", "CheckoutHandler#handle"), route.path());
        List<String> names = route.verbs().stream().map(SpecReach.Verb::name).toList();
        assertTrue(names.containsAll(List.of("checkoutBook", "checkoutCopy")), names.toString());
        assertFalse(names.contains("pauseFor"), "a utility returning Object is not a verb");
        SpecReach.Verb v = route.verbs().get(0);
        assertEquals("CommandVerbs", v.owner());
        assertEquals("spec.CommandVerbs", v.ownerFqn());
        assertEquals("SpecCheckout", v.opClass());
        assertEquals("Checkout", v.functionality());
        assertEquals("()", v.signature());
    }

    @Test
    void lookupPathResolvesThroughLookupDispatcher() throws IOException {
        SpecReach.Result r = SpecReach.of(graph(), "ShelfLookupHandler#shelfFor", 10);
        assertTrue(r.specReachable(), r.render());
        SpecReach.Route route = r.routes().get(0);
        assertEquals("ShelfLookupHandler#respond", route.handlerMethod());
        assertEquals("SHELF_LOOKUP", route.kind());   // kind SHELF_LOOKUP ≡ functionality ShelfLookup
        assertEquals("getShelf", route.verbs().get(0).name());
        assertEquals("(final String isbn)", route.verbs().get(0).signature());
    }

    @Test
    void noHandlerReportsEntryPointsHonestly() throws IOException {
        SpecReach.Result r = SpecReach.of(graph(), "Orphan#work", 10);
        assertFalse(r.specReachable());
        assertTrue(r.routes().isEmpty());
        assertEquals(List.of("Main#run"), r.entryPoints());
        assertTrue(r.render().startsWith("NO HANDLER"), r.render());
    }

    @Test
    void unknownTargetIsAnExplicitNote() throws IOException {
        SpecReach.Result r = SpecReach.of(graph(), "Nope#nothing", 10);
        assertFalse(r.specReachable());
        assertTrue(r.notes().get(0).startsWith("target not found"));
    }

    @Test
    void catalogueFiltersByFunctionalityOpOrName() throws IOException {
        CodeGraph g = graph();
        assertEquals(3, SpecReach.verbs(g, null).size());
        assertEquals(List.of("getShelf"),
                SpecReach.verbs(g, "shelflook").stream().map(SpecReach.Verb::name).toList());
        assertEquals(2, SpecReach.verbs(g, "SpecCheckout").size());
        assertEquals(1, SpecReach.verbs(g, "checkoutBook").size());
        assertEquals(Set.of("CHECKOUT"), SpecReach.dispatchTable(g).get("CheckoutHandler"));
        assertEquals(Set.of("RENEWAL"), SpecReach.dispatchTable(g).get("RenewalHandler"));
    }

    @Test
    void entryPointsAreSplitBySourceSet() throws IOException {
        // spec infrastructure in a spec-test module's src/main was reported as a PRODUCTION caller
        Path prod = dir.resolve("app/src/main/java");
        Path specs = dir.resolve("spec-tests/src/main/java");
        Path unit = dir.resolve("app/src/test/java");
        Files.createDirectories(prod);
        Files.createDirectories(specs);
        Files.createDirectories(unit);
        Files.writeString(prod.resolve("Deep.java"), """
                class Deep {
                    void leaf() { }
                }
                """);
        Files.writeString(prod.resolve("Runner.java"), """
                class Runner {
                    void drive() { new Deep().leaf(); }
                }
                """);
        Files.writeString(specs.resolve("SpecVerbs.java"), """
                class SpecVerbs {
                    static void usingCallback() { new Deep().leaf(); }
                }
                """);
        Files.writeString(unit.resolve("DeepUnitTest.java"), """
                class DeepUnitTest {
                    void covers() { new Deep().leaf(); }
                }
                """);
        SpecReach.Result r = SpecReach.of(CodeGraph.build(List.of(dir), ClasspathIndex.system()), "Deep#leaf", 8);
        assertTrue(r.entryPoints().contains("Runner#drive"), "production: " + r.entryPoints());
        assertFalse(r.entryPoints().contains("SpecVerbs#usingCallback"), "spec infra is not production: " + r.entryPoints());
        assertTrue(r.testSupportEntryPoints().contains("SpecVerbs#usingCallback"), r.testSupportEntryPoints().toString());
        assertTrue(r.testSupportEntryPoints().contains("DeepUnitTest#covers"), r.testSupportEntryPoints().toString());
        String rendered = r.render();
        assertTrue(rendered.contains("test-support entry points"), rendered);
        assertFalse(rendered.contains("production callers with no callers): SpecVerbs"), rendered);
    }

    @Test
    void aProjectsConventionsComeFromItsPropertiesFile() throws IOException {
        Path file = dir.resolve("conventions.properties");
        Files.writeString(file, "dispatchers=ClimateDispatcher, ReportDispatcher\nfunctionalityType=ClimateKind\n"
                + "testAnnotation=ClimateSpec\nhelper.digest=static example.util.Digests.digest\n"
                + "specModuleMarker=/acceptance/\nextraTestSourceSets=soak, perf\nextraHelperHeads=randomName\n"
                + "generatedPackageMarker=.gen.\nidPrefix=room-\n");
        SpecConventions c = SpecConventions.read(file);
        assertEquals(List.of("ClimateDispatcher", "ReportDispatcher"), c.dispatchers());
        assertEquals("ClimateKind", c.functionalityType());
        assertEquals("ClimateSpec", c.testAnnotation());
        assertEquals(Map.of("digest", "static example.util.Digests.digest"), c.helperImports());
        assertEquals("/acceptance/", c.specModuleMarker());
        assertEquals(List.of("soak", "perf"), c.extraTestSourceSets());
        assertEquals(List.of("randomName"), c.extraHelperHeads());
        assertEquals(".gen.", c.generatedPackageMarker());
        assertEquals("room-", c.idPrefix());
        // a key the file leaves out keeps its default, and without a file the run has the defaults
        assertEquals(SpecConventions.DEFAULTS.handlerRecords(), c.handlerRecords());
        assertEquals(SpecConventions.DEFAULTS, SpecConventions.load());
    }
}
