pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
    }
}
rootProject.name = "pixel-apps"
file("apps").listFiles()?.filter { it.resolve("build.gradle.kts").isFile }?.sortedBy { it.name }?.forEach {
    include(":apps:" + it.name)
}

