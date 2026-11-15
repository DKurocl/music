plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ua.olex.music.library"
    compileSdk = 37
    testOptions { unitTests.all {
        // Robolectric bridges Android descriptors to host files for sidecar storage tests.
        it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    } }
    defaultConfig { minSdk = providers.gradleProperty("minSdk").orNull?.toInt() ?: 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:tags"))
    api(libs.coroutines.core)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
