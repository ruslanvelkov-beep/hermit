package hermit.tool.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunProgramToolTest {

    @Test
    void normalizesBarePythonToPython3() {
        assertEquals("python3 foo.py", RunProgramTool.normalizePython("python foo.py"));
        assertEquals("cd x && python3 a.py", RunProgramTool.normalizePython("cd x && python a.py"));
    }

    @Test
    void normalizePythonLeavesOtherTokensAlone() {
        assertEquals("python3 foo.py", RunProgramTool.normalizePython("python3 foo.py"));    // already 3
        assertEquals("mypython foo", RunProgramTool.normalizePython("mypython foo"));          // substring
        assertEquals("/usr/bin/python x", RunProgramTool.normalizePython("/usr/bin/python x")); // explicit path
    }

    @Test
    void separatesStdoutStderrAndExit() {
        Map<String, Object> r = new RunProgramTool().execute(
                Map.of("command", "echo hello; echo oops 1>&2; exit 3"));
        assertEquals(3, r.get("exitCode"));
        assertEquals("hello", String.valueOf(r.get("stdout")).strip());
        assertEquals("oops", String.valueOf(r.get("stderr")).strip());
        assertTrue((Boolean) r.get("hadOutput"));
    }

    @Test
    void reportsNoOutputExplicitly() {
        Map<String, Object> r = new RunProgramTool().execute(Map.of("command", "true"));
        assertFalse((Boolean) r.get("hadOutput"));
    }

    @Test
    void gradleTestFiltersAreNormalisedHereToo() {
        // `--tests PlaylistTest#method` through RunProgram - gradle wants Class.method
        assertEquals("./gradlew :playlist-core:test --tests PlaylistTest.testShuffle",
                RunProgramTool.effectiveCommand("./gradlew :playlist-core:test --tests PlaylistTest#testShuffle"));
        assertEquals("python3 x.py", RunProgramTool.effectiveCommand("python x.py"));
    }
}
