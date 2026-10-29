pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "universal-ai-connector"

include(":bridge")
include(":samples:android")
include(":samples:jvm-console")
include(":samples:host-controller")
include(":samples:desktop")
