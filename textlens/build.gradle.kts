// TextLens: the text annotator (the hermit.text packages) as a library of its own; the agent depends on it.
plugins {
    id("java-library")
    id("application")
}

version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

application {
    mainClass = "hermit.text.cli.TextLensCommand"
    applicationName = "textlens"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "hermit.text.cli.TextLensCommand", "Implementation-Version" to version)
    }
}

tasks.test {
    useJUnitPlatform()
}
