pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven {
                name = "ConnectorUnderTest"
                url = uri(providers.gradleProperty("uacRepository").get())
            } }
            filter { includeGroup("com.myadidi") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "uac-android-coordinate-consumer"
