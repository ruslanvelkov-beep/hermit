package hermit.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CheckpointTest {

    static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + ": " + out);
    }

    static Path repoWithOneCommit() throws Exception {
        Path repo = Files.createTempDirectory("wb");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        git(repo, "add", "A.java");
        git(repo, "commit", "-q", "-m", "base");
        return repo;
    }

    @Test
    void stepsAreNumberedAcrossTheRunAndReadBack() throws IOException {
        Path run = Files.createTempDirectory("run");
        Checkpoint cp = Checkpoint.at(run, false, null, null);
        TurnSnapshot s = TurnSnapshotTest.sample();
        Path p1 = cp.write(s, "plan");
        Path p2 = cp.write(s, "executed");
        assertEquals("step-0001-plan", p1.getFileName().toString());
        assertEquals("step-0002-executed", p2.getFileName().toString());
        assertEquals("executed", Checkpoint.read(p2).seam);
        assertNotNull(Checkpoint.read(p2).createdAt);
        cp.note(p2, "results.json", "{}");
        assertTrue(Files.exists(p2.resolve("results.json")));
        // a new process on the same run directory continues the numbering
        Checkpoint again = Checkpoint.at(run, false, null, null);
        assertEquals("step-0003-plan", again.write(s, "plan").getFileName().toString());
        assertEquals(3, Checkpoint.steps(run).size());
        // the plan step a retry re-enters
        assertEquals(p1, Checkpoint.planStepBefore(p2));
        // off: nothing written, notes ignored
        Checkpoint off = Checkpoint.off();
        assertNull(off.write(s, "plan"));
        off.note(null, "x", "y");
        assertEquals(Checkpoint.Control.CONTINUE, off.control(null, "executed", 1));
    }

    @Test
    void stopAtStopsWithoutWaitingAndPauseWaitsForTheControlFile() throws Exception {
        Path run = Files.createTempDirectory("run");
        // stopAt without pausing: iteration 1 continues, iteration 2 stops
        Checkpoint stopping = Checkpoint.at(run, false, null, "executed:2");
        Path step = stopping.write(new TurnSnapshot(), "executed");
        assertEquals(Checkpoint.Control.CONTINUE, stopping.control(step, "executed", 1), "iteration 1 is not the stop point");
        assertTrue(stopping.stopsAt("executed", 2));
        assertFalse(stopping.stopsAt("answer", 2));
        Path step2 = stopping.write(new TurnSnapshot(), "executed");
        assertEquals(Checkpoint.Control.STOP, stopping.control(step2, "executed", 2));
        assertTrue(Files.exists(step2.resolve("stopped")));

        // pause: a thread answers the waiting step with retry
        Path step3 = stopping.write(new TurnSnapshot(), "answer");
        Thread answerer = new Thread(() -> {
            try {
                while (!Files.exists(step3.resolve("waiting"))) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                Checkpoint.control(step3, Checkpoint.Control.RETRY);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        answerer.start();
        assertEquals(Checkpoint.Control.RETRY, Checkpoint.waitForControl(step3, Duration.ofSeconds(20)));
        answerer.join(5000);
        assertTrue(Files.readString(step3.resolve("controlled")).startsWith("retry"));
        assertFalse(Files.exists(step3.resolve("waiting")));
        // a timeout without a control file continues
        Path step4 = stopping.write(new TurnSnapshot(), "answer");
        assertEquals(Checkpoint.Control.CONTINUE, Checkpoint.waitForControl(step4, Duration.ofMillis(600)));
    }

    @Test
    void workbenchSnapshotsRestoreTrackedUntrackedAndDeletedFiles() throws Exception {
        Path repo = repoWithOneCommit();
        Path run = Files.createTempDirectory("run");
        Checkpoint cp = Checkpoint.at(run, false, repo, null);
        // an uncommitted edit and an untracked file are part of the snapshot
        Files.writeString(repo.resolve("A.java"), "class A { int x; }\n");
        Files.writeString(repo.resolve("New.java"), "class New {}\n");
        TurnSnapshot s = new TurnSnapshot();
        Path plan = cp.write(s, "plan");
        String tree = Checkpoint.read(plan).workbenchTree;
        assertNotNull(tree);
        assertEquals(40, tree.length());
        // the repo's own index and HEAD are untouched by the snapshot
        Process st = new ProcessBuilder("git", "-C", repo.toString(), "status", "--short").start();
        String status = new String(st.getInputStream().readAllBytes());
        assertTrue(status.contains(" M A.java") && status.contains("?? New.java"), status);

        // the iteration then edits further, adds and deletes files
        Files.writeString(repo.resolve("A.java"), "class A { int x; int y; }\n");
        Files.writeString(repo.resolve("Later.java"), "class Later {}\n");
        Files.delete(repo.resolve("New.java"));
        String later = Checkpoint.snapshot(repo);
        assertNotEquals(tree, later);
        // the executed step records the tree AFTER the iteration's edits (a resume keeps them); the plan step's
        // tree is the one a retry goes back to
        Path executed = cp.write(s, "executed");
        assertEquals(later, Checkpoint.read(executed).workbenchTree);
        assertEquals(tree, Checkpoint.read(plan).workbenchTree);

        Checkpoint.restore(repo, tree);
        assertEquals("class A { int x; }\n", Files.readString(repo.resolve("A.java")));
        assertTrue(Files.exists(repo.resolve("New.java")), "a file deleted after the snapshot comes back");
        assertFalse(Files.exists(repo.resolve("Later.java")), "a file added after the snapshot is removed");
        assertEquals(tree, Checkpoint.snapshot(repo), "restoring reproduces the snapshot exactly");
        // restoring to the current tree is a no-op; a null tree is ignored
        Checkpoint.restore(repo, tree);
        Checkpoint.restore(repo, null);
        assertEquals(tree, Checkpoint.snapshot(repo));
    }

    @Test
    void aRestoreKeepsWhatWasCommittedAfterTheSeamAndUndoesTheRestOfTheAbandonedRun() throws Exception {
        // a resume restored the seam's whole tree and reverted a document committed since
        Path repo = repoWithOneCommit();
        Files.writeString(repo.resolve("Doc.md"), "one\n");
        Files.writeString(repo.resolve("Old.md"), "old\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "docs");
        Checkpoint cp = Checkpoint.at(Files.createTempDirectory("run"), false, repo, null);
        TurnSnapshot seam = Checkpoint.read(cp.write(new TurnSnapshot(), "selected"));
        assertNotNull(seam.workbenchHead);
        // the abandoned run edits a file and adds one, uncommitted
        Files.writeString(repo.resolve("A.java"), "class A { broken }\n");
        Files.writeString(repo.resolve("Run.java"), "class Run {}\n");
        // after it, a commit changes one document, adds one and deletes one
        Files.writeString(repo.resolve("Doc.md"), "one\ntwo\n");
        Files.writeString(repo.resolve("Note.md"), "note\n");
        Files.delete(repo.resolve("Old.md"));
        git(repo, "add", "Doc.md", "Note.md", "Old.md");
        git(repo, "commit", "-q", "-m", "more docs");

        Checkpoint.restore(repo, seam.workbenchTree, seam.workbenchHead);
        assertEquals("class A {}\n", Files.readString(repo.resolve("A.java")), "the run's edit is undone");
        assertFalse(Files.exists(repo.resolve("Run.java")), "the run's new file is removed");
        assertEquals("one\ntwo\n", Files.readString(repo.resolve("Doc.md")), "the committed change stays");
        assertTrue(Files.exists(repo.resolve("Note.md")), "the committed new file stays");
        assertFalse(Files.exists(repo.resolve("Old.md")), "the committed deletion stays");
        Process st = new ProcessBuilder("git", "-C", repo.toString(), "status", "--short").start();
        assertEquals("", new String(st.getInputStream().readAllBytes()).strip(), "the working tree is HEAD's");
        // without a recorded HEAD the whole tree is restored, as before
        Checkpoint.restore(repo, seam.workbenchTree, null);
        assertEquals("one\n", Files.readString(repo.resolve("Doc.md")));
    }

    // a supervisor may reject a selected plan with one sentence; only the configured seams pause
    @Test
    void aRejectWithANoteIsADecisionAndOnlyConfiguredSeamsPause() throws Exception {
        Path run = Files.createTempDirectory("run");
        Checkpoint cp = Checkpoint.at(run, true, null, null, "selected,answer");
        Path exec = cp.write(new TurnSnapshot(), "executed");
        assertEquals(Checkpoint.Control.CONTINUE, cp.decide(exec, "executed", 1).control(), "executed is not a pause seam here");
        Path sel = cp.write(new TurnSnapshot(), "selected");
        Thread answerer = new Thread(() -> {
            try {
                while (!Files.exists(sel.resolve("waiting"))) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
                Checkpoint.reject(sel, "the plan re-runs the reach lookup instead of composing with the verb it named");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        answerer.start();
        Checkpoint.Decision d = Checkpoint.waitForDecision(sel, Duration.ofSeconds(20));
        answerer.join(5000);
        assertEquals(Checkpoint.Control.REJECT, d.control());
        assertEquals("the plan re-runs the reach lookup instead of composing with the verb it named", d.note());
        // plain verbs still parse; a bare reject gets a default note
        Path s2 = cp.write(new TurnSnapshot(), "selected");
        Files.writeString(s2.resolve("control"), "reject");
        assertEquals("the selected plan does not advance the task", Checkpoint.waitForDecision(s2, Duration.ofSeconds(5)).note());
        Path s3 = cp.write(new TurnSnapshot(), "selected");
        Files.writeString(s3.resolve("control"), "CONTINUE");
        assertEquals(Checkpoint.Control.CONTINUE, Checkpoint.waitForDecision(s3, Duration.ofSeconds(5)).control());
    }
}
