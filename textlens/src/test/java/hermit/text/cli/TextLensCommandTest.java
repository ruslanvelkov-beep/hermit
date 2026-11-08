package hermit.text.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TextLens on the command line: the options, the exit codes and what each command writes. */
class TextLensCommandTest {

    /** One run of the command: its exit code and what it wrote to standard output and standard error. */
    record Run(int code, String out, String err) {}

    static Run run(List<String> args, String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = TextLensCommand.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** {@code command} with the clock of 2026-10-28 in UTC and {@code more} options. */
    static List<String> args(String command, String... more) {
        List<String> args = new ArrayList<>(List.of(command, "--clock", "2026-10-28T10:00:00Z", "--zone", "UTC"));
        args.addAll(List.of(more));
        return args;
    }

    @Test
    void theOptionsAreRead() {
        TextLensCommand.Options options = TextLensCommand.parse(List.of("check", "--clock", "2026-10-28T10:00:00Z",
                "--zone", "UTC", "--source", "a.txt", "--source", "b.txt", "--server", "7471"));
        assertEquals("check", options.command());
        assertEquals(Instant.parse("2026-10-28T10:00:00Z"), options.clock());
        assertEquals(ZoneId.of("UTC"), options.zone());
        assertEquals(List.of(Path.of("a.txt"), Path.of("b.txt")), options.sources());
        assertEquals(7471, options.server());
        assertNull(options.weights());
        assertEquals(TextLensServer.DEFAULT_PORT, options.port());
        assertEquals(30, options.idleMinutes());
        TextLensCommand.Options serve = TextLensCommand.parse(List.of("serve", "--port", "0", "--idle-minutes", "5",
                "--weights", "w.jsonl.gz", "--search", "s.jsonl.gz"));
        assertEquals(List.of(0, 5), List.of(serve.port(), serve.idleMinutes()));
        assertEquals(List.of(Path.of("w.jsonl.gz"), Path.of("s.jsonl.gz")), List.of(serve.weights(), serve.search()));
        assertNull(serve.server());
    }

    @Test
    void aUsageErrorNamesTheProblemAndExitsTwo() {
        Run none = run(List.of(), "");
        assertEquals(2, none.code());
        assertTrue(none.err().startsWith("textlens: no command\nusage: textlens"), none.err());
        assertTrue(run(List.of("frobnicate"), "").err().startsWith("textlens: unknown command: frobnicate\n"));
        assertTrue(run(List.of("check", "--zone"), "").err().startsWith("textlens: --zone needs a value\n"));
        assertTrue(run(List.of("check", "--clock", "yesterday"), "").err()
                .startsWith("textlens: --clock: cannot read yesterday\n"));
        Run unknown = run(List.of("tag", "--colour", "red"), "");
        assertEquals(2, unknown.code());
        assertTrue(unknown.err().startsWith("textlens: unknown option: --colour\n"));
        assertEquals("", unknown.out());
    }

    @Test
    void annotateWritesTheDocument() {
        Run run = run(args("annotate"), "The release was on Mon Oct 20, 2026.");
        assertEquals(0, run.code());
        AnnotationDocument document = AnnotationDocument.fromJson(run.out());
        assertEquals("The release was on Mon Oct 20, 2026.", document.text());
        assertEquals(List.of(Check.Status.INCONSISTENT), document.checks().stream().map(Check::status).toList());
    }

    @Test
    void checkWritesTheFindingsAndExitsOneWithAny() {
        Run found = run(args("check"), "The release was on Mon Oct 20, 2026.");
        assertEquals(1, found.code());
        assertEquals("date: October 20, 2026 was a Tuesday, not a Monday.\n", found.out());
        Run none = run(args("check"), "We met on Tue Oct 20.");
        assertEquals(0, none.code());
        assertEquals("", none.out());
    }

    @Test
    void checkReadsTheSources(@TempDir Path dir) throws IOException {
        Path mail = dir.resolve("mail.txt");
        Files.writeString(mail, "Sent 07/18/2000 08:58 AM");
        assertEquals(1, run(args("check"), "How about Thursday, July 6?").code());
        assertEquals(0, run(args("check", "--source", mail.toString()), "How about Thursday, July 6?").code(),
                "July 6, 2000 was a Thursday");
        Run missing = run(args("check", "--source", dir.resolve("missing.txt").toString()), "Hello.");
        assertEquals(3, missing.code());
        assertTrue(missing.err().startsWith("textlens: "));
    }

    @Test
    void tagWritesEachTokenWithItsTag() {
        Run run = run(args("tag"), "Just sort files by size.");
        assertEquals(0, run.code());
        assertEquals("Just\tADV\nsort\tVERB\nfiles\tNOUN\nby\tADP\nsize\tNOUN\n.\tPUNCT\n", run.out());
    }
}
