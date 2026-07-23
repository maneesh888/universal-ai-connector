plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.android.application") version "9.3.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.0" apply false
}

if (JavaVersion.current() != JavaVersion.VERSION_21) {
    throw GradleException(
        "Java 21 is required to build Universal AI Connector. " +
            "Current runtime: ${JavaVersion.current()}",
    )
}
