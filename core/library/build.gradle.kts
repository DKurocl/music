plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ua.olex.music.library"
    compileSdk = 37
    defaultConfig { minSdk = providers.gradleProperty("minSdk").orNull?.toInt() ?: 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.coroutines.core)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
