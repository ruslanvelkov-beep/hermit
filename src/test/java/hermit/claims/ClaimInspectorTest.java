package hermit.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.evidence.Evidence;
import hermit.evidence.RecordedExecutionReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaimInspectorTest {

    @TempDir
    Path dir;
    Path gate;
    Path gateTest;
    Path reader;
    final List<String> recorded = new ArrayList<>();
    final List<String> scenarios = new ArrayList<>();

    @BeforeEach
    void workbench() throws Exception {
        Path src = Files.createDirectories(dir.resolve("svc/src/main/java/app"));
        gate = Files.writeString(src.resolve("Gate.java"), """
                package app;
                public class Gate {
                    boolean sameValue(Object a, Object b) { return a.equals(b); }
                    void handle() { check(); }
                    void check() {}
                    void unrelated() {}
                }
                """);
        Path test = Files.createDirectories(dir.resolve("svc/src/test/java/app"));
        gateTest = Files.writeString(test.resolve("GateTest.java"),
                "package app;\nclass GateTest {\n  void reaches() {}\n  void written() {}\n}\n");
        Files.writeString(dir.resolve("dump.txt"), """
                [seq 10 t1] Gate.handle(0 args) this=Gate#1 (Gate.java:4)
                [seq 11 t1]   line 4 (Gate.java:4)
                [seq 12 t1]   Gate.check(0 args) this=Gate#1 (Gate.java:5)
                [seq 13 t1]     condition: 1 == 1 -> true (Gate.java:5)
                [seq 14 t1]     return null [Gate.check]
                [seq 15 t1] return null [Gate.handle]
                """);
        reader = dir.resolve("reader");
        Files.writeString(reader, "#!/bin/sh\ncat \"" + dir.resolve("dump.txt") + "\"\n");
        Files.setPosixFilePermissions(reader, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    ClaimInspector.Located at(String spec, Path file, int line) {
        return new ClaimInspector.Located(spec, file, line);
    }

    ClaimInspector.Code code(List<String> tests) {
        return new ClaimInspector.Code() {
            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                return switch (ClaimInspector.normalize(spec)) {
                    case "Gate#sameValue", "sameValue" -> List.of(at("Gate#sameValue", gate, 3));
                    case "Gate#handle" -> List.of(at("Gate#handle", gate, 4));
                    case "Gate#check", "check" -> List.of(at("Gate#check", gate, 5));
                    case "Gate#unrelated" -> List.of(at("Gate#unrelated", gate, 6));
                    case "GateTest#reaches" -> List.of(at("GateTest#reaches", gateTest, 3));
                    default -> List.of();
                };
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                return from.equals("Gate#handle")
                        ? new ClaimInspector.Reach(true, List.of("Gate#handle", "Gate#check"), "resolved")
                        : new ClaimInspector.Reach(false, List.of(), "no-path");
            }

            @Override
            public List<String> tests(String target) {
                return tests;
            }

            @Override
            public Path root() {
                return dir;
            }
        };
    }

    ClaimInspector.Runtime runtime() {
        return new ClaimInspector.Runtime() {
            @Override
            public String record(ClaimInspector.Located test, List<ClaimInspector.Located> targets) {
                recorded.add(test.spec());
                return dir.toString();
            }

            @Override
            public RecordedExecutionReader query(String recordingDir) {
                return new RecordedExecutionReader(reader, Path.of(recordingDir));
            }
        };
    }

    /** A scripted model: hypotheses and judgements by claim. */
    static Function<String, String> model(Map<String, String> hypotheses, Map<String, String> staticVerdicts,
            List<String> runtimeVerdicts) {
        return prompt -> {
            if (prompt.startsWith("Split the text")) {
                return "{\"claims\": [\"Gate.handle calls Gate.check at runtime\", "
                        + "\"sameValue compares a and b with equals\", \"short\"]}";
            }
            String claim = prompt.contains("CLAIM: ")
                    ? prompt.substring(prompt.indexOf("CLAIM: ") + 7).split("\n")[0] : "";
            if (prompt.startsWith("Translate ONE claim")) {
                return hypotheses.getOrDefault(claim, "{\"kind\": \"structure\", \"targets\": []}");
            }
            if (prompt.startsWith("You check ONE claim against a runtime recording")) {
                return runtimeVerdicts.isEmpty() ? "{\"status\": \"insufficient\"}" : runtimeVerdicts.remove(0);
            }
            return staticVerdicts.getOrDefault(claim, "{\"status\": \"insufficient\", \"summary\": \"not shown\"}");
        };
    }

    @Test
    void aClaimOnCodeThatDoesNotExistIsRefutedAtOnce() {
        ClaimInspector inspector = new ClaimInspector(model(Map.of(), Map.of(), new ArrayList<>()),
                null, code(List.of()), null, null, 4);
        Claim c = inspector.map(new Claim("C1", "MissingType#foo returns null when empty", Claim.Origin.ANSWER, 1));
        assertEquals(Claim.Status.REFUTED, c.status);
        assertEquals(Evidence.Kind.STATIC, c.decidingEvidence().kind);
        assertTrue(c.decidingEvidence().summary.contains("MissingType"), c.decidingEvidence().summary);
    }

    @Test
    void aClaimedCallPathThatDoesNotExistIsRefutedByTheCallGraph() {
        String claim = "Gate.unrelated calls Gate.check";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"structure\", \"entry\": \"Gate#unrelated\", "
                + "\"targets\": [\"Gate#check\"], \"path\": [\"Gate#unrelated\", \"Gate#check\"]}"),
                Map.of(), new ArrayList<>()), null, code(List.of()), null, null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.REFUTED, c.status);
        assertTrue(c.decidingEvidence().summary.contains("no call path from Gate#unrelated to Gate#check"),
                c.decidingEvidence().summary);
    }

    @Test
    void aStructuralClaimIsDecidedOnTheSourceOfItsTargets() {
        String claim = "sameValue compares a and b with equals";
        ClaimInspector inspector = new ClaimInspector(model(
                Map.of(claim, "{\"kind\": \"structure\", \"targets\": [\"Gate#sameValue\"]}"),
                Map.of(claim, "{\"status\": \"supported\", \"evidence\": \"Gate.java:3\", "
                        + "\"summary\": \"returns a.equals(b)\"}"),
                new ArrayList<>()), null, code(List.of()), null, null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.SUPPORTED, c.status);
        assertEquals(List.of("Gate.java:3"), c.decidingEvidence().anchors);
        assertTrue(c.decidingEvidence().summary.contains("returns a.equals(b)"));
    }

    @Test
    void aBehaviouralClaimIsDecidedOnARecordingOfATestThatReachesIt() {
        String claim = "Gate.handle calls Gate.check at runtime";
        List<String> runtimeVerdicts = new ArrayList<>(List.of(
                "{\"status\": \"supported\", \"condition_met\": true, \"evidence\": \"seq 12\", "
                        + "\"summary\": \"check entered from handle\"}"));
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"entry\": \"Gate#handle\", "
                + "\"targets\": [\"Gate#check\"], \"path\": [\"Gate#handle\", \"Gate#check\"], "
                + "\"observable\": \"check is entered\"}"),
                Map.of(), runtimeVerdicts), null, code(List.of("GateTest#reaches")), runtime(), null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.SUPPORTED, c.status);
        Evidence p = c.decidingEvidence();
        assertEquals(Evidence.Kind.RUNTIME, p.kind);
        assertEquals("GateTest#reaches", p.test);
        assertTrue(p.events.contains("Gate.check()") && p.events.contains("condition: 1 == 1 -> true"), p.events);
        assertEquals(List.of("GateTest#reaches"), recorded);
    }

    @Test
    void whenTheRecordedTestMissesTheConditionAFocusedTestIsWrittenAndRecorded() {
        String claim = "Gate.handle calls Gate.check at runtime";
        List<String> runtimeVerdicts = new ArrayList<>(List.of(
                "{\"status\": \"insufficient\", \"condition_met\": false, \"missing\": \"a full cache first\"}",
                "{\"status\": \"contradicted\", \"condition_met\": true, "
                        + "\"summary\": \"check not entered with a full cache\"}"));
        ClaimInspector.TestWriter writer = (cl, nearest, scenario) -> {
            scenarios.add(scenario);
            return at("GateTest#written", gateTest, 4);
        };
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"entry\": \"Gate#handle\", "
                + "\"targets\": [\"Gate#check\"], \"path\": [\"Gate#handle\", \"Gate#check\"], "
                + "\"condition\": \"the cache is full\"}"),
                Map.of(), runtimeVerdicts), null, code(List.of("GateTest#reaches")), runtime(), writer, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.REFUTED, c.status);
        assertEquals("GateTest#written", c.decidingEvidence().test);
        assertEquals(List.of("GateTest#reaches", "GateTest#written"), recorded);
        assertTrue(scenarios.get(0).contains("a full cache first"), scenarios.toString());
    }

    @Test
    void aClaimAboutSomethingOutsideTheRepositoryStaysUndecidedWithItsReason() {
        String claim = "standard clients do not retry a failed create";
        ClaimInspector inspector = new ClaimInspector(model(
                Map.of(claim, "{\"kind\": \"outside\", \"targets\": [\"Gate#handle\"]}"),
                Map.of(), new ArrayList<>()), null, code(List.of()), null, null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.ANSWER, 2));
        assertEquals(Claim.Status.UNDECIDED, c.status);
        assertTrue(c.undecidedReason.contains("outside this repository"), c.undecidedReason);
        assertNull(c.decidingEvidence());
    }

    @Test
    void recordingsAreCachedPerTestAndBoundedByTheBudget() {
        List<String> verdicts = new ArrayList<>(List.of("{\"status\": \"supported\"}", "{\"status\": \"supported\"}"));
        String claim = "Gate.handle calls Gate.check at runtime";
        Map<String, String> hyp = Map.of(claim, "{\"kind\": \"behaviour\", \"targets\": [\"Gate#check\"]}");
        ClaimInspector inspector = new ClaimInspector(model(hyp, Map.of(), verdicts),
                null, code(List.of("GateTest#reaches")), runtime(), null, 1);
        inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        inspector.map(new Claim("C2", claim, Claim.Origin.ANSWER, 1));
        assertEquals(1, recorded.size(), "the same test is recorded once");
        assertEquals(1, inspector.recordingsUsed());
    }

    @Test
    void extractionAddsNewAtomicClaimsOnly() {
        ClaimInspector inspector = new ClaimInspector(model(Map.of(), Map.of(), new ArrayList<>()),
                null, code(List.of()), null, null, 4);
        List<Claim> ledger = new ArrayList<>();
        List<Claim> added = inspector.extract(ledger, "some answer text", Claim.Origin.ANSWER, 3, 20);
        assertEquals(2, added.size(), "the too-short one is dropped");
        assertEquals(Claim.Origin.ANSWER, ledger.get(0).origin);
        assertTrue(inspector.extract(ledger, "again", Claim.Origin.ANSWER, 4, 20).isEmpty(),
                "restated claims are not added twice");
        assertFalse(ledger.isEmpty());
        assertNotNull(ClaimLedger.match(ledger, "Gate.handle calls Gate.check at runtime"));
    }

    @Test
    void specsAreNormalizedToTypeHashMethod() {
        assertEquals("Gate#check", ClaimInspector.normalize("app.Gate.check()"));
        assertEquals("Gate#check", ClaimInspector.normalize("Gate#check"));
        assertEquals("Gate#check", ClaimInspector.normalize("com.x.Gate#check"));
        assertEquals("sameValue", ClaimInspector.normalize("sameValue()"));
    }

    @Test
    void onlyAPathOverResolvedCallsShowsACall() {
        assertTrue(ClaimInspector.certain(new ClaimInspector.Reach(true, List.of("A#a", "B#b"), "resolved")));
        assertFalse(ClaimInspector.certain(new ClaimInspector.Reach(true,
                List.of("A#a", "B#b [one of 45 dispatch candidates, name-only]", "C#c"), "resolved")));
        assertFalse(ClaimInspector.certain(new ClaimInspector.Reach(true, List.of("A#a", "B#b"), "low-confidence")));
        assertFalse(ClaimInspector.certain(new ClaimInspector.Reach(false, List.of(), "no-path")));
    }

    @Test
    void theTargetsOwnTestClassIsRecordedBeforeTestsTheGraphOverApproximates() throws Exception {
        Path far = Files.createDirectories(dir.resolve("other/src/test/java/x"));
        Path farTest = Files.writeString(far.resolve("PoolTest.java"), "package x;\nclass PoolTest {\n  void drains() {}\n}\n");
        ClaimInspector.Code base = code(List.of());
        ClaimInspector.Code code = new ClaimInspector.Code() {
            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                return spec.equals("PoolTest#drains") ? List.of(at("PoolTest#drains", farTest, 3)) : base.locate(spec);
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                return base.reach(from, to);
            }

            @Override
            public List<String> tests(String target) {
                return List.of("PoolTest#drains", "Missing#gone", "GateTest#reaches");
            }

            @Override
            public Path root() {
                return dir;
            }
        };
        ClaimInspector inspector = new ClaimInspector(model(Map.of(), Map.of(), new ArrayList<>()),
                null, code, null, null, 1);
        List<ClaimInspector.Located> ranked = inspector.rankTests(code.tests("Gate#check"), at("Gate#check", gate, 5));
        assertEquals(List.of("GateTest#reaches"), ranked.stream().map(ClaimInspector.Located::spec).toList(),
                "another module's test reaches the target only through a name-matched call");
    }

    @Test
    void namesInProseAreLookedUp() {
        assertEquals(Set.of("findNearest", "ShelfReturner", "Gate#check"),
                ClaimInspector.candidateNames("findNearest finds it, ShelfReturner shelves it and Gate.check() runs"));
    }

    @Test
    void aMethodTheTranslatorPutOnTheWrongTypeIsResolvedToItsDeclaration() {
        String claim = "check is entered when handle runs";
        List<String> runtimeVerdicts = new ArrayList<>();
        ClaimInspector inspector = new ClaimInspector(model(
                Map.of(claim, "{\"kind\": \"structure\", \"entry\": \"Gatekeeper#handle\", \"targets\": [\"Checker#check\", \"Nowhere#nothing\"]}"),
                Map.of(claim, "{\"status\": \"supported\", \"evidence\": \"Gate.java:4\", \"summary\": \"handle calls check\"}"),
                runtimeVerdicts), null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(List.of("Gate#check"), c.hypothesis.get("targets"));
        assertEquals("", c.hypothesis.get("entry"), "a name no method has is dropped, not kept as if it existed");
        assertEquals(Claim.Status.SUPPORTED, c.status);
        assertTrue(inspector.log().stream().anyMatch(l -> l.contains("Checker#check resolved to Gate#check")),
                inspector.log().toString());
    }

    @Test
    void typesNamedByWordsAreMatchedBeforeTheFirstDeclaration() {
        assertTrue(ClaimInspector.sharesWord("ShelfCatalog", "CachedShelfCatalogImpl"));
        assertFalse(ClaimInspector.sharesWord("Shelving", "CachedShelfCatalogImpl"));
    }

    @Test
    void theTypesAClaimDescribesInWordsAreOfferedToTheTranslator() {
        Set<String> types = Set.of("LoanRenewService", "LoanReturnService", "LoanRenewServiceTest",
                "BookRenewService", "ShelfCatalogImpl");
        assertEquals("LoanRenewService", ClaimInspector.relatedTypes(
                "If a loan has no due date, its borrower can renew it", types, 4).get(0));
        assertEquals("LoanRenewService", ClaimInspector.relatedTypes(
                "Renewing a loan requires its due date", types, 4).get(0));
        assertEquals("LoanReturnService", ClaimInspector.relatedTypes(
                "A LoanReturn request requires a receipt", types, 4).get(0));
    }

    @Test
    void aMissingPathRefutesOnlyThePathTheClaimNames() {
        String claim = "check is reached from unrelated";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"structure\", \"entry\": \"GateTest#reaches\", "
                        + "\"targets\": [\"Gate#check\"], \"path\": [\"GateTest#reaches\", \"Gate#check\"]}"),
                Map.of(), new ArrayList<>()), null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertTrue(c.status != Claim.Status.REFUTED || !c.decidingEvidence().summary.contains("no call path"),
                "a test method the translator picked as the start refutes nothing");
        assertTrue(ClaimInspector.names("Gate.unrelated calls Gate.check", "Gate#unrelated"));
        assertFalse(ClaimInspector.names("check is reached", "Gate#unrelated"));
    }

    @Test
    void theJudgeIsGivenTheDeclarationItAsksFor() {
        String claim = "sameValue compares a and b with equals";
        AtomicInteger asked = new AtomicInteger();
        Function<String, String> llm = prompt -> {
            if (prompt.startsWith("Translate ONE claim")) {
                return "{\"kind\": \"structure\", \"targets\": [\"Gate#handle\"]}";
            }
            if (prompt.startsWith("Split a claim about a code base")) {
                return "{\"parts\": [\"" + claim + "\"]}";
            }
            if (asked.incrementAndGet() == 1) {
                return "{\"status\": \"insufficient\", \"next_read\": \"the body of sameValue\"}";
            }
            return prompt.contains("a.equals(b)")
                    ? "{\"status\": \"supported\", \"summary\": \"equals\"}"
                    : "{\"status\": \"insufficient\"}";
        };
        ClaimInspector inspector = new ClaimInspector(llm, null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.SUPPORTED, c.status);
        assertTrue(c.decidingEvidence().anchors.contains("Gate.java:3"), c.decidingEvidence().anchors.toString());
        assertTrue(inspector.log().stream().anyMatch(l -> l.contains("read Gate#sameValue")),
                inspector.log().toString());
    }

    @Test
    void aClaimWhoseEntryIsItsTargetIsNotAPathClaim() {
        String claim = "Gate.check checks nothing";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"structure\", \"entry\": \"Gate#check\", "
                        + "\"targets\": [\"Gate#check\"], \"path\": [\"Gate#check\"]}"), Map.of(), new ArrayList<>()),
                null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertTrue(c.decidingEvidence() == null || !c.decidingEvidence().summary.contains("no call path"),
                String.valueOf(c.status));
    }

    @Test
    void theMethodsOfATypeTheJudgeAsksForAreRankedByTheClaimsWords() {
        assertEquals(List.of("ThermostatPanel#validateTarget", "ThermostatPanel#apply"), ClaimInspector.relevantMethods(
                List.of("ThermostatPanel#renderLabel", "ThermostatPanel#apply", "ThermostatPanel#validateTarget"),
                "the panel validates the target setting before apply", 2));
    }

    @Test
    void stemsAndTestCode() {
        assertEquals(ClaimInspector.stem("deleting"), ClaimInspector.stem("Delete"));
        assertEquals(ClaimInspector.stem("approving"), ClaimInspector.stem("Approve"));
        assertTrue(ClaimInspector.isTestCode(Path.of("/r/service/spec-tests/src/main/java/x/ItemTransferSpec.java")));
        assertTrue(ClaimInspector.isTestCode(Path.of("/r/kit/src/testFixtures/java/x/FakeClock.java")));
        assertTrue(ClaimInspector.isTestCode(Path.of("/r/svc/src/test/java/x/A.java")));
        assertFalse(ClaimInspector.isTestCode(Path.of("/r/svc/src/main/java/x/LoanRenewService.java")));
        assertTrue(ClaimInspector.isGenerated(
                Path.of("/r/api/build/generated/source/proto/main/java/x/LoanReturnRequest.java")));
        assertFalse(ClaimInspector.isGenerated(Path.of("/r/svc/src/main/java/x/LoanReturnService.java")));
    }

    // a project's own test source sets come from its spec conventions
    @Test
    void anExtraTestSourceSetFromTheConventionsIsTestCode() throws Exception {
        Path soak = Path.of("/r/svc/src/soak/java/x/LoadRun.java");
        assertFalse(ClaimInspector.isTestCode(soak));
        Path conventions = Files.writeString(dir.resolve("conventions.properties"), "extraTestSourceSets=soak\n");
        System.setProperty("hermit.spec.conventions", conventions.toString());
        try {
            assertTrue(ClaimInspector.isTestCode(soak));
            assertFalse(ClaimInspector.isTestCode(Path.of("/r/svc/src/main/java/x/LoadRun.java")));
        } finally {
            System.clearProperty("hermit.spec.conventions");
        }
    }

    @Test
    void aCompoundClaimIsDecidedByItsParts() {
        String claim = "Gate.check is called by Gate.handle and Gate.unrelated calls Gate.check";
        Function<String, String> llm = prompt -> {
            if (prompt.startsWith("Split a claim about a code base")) {
                return "{\"parts\": [\"Gate.check is called by Gate.handle\", \"Gate.unrelated calls Gate.check\"]}";
            }
            if (prompt.startsWith("Translate ONE claim")) {
                return prompt.contains("CLAIM: Gate.unrelated")
                        ? "{\"kind\": \"structure\", \"entry\": \"Gate#unrelated\", \"targets\": [\"Gate#check\"], "
                                + "\"path\": [\"Gate#unrelated\", \"Gate#check\"]}"
                        : "{\"kind\": \"structure\", \"targets\": [\"Gate#handle\"]}";
            }
            return "{\"status\": \"supported\", \"summary\": \"handle calls check\", \"evidence\": \"Gate.java:4\"}";
        };
        ClaimInspector inspector = new ClaimInspector(llm, null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.ANSWER, 1));
        assertEquals(2, c.parts.size());
        assertEquals(Claim.Status.SUPPORTED, c.parts.get(0).status);
        assertEquals(Claim.Status.REFUTED, c.parts.get(1).status);
        assertEquals(Claim.Status.REFUTED, c.status, "one refuted part refutes the whole");
        assertTrue(c.decidingEvidence().summary.startsWith("part C1.2"), c.decidingEvidence().summary);
    }

    @Test
    void aDisjunctionIsNotSplit() {
        List<String> prompts = new ArrayList<>();
        ClaimInspector inspector = new ClaimInspector(prompt -> {
            prompts.add(prompt);
            return "{\"kind\": \"outside\"}";
        }, null, code(List.of()), null, null, 1);
        inspector.map(new Claim("C1", "renewing needs a library card or a staff pass", Claim.Origin.ANSWER, 1));
        assertTrue(prompts.stream().noneMatch(p -> p.startsWith("Split a claim about a code base")));
    }

    @Test
    void anImplementationAndItsInterfaceMethodAreNotAClaimedPath() {
        ClaimInspector inspector = new ClaimInspector(model(Map.of(), Map.of(), new ArrayList<>()),
                null, code(List.of()), null, null, 1);
        assertFalse(inspector.ownPath("check() includes the key", "Gate#check", "Other#check"));
        assertTrue(inspector.ownPath("unrelated calls check", "Gate#unrelated", "Gate#check"));
    }

    @Test
    void aReportIsReadAParagraphAtATime() {
        String report = "**Severity:** Medium\n\n" + "A".repeat(900) + " first.\n\n" + "B".repeat(900) + " second.\n\n#### Impact\n\n"
                + "C".repeat(200) + " third.";
        List<String> chunks = ClaimInspector.chunks(report, 1500);
        assertEquals(2, chunks.size(), chunks.toString());
        assertTrue(chunks.get(0).startsWith("AAA") && chunks.get(1).startsWith("BBB") && chunks.get(1).contains("CCC"));
    }

    @Test
    void theRecommendationsOfAReportAreNotItsClaims() {
        String report = "#### Description\n\n" + "A".repeat(100) + " claim.\n\n#### Remediation\n\n" + "B".repeat(100) + " advice.";
        List<String> chunks = ClaimInspector.chunks(report, 1500);
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).contains("advice"));
    }

    @Test
    void aFailedRecordingFallsBackToTheNextNearestTest() {
        String claim = "Gate.handle calls Gate.check at runtime";
        List<String> runtimeVerdicts = new ArrayList<>(List.of("{\"status\": \"supported\", \"summary\": \"entered\"}"));
        ClaimInspector.Runtime flaky = new ClaimInspector.Runtime() {
            @Override
            public String record(ClaimInspector.Located test, List<ClaimInspector.Located> targets) {
                recorded.add(test.spec());
                if (test.spec().equals("GateTest#reaches")) {
                    throw new IllegalStateException("No tests found");
                }
                return dir.toString();
            }

            @Override
            public RecordedExecutionReader query(String recordingDir) {
                return new RecordedExecutionReader(reader, Path.of(recordingDir));
            }
        };
        ClaimInspector.Code base = code(List.of());
        ClaimInspector.Code twoTests = new ClaimInspector.Code() {
            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                return spec.equals("GateTest#written") ? List.of(at("GateTest#written", gateTest, 4)) : base.locate(spec);
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                return base.reach(from, to);
            }

            @Override
            public List<String> tests(String target) {
                return List.of("GateTest#reaches", "GateTest#written");
            }

            @Override
            public Path root() {
                return dir;
            }
        };
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"targets\": [\"Gate#check\"]}"),
                Map.of(), runtimeVerdicts), null, twoTests, flaky, null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(List.of("GateTest#reaches", "GateTest#written"), recorded);
        assertEquals(Claim.Status.SUPPORTED, c.status);
        assertEquals("GateTest#written", c.decidingEvidence().test);
    }

    @Test
    void thatSomethingDoesNotHappenIsNeverShownByReadingLines() {
        String claim = "A rejected handle call does not write any audit entry";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"targets\": [\"Gate#handle\"]}"),
                Map.of(claim, "{\"status\": \"supported\", \"summary\": \"it throws before any write\"}"),
                new ArrayList<>()), null, code(List.of()), null, null, 1);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.UNDECIDED, c.status);
        assertTrue(c.undecidedReason.startsWith("a negative claim about what happens"), c.undecidedReason);

        String structural = "sameValue does not compare the label";
        ClaimInspector reader = new ClaimInspector(model(Map.of(structural,
                "{\"kind\": \"structure\", \"targets\": [\"Gate#sameValue\"]}"),
                Map.of(structural, "{\"status\": \"supported\", \"summary\": \"no label in the comparison\"}"),
                new ArrayList<>()), null, code(List.of()), null, null, 1);
        assertEquals(Claim.Status.SUPPORTED, reader.map(new Claim("C2", structural, Claim.Origin.REQUEST, 0)).status,
                "what a method's own lines do not contain is read from them");
    }

    @Test
    void aSupportTheSkepticFindsAnUnshownPhraseInDoesNotStand() {
        String claim = "Gate.check requires the permit for exporting";
        Function<String, String> llm = prompt -> {
            if (prompt.startsWith("Translate ONE claim")) {
                return "{\"kind\": \"structure\", \"targets\": [\"Gate#check\"]}";
            }
            if (prompt.startsWith("A judge decided")) {
                return "{\"unshown\": [\"for exporting — the lines check the permit for printing\"]}";
            }
            return "{\"status\": \"supported\", \"summary\": \"check verifies the key\"}";
        };
        Claim c = new ClaimInspector(llm, null, code(List.of()), null, null, 1)
                .map(new Claim("C1", claim, Claim.Origin.ANSWER, 1));
        assertEquals(Claim.Status.UNDECIDED, c.status);
        assertTrue(c.undecidedReason.contains("for exporting — the lines check the permit for printing"),
                c.undecidedReason);
    }

    @Test
    void anAbsentEffectNeedsARecordingButAPreventedOperationIsShownByItsGuard() {
        assertTrue(ClaimInspector.absence("A cancelled invoice does not send the customer any reminder"));
        assertTrue(ClaimInspector.absence("The tenant never receives the refunds"));
        assertFalse(ClaimInspector.absence("An invoice without a due date cannot be sent"));
        assertFalse(ClaimInspector.absence("The second clerk can void it"));
        assertFalse(ClaimInspector.absence("The effective due date is the draft's due date when no override is set"));
        assertTrue(ClaimInspector.absence("When a reminder is pending, no reminder is sent"));
    }

    @Test
    void aRefutationRestingOnACaseTheClaimLeavesOutIsAMissingCondition() {
        String claim = "Gate.check rejects a repeated key";
        Function<String, String> llm = prompt -> {
            if (prompt.startsWith("Translate ONE claim")) {
                return "{\"kind\": \"structure\", \"targets\": [\"Gate#check\"]}";
            }
            if (prompt.startsWith("A judge decided that the evidence below CONTRADICTS")) {
                return "{\"opposite_shown\": false, "
                        + "\"why\": \"only when a flag is off, which the claim does not mention\"}";
            }
            return "{\"status\": \"contradicted\", \"summary\": \"with the flag off it accepts\"}";
        };
        Claim c = new ClaimInspector(llm, null, code(List.of()), null, null, 1)
                .map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(Claim.Status.UNDECIDED, c.status);
        assertTrue(c.undecidedReason.contains("only when a flag is off"), c.undecidedReason);
    }

    @Test
    void aRecordingInWhichTheTargetNeverRanDecidesNothing() throws Exception {
        Files.writeString(dir.resolve("dump.txt"),
                "[seq 1 t1] Other.run(0 args) this=Other#1 (Other.java:3)\n[seq 2 t1] return null [Other.run]\n");
        String claim = "Gate.handle calls Gate.check at runtime";
        List<String> runtimeVerdicts = new ArrayList<>(List.of(
                "{\"status\": \"contradicted\", \"summary\": \"check never ran\"}"));
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"targets\": [\"Gate#check\"]}"),
                Map.of(), runtimeVerdicts), null, code(List.of("GateTest#reaches")), runtime(), null, 4);
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertTrue(c.status != Claim.Status.REFUTED, "a recording of code the claim is not about refutes nothing");
        assertTrue(inspector.log().stream().anyMatch(l -> l.contains("never entered Gate#check")),
                inspector.log().toString());
        assertEquals(1, runtimeVerdicts.size(), "the runtime judge is not even asked");
    }

    @Test
    void onlyOneTestIsWrittenPerTurnByDefault() {
        List<String> scenariosSeen = new ArrayList<>();
        ClaimInspector.TestWriter writer = (cl, nearest, scenario) -> {
            scenariosSeen.add(cl.id);
            return null;
        };
        String claim = "Gate.check is entered when nothing reaches it";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"targets\": [\"Gate#check\"]}"),
                Map.of(), new ArrayList<>()), null, code(List.of()), runtime(), writer, 8);
        inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        inspector.map(new Claim("C2", claim, Claim.Origin.ANSWER, 1));
        assertEquals(List.of("C1"), scenariosSeen, "the second claim finds the written-test budget spent");
    }

    @Test
    void aLabelBlockWithoutASentenceYieldsNoClaims() {
        String report = "A01 Some Category Label\nLocation\nsvc/src/main/java/x/Gate.java:L12-L19\n\n"
                + "Gate.check() compares the name and the size. It does not compare the date.";
        List<String> chunks = ClaimInspector.chunks(report, 1500);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith("Gate.check()"), chunks.toString());
    }

    @Test
    void aBareMethodNameMeansTheMethodOnTheRequestsOwnType() throws Exception {
        Path other = Files.writeString(dir.resolve("svc/src/main/java/app/Pool.java"),
                "package app;\npublic class Pool {\n  void check() {}\n}\n");
        ClaimInspector.Code base = code(List.of());
        ClaimInspector.Code two = new ClaimInspector.Code() {
            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                return switch (spec) {
                    case "Pool#check" -> List.of(at("Pool#check", other, 3));
                    case "check" -> List.of(at("Pool#check", other, 3), at("Gate#check", gate, 5));
                    default -> base.locate(spec);
                };
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                return base.reach(from, to);
            }

            @Override
            public List<String> tests(String target) {
                return List.of();
            }

            @Override
            public Path root() {
                return dir;
            }

            @Override
            public Set<String> typeNames() {
                return Set.of("Gate", "Pool", "GateKeeper");
            }
        };
        String claim = "In check(), a repeated key is rejected";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"structure\", \"targets\": [\"Pool#check\"]}"),
                Map.of(), new ArrayList<>()), null, two, null, null, 1);
        inspector.context("THE FINDING: GateKeeper and Gate mishandle keys; in check() a repeated key is rejected.");
        Claim c = inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0));
        assertEquals(List.of("Gate#check"), c.hypothesis.get("targets"));
        assertTrue(inspector.log().stream().anyMatch(l -> l.contains("re-anchored to the request's Gate#check")),
                inspector.log().toString());
        assertTrue(ClaimInspector.relatedTypes("rejected with SHELF_CAPACITY_EXCEEDED by the handler",
                Set.of("ShelfCapacityHandler", "ItemRejectHandler"), 2).get(0).equals("ShelfCapacityHandler"));
    }

    @Test
    void aSkepticGapMustNameASpecificPhraseNotTheWholeClaimOrAnAdverb() {
        String claim = "The invoice's due date inherits from the InvoiceDraft due date when no override date is set";
        assertFalse(ClaimInspector.specificGap(claim, "the invoice's due date inherits from the InvoiceDraft due date "
                + "when no override date is set — the evidence shows dueDate is set to draft.dueDateOrElse(fallback)"));
        assertFalse(ClaimInspector.specificGap("sameValue explicitly excludes the label",
                "explicitly — the comment says it"));
        assertTrue(ClaimInspector.specificGap("Gate.check requires the permit for exporting",
                "for exporting — the lines check it for printing"));
    }

    @Test
    void whatANamedMethodDoesNotDoIsReadFromItsOwnLines() {
        String claim = "sameValue does not compare the timestamp";
        ClaimInspector inspector = new ClaimInspector(model(Map.of(claim,
                "{\"kind\": \"behaviour\", \"targets\": [\"Gate#sameValue\"]}"),
                Map.of(claim, "{\"status\": \"supported\", \"summary\": \"no timestamp in the comparison\"}"),
                new ArrayList<>()), null, code(List.of()), null, null, 1);
        assertEquals(Claim.Status.SUPPORTED, inspector.map(new Claim("C1", claim, Claim.Origin.REQUEST, 0)).status);
    }

    @Test
    void protobufMessagesAreIndexedWithTheirDefinitions() throws Exception {
        Path proto = Files.createDirectories(dir.resolve("api/src/main/proto"));
        Files.writeString(
                proto.resolve("playlist.proto"),
                "syntax = \"proto3\";\nmessage Playlist {\n  repeated string tracks = 3;\n}\n"
        );
        Files.createDirectories(dir.resolve("api/build/generated"));
        Files.writeString(
                dir.resolve("api/build/generated/copy.proto"),
                "message Other {\n}\n"
        );
        var index = WorkbenchPorts.protoIndex(dir);
        assertEquals(Set.of("Playlist"), index.keySet());
        assertTrue(ClaimInspector.source(index.get("Playlist"), 80).contains("repeated string tracks = 3;"));
    }

    @Test
    void aNestedTestClassIsNamedOuterDollarInner() throws Exception {
        Path f = Files.writeString(
                dir.resolve("PlaylistTest.java"),
                "package a.b;\nclass PlaylistTest {\n  class ShuffleTests {\n  }\n}\n"
        );
        assertEquals("a.b.PlaylistTest$ShuffleTests", WorkbenchPorts.fqnOf(at("ShuffleTests#keepsAll", f, 3)));
        assertEquals("a.b.PlaylistTest", WorkbenchPorts.fqnOf(at("PlaylistTest#x", f, 2)));
    }
}
