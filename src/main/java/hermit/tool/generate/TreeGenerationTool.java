package hermit.tool.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.analysis.edit.TreeEditor;
import hermit.analysis.emu.Interpreter;
import hermit.analysis.graph.CodeGraph;
import hermit.analysis.graph.SpecConventions;
import hermit.analysis.lex.TokenType;
import hermit.analysis.sym.ClasspathIndex;
import hermit.analysis.sym.SourceIndex;
import hermit.analysis.sym.TypeHierarchy;
import hermit.analysis.tree.JavaParser;
import hermit.analysis.tree.Node;
import hermit.analysis.tree.NodeType;
import hermit.analysis.tree.ParseTree;
import hermit.model.ChatClient;
import hermit.model.HelperChain;
import hermit.model.HelperEndpoint;
import hermit.plan.PlanParser;
import hermit.review.HostedCodeReview;
import hermit.tool.analysis.TreeEditTool;
import hermit.tool.api.Tool;
import hermit.tool.graph.CodeGraphTool;
import hermit.tool.helper.HelperModelTool;
import hermit.tool.process.BashTool;
import hermit.workspace.GradleModules;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * INCREMENTAL code generation over the parse tree - the multi-stage loop from the module spec:
 * <ol>
 *   <li>the task is turned into a list of EDIT ITEMS (op + anchor + code), either supplied directly or
 *       authored by the LLM ("text instructions → rough operations → tree commands");</li>
 *   <li>each item is applied and COMMITTED individually (parse → splice → whole-program revalidation);
 *       a rejected item goes back to the author with the positioned problems until it commits;</li>
 *   <li>when the items are exhausted the file is compiled (gradle module when available, plain javac
 *       otherwise); on failure the file is RESTORED and the whole process retries with the compiler
 *       output in the author's context;</li>
 *   <li>on success the {@link CodeGraph} re-indexes the file and, when asked, verifies the TRANSITIVE
 *       call chain reaches the desired code;</li>
 *   <li>critics review the generated method's rendered text; an optional test run reports red/green
 *       (TDD-aware via {@code expectTestOutcome}).</li>
 * </ol>
 * Every generated call thus has valid names/arity BEFORE compilation is ever attempted. Attempt budgets
 * are configurable ({@code -Dhermit.treegen.itemAttempts}, {@code -Dhermit.treegen.processRetries};
 * {@code 0} = unlimited, per the no-hard-cap design).
 */
public class TreeGenerationTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Function<String, String> llm;

    /** Methods this process generated successfully (file + spec) - a second generation of one is refused. */
    static final Set<String> GENERATED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    /** The production method under test, shown to the author and the repair author (per run). */
    private String codeUnderTestSection = "";
    /** The same text, readable by the (static) form parser for derivation stubs. */
    private static volatile String codeUnderTestForDerivation = null;

    public TreeGenerationTool() {
        this.llm = TreeGenerationTool::defaultLlm;
    }

    /** Test/embedding constructor: a scripted author/critic in place of the live model. */
    public TreeGenerationTool(Function<String, String> llm) {
        this.llm = llm;
    }

    @Override
    public String getDocumentation() {
        return "TreeCodegen: INCREMENTAL code generation — turns a task into a list of parse-tree edits, "
                + "commits each one individually with whole-program validation (hallucinated names/arities "
                + "are rejected and re-authored with the exact problems), then compiles, verifies the "
                + "transitive call chain reaches the desired code, runs critics on the generated method, and "
                + "optionally runs a test. Parameters: {\"task\": \"<what to generate>\", \"file\": \"<file "
                + "to edit>\", \"method\": \"<Type#method being generated/edited>\", \"items\": [<optional "
                + "pre-planned edits: {op, code, method, anchor, type}>], \"targetReach\": \"<Type#method the "
                + "generated code must transitively invoke>\" (optional), \"module\": \":app\" (optional, for "
                + "gradle compile), \"testFilter\": \"<--tests pattern>\" (optional), \"expectTestOutcome\": "
                + "\"red\"|\"green\" (optional, TDD — an EMULATOR predicts the outcome before any real test "
                + "run and contradictions are repaired in place), \"emulateSpec\": \"<Type#testMethod to "
                + "emulate>\" (optional; derived from method/testFilter when omitted), \"projectDir\", "
                + "\"classesDir\"}.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String task = str(parameters.get("task"));
        String fileParam = str(parameters.get("file"));
        String projectDir = firstNonBlank(str(parameters.get("projectDir")),
                System.getProperty("hermit.projectDir"));
        if (task == null || fileParam == null || projectDir == null) {
            return Map.of("status", "Error", "message", "task, file and projectDir are required.");
        }
        // with the red test landed, run and recorded, the planner re-selected the SAME generation
        // (its repeat guard missed a differently written gradle filter). Regenerating an already-landed test can only
        // degrade it: refuse, and point at the step that is actually missing.
        String alreadySpec = qualifySpec(str(parameters.get("method")), fileParam);
        if (fileParam != null && alreadySpec != null
                && GENERATED_THIS_SESSION.contains(fileParam + "::" + alreadySpec)) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("status", "Success");
            done.put("alreadyGenerated", true);
            done.put("message", alreadySpec + " was ALREADY generated by this tool earlier in this session and is in the "
                    + "file. Do not generate it again — that can only degrade a test that already compiles and runs RED. "
                    + "The next steps are the ones still missing: run it if not yet run, record it, then COMMIT it "
                    + "(git add <file> && git commit -s -m '...').");
            return done;
        }
        String misplacedTest = misplacedTestIssue(task, fileParam, str(parameters.get("templateMethod")),
                str(parameters.get("expectTestOutcome")));
        if (misplacedTest != null) {
            return Map.of("status", "Error", "message", misplacedTest);
        }
        if (looksLikeSpecTestTask(task, fileParam)) {
            return Map.of("status", "Error", "message", "this is a spec test (@SpecTest in a spec "
                    + "suite): use SpecTestComposer {\"action\": \"compose\", \"suiteFile\": \"<suite>\", "
                    + "\"testName\": ..., \"ops\": [...], \"target\": \"<Type#method the spec must reach>\"} — it "
                    + "resolves the verb imports, validates the member in the whole program and REJECTS a vacuous "
                    + "spec. Nothing was edited.");
        }
        if (looksLikeDocEcho(task)) {
            return Map.of("status", "Error", "message", "task '" + task + "' describes the TOOL, not the change: "
                    + "state the behaviour to generate, naming the REAL classes and methods (shape: \"a JUnit test "
                    + "that stubs <collaborator method> to throw and asserts <observable> equals <value>\" or \"in the "
                    + "catch block replace <old expression> with <new expression>\" — fill every <…> in). Nothing was edited.");
        }
        if (looksLikePlaceholderTask(task)) {
            // the coaching's own example ("stubs X to throw and asserts Y") came back verbatim as the task
            return Map.of("status", "Error", "message", "task '" + task + "' still contains PLACEHOLDERS (X/Y or <…>): "
                    + "replace them with the real class#method names and the value the assertion expects. Nothing was edited.");
        }
        Path project = Path.of(projectDir).toAbsolutePath().normalize();
        Path file = Path.of(fileParam);
        if (!file.isAbsolute()) {
            file = project.resolve(fileParam);
        }
        file = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return Map.of("status", "Error", "message", "file not found: " + file);
        }
        String classesDir = firstNonBlank(str(parameters.get("classesDir")),
                System.getProperty("hermit.classesDir"));
        // the planner copied `targetReach: "<Type#method>"` from the tool's own description two
        // plans running; had it reached the tool, every round would have hunted a method that cannot exist. A
        // placeholder in an OPTIONAL parameter is ignored (noted in the transcript); in `method` it is an error.
        List<String> placeholderNotes = new ArrayList<>();
        if (isPlaceholderValue(str(parameters.get("method")))) {
            return Map.of("status", "Error", "message", "method '" + str(parameters.get("method")) + "' is a PLACEHOLDER "
                    + "copied from the tool description — give the NEW test method a real name (TestClass#newTestName). "
                    + "Nothing was edited.");
        }
        String methodSpec = qualifySpec(str(parameters.get("method")), fileParam);
        String templateMethod = qualifySpec(dropPlaceholder(str(parameters.get("templateMethod")), "templateMethod",
                placeholderNotes), fileParam);
        if (methodSpec != null && methodSpec.equals(templateMethod)) {
            // method == templateMethod means "model this method on itself" - the roles were
            // conflated, and the run inserted an empty duplicate of an existing passing test
            return Map.of("status", "Error", "message", "method and templateMethod are the SAME method ("
                    + methodSpec + "): `method` is the NEW test to create (a name that does not exist yet) and "
                    + "`templateMethod` is the EXISTING test to model it on. Give the new test its own name. "
                    + "Nothing was edited.");
        }
        String targetReach = qualifySpec(dropPlaceholder(str(parameters.get("targetReach")), "targetReach",
                placeholderNotes), fileParam);
        String module = GradleModules.resolve(str(parameters.get("module")), project, file,
                System.getProperty("hermit.treegen.module"));
        String testFilter = dropPlaceholder(str(parameters.get("testFilter")), "testFilter", placeholderNotes);
        String expectOutcome = inferredExpectation(task, str(parameters.get("expectTestOutcome")), placeholderNotes);
        String emulateSpecParam = qualifySpec(dropPlaceholder(str(parameters.get("emulateSpec")), "emulateSpec",
                placeholderNotes), fileParam);

        int processRetries = Integer.getInteger("hermit.treegen.processRetries", 3);
        int itemAttempts = Integer.getInteger("hermit.treegen.itemAttempts", 25);

        List<String> transcript = new ArrayList<>(placeholderNotes);
        String originalSource;
        try {
            originalSource = Files.readString(file);
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "cannot read " + file + ": " + e.getMessage());
        }

        // two items made the named test pass and broke two round-trip tests of the
        // same class, and TreeCodegen would have returned Success. When the named test is one method and is expected
        // green, or red (a new test beside them), the tests of its class that pass before the change must still pass
        // after it.
        regressionClass = null;
        baselineGreen = Set.of();
        String testClass = testClassOf(testFilter);
        if (testClass != null && ("green".equalsIgnoreCase(expectOutcome) || "red".equalsIgnoreCase(expectOutcome))
                && testsCanRun(project, module)
                && Boolean.parseBoolean(System.getProperty("hermit.treegen.regressionGate", "true"))) {
            try {
                if (runTests(project, module, testClass).exitCode() != -1) {
                    baselineGreen = new LinkedHashSet<>(passingTests(project, testClass));
                    if ("red".equalsIgnoreCase(expectOutcome)) {
                        baselineGreen.remove(testFilter.substring(testClass.length() + 1));
                    }
                    regressionClass = testClass;
                    transcript.add("regression baseline: " + baselineGreen.size() + " test(s) of " + testClass
                            + " pass before the change");
                }
            } catch (Exception e) {
                transcript.add("regression baseline unavailable: " + e.getMessage());
            }
        }

        List<Map<String, String>> presetItems = itemsFromParams(parameters);
        String processFeedback = "";
        for (int round = 1; processRetries == 0 || round <= processRetries; round++) {
            transcript.add("process round " + round);
            try {
                Map<String, Object> result = runOnce(task, project, file, methodSpec, templateMethod,
                        classesDir, presetItems, targetReach, module, testFilter, expectOutcome,
                        emulateSpecParam, itemAttempts, processFeedback, transcript);
                if ("Success".equals(result.get("status"))) {
                    result.put("rounds", round);
                    result.put("transcript", transcript);
                    return result;
                }
                // process-level failure: restore the file, carry the failure AND the round's lessons
                // (the transcript tail) into the next round's authoring context
                transcript.add("round " + round + " failed: "
                        + cap(String.valueOf(result.get("message")), 400));
                restore(file, originalSource, transcript, methodSpec);
                StringBuilder fb = new StringBuilder(String.valueOf(result.get("message")));
                int from = Math.max(0, transcript.size() - 8);
                fb.append("\nWhat happened, step by step:");
                for (int k = from; k < transcript.size(); k++) {
                    fb.append("\n  ").append(transcript.get(k));
                }
                processFeedback = fb.toString();
                // the caller's eight items all committed and compiled, and the
                // round failed on the test; the next two rounds re-authored the whole change as one item and never
                // compiled again. When every caller item committed, the next round starts from those items as
                // committed and repairs from the failure; otherwise it re-authors with the failure in context.
                List<Map<String, String>> carried = committedPresetItems;
                if (presetItems != null && !presetItems.isEmpty() && carried != null
                        && carried.size() == presetItems.size()) {
                    transcript.add("the next round starts from the " + carried.size() + " committed item(s)");
                    presetItems = carried;
                } else {
                    presetItems = null;
                }
            } catch (Exception e) {
                restore(file, originalSource, transcript, methodSpec);
                processFeedback = "unexpected failure: " + e;
                transcript.add(processFeedback);
            }
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "Error");
        err.put("message", "no successful generation within " + processRetries + " process rounds; last: "
                + processFeedback);
        err.put("transcript", transcript);
        return err;
    }

    // ---- one full process round --------------------------------------------------------------------------

    private Map<String, Object> runOnce(String task, Path project, Path file, String methodSpec,
            String templateMethod, String classesDir, List<Map<String, String>> presetItems,
            String targetReach, String module, String testFilter, String expectOutcome,
            String emulateSpecParam, int itemAttempts,
            String processFeedback, List<String> transcript) throws Exception {
        formAuthoredThisRound = false;
        committedPresetItems = null;

        // the project-wide graph + source table (built once, cached; refreshed per committed edit)
        transcript.add("inputs: method=" + methodSpec + " templateMethod=" + templateMethod + " items="
                + (presetItems == null ? 0 : presetItems.size()) + " expect=" + expectOutcome);
        if (Boolean.parseBoolean(System.getProperty("hermit.treegen.remoteCritics", "false"))) {
            HelperModelTool remote = new HelperModelTool();
            transcript.add("critic panel: " + (remote.isAvailable() ? "REMOTE " + remote.endpointLabel()
                    : "local (remote " + remote.endpointLabel() + " unreachable)"));
        }
        CodeGraph graph = CodeGraphTool.graphFor(project.toString(), classesDir);
        codeUnderTestSection = codeUnderTest(graph, methodSpec, task, targetReach, transcript);
        codeUnderTestForDerivation = codeUnderTestSection;

        List<Map<String, String>> items = presetItems;
        String templateFirstNote = "";
        // ROLE REPAIR: the planner puts the PRODUCTION method in `method` and the intended NEW test name in
        // `templateMethod` (drive 2D: method=Thermostat#adjust, templateMethod=
        // ThermostatTest#testAdjustWhenTheSensorFails, gradle/Hindsight steps referencing the latter).
        // When `method`'s type is not the edited file's type and the "template" is a non-existent method OF the
        // file's type, swap them; when only the type is foreign, re-home the method into the file's type.
        String fileType = fileParamType(file);
        if (methodSpec != null && methodSpec.contains("#") && fileType != null
                && !methodSpec.startsWith(fileType + "#") && !methodExists(file, methodSpec)) {
            if (templateMethod != null && templateMethod.startsWith(fileType + "#") && !methodExists(file, templateMethod)) {
                transcript.add("role repair: method=" + methodSpec + " is not in " + file.getFileName()
                        + "; templateMethod=" + templateMethod + " names the NEW test — swapping roles");
                methodSpec = templateMethod;
                templateMethod = null;
            } else {
                String rehomed = fileType + methodSpec.substring(methodSpec.indexOf('#'));
                transcript.add("role repair: method=" + methodSpec + " is not in " + file.getFileName()
                        + " — treating it as the NEW method " + rehomed);
                methodSpec = rehomed;
            }
        }
        if (templateMethod != null && !methodExists(file, templateMethod)) {
            // the planner named a template that does not exist (drive 2C: the same invented name as the new
            // method) - treat it as absent so the auto-template can pick a real one
            transcript.add("templateMethod " + templateMethod + " does not exist in the file — ignoring it");
            templateMethod = null;
        }
        if ((items == null || items.isEmpty()) && templateMethod != null && methodSpec != null
                && methodSpec.contains("#") && !methodExists(file, methodSpec) && looksLikeTestTask(task, file)) {
            // the planner names an EXISTING but unrelated template (lifted from a list of real names); when it
            // shares no words with the task and a better-matching @Test exists, clone that one instead
            String words = task + " " + camelWords(methodSpec) + " " + camelWords(targetReach);
            String auto = autoTemplate(file, methodSpec, words);
            if (auto != null && !auto.equals(templateMethod) && templateScore(file, templateMethod, words) == 0) {
                transcript.add("templateMethod " + templateMethod + " shares no words with the task — using "
                        + auto + " instead (best task-word overlap)");
                templateMethod = auto;
            }
        }
        if ((items == null || items.isEmpty()) && templateMethod == null && methodSpec != null
                && methodSpec.contains("#") && !methodExists(file, methodSpec) && looksLikeTestTask(task, file)) {
            // no template named: pick the existing @Test method whose name/body best matches the task words
            templateMethod = autoTemplate(file, methodSpec, task + " " + camelWords(methodSpec)
                    + " " + camelWords(targetReach));
            if (templateMethod != null) {
                transcript.add("auto-template: " + templateMethod + " (best task-word overlap)");
            }
        }
        if ((items == null || items.isEmpty()) && templateMethod != null && methodSpec != null
                && methodSpec.contains("#") && !methodExists(file, methodSpec)) {
            // TEMPLATE-FIRST: a small author cannot write a 30-line Mockito test from scratch (it
            // anchors on imports, invents helpers, reaches for hamcrest). Clone the template method under
            // the new name DETERMINISTICALLY - it compiles by construction - then ask only for the
            // statement edits that turn the clone into the requested behaviour.
            Map<String, String> clone = cloneTemplateItem(file, templateMethod, methodSpec);
            if (clone == null) {
                // this branch used to be SILENT - the author was then asked to edit a method that did not
                // exist, with no note and no template preference, and anchored on the template's javadoc
                transcript.add("template-first: could not clone " + templateMethod + " as " + methodSpec
                        + " — the template was not found in " + file.getFileName());
            }
            if (clone != null) {
                transcript.add("template-first: cloned " + templateMethod + " as " + methodSpec);
                ApplyOutcome cloned = applyItems(List.of(clone), file, methodSpec, graph, classesDir, 1,
                        task, transcript);
                if (cloned.failure() != null) {
                    return cloned.failure();
                }
                templateFirstNote = explicitThrowRule(task) + contrastRule(task) + "\n\nNOTE: " + methodSpec + " ALREADY EXISTS as an exact clone of "
                        + templateMethod + " (shown as CURRENT CODE). Do NOT insert a new method. PREFERRED: reply "
                        + "with EXACTLY ONE item {\"op\": \"replaceBody\", \"method\": \"" + methodSpec
                        + "\", \"code\": \"<the COMPLETE new body — every statement, no signature>\"}: start from "
                        + "the clone's body verbatim and change only what the behaviour requires (what it stubs, "
                        + "the call it exercises, the value it asserts). Statement-level edits with anchors copied "
                        + "from its body are also accepted. The result must do exactly this: " + task;
            }
        }
        if ((items == null || items.isEmpty()) && !templateFirstNote.isBlank()) {
            // the scenario form first - a small model fills a form far more reliably than it writes a body
            List<Map<String, String>> formItems = scenarioFormItems(task, file, methodSpec, templateMethod,
                    processFeedback, transcript);
            if (formItems != null && !formItems.isEmpty()) {
                items = formItems;
                formAuthoredThisRound = true;
            }
        }
        if (items == null || items.isEmpty()) {
            // even after the clone the first round used anchored items and burned five on a
            // placeholder anchor; the whole-body item is required from the FIRST round whenever a clone exists
            boolean requireWholeBody = !templateFirstNote.isBlank();
            items = authorItems(task + templateFirstNote + wholeBodyRequirement(requireWholeBody, methodSpec), file,
                    methodSpec, templateMethod, processFeedback, transcript);
            for (int ask = 0; requireWholeBody && !isSingleWholeBodyItem(items) && ask < 2; ask++) {
                // statement-level items after a failed round put a stub before its variable's declaration
                // and appended calls after the exercised call; every coherent body came from a whole-body rewrite
                transcript.add("round requires ONE replaceBody item — the author sent " + items.size()
                        + " item(s) of other ops; asking again");
                items = authorItems(task + templateFirstNote + wholeBodyRequirement(true, methodSpec)
                        + "\n\nYOUR LAST REPLY WAS NOT ONE replaceBody ITEM. Reply with exactly: "
                        + "[{\"op\": \"replaceBody\", \"method\": \"" + methodSpec + "\", \"code\": \"<the complete body>\"}]",
                        file, methodSpec, templateMethod, processFeedback, transcript);
            }
            if (items.isEmpty()) {
                return failure("the author produced no edit items");
            }
        }

        // 2) apply + COMMIT each item individually; failed items iterate with their problems
        ApplyOutcome applied = applyItems(items, file, methodSpec, graph, classesDir, itemAttempts,
                task, transcript);
        if (applied.failure() != null) {
            return applied.failure();
        }
        if (presetItems != null && !presetItems.isEmpty() && items == presetItems) {
            committedPresetItems = applied.committed();
        }
        String lastFocus = applied.focus();
        String roundFocus = lastFocus;   // every member this round committed, for the test-driven repair

        // 3..5) compile → reach → critics; critic dissent triggers SURGICAL repair of the committed
        // code (a rejected round would throw away a compiled method over a few missing lines)
        Map<String, Object> result = new LinkedHashMap<>();
        int lenses = CRITIC_LENSES.length + (HostedCodeReview.enabled() ? 1 : 0);   // + the hosted reviewer
        int needed = Math.max(1, Math.min(Integer.getInteger("hermit.treegen.critics.min", lenses), lenses));
        int repairsAllowed = Math.max(0, Integer.getInteger("hermit.treegen.criticRepairs", 2));
        // the best version seen by the critic panel - a repair that lowers approval is reverted, and a split
        // panel at the end is settled by the emulator's positive prediction of the expected outcome
        String bestSource = null;
        String bestFocus = null;
        int bestApprovals = -1;
        List<String> bestIssues = List.of();
        boolean testDecided = false;
        for (int repair = 0; ; repair++) {
            CliResult compile = compile(project, file, module, classesDir);
            if (compile.exitCode() != 0) {
                // javac said "symbol: method doThrow" - a MISSING STATIC IMPORT, which the author
                // cannot see and kept failing to fix. When the unresolved name is a static method of a well-known
                // test-library owner, add the import ourselves and recompile before spending a repair.
                List<String> added = addMissingStaticImports(file, compile.tail());
                if (!added.isEmpty()) {
                    transcript.add("added missing static import(s): " + added);
                    CodeGraphTool.invalidate(file);
                    continue;
                }
                // `verify(catalog).loadAll(any())` on a method that throws IOException - javac:
                // "unreported exception IOException; must be caught or declared to be thrown". The repair author
                // deleted the verify instead; the deterministic fix is a throws clause on the generated method.
                List<String> thrown = addMissingThrows(file, methodSpec, compile.tail());
                if (!thrown.isEmpty()) {
                    transcript.add("added throws clause: " + thrown);
                    CodeGraphTool.invalidate(file);
                    continue;
                }
                // `doReturn(Books.from("content")).when(spy).loadAll(...)` - an invented static
                // on a stub the scenario never needed (the spy already returns the real content) - cost the round to
                // a repair that tried to import a non-existent class. A stub on a spy that does not compile is dropped.
                String droppedStub = dropUnresolvableSpyStub(file, methodSpec, compile.tail(),
                        CodeGraphTool.indexFor(classesDir), graph.sources());
                if (droppedStub != null) {
                    transcript.add("dropped an unresolvable stub on a spy (the real object's behaviour stands): " + droppedStub);
                    CodeGraphTool.invalidate(file);
                    continue;
                }
                // javac found what source-only validation honestly couldn't (open supertype chains):
                // repair the one bad line instead of burning the round
                if (repair >= repairsAllowed) {
                    return failure("compilation failed:\n" + compile.tail());
                }
                transcript.add("compile failed — surgical repair " + (repair + 1) + "; javac: "
                        + cap(firstJavacError(compile.tail()), 400));
                List<Map<String, String>> fixes = authorRepairItems(task, file, methodSpec,
                        templateMethod,
                        List.of("the code does NOT COMPILE; javac says:\n" + cap(compile.tail(), 1500)
                                + offendingLines(file, compile.tail())
                                + idiomFromTemplate(file, templateMethod, compile.tail())
                                + callSiteExamples(graph, compile.tail())),
                        transcript);
                if (fixes.isEmpty()) {
                    return failure("compilation failed and the author produced no repair items:\n"
                            + compile.tail());
                }
                ApplyOutcome fixedUp = applyItems(fixes, file, methodSpec, graph, classesDir,
                        itemAttempts, task, transcript);
                if (fixedUp.failure() != null) {
                    return fixedUp.failure();
                }
                lastFocus = fixedUp.focus();
                roundFocus = roundFocus + "\n\n" + lastFocus;
                continue;
            }
            transcript.add("compiled OK");
            if (targetReach != null && methodSpec != null) {
                graph.refresh(file);
                CodeGraph.ReachResult reach = graph.reach(methodSpec, targetReach, 12);
                if (!reach.reachable()) {
                    return failure("generated code does not transitively reach " + targetReach
                            + " from " + methodSpec + (reach.undecided()
                                    ? " (search stopped on its " + reach.status() + " bound — inconclusive)"
                                    : " (no path in the indexed code)")
                            + " — restructure the edits so the call chain reaches it");
                }
                result.put("reachPath", reach.path());
                result.put("reachStatus", reach.status());
                transcript.add("reach verified: " + reach.path());
            }

            // two compiled one-line edits were discarded because the critics misread
            // one ("consumes ')' before checking", which was the point of it) and asked the other for work outside the
            // task, and the repair author then invented methods; the named test never ran. When the caller names a test
            // and expects it GREEN, that run decides: a green outcome is accepted, and the critics' points come back as
            // advisory notes. A red expectation keeps the critics in charge (they catch tests that are red for the wrong
            // reason), and so does a test that is not yet green.
            if (testFilter != null && "green".equalsIgnoreCase(expectOutcome) && testsCanRun(project, module)) {
                CliResult early = runTests(project, module, testFilter);
                String earlyOutcome = early.exitCode() == 0 ? "green" : "red";
                transcript.add("test outcome: " + earlyOutcome + " (run before the critics)");
                List<String> broken = "green".equals(earlyOutcome) ? regressions(project, module) : List.of();
                if (!broken.isEmpty()) {
                    earlyOutcome = "red";
                    transcript.add("regression: " + broken.size() + " test(s) of " + regressionClass
                            + " that passed before now fail: " + cap(String.join(", ", broken), 400));
                }
                if ("green".equals(earlyOutcome)) {
                    List<String> advisory = runCritics(task, lastFocus, expectOutcome);
                    if (!advisory.isEmpty()) {
                        // a note on a member a later part ports ("typeOf throws ...") sent
                        // the next plan after it, not to the request's commit command; the notes say they are advisory
                        result.put("reviewNotes", "ADVISORY ONLY: the expected test passed, so this step is done; do the "
                                + "request's next step (a note about code outside this step is for a later step). Notes: "
                                + advisory);
                        transcript.add("critics' notes kept as advisory: " + cap(String.valueOf(advisory)
                                .replaceAll("\\s+", " "), 600));
                    }
                    result.put("testOutcome", earlyOutcome);
                    result.put("testExpectationMet", true);
                    result.put("critics", (lenses - advisory.size()) + "/" + lenses
                            + " approved (advisory: the expected green test decided)");
                    testDecided = true;
                    break;
                }
                String earlyWhy = BashTool.gradleTestFailures(project, "--tests "
                        + (broken.isEmpty() ? testFilter : regressionClass));
                result.put("earlyTestOutput", earlyWhy.isBlank() ? early.tail() : earlyWhy);
                // eight committed and compiled items left the expected-green test
                // red (the code took `cursor.match(COLON)` to consume the colon; in that file match only looks). The
                // critics then judged the last item alone, their complaints went to the repair author instead of the
                // failure, and the author invented five helpers. A red test that was expected green is the evidence:
                // it drives the repair, with the lines the failure passed through and the members the code calls.
                String failureText = !broken.isEmpty()
                        ? " the named test passes, but these tests of " + regressionClass + " passed before the change "
                                + "and fail now: " + String.join(", ", broken) + cap(earlyWhy, 1500)
                        : early.exitCode() == -1
                        ? " the test run did not finish within " + testTimeoutSeconds() + " seconds and was stopped "
                                + "(the code under test may loop without consuming its input)"
                        : earlyWhy.isBlank() ? " (test output, tail) " + cap(early.tail(), 1500) : cap(earlyWhy, 1500);
                if (repair >= repairsAllowed) {
                    return failure((broken.isEmpty() ? "the named test " + testFilter + " is red, but green was expected"
                            : "the change breaks tests that passed before it") + "; the test report says:" + failureText);
                }
                transcript.add("test red, expected green — repair " + (repair + 1) + " from the test failure:"
                        + cap(failureText.replaceAll("\\s+", " "), 400));
                List<Map<String, String>> testFixes = authorRepairItems(TEST_REPAIR_HEADER, task, file, methodSpec,
                        templateMethod,
                        List.of((broken.isEmpty() ? "the named test " + testFilter + " is RED but must be GREEN"
                                : "the change must keep every test that passed before it passing") + "; the test report says:"
                                + failureText
                                + failurePath(project, file)
                                + calledMembers(file, roundFocus)
                                + (processFeedback.isBlank() ? ""
                                        : "\nAN EARLIER ROUND ENDED LIKE THIS:\n" + cap(processFeedback, 1500))),
                        transcript);
                if (testFixes.isEmpty()) {
                    return failure("the named test " + testFilter + " is red, but green was expected, and the author "
                            + "produced no repair items; the test report says:" + failureText);
                }
                ApplyOutcome testFixed = applyItems(testFixes, file, methodSpec, graph, classesDir, itemAttempts,
                        task, transcript);
                if (testFixed.failure() != null) {
                    return testFixed.failure();
                }
                lastFocus = testFixed.focus();
                roundFocus = roundFocus + "\n\n" + lastFocus;
                continue;
            }

            // the caller's items, reviewed before they ran, made the named new test red at
            // its assertions, as expected. The critics then called its position asserts fragile, and two repair rounds
            // rewrote it over half an hour into a test that could never pass. When the caller supplies the items and
            // expects the named test RED, a first run that fails it at an assertion, with every test of its class that
            // passed before still passing, decides, and the critics' points come back as advisory notes. A red for
            // another reason (an exception from the fixture, no report) keeps the critics in charge.
            if (repair == 0 && committedPresetItems != null && testFilter != null
                    && "red".equalsIgnoreCase(expectOutcome) && testsCanRun(project, module)) {
                CliResult early = runTests(project, module, testFilter);
                boolean atAssertion = early.exitCode() != 0 && early.exitCode() != -1
                        && redAtAnAssertion(project, testFilter);
                List<String> broken = atAssertion ? regressions(project, module) : List.of();
                transcript.add("test outcome: " + (early.exitCode() == 0 ? "green" : "red")
                        + (atAssertion ? " at an assertion" : "") + " (run before the critics)"
                        + (broken.isEmpty() ? "" : "; these tests of " + regressionClass
                                + " passed before the change and fail now: " + cap(String.join(", ", broken), 400)));
                if (atAssertion && broken.isEmpty()) {
                    List<String> advisory = runCritics(task, lastFocus, expectOutcome);
                    if (!advisory.isEmpty()) {
                        result.put("reviewNotes", "ADVISORY ONLY: the expected red test fails at its assertion, so this "
                                + "step is done; do the request's next step. Notes: " + advisory);
                        transcript.add("critics' notes kept as advisory: " + cap(String.valueOf(advisory)
                                .replaceAll("\\s+", " "), 600));
                    }
                    result.put("testOutcome", "red");
                    result.put("testExpectationMet", true);
                    result.put("critics", (lenses - advisory.size()) + "/" + lenses
                            + " approved (advisory: the expected red test decided)");
                    testDecided = true;
                    break;
                }
            }

            // a compiling test approved 3/3 by the remote panel was RED for the wrong reason - its
            // only assertion was the template's `verify(display)…`, which fails before AND after the fix.
            // For a contrast task the assertion must target a write of the contrasted value; that is checkable.
            String testSourceNow = "";
            try {
                testSourceNow = Files.readString(file);
            } catch (Exception ignored) {
                // gate falls back to the unfiltered candidates
            }
            String gate = contrastedVerifyIssue(lastFocus, contrastedWriteCalls(codeUnderTestSection, task, testSourceNow), task);
            List<String> criticIssues = gate != null
                    ? List.of("[deterministic: the assertion must target the contrasted write] " + gate)
                    : dropTddContradictions(runCritics(task, lastFocus, expectOutcome), expectOutcome, transcript);
            int approvals = gate != null ? 0 : lenses - criticIssues.size();
            if (gate != null) {
                transcript.add("deterministic gate: " + cap(gate, 300));
            }
            if (approvals > bestApprovals) {
                bestApprovals = approvals;
                bestSource = Files.readString(file);
                bestFocus = lastFocus;
                bestIssues = criticIssues;
            } else if (approvals < bestApprovals && bestSource != null && approvals < needed) {
                // a body one critic approved was "repaired" down to none - never continue from a
                // version worse than the best; revert and repair the best one instead
                transcript.add("repair made it worse (" + approvals + "/" + lenses + " < " + bestApprovals + "/"
                        + lenses + ") — reverting to the best version");
                Files.writeString(file, bestSource);
                CodeGraphTool.invalidate(file);
                lastFocus = bestFocus;
                criticIssues = bestIssues;
                approvals = bestApprovals;
            }
            if (approvals >= needed) {
                // 5.5) EMULATOR GATE: predict the test outcome before paying for a real gradle run;
                // a prediction contradicting the expectation is repaired like a critic dissent
                String emulateSpec = emulateSpecFor(emulateSpecParam, methodSpec, testFilter,
                        expectOutcome);
                if (emulateSpec != null
                        && Boolean.parseBoolean(System.getProperty("hermit.treegen.emulate", "true"))) {
                    graph.refresh(file);
                    Interpreter.Report prediction =
                            Interpreter.run(graph, emulateSpec);
                    String verdict = prediction.verdict();
                    transcript.add("emulator: " + verdict);
                    result.put("emulatorPrediction", verdict);
                    boolean contradicted =
                            verdict.startsWith("PREDICTED RED") && "green".equalsIgnoreCase(expectOutcome)
                            || verdict.startsWith("PREDICTED GREEN") && "red".equalsIgnoreCase(expectOutcome)
                            || verdict.startsWith("PREDICTED THROW") && "green".equalsIgnoreCase(expectOutcome);
                    if (contradicted) {
                        if (repair >= repairsAllowed) {
                            return failure("the emulator predicts the test will contradict the expected "
                                    + "outcome (" + expectOutcome + "): " + verdict);
                        }
                        transcript.add("emulator contradicts expected " + expectOutcome
                                + " — surgical repair " + (repair + 1));
                        List<String> tail = prediction.trace().size() > 25
                                ? prediction.trace().subList(prediction.trace().size() - 25,
                                        prediction.trace().size())
                                : prediction.trace();
                        List<Map<String, String>> emuFixes = authorRepairItems(task, file, methodSpec,
                                templateMethod,
                                List.of("an EMULATION of the test predicts it will contradict the expected "
                                        + "outcome (" + expectOutcome + "): " + verdict
                                        + "\nPredicted trace (tail):\n  " + String.join("\n  ", tail)),
                                transcript);
                        if (emuFixes.isEmpty()) {
                            return failure("emulator contradiction and no repair items: " + verdict);
                        }
                        ApplyOutcome emuFixed = applyItems(emuFixes, file, methodSpec, graph, classesDir,
                                itemAttempts, task, transcript);
                        if (emuFixed.failure() != null) {
                            return emuFixed.failure();
                        }
                        lastFocus = emuFixed.focus();
                        roundFocus = roundFocus + "\n\n" + lastFocus;
                        continue;
                    }
                }
                result.put("critics", approvals + "/" + lenses + " approved"
                        + (repair > 0 ? " after " + repair + " repair(s)" : ""));
                break;
            }
            if (repair >= repairsAllowed) {
                // a split panel (some approval, not enough) is a judgement call an LLM heuristic should not
                // make alone - the emulator is deterministic. Accept the best version only when it POSITIVELY
                // predicts the expected outcome; anything less stays a rejection.
                String emulateSpec = emulateSpecFor(emulateSpecParam, methodSpec, testFilter, expectOutcome);
                if (bestApprovals >= 1 && bestSource != null && emulateSpec != null && expectOutcome != null
                        && Boolean.parseBoolean(System.getProperty("hermit.treegen.emulate", "true"))) {
                    Files.writeString(file, bestSource);
                    CodeGraphTool.invalidate(file);
                    graph.refresh(file);
                    Interpreter.Report prediction = Interpreter.run(graph, emulateSpec);
                    transcript.add("critics split (" + bestApprovals + "/" + lenses + ") — emulator: "
                            + prediction.verdict());
                    if (emulatorConfirms(prediction.verdict(), expectOutcome)) {
                        transcript.add("emulator confirms the expected " + expectOutcome + " — accepting the best version");
                        result.put("emulatorPrediction", prediction.verdict());
                        result.put("critics", bestApprovals + "/" + lenses + " approved (emulator tie-break)");
                        lastFocus = bestFocus;
                        break;
                    }
                }
                return failure("critic panel rejected (" + approvals + "/" + lenses + " approved): "
                        + criticIssues);
            }
            if (criticDissentGoesToTheForm(formAuthoredThisRound)) {
                // after a FORM-authored round the local repair author "fixed" the critics'
                // scenario complaints at code level - invented load()/hash(), duplicate spies, undeclared names -
                // through six repairs. The scenario is the form author's to change: the round ends here and the
                // critics' issues become the next round's form feedback.
                return failure("critic panel rejected the scenario (" + approvals + "/" + lenses + " approved): "
                        + cap(String.valueOf(criticIssues).replaceAll("\\s+", " "), 1500)
                        + " — the next scenario form must address these (verifyCallChoice + repeatTrigger pin how often "
                        + "a collaborator call happens)");
            }
            transcript.add("critics dissented (" + approvals + "/" + lenses + ") — surgical repair "
                    + (repair + 1) + "; issues: " + cap(String.valueOf(criticIssues).replaceAll("\\s+", " "), 1200));
            List<Map<String, String>> fixes = authorRepairItems(task, file, methodSpec, templateMethod,
                    criticIssues, transcript);
            if (fixes.isEmpty()) {
                return failure("critic panel rejected (" + approvals + "/" + lenses + ") and the author "
                        + "produced no repair items: " + criticIssues);
            }
            ApplyOutcome repaired = applyItems(fixes, file, methodSpec, graph, classesDir, itemAttempts,
                    task, transcript);
            if (repaired.failure() != null) {
                return repaired.failure();
            }
            lastFocus = repaired.focus();
            roundFocus = roundFocus + "\n\n" + lastFocus;
        }
        result.put("status", "Success");
        result.put("focus", lastFocus);
        result.put("compiled", true);
        if (methodSpec != null) {
            GENERATED_THIS_SESSION.add(file.toString() + "::" + methodSpec);
        }

        // 6) optional test run (TDD-aware); already done when the expected green outcome decided above
        if (testFilter != null && !testDecided) {
            CliResult test = runTests(project, module, testFilter);
            String outcome = test.exitCode() == 0 ? "green" : "red";
            result.put("testOutcome", outcome);
            if (expectOutcome != null && !expectOutcome.equalsIgnoreCase(outcome)) {
                // a round that ended in a one-line stub was approved by the critics
                // and returned as Success with the expected-green test red, and the stub stayed in the file. An
                // outcome that contradicts the expectation fails the round: the file is restored and re-authored.
                transcript.add("test outcome: " + outcome + " — expected " + expectOutcome);
                String why = BashTool.gradleTestFailures(project, "--tests " + testFilter);
                return failure("the named test " + testFilter + " is " + outcome + ", but " + expectOutcome
                        + " was expected; " + (why.isBlank() ? "test output (tail): " + cap(test.tail(), 1500)
                        : "the test report says:" + cap(why, 1500)));
            } else {
                result.put("testExpectationMet", expectOutcome != null);
            }
            transcript.add("test outcome: " + outcome);
        }
        return result;
    }

    /**
     * Either the focus text of the committed items (with the items as committed), or the failure that stopped the
     * batch. Rewrite drive, parser slice 030j3: the focus used to be the LAST item's alone, so the critics judged a
     * one-line call out of eight items and called the whole change a stub.
     */
    private record ApplyOutcome(String focus, Map<String, Object> failure, List<Map<String, String>> committed) {
        ApplyOutcome(String focus, Map<String, Object> failure) {
            this(focus, failure, List.of());
        }
    }

    /** How many rejections with the same problems end an item's re-authoring. */
    static final int STALL_REPEATS = 3;

    /** Apply + COMMIT each item individually; failed items iterate with their problems. */
    private ApplyOutcome applyItems(List<Map<String, String>> items, Path file, String methodSpec,
            CodeGraph graph, String classesDir, int itemAttempts, String task,
            List<String> transcript) throws Exception {
        items = mergedMemberInserts(declarationsFirst(items, transcript), transcript);
        List<String> focuses = new ArrayList<>();
        List<Map<String, String>> committedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, String> item = items.get(i);
            List<String> problems = List.of();
            boolean committed = false;
            Map<String, Integer> seenProblems = new HashMap<>();
            for (int attempt = 1; itemAttempts == 0 || attempt <= itemAttempts; attempt++) {
                String source = Files.readString(file);
                // a whole-body REPAIR dropped the entire fixture setup (creatorId, event
                // stubs, givenSubjectWith) and the body shrank from ~20 statements to 8 - javac then failed on
                // an undeclared variable. A rewrite that loses most of what was there is refused, naming the losses.
                String shrink = bodyShrinkProblem(source,
                        repairTruncatedType(resolveItemSpec(itemTarget(item, methodSpec), methodSpec),
                                methodSpec),
                        item.getOrDefault("op", ""), item.get("code"), item.get("anchor"));
                if (shrink != null) {
                    problems = coachTypeProblems(List.of(shrink), graph);
                    String head0 = String.valueOf(item.get("code")).replaceAll("\\s+", " ");
                    transcript.add("item " + (i + 1) + " rejected: " + problems + " code=«"
                            + (head0.length() > 140 ? head0.substring(0, 140) + "…" : head0) + "»");
                    item = reauthorItem(item, problems, task);
                    if (item == null) {
                        break;
                    }
                    continue;
                }
                TreeEditTool.Outcome out = TreeEditTool.apply(source, CodeGraphTool.indexFor(classesDir),
                        graph.sources(),
                        item.getOrDefault("op", "insertAtEnd"),
                        repairTruncatedType(resolveItemSpec(itemTarget(item, methodSpec), methodSpec),
                                methodSpec),
                        repairTruncatedType(resolveItemType(item.get("type"), methodSpec), methodSpec),
                        item.get("anchor"), item.get("code"));
                if (out.ok()) {
                    Files.writeString(file, out.rendered());
                    CodeGraphTool.invalidate(file);
                    focuses.add(out.focusText());
                    committedItems.add(item);
                    transcript.add("item " + (i + 1) + "/" + items.size() + " committed (attempt " + attempt + ")");
                    committed = true;
                    break;
                }
                problems = coachTypeProblems(out.problems(), graph);
                // show the rejected code itself - the drive-2K post-mortem needed the model server's log to
                // learn that every "INCOMPLETE" item was in fact a comment
                String head = String.valueOf(item.get("code")).replaceAll("\\s+", " ");
                transcript.add("item " + (i + 1) + " rejected: " + problems + " code=«"
                        + (head.length() > 140 ? head.substring(0, 140) + "…" : head) + "»");
                // one repair item was re-authored 25 times into the same rejected
                // code, the same invented helpers each time. Slice 030k2: two invented type names alternated for 25
                // attempts, so no two rejections in a row were the same. A rejection whose problems came back a third
                // time ends the item.
                String normalized = String.join("\n", problems).replaceAll("\\[\\d+:\\d+\\]", "");
                if (seenProblems.merge(normalized, 1, Integer::sum) >= STALL_REPEATS) {
                    return new ApplyOutcome(null, failure("item " + (i + 1) + " stalled: the same problems came back "
                            + STALL_REPEATS + " times: " + problems));
                }
                // a code string that ends mid-expression is usually a cut-off; re-authoring reproduced the
                // SAME cut four times (drive 2J). Before asking again, try the mechanical completion: balance an
                // open string literal, close the open brackets, add the ';'.
                String completed = problems.stream().anyMatch(pr -> pr.contains("END_OF_FILE"))
                        ? autoCompleteSnippet(item.get("code")) : null;
                if (completed != null && !completed.equals(item.get("code"))) {
                    Map<String, String> tryItem = new LinkedHashMap<>(item);
                    tryItem.put("code", completed);
                    TreeEditTool.Outcome retry = TreeEditTool.apply(source, CodeGraphTool.indexFor(classesDir),
                            graph.sources(), tryItem.getOrDefault("op", "insertAtEnd"),
                            repairTruncatedType(resolveItemSpec(itemTarget(tryItem, methodSpec),
                                    methodSpec), methodSpec),
                            repairTruncatedType(resolveItemType(tryItem.get("type"), methodSpec), methodSpec),
                            tryItem.get("anchor"), completed);
                    if (retry.ok()) {
                        Files.writeString(file, retry.rendered());
                        CodeGraphTool.invalidate(file);
                        focuses.add(retry.focusText());
                        committedItems.add(tryItem);
                        transcript.add("item " + (i + 1) + "/" + items.size() + " committed after auto-completing a cut-off snippet");
                        committed = true;
                        break;
                    }
                }
                Map<String, String> fixed = reauthorItem(item, problems, task);
                if (fixed == null) {
                    return new ApplyOutcome(null, failure("author gave up on item " + (i + 1) + ": " + problems));
                }
                item = fixed;
            }
            if (!committed) {
                return new ApplyOutcome(null,
                        failure("item " + (i + 1) + " never committed; last problems: " + problems));
            }
        }
        return new ApplyOutcome(String.join("\n\n", focuses), null, committedItems);
    }

    /** an item whose method spec carries a <placeholder> type ("<ClassName>#m", "<the class>#m") means the
     *  target method's type; a bare name means the target type too. */
    /**
     * Item 1 called isKeywordOf, which item 2 inserts, and was validated first: "no visible method 'isKeywordOf'". Its
     * re-author then swapped in a method that does exist, with the wrong arity, and the item stalled. Each item is
     * validated against the file as it stands, so imports go first, then member inserts, then the statement and body
     * edits; the order within each group is kept, and a reorder is noted in the transcript.
     */
    static List<Map<String, String>> declarationsFirst(List<Map<String, String>> items, List<String> transcript) {
        if (items == null || items.size() < 2) {
            return items;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> declarationRank(items.get(i))));
        boolean moved = false;
        for (int i = 0; i < order.size(); i++) {
            moved |= order.get(i) != i;
        }
        if (!moved) {
            return items;
        }
        List<Map<String, String>> out = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (int i : order) {
            out.add(items.get(i));
            if (declarationRank(items.get(i)) < 2) {
                numbers.add(String.valueOf(i + 1));
            }
        }
        transcript.add("items reordered: planned items " + String.join(", ", numbers) + " first (imports, then members), "
                + "so the edits that use them see them; items are numbered in the new order below");
        return out;
    }

    /**
     * Build (item 6) called processImports, which item 7 inserts; both were member inserts, validated one by one, so
     * item 6 was refused and re-authored. Consecutive member inserts into one type become one insert, validated
     * together (TreeEdit's insertMember takes several members), as ToolExecutor merges TreeEdit's.
     */
    static List<Map<String, String>> mergedMemberInserts(List<Map<String, String>> items, List<String> transcript) {
        if (items == null || items.size() < 2) {
            return items;
        }
        List<Map<String, String>> out = new ArrayList<>();
        int run = 1;
        for (Map<String, String> item : items) {
            Map<String, String> last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && declarationRank(item) == 1 && declarationRank(last) == 1
                    && Objects.equals(item.get("type"), last.get("type")) && item.get("code") != null) {
                Map<String, String> merged = new LinkedHashMap<>(last);
                merged.put("code", String.valueOf(last.get("code")).strip() + "\n\n" + item.get("code").strip());
                out.set(out.size() - 1, merged);
                run++;
                continue;
            }
            noteMerge(last, run, transcript);
            run = 1;
            out.add(item);
        }
        noteMerge(out.get(out.size() - 1), run, transcript);
        return out;
    }

    private static void noteMerge(Map<String, String> item, int run, List<String> transcript) {
        if (item != null && run > 1) {
            transcript.add(run + " member inserts into " + item.getOrDefault("type", "the type") + " are validated "
                    + "together as one item, so members that use each other see each other");
        }
    }

    /** 0 an import, 1 a member insert, 2 a statement or body edit. */
    private static int declarationRank(Map<String, String> item) {
        String op = item.getOrDefault("op", "").toLowerCase(Locale.ROOT).replace("_", "");
        if (op.equals("addimport")) {
            return 0;
        }
        return op.equals("insertmember") || op.equals("addmember") || op.equals("insertfield") || op.equals("addfield")
                || op.equals("insertmethod") || op.equals("addmethod") || op.equals("addtype") ? 1 : 2;
    }

    static String resolveItemSpec(String spec, String methodSpec) {
        if (spec == null || methodSpec == null || !methodSpec.contains("#")) {
            return spec;
        }
        String targetType = methodSpec.substring(0, methodSpec.indexOf('#'));
        String s = spec.trim();
        // the author wrapped the WHOLE spec in brackets - "<Type#testTotalThrows>" -
        // a shape F105 did not cover (it handled "<ClassName>#method"), and the coaching then echoed "type=<Type".
        if (s.length() > 2 && s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.contains("#")) {
            String type = s.substring(0, s.indexOf('#'));
            if (type.matches("(?i)type|classname|testclass|theclass|myclass|class|test|thetestclass")) {
                return targetType + s.substring(s.indexOf('#'));   // a placeholder TYPE means the target's type
            }
        }
        if (s.startsWith("<") && s.contains(">#")) {
            return targetType + s.substring(s.indexOf('#'));
        }
        if (!s.contains("#") && !s.contains(".") && !s.isEmpty()) {
            return targetType + "#" + s;
        }
        return s;
    }

    /** A <placeholder> type ("<the class>", "<ClassName>") means the target method's type. */
    static String resolveItemType(String type, String methodSpec) {
        if (type == null) {
            return null;
        }
        String t = type.trim();
        // a half-open bracket ("<Type") or a placeholder word both mean the target's type
        if (t.startsWith("<")) {
            t = t.replaceAll("^<+", "").replaceAll(">+$", "").trim();
            if (methodSpec != null && methodSpec.contains("#")) {
                return methodSpec.substring(0, methodSpec.indexOf('#'));
            }
        }
        if (methodSpec != null && methodSpec.contains("#")
                && t.matches("(?i)type|classname|testclass|theclass|myclass|class|test|thetestclass")) {
            return methodSpec.substring(0, methodSpec.indexOf('#'));
        }
        return t;
    }

    /**
     * The plan named {@code InvoiceTest#testTotalThrows} correctly, and the AUTHOR wrote its items
     * against {@code Test#testTotalThrows} - a truncated type. Both rounds died on "type not found: Test",
     * and the coaching echoed the author's own wrong name back at it. An unresolvable type that is a suffix or prefix
     * of the TARGET's type is repaired to the target's type.
     */
    static String repairTruncatedType(String spec, String methodSpec) {
        if (spec == null || methodSpec == null || !methodSpec.contains("#")) {
            return spec;
        }
        String target = methodSpec.substring(0, methodSpec.indexOf('#'));
        int hash = spec.indexOf('#');
        String type = hash < 0 ? spec : spec.substring(0, hash);
        String rest = hash < 0 ? "" : spec.substring(hash);
        if (type.isEmpty() || type.equals(target)) {
            return spec;
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        boolean related = simple.length() >= 4
                && (target.endsWith(simple) || simple.endsWith(target)
                        || target.toLowerCase(Locale.ROOT).contains(simple.toLowerCase(Locale.ROOT)));
        return related ? target + rest : spec;
    }

    /** Static-import owners whose members a generated TEST routinely calls unqualified. */
    private static final List<String> STATIC_IMPORT_OWNERS = List.of(
            "org.mockito.Mockito", "org.mockito.BDDMockito", "org.mockito.ArgumentMatchers",
            "org.junit.jupiter.api.Assertions", "org.junit.jupiter.api.Assumptions",
            "org.assertj.core.api.Assertions", "java.util.Objects", "java.util.Collections",
            "java.util.List", "java.util.Map", "java.util.Set");

    /**
     * Add {@code import static <owner>.<name>;} for every unresolved static method javac names, when exactly one known
     * owner declares it. Returns the imports added (empty when nothing matched, so the caller falls through to a normal
     * repair).
     */
    static List<String> addMissingStaticImports(Path file, String javacTail) {
        return addMissingStaticImports(file, javacTail, STATIC_IMPORT_OWNERS);
    }

    /**
     * The planner never passed expectTestOutcome, so the critics were not told the test is meant to FAIL now, and one
     * rejected the right test because "the service does not cache yet … loadAll will be called twice" - the defect the
     * test pins. A task that states a NEGATIVE requirement (must not, never, no longer …) for a new test implies RED
     * against the current code.
     */
    static String inferredExpectation(String task, String expectOutcome, List<String> notes) {
        if (expectOutcome != null && !expectOutcome.isBlank()) {
            return expectOutcome;
        }
        if (task != null && task.toLowerCase(Locale.ROOT).matches(
                "(?s).*\\b(must not|should not|shouldn'?t|mustn'?t|never|no longer|not (be )?(re-?)?(load|hash|comput|call|invok|execut)\\w*|only once|at most once)\\b.*")) {
            if (notes != null) {
                notes.add("expectTestOutcome not given — inferred RED from the task's negative requirement");
            }
            return "red";
        }
        return expectOutcome;
    }

    private static final Pattern TDD_CONTRADICTION = Pattern.compile(
            "(?i)(will|would|is going to) (fail|not pass|throw)|does not (yet )?(cache|implement|support|track|remember)"
            + "|not (yet )?implemented|current(ly)?\\s+(implementation|code|behaviou?r)\\s+(does|doesn't|re-?loads|reloads|re-?hashes|recomputes|calls)"
            + "|(called|invoked|executed) (twice|two times|on (each|every) (call|query|invocation)|again)|(reload|re-load|rehash|re-hash)\\w* (on|for) (each|every)");

    /** With RED expected, an issue whose point is that the test fails against today's code is not a defect. */
    static List<String> dropTddContradictions(List<String> issues, String expectOutcome, List<String> transcript) {
        if (issues == null || !"red".equalsIgnoreCase(expectOutcome)) {
            return issues;
        }
        List<String> kept = new ArrayList<>();
        for (String issue : issues) {
            if (TDD_CONTRADICTION.matcher(issue).find()
                    && !issue.toLowerCase(Locale.ROOT).matches("(?s).*\\b(compile|syntax|undeclared|cannot find|wrong (mock|call|method)|vacuous|never (verifies|asserts|checks))\\b.*")) {
                if (transcript != null) {
                    transcript.add("critic issue dropped (RED is expected — it describes the current defect): "
                            + cap(issue.replaceAll("\\s+", " "), 220));
                }
                continue;
            }
            kept.add(issue);
        }
        return kept;
    }

    /**
     * The first javac error's offending line, when it is a stub on a spied real object
     * ({@code doReturn(...).when(spy).call(...)}), is deleted from the method; returns the dropped statement or null.
     */
    static String dropUnresolvableSpyStub(Path file, String methodSpec, String javacTail,
            ClasspathIndex index, SourceIndex sources) {
        if (file == null || methodSpec == null || javacTail == null) {
            return null;
        }
        Matcher m = Pattern
                .compile("error: (cannot find symbol|no suitable method|incompatible types|package [\\w.]+ does not exist)[^\\n]*\\n([^\\n]*)")
                .matcher(javacTail);
        if (!m.find()) {
            return null;
        }
        String line = m.group(2).strip();
        Matcher stub = Pattern
                .compile("^(?:doReturn|doThrow|doAnswer|doNothing|doCallRealMethod)\\(.*\\)\\.when\\((\\w+)\\)\\.\\w+\\(.*\\);$").matcher(line);
        if (!stub.matches()) {
            return null;
        }
        try {
            String source = Files.readString(file);
            String name = stub.group(1);
            if (!source.matches("(?s).*\\b" + Pattern.quote(name) + "\\s*=\\s*spy\\(.*")) {
                return null;
            }
            TreeEditTool.Outcome out = TreeEditTool.apply(source, index, sources, "deleteStatement", methodSpec, null,
                    line, "");
            if (!out.ok()) {
                return null;
            }
            Files.writeString(file, out.rendered());
            return line;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * For every "unreported exception X; must be caught or declared to be thrown" javac reports inside the generated
     * method, X is declared on that method (`throws X`, or appended to its clause). Returns the exceptions added; empty
     * when the tail has none or the method cannot be found.
     */
    static List<String> addMissingThrows(Path file, String methodSpec, String javacTail) {
        List<String> added = new ArrayList<>();
        if (file == null || methodSpec == null || javacTail == null) {
            return added;
        }
        Matcher m = Pattern
                .compile("unreported exception ([\\w.]+); must be caught or declared to be thrown").matcher(javacTail);
        LinkedHashSet<String> exceptions = new LinkedHashSet<>();
        while (m.find()) {
            String ex = m.group(1);
            exceptions.add(ex.contains(".") ? ex.substring(ex.lastIndexOf('.') + 1) : ex);
        }
        if (exceptions.isEmpty()) {
            return added;
        }
        try {
            String source = Files.readString(file);
            String method = methodSpec.contains("#") ? methodSpec.substring(methodSpec.indexOf('#') + 1) : methodSpec;
            Matcher h = Pattern.compile(
                    "([\\w<>\\[\\],.?\\s]+\\s" + Pattern.quote(method) + "\\s*\\([^)]*\\))(\\s*throws\\s+([^{]+?))?\\s*\\{")
                    .matcher(source);
            if (!h.find()) {
                return added;
            }
            String existing = h.group(3) == null ? "" : h.group(3).strip();
            List<String> clause = new ArrayList<>();
            if (!existing.isEmpty()) {
                for (String e : existing.split(",")) {
                    clause.add(e.strip());
                }
            }
            for (String ex : exceptions) {
                boolean present = clause.stream().anyMatch(c -> c.equals(ex) || c.endsWith("." + ex) || c.equals("Exception"));
                if (!present) {
                    clause.add(ex);
                    added.add(ex);
                }
            }
            if (added.isEmpty()) {
                return added;
            }
            String header = h.group(1) + " throws " + String.join(", ", clause) + " {";
            String rendered = source.substring(0, h.start()) + header + source.substring(h.end());
            Files.writeString(file, rendered);
        } catch (IOException e) {
            return List.of();
        }
        return added;
    }

    /**
     * The reflective lookup can only see classes loadable in THIS JVM - hermit does not ship Mockito, JUnit or AssertJ,
     * so `doThrow`/`when`/`assertThat` were never importable although the test project has them. The owners of the
     * statics every test calls unqualified are simply known.
     */
    static final Map<String, String> WELL_KNOWN_STATICS = new HashMap<>();
    static {
        for (String n : List.of("when", "doThrow", "doAnswer", "doReturn", "doNothing", "doCallRealMethod", "mock",
                "spy", "verify", "verifyNoInteractions", "verifyNoMoreInteractions", "times", "never", "atLeast",
                "atLeastOnce", "atMost", "only", "lenient", "inOrder", "reset", "mockStatic", "withSettings",
                "clearInvocations", "ignoreStubs", "timeout")) {
            WELL_KNOWN_STATICS.put(n, "org.mockito.Mockito");
        }
        for (String n : List.of("given", "then", "willReturn", "willThrow", "willAnswer")) {
            WELL_KNOWN_STATICS.put(n, "org.mockito.BDDMockito");
        }
        for (String n : List.of("any", "anyInt", "anyLong", "anyString", "anyBoolean", "anyDouble", "anyList",
                "anyMap", "anySet", "anyCollection", "eq", "argThat", "isNull", "isNotNull", "notNull", "same",
                "refEq", "startsWith", "endsWith", "contains", "matches", "anyIterable", "longThat", "intThat")) {
            WELL_KNOWN_STATICS.put(n, "org.mockito.ArgumentMatchers");
        }
        for (String n : List.of("assertEquals", "assertNotEquals", "assertTrue", "assertFalse", "assertNull",
                "assertNotNull", "assertSame", "assertNotSame", "assertThrows", "assertDoesNotThrow",
                "assertArrayEquals", "assertIterableEquals", "assertInstanceOf", "assertAll", "assertTimeout",
                "fail")) {
            WELL_KNOWN_STATICS.put(n, "org.junit.jupiter.api.Assertions");
        }
        for (String n : List.of("assertThat", "assertThatThrownBy", "assertThatCode", "assertThatNoException",
                "assertThatExceptionOfType", "assertThatIllegalArgumentException",
                "assertThatIllegalStateException", "assertThatNullPointerException", "within", "tuple", "offset")) {
            WELL_KNOWN_STATICS.put(n, "org.assertj.core.api.Assertions");
        }
        for (String n : List.of("requireNonNull", "requireNonNullElse", "requireNonNullElseGet")) {
            WELL_KNOWN_STATICS.put(n, "java.util.Objects");
        }
        for (String n : List.of("emptyList", "emptyMap", "emptySet", "emptyIterator", "singletonList", "singleton",
                "singletonMap", "unmodifiableList", "unmodifiableMap", "unmodifiableSet", "nCopies")) {
            WELL_KNOWN_STATICS.put(n, "java.util.Collections");
        }
    }

    static List<String> addMissingStaticImports(Path file, String javacTail, List<String> ownerNames) {
        List<String> added = new ArrayList<>();
        if (javacTail == null) {
            return added;
        }
        try {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            var m = Pattern.compile("symbol:\\s*method\\s+(\\w+)").matcher(javacTail);
            while (m.find()) {
                names.add(m.group(1));
            }
            if (names.isEmpty()) {
                return added;
            }
            String source = Files.readString(file);
            StringBuilder imports = new StringBuilder();
            for (String name : names) {
                if (source.contains("import static ") && source.contains("." + name + ";")) {
                    continue;   // already imported
                }
                // BDDMockito INHERITS when/doThrow/doAnswer from Mockito, so two owners "declared" them and
                // the import was never added. The DECLARING class is the owner that counts.
                LinkedHashSet<String> owners = new LinkedHashSet<>();
                if (WELL_KNOWN_STATICS.containsKey(name)) {
                    owners.add(WELL_KNOWN_STATICS.get(name));   // known without loading anything
                }
                for (String owner : owners.isEmpty() ? ownerNames : List.<String>of()) {
                    try {
                        Class<?> c = Class.forName(owner);
                        for (Method rm : c.getMethods()) {
                            if (rm.getName().equals(name)
                                    && Modifier.isStatic(rm.getModifiers())) {
                                Class<?> decl = rm.getDeclaringClass();
                                owners.add(decl.getCanonicalName() != null ? decl.getCanonicalName() : decl.getName());
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        // owner not on this classpath
                    }
                }
                if (owners.size() == 1) {
                    String owner = owners.iterator().next();
                    imports.append("import static ").append(owner).append('.').append(name).append(";\n");
                    added.add(owner + "." + name);
                }
            }
            if (added.isEmpty()) {
                return added;
            }
            int at = source.indexOf("\nimport ");
            if (at < 0) {
                added.clear();
                return added;
            }
            Files.writeString(file, source.substring(0, at + 1) + imports + source.substring(at + 1));
        } catch (Exception e) {
            added.clear();
        }
        return added;
    }

    /** Mechanically complete a snippet cut off mid-expression: close an open string, then the open brackets in
     *  reverse order, then terminate with ';' (when the snippet is not a block). Null when nothing to do. */
    static String autoCompleteSnippet(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(code.stripTrailing());
        Deque<Character> open = new ArrayDeque<>();
        boolean inStr = false;
        char quote = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '(' || c == '{' || c == '[') {
                open.push(c);
            } else if ((c == ')' && !open.isEmpty() && open.peek() == '(') || (c == '}' && !open.isEmpty() && open.peek() == '{')
                    || (c == ']' && !open.isEmpty() && open.peek() == '[')) {
                open.pop();
            }
        }
        boolean changed = false;
        if (inStr) {
            sb.append(quote);
            changed = true;
        }
        while (!open.isEmpty()) {
            char o = open.pop();
            sb.append(o == '(' ? ')' : o == '{' ? '}' : ']');
            changed = true;
        }
        String t = sb.toString();
        if (!t.endsWith(";") && !t.endsWith("}")) {
            sb.append(';');
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    /** The Type#method to emulate: explicit param, the edited method when expecting red, else testFilter. */
    private static String emulateSpecFor(String explicit, String methodSpec, String testFilter,
            String expectOutcome) {
        if (expectOutcome == null) {
            return null;
        }
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if ("red".equalsIgnoreCase(expectOutcome) && methodSpec != null && methodSpec.contains("#")) {
            return methodSpec;   // TDD: the method being written IS the test
        }
        if (testFilter != null) {
            String f = testFilter.replace("*", "").trim();
            int lastDot = f.lastIndexOf('.');
            if (lastDot > 0) {
                String method = f.substring(lastDot + 1);
                String rest = f.substring(0, lastDot);
                int classDot = rest.lastIndexOf('.');
                String cls = classDot >= 0 ? rest.substring(classDot + 1) : rest;
                if (!method.isEmpty() && !cls.isEmpty() && Character.isUpperCase(cls.charAt(0))) {
                    return cls + "#" + method;
                }
            }
        }
        return null;
    }

    /**
     * A TEST job aimed at a PRODUCTION file (drive 2G stage 3: file=Thermostat.java, task "write a failing unit
     * test", expectTestOutcome=red). Tests live under a test source set; refuse and point at the right file.
     */
    static String misplacedTestIssue(String task, String fileParam, String templateMethod, String expectOutcome) {
        if (fileParam == null) {
            return null;
        }
        String f = fileParam.replace('\\', '/');
        // only a file under a MAIN source set is production code (flat fixtures / test source sets are fine)
        boolean productionSourceSet = f.contains("/src/main/")
                && !(f.endsWith("Test.java") || f.endsWith("Tests.java") || f.endsWith("IT.java"));
        if (!productionSourceSet) {
            return null;
        }
        String t = task == null ? "" : task.toLowerCase(Locale.ROOT);
        boolean testJob = "red".equalsIgnoreCase(expectOutcome)
                || t.matches("(?s).*\\b(write|create|add|generate)\\b.*\\b(unit )?test\\b.*")
                || (templateMethod != null && templateMethod.matches(".*Tests?#.*"));
        if (!testJob) {
            return null;
        }
        String hint = templateMethod != null && templateMethod.contains("#")
                ? " The test class is " + templateMethod.substring(templateMethod.lastIndexOf('.') + 1, templateMethod.indexOf('#'))
                        + " — pass its file (under src/test/java) as `file` and the NEW test as `method`."
                : " Pass the TEST class's file (under src/test/java) as `file`.";
        return "this is a TEST job, but `file` is a PRODUCTION source (" + f.substring(f.lastIndexOf('/') + 1)
                + "). A test must not be written into production code." + hint + " Nothing was edited.";
    }

    /** A spec-test job in disguise (a suite in the conventions' spec module, or a task naming one): the composer owns
     *  those. */
    static boolean looksLikeSpecTestTask(String task, String fileParam) {
        String f = fileParam == null ? "" : fileParam.replace('\\', '/');
        String module = SpecConventions.load().specModuleMarker();
        if (!module.isEmpty() && f.contains(module) && f.contains("/suites/")) {
            return true;
        }
        String t = task == null ? "" : task.toLowerCase(Locale.ROOT);
        return t.contains("@spectest") || t.contains("spectest(") || t.contains("spec test");
    }

    /** A task copied from the tool documentation, or too generic to author from. */
    private static final Set<String> GENERIC_TASK_WORDS = Set.of("write", "create", "generate",
            "add", "make", "implement", "failing", "unit", "test", "tests", "code", "method", "fix", "bug", "issue",
            "change", "update", "modify", "class", "file", "that", "this", "with", "which", "into", "from", "should");

    /** a task that still carries example placeholders - single capital letters used as words ("stubs X to
     *  throw and asserts Y") or angle-bracket slots ("<collaborator>") - was copied from coaching, not composed. */
    static boolean looksLikePlaceholderTask(String task) {
        if (task == null) {
            return false;
        }
        if (task.matches("(?s).*<[^<>\\n]{1,60}>.*")) {
            return true;
        }
        Matcher m = Pattern.compile("(?<![\\w.#])[A-Z](?![\\w.#(])").matcher(task);
        int singles = 0;
        while (m.find()) {
            singles++;
        }
        return singles >= 2;
    }

    static boolean looksLikeDocEcho(String task) {
        String t = task.toLowerCase(Locale.ROOT).trim().replaceAll("[.!]+$", "");
        // a task must SAY WHAT the code does: "write a failing unit test" / "generate a failing test" carry no
        // behaviour at all - the author has nothing to author from (drives 2B-2G)
        int content = 0;
        for (String w : t.split("[^a-z0-9#.]+")) {
            if (w.length() > 3 && !GENERIC_TASK_WORDS.contains(w)) {
                content++;
            }
        }
        if (content < 3) {
            return true;
        }
        return t.contains("incremental code") || t.contains("emulator gate") || t.contains("parse-tree edit")
                || t.contains("code generation") || t.equals("<what to generate>") || t.contains("<what to");
    }

    /**
     * A bare method name ("testFoo") or a dotted one ("Type.testFoo") becomes {@code Type#testFoo}, the type
     * taken from the file being edited - otherwise the emulator gate and template lookups silently skip
     * (they key on {@code Type#method}).
     */
    /** True for a value copied from a tool description rather than filled in: {@code <Type#method>}, {@code <path>}. */
    static boolean isPlaceholderValue(String value) {
        return value != null && value.strip().matches("(?s)<[^<>]{1,80}>|\\[?<[^<>]{1,80}>\\]?|\\.\\.\\.|…|TODO|TBD");
    }

    /** {@code value}, or null (with a transcript note) when it is a placeholder. */
    static String dropPlaceholder(String value, String param, List<String> notes) {
        if (!isPlaceholderValue(value)) {
            return value;
        }
        notes.add(param + " '" + value.strip() + "' is a placeholder copied from the tool description — ignored");
        return null;
    }

    static String qualifySpec(String spec, String fileParam) {
        if (spec == null || spec.isBlank()) {
            return spec;
        }
        String s = spec.trim();
        // a repair item named the method with its parameter list
        // ("InvoiceTest.testTotalThrowsKeepsTheSubtotal()") and the lookup failed with
        // "method not found" although the method existed. Specs address a method by NAME, never by signature.
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren).trim();
        }
        // the planner wrote the target as `com.x.ReportService$summaryFor`; the `$` was
        // read as part of a package path and the spec became `ReportTest#com.x.…$summaryFor`, so the reach
        // gate looked for a method that cannot exist and two whole rounds ended "inconclusive"
        s = s.replaceFirst("^([\\w.]*[A-Z]\\w*)\\$([a-z]\\w*)$", "$1#$2");
        int colon = s.indexOf(':');
        if (colon > 0 && colon < s.length() - 1 && !s.endsWith(".java") && !s.substring(colon + 1).matches("\\d+")) {
            s = s.substring(0, colon) + "#" + s.substring(colon + 1);   // Type:method is Type#method
        }
        if (s.contains("#")) {
            // a.b.C#m → C#m: file-level lookups (findMethod, template clone, emulate) key on the simple type
            String type = s.substring(0, s.indexOf('#'));
            int lastDot = type.lastIndexOf('.');
            return lastDot >= 0 ? type.substring(lastDot + 1) + s.substring(s.indexOf('#')) : s;
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0 && !s.endsWith(".java")) {
            // Type.method or a.b.Type.method: the segment before the last dot is the (simple) type
            String typePart = s.substring(0, dot);
            String simpleType = typePart.substring(typePart.lastIndexOf('.') + 1);
            if (!simpleType.isEmpty() && Character.isUpperCase(simpleType.charAt(0))) {
                return simpleType + "#" + s.substring(dot + 1);
            }
        }
        if (fileParam == null) {
            return s;
        }
        String fileName = Path.of(fileParam).getFileName().toString();
        String type = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        return type + "#" + s;
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "Error");
        m.put("message", message);
        return m;
    }

    private static void restore(Path file, String originalSource, List<String> transcript) {
        restore(file, originalSource, transcript, null);
    }

    /** Restores the file; when the target method is known its LAST COMMITTED text goes to the transcript first -
     *  a failed round used to wipe the only evidence of what the critics or javac actually saw. */
    private static void restore(Path file, String originalSource, List<String> transcript, String methodSpec) {
        try {
            if (methodSpec != null) {
                String last = focusContext(file, methodSpec);
                if (!last.isBlank()) {
                    transcript.add("last committed code of " + methodSpec + ":\n"
                            + (last.length() > 6000 ? last.substring(0, 6000) + "\n…" : last));
                }
            }
            Files.writeString(file, originalSource);
            CodeGraphTool.invalidate(file);
            transcript.add("file restored to its original content");
        } catch (Exception e) {
            transcript.add("RESTORE FAILED: " + e);
        }
    }

    // ---- authoring (pluggable LLM) --------------------------------------------------------------------------

    private List<Map<String, String>> authorItems(String task, Path file, String methodSpec,
            String templateMethod, String processFeedback, List<String> transcript) throws Exception {
        StringBuilder p = new StringBuilder();
        p.append("You are generating Java code INCREMENTALLY as parse-tree edit items.\n\nTASK: ")
                .append(task).append("\n\n");
        String context = focusContext(file, methodSpec);
        if (!context.isBlank()) {
            p.append("CURRENT CODE (the region being edited):\n").append(context).append("\n\n");
        }
        if (templateMethod != null) {
            String template = focusContext(file, templateMethod);
            if (!template.isBlank()) {
                p.append("AN EXISTING METHOD TO MODEL ON — reuse its setup/helpers/idioms:\n")
                        .append(template).append("\n\n");
            }
        }
        if (!codeUnderTestSection.isBlank()) {
            p.append(codeUnderTestSection.strip()).append("\n\n");
        }
        if (!processFeedback.isBlank()) {
            p.append("A PREVIOUS FULL ATTEMPT FAILED — take this into account:\n").append(processFeedback)
                    .append("\n\n");
        }
        p.append(ITEM_FORMAT);
        return itemsWithRetry(p, "author", transcript);
    }

    private static final String ITEM_FORMAT =
            "Reply with ONLY a JSON array of edit items, applied in order. Each item: "
                + "{\"op\": \"insertAtEnd\"|\"insertAtStart\"|\"insertBefore\"|\"insertAfter\"|"
                + "\"replaceStatement\"|\"replaceExpression\"|\"deleteStatement\"|\"replaceBody\"|\"insertMember\"|"
                + "\"addImport\"|\"sortImports\"|\"spaceMembers\"|\"addType\", \"code\": \"<Java source of the statement/expression/member/"
                + "import/type — for replaceBody: the method's ENTIRE new body>\", \"method\": \"<Type#method>\" "
                + "(statement ops and replaceBody), \"anchor\": \"<existing "
                + "statement/expression text>\" (before/after/replace/delete), \"type\": \"<type name>\" "
                + "(insertMember)}. An item's code may hold ONE statement or a SHORT GROUP of consecutive "
                + "statements — each item is committed and validated separately, so smaller items give "
                + "better error isolation. "
                + "To ADD A NEW METHOD: FIRST one insertMember item with the method skeleton (annotations, "
                + "signature, EMPTY body), THEN items with op=insertAtEnd, method = Type#theNewMethod and "
                + "NO anchor (anchors match existing statements INSIDE a body — never use a method "
                + "signature as an anchor).";

    /**
     * The generated test COMPILED and the whole critic panel rejected it - "the test does not simulate a scenario where
     * the sensor read throws". The template's scenario throws IMPLICITLY (an iterator returning null trips an NPE
     * deep inside), which neither a reviewer nor a reader can see. A task about a throw must make the throw explicit in
     * the test.
     */
    static String explicitThrowRule(String task) {
        if (task == null || !task.toLowerCase(Locale.ROOT).matches("(?s).*\\b(throw|throws|thrown|exception|fails?|failure)\\b.*")) {
            return "";
        }
        return "\n\nTHE TASK IS ABOUT A THROW: the test must make the exercised call throw EXPLICITLY — e.g. "
                + "given(iterator.next()).willThrow(new RuntimeException(\"boom\")) or doThrow(...).when(mock).call() "
                + "— and then assert the behaviour that follows. A null return that merely happens to cause an NPE is "
                + "NOT an explicit simulation: reviewers reject it and readers cannot see it. Keep a comment naming "
                + "which call throws.";
    }

    /**
     * The author never saw the production method it was testing. It kept the template's `lastSetting() →
     * UNSET` stub, and the code under test turns UNSET into `safeLevel = requestedLevel` - a scenario in which the
     * defect is invisible and no assertion can go red. The production method comes from {@code targetReach} when the
     * plan names it, else from the test class's production twin (ThermostatTest → Thermostat) picking the method
     * whose words overlap the task most.
     */
    static String codeUnderTest(CodeGraph graph, String methodSpec, String task, String targetReach,
            List<String> transcript) {
        try {
            String spec = targetReach;
            Node decl = null;
            if (spec != null && spec.contains("#")) {
                var refs = graph.locate(spec);
                if (!refs.isEmpty()) {
                    decl = refs.get(0).decl();
                }
            }
            List<String> specs = new ArrayList<>();
            List<Node> decls = new ArrayList<>();
            if (decl != null) {
                specs.add(spec);
                decls.add(decl);
            } else if (methodSpec != null && methodSpec.contains("#")) {
                String testType = methodSpec.substring(0, methodSpec.indexOf('#'));
                String stem = testType.replaceAll("(Tests?|IT|Spec|TestCase)$", "");
                String twin = stem;
                if (!stem.equals(testType)) {
                    // ReportTest's twin is ReportService, not `Report` - with no
                    // type of the bare stem the code under test was blank, the form offered no collaborator calls, and
                    // the author invented method names for four rounds. A stem with no type of its own resolves to the
                    // source type whose name starts with it (Handler/Impl/Service…), the shortest such name first.
                    if (graph.hierarchy().entries(stem).isEmpty()) {
                        // the SHORTEST name starting with the stem was the generated
                        // `ReportQuery`, whose methods match no task word - the service lost. Candidates are ranked
                        // by the best task-word overlap of their methods, with a conventional production suffix
                        // (Handler, Impl, Service, Manager, Workflow, Store) ahead of anything else; length breaks ties.
                        String best = null;
                        int bestScore = Integer.MIN_VALUE;
                        for (var e : graph.hierarchy().sourceEntries()) {
                            String n = e.name();
                            if (!n.startsWith(stem) || n.equals(testType) || n.matches(".*(Test|Tests|IT|TestCase|Spec)$")) {
                                continue;
                            }
                            int score = n.matches(".*(Handler|Impl|Service|Manager|Workflow|Store|Controller)$") ? 10 : 0;
                            int methodBest = 0;
                            for (var d : graph.hierarchy().allDeclaredMethods(e)) {
                                if (d.decl() != null) {
                                    methodBest = Math.max(methodBest, wordOverlap(d.name(), d.decl().toString(), task));
                                }
                            }
                            score += methodBest * 2 - n.length() / 20;
                            if (best == null || score > bestScore || (score == bestScore && n.length() < best.length())) {
                                best = n;
                                bestScore = score;
                            }
                        }
                        if (best != null) {
                            twin = best;
                            transcript.add("code under test twin: " + testType + " → " + twin);
                        }
                    }
                    // the callee `applyLevel` outscored `adjust`,
                    // whose catch block IS the code under test - when two methods are both relevant, show both
                    List<Object[]> scored = new ArrayList<>();
                    for (var e : graph.hierarchy().entries(twin)) {
                        for (var d : graph.hierarchy().allDeclaredMethods(e)) {
                            if (d.decl() != null) {
                                scored.add(new Object[] {wordOverlap(d.name(), d.decl().toString(), task), d});
                            }
                        }
                    }
                    scored.sort((a, b) -> Integer.compare((int) b[0], (int) a[0]));
                    int best = scored.isEmpty() ? 0 : (int) scored.get(0)[0];
                    for (Object[] sc : scored) {
                        int score = (int) sc[0];
                        var d = (TypeHierarchy.MethodDecl) sc[1];
                        if (score >= 3 && score >= best * 0.6 && decls.size() < 2 && !decls.contains(d.decl())) {
                            specs.add(twin + "#" + d.name());
                            decls.add(d.decl());
                        }
                    }
                }
            }
            if (decls.isEmpty()) {
                transcript.add("code under test: NONE — no targetReach given and no production twin of "
                        + (methodSpec == null ? "?" : methodSpec) + " found; the form cannot list collaborator calls");
                return "";
            }
            StringBuilder out = new StringBuilder("\n\nTHE CODE UNDER TEST (production — read how it DERIVES the values "
                    + "your test controls and asserts; choose stubs that make the defect observable):");
            for (int k = 0; k < decls.size(); k++) {
                String text = cap(decls.get(k).toString().strip(), decls.size() == 1 ? 5000 : 3200);
                transcript.add("code under test: " + specs.get(k) + " (" + text.lines().count() + " lines)");
                out.append("\n\n// ").append(specs.get(k)).append('\n').append(text);
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Name words count 3, body words 1 - the same weighting as the template ranking. */
    private static int wordOverlap(String name, String body, String task) {
        Set<String> words = taskWords(task);
        Set<String> seen = new HashSet<>();
        int score = 0;
        for (String w : name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String st = stem(w);
            if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                score += 3;
            }
        }
        for (String w : body.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String st = stem(w);
            if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                score++;
            }
        }
        return score;
    }

    /**
     * Null when the item is fine; otherwise the coaching for a replaceBody whose new body drops more than 40% of an
     * existing body of at least 8 statements - the dropped statements are named so the author can keep them.
     */
    static String bodyShrinkProblem(String source, String methodSpec, String op, String code, String anchor) {
        try {
            if (!"replaceBody".equals(TreeEditTool.canonicalOp(op, anchor)) || methodSpec == null
                    || !methodSpec.contains("#") || code == null) {
                return null;
            }
            var tree = ParseTree.parse(source);
            String[] tp = methodSpec.split("#", 2);
            var m = tree.findMethod(tp[0], tp[1]);
            var block = m == null ? null : m.child(NodeType.BLOCK);
            if (block == null) {
                return null;
            }
            List<String> before = new ArrayList<>();
            for (var c : block.children()) {
                if (c.type().isStatement()) {
                    before.add(c.toString().strip().replaceAll("\\s+", " "));
                }
            }
            if (before.size() < 8) {
                return null;
            }
            String body = TreeEditor.bodyStatementsOf(code);
            List<String> after = new ArrayList<>();
            for (var n : JavaParser.parseStatementsSnippet(body)) {
                after.add(n.toString().strip().replaceAll("\\s+", " "));
            }
            if (after.size() >= before.size() * 0.6) {
                return null;
            }
            List<String> dropped = new ArrayList<>();
            for (String b : before) {
                String key = b.length() > 40 ? b.substring(0, 40) : b;
                if (after.stream().noneMatch(a -> a.contains(key)) && dropped.size() < 6) {
                    dropped.add(b.length() > 90 ? b.substring(0, 90) + "…" : b);
                }
            }
            return "your replaceBody has " + after.size() + " statements but the current body has " + before.size()
                    + " — a rewrite must KEEP the existing setup and change only what the task requires. Dropped, "
                    + "among others:\n  - " + String.join("\n  - ", dropped)
                    + "\nResend the COMPLETE body including them.";
        } catch (Exception e) {
            return null;   // unparseable code fails validation on its own
        }
    }

    /**
     * The task contrasts two values ("fall back only to safeLevel, never to requestedLevel") and the author's stubs
     * made them EQUAL (UNSET → safeLevel = requestedLevel), so no assertion could tell the behaviours apart and the
     * critics rightly rejected every version. A test of "X, never Y" must set up X ≠ Y.
     */
    static String contrastRule(String task) {
        if (task == null) {
            return "";
        }
        Matcher m = Pattern.compile(
                "(?i)\\b(?:only\\s+to|to|be|equal|return|use)\\s+`?(\\w+)`?\\s*,?\\s*(?:never|not|instead of|rather than)\\s+(?:to\\s+)?`?(\\w+)`?")
                .matcher(task);
        if (!m.find() || m.group(1).equalsIgnoreCase(m.group(2))) {
            return "";
        }
        String x = m.group(1);
        String y = m.group(2);
        return "\n\nTHE TASK CONTRASTS TWO VALUES (" + x + " vs " + y + "): your stubs MUST make them DIFFERENT — if "
                + "they are equal, no assertion can tell the correct behaviour from the defect. Read THE CODE UNDER "
                + "TEST for how each is derived, choose stub values that make " + x + " != " + y + ", declare the "
                + "expected value in the test, and assert the call receives " + x + " (verify(...) with eq(" + x
                + ")). Do not wrap the exercised call in try/catch — the code under test handles the exception.";
    }

    // ---- F208: scenario-form authoring ------------------------------------------------------------------------

    static final String SCENARIO_FORM_FORMAT =
            "Reply with ONLY this JSON object (no code, no prose):\n"
            + "{\"declare\": [{\"name\": \"<local>\", \"expr\": \"<Java expression>\"}],\n"
            + " \"stubs\": [{\"call\": \"<mock.method(args)>\", \"returns\": \"<Java expression>\"}],\n"
            + " \"throwAt\": \"<mock.method(args)>\", \"throwType\": \"RuntimeException\",\n"
            + " \"verifyChoice\": <number of the WRITE to verify from the numbered list, when one is given>,\n"
            + " \"verify\": [\"<mock.method(eq(x), any())>\" (only if no numbered list fits)], \"dropVerify\": [\"<text of an existing verify to remove>\"],\n"
            + " \"spy\": [\"<name of a REAL object whose calls you must stub or verify>\"], \"repeatTrigger\": <N — run the exercised call N times (default 1)>,\n"
            + " \"verifyCallChoice\": <number of the COLLABORATOR CALL whose count the test must pin, when that list is given>}\n"
            + "Rules: `declare` adds locals at the top of the test (declare every expected value you verify against); "
            + "`stubs` REPLACES an existing given(call).willReturn(...) of the same call or adds one; `throwAt` makes "
            + "that call throw (an explicit stub, inserted right after the mock's declaration); `verify` appends "
            + "verify(mock).method(...) AFTER the exercised call; `dropVerify` removes template assertions that no "
            + "longer apply; `spy` wraps a REAL object in a Mockito spy under the SAME name (existing statements then "
            + "hand out the spy) so you may stub/verify it; `repeatTrigger` repeats the exercised call, so a plain "
            + "verify(mock).call(...) fails when the call happens more than once. Use eq(...)/any() as matchers. Every "
            + "expression must compile in this test class.";

    /**
     * The author decides a correct SCENARIO far more reliably than it writes 25 lines of Mockito - its whole bodies
     * died on unclosed parentheses, duplicate mocks and use-before-declaration. It fills a small form (what to declare,
     * stub, throw, verify) and the TOOL turns the form into edits of the cloned test. The critic panel and the emulator
     * still judge the result.
     */
    /** The reason the last scenario form was unusable (an invalid expression), for the re-ask. */
    private static volatile String lastFormProblem = null;
    /** A note about an author stub the derivation rule overrode, for the transcript. */
    static volatile String lastFormNote = null;

    static List<Map<String, String>> itemsFromScenarioForm(String formJson, Path file, String methodSpec) {
        return itemsFromScenarioForm(formJson, file, methodSpec, List.of(), null);
    }

    static List<Map<String, String>> itemsFromScenarioForm(String formJson, Path file, String methodSpec,
            List<String> verifyChoices, String task) {
        lastFormProblem = null;
        lastFormNote = null;
        List<Map<String, String>> items = new ArrayList<>();
        try {
            String json = PlanParser.joinConcatenatedStrings(formJson);
            JsonNode form = MAPPER.readTree(firstJsonObject(json));
            var tree = ParseTree.parse(Files.readString(file));
            String[] tp = methodSpec.split("#", 2);
            var m = tree.findMethod(tp[0], tp[1]);
            var block = m == null ? null : m.child(NodeType.BLOCK);
            if (block == null) {
                return items;
            }
            List<String> stmts = new ArrayList<>();
            for (var c : block.children()) {
                if (c.type().isStatement()) {
                    stmts.add(c.toString().strip());
                }
            }
            String exercised = null;
            for (String st : stmts) {
                // `final var result = subject.find(...)` is the exercised call too -
                // matching only a bare `subject.` prefix made the LAST statement the trigger, so the repeat
                // duplicated `result.titlesOrThrow()` and the verify's times(1) passed against the bug
                if (st.startsWith("subject.") || bareCall(st).startsWith("subject.")) {
                    exercised = st;
                    break;
                }
            }
            if (exercised == null) {
                for (int i = stmts.size() - 1; i >= 0; i--) {
                    String st = stmts.get(i);
                    if (!st.startsWith("verify") && !st.startsWith("assert")) {
                        exercised = st;
                        break;
                    }
                }
            }
            List<String> declares = new ArrayList<>();
            Set<String> declaredNames = new LinkedHashSet<>();
            for (JsonNode d : form.path("declare")) {
                String name = d.path("name").asText("").trim();
                String expr = d.path("expr").asText("").trim().replaceAll(";$", "");
                if (!name.isEmpty() && !expr.isEmpty()) {
                    String bad = badExpression(expr);
                    if (bad != null) {
                        lastFormProblem = bad;
                        return List.of();
                    }
                    // `retryDelayMillis` was declared again although the clone already declares it
                    boolean exists = stmts.stream().anyMatch(st -> st.matches("(?s)(final\\s+)?(var|[A-Z][\\w<>,.\\s]*|int|long|boolean|double)\\s+"
                            + Pattern.quote(name) + "\\s*=.*"));
                    if (!exists) {
                        declares.add("final var " + name + " = " + expr + ";");
                    }
                    declaredNames.add(name);
                }
            }
            // the contrasted value is DERIVED in the code under test from stubbable calls - stub each of them
            // to return the declared value, unless the form stubs that call itself
            // the author's own stub of the derivation call returned the template's UNSET and took
            // precedence, so the declared expectation never became the derived value. The derivation wins: a
            // derivation call is stubbed to the declared value, and a conflicting author stub of it is dropped.
            Map<String, String> derivedFor = new LinkedHashMap<>();   // call → declared name
            if (task != null && codeUnderTestForDerivation != null) {
                for (String name : declaredNames) {
                    for (String call : derivationCalls(codeUnderTestForDerivation, name)) {
                        derivedFor.putIfAbsent(call.replaceAll("\\s+", ""), name);
                    }
                }
            }
            List<String[]> derivedStubs = new ArrayList<>();
            for (var e : derivedFor.entrySet()) {
                derivedStubs.add(new String[] {e.getKey(), e.getValue()});
            }
            if (!declares.isEmpty()) {
                items.add(item("insertAtStart", methodSpec, null, String.join("\n", declares)));
            }
            // a REAL object listed under "spy" is wrapped under the same name before its first use, so the
            // statements that hand it out (given(session.open(...)).willReturn(catalog)) hand out the spy
            Fixture fixture = fixtureOf(file);
            Set<String> spied = new LinkedHashSet<>();
            for (JsonNode sp : form.path("spy")) {
                String name = sp.asText("").trim();
                if (name.isEmpty() || fixture.mocks().contains(name) || declaredNames.contains(name)) {
                    continue;
                }
                String declaration = null;
                String firstUse = null;
                for (String st : stmts) {
                    if (st.matches("(?s)(final\\s+)?(var|[A-Z][\\w<>,.\\s]*)\\s+" + Pattern.quote(name) + "\\s*=.*")) {
                        declaration = st;
                        break;
                    }
                    if (firstUse == null && st.matches("(?s).*\\b" + Pattern.quote(name) + "\\b.*")) {
                        firstUse = st;
                    }
                }
                if (declaration != null) {
                    Matcher dm = Pattern.compile("(?s)^(.*?=\\s*)(.+?);$").matcher(declaration.strip());
                    if (dm.matches()) {
                        items.add(item("replaceStatement", methodSpec, declaration, dm.group(1) + "spy(" + dm.group(2) + ");"));
                    }
                } else if (firstUse != null) {
                    items.add(item("insertBefore", methodSpec, firstUse, "final var " + name + " = spy(this." + name + ");"));
                } else {
                    items.add(item("insertAtStart", methodSpec, null, "final var " + name + " = spy(this." + name + ");"));
                }
                spied.add(name);
            }
            for (JsonNode st : form.path("stubs")) {
                String call = unwrapStubCall(st.path("call").asText("").trim().replaceAll(";$", ""));
                String returns = st.path("returns").asText("").trim().replaceAll(";$", "");
                if (call.isEmpty() || returns.isEmpty()) {
                    continue;
                }
                autoSpy(call, fixture, spied, stmts, methodSpec, items);
                String derivedName = derivedFor.get(call.replaceAll("\\s+", ""));
                if (derivedName != null) {
                    // when the author's stub already returned the declared name, BOTH it and the
                    // derived stub replaced the same anchor and the second could not find it - the derived stub alone
                    // covers a derivation call, whatever the author returned
                    if (!returns.equals(derivedName)) {
                        lastFormNote = "the code under test derives " + derivedName + " from " + call + " — your stub of it ("
                                + returns + ") was replaced by willReturn(" + derivedName + ")";
                    }
                    continue;
                }
                String badCall = badExpression(call);
                String badRet = badExpression(returns);
                if (badCall != null || badRet != null) {
                    lastFormProblem = badCall != null ? badCall : badRet;
                    return List.of();
                }
                String receiver = call.contains(".") ? call.substring(0, call.indexOf('.')).trim() : "";
                // a stub on a spy must not call the real method while being stubbed: doReturn(...).when(spy).call
                String code = spied.contains(receiver)
                        ? "doReturn(" + returns + ").when(" + receiver + ")." + call.substring(call.indexOf('.') + 1) + ";"
                        : "given(" + call + ").willReturn(" + returns + ");";
                String existing = findStatement(stmts, "given(" + call + ")");
                if (existing != null) {
                    items.add(item("replaceStatement", methodSpec, existing, code));
                } else if (exercised != null) {
                    items.add(item("insertBefore", methodSpec, exercised, code));
                } else {
                    items.add(item("insertAtEnd", methodSpec, null, code));
                }
            }
            for (String[] ds : derivedStubs) {
                // the value is derived from SEVERAL calls (a ternary on the stream mode) and only
                // one branch runs, so a strict-stubs test fails with UnnecessaryStubbingException once the assertion
                // passes - alternative derivation stubs are lenient
                String code = derivedStubs.size() > 1
                        ? "lenient().when(" + ds[0] + ").thenReturn(" + ds[1] + ");"
                        : "given(" + ds[0] + ").willReturn(" + ds[1] + ");";
                String existing = findStatement(stmts, "given(" + ds[0] + ")");
                if (existing == null) {
                    existing = findStatement(stmts, "when(" + ds[0] + ")");
                }
                if (existing != null) {
                    items.add(item("replaceStatement", methodSpec, existing, code));
                } else if (exercised != null) {
                    items.add(item("insertBefore", methodSpec, exercised, code));
                }
            }
            String throwAt = form.path("throwAt").asText("").trim().replaceAll(";$", "");
            if (!throwAt.isEmpty()) {
                autoSpy(throwAt, fixture, spied, stmts, methodSpec, items);
                String type = form.path("throwType").asText("RuntimeException").trim();
                if (type.isEmpty()) {
                    type = "RuntimeException";
                }
                String receiver = throwAt.contains(".") ? throwAt.substring(0, throwAt.indexOf('.')).trim() : "";
                // a throw on a spy must not call the real method while being stubbed: doThrow(...).when(spy).call
                String code = spied.contains(receiver)
                        ? "doThrow(new " + type + "(\"boom\")).when(" + receiver + ")." + throwAt.substring(throwAt.indexOf('.') + 1) + ";"
                        : "given(" + throwAt + ").willThrow(new " + type + "(\"boom\"));";
                String declaration = null;
                for (String st : stmts) {
                    if (!receiver.isEmpty() && st.matches("(?s)(final\\s+)?(var|[A-Z][\\w<>,.\\s]*)\\s+"
                            + Pattern.quote(receiver) + "\\s*=.*")) {
                        declaration = st;
                        break;
                    }
                }
                if (declaration != null) {
                    items.add(item("insertAfter", methodSpec, declaration, code));
                } else if (exercised != null) {
                    items.add(item("insertBefore", methodSpec, exercised, code));
                } else {
                    items.add(item("insertAtEnd", methodSpec, null, code));
                }
            }
            // the form made `sensor.readings(...)` throw, so the iterator that call
            // would have returned is never used and the clone's stubs on it (`given(readings.hasNext())…`) failed
            // strict stubs. Stubs on an object produced by the throwing call are unreachable - make them lenient.
            if (!throwAt.isEmpty()) {
                String producer = findStatement(stmts, "given(" + throwAt + ").willReturn(");
                if (producer != null) {
                    Matcher pv = Pattern.compile("willReturn\\(\\s*(\\w+)\\s*\\)\\s*;").matcher(producer);
                    if (pv.find()) {
                        String produced = pv.group(1);
                        for (String st : stmts) {
                            Matcher gm = Pattern.compile(
                                    "^given\\((" + Pattern.quote(produced) + "\\.[^;]*?)\\)\\.will(Return|Answer)\\((.*)\\);$",
                                    Pattern.DOTALL).matcher(st.strip());
                            if (gm.find()) {
                                items.add(item("replaceStatement", methodSpec, st, "lenient().when(" + gm.group(1) + ").then"
                                        + gm.group(2) + "(" + gm.group(3) + ");"));
                            }
                        }
                    }
                }
            }
            for (JsonNode dv : form.path("dropVerify")) {
                String text = dv.asText("").trim();
                String existing = text.isEmpty() ? null : findStatement(stmts, text);
                if (existing != null) {
                    items.add(item("deleteStatement", methodSpec, existing, ""));
                }
            }
            // "repeated queries must not re-load" needs the exercised call to happen more than
            // once - the form says how many times and the tool repeats the bare call before the asserted one
            int repeat = form.path("repeatTrigger").asInt(1);
            JsonNode callChoiceNode = form.path("verifyCallChoice");
            boolean pinsACount = callChoiceNode.isNumber() || (callChoiceNode.isTextual() && callChoiceNode.asText().matches("\\d+"));
            if (repeat < 2 && pinsACount && task != null && task.toLowerCase(Locale.ROOT)
                    .matches("(?s).*\\b(repeat\\w*|again|every time|each time|subsequent\\w*|twice|multiple|several|re-?load\\w*|re-?hash\\w*|re-?comput\\w*)\\b.*")) {
                // a count-pinning verify with a single trigger cannot express "not again"
                repeat = 2;
                String note = "repeatTrigger defaulted to 2 (the requirement is about repeated calls and a call count is pinned)";
                lastFormNote = lastFormNote == null ? note : lastFormNote + "; " + note;
            }
            if (repeat > 1 && exercised != null) {
                String bare = bareCall(exercised);
                StringBuilder rep = new StringBuilder();
                for (int k = 1; k < Math.min(repeat, 6); k++) {
                    rep.append(k > 1 ? "\n" : "").append(bare);
                }
                items.add(item("insertBefore", methodSpec, exercised, rep.toString()));
            }
            List<String> verifies = new ArrayList<>();
            JsonNode callChoice = form.path("verifyCallChoice");
            if (callChoice.isNumber() || (callChoice.isTextual() && callChoice.asText().matches("\\d+"))) {
                List<CallChoice> calls = collaboratorCalls(codeUnderTestForDerivation, fixture, stmts);
                int n = callChoice.asInt();
                if (n >= 1 && n <= calls.size()) {
                    CallChoice chosen = calls.get(n - 1);
                    if (chosen.testObject() == null) {
                        lastFormProblem = "call " + n + " (" + chosen.call() + ") is not held by this test — choose a call "
                                + "whose collaborator this test holds";
                        return List.of();
                    }
                    if (chosen.real() && !spied.contains(chosen.testObject())) {
                        String name = chosen.testObject();
                        String firstUse = null;
                        for (String st : stmts) {
                            if (st.matches("(?s).*\\b" + Pattern.quote(name) + "\\b.*")) {
                                firstUse = st;
                                break;
                            }
                        }
                        items.add(firstUse != null
                                ? item("insertBefore", methodSpec, firstUse, "final var " + name + " = spy(this." + name + ");")
                                : item("insertAtStart", methodSpec, null, "final var " + name + " = spy(this." + name + ");"));
                        spied.add(name);
                    }
                    verifies.add(verifyFromCallChoice(chosen) + ";");
                }
            }
            JsonNode choice = form.path("verifyChoice");
            if (choice.isNumber() || (choice.isTextual() && choice.asText().matches("\\d+"))) {
                String chosen = verifyFromChoice(verifyChoices, choice.asInt(), task);
                if (chosen != null) {
                    verifies.add(chosen + ";");
                }
            }
            for (JsonNode v : form.path("verify")) {
                String call = v.asText("").trim().replaceAll(";$", "");
                if (call.isEmpty()) {
                    continue;
                }
                autoSpy(call.startsWith("verify(")
                        ? call.substring("verify(".length()).replaceFirst("^(\\w+)\\).*$", "$1.x()") : call,
                        fixture, spied, stmts, methodSpec, items);
                if (call.startsWith("verify(")) {
                    verifies.add(call + ";");
                } else if (call.contains(".")) {
                    String mock = call.substring(0, call.indexOf('.'));
                    verifies.add("verify(" + mock + ")." + call.substring(call.indexOf('.') + 1) + ";");
                }
            }
            if (!verifies.isEmpty()) {
                items.add(exercised != null
                        ? item("insertAfter", methodSpec, exercised, String.join("\n", verifies))
                        : item("insertAtEnd", methodSpec, null, String.join("\n", verifies)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return items;
    }

    /**
     * "CatalogImpl has no method 'getBookById'" was answered by the author with
     * getBook, getBookContents, getBookData, load - five guesses over two rounds, never the real lookup/loadAll. A
     * rejection that names a missing method now lists the methods the type HAS, so the next reply picks one.
     */
    static List<String> coachTypeProblems(List<String> problems, CodeGraph graph) {
        if (problems == null || graph == null) {
            return problems;
        }
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            Matcher m = Pattern.compile("(\\w+) has no method '(\\w+)'").matcher(problem);
            String menu = m.find() ? methodMenu(graph, m.group(1)) : "";
            out.add(menu.isEmpty() ? problem : problem + " — the methods " + m.group(1) + " HAS: " + menu
                    + "; use one of these EXACT names (never invent a method)");
        }
        return out;
    }

    /** The methods a type declares or inherits (Object's excluded), as {@code name(arity)} sorted, at most 24. */
    static String methodMenu(CodeGraph graph, String typeName) {
        try {
            TreeSet<String> names = new TreeSet<>();
            for (var e : graph.hierarchy().entries(typeName)) {
                for (var d : graph.hierarchy().visibleMethods(e).values()) {
                    if (d.owner() != null && "java.lang.Object".equals(d.owner().fqn())) {
                        continue;
                    }
                    names.add(d.name() + "(" + d.params() + (d.varargs() ? "+" : "") + ")");
                }
            }
            List<String> list = new ArrayList<>(names);
            if (list.size() > 24) {
                list = new ArrayList<>(list.subList(0, 24));
                list.add("…");
            }
            return String.join(", ", list);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The test fixture - which names are Mockito MOCKS (stubbable, verifiable) and which are REAL objects built with
     * {@code new} (a stub on one compiles and fails at runtime; the author stubbed the real
     * {@code CatalogImpl} in every round). Read from the test class and its superclasses.
     */
    record Fixture(Set<String> mocks, Map<String, String> reals, Map<String, String> mockTypes) {
        Fixture(Set<String> mocks, Map<String, String> reals) {
            this(mocks, reals, Map.of());
        }

        String mockList() {
            return String.join(", ", mocks);
        }

        /** The test object (mock or real) whose type is or implements {@code type}, else null. */
        String objectOfType(String type) {
            if (type == null || type.isBlank()) {
                return null;
            }
            for (var e : mockTypes.entrySet()) {
                if (e.getValue().equals(type) || e.getValue().startsWith(type)) {
                    return e.getKey();
                }
            }
            for (var e : reals.entrySet()) {
                if (e.getValue().equals(type) || e.getValue().startsWith(type)) {
                    return e.getKey();
                }
            }
            return null;
        }

        String realList() {
            List<String> parts = new ArrayList<>();
            reals.forEach((n, t) -> parts.add(n + " (" + t + ")"));
            return String.join(", ", parts);
        }
    }

    private static final Pattern MOCK_FIELD = Pattern.compile(
            "@(?:Mock|Spy)(?:\\([^)]*\\))?\\s+(?:(?:private|protected|public|final|static)\\s+)*([\\w.]+)(?:<[^;=]*>)?\\s+(\\w+)\\s*[=;]");
    private static final Pattern MOCK_LOCAL = Pattern.compile("\\b(\\w+)\\s*=\\s*(?:mock|spy)\\(");
    private static final Pattern REAL_ASSIGN = Pattern.compile("\\b(\\w+)\\s*=\\s*new\\s+([A-Z]\\w*)\\s*[(<]");
    private static final Pattern EXTENDS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+([A-Z]\\w*)");

    static Fixture fixtureOf(Path file) {
        Set<String> mocks = new LinkedHashSet<>();
        Map<String, String> reals = new LinkedHashMap<>();
        Map<String, String> mockTypes = new LinkedHashMap<>();
        Path current = file;
        for (int depth = 0; current != null && depth < 4; depth++) {
            String text;
            try {
                text = Files.readString(current);
            } catch (IOException e) {
                break;
            }
            Matcher m = MOCK_FIELD.matcher(text);
            while (m.find()) {
                mocks.add(m.group(2));
                String type = m.group(1);
                mockTypes.putIfAbsent(m.group(2), type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type);
            }
            m = MOCK_LOCAL.matcher(text);
            while (m.find()) {
                mocks.add(m.group(1));
            }
            m = REAL_ASSIGN.matcher(text);
            while (m.find()) {
                if (!mocks.contains(m.group(1))) {
                    reals.putIfAbsent(m.group(1), m.group(2));
                }
            }
            Matcher ext = EXTENDS.matcher(text);
            current = ext.find() ? findSourceFile(current, ext.group(1) + ".java") : null;
        }
        mocks.forEach(reals::remove);
        return new Fixture(mocks, reals, mockTypes);
    }

    /**
     * The author verified `lookup` - the wrong call - because nothing listed the calls the code under test makes on its
     * collaborators. Each call on a parameter or field receiver is numbered and mapped to the object THIS test holds
     * for it (by the clone's `open(Type.class)…thenReturn(name)` or by type).
     */
    record CallChoice(String call, String receiver, String method, int argCount, String testObject, boolean real) {
        String display() {
            return call + (testObject == null ? " — not held by this test (cannot be verified here)"
                    : " — in this test: " + testObject + (real ? " (REAL; the tool spies it)" : " (mock)"));
        }
    }

    static List<CallChoice> collaboratorCalls(String codeUnderTest, Fixture fixture, List<String> stmts) {
        List<CallChoice> out = new ArrayList<>();
        if (codeUnderTest == null || codeUnderTest.isBlank()) {
            return out;
        }
        // locals declared inside the code under test are not collaborators
        Set<String> locals = new HashSet<>();
        Matcher d = Pattern
                .compile("\\b(?:var|[A-Z][\\w<>,.?\\[\\]]*)\\s+(\\w+)\\s*=").matcher(codeUnderTest);
        while (d.find()) {
            locals.add(d.group(1));
        }
        // parameter types: `final PageArchive pageArchive,`
        Map<String, String> paramTypes = new LinkedHashMap<>();
        Matcher pt = Pattern
                .compile("([A-Z][\\w.]*)(?:<[^>]*>)?\\s+(\\w+)\\s*[,)]").matcher(codeUnderTest);
        while (pt.find()) {
            paramTypes.putIfAbsent(pt.group(2), pt.group(1));
        }
        // the clone's own evidence: given(session.open(Type.class)).willReturn(name)
        Map<String, String> handedOut = new LinkedHashMap<>();
        if (stmts != null) {
            for (String st : stmts) {
                Matcher ho = Pattern
                        .compile("\\(\\s*(\\w+)\\.class\\s*\\)\\s*\\)\\s*\\.(?:thenReturn|willReturn)\\(\\s*(\\w+)\\s*\\)").matcher(st);
                while (ho.find()) {
                    handedOut.putIfAbsent(ho.group(1), ho.group(2));
                }
            }
        }
        Matcher c = Pattern
                .compile("(?<![\\w.])([a-z]\\w*)\\.(\\w+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)").matcher(codeUnderTest);
        Set<String> seen = new HashSet<>();
        while (c.find()) {
            String receiver = c.group(1);
            String method = c.group(2);
            if (locals.contains(receiver) || receiver.equals("this") || receiver.equals("super")) {
                continue;
            }
            String args = c.group(3).replaceAll("\\s+", " ").strip();
            String call = receiver + "." + method + "(" + args + ")";
            if (!seen.add(receiver + "." + method)) {
                continue;
            }
            int argCount = args.isEmpty() ? 0 : splitArgs(args).size();
            String type = paramTypes.get(receiver);
            String testObject = type == null ? null : handedOut.get(type);
            if (testObject == null && fixture != null) {
                testObject = fixture.objectOfType(type);
            }
            boolean real = testObject != null && fixture != null && fixture.reals().containsKey(testObject);
            out.add(new CallChoice(call, receiver, method, argCount, testObject, real));
        }
        return out;
    }

    /** The verify for a numbered collaborator call: {@code verify(object, times(1)).method(any(), …)}. */
    static String verifyFromCallChoice(CallChoice choice) {
        List<String> matchers = new ArrayList<>();
        for (int i = 0; i < choice.argCount(); i++) {
            matchers.add("any()");
        }
        return "verify(" + choice.testObject() + ", times(1))." + choice.method() + "(" + String.join(", ", matchers) + ")";
    }

    /** {@code name.java} beside {@code start}, else anywhere under the nearest {@code java} source root. */
    static Path findSourceFile(Path start, String name) {
        Path dir = start.getParent();
        if (dir == null) {
            return null;
        }
        Path sibling = dir.resolve(name);
        if (Files.exists(sibling)) {
            return sibling;
        }
        Path root = dir;
        for (int i = 0; i < 8 && root != null && !"java".equals(String.valueOf(root.getFileName())); i++) {
            root = root.getParent();
        }
        if (root == null) {
            root = dir;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(f -> f.getFileName().toString().equals(name)).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Told that its stub targeted a REAL object, the author's second reply kept only the spy and dropped every stub and
     * verify - the scenario was lost to a formality. A stub, throw or verify whose receiver is a REAL object of the
     * fixture now spies that object itself (under the same name, before its first use) and keeps the author's scenario;
     * the transcript notes it.
     */
    static void autoSpy(String call, Fixture fixture, Set<String> spied, List<String> stmts, String methodSpec,
            List<Map<String, String>> items) {
        if (call == null || fixture == null || !call.contains(".")) {
            return;
        }
        String receiver = call.substring(0, call.indexOf('.')).trim();
        if (!receiver.matches("\\w+") || spied.contains(receiver) || !fixture.reals().containsKey(receiver)) {
            return;
        }
        String firstUse = null;
        for (String st : stmts) {
            if (st.matches("(?s).*\\b" + Pattern.quote(receiver) + "\\b.*")) {
                firstUse = st;
                break;
            }
        }
        items.add(firstUse != null
                ? item("insertBefore", methodSpec, firstUse, "final var " + receiver + " = spy(this." + receiver + ");")
                : item("insertAtStart", methodSpec, null, "final var " + receiver + " = spy(this." + receiver + ");"));
        spied.add(receiver);
        String note = receiver + " is a REAL " + fixture.reals().get(receiver) + " — spied automatically so the scenario's "
                + "stub/verify on it holds";
        lastFormNote = lastFormNote == null ? note : lastFormNote + "; " + note;
    }

    /** Which form fields a reply used, for the transcript (so a lost scenario is visible without the reply). */
    static String formFieldSummary(String reply) {
        if (reply == null || !reply.contains("{")) {
            return "(unparsable)";
        }
        try {
            JsonNode form = MAPPER.readTree(firstJsonObject(PlanParser.joinConcatenatedStrings(reply)));
            List<String> parts = new ArrayList<>();
            for (String k : List.of("declare", "stubs", "spy", "verify", "dropVerify")) {
                if (form.path(k).isArray() && form.path(k).size() > 0) {
                    parts.add(k + "=" + form.path(k).size());
                }
            }
            for (String k : List.of("throwAt", "verifyChoice", "verifyCallChoice", "repeatTrigger")) {
                if (!form.path(k).isMissingNode() && !form.path(k).asText("").isBlank()) {
                    parts.add(k + "=" + form.path(k).asText());
                }
            }
            return parts.isEmpty() ? "(none)" : String.join(", ", parts);
        } catch (Exception e) {
            return "(unparsable)";
        }
    }

    /** the author wrote the stub's call as `given(catalog.lookup(id))` - the tool wraps it itself, so the item
     *  became `given(given(catalog.lookup(id))).willReturn(...)`: a leading given(...)/when(...) is unwrapped. */
    static String unwrapStubCall(String call) {
        String c = call == null ? "" : call.strip();
        Matcher m = Pattern.compile("^(?:given|when|lenient\\(\\)\\.when|BDDMockito\\.given|Mockito\\.when)\\((.*)\\)$", Pattern.DOTALL).matcher(c);
        while (m.matches()) {
            c = m.group(1).strip();
            m = Pattern.compile("^(?:given|when|lenient\\(\\)\\.when|BDDMockito\\.given|Mockito\\.when)\\((.*)\\)$", Pattern.DOTALL).matcher(c);
        }
        return c;
    }

    /** The exercised statement without its declaration: {@code final var r = subject.find(ctx);} → {@code subject.find(ctx);}. */
    static String bareCall(String statement) {
        if (statement == null) {
            return null;
        }
        String st = statement.strip();
        Matcher m = Pattern
                .compile("^(?:final\\s+)?(?:var|[A-Z][\\w<>,.?\\[\\]\\s]*)\\s+\\w+\\s*=\\s*(.+)$", Pattern.DOTALL)
                .matcher(st);
        String bare = m.matches() ? m.group(1).strip() : st;
        return bare.endsWith(";") ? bare : bare + ";";
    }

    /** The top-level statements of a method, as source text; empty when the method cannot be parsed. */
    static List<String> statementsOf(Path file, String methodSpec) {
        List<String> stmts = new ArrayList<>();
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String[] tp = methodSpec.split("#", 2);
            var m = tree.findMethod(tp[0], tp[1]);
            var block = m == null ? null : m.child(NodeType.BLOCK);
            if (block != null) {
                for (var c : block.children()) {
                    if (c.type().isStatement()) {
                        stmts.add(c.toString().strip());
                    }
                }
            }
        } catch (Exception ignored) {
            // an unparsable file simply offers no statements
        }
        return stmts;
    }

    private static String findStatement(List<String> stmts, String needle) {
        String n = needle.replaceAll("\\s+", "");
        for (String st : stmts) {
            if (st.replaceAll("\\s+", "").contains(n)) {
                return st;
            }
        }
        return null;
    }

    private static Map<String, String> item(String op, String method, String anchor, String code) {
        Map<String, String> it = new LinkedHashMap<>();
        it.put("op", op);
        it.put("method", method);
        if (anchor != null) {
            it.put("anchor", anchor);
        }
        it.put("code", code);
        return it;
    }

    /** Ask the author for the scenario form; null when it produced nothing usable. */
    private List<Map<String, String>> scenarioFormItems(String task, Path file, String methodSpec,
            String templateMethod, String processFeedback, List<String> transcript) {
        StringBuilder p = new StringBuilder();
        p.append("You are describing the SCENARIO of a unit test; a tool will write the Java.\n\nTASK: ").append(task)
                .append("\n\nTHE TEST AS IT STANDS (a clone of an existing test — you change its scenario):\n")
                .append(focusContext(file, methodSpec)).append("\n\n");
        if (!codeUnderTestSection.isBlank()) {
            p.append(codeUnderTestSection.strip()).append("\n\n");
        }
        Fixture fixture = fixtureOf(file);
        if (!fixture.mocks().isEmpty() || !fixture.reals().isEmpty()) {
            p.append("MOCKS in this test (the ONLY objects you may stub or verify): ").append(fixture.mockList())
                    .append("\nREAL objects (built with new — a stub or verify on them FAILS at runtime): ")
                    .append(fixture.realList())
                    .append("\nTo stub or verify a REAL object's calls, list its name under \"spy\".\n\n");
        }
        String testSource = "";
        try {
            testSource = Files.readString(file);
        } catch (Exception ignored) {
            // the ranking then falls back to the whole candidate list
        }
        List<String> choices = rankByTestBody(contrastedWriteCalls(codeUnderTestSection, task, testSource),
                focusContext(file, methodSpec));
        String rules = (explicitThrowRule(task) + contrastRule(task)).strip();
        if (!rules.isBlank()) {
            p.append(rules).append("\n\n");
        }
        if (!choices.isEmpty()) {
            p.append("NUMBERED WRITES in the code under test that receive a contrasted value — set \"verifyChoice\" to the "
                    + "number of the one your test must check (the tool writes the verify with eq(<correct value>)); the "
                    + "list is ordered with the collaborators THIS test already stubs first — prefer them:\n");
            for (int k = 0; k < choices.size(); k++) {
                p.append("  ").append(k + 1).append(". ").append(choices.get(k)).append('\n');
            }
            p.append('\n');
        }
        List<CallChoice> callChoices = collaboratorCalls(codeUnderTestSection, fixture, statementsOf(file, methodSpec));
        transcript.add("form offered " + callChoices.size() + " collaborator call(s), " + choices.size()
                + " contrasted write(s); mocks=" + fixture.mocks().size() + " reals=" + fixture.reals().size());
        if (!callChoices.isEmpty()) {
            p.append("NUMBERED COLLABORATOR CALLS the code under test makes — when the requirement is about how OFTEN "
                    + "something happens (\"not again\", \"every time\", \"only once\"), set \"verifyCallChoice\" to the "
                    + "number of the call whose count the test must pin and \"repeatTrigger\" to 2 or more; the tool writes "
                    + "verify(<object>, times(1)).call(any()…) after the exercised call and spies a REAL object itself:\n");
            for (int k = 0; k < callChoices.size(); k++) {
                p.append("  ").append(k + 1).append(". ").append(callChoices.get(k).display()).append('\n');
            }
            p.append('\n');
        }
        if (!processFeedback.isBlank()) {
            p.append("A PREVIOUS ATTEMPT FAILED — take this into account:\n").append(processFeedback).append("\n\n");
        }
        p.append(SCENARIO_FORM_FORMAT);
        Function<String, String> formLlm = llm;
        if (Boolean.parseBoolean(System.getProperty("hermit.treegen.remoteForm", "false"))) {
            HelperModelTool remote = new HelperModelTool();
            if (remote.isAvailable()) {
                formLlm = prompt -> {
                    String r = remote.complete(prompt, 4000);
                    return r != null ? r : llm.apply(prompt);
                };
                transcript.add("scenario form author: REMOTE " + remote.endpointLabel());
            }
        }
        // ESCALATION (user, 5 Sep): a bigger cloud model (-Dhermit.form.baseUrl/model, key from the cloud-key store)
        // is a LAST RESORT and rate-limited - it authors the form only when the first author's reply was unusable
        // or a previous process round has already failed, never on the first try of a fresh round
        Function<String, String> escalated = escalationFormAuthor(llm, transcript);
        // -Dhermit.form.first=true makes the escalation author write the FIRST form too (a comparison run; one call
        // per round stays well inside a cloud endpoint's rate limit)
        boolean escalateNow = escalated != null && (!processFeedback.isBlank() || Boolean.getBoolean("hermit.form.first"));
        for (int attempt = 1; attempt <= 2; attempt++) {
            Function<String, String> author = (escalateNow || (attempt == 2 && escalated != null)) ? escalated : formLlm;
            if (author == escalated) {
                transcript.add("scenario form author (attempt " + attempt + "): ESCALATED to " + escalationLabel);
            }
            String reply = author.apply(p.toString());
            List<Map<String, String>> items = itemsFromScenarioForm(reply, file, methodSpec, choices, task);
            if (!items.isEmpty()) {
                transcript.add("scenario form → " + items.size() + " item(s): " + items.stream()
                        .map(it -> it.get("op")).toList() + "; fields: " + formFieldSummary(reply)
                        + (lastFormNote == null ? "" : "; " + lastFormNote));
                return items;
            }
            String why = lastFormProblem == null ? "it was not the JSON object described above" : lastFormProblem;
            transcript.add("scenario form unusable (attempt " + attempt + "): " + why + " — reply: "
                    + cap(reply.replaceAll("\\s+", " "), 200));
            p.append("\n\nYOUR LAST REPLY WAS UNUSABLE: ").append(why)
                    .append(". Reply with ONLY the JSON object, keeping every OTHER field of your previous reply unchanged.");
        }
        return null;
    }

    /** Whether the current round's items came from the scenario form (then critics' dissent goes back to it). */
    private static volatile boolean formAuthoredThisRound = false;

    /** The class of a named single-method test whose passing tests a change must keep passing, or null. */
    private String regressionClass;

    /** The tests of {@link #regressionClass} that passed before the change. */
    private Set<String> baselineGreen = Set.of();

    /** "pkg.ClassTest.method" names one method of the class "pkg.ClassTest"; any other filter names no class. */
    static String testClassOf(String testFilter) {
        if (testFilter == null || testFilter.contains("*") || testFilter.contains(" ")) {
            return null;
        }
        int dot = testFilter.lastIndexOf('.');
        if (dot <= 0 || dot == testFilter.length() - 1) {
            return null;
        }
        String method = testFilter.substring(dot + 1);
        String owner = testFilter.substring(0, dot);
        String ownerSimple = owner.substring(owner.lastIndexOf('.') + 1);
        return Character.isLowerCase(method.charAt(0)) && !ownerSimple.isEmpty()
                && Character.isUpperCase(ownerSimple.charAt(0)) ? owner : null;
    }

    /** The newest test report of {@code testClass} under the project, or null. */
    private static Path newestReport(Path project, String testClass) throws IOException {
        Path newest = null;
        try (Stream<Path> walk = Files.walk(project, 6)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (f.toString().contains("/build/test-results/")
                        && f.getFileName().toString().equals("TEST-" + testClass + ".xml")
                        && (newest == null || f.toFile().lastModified() > newest.toFile().lastModified())) {
                    newest = f;
                }
            }
        }
        return newest;
    }

    /**
     * Whether the newest report fails the one test method {@code testFilter} names at an assertion
     * (org.opentest4j.AssertionFailedError or java.lang.AssertionError): the red a new test is meant to have, not an
     * exception from its fixture.
     */
    static boolean redAtAnAssertion(Path project, String testFilter) throws IOException {
        String testClass = testClassOf(testFilter);
        Path report = testClass == null ? null : newestReport(project, testClass);
        if (report == null) {
            return false;
        }
        String method = testFilter.substring(testClass.length() + 1);
        Matcher m = Pattern.compile("<testcase name=\""
                + Pattern.quote(method) + "(?:\\([^\"]*\\))?\"[^>]*?>(.*?)</testcase>",
                Pattern.DOTALL).matcher(Files.readString(report));
        if (!m.find() || m.group(1).contains("<error")) {
            return false;
        }
        Matcher failure = Pattern.compile("<failure[^>]*\\btype=\"([^\"]+)\"")
                .matcher(m.group(1));
        return failure.find() && (failure.group(1).equals("org.opentest4j.AssertionFailedError")
                || failure.group(1).equals("java.lang.AssertionError"));
    }

    /** The passing test methods in the newest report of {@code testClass} under the project (names without "()"). */
    static Set<String> passingTests(Path project, String testClass) throws IOException {
        Path newest = newestReport(project, testClass);
        Set<String> passing = new LinkedHashSet<>();
        if (newest == null) {
            return passing;
        }
        Matcher m = Pattern.compile(
                "<testcase name=\"([^\"]+)\"[^>]*?(/>|>(.*?)</testcase>)", Pattern.DOTALL)
                .matcher(Files.readString(newest));
        while (m.find()) {
            String body = m.group(3) == null ? "" : m.group(3);
            if (!body.contains("<failure") && !body.contains("<error") && !body.contains("<skipped")) {
                passing.add(m.group(1).replaceAll("\\(.*\\)$", ""));
            }
        }
        return passing;
    }

    /** The baseline's passing tests that fail after the change (empty when no baseline was taken). */
    private List<String> regressions(Path project, String module) throws Exception {
        if (regressionClass == null || baselineGreen.isEmpty()) {
            return List.of();
        }
        CliResult run = runTests(project, module, regressionClass);
        if (run.exitCode() == -1) {
            return List.of("(the run of " + regressionClass + " did not finish within " + testTimeoutSeconds() + " s)");
        }
        Set<String> now = passingTests(project, regressionClass);
        List<String> broken = new ArrayList<>();
        for (String name : baselineGreen) {
            if (!now.contains(name)) {
                broken.add(name);
            }
        }
        return broken;
    }

    /** The caller's items as this round committed them (re-authored ones in their committed form), or null. */
    private List<Map<String, String>> committedPresetItems;

    /** Critic dissent after a form-authored round is the form author's to resolve, unless disabled. */
    static boolean criticDissentGoesToTheForm(boolean formAuthored) {
        return formAuthored && Boolean.parseBoolean(System.getProperty("hermit.treegen.formFeedback", "true"));
    }

    private static volatile String escalationLabel = "";
    /** The escalation endpoint's reachability, probed ONCE per process (a probe is a request against its rate limit). */
    private static volatile Boolean escalationReachable = null;

    /** The escalation author (a cloud model) when -Dhermit.form.baseUrl names one that answers; else null. */
    private static Function<String, String> escalationFormAuthor(Function<String, String> llm, List<String> transcript) {
        HelperEndpoint cfg = HelperEndpoint.named("form", null, null, null);
        if (cfg == null || cfg.model() == null) {
            return null;
        }
        HelperModelTool remote = new HelperModelTool(cfg);
        if (escalationReachable == null) {
            escalationReachable = remote.isAvailable();
        }
        if (!escalationReachable) {
            transcript.add("escalation author " + cfg.label() + " not reachable — not used");
            return null;
        }
        escalationLabel = remote.endpointLabel();
        return prompt -> {
            String r = remote.complete(prompt, 4000);
            return r != null ? r : llm.apply(prompt);
        };
    }

    /**
     * The scenario form got the stubs and the throw right and verified the TEMPLATE's call
     * (`display.showLevel`) instead of the write the catch block makes. The code under test
     * names that write: list every call that receives one of the contrasted values so the author verifies one of them.
     */
    /**
     * The stubbable calls a value is DERIVED from in the code under test - {@code X = a.b()},
     * {@code X = cond ? a.b() : c.d()}, {@code var X = a.b()} - each of which the test must stub to return X.
     */
    static List<String> derivationCalls(String codeUnderTest, String name) {
        List<String> out = new ArrayList<>();
        if (codeUnderTest == null || name == null) {
            return out;
        }
        Matcher m = Pattern.compile(
                "(?:var|final\\s+var|[A-Z]\\w*)?\\s*\\b" + Pattern.quote(name) + "\\s*=\\s*([^;]{1,300});").matcher(codeUnderTest);
        while (m.find()) {
            String rhs = m.group(1);
            Matcher c = Pattern.compile(
                    "([a-z]\\w*(?:\\.[a-z]\\w*)+)\\s*\\(([^()]*)\\)").matcher(rhs);
            while (c.find()) {
                String call = c.group(1) + "(" + c.group(2).strip() + ")";
                if (!out.contains(call) && !call.startsWith("log.")) {
                    out.add(call);
                }
            }
        }
        return out;
    }

    /** candidates whose receiver the test body already stubs or declares come first - they are the
     *  collaborators this test actually drives; the others keep their order. */
    static List<String> rankByTestBody(List<String> calls, String testBody) {
        List<String> first = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String call : calls) {
            String receiver = call.contains(".") ? call.substring(0, call.indexOf('.')) : call;
            if (testBody != null && testBody.matches("(?s).*\\b" + Pattern.quote(receiver) + "\\b.*")) {
                first.add(call);
            } else {
                rest.add(call);
            }
        }
        first.addAll(rest);
        return first;
    }

    /** The contrasted-write calls as a list (empty when the task has no contrast or the code none). */
    static List<String> contrastedWriteCalls(String codeUnderTest, String task) {
        return contrastedWriteCalls(codeUnderTest, task, null);
    }

    /**
     * The catch block's `logger.error(…, safeLevel, requestedLevel, e)` receives both contrasted values and was
     * listed as a candidate write - the filter only knew a receiver called `log` - and the author verified a logger the
     * test does not hold. A logging call is never the write, and a verify can only target a collaborator the TEST
     * holds: when the test source is given, receivers it does not mention are dropped.
     */
    static List<String> contrastedWriteCalls(String codeUnderTest, String task, String testSource) {
        List<String> out = new ArrayList<>();
        if (codeUnderTest == null || task == null) {
            return out;
        }
        Matcher m = Pattern.compile(
                "(?i)\\b(?:only\\s+to|to|be|equal|return|use)\\s+`?(\\w+)`?\\s*,?\\s*(?:never|not|instead of|rather than)\\s+(?:to\\s+)?`?(\\w+)`?")
                .matcher(task);
        if (!m.find()) {
            return out;
        }
        String x = m.group(1);
        String y = m.group(2);
        Matcher c = Pattern.compile(
                "([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)").matcher(codeUnderTest);
        while (c.find()) {
            String args = c.group(2);
            if (args.matches("(?s).*\\b(" + Pattern.quote(x) + "|" + Pattern.quote(y) + ")\\b.*")) {
                String call = c.group(1) + "(" + args.replaceAll("\\s+", " ").strip() + ")";
                String receiver = c.group(1).contains(".") ? c.group(1).substring(0, c.group(1).lastIndexOf('.')) : c.group(1);
                String receiverSimple = receiver.contains(".") ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
                String method = c.group(1).substring(c.group(1).lastIndexOf('.') + 1);
                boolean logging = receiverSimple.matches("(?i).*(log|logger|logging).*")
                        || method.matches("(?i)error|warn|info|debug|trace|log");
                boolean held = testSource == null
                        || testSource.matches("(?s).*\\b" + Pattern.quote(receiverSimple) + "\\b.*");
                if (!logging && held && !call.contains("format(") && !out.contains(call)) {
                    out.add(call);
                }
            }
        }
        return out;
    }

    /**
     * Given the candidate writes as prose, the author still verified the template's call. A numbered CHOICE is what a
     * small model answers reliably: {@code verifyChoice: 2} picks a call, and the tool writes
     * {@code verify(mock).method(eq(X), any()…)} - the contrasted argument becomes eq(X), the rest any().
     */
    static String verifyFromChoice(List<String> calls, int choice, String task) {
        if (choice < 1 || choice > calls.size()) {
            return null;
        }
        Matcher m = Pattern.compile(
                "(?i)\\b(?:only\\s+to|to|be|equal|return|use)\\s+`?(\\w+)`?\\s*,?\\s*(?:never|not|instead of|rather than)\\s+(?:to\\s+)?`?(\\w+)`?")
                .matcher(task == null ? "" : task);
        String x = m.find() ? m.group(1) : null;
        String y = x == null ? null : m.group(2);
        String call = calls.get(choice - 1);
        int open = call.indexOf('(');
        String target = call.substring(0, open);
        String args = call.substring(open + 1, call.lastIndexOf(')'));
        String mock = target.substring(0, target.lastIndexOf('.'));
        String method = target.substring(target.lastIndexOf('.') + 1);
        if (mock.contains(".")) {
            mock = mock.substring(mock.lastIndexOf('.') + 1);   // this.heater → heater
        }
        List<String> matchers = new ArrayList<>();
        for (String a : splitArgs(args)) {
            String t = a.strip();
            if (x != null && (t.equals(x) || t.equals(y))) {
                matchers.add("eq(" + x + ")");
            } else {
                matchers.add("any()");
            }
        }
        return "verify(" + mock + ")." + method + "(" + String.join(", ", matchers) + ")";
    }

    private static List<String> splitArgs(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : args.toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString());
        }
        return out;
    }

    static String contrastedWrites(String codeUnderTest, String task) {
        if (codeUnderTest == null || task == null) {
            return "";
        }
        Matcher m = Pattern.compile(
                "(?i)\\b(?:only\\s+to|to|be|equal|return|use)\\s+`?(\\w+)`?\\s*,?\\s*(?:never|not|instead of|rather than)\\s+(?:to\\s+)?`?(\\w+)`?")
                .matcher(task);
        if (!m.find()) {
            return "";
        }
        String x = m.group(1);
        String y = m.group(2);
        LinkedHashSet<String> calls = new LinkedHashSet<>();
        Matcher c = Pattern.compile(
                "([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)").matcher(codeUnderTest);
        while (c.find()) {
            String args = c.group(2);
            if (args.matches("(?s).*\\b(" + Pattern.quote(x) + "|" + Pattern.quote(y) + ")\\b.*")) {
                String call = (c.group(1) + "(" + args.replaceAll("\\s+", " ").strip() + ")");
                if (!call.startsWith("log.") && !call.contains("format(")) {
                    calls.add(call.length() > 140 ? call.substring(0, 140) + "…" : call);
                }
            }
        }
        if (calls.isEmpty()) {
            return "";
        }
        return "\n\nCALLS IN THE CODE UNDER TEST THAT RECEIVE " + x + " OR " + y + " — the WRITE among them is what your "
                + "`verify` must check, with eq(" + x + ") in that argument's position and any() elsewhere (do NOT verify "
                + "an unrelated call the template happened to check):\n  - " + String.join("\n  - ", calls);
    }

    /** A form value that does not parse as an expression is reported instead of producing a broken item. */
    static String badExpression(String expr) {
        try {
            JavaParser.parseExpressionSnippet(expr);
            return null;
        } catch (Exception e) {
            return "`" + expr + "` is not a valid Java expression (" + e.getMessage() + ")";
        }
    }

    /**
     * For a task contrasting X and Y, null when some verify in the body targets one of the writes that receive the
     * contrasted value (with X among its arguments); otherwise the coaching naming those writes.
     */
    static String contrastedVerifyIssue(String body, List<String> writes, String task) {
        if (body == null || task == null || writes == null || writes.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile(
                "(?i)\\b(?:only\\s+to|to|be|equal|return|use)\\s+`?(\\w+)`?\\s*,?\\s*(?:never|not|instead of|rather than)\\s+(?:to\\s+)?`?(\\w+)`?")
                .matcher(task);
        if (!m.find()) {
            return null;
        }
        String x = m.group(1);
        String flat = body.replaceAll("\\s+", "");
        for (String call : writes) {
            String target = call.substring(0, call.indexOf('('));
            String mock = target.substring(0, target.lastIndexOf('.'));
            mock = mock.contains(".") ? mock.substring(mock.lastIndexOf('.') + 1) : mock;
            String method = target.substring(target.lastIndexOf('.') + 1);
            Matcher v = Pattern.compile(
                    "verify\\(" + Pattern.quote(mock) + "[^)]*\\)\\." + Pattern.quote(method)
                    + "\\(([^;]*)\\);").matcher(flat);
            while (v.find()) {
                if (v.group(1).contains(x)) {
                    return null;   // an assertion on the right write with the contrasted value
                }
            }
        }
        return "the task contrasts " + x + " with another value, but no assertion checks a WRITE of it. The writes in the "
                + "code under test are: " + String.join("; ", writes) + ". Add, after the exercised call, verify(<mock>)."
                + "<method>(eq(" + x + "), any()…) for one of them — an assertion on an unrelated call (e.g. the template's) "
                + "fails before AND after the fix and proves nothing.";
    }

    /** Does the emulator POSITIVELY predict the expected outcome (an undecided verdict does not)? */
    static boolean emulatorConfirms(String verdict, String expectOutcome) {
        if (verdict == null || expectOutcome == null) {
            return false;
        }
        return "red".equalsIgnoreCase(expectOutcome) && verdict.startsWith("PREDICTED RED")
                || "green".equalsIgnoreCase(expectOutcome) && verdict.startsWith("PREDICTED GREEN");
    }

    /** The whole-body item is PREFERRED in a first round and REQUIRED once a round has failed. */
    static String wholeBodyRequirement(boolean required, String methodSpec) {
        if (!required) {
            return "";
        }
        return "\n\nREQUIRED THIS ROUND: reply with EXACTLY ONE "
                + "item {\"op\": \"replaceBody\", \"method\": \"" + methodSpec + "\", \"code\": \"<the COMPLETE "
                + "body: every statement in order, declarations BEFORE their uses, no signature>\"}. No other ops.";
    }

    static boolean isSingleWholeBodyItem(List<Map<String, String>> items) {
        if (items == null || items.size() != 1) {
            return false;
        }
        String op = items.get(0).getOrDefault("op", "");
        return "replaceBody".equals(TreeEditTool.canonicalOp(op, items.get(0).get("anchor")));
    }

    /** Anchored repairs failed the same way anchored authoring did - the repair author may rewrite the body. */
    static final String REPAIR_RULES =
            " REPAIR RULES: PREFERRED when more than one line must change — EXACTLY ONE item {\"op\": "
            + "\"replaceBody\", \"method\": \"<METHOD>\", \"code\": \"<the COMPLETE corrected body — every "
            + "statement, no signature>\"}: start from THE CODE AS COMMITTED and fix what the issues name. For a "
            + "single line: fix it with op=replaceStatement (anchor = its exact current text), remove a redundant or "
            + "duplicated statement with op=deleteStatement, and never RE-ADD a statement that already exists — that "
            + "creates duplicates the review panel rejects. Insert only what is genuinely MISSING.";

    /** Repair items for code the critic panel dissented on: edit the COMMITTED method in place. */
    private List<Map<String, String>> authorRepairItems(String task, Path file, String methodSpec,
            String templateMethod, List<String> criticIssues, List<String> transcript) {
        return authorRepairItems(REVIEW_REPAIR_HEADER, task, file, methodSpec, templateMethod, criticIssues, transcript);
    }

    static final String REVIEW_REPAIR_HEADER = "Java code you generated COMPILES but a review panel raised issues.";
    static final String TEST_REPAIR_HEADER = "Java code you generated COMPILES but its named test FAILS. Find the "
            + "cause from the failure, the lines it passed through and what the members your code calls really do.";

    private List<Map<String, String>> authorRepairItems(String header, String task, Path file, String methodSpec,
            String templateMethod, List<String> criticIssues, List<String> transcript) {
        StringBuilder p = new StringBuilder();
        p.append(header).append(" REPAIR it IN PLACE "
                + "with parse-tree edit items — do NOT rewrite it from scratch.\n\nTASK: ")
                .append(task).append("\n\n");
        String context = focusContext(file, methodSpec);
        if (!context.isBlank()) {
            p.append("THE CODE AS COMMITTED:\n").append(context).append("\n\n");
        }
        if (templateMethod != null) {
            String template = focusContext(file, templateMethod);
            if (!template.isBlank()) {
                p.append("AN EXISTING METHOD TO MODEL ON:\n").append(template).append("\n\n");
            }
        }
        if (!codeUnderTestSection.isBlank()) {
            p.append(codeUnderTestSection.strip()).append("\n\n");
        }
        String rules = (explicitThrowRule(task) + contrastRule(task)).strip();
        p.append(rules).append(rules.isBlank() ? "" : "\n\n");
        p.append(TEST_REPAIR_HEADER.equals(header) ? "WHAT FAILED:\n" : "REVIEW ISSUES to address:\n");
        for (String issue : criticIssues) {
            p.append("- ").append(issue).append('\n');
        }
        p.append('\n').append(ITEM_FORMAT).append(REPAIR_RULES.replace("<METHOD>", String.valueOf(methodSpec)));
        return itemsWithRetry(p, "repair author", transcript);
    }

    /**
     * For each {@code file.java:NNN: error:} in the javac output, quote the offending source line and
     * point the repair author at the surgical ops - a 3-bit reliably fixes "replace THIS exact text",
     * not "somewhere a symbol is wrong".
     */
    /**
     * The items applied and the file reached javac, which rejected Mockito misuse
     * ({@code willThrow(...).given(x).next()} instead of {@code given(x.next()).willThrow(...)}) - the TEMPLATE the
     * test was cloned from contains the correct idiom for exactly those symbols. Quote the template's own lines that
     * mention a symbol javac complained about, so the repair copies a working call instead of guessing again.
     */
    static String idiomFromTemplate(Path file, String templateMethod, String javacTail) {
        if (templateMethod == null || javacTail == null) {
            return "";
        }
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String[] tp = templateMethod.contains("#") ? templateMethod.split("#", 2) : null;
            var m = tp == null ? null : tree.findMethod(tp[0], tp[1]);
            if (m == null) {
                return "";
            }
            Set<String> symbols = new LinkedHashSet<>();
            var sm = Pattern.compile("symbol:\\s*(?:method|variable|class)\\s+(\\w+)")
                    .matcher(javacTail);
            while (sm.find()) {
                symbols.add(sm.group(1));
            }
            var am = Pattern.compile("method (\\w+) in class \\w+ cannot be applied").matcher(javacTail);
            while (am.find()) {
                symbols.add(am.group(1));
            }
            if (symbols.isEmpty()) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            for (String line : m.toString().split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    continue;
                }
                for (String sym : symbols) {
                    if (trimmed.contains(sym) && !lines.contains(trimmed)) {
                        lines.add(trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed);
                        break;
                    }
                }
                if (lines.size() >= 6) {
                    break;
                }
            }
            if (lines.isEmpty()) {
                return "";
            }
            return "\n\nThe TEMPLATE method (" + templateMethod + ") uses those same symbols CORRECTLY — copy its "
                    + "idiom instead of inventing a call shape:\n  " + String.join("\n  ", lines);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Javac rejected {@code setLevel(eq(safeLevel))} four rounds running - "required:
     * long,String found: long" - and the template never calls that setter, so F178 had nothing to quote. The
     * CODEBASE does call it: one real call site shows the arity and what to pass.
     */
    static String callSiteExamples(CodeGraph graph, String javacTail) {
        if (graph == null || javacTail == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        var m = Pattern.compile(
                "method (\\w+) in (?:class|interface) (\\w+) cannot be applied to given types;"
                + "[\\s\\S]{0,400}?required: ([^\\n]+)\\n\\s*found: ([^\\n]+)").matcher(javacTail);
        int shown = 0;
        while (m.find() && shown < 3) {
            String method = m.group(1);
            String owner = m.group(2);
            String required = m.group(3).trim();
            String found = m.group(4).trim();
            sb.append("\n\nWRONG ARGUMENTS for ").append(owner).append('#').append(method)
                    .append(" (required: ").append(required).append("; found: ").append(found).append(").");
            List<String> examples = new ArrayList<>();
            try {
                for (CodeGraph.CallerEdge e : graph.callerEdges(owner + "#" + method)) {
                    String line = sourceLine(e.site().file(), e.site().line());
                    if (line != null && !examples.contains(line)) {
                        examples.add(line);
                    }
                    if (examples.size() >= 2) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // no graph answer - the required/found line still helps
            }
            if (!examples.isEmpty()) {
                sb.append(" Real call sites in this codebase — copy the ARGUMENT SHAPE (in a verify(...) use "
                        + "eq(...)/any() per argument):");
                for (String ex : examples) {
                    sb.append("\n  ").append(ex);
                }
            } else {
                sb.append(" Pass every required argument (any() is acceptable for the ones the test does not care about).");
            }
            shown++;
        }
        return sb.toString();
    }

    private static String sourceLine(Path file, int line) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (line >= 1 && line <= lines.size()) {
                String t = lines.get(line - 1).strip();
                return t.length() > 180 ? t.substring(0, 180) + "…" : t;
            }
        } catch (Exception ignored) {
            // unreadable
        }
        return null;
    }

    /** The first `error:` line of a javac tail with its following source line, for the transcript. */
    static String firstJavacError(String javacTail) {
        if (javacTail == null) {
            return "";
        }
        Matcher m = Pattern.compile("error:([^\\n]*)\\n([^\\n]*)").matcher(javacTail);
        return m.find() ? (m.group(1).strip() + " <- " + m.group(2).strip()) : cap(javacTail, 200);
    }

    private static String offendingLines(Path file, String javacTail) {
        StringBuilder sb = new StringBuilder();
        try {
            var m = Pattern
                    .compile(Pattern.quote(file.toString()) + ":(\\d+): error:")
                    .matcher(javacTail);
            List<String> lines = null;
            Set<Integer> seen = new HashSet<>();
            while (m.find() && seen.size() < 8) {
                int ln = Integer.parseInt(m.group(1));
                if (!seen.add(ln)) {
                    continue;
                }
                if (lines == null) {
                    lines = Files.readAllLines(file);
                }
                if (ln - 1 < lines.size()) {
                    sb.append("\nline ").append(ln).append(" is: `").append(lines.get(ln - 1).strip())
                            .append("` — fix it with replaceStatement (anchor = that exact text) or "
                                    + "remove it with deleteStatement");
                }
            }
        } catch (Exception ignored) {
            // enrichment only - the raw javac text is already in the issue
        }
        return sb.toString();
    }

    private List<Map<String, String>> itemsWithRetry(StringBuilder p, String who, List<String> transcript) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String raw = llm.apply(p.toString());
            List<Map<String, String>> items = parseItems(raw);
            if (!items.isEmpty()) {
                transcript.add(who + " proposed " + items.size() + " item(s)");
                return items;
            }
            transcript.add(who + " reply had no parseable item array (attempt " + attempt + ")");
            p.append("\n\nYour previous reply contained no parseable JSON array of items. Reply with ONLY "
                    + "the JSON array, nothing else.");
        }
        return List.of();
    }

    private Map<String, String> reauthorItem(Map<String, String> item, List<String> problems, String task) {
        // the commonest single item failure is code that is ONLY a note. The generic re-ask
        // produced the same note again and again; naming the exact two-line shape the editor accepts is far more
        // targeted than repeating "write the statement itself".
        String code = item.get("code") == null ? "" : item.get("code").strip();
        String commentOnly = code.startsWith("//") && !code.contains("\n") || code.startsWith("/*")
                ? "\n\nYOUR CODE WAS ONLY A NOTE: «" + (code.length() > 120 ? code.substring(0, 120) + "…" : code)
                        + "»\nA note edits nothing. Reply with the SAME item whose `code` is that note, then a "
                        + "NEWLINE (\\n), then the Java STATEMENT it describes — for example:\n"
                        + "  \"code\": \"// stub the iterator to throw\\ngiven(iter.next()).willThrow(new RuntimeException());\"\n"
                        + "The statement must be real Java that compiles in this test class."
                : "";
        String p = "Your parse-tree edit was REJECTED by validation.\n\nTASK: " + task
                + "\n\nTHE ITEM:\n" + toJson(item)
                + "\n\nPROBLEMS (positions refer to the edited file):\n" + String.join("\n", problems)
                + commentOnly
                + "\n\nReply with ONLY the corrected item as ONE JSON object with the same fields"
                + " (or {\"give_up\": \"<reason>\"} if it cannot be fixed).";
        String raw = llm.apply(p);
        try {
            JsonNode n = MAPPER.readTree(firstJsonObject(
                    PlanParser.joinConcatenatedStrings(raw)));
            if (n.has("give_up")) {
                return null;
            }
            Map<String, String> fixed = itemFrom(n);
            if (!fixed.containsKey("op") || !fixed.containsKey("code")) {
                return null;   // not an edit item - treat a confused reply as giving up
            }
            return fixed;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String[] CRITIC_LENSES = {
        "whether the generated code actually accomplishes the stated task",
        "correctness of the inserted logic (control flow, edge cases, contracts)",
        "meaningful assertions and no vacuous checks (when the code is a test)",
    };

    /** One critic per lens; returns the DISSENTING lenses' issues. */
    /** The critic panel may run on the remote helper model - a stronger judge than the local author. */
    private Function<String, String> criticLlm() {
        if (!Boolean.parseBoolean(System.getProperty("hermit.treegen.remoteCritics", "false"))) {
            return llm;
        }
        HelperModelTool remote = new HelperModelTool();
        if (!remote.isAvailable()) {
            return llm;
        }
        return prompt -> {
            String r = remote.complete(prompt, 4000);
            return r != null ? r : llm.apply(prompt);   // local fallback keeps the panel complete
        };
    }

    private List<String> runCritics(String task, String focusText, String expectOutcome) {
        List<String> issues = new ArrayList<>();
        if (focusText == null || focusText.isBlank()) {
            return issues;
        }
        Function<String, String> critic = criticLlm();
        String tdd = "red".equalsIgnoreCase(expectOutcome)
                ? "\n\nTDD CONTEXT: this is a NEW test written BEFORE the fix — it MUST fail (red) "
                + "against the current code, because the current behavior is the bug. Judge whether the "
                + "test CORRECTLY EXPRESSES the DESIRED behavior stated in the task. Do NOT reject it "
                + "because its assertion contradicts today's implementation — that mismatch is the point."
                : "";
        for (String lens : CRITIC_LENSES) {
            String p = "You are a STRICT Java reviewer focusing on " + lens + ".\n\nTASK the code claims to "
                    + "accomplish: " + task + tdd + "\n\nGENERATED CODE:\n" + focusText
                    + "\n\nReject ONLY for a concrete defect from your angle. Respond with ONE JSON object: "
                    + "{\"approved\": true|false, \"issues\": [\"...\"]}.";
            try {
                JsonNode n = MAPPER.readTree(firstJsonObject(critic.apply(p)));
                if (!n.path("approved").asBoolean(false)) {
                    issues.add("[" + lens + "] " + n.path("issues").toString());
                }
            } catch (Exception e) {
                issues.add("[" + lens + "] unparseable verdict");
            }
        }
        // the escalation ladder's reviewer: one more voice, on the rung the code's complexity asks for; no verdict = no objection
        HostedCodeReview.Verdict hosted = HostedCodeReview.review(task, focusText, tdd);
        if (hosted != null && !hosted.approved()) {
            issues.add("[hosted reviewer " + hosted.model() + "] " + hosted.issues());
        }
        return issues;
    }

    /** The primary type of the edited file (its file name). */
    static String fileParamType(Path file) {
        if (file == null) {
            return null;
        }
        String n = file.getFileName().toString();
        return n.endsWith(".java") ? n.substring(0, n.length() - 5) : null;
    }

    /** "Thermostat#applyLevel" → "apply Level" (words for template matching). */
    static String camelWords(String spec) {
        if (spec == null) {
            return "";
        }
        String name = spec.contains("#") ? spec.substring(spec.indexOf('#') + 1) : spec;
        return name.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
    }

    static boolean looksLikeTestTask(String task, Path file) {
        String t = task == null ? "" : task.toLowerCase(Locale.ROOT);
        return t.contains("test") || file.toString().contains("/src/test/");
    }

    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "of",
            "to", "in", "that", "when", "is", "it", "for", "on", "with", "write", "unit", "test", "failing", "one",
            "which", "not", "never", "only", "must", "should", "verifies", "verify", "assert", "asserts");

    /**
     * The {@code @Test} method of the target type whose camel-case name (and body identifiers) share the most
     * words with the task - the closest existing example to clone. Null when nothing overlaps.
     */
    static String autoTemplate(Path file, String methodSpec, String task) {
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String typeName = methodSpec.split("#", 2)[0];
            var type = tree.findType(typeName);
            if (type == null) {
                return null;
            }
            Set<String> words = taskWords(task);
            if (words.isEmpty()) {
                return null;
            }
            String best = null;
            int bestScore = 0;
            var body = type.child(NodeType.CLASS_BODY);
            if (body == null) {
                return null;
            }
            for (var m : body.children(NodeType.METHOD_DECLARATION)) {
                boolean isTest = m.children(NodeType.ANNOTATION).stream()
                        .anyMatch(a -> a.name() != null && a.name().endsWith("Test"));
                if (!isTest || m.name() == null) {
                    continue;
                }
                // a match in the method NAME says what the test is ABOUT; a match somewhere in a long
                // body is weak evidence (every adjust test mentions "adjust"). Name hits count 3, body hits 1.
                Set<String> seen = new HashSet<>();
                int score = 0;
                for (String w : m.name().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT)
                        .split("[^a-z0-9]+")) {
                    String st = stem(w);
                    if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                        score += 3;
                    }
                }
                for (String w : m.toString().replaceAll("([a-z])([A-Z])", "$1 $2")
                        .toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    String st = stem(w);
                    if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = m.name();
                }
            }
            return best == null || bestScore < 3 ? null : typeName + "#" + best;
        } catch (Exception e) {
            return null;
        }
    }

    /** The task-word overlap score of ONE named template (name hits ×3 + body hits), 0 when it shares nothing. */
    static int templateScore(Path file, String templateSpec, String task) {
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String[] parts = templateSpec.split("#", 2);
            var m = tree.findMethod(parts[0], parts[1]);
            if (m == null) {
                return 0;
            }
            Set<String> words = taskWords(task);
            Set<String> seen = new HashSet<>();
            int score = 0;
            for (String w : m.name().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                String st = stem(w);
                if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                    score += 3;
                }
            }
            for (String w : m.toString().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                String st = stem(w);
                if (w.length() > 3 && words.contains(st) && seen.add(st)) {
                    score++;
                }
            }
            return score;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Stemmed content words of a task; camelCase identifiers inside it are split first (readSensorValue →
     *  read, sensor, value) so a method name mentioned in prose matches a test's name/body. */
    static Set<String> taskWords(String task) {
        Set<String> words = new HashSet<>();
        String t = (task == null ? "" : task).replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String w : t.split("[^a-z0-9]+")) {
            if (w.length() > 3 && !STOP_WORDS.contains(w)) {
                words.add(stem(w));
            }
        }
        return words;
    }

    private static String stem(String w) {
        String x = w;
        for (String suf : new String[] {"ations", "ation", "ions", "ion", "able", "ible", "ing", "ed", "es", "s"}) {
            if (x.length() > suf.length() + 3 && x.endsWith(suf)) {
                x = x.substring(0, x.length() - suf.length());
                break;
            }
        }
        // compute/computed → comput, execute/executable/execution → execut
        return x.length() > 4 && x.endsWith("e") ? x.substring(0, x.length() - 1) : x;
    }

    /** Does {@code Type#method} exist in the file's parse tree? */
    static boolean methodExists(Path file, String methodSpec) {
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String[] parts = methodSpec.split("#", 2);
            return tree.findMethod(parts[0], parts[1]) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** An insertMember item holding the template method's text with its name replaced by the new one. */
    static Map<String, String> cloneTemplateItem(Path file, String templateMethod, String newMethodSpec) {
        try {
            var tree = ParseTree.parse(Files.readString(file));
            String[] tp = templateMethod.contains("#") ? templateMethod.split("#", 2)
                    : new String[] {newMethodSpec.split("#", 2)[0], templateMethod};
            var m = tree.findMethod(tp[0], tp[1]);
            if (m == null) {
                return null;
            }
            String code = renameMethod(m.toString().strip(), tp[1], newMethodSpec.split("#", 2)[1]);
            Map<String, String> item = new LinkedHashMap<>();
            item.put("op", "insertMember");
            item.put("type", newMethodSpec.split("#", 2)[0]);
            item.put("code", code);
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    /** Replace the declared name in a method's source text (first {@code oldName(} after the annotations). */
    static String renameMethod(String methodText, String oldName, String newName) {
        return methodText.replaceFirst("\\b" + Pattern.quote(oldName) + "\\s*\\(",
                Matcher.quoteReplacement(newName) + "(");
    }

    /**
     * The frames of the edited file that a red test's failure passed through, from the newest test reports (innermost
     * first, a recursion's repeated frames once), each with its source line.
     */
    static String failurePath(Path project, Path file) {
        String fileName = file.getFileName().toString();
        try {
            List<Path> reports = new ArrayList<>();
            long recent = System.currentTimeMillis() - 15 * 60_000L;
            try (Stream<Path> walk = Files.walk(project, 6)) {
                walk.filter(f -> f.toString().contains("/build/test-results/")
                                && f.getFileName().toString().startsWith("TEST-")
                                && f.getFileName().toString().endsWith(".xml")
                                && f.toFile().lastModified() >= recent)
                        .forEach(reports::add);
            }
            List<String> lines = Files.readAllLines(file);
            Set<String> frames = new LinkedHashSet<>();
            Pattern frame = Pattern.compile(
                    "at [\\w.$]*?\\.(\\w+)\\(" + Pattern.quote(fileName) + ":(\\d+)\\)");
            Pattern body = Pattern.compile("<(failure|error)[^>]*>(.*?)</\\1>",
                    Pattern.DOTALL);
            for (Path report : reports) {
                Matcher b = body.matcher(Files.readString(report));
                while (b.find()) {
                    Matcher m = frame.matcher(b.group(2));
                    while (m.find() && frames.size() < 14) {
                        int n = Integer.parseInt(m.group(2));
                        String text = n >= 1 && n <= lines.size() ? lines.get(n - 1).strip() : "";
                        frames.add(m.group(1) + ", line " + n + ": " + text);
                    }
                }
            }
            return frames.isEmpty() ? "" : "\nTHE FAILURE PASSED THROUGH THESE LINES OF " + fileName
                    + " (innermost first):\n  " + String.join("\n  ", frames);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The generated code called {@code cursor.match(TokenType.COLON)} to consume a colon, but in that file match only
     * looks at the next token. The short members of the edited file that the generated code calls are quoted as the
     * file defines them, so a repair can see what each one really does.
     */
    static String calledMembers(Path file, String focus) {
        if (focus == null || focus.isBlank()) {
            return "";
        }
        try {
            Set<String> called = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\b([a-z]\\w*)\\s*\\(").matcher(focus);
            while (m.find()) {
                called.add(m.group(1));
            }
            called.removeAll(Set.of("if", "for", "while", "switch", "catch", "synchronized", "return",
                    "new", "throw", "super", "this", "assert"));
            var tree = ParseTree.parse(Files.readString(file));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (var method : tree.root().descendants(NodeType.METHOD_DECLARATION)) {
                String name = method.name();
                if (name == null || !called.contains(name)) {
                    continue;
                }
                String text = method.toString().strip();
                String head = text.lines().filter(l -> l.contains(name + "(")).findFirst().orElse("").strip();
                if (head.isEmpty() || focus.contains(head) || text.lines().count() > 15) {
                    continue;   // generated in this round, or too long to quote
                }
                sb.append('\n').append(text).append('\n');
                if (++shown >= 12 || sb.length() > 3500) {
                    break;
                }
            }
            return shown == 0 ? "" : "\nTHE EXISTING MEMBERS YOUR CODE CALLS, AS THIS FILE DEFINES THEM (check what "
                    + "each one really does and returns):" + sb;
        } catch (Exception e) {
            return "";
        }
    }

    private static String focusContext(Path file, String methodSpec) {
        try {
            String source = Files.readString(file);
            if (methodSpec != null) {
                var tree = ParseTree.parse(source);
                String[] parts = methodSpec.contains("#") ? methodSpec.split("#", 2)
                        : new String[] {null, methodSpec};
                var m = parts[0] != null ? tree.findMethod(parts[0], parts[1]) : null;
                if (m != null) {
                    return cap(m.toString().strip(), 6000);
                }
            }
            return cap(source, 6000);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- compile / test -----------------------------------------------------------------------------------

    record CliResult(int exitCode, String output) {
        String tail() {
            return output.length() > 2000 ? "..." + output.substring(output.length() - 2000) : output;
        }
    }

    /** Gradle when the project has a wrapper (+ module), plain javac over the project's sources otherwise. */
    private CliResult compile(Path project, Path file, String module, String classesDir) throws Exception {
        if (Files.isRegularFile(project.resolve("gradlew")) && module != null) {
            String task = GradleModules.task(module, file.toString().contains("/src/test/") ? "compileTestJava"
                    : "compileJava");
            return run(project, 900, project.resolve("gradlew").toString(), "--no-daemon", task, "--offline");
        }
        // javac fallback: the whole-repo source set + classpath overflow the OS argument limit
        // (ARG_MAX), so pass them via an @argfile - javac reads args from a file, no exec-length cap.
        String cp = System.getProperty("java.class.path", "");
        if (classesDir != null && !classesDir.isBlank()) {
            cp = classesDir + File.pathSeparator + cp;
        }
        StringBuilder argfile = new StringBuilder();
        argfile.append("-proc:none\n-d\n").append(quoteArg(
                Files.createTempDirectory("treegen-out").toString())).append('\n');
        argfile.append("-cp\n").append(quoteArg(cp)).append('\n');
        try (Stream<Path> s = Files.walk(project)) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .forEach(p -> argfile.append(quoteArg(p.toString())).append('\n'));
        }
        Path af = Files.createTempFile("treegen-javac", ".args");
        Files.writeString(af, argfile.toString());
        return run(project, 300, "javac", "@" + af);
    }

    /** Quote a javac @argfile entry: wrap in double quotes and escape backslashes so Windows paths survive. */
    private static String quoteArg(String s) {
        if (s.isEmpty()) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\") + "\"";
    }

    /** A real test run is possible: a gradle wrapper and a module (runTests skips with exit 0 otherwise). */
    static boolean testsCanRun(Path project, String module) {
        return Files.isRegularFile(project.resolve("gradlew")) && module != null;
    }

    private CliResult runTests(Path project, String module, String testFilter) throws Exception {
        if (!Files.isRegularFile(project.resolve("gradlew")) || module == null) {
            return new CliResult(0, "(no gradle project/module — test run skipped)");
        }
        return run(project, testTimeoutSeconds(), project.resolve("gradlew").toString(), "--no-daemon",
                GradleModules.task(module, "test"), "--tests", testFilter, "--offline");
    }

    static int testTimeoutSeconds() {
        return Math.max(30, Integer.getInteger("hermit.treegen.testTimeoutSec", 1800));
    }

    static CliResult run(Path dir, int timeoutSec, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuffer out = new StringBuffer();
        // the output was read to its end before the timeout was checked, so a test
        // that loops forever held the round forever. The output is read on its own thread; the timeout bounds the wait.
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // the process was stopped
            }
        }, "treegen-output");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            List<ProcessHandle> children = p.descendants().toList();
            children.forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            reader.join(2000);
            return new CliResult(-1, "TIMEOUT\n" + out);
        }
        reader.join(5000);
        return new CliResult(p.exitValue(), out.toString());
    }

    // ---- JSON plumbing ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> itemsFromParams(Map<String, Object> parameters) {
        Object raw = parameters.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, String> item = new LinkedHashMap<>();
                m.forEach((k, v) -> item.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                out.add(item);
            }
        }
        return out;
    }

    private static List<Map<String, String>> parseItems(String raw) {
        List<Map<String, String>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        // join Java-style concatenated literals ("a\n" + "b") before looking for the array - the author
        // writes them consistently, and they make the whole reply unparseable
        String text = PlanParser.joinConcatenatedStrings(raw);
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode arr = MAPPER.readTree(text.substring(start, end + 1));
                for (JsonNode n : arr) {
                    out.add(itemFrom(n));
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Exception ignored) {
                // fall through to the single-object shape
            }
        }
        // a SINGLE item object (the shape the re-author path uses) is accepted too
        try {
            JsonNode one = MAPPER.readTree(firstJsonObject(text));
            Map<String, String> item = itemFrom(one);
            if (item.containsKey("op") || item.containsKey("code")) {
                out.add(item);
            }
        } catch (Exception ignored) {
            // unparseable - the caller re-asks
        }
        return out;
    }

    private static Map<String, String> itemFrom(JsonNode n) {
        Map<String, String> item = new LinkedHashMap<>();
        n.fieldNames().forEachRemaining(f -> {
            if (n.get(f).isTextual()) {
                item.put(f, n.get(f).asText());
            }
        });
        return item;
    }

    private static String toJson(Map<String, String> item) {
        try {
            return MAPPER.writeValueAsString(item);
        } catch (Exception e) {
            return String.valueOf(item);
        }
    }

    private static String firstJsonObject(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i >= 0 && i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return "{}";
    }

    private static String defaultLlm(String prompt) {
        try {
            HelperChain.Answer a = HelperChain.shared().consult(prompt, "answer", null);
            if (a != null && a.text() != null && !a.text().isBlank()) {
                return a.text();
            }
        } catch (Throwable ignore) {
            // fall through to the local model
        }
        try {
            return new ChatClient().getResponse(prompt);
        } catch (Exception e) {
            return "";
        }
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n// ...(truncated)";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * A re-plan dropped each replaceMember item's own "method"; every item fell back to the call's method, and the code
     * of three members replaced one stub in turn. An item's own target stands; a replaceMember item without one
     * replaces the member its code declares, in the type of the call's method.
     */
    static String itemTarget(Map<String, String> item, String methodSpec) {
        String own = item.get("method");
        if (own != null && !own.isBlank()) {
            return own;
        }
        String code = item.get("code");
        if (code != null && methodSpec != null && methodSpec.contains("#")
                && "replaceMember".equals(TreeEditTool.canonicalOp(item.getOrDefault("op", ""), item.get("anchor")))) {
            String declared = TreeEditTool.memberSpec(methodSpec.substring(0, methodSpec.indexOf('#')), code);
            if (declared != null) {
                return declared;
            }
        }
        return methodSpec;
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
