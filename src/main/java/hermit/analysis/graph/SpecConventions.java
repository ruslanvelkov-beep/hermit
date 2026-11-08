package hermit.analysis.graph;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A project's conventions for end-to-end spec tests, which the spec reach and the spec-test composer read instead of
 * naming one project's classes: the request dispatchers and the records of their handlers, the handlers' entry
 * methods, the functionality type a spec operation's {@code type()} returns, the verb classes, where spec
 * infrastructure lives, and how a composed test is written (its annotation, the runner it returns, the helpers it may
 * use unqualified, the command that runs it), which source sets and which module hold test code, the extra helpers an
 * op may start with, the package marker of generated message classes and the prefix of an id written as a String.
 * The defaults are neutral; a project's own values come from the properties file named by
 * {@code -Dhermit.spec.conventions}: one key per field, lists comma-separated, and each helper as
 * {@code helper.<name>=<import>}.
 */
public record SpecConventions(List<String> dispatchers, List<String> handlerRecords, String handlerSuffix,
        List<String> handlerEntryMethods, String functionalityType, List<String> verbClasses, String queryVerbs,
        List<String> specSourceMarkers, String testAnnotation, String testAnnotationImport, String runner,
        String runnerImport, String suiteHeader, Map<String, String> helperImports, String digestHelper,
        List<String> constantHolders, String specPackage, String testSources, String runCommand,
        String specModuleMarker, List<String> extraTestSourceSets, List<String> extraHelperHeads,
        String generatedPackageMarker, String idPrefix) {

    /** The neutral conventions, used when no properties file is named. */
    public static final SpecConventions DEFAULTS = new SpecConventions(
            List.of("CommandDispatcher", "LookupDispatcher"),
            List.of("CommandHandlers", "LookupHandlers"),
            "Handler",
            List.of("handle", "check", "respond"),
            "OperationKind",
            List.of("CommandVerbs", "LookupVerbs", "SetupVerbs"),
            "LookupVerbs",
            List.of("/spec-tests/", ".scenarios.", "/scenarios/", "/junit/"),
            "SpecTest",
            "example.spec.junit.SpecTest",
            "specTest",
            "example.spec.SpecRunner.specTest",
            "",
            defaultHelpers(),
            "sha256Of",
            List.of("SpecSuite", "Verbs", "SpecRunner", "SpecSetup", "SpecProperties", "SpecValues"),
            "example.spec.",
            "spec-tests/src/main/java",
            "./gradlew test --tests \"%s\"",
            "/spec-tests/",
            List.of(),
            List.of(),
            ".generated.",
            "");

    private static Map<String, String> defaultHelpers() {
        Map<String, String> helpers = new LinkedHashMap<>();
        helpers.put("hex", "static example.spec.util.Hex.hex");
        helpers.put("Payload", "example.spec.util.Payload");
        helpers.put("unhex", "static example.spec.util.Hex.unhex");
        helpers.put("sha256Of", "static example.spec.util.Digests.sha256Of");
        helpers.put("DEFAULT_USER", "static example.spec.suites.SpecSuite.DEFAULT_USER");
        helpers.put("DEFAULT_TIMEOUT", "static example.spec.suites.SpecSuite.DEFAULT_TIMEOUT");
        return helpers;
    }

    /** The conventions of this run: the file {@code -Dhermit.spec.conventions} names, else {@link #DEFAULTS}. */
    public static SpecConventions load() {
        String file = System.getProperty("hermit.spec.conventions", "");
        return file.isBlank() ? DEFAULTS : read(Path.of(file));
    }

    /** The conventions in {@code file}; a key the file leaves out keeps its default. */
    public static SpecConventions read(Path file) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("the spec conventions could not be read from " + file, e);
        }
        SpecConventions d = DEFAULTS;
        Map<String, String> helpers = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames().stream().sorted().toList()) {
            if (key.startsWith("helper.")) {
                helpers.put(key.substring("helper.".length()), p.getProperty(key).strip());
            }
        }
        return new SpecConventions(list(p, "dispatchers", d.dispatchers()),
                list(p, "handlerRecords", d.handlerRecords()),
                p.getProperty("handlerSuffix", d.handlerSuffix()).strip(),
                list(p, "handlerEntryMethods", d.handlerEntryMethods()),
                p.getProperty("functionalityType", d.functionalityType()).strip(),
                list(p, "verbClasses", d.verbClasses()), p.getProperty("queryVerbs", d.queryVerbs()).strip(),
                list(p, "specSourceMarkers", d.specSourceMarkers()),
                p.getProperty("testAnnotation", d.testAnnotation()).strip(),
                p.getProperty("testAnnotationImport", d.testAnnotationImport()).strip(),
                p.getProperty("runner", d.runner()).strip(), p.getProperty("runnerImport", d.runnerImport()).strip(),
                p.getProperty("suiteHeader", d.suiteHeader()).strip(), helpers.isEmpty() ? d.helperImports() : helpers,
                p.getProperty("digestHelper", d.digestHelper()).strip(),
                list(p, "constantHolders", d.constantHolders()), p.getProperty("specPackage", d.specPackage()).strip(),
                p.getProperty("testSources", d.testSources()).strip(),
                p.getProperty("runCommand", d.runCommand()).strip(),
                p.getProperty("specModuleMarker", d.specModuleMarker()).strip(),
                list(p, "extraTestSourceSets", d.extraTestSourceSets()),
                list(p, "extraHelperHeads", d.extraHelperHeads()),
                p.getProperty("generatedPackageMarker", d.generatedPackageMarker()).strip(),
                p.getProperty("idPrefix", d.idPrefix()).strip());
    }

    private static List<String> list(Properties p, String key, List<String> fallback) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                out.add(item.strip());
            }
        }
        return List.copyOf(out);
    }

    /** The imports a composed test needs: its annotation, the runner's return type and the runner itself. */
    public List<String> testImports() {
        return List.of(testAnnotationImport, "java.util.stream.Stream", "org.junit.jupiter.api.DynamicTest",
                "static " + runnerImport);
    }
}
