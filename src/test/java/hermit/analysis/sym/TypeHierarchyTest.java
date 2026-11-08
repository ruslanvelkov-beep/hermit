package hermit.analysis.sym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeHierarchyTest {

    @TempDir
    Path dir;

    private TypeHierarchy build() throws IOException {
        Files.writeString(dir.resolve("Shape.java"), """
                package geo;

                public interface Shape {
                    double area();
                }
                """);
        Files.writeString(dir.resolve("Base.java"), """
                package geo;

                public abstract class Base implements Shape {
                    int shared() { return 1; }
                }
                """);
        Files.writeString(dir.resolve("Circle.java"), """
                package geo;

                public class Circle extends Base {
                    public double area() { return 3.14; }
                }
                """);
        Files.writeString(dir.resolve("Square.java"), """
                package geo;

                public class Square extends Base {
                    public double area() { return 4; }
                    int shared() { return 2; }
                }
                """);
        Files.writeString(dir.resolve("Registry.java"), """
                package geo;

                import java.util.ArrayList;

                public class Registry extends ArrayList<Shape> {
                    Runnable hook() {
                        return new Runnable() {
                            public void run() { }
                        };
                    }
                }
                """);
        return TypeHierarchy.build(SourceIndex.build(List.of(dir), ClasspathIndex.system()));
    }

    @Test
    void hierarchyLinksSourceAndLibrarySupertypesBothWays() throws IOException {
        TypeHierarchy h = build();
        TypeHierarchy.TypeEntry shape = h.entries("Shape").get(0);
        TypeHierarchy.TypeEntry base = h.entries("geo.Base").get(0);
        TypeHierarchy.TypeEntry circle = h.entries("Circle").get(0);

        assertTrue(shape.isInterface());
        assertTrue(base.supertypes().contains(shape));
        assertTrue(circle.supertypes().contains(base));
        assertTrue(shape.subtypes().contains(base), "reverse edge");
        assertEquals(2, base.subtypes().size(), "Circle and Square");

        // a source class under a LIBRARY supertype: both directions again (Object is always there too)
        TypeHierarchy.TypeEntry registry = h.entries("Registry").get(0);
        TypeHierarchy.TypeEntry arrayList = registry.supertypes().stream()
                .filter(e -> "java.util.ArrayList".equals(e.fqn())).findFirst().orElseThrow();
        assertNotNull(arrayList.clazz());
        assertTrue(arrayList.subtypes().contains(registry));

        // the anonymous Runnable is an entry, subtype of the library interface
        TypeHierarchy.TypeEntry runnable = h.libraryEntry(Runnable.class);
        assertTrue(runnable.subtypes().stream().anyMatch(e -> e.fqn().contains("$anon")),
                runnable.subtypes().toString());
    }

    @Test
    void visibilityInheritsAndOverridesShadow() throws IOException {
        TypeHierarchy h = build();
        TypeHierarchy.TypeEntry circle = h.entries("Circle").get(0);
        TypeHierarchy.TypeEntry square = h.entries("Square").get(0);

        // Circle inherits shared() from Base; Square's own declaration shadows it
        assertEquals("Base", h.visibleMethods(circle).get("shared/0").owner().name());
        assertEquals("Square", h.visibleMethods(square).get("shared/0").owner().name());

        // library methods are visible through the chain, as reflect Methods
        TypeHierarchy.TypeEntry registry = h.entries("Registry").get(0);
        TypeHierarchy.MethodDecl add = h.resolveCall(registry, "add", 1);
        assertNotNull(add);
        assertNotNull(add.reflect());
        assertNull(add.decl());
    }

    @Test
    void dispatchCandidatesCoverTheSubtypeCone() throws IOException {
        TypeHierarchy h = build();
        TypeHierarchy.TypeEntry shape = h.entries("Shape").get(0);
        List<TypeHierarchy.MethodDecl> area = h.dispatchCandidates(shape, "area", 0);
        var owners = area.stream().map(d -> d.owner().name()).collect(Collectors.toSet());
        assertTrue(owners.contains("Circle") && owners.contains("Square"), owners.toString());

        // through the intermediate abstract class: the inherited abstract declaration is the static
        // target, the two overrides are the cone
        TypeHierarchy.TypeEntry base = h.entries("geo.Base").get(0);
        List<TypeHierarchy.MethodDecl> viaBase = h.dispatchCandidates(base, "area", 0);
        assertEquals(3, viaBase.size(), viaBase.toString());
        assertEquals("Shape", viaBase.get(0).owner().name(), "static target first");
        assertFalse(viaBase.get(0).concrete(), "the interface declaration has no body");
        assertTrue(viaBase.get(1).concrete() && viaBase.get(2).concrete(), "the overrides do");

        // a monomorphic call has exactly one candidate
        TypeHierarchy.TypeEntry circle = h.entries("Circle").get(0);
        assertEquals(1, h.dispatchCandidates(circle, "area", 0).size());
    }

    @Test
    void implementsEdgeSurvivesAResolutionCycle() throws IOException {
        // An implements edge was
        // missing from the hierarchy. The interface statically imports a member of its own implementation; resolving the
        // interface first (index order) resolves the implementation while the interface has no scopes yet, so the
        // implementation's `implements` resolves to a name-only symbol and the edge is dropped - and never retried.
        Files.writeString(dir.resolve("Iface.java"), """
                package cyc;
                import static cyc.Impl.helper;
                public interface Iface {
                    Object log = helper();
                    void m();
                    default void d() { m(); }
                }
                """);
        Files.writeString(dir.resolve("Impl.java"), """
                package cyc;
                public class Impl implements Iface {
                    public static Object helper() { return null; }
                    @Override
                    public void m() { }
                }
                """);
        TypeHierarchy h = TypeHierarchy.build(SourceIndex.build(List.of(dir), ClasspathIndex.system()));
        TypeHierarchy.TypeEntry iface = h.entries("cyc.Iface").get(0);
        TypeHierarchy.TypeEntry impl = h.entries("cyc.Impl").get(0);
        assertTrue(impl.supertypes().contains(iface), "Impl implements Iface must be linked; got " + impl.supertypes());
        assertTrue(iface.subtypes().contains(impl), "reverse edge");
        // the edge carries through to dispatch: the interface method's candidates include the implementation
        List<TypeHierarchy.MethodDecl> impls = h.dispatchCandidates(iface, "m", 0);
        assertTrue(impls.stream().anyMatch(d -> d.owner() == impl), "dispatch candidates: " + impls);
    }
}
