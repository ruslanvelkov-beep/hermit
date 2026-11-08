package hermit.text.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The checkers over every case of G3 (section 8 of the annotation plan), checked in as {@code g3-cases.txt}. */
class G3Test {

    static List<String> cases() throws IOException {
        try (InputStream in = G3Test.class.getResourceAsStream("g3-cases.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    @Test
    void theCheckedInCasesAreTheGeneratorsOutput() throws IOException {
        assertEquals(G3Cases.generate(), cases());
        assertEquals(200, cases().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyCaseHasItsOutcome(String line) {
        String[] field = line.split("\\|", 5);
        Instant clock = field[1].equals("-") ? null : Instant.parse(field[1]);
        AnnotationDocument.Context context = new AnnotationDocument.Context(clock, ZoneId.of(field[2]), "en");
        List<Check.Status> found = Checkers.checksOf(field[4].replace('~', '\n'), context).stream()
                .filter(c -> c.checker().equals(field[0])).map(Check::status).toList();
        assertEquals(field[3].equals("NONE") ? List.of() : List.of(Check.Status.valueOf(field[3])), found);
    }
}
