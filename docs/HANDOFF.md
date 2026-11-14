# Handoff

## Current task and branch (2026-11-14)

Owner priority: a dependable local music player for Pixel 10 Pro XL (Tensor G5, Android 17),
Sony WF-1000XM5, paired Bluetooth speakers and the phone speakers. Poweramp/Symfonium are the
functional quality reference. Customization and cosmetic expansion are deferred. Do not claim
sound-quality parity without physical measurements.

Branch: `agent/m0-first-run-reliability`. Draft PR: https://github.com/DKurocl/music/pull/1
Main is unchanged by this work. Owner signing secrets are configured; the old foundation claim
that they are missing is superseded by successful signed CI builds. Obtainium installation has
not been verified. Preserve the existing signing identity and version pins.

## Implemented blocks

- Reliable scans, permission-denial/settings flow, error/retry UI, foreground-only file observation,
  controller lifecycle, buffering-aware playback controls and unknown-format handling.
- Shared library index: songs, albums, album artists, genres and folders; disc/track ordering;
  cancellable, literal prefix and diacritic-insensitive FTS search in rebuildable library.db v2.
- Durable user.db v1: playlist create/rename/delete, ordered add/move/remove, collection additions,
  favourites, actual playback-start history and stored lyric offsets. UI offsets editor is not built.
  Missing files keep their saved metadata; moved matching recordings resolve for personal collections.
  Database updates never use destructive migrations. Genuine KSP schema is committed; migration
  baseline and future-version refusal tests are included.
- Queue editing by stable occurrence keys, duplicate occurrences, Play next/add to queue,
  shuffle and repeat off/all/one. Play next updates the service's actual shuffle traversal.
- Paused queue recovery with current position and modes. Versioned AtomicFiles use a queue generation
  to reject a stale cursor. Queue metadata is not serialized on every position update. Active playback
  checkpoints every 15 seconds; pause/seek/transition/teardown also checkpoint. Restore does not prepare
  the decoder or start audio. An explicit new selection wins over asynchronous restore.
- A process-owned IO journal orders final writes before a recreated service reads state. It coalesces
  queued writes and suspends while idle; it has no platform job, timer, service or wake lock of its own.
- Service-owned sleep timer, system media-output switcher, media-volume hardware keys and passive
  route updates. Audio path reports decoder and actual configured AudioTrack encoding/rate/channels.
  It does not infer mixer format, Bluetooth codec, LDAC bitrate or bit-perfect output.

## Verification

Latest fully green baseline: `4560b91`, CI 43566729401. Downloaded XML reports contain 67 distinct
cases / 67 executions, zero failures/errors. Tests, debug/release lint, APK builds, R8 and owner
signature verification passed. Light previews and dark font-scale-2 album preview were inspected;
these are component previews, not approved goldens or physical-device screenshots.

Expanded player `e2b5d37`: CI 43570410356 reports 105 distinct cases, 104 passed and one failed.
All queue recovery, lifecycle, shuffle, persistence, database schema and diagnostics cases passed.
CI 43570976901 reproduced the remaining dialog failure despite explicit clock advancement.
Root cause matches open Robolectric issue 8460: custom window-size qualifiers with text input in
a Dialog cause a measure/layout idle loop. The interaction test now uses default window metrics
and normal synchronization; manual clock/attachment workarounds were removed. Pixel-sized browser
and large-font rendering tests keep their explicit qualifiers. Full CI is being retried.
[docs] https://github.com/robolectric/robolectric/issues/8460
Lint and APK build did not run after that failed test command; do not claim this head is green.

Earlier checkpoints:
- `04bef1b`: CI 43549776145 passed initial hardening, 35 regression cases.
- `92c4fdf`: CI 43550547422 passed library/FTS work.
- `5aa0d92`: CI 43564927822 passed observer/UI checks, 56 cases.
- `3e2fe37`: CI 43568918162 compiled the personal-library block, then failed two UI tests.
- `a880b59`: CI 43569872767 compiled output/queue recovery, then failed only text-input auto-idling.
  Delete confirmation now targets its dialog. Text-input assertions use the normal clock
  and default test window metrics (see the upstream issue above). No failing test has been removed. Test tasks use --continue so
  independent failures are reported together; APK build still requires the complete test command to pass.

Local pure Kotlin verification passed after shuffle-order tests were added:

```sh
GRADLE_USER_HOME=/workspace/.gradle-music ./gradlew :core:model:test :core:lyrics:test --offline --no-daemon
```

Local environment: JDK 21, authenticated GitHub, no Android SDK/device/KVM. Gradle requires the
permitted shell escalation for its local lock socket. CI has SDK 37 and the owner signing key.
Never infer physical audio behavior from Robolectric API 35. Test minSdk override is only for tests;
APKs retain minSdk 37 and arm64-v8a. No version pins were downgraded.

## Remaining limits and next work

1. Finish CI, fix every failure, inspect exact test XML and lint/R8/signature results, then update
   this entry and ROADMAP with the verified head. Update PR title/body around the whole final change.
2. Run docs/PHONE_CHECK.md on the target phone. No ADB device is connected here. Required checks include
   24/96 output, gapless FLAC/MP3/Opus, long screen-off playback, calls/focus, XM5 buttons/wear detection,
   Bluetooth speaker handover, phone-speaker fallback, queue restore and battery/thermal measurements.
3. Existing float output is a configured preference, not a measured final sound path. LDAC 990 kbps,
   offload savings, USB bit-perfect and processor-specific gains are unverified. Network casting is
   not implemented; the output action controls system media routes.
4. Queue recovery currently retains playable URIs. Personal collections resolve moved files, but saved
   playback queues do not yet re-resolve changed MediaStore URIs. Missing URIs surface a playback error.
   Queue reordering uses accessible up/down actions; drag-to-reorder is still open.
5. Finish the real tag-reading pipeline before ReplayGain/R128. Preserve recording identity, expose
   known file properties, test clipping prevention and album gain, and measure the actual path. Do not
   add guessed EQ, forced Bluetooth settings, silent volume boosts or invented Tensor-specific tuning.
6. Reviewed goldens, automated accessibility, API 37 runtime checks, cold start, baseline profiles,
   device background inspection and Obtainium installation remain unrun gates. App data has no export
   yet and is removed by uninstall (backup disabled). Do not claim user.db survives uninstall.
