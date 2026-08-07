import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
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
                Copy .env.live.example to the Git-ignored .env.live file, add OPENAI_API_KEY and
                OPENAI_LIVE_MODEL manually, restrict it with chmod 600 .env.live, then export it
                into this process before rerunning ./scripts/check-live.sh openai.
                The live task never reads or sources .env.live automatically.
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
                Copy .env.live.example to the Git-ignored .env.live file, add ANTHROPIC_API_KEY
                and ANTHROPIC_LIVE_MODEL manually, restrict it with chmod 600 .env.live, then
                export it into this process before rerunning ./scripts/check-live.sh anthropic.
                The live task never reads or sources .env.live automatically.
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
                Copy .env.live.example to the Git-ignored .env.live file, add OPENROUTER_API_KEY
                and OPENROUTER_LIVE_MODEL manually, restrict it with chmod 600 .env.live, then
                export it into this process before rerunning ./scripts/check-live.sh openrouter.
                The live task never reads or sources .env.live automatically.
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
