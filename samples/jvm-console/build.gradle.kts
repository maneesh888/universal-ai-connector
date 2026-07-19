plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":bridge"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

application {
    mainClass.set("com.maneesh.universalai.samples.jvm.MainKt")
}

tasks.register("consumerCheck") {
    group = "verification"
    description = "Tests and runs the JVM console as a public bridge-module consumer."
    dependsOn("test", "run")
}
