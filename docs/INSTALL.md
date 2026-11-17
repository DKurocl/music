# Install on the owner's Pixel

Target: Pixel 10 Pro XL running Android 17 (API 37). Keep the existing app installed when updating:
uninstalling removes the local personal library and playback state. The app does not yet export them.

## Install a verified PR build manually

1. Open the successful GitHub Actions run linked from the PR or HANDOFF.md. The repository is public;
   Actions artifacts still need a signed-in GitHub account.
2. Under Artifacts, download `Music-install` and extract its ZIP. Use the release APK, not a test report.
3. On the phone, open the APK from Files. If Android asks, allow installation from that source and
   install/update Music. Do not uninstall an existing Music installation to bypass a signer mismatch.
4. Open Music, grant audio access, and put local music files in the phone's Music folder. Rescan if
   MediaStore has not indexed the copy yet. Notifications provide playback controls.
5. Run docs/PHONE_CHECK.md, especially background playback and output switching, before relying on it
   for a long listening session. CI builds do not prove behavior on the physical phone.

## Automatic updates with Obtainium

Add `https://github.com/DKurocl/music` as a GitHub source and use the release-title filter `^music-`.
The repository is public (since 2026-11-16), so Obtainium needs no token. If it is made private again,
Obtainium needs its own read-only token in its GitHub settings; never put tokens in repository files or chat.

Obtainium follows published releases from main. A draft PR's Actions artifact does not appear in
Releases and is not installed automatically. The owner signer and increasing versionCode allow a
manual candidate APK to be updated by a later release using the same identity.

## Build environment for agents

What CI uses (`.github/workflows/build.yml`), and what a local or cloud agent needs:

- JDK 17 (Temurin) or newer; Gradle 9.9.1 (the wrapper `./gradlew` pins it).
- Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`; `local.properties` with
  `sdk.dir=...` is local only and never committed.
- Checks, in this order (the same as CI):

  ```sh
  ./gradlew test -PminSdk=35 --continue
  ./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
  ```

  `-PminSdk=35` is only for Robolectric, which lacks API 37; APKs keep minSdk 37.
- Lint sometimes fails in `lintAnalyze*Test` with a `FileNotFoundException` from an appfunctions jar
  in the Gradle cache; running the same command again passes. It is a tooling flake, not a finding.
- Screenshot gallery: `DesignGalleryTest` and `WidgetGalleryTest` write PNGs to
  `app/build/reports/ui-previews/`. Look at them before and after any UI change.
- Signing: CI signs with the owner key from the repository secrets. Without them a build is unsigned
  and nothing is published. Never add keys, passwords or cookies to the repository.

