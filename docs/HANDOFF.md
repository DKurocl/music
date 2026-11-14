# Handoff

The newest entry is on top. Every session adds one: what changed, what is verified and how, what is
only on paper, what failed, what comes next.

## Session 2 (2026-11-14): browsable library, search and playback reliability

**Changed.** Work branch `agent/m0-first-run-reliability`. Failed or cancelled scans preserve the
library; successful empty scans remove deleted files. Scan errors and retry are visible, and
permanently denied audio access opens app settings. A tap during controller startup retains the
requested queue. Controller bindings, progress polling and output callbacks stop with their UI
lifecycle. Pause handles buffering/focus suppression; Play retries an errored item or restarts an
ended queue. The player page scrolls for small windows and large fonts. Unknown bit depth and
M4A codecs are no longer shown as measured audio properties. `[код]`
Review and continuation entry point: https://github.com/DKurocl/music/pull/1 (work branch above).

**Verified M0 hardening.** CI run 43549776145 for `04bef1b` passed `gradle test -PminSdk=35`,
`lintDebug`, `lintRelease`, `assembleDebug`, R8 `assembleRelease` and owner-signature verification.
Regression tests cover scanner/provider failures, cancellation, concurrent scans, player commands,
media metadata and unknown audio resolution. No physical-device or visual verification yet.
`[код]` https://github.com/DKurocl/music/actions/runs/43549776145

**Expanded scope requested by the owner.** Continue M1 in this branch while M0's physical-device
gate is pending. Added pure Kotlin library grouping/sorting, Songs/Albums/Artists/Genres/Folders
tabs, collection pages with ordered Play all queues, and cancellable prefix/diacritic-insensitive
Room FTS search. `library.db` v2 rebuilds its cache; FTS has external content and transactional
triggers. New tests cover grouping, ordering, safe search expressions, SQLite matching and index
updates/rollback. Preserve this work if the next session begins before CI is complete.

**Verified M1 browsing/search.** `92c4fdf` passed full CI run 43550547422: tests (including native
SQLite/Room FTS matching, update/delete triggers and transaction rollback), both lints, both APK
builds and owner signature verification. `[код]`
https://github.com/DKurocl/music/actions/runs/43550547422

**Verified observer and UI interaction.** `5aa0d92` passed full CI run 43564927822, including
debounced ContentObserver registration/cancellation and Compose tests for album/song queues,
stale search results and clearing queries. Source matching fixes in `f1b286d` are included.
`[код]` https://github.com/DKurocl/music/actions/runs/43564927822

**Baseline verified.** GitHub Actions run 43546746908 for `90ded6f` passed the existing build and
release jobs. The owner-signing step and signature verification passed; the old note below about
missing signing secrets is superseded. This does not prove installation through Obtainium.
`[код]` https://github.com/DKurocl/music/actions/runs/43546746908

**Environment.** Cloud has JDK 21, authenticated GitHub and no Android SDK/device/KVM. Use CI
instead of claiming local Android builds. `check_versions.py --network` resolved all five checked
pins. AGP 9.5.1 POM declares Kotlin Gradle plugin 2.2.10; the project explicitly pins Kotlin and
Compose plugin 2.2.21. Existing baseline CI compiled this combination. No pins downgraded.
The initial local wrapper download failed because Java did not use the inherited proxy. Mapping
that proxy to Java's standard proxy properties fixed it, without changing network policy.
`./gradlew :core:model:test :core:lyrics:test --no-daemon` then passed locally (Gradle 9.9.1,
JDK 21). Android validation still runs in CI, where the SDK is available.

**Next.** Finish CI verification, then run the M0 checklist on the Pixel with a known FLAC and
WF-1000XM5. Earbud wear detection, notification/lock-screen controls, API 37 behavior, screenshot
goldens, accessibility, cold start, background measurement and baseline profiles remain unverified.
The owner explicitly requested continuing M1 before the phone run; do not mark M0 done based on
unit tests. Latest unverified work adds a foreground-only debounced MediaStore observer and
Compose interaction tests for collection queues, song selection and stale search results.
`docs/PHONE_CHECK.md` is the runnable manual checklist for the owner or next agent.

**Timer work in progress.** The player's sleep timer offers 5/15/30/60/90 minutes and cancellation.
PlaybackService owns one delayed job; a private session command changes it and session extras
restore state after controller reconnection. Pure model tests cover expiry, replacement,
cancellation and invalid/overflowing requests. Compose tests exercise duration selection,
cancellation and dismissal. These changes still need the next CI result and a physical playback
check; timer state intentionally ends with the playback service rather than persisting after
process death.
The next CI also renders actual UI previews under `app/build/reports/ui-previews/`, including
dark mode with font scale 2. These are review artifacts, not approved golden baselines. It adds a
10,000-track index invariant test, keeps identity normalization lazy for normal browsing, and
preserves radio edits and alternate mixes. Search failure now has an explicit Retry action;
observer registration failure falls back to manual refresh instead of crashing the app.
The final performance pass moves large-queue metadata construction off the main thread with
cancellation on replacement/disconnect, and isolates progress recomposition from the rest of the
player. This is source-level optimization, not a measured frame-time or battery claim.

**Validation failure being corrected.** CI 43566209570 compiled the final sources, but the large-font
dark-mode test rejected an invalid qualifier order (`xhdpi-night`). The corrected order is
`night-xhdpi`; no test was removed. The other tests passed, and the light UI renders were produced.
The retry also enables warnings-as-errors for lint, documents actual test/build commands in
AGENTS.md, caches branch test dependencies, and makes future release notes list unrun gates.
Light previews from that run were inspected: songs and album actions are readable, with ordered
track rows. The standalone browser test now supplies a Material Surface, matching the real
Scaffold's background/content colors; this matters for a faithful dark-mode render.

**Additional model verification.** Commit `f1b286d` preserves band names and live/remixed variants
when normalizing SongKey. Pure tests pass offline: 26 tests (10 SongKey, 8 library index, 8 lyrics),
using `GRADLE_USER_HOME=/workspace/.gradle-music ./gradlew :core:model:test :core:lyrics:test
--offline --no-daemon`. The restricted shell blocks Gradle's local lock socket; the same offline
command succeeds outside that sandbox. No SDK or license bootstrap was needed for these modules.

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

## Active checkpoint: personal library and player block (2026-11-14)

Owner priority: finish the player to a high functional standard for Pixel 10 Pro XL / Tensor G5,
WF-1000XM5, Bluetooth speakers and phone speakers. No customization work.

Previous head `4560b91`: CI 43566729401 succeeded (test, lint, APK build, owner signing).
New block: separate user.db, transactional playlist editing, favourites, audible-start history,
lyric-offset storage, missing-file matching, queue actions, shuffle/repeat and integration tests.
This checkpoint is source-complete but Android CI verification is still pending. Exported Room
schema must be retrieved from the genuine KSP artifact and committed, then validated in tests.
Next: validate this block, harden queue identity/shuffle insertion, add system output switching
and honest signal-path details. Physical playback/codec/battery gates remain unrun.
