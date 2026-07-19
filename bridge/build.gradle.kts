import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    val xcframework = XCFramework("UniversalAiConnectorBridge")

    jvm()

    androidLibrary {
        namespace = "com.maneesh.universalai.connector.bridge"
        compileSdk = 36
        minSdk = 24
        buildToolsVersion = "36.1.0"

        withHostTestBuilder {}
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
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
