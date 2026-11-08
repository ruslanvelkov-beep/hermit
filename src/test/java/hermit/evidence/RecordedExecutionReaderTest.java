package hermit.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The recording reader's dump parsed into calls (arguments, returns) and the events inside a call. */
class RecordedExecutionReaderTest {

    // a short recording of a heating-plan test
    static final String DUMP = """
            [seq 310 t1] snapshot Reading#41 {unit=Scale.CELSIUS, value=21}
            [seq 311 t1] ZonePlanner.planFor(1 arg) this=ZonePlanner#1 (ZonePlanner.java:42)
            [seq 312 t1]   arg0 = HeatRequest#88
            [seq 313 t1]   line 42 (ZonePlanner.java:42)
            [seq 314 t1]   HeatRequest.zonesFor(0 args) this=HeatRequest#88 (HeatRequest.java:19)
            [seq 315 t1]     line 19 (HeatRequest.java:19)
            [seq 316 t1]     first-seen Zone#90
            [seq 320 t1]     local target = Zone#90 (HeatRequest.java:19)
            [seq 323 t1]     condition: 2 == 0 -> false (HeatRequest.java:21)
            [seq 327 t1]     return ZoneList#95 [HeatRequest.zonesFor]
            [seq 328 t1]   line 43 (ZonePlanner.java:43)
            [seq 402 t1] return HeatingPlan#97 [ZonePlanner.planFor]
            [seq 403 t1] Other.next(0 args) (Other.java:1)
            """;

    @Test
    void callsCarryTheirArgumentsAndReturnValues() {
        List<RecordedExecutionReader.Call> calls = RecordedExecutionReader.parseCalls(DUMP, 10);
        RecordedExecutionReader.Call outer = calls.stream()
                .filter(c -> c.method().equals("planFor")).findFirst().orElseThrow();
        assertEquals("ZonePlanner", outer.owner());
        assertEquals(List.of("HeatRequest#88"), outer.args());
        assertEquals("HeatingPlan#97", outer.returned());
        assertEquals("ZonePlanner.java:42", outer.location());
        RecordedExecutionReader.Call inner = calls.stream()
                .filter(c -> c.method().equals("zonesFor")).findFirst().orElseThrow();
        assertEquals("ZoneList#95", inner.returned(),
                "a nested call's return closes the nested call, not the outer one");
        assertTrue(outer.render().contains("returned HeatingPlan#97"), outer.render());
    }

    @Test
    void insideACallAreItsLocalsConditionsNestedCallsAndReturn() {
        List<String> inside = RecordedExecutionReader.insideOf(DUMP, 311, 50);
        assertTrue(inside.get(0).startsWith("ZonePlanner.planFor"), inside.toString());
        assertTrue(inside.stream().anyMatch(l -> l.contains("condition: 2 == 0 -> false")), inside.toString());
        assertTrue(inside.stream().anyMatch(l -> l.contains("local target = Zone#90")), inside.toString());
        assertTrue(inside.get(inside.size() - 1).startsWith("return HeatingPlan#97"),
                inside.toString());
        assertFalse(inside.stream().anyMatch(l -> l.contains("Other.next")), "the next top-level call is outside");
        assertFalse(inside.stream().anyMatch(l -> l.contains("first-seen")), "bookkeeping lines are left out");
    }
}
