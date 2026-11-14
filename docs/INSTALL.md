# Install on the owner's Pixel

Target: Pixel 10 Pro XL running Android 17 (API 37). Keep the existing app installed when updating:
uninstalling removes the local personal library and playback state. The app does not yet export them.

## Install a verified PR build manually

1. Open the successful GitHub Actions run linked from the PR or HANDOFF.md. Sign in to the GitHub
   account that can access this private repository.
2. Under Artifacts, download `Music-install` and extract its ZIP. Use the release APK, not a test report.
3. On the phone, open the APK from Files. If Android asks, allow installation from that source and
   install/update Music. Do not uninstall an existing Music installation to bypass a signer mismatch.
4. Open Music, grant audio access, and put local music files in the phone's Music folder. Rescan if
   MediaStore has not indexed the copy yet. Notifications provide playback controls.
5. Run docs/PHONE_CHECK.md, especially background playback and output switching, before relying on it
   for a long listening session. CI builds do not prove behavior on the physical phone.

## Automatic updates with Obtainium

Add `https://github.com/DKurocl/music` as a GitHub source and use the release-title filter `^music-`.
The repository is private, so Obtainium needs its own authorized access in its GitHub authentication
settings; connecting GitHub to ChatGPT does not authenticate Obtainium. Keep credentials in the app,
never in repository files or chat. For a fine-grained token, grant only the repository access required
for reading its releases/assets.

Obtainium follows published releases from main. A draft PR's Actions artifact does not appear in
Releases and is not installed automatically. The owner signer and increasing versionCode allow a
manual candidate APK to be updated by a later release using the same identity.
