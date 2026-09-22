plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":samples:host-controller"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.github.javakeyring:java-keyring:1.0.4") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
compose.desktop { application { mainClass = "com.myadidi.universalai.samples.desktop.MainKt" } }
tasks.register("consumerCheck") {
    group = "verification"
    dependsOn("check", "jar")
}
