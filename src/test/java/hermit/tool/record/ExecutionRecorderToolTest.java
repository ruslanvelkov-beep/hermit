package hermit.tool.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A hallucinated test method is refused instantly with the real @Test methods. */
class ExecutionRecorderToolTest {

    @TempDir
    Path dir;

    @BeforeEach
    void fixture() throws IOException {
        Files.writeString(dir.resolve("PlaylistTest.java"), """
                package w;
                class PlaylistTest {
                    @Test void shuffleKeepsEveryTrackOnce() { }
                    @Test void skipPastTheLastTrackWrapsToTheFirst() { }
                    void helper() { }
                }
                """);
    }

    @Test
    void existingMethodPasses() {
        assertNull(ExecutionRecorderTool.missingTestMethod(dir.toString(), "w.PlaylistTest",
                "shuffleKeepsEveryTrackOnce"));
    }

    @Test
    void hallucinatedMethodIsRefusedWithTheRealOnes() {
        String msg = ExecutionRecorderTool.missingTestMethod(dir.toString(), "w.PlaylistTest",
                "testShuffleTracks");
        assertTrue(msg.contains("does not exist"), msg);
        assertTrue(msg.contains("shuffleKeepsEveryTrackOnce"), msg);
        assertTrue(msg.contains("skipPastTheLastTrackWrapsToTheFirst"), msg);
        assertTrue(!msg.contains("helper"), "non-@Test methods are not offered: " + msg);
    }

    @Test
    void toolRefusesBeforeRunningGradle() {
        Map<String, Object> r = new ExecutionRecorderTool().execute(Map.of(
                "testClass", "w.PlaylistTest", "testMethod", "testShuffleTracks",
                "projectDir", dir.toString()));
        assertEquals("Error", r.get("status"), r.toString());
        assertTrue(String.valueOf(r.get("message")).contains("Real @Test methods"), r.toString());
    }

    @Test
    void unknownClassIsNamed() {
        String msg = ExecutionRecorderTool.missingTestMethod(dir.toString(), "w.Nope", "x");
        assertTrue(msg.contains("not in the project sources"), msg);
    }

    @Test
    void fabricatedPackageIsRefusedEvenWithoutAMethodAndTheRealFqnOffered() {
        String msg = ExecutionRecorderTool.missingTestMethod(
                dir.toString(), "com.example.PlaylistTest", null);
        assertTrue(msg.contains("not in the project sources"), msg);
        assertTrue(msg.contains("w.PlaylistTest"), "the real FQN is offered: " + msg);
        assertNull(ExecutionRecorderTool.missingTestMethod(dir.toString(), "w.PlaylistTest", null));
    }

    @Test
    void zeroRecordStatsMeanAnEmptyRecording() {
        assertTrue(ExecutionRecorderTool.isEmptyRecording(
                "records: 0\nper type:\nper thread:\nblob bytes total: 0\ntop methods by ENTER count:\n"));
        assertTrue(ExecutionRecorderTool.isEmptyRecording(""));
        assertTrue(!ExecutionRecorderTool.isEmptyRecording("records: 48213\nper type: ENTER=12000 ..."));
    }

    @Test
    void includeIsWidenedToTheTestAndTriggerPackages() {
        String widened = ExecutionRecorderTool.widenInclude(
                "com.example.media.player.", dir.toString(),
                "w.PlaylistTest", "w.Playlist#nextTrack");
        assertTrue(widened.contains("com.example.media.player."), widened);
        assertTrue(widened.contains("w."), "the test class's package is added: " + widened);
        // already covered → unchanged
        assertEquals("w.", ExecutionRecorderTool.widenInclude(
                "w.", dir.toString(), "w.PlaylistTest", "PlaylistTest#x"));
    }
}
