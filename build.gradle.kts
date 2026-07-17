plugins {
    kotlin("multiplatform") version "2.4.10" apply false
}

if (JavaVersion.current() != JavaVersion.VERSION_21) {
    throw GradleException(
        "Java 21 is required to build the Universal AI Connector POC. " +
            "Current runtime: ${JavaVersion.current()}",
    )
}
