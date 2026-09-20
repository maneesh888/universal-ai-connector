plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":bridge"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
