plugins {
    id("com.android.application") version "9.3.1"
    // Align AGP's built-in Kotlin compiler with the library metadata without applying KAP.
    kotlin("android") version "2.4.10" apply false
}
val candidateVersion = providers.gradleProperty("uacVersion").get()
require(candidateVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")))
android {
    namespace = "example.uac.consumer"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "example.uac.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = candidateVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UAC_EXPECTED_VERSION", "\"$candidateVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation("com.myadidi:universal-ai-connector:$candidateVersion")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
