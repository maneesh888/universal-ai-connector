plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("jvm") version "2.4.10" apply false
    id("com.android.kotlin.multiplatform.library") version "8.10.1" apply false
}

if (JavaVersion.current() != JavaVersion.VERSION_21) {
    throw GradleException(
        "Java 21 is required to build the Universal AI Connector POC. " +
            "Current runtime: ${JavaVersion.current()}",
    )
}
