plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":textlens"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

application {
    mainClass = "hermit.app.Main"
}

tasks.test {
    useJUnitPlatform()
}
