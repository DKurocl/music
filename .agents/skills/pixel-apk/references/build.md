# Build and delivery

verified: ні

[аналіз] Fixed user pins, not compilation results: AGP 9.5.1, Gradle 9.9.1, JDK 17, KSP 2.3.13, Compose BOM 2026.09.00, explicit Material 3 1.5.0-rc01, compileSdk 37.1, targetSdk 37, minSdk 35. Glance 1.2.0, Robolectric 4.17, Roborazzi 1.77.0, ATF 4.1.1.

[docs] AGP release notes: https://developer.android.com/build/releases/agp-9-5-0-release-notes
[аналіз] Built-in Kotlin removes kotlin-android, not the Compose compiler plugin. Read AGP POM and match org.jetbrains.kotlin.plugin.compose to embedded Kotlin. The handoff's 2.4.0 needs compatibility verification. Apply KSP only when a feature needs it.

[аналіз] Prefer authenticated GitHub Actions. Cloud/Windows require Java, SDK, Gradle and HTTPS to Google Maven, Gradle and Central mirror. HEAD failure alone is insufficient; try an artifact GET. No Android build was run in the authoring session.
Put https://maven-central.storage-download.googleapis.com/maven2/ first. Keep Google Maven. Set robolectric.dependency.repo.url separately. Heavy dependencies require -Pheavy=true; release gates always enable it.

## SDK and Gradle

[docs] https://developer.android.com/tools/sdkmanager
[аналіз] Bootstrap from an official SDK archive URL and verified SHA-256; licenses require explicit acceptance. Install SDK package names from sdkmanager --list. Template package names platforms;android-37.1 and build-tools;37.0.0 must resolve or fail. Generate and commit Gradle wrapper files using pinned distribution and verified checksum. Do not pass off missing wrapper files as a complete build.

## Versions and releases

[аналіз] One workflow, apps.json matrix, versionCode = GITHUB_RUN_NUMBER + per-app OFFSET. Reruns reuse codes; never overwrite a tag. Workflow replacement can reset numbering. Compare existing release metadata. For xm5/VPN preserve applicationId and certificate; do not guess offsets.
[docs] https://wiki.obtainium.imranr.dev/sources/github/
[аналіз] Prefix both title and tag, e.g. quickfocus-v123. Separate Obtainium entry per app, title regex ^quickfocus-, APK regex ^quickfocus-.*\\.apk$.

## Signing

[docs] https://developer.android.com/tools/apksigner
[аналіз] Secrets: OWNER_KEYSTORE_B64, OWNER_KEYSTORE_PASSWORD, OWNER_KEY_ALIAS, OWNER_KEY_PASSWORD. Decode only under RUNNER_TEMP. No shell tracing. Align before sign, verify after sign. Compare certificate against the offline owner record. Keep key backup outside repository and chat. Missing secrets are a blocker, not permission to use debug signing.

[docs] https://developer.android.com/developer-verification
[аналіз] Recheck current distribution rules, dates and eligibility before registration. The 2027 plan is not proof of registration. Sideload does not bypass runtime permissions, special access or background restrictions.

