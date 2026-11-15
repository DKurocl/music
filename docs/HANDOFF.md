# Current functional work: local artwork (Codex, 2026-11-15)

Branch: `agent/m3-replaygain`. Owner still requests functionality first, no unnecessary APK builds
or releases. Source-only [skip ci] checkpoint; do not merge this batch to main yet.

Implemented:
- Embedded picture range -> cover/folder/front file in the track's selected internal-storage folder
  -> MediaStore album art. Existing read-only document-tree grants are reused; no new permission,
  network request, dependency or database schema. No bitmap decoding during library scans.
- Private non-exported read-only provider consumed by Coil in library/player and by media metadata.
  Image SHA-256 deduplicates disk variants at 192/512/1280 px. Preserve aspect, no upscaling,
  bounded source bytes/dimensions, software sRGB decode, atomic writes and 128 MB disk pruning.
- Manual Rescan/Reload and folder changes invalidate request identities through a persisted local
  revision. Ordinary lifecycle refresh does not invalidate the cache. Current artwork metadata is
  updated without explicitly seeking or preparing the player. Wallpaper colors remain unchanged.

Verified locally, after final source changes:
- `python3 /workspace/toolchains/music-jvm/check.py`: 117 tests passed. Includes 3 artwork-plan
  cases for dimensions, filename priority and ambiguity. Expected malformed-XML fixture logs remain.
- From /workspace/toolchains/music-room-check:
  `GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle test --tests ua.olex.music.library.LocalArtworkCacheTest --tests ua.olex.music.library.LocalMusicFilesTest --no-daemon --console=plain`
  passed 9 tests, zero failures/errors/skips. Native Robolectric graphics decode real PNG bytes from
  embedded offsets, validate colors/sizes/cache hits, shared variants and invalid-embedded fallback.
  Storage tests cover chosen-folder cover priority, scope boundaries, revision persistence and the
  preceding sidecar behavior. URI tests preserve source fields and absent optional fields.
- From /workspace/toolchains/music-ui-check, `compileKotlin --no-daemon --console=plain` with the
  same GRADLE_USER_HOME/toolchain: succeeded for all actual main Kotlin/Compose sources. Temporary
  font R ID stand-in only; this is API/type checking, not AGP/resource/manifest linking or rendered UI.
- `git diff --check` passed. Logs: /workspace/verification/artwork-pure-tests.log,
  local-artwork-tests.log and ui-typecheck.log.

No full CI, APK, release, Android lint, system picker test or physical Pixel check was run.
Notification/lock-screen artwork, uninterrupted metadata replacement and memory/latency on the
Pixel still need acceptance. The private provider does not grant external clients direct access.
Only explicitly chosen primary internal-storage folders are supported; use current Lyrics > Sources
folder controls and Rescan after editing an external cover file. A broader library-folder settings
entry can follow. No performance or competitor superiority is claimed from these local checks.

Next: queue drag and selection retention, then a coherent phone acceptance batch when requested.

# Current functional work: scoped local sidecar lyrics (Codex, 2026-11-15)

Branch: `agent/m3-replaygain`. Owner instruction remains: functionality first, no unnecessary full
APK builds/releases. Keep [skip ci] source checkpoints; do not merge this batch to main yet.

Implemented:
- Optional folder selection from Lyrics > Sources via Android OpenDocumentTree. Read-only persisted
  grants to selected internal-storage primary subtrees; multiple roots, explicit clear access,
  revoked-grant detection. No broad storage permission, manifest permission or network addition.
- LRC, TTML and plain TXT sidecars beside the exact audio filename stem. Lowercase LRC opens
  directly (no directory walk); otherwise only its folder is queried for supported extensions.
  Exact case is retained, ambiguous matches are not chosen; strict UTF-8 and 1 MB bound.
- Sidecar -> embedded fallback, source label and storage notices, Reload, configured-folder controls.
  Reads/parsing run off main with cancellation. Old-track lyrics are hidden during async transition;
  offset updates carry an expected mediaId. Controls share the scrollable lyric body at large fonts.
  Plain/empty lyrics do not poll the clock. Timed loops remain lifecycle/playing-bound.
- TTML refuses DTD/entity declarations and uses a non-fetching resolver.

Verified: pure model/tag/lyrics/DSP/delta/selection suite now 114 tests, zero failures.
Focused storage harness from /workspace/toolchains/music-room-check:
`GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle test --tests ua.olex.music.library.LocalMusicFilesTest --no-daemon --console=plain`
passed 4 tests, zero failures/errors/skips. Uses real document URI construction, a registered local
ContentProvider, file descriptors and Robolectric grant state. Covers persisted configuration,
LRC fast path, extension fallback, scope boundaries, revocation, size and invalid UTF-8 handling.
Fixture initially lacked attached provider Context, then host JDK denied FileDescriptor reflection;
fixed fixture attachment and added the standard test-only java.io opens argument. Added matching
unit-test JVM argument to core/library build.gradle.kts; no production permission/behavior bypass.

All actual main Kotlin/Compose sources compiled successfully again in the standalone Android-variant
UI harness (same command/toolchain described below), including the new lyric flow/source controls.
A temporary font R ID is still a TYPE-CHECK stand-in only: no resource linking, real AGP build, R8,
Android lint, rendered UI, Android system folder-picker flow or physical Pixel test was executed.
The final removal of one unused import needs no separate compile. New folder-selection UI test was
authored, not run. Existing focused SQL/playback/bulk evidence below is unchanged.

Limitations: only internal primary-storage folder trees; exact stem names; UTF-8 sidecars; no fetched
lyrics or sidecar writing, no word-fill rendering yet. Root selection is currently used for sidecars,
not the future direct file scanner. Embedded and sidecar offsets remain in user.db.
Next functional work: local artwork with the same chosen-folder access; queue drag / selection
retention; then repeatable phone acceptance when the owner wants a coherent APK build.

# Latest verification: standalone Android/Compose source type check (Codex, 2026-11-15)

Owner still defers unnecessary full APK builds/releases. No APK, full CI or release was triggered.
Active functionality is on `agent/m3-replaygain`, code checkpoint b5c3c96.

From /workspace/toolchains/music-ui-check:
`GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle compileKotlin --no-daemon --console=plain`
succeeded for ALL actual application/core main Kotlin sources with pinned Kotlin/Compose plugin,
KSP, Room, Compose BOM/Material3, lifecycle/activity, Media3 and Coil dependencies. Android variants
were explicitly selected; default JVM resolution initially selected desktop variants missing
LocalContext/dynamic colors/AndroidViewModel, which was corrected in the harness without changing
production dependencies. One temporary R.font ID stand-in was used only for type checking; the
actual google_sans_flex font resource exists. No resource linking, APK, R8, Android lint or device
runtime was exercised. This is source/API compilation, not a real AGP application build or UI test.

Thus the new ReplayGain/EQ/import, M3U8, sorting and multiselect Compose source is now type-checked.
Authored Compose interaction tests still have NOT run. Existing point-test counts below remain valid.
The user-facing ~50% local-feature estimate is qualitative, not a benchmark or a verified numerical
parity score. No sound/speed/battery advantage over either competitor has been demonstrated.

# Current functional work: multiselect and bulk queue (Codex, 2026-11-15)

Active source branch: `agent/m3-replaygain`. Keep owner instruction: no unnecessary APK builds or
releases, source checkpoints use [skip ci], UI/AGP validation deferred, do not merge to main yet.
The preceding functional batch includes ReplayGain/EQ/AutoEq, M3U8 and persisted sorting.

Implemented: selection mode via long press or Select, per-row accessible checkboxes, Select all,
Clear/Close, Back-to-cancel, and Play selected / Play next / Add to queue / Add to playlist.
Actions preserve displayed order rather than tap order. Selection is scoped to tab/collection/query,
retains IDs through sort changes and prunes removed rows. No selection work on empty/default startup.
Current limit: 20000 items, explicitly shown. Selection is ephemeral and clears on Activity/process
recreation; it does not serialize a huge saved-state Bundle. Favourites/history/playlist editing
multiselect and batch favourite operations are not included in this first library selection block.

Found/fixed old enqueue >500-item rejection: QueueCommand now uses Media3 BundleListRetriever for
paginated Binder transport. The service resolves all items on Dispatchers.Default before enqueue;
private same-UID checks, count bounds and URI validation remain. The client awaits completion under
its queue mutex, preserving append request order. Errors appear directly in Library with Dismiss.
The existing service-owned shuffle/live-cursor insertion algorithm is unchanged.
Source inspected: media3-common 1.11.1 BundleListRetriever.java, public pagination/in-process APIs.

Verification: pure JVM run passed 110 tests, including 3 new selection cases. Focused playback
harness passed 2 BulkQueueCommandTest cases: 1001 ordered items retain URIs/occurrence keys via Binder,
and missing URI validation fails before mutation. Actual playback sources and test sources compiled
against pinned Media3/Android APIs. Remote Binder transport itself was not instrumented; pagination
is the upstream class's implementation, not a measured cross-process result of this test.
The later early count-check/error-text addition is straightforward source inspection, not a new
full build. New Compose multiselect interaction test authored, not compiled/run under AGP.
Existing earlier evidence: 12 audio/checkpoint tests, 9 user-repository tests; see entries below.
No APK, lint, full CI, release or physical Pixel performance checks ran in this phase.

Next functional priorities: local artwork and sidecar lyrics; queue drag; retaining selection across
rotation. Before eventual integration, run one coherent deferred verification batch with the owner.

# Current functional work: persisted library sorting (Codex, 2026-11-15)

Active branch: `agent/m3-replaygain`. Source batch includes ReplayGain/parametric EQ/AutoEq, M3U8
and now track sorting. Owner still requests functionality first: no new APK builds/releases or
full CI per edit. Use [skip ci] source checkpoints, keep unverified UI out of main.

Implemented sorting: Default, Title, Artist, Album, Year, Duration, File name and descending order.
Preferences are independent for Songs, Search and album/artist/genre/folder track lists, retained
across app restarts. Playlist order stays manual. Album grouping sort preserves disc/track order;
unknown values stay last in either direction, ties are deterministic across input permutations.
Keys are precomputed once per item. Non-default sorting runs off main. A source-identity/order guard
hides pending old results; row taps, Play all and Add all use exactly the displayed ordered snapshot.
Default collection ordering reuses the original list without asynchronous startup work.

Verified: `python3 /workspace/toolchains/music-jvm/check.py` passed 107 tests, including 5 sort tests.
The new Compose sorted-queue interaction test is authored but NOT compiled/run in AGP. No APK/full
CI started. Existing focused M3U8 SQL evidence remains 9 passing tests; audio evidence 12 passing
focused playback tests. No new phone measurements. Full UI/AGP/lint verification remains deferred.

Next functional priorities: multiselect/batch actions; offline artwork/sidecar lyrics; queue drag.
Before eventual main integration, run one coherent full verification batch when owner wants builds.

# Current functional work: M3U8 playlists (Codex, 2026-11-15)

Active branch remains `agent/m3-replaygain`, now carrying the coherent local-player functional batch.
Owner requests functionality first with no unnecessary APK builds/releases. Preserve this instruction:
commit/push source checkpoints with [skip ci], keep full AGP/UI gates deferred, do not merge to main.
No environment configuration or publication action is needed.

Implemented M3U8 import/export in code:
- UTF-8-only, bounded 8 MB / 20000-entry codec, EXTINF/PLAYLIST, BOM and CRLF, local file URIs and
  raw paths. HLS and unsupported URI schemes are reported/rejected without network access.
- Exact path lookup first; relocated matches require a unique suffix of at least two segments.
  Filename-only guessing is excluded. Missing/ambiguous paths become durable saved references that
  resolve when a matching file becomes available. Normal saved-song identity rules remain unchanged.
- Relative ../ paths require a known playlist folder. Android primary-storage document IDs supply
  it where available; the preview has an editable folder field with explicit root choice (.).
- Preview lists exact/relocated/missing/ambiguous/unsupported/repeated results. This schema stores
  each file once per playlist, so repeats are explicitly shown as skipped. Repeated occurrences
  still need a future user.db migration; do not claim full playlist parity.
- One import transaction saves header/references/positions, including unavailable songs, in batches.
  No user.db schema/lockfile change. No saved favourite/history/offset deletion.
- Export captures a selected playlist, includes unavailable references and uses absolute file URIs
  independent of destination folder. URI encoding preserves Unicode byte identity, including NFD;
  java.net.URI.toASCIIString would normalize it and was removed after the test caught that error.
- System OpenDocument/CreateDocument controls, cancellable preview resolution off main, strict UTF-8
  input, bounded IO, per-line issues, meaningful errors, duplicate-click guard and rotation fallback
  for the selected export playlist. Source audio and existing playlists are never rewritten.

Verification:
`python3 /workspace/toolchains/music-jvm/check.py`: 102 tests, zero failures, including 12 M3U8
cases and a 10000-entry correctness case. This is not a target-device timing benchmark.
Focused user repository harness from /workspace/toolchains/music-user-check:
`GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle test --tests ua.olex.music.user.UserRepositoryTest --no-daemon --console=plain`
passed 9 tests, zero failures/errors/skips. Actual UserRepository, Room-generated DAO/database and
model sources compiled; Robolectric exercised reopen, batched imports, order, missing references,
validation/rollback and preservation of favourites. The harness explicitly includes pinned SQLite
Android classes because the standalone JVM variant selection omits them. No SDK/APK build occurred.
New Compose import/export/picker interaction test was authored but NOT compiled/run under AGP.
The previous focused 12 playback tests remain the audio checkpoint evidence; no extra full CI run.
`git diff --check` passed. Full Compose/AGP/lint/APK and physical Pixel checks are deferred by owner.

Next functional priorities: library sorting and multiselect/batch actions; offline sidecar lyrics
and artwork; queue drag editing. Before eventual integration run one deferred coherent full check.

# Current functional work (Codex, 2026-11-15)

Active branch: `agent/m3-replaygain`. Owner now requests functionality first and no unnecessary
releases or full APK builds. Do not merge/publish or trigger a new full pipeline for each edit.
Use focused tests. The next source checkpoint uses [skip ci] to preserve code without a new build.
This preference overrides earlier routine push/build/release instructions for this phase.

Implemented locally:
- ReplayGain Off/Track/Album, tagged-track preamp and peak guard. Album fallback keeps gain/peak
  pairs intact. Unknown peaks prevent positive boost; untagged and legacy queue entries are neutral.
- Scanner tags -> item extras -> per-source extractor Format.customData -> timestamped decoded PCM.
  No audible-transition volume listener. Format/endianness/frame count preserved for PCM8/16/24/32
  and float. Original buffer bypass, stable identity/partial writes, reusable direct storage.
  Global persistent settings, service-owned private commands, queue checkpoint tags and controls.
- Parametric EQ editing: frequency, Q and Peak/Low shelf/High shelf type, validated as one update.
- AutoEq TXT import: supported PK/LS/LSC/HS/HSC commands, preamp, comments/BOM/CRLF, bounded size,
  duplicate/filter-count validation and complete rejection of unsupported commands. Import leaves
  processing disabled and targets the route captured when the file picker opened. IO off main;
  service route guard protects a Bluetooth switch during reading.

Verified locally: `python3 /workspace/toolchains/music-jvm/check.py`: 90 JVM tests pass.
Focused playback harness command from /workspace/toolchains/music-playback-check:
`GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle test --tests ua.olex.music.playback.ReplayGainIntegrationTest --tests ua.olex.music.playback.PlaybackStoreTest --no-daemon --console=plain`
passed 12 tests (4 source/PCM/codec integration, 8 checkpoint tests), no failures/errors/skips.
Actual playback source and test sources compiled in that JVM harness against Android 17 APIs.
The Compose controls/import picker and new UI interaction tests have NOT been compiled/run in AGP.
Phone gapless, decoder/mixer depth, interruptions, sound and battery remain unmeasured.

CI 43653974545 for initial f79e399 failed on an inferred anonymous Array<Extractor> type.
Fixed with explicit map/return types; actual corrected playback sources compiled and focused tests
passed locally. No new full CI run, lint, APK or release requested after the owner's instruction.
Full UI/AGP checks remain pending by that instruction, not a network blocker.

Previous incremental-cache code is already main 3cce85f, CI 43651556524 XML: 167 cases, zero failures,
errors or skips, both lints/APKs and owner signature passed. Main CI 43653234944 succeeded and
published music-v1041 BEFORE the owner paused releases. Downloaded APK SHA-256 matches GitHub's
published digest: 2db2c662fc1bf2af9df5233678436fff189d5f96354c368281b68c30d1c29edf.
The standalone Room harness has incomplete Android variant classpath modeling; its local failures
are not production CI failures. Use the retrieved production evidence for the cache change.
Network access is functional; no old environment draft changes were reapplied.

Next useful functional block: M3U8 import/export and folder/sort/batch workflows, then offline
sidecar lyrics and queue drag editing. Before eventual integration run the deferred production
checks once for a coherent batch. No claim of Poweramp/Symfonium superiority without phone evidence.

# Active: sample-associated ReplayGain (Codex, 2026-11-15)

Work branch: `agent/m3-replaygain`. Implementation and regression tests are in progress; do not
merge until CI confirms tests, both lints and debug/release R8 builds. Physical playback is unmeasured.

The previous incremental-cache change is now in main at 3cce85f. Retrieved CI 43651556524 XML:
167 distinct cases, 167 executions, zero failures/errors/skips, including 11 LibrarySearchTest cases.
Both lints, both APK variants and owner signature verification passed. Main release CI 43653234944
is in progress. Its outcome and the signed release must be checked before claiming delivery.

Network publication is now active: GitHub API and Robolectric mirror requests succeed. No prior
network changes were reapplied and nothing was published by this agent.
The standalone local Room harness still has incomplete Android Gradle variant modeling and fails
with missing SupportSQLiteOpenHelper classes; this is a harness limitation, not a CI test failure.
Use production CI evidence above. Do not report this local full-suite attempt as passing.

ReplayGain code: opt-in Off/Track/Album, preamp, peak guard; source tags attached before extraction
and retained in the decoder's timestamped Format.customData. Gain precedes sink buffering and
conversion; no onMediaItemTransition gain changes. PCM format/byte order preserved, reusable direct
buffers, stable partial-write identity including bypass. Settings changes ramp; tracks do not crossfade.
Global preferences/session state and controls in Equalizer, with queue tag persistence/backward read.
Pure model/tag/lyrics/DSP/delta run now passes 87 tests; new Android integration/UI tests not yet run.
Next: inspect full branch CI, fix failures, verify source-to-buffer/queue/UI regressions, update
comparison/audio docs, then integrate after green. Phone gapless/24-bit/calls/battery gates remain.

# Active work: incremental cache and local-player comparison (Codex, 2026-11-15)

Unmerged branch: `agent/m1-incremental-cache`. Full CI/APK validation remains blocked by network
access to api.github.com and the absence of a local Android SDK. SQLite runtime tests additionally
need gcp.mirrors.maven.org for Robolectric native runtime. Do not merge or call this released.
Owner now explicitly defers network libraries; compare local workflows only.

[code] Replaced unconditional whole-library upserts with a linear snapshot delta inside the Room
transaction. Unchanged rows keep scanId, so no write/FTS trigger/library invalidation is needed.
Changed rows and removed IDs are batched at 500; cross-source/wrong-generation/duplicate snapshots
fail before writes. Existing failure/cancellation behavior and transactional publication remain.
Added six pure delta tests and five SQLite regression cases; updated the in-memory DAO test double.

Verified this session: `python3 /workspace/toolchains/music-jvm/check.py` compiled actual model,
tags, lyrics, DSP and ScanDelta sources with pinned Kotlin 2.2.21 and ran JUnit 4.13.2: 79 tests,
zero failures. Compiler dependencies were downloaded over TLS from Maven Central and checked
against its published artifact hashes. The XML parser stderr line belongs to malformed-input tests.
The helper is outside the checkout, not a replacement for Gradle/Android gates.
`git diff --check` passed. No Android SDK, emulator or phone tests were run. The SQLite execution of the new Room tests,
full production Gradle test, lint and APK build remain unrun, not passed.

Additional local validation (same source, no production-build changes): built a standalone JVM
Robolectric harness under `/workspace/toolchains/music-room-check`, using Gradle 9.9.1 (official
SHA-256 verified), Kotlin 2.2.21, KSP 2.3.13, Room 2.8.5 and Android 17 android-all API classes.
Actual library/model/tag sources, generated Room DAO/database and all library test sources compiled.
Inspected generated DAO: replace uses performInTransactionSuspending; snapshot/delete SQL generated.
This is JVM compilation against Android API classes, not an AGP or APK build.

Command from that harness directory:
`GRADLE_USER_HOME=/workspace/.gradle-music /workspace/toolchains/gradle-9.9.1/bin/gradle test -PplainJvmChecks --no-daemon --console=plain`
passed 11 tests (five LibraryRepositoryTest and six ScanDeltaTest), zero failures/errors/skips.
The six delta cases overlap the earlier 79-test run; do not sum these as 90 unique cases.
The scoped JVM configuration omits the unused native-runtime artifact only for these non-Robolectric
tests. The full suite retains it and could not resolve it: Maven Central redirects
nativeruntime-dist-compat 1.0.19 to gcp.mirrors.maven.org, denied by proxy. No SQLite tests ran;
no fallback runner, disabled assertions, library downgrade or verification bypass was used.
Added gcp.mirrors.maven.org to the existing network draft. User review/publication remains necessary.

Git fetch succeeded; GitHub API and both competitor product sites return proxy access denial.
Saved a network-only environment draft adding api.github.com, powerampapp.com,
forum.powerampapp.com, symfonium.app, www.symfonium.app, support.symfonium.app and
maven-central.storage-download.googleapis.com, preserving presets. Draft save is confirmed;
publication/runtime application is not verified. No secrets requested.

See PLAYER_COMPARISON.md for the complete local feature audit, limitations and ordered next steps.
Competitor capabilities are contextual references, not current-version or phone-verified findings.
Next: enable API access, inspect branch CI, fix any failures, then integrate only after green CI.
Phone measurements remain required before any sound, speed, battery or overall-parity claim.

# Handoff

## Active continuation: audio processing (Codex, 2026-11-14)

Main now contains `9c7dd00`: file-tag integration + new player surface + embedded lyrics.
Code `85d7748` passed CI 43638096372; main release run 43638695765 is being monitored.
Temporary metadata branch was deleted after integration. Work continues from main on
`agent/m3-equalizer`, not on another agent's unmerged branch.

Owner clarified: every listening entry point matters (albums/folders, shuffle, playlists). Wants
ready Sony WF-1000XM5 correction and EQ. No crossfade/transitions; gapless still required.
No backup/export: owner explicitly declined, persistence across updates is sufficient. Temporary
backup worktree was removed without implementation. No visualizers; wallpaper palette everywhere; functionality before full UI polish. Asked to
review the logic from the start, not just accumulate controls. See LOCAL_PLAYER_REVIEW.md.

New EQ block implemented: AudioOutputProvider wrapper preserves PCM16/float encoding/rate and
Media3 gapless handling. Reusable direct buffers, partial-write position accounting, exact disabled
bypass, per-channel double-precision RBJ state, combined-response headroom and a 10 ms parameter
transition. Per-output persistent settings and route-key command guards prevent Sony settings from
carrying over to phone/unrelated speakers. Private same-uid session commands only. UI has flat/manual
bands, preamp, opt-in Sony AutoEq preset and route/error labels. Sony preset source and MIT license
retained; it is not an individual-ear calibration. 15 pure DSP tests pass locally. Code `4aa3efe` passed full CI 43640064817. Downloaded
XML reports contain 156 distinct cases / 156 executions, zero failures/errors. Both lints, R8
APKs and owner signing passed. This final checkpoint changes documentation and a comment only.

Main release 43638695765 succeeded: music-v1025 is installable through Obtainium. EQ is not yet in
that release. Integrate this verified code into main and delete the task branch; monitor its release.
Further local-player work: library sorting, multiselect/batch actions, m3u8 and sidecar lyrics. ReplayGain is not implemented: decoded-item association must handle prebuffered transitions.
Full lifecycle/output/battery measurements remain phone gates. Never claim Poweramp/Symfonium parity
from JVM tests. See LOCAL_PLAYER_REVIEW.md for the full flow and remaining priorities.

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
