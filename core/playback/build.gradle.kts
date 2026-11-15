plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ua.olex.music.playback"
    compileSdk = 37
    defaultConfig { minSdk = providers.gradleProperty("minSdk").orNull?.toInt() ?: 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:dsp"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:tags"))
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
