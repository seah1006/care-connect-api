import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.gradle.process.ExecOperations

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("info.solidsoft.pitest") version "1.15.0" apply false
}

// PIT Gradle plugin 1.15.0 still reads the pre-Gradle-9 reporting property.
// Keep the requested plugin version while supplying its legacy read-only alias.
(reporting as ExtensionAware).extensions.extraProperties["baseDir"] = reporting.baseDirectory.get().asFile
apply(plugin = "info.solidsoft.pitest")

group = "io.github.seah1006"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (providers.gradleProperty("skipDockerTests").isPresent) {
            excludeTags("docker")
        }
    }
    // Keep jqwik's local failure database inside the disposable build directory.
    systemProperty("jqwik.database", layout.buildDirectory.file("jqwik-database").get().asFile.absolutePath)
}

configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    pitestVersion.set("1.22.1")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("io.github.seah1006.careconnect.*"))
    excludedClasses.set(listOf("*.CareConnectApplication", "*.config.*", "*Configuration"))
    excludedTestClasses.set(listOf("*.ApplicationIntegrationTest", "*.ArchitectureTest"))
    mutationThreshold.set(80)
    failWhenNoMutations.set(false)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    jvmPath.set(javaToolchains.launcherFor(java.toolchain).map { it.executablePath })
    jvmArgs.set(listOf("-Djqwik.database=${layout.buildDirectory.file("pitest-jqwik-database").get().asFile.absolutePath}"))
}

val downloadTlc = tasks.register("downloadTlc") {
    group = "verification"
    description = "Downloads TLC 1.7.4 and verifies its pinned SHA-256 on every invocation."
    val jar = layout.buildDirectory.file("tools/tla2tools.jar")
    outputs.file(jar)
    outputs.upToDateWhen { false }
    doLast {
        val target = jar.get().asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val temporary = target.resolveSibling("tla2tools.jar.part")
            try {
                val connection = uri("https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar")
                    .toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.getInputStream().use { input -> temporary.outputStream().use(input::copyTo) }
                temporary.copyTo(target, overwrite = true)
            } finally {
                temporary.delete()
            }
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(checksum == "936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88") {
            "TLC SHA-256 mismatch: $checksum"
        }
    }
}

// Use injected ExecOperations instead of a shell so paths with spaces work on Windows too.
abstract class TlcCheck : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val javaExecutable: RegularFileProperty

    @get:InputFile
    abstract val tlcJar: RegularFileProperty

    @get:InputDirectory
    abstract val specificationDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val metadataDirectory: DirectoryProperty

    @TaskAction
    fun checkModels() {
        val specs = specificationDirectory.get().asFile.listFiles().orEmpty()
            .filter { it.extension == "tla" && it.resolveSibling("${it.nameWithoutExtension}.cfg").isFile }
            .sortedBy { it.name }
        check(specs.isNotEmpty()) { "No TLA+ specifications with matching .cfg files found" }
        specs.forEach { spec ->
            val metadata = metadataDirectory.dir(spec.nameWithoutExtension).get().asFile
            metadata.mkdirs()
            val output = ByteArrayOutputStream()
            val result = execOperations.exec {
                executable(javaExecutable.get().asFile)
                args("-cp", tlcJar.get().asFile.absolutePath, "tlc2.TLC", "-workers", "auto", "-deadlock",
                    "-metadir", metadata.absolutePath, "-config",
                    spec.resolveSibling("${spec.nameWithoutExtension}.cfg").absolutePath, spec.absolutePath)
                standardOutput = output
                errorOutput = output
                isIgnoreExitValue = true
            }
            val log = output.toString(Charsets.UTF_8)
            metadata.resolve("tlc.log").writeText(log)
            logger.lifecycle("{}:\n{}", spec.name, log)
            check(result.exitValue == 0 && log.contains("Model checking completed. No error has been found.")) {
                "TLC verification failed for ${spec.name}; see ${metadata.resolve("tlc.log")}"
            }
        }
    }
}

tasks.register<TlcCheck>("tlcCheck") {
    group = "verification"
    description = "Checks INV-SMOKE-001 and every docs/spec/*.tla with a matching .cfg using TLC."
    dependsOn(downloadTlc)
    javaExecutable.set(javaToolchains.launcherFor(java.toolchain).map { it.executablePath })
    tlcJar.set(layout.buildDirectory.file("tools/tla2tools.jar"))
    specificationDirectory.set(layout.projectDirectory.dir("docs/spec"))
    metadataDirectory.set(layout.buildDirectory.dir("tlc"))
    outputs.upToDateWhen { false }
}
