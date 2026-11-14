# Handoff

## Active continuation: audio processing (Codex, 2026-11-14)

Main now contains `9c7dd00`: file-tag integration + new player surface + embedded lyrics.
Code `85d7748` passed CI 43638096372; main release run 43638695765 is being monitored.
Temporary metadata branch was deleted after integration. Work continues from main on
`agent/m3-equalizer`, not on another agent's unmerged branch.

Owner clarified: every listening entry point matters (albums/folders, shuffle, playlists). Wants
ready Sony WF-1000XM5 correction and EQ. No crossfade/transitions; gapless still required.
No visualizers; wallpaper palette everywhere; functionality before full UI polish. Asked to
review the logic from the start, not just accumulate controls. See LOCAL_PLAYER_REVIEW.md.

Current new work is an unconnected pure core:dsp scaffold (RBJ filters, state per channel,
neutral bypass and bounds). Local `:core:dsp:test --offline --no-daemon` passed all 7 cases (Gradle 9.9.1, JDK 21). The
headroom policy needs refinement from conservative summed boosts to combined response before
Sony integration. Do not claim EQ works in the player yet.

Next: implement AudioOutputProvider delegation with buffer-contract regression tests, service-owned
per-output settings and safe transitions, then EQ controls/preset. Standard Media3 processor chains
are skipped on float output, so do not wire there and silently miss hi-res playback. ReplayGain
needs decoded-item association; audible-transition listeners alone are insufficient with prebuffering.


## Codex integrated local-player delivery (2026-11-14)

Branch `agent/m1-file-metadata`, based on current main `f04db66`. The owner supplied Claude's
latest chat: Claude was working on design but the owner reports its quota is exhausted. Codex now owns
the integrated local-player delivery (12-hour travel deadline), including UI. Codex takes the open M1 task wiring the
existing tag reader into the library. No parser duplication. Task claimed in ROADMAP.

Latest owner steering: functionality first, then review every visible UI state for fit/finish.
Use only wallpaper-derived system colors, including the player; never artwork-derived colors.
This is now an explicit AGENTS rule. Existing AppTheme already uses wallpaper colors. No visualizers.

Implemented: incremental file enrichment, exact audio properties, multiple artists/genres, stored
ReplayGain/lyrics/MusicBrainz/cover range/delay/padding, cache v3. Per-file IO failure falls back
and retries; permission/cancellation abort preserves the previous cache. New tests cover cache
reuse/invalidation, metadata precedence, fallback, malformed values and separate collections.
CI 43637047860 found a non-void expression-body JUnit method in the new scanner tests.
The method now asserts its result and returns Unit; no tests removed. Complete player CI pending.
Local model/tag-reader tests pass with the documented offline command.
Physical library performance and real-file coverage remain unmeasured.

Owner added PixelPlayerHQ/PixelPlayer as a possible design reference and explicitly rejected
visualizers. README and the published Home/player screenshots were reviewed; findings and
Google-quality acceptance criteria are in DESIGN_DIRECTION.md for the design agent. No reference code/assets were copied. The owner then assigned design to Codex while Claude is
unavailable: Google Sans Flex from Earbuds, official Material Symbols subset/generated vectors,
new player surface and local embedded-lyrics UI with seeking and saved timing offset.
CI 43637566474: all 132 distinct test cases passed (132 executions, zero failures/errors).
Player light and dark/font-scale-2 renders inspected: controls and typography are readable; the
large-font page scrolls to its lower actions, and that action is exercised by the test. These are
component renders with fixture data and no artwork, not approved goldens or phone screenshots.
Lint caught an unused icon-font resource because runtime uses generated vectors. The source subset
now lives under scripts/assets, outside APK resources; no lint suppression added. Final code head `85d7748` passed full CI 43638096372: tests, debug/release lint, R8 APK builds
and owner-signature verification. Code is ready for shared main; this final entry changes only docs.

Previous player head `f1ba244` passed CI 43571427496: all tests, lint, R8 and owner signing. It is
already in main and release music-v1018 via Claude's integration. Earlier pending-CI language below
is historical. The pasted Claude chat reports the owner has 890 tracks and audible FLAC playback;
this is reported device evidence, not a new measurement by this agent.


## Session 3 (Claude, 2026-11-14): review of the Codex branch, tag reader

Read first: this entry, then the Codex entry below (still accurate for its branch).

**Reviewed.** `agent/m0-first-run-reliability` at `f1ba244`: CI 43571427496 is green (tests, lint,
R8 release, owner signature). Code read: `PlaybackService` and the diff summary. Sound work: custom
commands are limited to the app's own uid, state is persisted and restored without starting audio.
The owner then asked for one shared line, so `main` was fast-forwarded to
`agent/m1-tags`, which contains the Codex branch and the tag reader. Both task branches are done.

**Added** (now in `main`): `:core:tags`, a pure Kotlin reader of
tags and audio properties from the files themselves (ARCHITECTURE decision 8, rewritten). Not wired
into the scan yet: the next task in ROADMAP M1 says exactly how.

**Verified.** `gradle :core:tags:test`: 14 tests, 0 failures (cloud sandbox, Gradle 9.9.1, JDK 21).
`[код]` Fixtures are built from the format specifications byte by byte; no real file has been read.

**Only on paper.** Behaviour on real files (odd taggers, huge embedded pictures, broken headers).
The whole-library check on the phone is its own ROADMAP task. The targets table in ROADMAP is a
list of goals; none is measured.

**Owner's direction this session.** Local player to Poweramp/Symfonium level first; streaming and
Soulseek after. Squeeze everything out of this phone: battery, quality, speed, features, each
proven by measurement (ROADMAP, top section). Kotlin stays the language; C++ only where a
measurement shows the need (the equalizer's inner loop is the likely first case).

**Next.** 1) Owner installs the `main` release and runs docs/PHONE_CHECK.md. 2) Wire the reader into the scan.
3) Artwork from the picture ranges. 4) ReplayGain using `AudioTags.replayGain` (M3).

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
