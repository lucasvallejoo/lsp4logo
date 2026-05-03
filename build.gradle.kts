plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.github.lucasvallejoo"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.lucasvallejoo.lsp4logo.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.shadowJar {
    archiveBaseName.set("lsp4logo")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    // Belt-and-braces: explicitly set Main-Class so `java -jar lsp4logo.jar`
    // works regardless of whether the Shadow plugin picks it up from the
    // `application` plugin in this Gradle version.
    manifest { attributes["Main-Class"] = "io.github.lucasvallejoo.lsp4logo.MainKt" }
}

// Convenience: `./gradlew run` should pipe stdin/stdout straight through
// so the LSP client can drive the server interactively.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// The deliverable for an LSP server is a single fat-jar produced by `shadowJar`,
// not the tar/zip distributions the `application` plugin generates by default.
// Disabling them avoids the implicit-dependency wiring conflict between the
// `application` and `shadow` plugins under Gradle 8.10+.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
tasks.named("startScripts") { enabled = false }
tasks.named("startShadowScripts") { enabled = false }
tasks.named("shadowDistTar") { enabled = false }
tasks.named("shadowDistZip") { enabled = false }
