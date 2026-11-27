plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ua.olex.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "ua.olex.music"
        // One phone: the owner's Pixel 10 Pro XL on Android 17. No older Android is supported, so no
        // compatibility paths are written. (-PminSdk=35 only for tools that lag, such as Robolectric.)
        minSdk = providers.gradleProperty("minSdk").orNull?.toInt() ?: 37
        targetSdk = 37
        // CI run numbers only grow, so every CI build installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(1000) ?: 1
        versionName = "2.2.0"
        // One phone, one ABI: no 32-bit or x86 code in the APK the owner installs.
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("music.testVariant", it.name) }
    }
    // UseKtx: style only. ChromeOsAbiSupport: this APK is for one arm64 phone.
    lint {
        warningsAsErrors = true
        disable += listOf("UseKtx", "ChromeOsAbiSupport")
    }

    // Owner key from CI secrets (the same key as Earbuds and VPN). Without it the build is signed
    // with the debug identity and CI does not publish it: a release needs the owner key.
    val ownerKeystore = providers.environmentVariable("OWNER_KEYSTORE").orNull?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (ownerKeystore != null) create("owner") {
            storeFile = ownerKeystore
            storePassword = providers.environmentVariable("OWNER_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("OWNER_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("OWNER_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:tags"))
    implementation(libs.appfunctions)
    ksp(libs.appfunctions.compiler)
    implementation(project(":core:library"))
    implementation(project(":core:user"))
    implementation(project(":core:playback"))
    // Moving covers (a muted looping video beside the song) are drawn by their own small player.
    implementation(libs.media3.exoplayer)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    // Covers of online songs come over the network.
    implementation(libs.coil.network)
    // YouTube Music: search and the audio stream of a song, the way NewPipe does it (no account).
    implementation(libs.newpipe.extractor)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
