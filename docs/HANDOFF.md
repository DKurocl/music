# Handoff

The newest entry is on top. Every session adds one: what changed, what is verified and how, what is
only on paper, what failed, what comes next.

## Session 2 (2026-11-14): M0 first-run reliability

**Changed.** Work branch `agent/m0-first-run-reliability`. Failed or cancelled scans preserve the
library; successful empty scans remove deleted files. Scan errors and retry are visible, and
permanently denied audio access opens app settings. A tap during controller startup retains the
requested queue. Controller bindings, progress polling and output callbacks stop with their UI
lifecycle. Pause handles buffering/focus suppression; Play retries an errored item or restarts an
ended queue. The player page scrolls for small windows and large fonts. Unknown bit depth and
M4A codecs are no longer shown as measured audio properties. `[код]`

**Verification in progress.** Regression tests added for scanner/provider failures, cancellation,
concurrent scans, player commands, media metadata and unknown audio resolution. CI results will
be recorded here after the branch build. No physical-device or visual verification yet.

**Baseline verified.** GitHub Actions run 43546746908 for `90ded6f` passed the existing build and
release jobs. The owner-signing step and signature verification passed; the old note below about
missing signing secrets is superseded. This does not prove installation through Obtainium.
`[код]` https://github.com/DKurocl/music/actions/runs/43546746908

**Environment.** Cloud has JDK 21, authenticated GitHub and no Android SDK/device/KVM. Use CI
instead of claiming local Android builds. `check_versions.py --network` resolved all five checked
pins. AGP 9.5.1 POM declares Kotlin Gradle plugin 2.2.10; the project explicitly pins Kotlin and
Compose plugin 2.2.21. Existing baseline CI compiled this combination. No pins downgraded.

**Next.** Finish CI verification, then run the M0 checklist on the Pixel with a known FLAC and
WF-1000XM5. Earbud wear detection, notification/lock-screen controls, API 37 behavior, screenshot
goldens, accessibility, cold start, background measurement and baseline profiles remain unverified.
M1 starts after recording the first phone run; do not mark M0 done based on unit tests.

## Session 1 (2026-11-14): foundation

**Changed.** The repository was empty. It now has the Gradle project (five modules), the first
working path from a local file to the player, the lyrics parsers, the song identity rule, CI, and
the documents an agent needs to continue (AGENTS.md, ARCHITECTURE, AUDIO, RESEARCH, ROADMAP).

**Verified** (cloud sandbox, Gradle 9.9.1, JDK 21, SDK 37.0):
- `gradle test`: 15 unit tests pass (8 lyrics, 7 model). `[код]`
- `gradle :app:lintDebug :app:assembleRelease`: builds; R8 release APK 3.5 MB (debug-signed, no
  owner key in the sandbox). `[код]`
- SDK 37 `android.jar` has no public `BluetoothA2dp.getCodecStatus` (javap). `[код]`
- Coil 3.6.x cannot be used with the Kotlin 2.2 compiler (build error); 3.4.0 works. `[код]`

**Only on paper** (compiled, never run on a device or emulator):
- Everything Android: scanning, playback, the service, the notification, the screens. Nobody has
  seen this app run. The first task is to run it on the phone (ROADMAP M0).
- Float output keeping more than 16 bits end to end (AUDIO.md, to measure).
- All of RESEARCH.md beyond the linked pages; the InnerTube, Soulseek and Apple motion artwork plans.
- The CI workflow itself: written from the Earbuds one, first run pending.

**Not done / known gaps.**
- No owner key in this repository's secrets, so CI will build but not publish. The owner adds
  `OWNER_KEYSTORE_B64`, `OWNER_KEYSTORE_PASSWORD`, `OWNER_KEY_ALIAS`, `OWNER_KEY_PASSWORD`.
- No screenshot tests, no accessibility tests, no baseline profile yet (ROADMAP M2, M9).
- The UI is a plain first shape, not the designed app.
- "Liner" (named by the owner) was not found; Sonora was given as the closest.

**Next.** ROADMAP M0 open tasks (first run on the phone), then M1 and M2 in parallel.
