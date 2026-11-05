plugins {
    kotlin("jvm") version "2.4.10"
    application
}
kotlin { jvmToolchain(21) }
val candidateVersion = providers.gradleProperty("uacVersion").get()
dependencies { implementation("com.myadidi:universal-ai-connector:$candidateVersion") }
application { mainClass.set("example.uac.ConsumerKt") }
tasks.named<JavaExec>("run") {
    systemProperty("uac.expectedVersion", candidateVersion)
    systemProperty("uac.expectedJarSha256", providers.gradleProperty("uacExpectedJarSha256").get())
}
