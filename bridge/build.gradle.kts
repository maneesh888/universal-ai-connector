import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
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
                    "com/maneesh/universalai/connector/UniversalAiConnectorVersion.kt",
                ).asFile
            outputFile.parentFile.mkdirs()
            outputFile.writeText(
                """
                package com.maneesh.universalai.connector

                internal const val UNIVERSAL_AI_CONNECTOR_VERSION: String = "${libraryVersion.get()}"
                """.trimIndent() + "\n",
            )
        }
    }

kotlin {
    val xcframework = XCFramework("UniversalAiConnectorBridge")
    val ktorVersion = "3.5.1"

    jvm()

    androidLibrary {
        namespace = "com.maneesh.universalai.connector.bridge"
        compileSdk = 36
        minSdk = 24
        buildToolsVersion = "36.1.0"

        withHostTestBuilder {}
    }

    iosArm64 {
        binaries.framework {
            baseName = "UniversalAiConnectorBridge"
            isStatic = true
            binaryOption("bundleId", "com.maneesh.universalai.connector.bridge")
            freeCompilerArgs +=
                "-Xoverride-konan-properties=minVersion.ios=17.0"
            xcframework.add(this)
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = "UniversalAiConnectorBridge"
            isStatic = true
            binaryOption("bundleId", "com.maneesh.universalai.connector.bridge")
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
            implementation("com.networknt:json-schema-validator:3.0.6")
        }
    }
}

tasks.withType<Test>().configureEach {
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
