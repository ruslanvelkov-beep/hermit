package hermit.analysis.sym;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClasspathIndexTest {

    @Test
    void classesInIsAReadOnlyViewThatCallersCannotChange() {
        // An index of its own: the shared system index stays as it is whatever this test does.
        ClasspathIndex index = ClasspathIndex.withExtra(List.of());
        Set<String> util = index.classesIn("java.util");
        assertTrue(util.contains("List"), "java.util holds List");
        assertThrows(UnsupportedOperationException.class, () -> util.add("Invented"));
        assertThrows(UnsupportedOperationException.class, () -> index.classesIn("no.such.pkg").add("Invented"));
        assertFalse(index.classesIn("java.util").contains("Invented"));
    }
}
