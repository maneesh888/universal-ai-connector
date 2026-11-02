import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Properties
import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

val releaseMetadata = Properties().apply {
    rootProject.file("distribution/release.properties").inputStream().use { load(it) }
}
fun releaseProperty(name: String): String = requireNotNull(releaseMetadata.getProperty(name))

dokka {
    dokkaPublications.html {
        moduleName.set("Universal AI Connector")
        moduleVersion.set(project.version.toString())
        offlineMode.set(true)
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
    dokkaSourceSets.configureEach {
        // Kotlin/Native publications complete the metadata graph; Swift remains the Apple API.
        if (name.startsWith("ios")) suppress.set(true)
    }
}

val publicationJavadocJar = tasks.register<Jar>("publicationJavadocJar") {
    dependsOn("dokkaGeneratePublicationHtml")
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
    from(rootProject.file("LICENSE")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val generatedVersionSource =
    tasks.register("generateUniversalAiConnectorVersion") {
        val outputDirectory = layout.buildDirectory.dir("generated/sources/uacVersion/commonMain/kotlin")
        val libraryVersion = providers.gradleProperty("VERSION_NAME")

        inputs.property("libraryVersion", libraryVersion)
        outputs.dir(outputDirectory)

        doLast {
            val outputFile =
                outputDirectory.get().file(
                    "com/myadidi/universalai/connector/UniversalAiConnectorVersion.kt",
                ).asFile
            outputFile.parentFile.mkdirs()
            outputFile.writeText(
                """
                package com.myadidi.universalai.connector

                internal const val UNIVERSAL_AI_CONNECTOR_VERSION: String = "${libraryVersion.get()}"
                """.trimIndent() + "\n",
            )
        }
    }

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters { include { byNames.add("com.myadidi.universalai.connector.**") } }
    }
    val xcframework = XCFramework("UniversalAiConnectorBridge")
    val ktorVersion = "3.5.2"

    jvm()

    androidLibrary {
        namespace = "com.myadidi.universalai.connector.bridge"
        compileSdk = 36
        minSdk = 24
        buildToolsVersion = "36.1.0"

        withHostTestBuilder {}
    }

    iosArm64 {
        binaries.framework {
            baseName = "UniversalAiConnectorBridge"
            isStatic = true
            binaryOption("bundleId", "com.myadidi.universalai.connector.bridge")
            freeCompilerArgs +=
                "-Xoverride-konan-properties=minVersion.ios=17.0"
            xcframework.add(this)
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = "UniversalAiConnectorBridge"
            isStatic = true
            binaryOption("bundleId", "com.myadidi.universalai.connector.bridge")
            freeCompilerArgs +=
                "-Xoverride-konan-properties=minVersion.ios=17.0"
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedVersionSource)
        }

        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("io.ktor:ktor-client-core:$ktorVersion")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:$ktorVersion")
        }

        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:$ktorVersion")
        }

        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:$ktorVersion")
        }

        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }

        jvmTest.dependencies {
            implementation("com.networknt:json-schema-validator:3.0.7")
        }
    }
}

publishing {
    // This phase has no remote repository: ordinary publishing tasks can only write build output.
    repositories {
        maven {
            name = "LocalRelease"
            url = layout.buildDirectory.dir("distribution/maven").get().asFile.toURI()
        }
    }
    publications.withType<MavenPublication>().configureEach {
        val suffix = if (name == "kotlinMultiplatform") "" else "-${name.lowercase()}"
        artifactId = releaseProperty("MAVEN_ARTIFACT_ID") + suffix
        artifact(publicationJavadocJar)
        pom {
            name.set(releaseProperty("POM_NAME"))
            description.set(releaseProperty("POM_DESCRIPTION"))
            url.set(releaseProperty("POM_URL"))
            licenses { license {
                name.set(releaseProperty("POM_LICENSE_NAME"))
                url.set(releaseProperty("POM_LICENSE_URL"))
            } }
            developers { developer {
                id.set(releaseProperty("POM_DEVELOPER_ID"))
                name.set(releaseProperty("POM_DEVELOPER_NAME"))
                url.set(releaseProperty("POM_DEVELOPER_URL"))
            } }
            scm {
                url.set(releaseProperty("POM_SCM_URL"))
                connection.set(releaseProperty("POM_SCM_CONNECTION"))
                developerConnection.set(releaseProperty("POM_SCM_DEVELOPER_CONNECTION"))
            }
        }
    }
}

// No signing material is inspected during unsigned local builds or ordinary CI.
val signPublication = providers.gradleProperty("uacSignPublication").map {
    require(it == "true" || it == "false") { "uacSignPublication must be true or false." }
    it == "true"
}.orElse(false).get()
if (signPublication) {
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signing requires --no-configuration-cache; use an explicitly approved host invocation."
    }
    val key = providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").orNull
    val password = providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword").orNull
    require(!key.isNullOrBlank() && !password.isNullOrBlank()) {
        "Signing requires approved host-supplied key and passphrase inputs."
    }
    signing {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}


tasks.withType<Test>().configureEach {
    systemProperty("uac.openrouter.diagnostic.script", rootProject.file("scripts/openrouter-diagnostic.py").absolutePath)
    val contractsDirectory = rootProject.layout.projectDirectory.dir("contracts")
    inputs.dir(contractsDirectory)
    systemProperty("uac.contracts.root", contractsDirectory.asFile.absolutePath)
}

val jvmTestTask = tasks.named<Test>("jvmTest")
jvmTestTask.configure {
    exclude("**/OpenAiLiveTest.class")
    exclude("**/AnthropicLiveTest.class")
    exclude("**/OpenRouterLiveTest.class")
    exclude("**/GatewayLiveTest.class")
}

tasks.register<Test>("openAiLiveTest") {
    group = "verification"
    description = "Runs the explicit exact-head OpenAI live smoke tests."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    include("**/OpenAiLiveTest.class")

    val expectedSha = providers.gradleProperty("uacLiveExpectedSha").orElse("")
    inputs.property("uacLiveExpectedSha", expectedSha)
    systemProperty("uac.live.expectedSha", expectedSha)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live provider tests must execute and retain no reusable result.") {
        true
    }

    doFirst {
        val credentialPresent = !System.getenv("OPENAI_API_KEY").isNullOrBlank()
        val modelPresent = !System.getenv("OPENAI_LIVE_MODEL").isNullOrBlank()
        if (!credentialPresent || !modelPresent) {
            throw GradleException(
                """
                OpenAI live verification is not configured.
                Configure the primary-checkout file reported by
                ./scripts/local-config.sh live-env-path with OPENAI_API_KEY and OPENAI_LIVE_MODEL,
                restrict it to mode 600, then rerun ./scripts/check-live.sh openai. This Gradle
                task accepts process environment only; the runner validates and loads the file.
                """.trimIndent(),
            )
        }
        if (!expectedSha.get().matches(Regex("^[0-9a-f]{40}$"))) {
            throw GradleException(
                "OpenAI live verification requires -PuacLiveExpectedSha=<exact-HEAD-SHA>.",
            )
        }
        if (System.getenv("UAC_LIVE_EXPECTED_SHA") != expectedSha.get()) {
            throw GradleException(
                "OpenAI live verification process and Gradle exact-head inputs do not match.",
            )
        }
    }
}

tasks.register<Test>("anthropicLiveTest") {
    group = "verification"
    description = "Runs the explicit exact-head Anthropic live smoke tests."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    include("**/AnthropicLiveTest.class")

    val expectedSha = providers.gradleProperty("uacLiveExpectedSha").orElse("")
    inputs.property("uacLiveExpectedSha", expectedSha)
    systemProperty("uac.live.expectedSha", expectedSha)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live provider tests must execute and retain no reusable result.") {
        true
    }

    doFirst {
        val credentialPresent = !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()
        val modelPresent = !System.getenv("ANTHROPIC_LIVE_MODEL").isNullOrBlank()
        if (!credentialPresent || !modelPresent) {
            throw GradleException(
                """
                Anthropic live verification is not configured.
                Configure the primary-checkout file reported by
                ./scripts/local-config.sh live-env-path with ANTHROPIC_API_KEY and
                ANTHROPIC_LIVE_MODEL, restrict it to mode 600, then rerun
                ./scripts/check-live.sh anthropic. This Gradle task accepts process environment
                only; the runner validates and loads the file.
                """.trimIndent(),
            )
        }
        if (!expectedSha.get().matches(Regex("^[0-9a-f]{40}$"))) {
            throw GradleException(
                "Anthropic live verification requires -PuacLiveExpectedSha=<exact-HEAD-SHA>.",
            )
        }
        if (System.getenv("UAC_LIVE_EXPECTED_SHA") != expectedSha.get()) {
            throw GradleException(
                "Anthropic live verification process and Gradle exact-head inputs do not match.",
            )
        }
    }
}

tasks.register<Test>("openRouterLiveTest") {
    group = "verification"
    description = "Runs the explicit exact-head OpenRouter live smoke tests."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    include("**/OpenRouterLiveTest.class")

    val expectedSha = providers.gradleProperty("uacLiveExpectedSha").orElse("")
    inputs.property("uacLiveExpectedSha", expectedSha)
    systemProperty("uac.live.expectedSha", expectedSha)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live provider tests must execute and retain no reusable result.") {
        true
    }

    doFirst {
        val credentialPresent = !System.getenv("OPENROUTER_API_KEY").isNullOrBlank()
        val modelPresent = !System.getenv("OPENROUTER_LIVE_MODEL").isNullOrBlank()
        if (!credentialPresent || !modelPresent) {
            throw GradleException(
                """
                OpenRouter live verification is not configured.
                Configure the primary-checkout file reported by
                ./scripts/local-config.sh live-env-path with OPENROUTER_API_KEY and
                OPENROUTER_LIVE_MODEL, restrict it to mode 600, then rerun
                ./scripts/check-live.sh openrouter. This Gradle task accepts process environment
                only; the runner validates and loads the file.
                """.trimIndent(),
            )
        }
        if (!expectedSha.get().matches(Regex("^[0-9a-f]{40}$"))) {
            throw GradleException(
                "OpenRouter live verification requires -PuacLiveExpectedSha=<exact-HEAD-SHA>.",
            )
        }
        if (System.getenv("UAC_LIVE_EXPECTED_SHA") != expectedSha.get()) {
            throw GradleException(
                "OpenRouter live verification process and Gradle exact-head inputs do not match.",
            )
        }
    }
}

tasks.register<Test>("gatewayLiveTest") {
    group = "verification"
    description = "Runs the explicit exact-head OpenAI-compatible Gateway live smoke tests."
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    include("**/GatewayLiveTest.class")

    val expectedSha = providers.gradleProperty("uacLiveExpectedSha").orElse("")
    inputs.property("uacLiveExpectedSha", expectedSha)
    systemProperty("uac.live.expectedSha", expectedSha)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live Gateway tests must execute and retain no reusable result.") {
        true
    }

    doFirst {
        val baseUrlPresent = !System.getenv("GATEWAY_LIVE_BASE_URL").isNullOrBlank()
        val credentialPresent = !System.getenv("GATEWAY_API_KEY").isNullOrBlank()
        val modelPresent = !System.getenv("GATEWAY_LIVE_MODEL").isNullOrBlank()
        val structuredOutput = System.getenv("GATEWAY_LIVE_STRUCTURED_OUTPUT")
        if (!baseUrlPresent ||
            !credentialPresent ||
            !modelPresent ||
            structuredOutput !in setOf("true", "false")
        ) {
            throw GradleException(
                """
                Gateway live verification is not configured.
                Configure the primary-checkout file reported by
                ./scripts/local-config.sh live-env-path with GATEWAY_LIVE_BASE_URL,
                GATEWAY_API_KEY, GATEWAY_LIVE_MODEL, and explicit
                GATEWAY_LIVE_STRUCTURED_OUTPUT=true|false, restrict it to mode 600, then rerun
                ./scripts/check-live.sh gateway. This Gradle task accepts process environment
                only; the runner validates and loads the file.
                """.trimIndent(),
            )
        }
        if (!expectedSha.get().matches(Regex("^[0-9a-f]{40}$"))) {
            throw GradleException(
                "Gateway live verification requires -PuacLiveExpectedSha=<exact-HEAD-SHA>.",
            )
        }
        if (System.getenv("UAC_LIVE_EXPECTED_SHA") != expectedSha.get()) {
            throw GradleException(
                "Gateway live verification process and Gradle exact-head inputs do not match.",
            )
        }
    }
}

// Bind bundled-native notice review to the actual resolved iOS link inputs.
abstract class NativeNoticeInputs : DefaultTask() {
    @get:Input
    abstract val identities: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun writeInventory() {
        val filesByName = artifacts.files.groupBy { it.name }
        val rows = identities.get().map { identity ->
            val name = identity.substringAfterLast('\t')
            val file = filesByName[name]?.singleOrNull()
                ?: throw GradleException("Expected one native input for $name")
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            "$identity\t$hash"
        }
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(rows.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.register<NativeNoticeInputs>("releaseNativeNoticeInputs") {
    destination.set(layout.buildDirectory.file("distribution/native-notice-inputs.tsv"))
    artifacts.from(listOf("iosArm64CompileKlibraries", "iosSimulatorArm64CompileKlibraries")
        .map { configurations.getByName(it).incoming.artifacts.artifactFiles })
    identities.set(listOf("iosArm64CompileKlibraries", "iosSimulatorArm64CompileKlibraries").map { configurationName ->
        configurations.getByName(configurationName).incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { artifact ->
                val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                "$configurationName\t${id.group}:${id.module}:${id.version}\t${artifact.file.name}"
            }
        }
    }.let { providersList ->
        providersList[0].zip(providersList[1]) { first, second -> first + second }
    })
}
