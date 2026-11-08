package hermit.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The gradle project owning a file is derived from its module dir + the settings artifact mapping. */
class GradleModulesTest {

    @TempDir
    Path root;

    @Test
    void derivesTheArtifactMappedProjectForAFile() throws IOException {
        Files.writeString(root.resolve("settings.gradle.kts"), """
                javaModules {
                    directory("library") {
                        module("library-app") { artifact = "app" }
                        module("library-loans-impl") { artifact = "app-loans-impl" }
                    }
                }
                """);
        Path impl = root.resolve("library/library-loans-impl");
        Path test = impl.resolve("src/test/java/x/LoanDeskTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(impl.resolve("build.gradle.kts"), "plugins { }\n");
        Files.writeString(test, "class LoanDeskTest {}\n");
        Path other = root.resolve("shared/base-x/src/main/java/A.java");
        Files.createDirectories(other.getParent());
        Files.writeString(root.resolve("shared/base-x/build.gradle.kts"), "");
        Files.writeString(other, "class A {}\n");

        assertEquals(":app-loans-impl", GradleModules.projectFor(root, test));
        assertEquals(":app-loans-impl", GradleModules.projectFor(root,
                Path.of("library/library-loans-impl/src/test/java/x/LoanDeskTest.java")));
        assertEquals(":base-x", GradleModules.projectFor(root, other), "unmapped module → its directory name");
        assertNull(GradleModules.projectFor(root, root.resolve("README.md")), "not inside any module");
        assertEquals(":base-x", GradleModules.resolve(":base-x", root, test, ":app"), "a known module is kept");
        assertEquals(":app-loans-impl", GradleModules.resolve(":explicit", root, test, ":app"),
                "an explicit module the build does not have yields to the file's own");
        assertEquals(":app-loans-impl", GradleModules.resolve(null, root, test, ":app"));
        assertEquals(":app", GradleModules.resolve("", root, root.resolve("README.md"), ":app"));
    }

    @Test
    void aSingleProjectBuildIsTheRootProject() throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Path source = root.resolve("src/main/java/p/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}\n");
        assertEquals(":", GradleModules.projectFor(root, source));
        assertEquals(":compileJava", GradleModules.task(":", "compileJava"));
        assertEquals(":app:test", GradleModules.task(":app", "test"));
        assertEquals("test", GradleModules.task(null, "test"));
        assertEquals(":", GradleModules.resolve(":app", root, source, null),
                "':app' copied into a single-project build");
    }
}
