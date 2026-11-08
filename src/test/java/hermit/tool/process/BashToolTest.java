package hermit.tool.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolTest {

    @TempDir
    Path dir;

    private final BashTool tool = new BashTool();

    @AfterEach
    void clearProps() {
        System.clearProperty("hermit.bash.defaultDir");
        System.clearProperty("hermit.projectDir");
    }

    @Test
    void defaultsToTheProjectDirWhenNoWorkingDirGiven() throws IOException {
        Files.writeString(dir.resolve("marker.txt"), "here");
        System.setProperty("hermit.bash.defaultDir", dir.toString());
        // git/gradle must run in the WORKBENCH, not hermit's own cwd
        Map<String, Object> r = tool.execute(Map.of("command", "pwd && ls marker.txt"));
        assertEquals("Success", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("output")).contains("marker.txt"), r.toString());
        assertTrue(String.valueOf(r.get("output")).contains(dir.getFileName().toString()), r.toString());
    }

    @Test
    void anExplicitWorkingDirStillWins() throws IOException {
        Path other = Files.createTempDirectory("other");
        Files.writeString(other.resolve("only-here.txt"), "x");
        System.setProperty("hermit.bash.defaultDir", dir.toString());
        Map<String, Object> r = tool.execute(Map.of(
                "command", "ls only-here.txt", "workingDir", other.toString()));
        assertEquals("Success", r.get("status"), r.toString());
    }

    // a mkdir-and-printf that created the target file printed nothing, so the context said only
    // "Bash={status=Success, exitCode=0, output=}", and the next plan's critic refused it for never creating the file
    @Test
    void aCommandThatPrintsNothingIsRecordedWithTheCommand() {
        System.setProperty("hermit.bash.defaultDir", dir.toString());
        Map<String, Object> silent = tool.execute(Map.of("command", "mkdir -p a/b && printf 'package a.b;\\n' > a/b/C.java"));
        assertEquals("Success", silent.get("status"), silent.toString());
        assertEquals("mkdir -p a/b && printf 'package a.b;\\n' > a/b/C.java", silent.get("command"), silent.toString());
        Map<String, Object> printing = tool.execute(Map.of("command", "echo hello"));
        assertFalse(printing.containsKey("command"), "a command whose output shows what it did keeps the record short");
    }

    @Test
    void gradleTestFilterHashBecomesDot() {
        // --tests a.b.InvoiceTest#testX matches nothing in gradle
        assertEquals("./gradlew :app:test --tests com.x.InvoiceTest.testFoo",
                BashTool.normalizeGradleTestFilter("./gradlew :app:test --tests com.x.InvoiceTest#testFoo"));
        assertEquals("./gradlew test --tests \"com.x.T.m\" -x :y:test",
                BashTool.normalizeGradleTestFilter("./gradlew test --tests \"com.x.T#m\" -x :y:test"));
        assertEquals("git log -1 --format=%H", BashTool.normalizeGradleTestFilter("git log -1 --format=%H"));
        assertEquals(null, BashTool.normalizeGradleTestFilter(null));
    }

    @Test
    void bareGradleBecomesTheWrapperWhenPresent() throws IOException {
        // "gradle :app:test --tests …" on a machine that only has ./gradlew
        Path dir = Files.createTempDirectory("wb");
        Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\n");
        assertEquals("./gradlew :app:test --tests a.B.c", BashTool.preferGradleWrapper("gradle :app:test --tests a.B.c", dir.toString()));
        assertEquals("cd x && ./gradlew build", BashTool.preferGradleWrapper("cd x && gradle build", dir.toString()));
        assertEquals("./gradlew build", BashTool.preferGradleWrapper("./gradlew build", dir.toString()));
        Path bare = Files.createTempDirectory("nowrapper");
        assertEquals("gradle build", BashTool.preferGradleWrapper("gradle build", bare.toString()));
    }

    @Test
    void commitWithNothingStagedGetsAHint() {
        // `git commit -s -m …` right after the generator changed a file, without git add
        String hint = BashTool.commitHint("git commit -m 'Add failing test' -s",
                "On branch feature-273\nChanges not staged for commit:\n  modified: X.java\nno changes added to commit");
        assertTrue(hint != null && hint.contains("git add"), String.valueOf(hint));
        assertEquals(null, BashTool.commitHint("git commit -s -m 'x'", "[feature-273 abc1234] x\n 1 file changed"));
        assertEquals(null, BashTool.commitHint("git status", "nothing to commit, working tree clean"));
    }

    @Test
    void aBareGradleTestTaskIsScopedToTheConfiguredModule() {
        // the planner ran the whole build's tests for one test
        assertEquals("./gradlew :app:test --tests com.acme.T.m",
                BashTool.scopeGradleTestToModule("./gradlew test --tests com.acme.T.m", ":app"));
        assertEquals("./gradlew :app:test --tests com.acme.T.m",
                BashTool.scopeGradleTestToModule("./gradlew :app:test --tests com.acme.T.m", ":app"), "already scoped");
        assertEquals("./gradlew test --tests com.acme.T.m",
                BashTool.scopeGradleTestToModule("./gradlew test --tests com.acme.T.m", ""), "no module configured");
        assertEquals("./gradlew build", BashTool.scopeGradleTestToModule("./gradlew build", ":app"), "not a test run");
    }

    @Test
    void aFailedTestRunReportsTheFailedTestsFromTheReports() throws Exception {
        // the planner saw only "exit 1" - the UnnecessaryStubbingException was at the end of a
        // long output the tool had truncated
        Path dir = Files.createTempDirectory("gradle-reports");
        Path reports = dir.resolve("clock-app/build/test-results/test");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-com.acme.AlarmClockTest.xml"), """
                <?xml version="1.0"?>
                <testsuite name="com.acme.AlarmClockTest" tests="1" failures="1">
                  <testcase name="advancesOnlyToAlarmTime()" classname="com.acme.AlarmClockTest" time="0.1">
                    <failure message="org.mockito.exceptions.misusing.UnnecessaryStubbingException: \
                Unnecessary stubbings detected. Following stubbings are unnecessary: 1. -&gt; at \
                com.acme.AlarmClockTest.advancesOnlyToAlarmTime(AlarmClockTest.java:879)" type="x">trace</failure>
                  </testcase>
                </testsuite>
                """);
        Path src = dir.resolve("clock-app/src/test/java/com/acme");
        Files.createDirectories(src);
        Files.writeString(src.resolve("AlarmClockTest.java"),
                String.join("\n", Collections.nCopies(878, "// filler"))
                + "\n        given(clock.lastTickTime()).willReturn(alarmTime);\n");
        String text = BashTool.gradleTestFailures(dir,
                "./gradlew :app:test --tests com.acme.AlarmClockTest.advancesOnlyToAlarmTime");
        assertTrue(text.contains("TEST FAILED: advancesOnlyToAlarmTime()"), text);
        assertTrue(text.contains("UnnecessaryStubbingException"), text);
        // the statement at the reported location is quoted - the exact anchor to copy
        assertTrue(text.contains("AlarmClockTest.java:879 → given(clock.lastTickTime()).willReturn(alarmTime);"),
                text);
        assertEquals("", BashTool.gradleTestFailures(dir, "./gradlew build"), "not a test run");
    }

    @Test
    void inPlaceTextEditsOfJavaSourcesAreRefused() {
        // `sed -i` on a test source, malformed, instead of the edit tool
        assertTrue(BashTool.IN_PLACE_SOURCE_EDIT.matcher("sed -i '' 's/given(x)/lenient().when(x)/' src/test/java/A.java").find());
        assertTrue(BashTool.IN_PLACE_SOURCE_EDIT.matcher("perl -pi -e 's/a/b/' Foo.java").find());
        assertFalse(BashTool.IN_PLACE_SOURCE_EDIT.matcher("grep -n 'given' src/test/java/A.java").find());
        // a read-only awk whose program compares with >= is not an edit
        assertFalse(BashTool.editsJavaSourceInPlace("grep -n \"parseHeader\" /wb/CsvReader.java; awk "
                + "'/parseHeader\\(/ {f=1; start=NR-3} f && NR<=157 {print NR\": \"$0; count++} count>=60 {exit}' "
                + "/wb/CsvReader.java"));
        assertFalse(BashTool.editsJavaSourceInPlace("awk '{print}' A.java > /tmp/out.txt"), "a redirect elsewhere is not an edit of A.java");
        assertTrue(BashTool.editsJavaSourceInPlace("awk '{print}' A.java > A.java"));
        assertTrue(BashTool.editsJavaSourceInPlace("awk '{gsub(/x/,\"y\")} 1' A.java >> B.java"));
        assertTrue(BashTool.editsJavaSourceInPlace("sed -i '' 's/a/b/' src/test/java/A.java"));
        // sed -i on a ledger and a .java path in a later git add, in one command
        assertFalse(BashTool.editsJavaSourceInPlace("sed -i '' 's/| green: 3 of 4 |/| green: 4 of 4 |/' docs/TEST-LEDGER.md "
                + "&& git add src/main/java/A.java docs/TEST-LEDGER.md"));
        assertTrue(BashTool.editsJavaSourceInPlace("sed -i '' 's/a|b;c/d/' src/A.java"), "separators inside the expression");
        assertTrue(BashTool.editsJavaSourceInPlace("cd src && sed -i '' 's/x/y/' \"A.java\""));
        assertFalse(BashTool.IN_PLACE_SOURCE_EDIT.matcher("./gradlew :app:test --tests com.acme.T").find());
        Map<String, Object> r = new BashTool().execute(Map.of("command", "sed -i 's/a/b/' /tmp/nope/X.java"));
        assertEquals("Error", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("TreeEdit"), r.toString());
    }

    @Test
    void compileErrorsAreSurfacedInTheirOwnKey() {
        // the javac line sat beyond the capped output
        String output = "> Task :m:compileJava FAILED\n/w/library/x/CatalogLookup.java:155: error: incompatible types: "
                + "String cannot be converted to Optional<Book>\n"
                + "    return cache.get(isbn);\n           ^\n1 error\nFAILURE: Build failed";
        String errors = BashTool.compileErrors(output);
        assertTrue(errors.startsWith("=== compile errors ==="), errors);
        assertTrue(errors.contains("CatalogLookup.java:155: incompatible types: "
                + "String cannot be converted to Optional<Book> "
                + "<- return cache.get(isbn);"), errors);
        assertEquals("", BashTool.compileErrors("> Task :m:test FAILED\n1 test completed, 1 failed"));
    }

    @Test
    void aGradleResultCarriesItsCommandAndACompactBuildSummary() {
        // two green runs, and the gate saw none - the verdict sat beyond the 1200-char view
        String out = "> Task :library-api:generateSources UP-TO-DATE\n> Task :library-loans-impl:test\n"
                + "BUILD SUCCESSFUL in 41s\n";
        assertEquals(":library-loans-impl:test → exit 0, BUILD SUCCESSFUL [:library-loans-impl:test ran]",
                BashTool.buildSummary("./gradlew :library-loans-impl:test --tests LoanDeskTest.t", 0, out));
        String failed = "> Task :library-loans-impl:test FAILED\n3 tests completed, 1 failed\nBUILD FAILED in 40s\n";
        assertEquals(":library-loans-impl:test → exit 1, BUILD FAILED, 3 tests completed, 1 failed "
                + "[:library-loans-impl:test FAILED]",
                BashTool.buildSummary("./gradlew :library-loans-impl:test", 1, failed));
    }

    // a 20k-char `cat` reached the planner as its first 600 chars with nothing said
    @Test
    void aLongOutputSaysHowMuchThePlannerSeesAndHowToReadTheRest() throws IOException {
        Path f = dir.resolve("Big.java");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 400; i++) {
            sb.append("line ").append(i).append(" of the file under test;\n");
        }
        Files.writeString(f, sb.toString());
        Map<String, Object> r = tool.execute(Map.of("command", "cat " + f, "workingDir", dir.toString()));
        assertEquals("Success", r.get("status"));
        String note = String.valueOf(r.get("outputNote"));
        assertTrue(note.startsWith("this output is 401 lines"), note);
        assertTrue(note.contains("shows only its first ~") && note.contains("sed -n 'A,Bp' " + f), note);
        // a short output carries no note
        assertFalse(tool.execute(Map.of("command", "echo short", "workingDir", dir.toString())).containsKey("outputNote"));
        assertTrue(BashTool.outputNote("cat x.txt", "a\nb").contains("x.txt"));
    }

    // a plain range read runs numbered, so a citation into it can be exact
    @Test
    void aPlainRangeReadRunsWithItsRealLineNumbers() throws IOException {
        assertEquals("cat -n /w/a/CsvReader.java | sed -n '150,250p'",
                BashTool.numberedRead("sed -n '150,250p' /w/a/CsvReader.java"));
        assertEquals("cat -n library/x/ShelfIndex.java | sed -n '30,80p'",
                BashTool.numberedRead("sed -n 30,80p library/x/ShelfIndex.java"));
        assertEquals("cat -n \"/w/a b/X.java\" | sed -n '1,5p'", BashTool.numberedRead("  sed -n \"1,5p\" \"/w/a b/X.java\" "));
        // anything richer than one range of one file is left alone
        assertEquals("sed -n '1,5p' A.java B.java", BashTool.numberedRead("sed -n '1,5p' A.java B.java"));
        assertEquals("sed -n '1,5p' A.java | grep x", BashTool.numberedRead("sed -n '1,5p' A.java | grep x"));
        assertEquals("sed -n '/foo/,/bar/p' A.java", BashTool.numberedRead("sed -n '/foo/,/bar/p' A.java"));
        assertEquals("sed -i 's/a/b/' A.java", BashTool.numberedRead("sed -i 's/a/b/' A.java"));
        assertEquals("grep -n foo A.java", BashTool.numberedRead("grep -n foo A.java"));
        Path src = dir.resolve("Lines.java");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("line number ").append(i).append(" text\n");
        }
        Files.writeString(src, sb.toString());
        Map<String, Object> r = tool.execute(Map.of("command", "sed -n '3,4p' " + src));
        assertEquals("Success", r.get("status"), r.toString());
        String out = String.valueOf(r.get("output"));
        assertTrue(out.matches("(?s)\\s*3\\tline number 3 text\\n\\s*4\\tline number 4 text\\n?"), out);
    }
}
