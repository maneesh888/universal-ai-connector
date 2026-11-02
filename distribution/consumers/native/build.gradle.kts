plugins { kotlin("multiplatform") version "2.4.10" }
val candidateVersion = providers.gradleProperty("uacVersion").get()
kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets.commonMain.dependencies {
        implementation("com.myadidi:universal-ai-connector:$candidateVersion")
    }
}
