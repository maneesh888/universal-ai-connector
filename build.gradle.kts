plugins {
    id("org.jetbrains.compose") version "1.11.0" apply false
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
}

group = "com.myadidi"

if (JavaVersion.current() != JavaVersion.VERSION_21) {
    throw GradleException(
        "Java 21 is required to build Universal AI Connector. " +
            "Current runtime: ${JavaVersion.current()}",
    )
}
