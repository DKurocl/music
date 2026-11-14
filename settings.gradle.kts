pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Music"
// Pure Kotlin (JVM) modules hold everything that needs no Android: they test in seconds.
include(":core:model", ":core:lyrics", ":core:tags")
// Android libraries: one concern each, no UI.
include(":core:library", ":core:playback")
include(":core:user")
include(":app")
