pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // NewPipeExtractor (YouTube Music search and streams) is published only on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "Music"
// Pure Kotlin (JVM) modules hold everything that needs no Android: they test in seconds.
include(":core:model", ":core:lyrics", ":core:tags")
// Android libraries: one concern each, no UI.
include(":core:library", ":core:playback")
include(":core:user")
include(":app")

include(":core:dsp")
