plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.compose)
}
android {
    namespace = "@PACKAGE@"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "@PACKAGE@"
        minSdk = 35
        targetSdk = 37
        val code = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toLong() + @OFFSET@
        require(code in 1..2100000000)
        versionCode = code.toInt()
        versionName = code.toString()
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    lint { warningsAsErrors = true }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.material3)
    implementation("androidx.activity:activity-compose:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

