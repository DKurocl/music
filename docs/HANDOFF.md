# Offline online catalogue and search (Codex, 2026-11-18)

[код] Album, artist and account playlist metadata snapshots use atomic bounded disk storage.
Offline openings retain track order and duplicates. Online openings can refresh when the connection
returns. Album links are restored by video ID; confirmed artist name lookups keep their page ID.
No stream address, cookie, continuation token or raw provider reply is written by this cache.
A signed-in session has a separate cache namespace and likes outbox. Legacy unscoped likes are not
sent because their originating account cannot be determined. Inbound likes and playlist conflict
resolution are still open. The catalogue work does not touch lock-screen lyrics.

Search retries when the network returns and filters results against the current query's visible
tracks and confirmed source links. Only a matching file that appears in the results suppresses a
remote song. The disk snapshots are bounded and can be removed by Clear cache. This is not an
offline audio download feature. Live signed-in and Pixel checks are pending.

# Playback and artwork reliability (Codex, 2026-11-18)

Integration branch `agent/integrate-service-policy` combines the verified service policy at
`968a0ae` with the playback and artwork fixes. The merged source addresses the remaining app selection races,
cold-start notification entry, widget ordering, motion cover replacement and local cover locks.
The widget reads one preferences snapshot and serializes both submissions with its render generation.
Cover reads, writes and eviction use fixed lock stripes so the same digest keeps one lock.
Queue taps, shuffles, search and first mix pages supersede older app selections. Mix and search
preparation and system browse/search page construction run off the UI dispatcher. Local radio
uses recording-key statistics for ranking and recent-track exclusion. The player intent waits for a connected session with a track.

Sleep timer follow-up: the monotonic deadline survives an interrupted fade. Changing or cancelling
the timer stops only its ramp, and the end-of-track indicator also clears at terminal playback.
Added virtual-clock regressions for early ramp completion and cancellation. CI is pending.

Local diff check passed. Local APK compilation is unavailable: no Android SDK or Gradle, and the
skill's version probe could not reach the artifact endpoints from the shell sandbox. Branch CI and
physical Pixel/XM5, notification entry and motion playback acceptance are pending.
The service selects sources for mixes as well as direct playback. Durable artist/album pages and
account-scoped bidirectional sync remain open.
See AUDIT_CONTINUATION.md for the previous backlog.

# Service-source policy package (Codex, 2026-11-17)

Branch `agent/service-source-policy` starts at main `45be07f`. Foundation is integrated, verified
by CI 44172917731 (441 tests, zero failures/errors/skips, both lints, both APK builds, owner signature).
Published music-v1116: https://github.com/DKurocl/music/releases/tag/music-v1116
Main CI 44175207911 succeeded for 45be07f. Downloaded checks: 441 distinct cases/executions,
0 failures/errors/skips, all four exported-schema migration cases passed. Actual APK verified:
package ua.olex.music, min/target37, arm64-v8a, version1116, existing owner certificate
5c2e57db99715e0fc65befdcf8febd1a00222a104a24d635f27f97d949abc107. APK SHA256
4e244d46835faab55504d7bf7c6c326a4ebd59f6ec0a6873bff71957a8eaadf2 equals the release asset digest.
Three player/lyrics/queue component renders reviewed; the gallery is not a phone/layout golden gate.
Installation, Pixel/Sony audio/focus/Bluetooth, startup/battery and live account are unverified.
Old recording-sources branch is fully merged and deleted locally and remotely.

Owner asked to continue while asleep and repeated "continue". This package implements 52, with the
41 generation-skip refinement. The implementation routes set/add/enqueue/radio refill, paused startup and
system resumption through one bulk source policy built from all saved recording evidence. It
preserves requested video IDs, queue occurrence keys/context and real shuffle traversal. Unavailable
occurrences retain their position and expose a reason; their guarded URI cannot open an unrelated
file. Recovery is bounded by occurrence/source, and a healthy playing source is not replaced when
its downloaded file appears. Same-occurrence source changes do not count as another listen.
Explicit selection supersedes pending restore and rejects stale selection results.

All-recording evidence now prevents a metadata-only row or near-duration intermediary from merging
incompatible takes. Personal settings resolve known canonical IDs without re-registering a weak UI
snapshot. Search/credit hints require complete artist names, known durations and matching version
annotations; named remixes and live venues remain distinct. Source matching and queue conversion run
off main. MediaStore version/generation checks skip unchanged scans; manual Rescan stays forced,
unknown generation falls back to a full scan, and a change during a scan triggers the next refresh.
This is not a complete incremental MediaStore scanner. The service observer follows recording/source evidence changes as well as file/network/cache events,
and refreshes independently;
Play consumes the latest cached snapshot without waiting for scanning. Recovery cannot start the
next track after a concurrent Pause.

Local verification before full Android CI:
- pure JVM harness: 203 tests passed after row-identity and source-preference follow-ups;
- actual Room/native SQLite UserRepositoryTest: 25 passed, zero skipped/failures/errors;
- actual Room/native SQLite LibraryRepositoryTest: 9 passed, zero skipped/failures/errors;
- git diff --check passed. No dependency, schema or SDK pin changed.
Android evidence:
- CI 44188651400 (b9cd0c6): 477 distinct tests/executions, zero failures/errors/skips;
- CI 44191397033 (6cc00d7): 480 distinct tests/executions, zero failures/errors/skips;
- both runs passed tests, debug/release lint, debug/release APK builds and owner signer verification.
The latter includes source preference, row identity and slow-resumption regressions. A final observer
follow-up also invalidates future choices when durable recording/source evidence changes, without
waiting for a file/network/cache event. Source-event projections run on Dispatchers.Default,
including decoding saved recording relations. Delivery uses the same required CI gates for that head.

The app retains original online requests for actions; confirmed catalogue links expose local
quality/offline marks, and ambiguous raw files do not claim them. Online intent selects the best
confirmed file; explicitly chosen files/videos keep their preference and own unavailable artwork.

Delivery: only integrate the task branch after exact-source CI succeeds. Main then publishes the
signed APK automatically; verify the downloaded artifact, version, ABI/SDK and existing signer.
Current workflow evidence: https://github.com/DKurocl/music/actions
Next implementation: durable online pages and stable album/artist links (49/64), followed by
account-scoped inbound likes and playlist operation/conflict synchronization (56/58/59).

Still open: durable online pages and album/artist identity (49/64), inbound/account-scoped likes and
playlist synchronization (56/58/59), remaining audit/UI/device work in AUDIT_CONTINUATION.md.
Do not promote these local checks into Pixel/Sony, startup, battery or complete UI acceptance.

# Historical recording/source implementation notes (Codex, 2026-11-17)

Branch `agent/recording-sources`, based on main `d2ef360` after music-v1109. Owner asked to keep
working after release while asleep, then repeated "continue". This new block is not released yet.

Implemented: user.db v4 sources/aliases; conservative transactional recording reconciliation;
recording-aware catalogue, statistics, favourites, queue/checkpoint metadata and lyric files. A
confirmed merge preserves playlist occurrences, history event IDs, favourite state and latest settings.
A dated zero rating prevents resurrection of an older rating. Queue local-first substitution keeps
its video reserve. Lyrics read legacy/merged keys; reset cannot resurrect an old timing edit. The full
player now exposes online timing save (the prior release had its persistence path but hid this action).
Linked local hearts queue the YouTube operation too; remote inbound/account-scoped sync stays open.

CI for first implementation `648bd85`: https://github.com/DKurocl/music/actions/runs/44166893313
431 distinct tests/executions, 0 failures/errors/skips. Tests, lintDebug/lintRelease,
assembleDebug/assembleRelease and owner signer passed. Includes real exported-schema 3 -> 4 migration.
Final review found ambiguity, artwork fallback, explicit clears and physical-copy heart cases; follow-up
changes need their own complete CI before main. Local checks now pass 182 JVM tests (including physical-copy favourite state) and 23 native SQLite
repository tests. A bad single-basename test was corrected to an actually resolvable folder suffix;
basename-only matching is deliberately unavailable. Full follow-up Android CI is pending.
Final review also isolated CUE keys from literal paths, stabilized unresolved ambiguous files across
repeated plays, kept explicit recording IDs authoritative, and moved rich queue decoding/matching
off the UI thread.

Standalone user harness lacks Android schema asset packaging, so its MigrationTestHelper cases fail
with missing assets. Actual Android CI passed them without assertion or dependency changes. Independent
host SQLite also executed the exact v3 -> v4 SQL and compared all exported v4 columns/indexes/FKs.
No Pixel/Sony, live account, startup/battery trace or complete reviewed UI golden gate was run.

Next: final branch CI, inspect source/lyric regressions and migration, then fast-forward main and
verify the signed release. Follow with central service source selection (52), saved online pages
(49/64), account-scoped bidirectional sync (56/58/59), and remaining audit work in
`docs/AUDIT_CONTINUATION.md`. Detailed model contract: `docs/specs/RECORDING_SOURCES.md`.
A source-policy prototype and integration notes live only in `/tmp/music-next-sources`; they are
uncompiled and not in this branch. Central selection must consider all known recording evidence,
not just the raw local index. In particular do not let an automatic UI local-first substitution
claim a physical file that is ambiguous between two saved takes. Preserve queue occurrence keys
and avoid a second history event during same-occurrence source recovery.

# Current continuation (Codex, 2026-11-17): start here

Code `bc99d27` is integrated into `main` from `agent/unified-library-continuation`.
Branch CI passed: https://github.com/DKurocl/music/actions/runs/44114015206
Main delivery passed: https://github.com/DKurocl/music/actions/runs/44116900021
Published: https://github.com/DKurocl/music/releases/tag/music-v1109 (versionCode 1109, code bc99d27).
Downloaded APK verified: ua.olex.music, min/target 37, arm64-v8a, existing owner certificate;
SHA-256 c0e7b1251025e15fc1e67a36fb69d9a936d61d8af91f306b8febd9746db80e44 matches the release asset.
Installation on the phone is not verified. The owner asked to continue after this release while asleep.

## Implemented and checked in code

Eight of the owner's remaining points: 8, 21, 33, 34, 51, 53, 54, 60. Partial: 45, 50, 74.
Also completed the online lyric timing save path (32) and widened the system catalogue (66).
These counts refer to implementation and software checks, not physical-device acceptance.

- Active saved online metadata joins scanned files in Home, library browsing, statistics, smart
  playlists and prefix search, including songs beyond the 200-event history page. Local files win
  without rewriting stored YouTube references. CUE tracks do not split twice during projection.
- Account playlist discovery follows all continuations. Ordered song pages retain deliberate
  repeated video ids, share concurrent loads, preserve retry tokens and reject token cycles.
- Radio/autoplay refill lives in the playback service with an application-owned cursor, independent
  of UI connection. Loaded pages commit only against the requesting queue. Manual selections
  and shuffles clear the previous mix context and invalidate its pending continuation.
  The radio cursor is not persisted through process death yet. No UI controller is kept connected for background work.
- App and lock-screen lyrics share source selection and offsets. Online timing edits save too.
  The owner's lyric-as-artist option (73) stays. Word clocks use Compose's infinite frame policy;
  only drawing reads the clock, and cached text paths avoid per-word row recomposition.
- Offline selection keeps all queue occurrences, staging a paused queue if none are available;
  the service skips unavailable items without deleting them. Cache span revisions update marks.
- Coil, local artwork and artist metadata split the image share. Budget changes trim audio off
  main, close Coil's disk cache before replacement, and finish safely if Settings closes. Artist
  and Apple-motion lookups share two auxiliary permits, independently of stream resolution.
- Startup and system resumption share one journal read. Live selections win, matching occurrence
  keys guard shuffle/repeat recovery, and playback context survives checkpoint round trips.

## Verification

- `python3 /workspace/toolchains/music-jvm/check.py`: 165 tests passed (final pure model code).
- CI `44114015206`, code `bc99d27`: `gradle test -PminSdk=35 --continue --no-daemon`, then
  `gradle :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --no-daemon` passed.
  Downloaded XML: 406 distinct tests / 406 executions, zero failures, errors or skips. Includes
  21 added regression tests. APKs keep minSdk 37; Robolectric uses API 35.
- Owner-signature verification passed, matching the certificate in the prior successful CI.
- Inspected `lyrics-words.png` and `gallery/27-lyrics.png` from both 4784a19 and 2f565ad artifacts.
  These are component/gallery renders; no approved golden diff or actual phone screenshot.
- First expanded CI found a non-idling word clock in DesignGalleryTest. Standard infinite animation
  policy fixed it; added a manually advanced playback-clock test and a draw-without-parent-
  recomposition test. A cache coroutine receiver type error was fixed before the final green run.
  The first main publication was cancelled to include a final manual-selection regression guard.
  No test was disabled and no dependency pin was changed.
- Local machine has no Android SDK or device. No new Pixel/XM5, API 37 runtime, accessibility,
  battery, cold-start, audio-path or frame-time measurement. No physical performance claim.

## Next

The full remaining list is `docs/AUDIT_CONTINUATION.md`; 28 points are still open/partial or
intentionally retained, including the seven phone gates and option 73. Next code work: recording
IDs with multiple sources and a safe user.db migration (47/48/63), durable album/artist pages
(49/64), service source selection (52), bidirectional likes and playlist sync (56/58/59).
Navigation, system favourite actions, UI consistency and acceptance remain open too.
Run the concrete continuation scenarios in `docs/PHONE_CHECK.md` on the actual phone.

# Previous handoff

# Current state (Claude, 2026-11-17): start here

## YouTube Music as part of the library (Claude, 2026-11-17)

- Liked, playlists and history keep YouTube Music songs `[код]`: `SavedSong.from` stores the
  `ytm://` address as the path; `resolve` prefers the owner's file (same song key), else returns the
  streamed song (`SavedSong.online`). Songs liked or added before this change were saved without the
  address and still drop out when there is no file. Test: `UserLibraryTest`.
- A heart on a YouTube Music song is also a like on the owner's account when signed in
  (`YouTubeMusic.like`, `like/like` and `like/removelike`; not tried against a live account).
- Signed in: Playlists shows "On YouTube Music" (his playlists and Liked music) under his own
  (`accountPlaylists`, `FEmusic_liked_playlists`; parser checked on a fixture, not on a live library).
- Online songs: "Go to album" opens the album page YouTube Music named in the song's row
  (`albumOf`, remembered while parsing); "Go to artist" and the player's title/artist taps open the
  artist (the owner's own page when he has them).

## Moods on Home (Claude, 2026-11-17)

- A mood chip filters Home in place, as YouTube Music's chips do `[код]` (`MoodRow` chosen state,
  `rememberMoodView`, `moodItems`): a Play card, YouTube Music's playlists for the mood, "From your
  library" (songs whose genre fits, `Mix.moodSongs`, now also for genre chips like "Rock" and aliases
  like "Chill"), then YouTube Music's songs not shown above. Back or the same chip clears it. Mood
  answers are cached for the session; loading shows placeholders for at most 8 s; offline shows only
  the owner's songs. `MoodPage` (the old separate page) is no longer opened from Home.
- Not measured on the phone; mood chips' real labels depend on YouTube Music's home for the account.

## M10, second pass: one library (Claude, 2026-11-17, night)

Built and checked in the container like the first pass; not measured on the phone.

- Search is one list `[код]`: Artists, Albums, Songs; the owner's results first, YouTube Music's after
  them in the same row style with a cloud (`OnlineResultRow`), placeholder rows while it answers,
  no separate "YouTube Music" block. Play from any song plays the owner's songs then the online ones.
- An artist is one page `[код]`: opening an artist the library has (from Search, a song, an album
  page) opens the owner's artist page with the YouTube Music sections (`libraryTwin`); "See all"
  opens the full YouTube Music page (`OnlineTarget.Artist.full`).
- Offline `[код]`: `AppGraph.network` (default network callback). Online songs the stream cache
  doesn't keep are greyed out with `cloud_off`; Play skips them and starts at the next playable song,
  with one snackbar; online pages say "You're offline" and open by themselves when the connection
  returns (only on reconnect, never in a loop).
- Long playlists `[код]`: `albumMore` follows YouTube Music's continuation token (both forms) in the
  background and appends; not checked against a live answer (browse is blocked from the build machine).
- Back keeps the screen under an online page (scroll, open artist) through a saveable state holder.
- Play and Add to queue swap in the owner's files off the main thread; download marks redraw only
  their own row; the queue shows the cloud mark too.

## M10 built (Claude, 2026-11-17, evening)

Built and checked in the container (unit tests, lint debug and release, release build, gallery
screenshots looked at). **Nothing below was measured on the phone yet**: the "Done when" numbers in
`docs/specs/M10_ONLINE.md` (frame times, time to first audio) still need `dumpsys gfxinfo` on the
Pixel.

- D4 `[код]`: the library index (`YouTubeMusic.indexOf`) is built once per library on
  `Dispatchers.Default` (LibraryViewModel init); pages use `rememberOwned` (lookups only, or the build
  off the main thread). No composable calls `localCopy`. Artist, album and artist-id answers are kept
  in a 48-page LRU for the session (`peek*`, `cached`); a cached page draws in its first frame. The
  header comes from the card (`OnlineTarget.Album.cover/subtitle`), placeholder rows of real height
  replace the spinner. `warm()` resolves the first online song's stream when the page opens.
  Not done: playlist continuations (pages longer than one answer), pull to refresh.
- D5 `[код]`: online pages use the shell's top bar (Back, title after the header scrolls away); the
  pages' own back buttons are gone; 16 dp under the header buttons. Not done: restoring the exact
  scroll position of the screen under an online page.
- D3 `[код]`: `format()` is codec and bitrate only; no "YouTube Music" fallback. "Playing from" is
  `mixLabel ?: playingFrom ?: album ?: Your queue/Your library` (`LibraryViewModel.playFrom`).
- D2 `[код]`: `SourceMark` in every song row: quality tag for files, `cloud` / `cloud_done`
  (`CacheBudget.isStreamCached`, never opens the cache itself) / a ring while Soulseek downloads.
  Rows have one height: the tag is baseline-aligned (two baselines made ListItem a three-line item),
  2 dp above and below each row. Symbols font re-subset with the new glyphs (`docs/design/symbols.py`).
- D1 `[код]`: `searchAll` = plain search + artists-only search in parallel; Search shows YouTube
  Music artists and albums (minus ones the library has) above online songs. Artist pages parse
  singles and "Fans also like" from shelf titles (`parseHome` on the artist answer) and the monthly
  listeners. The library artist page's YouTube Music part shows Popular (owned songs swapped in, with
  marks), More albums, Singles and EPs, Fans also like. Fixture `ytm-search-artists.json` is a live
  answer from 2026-11-17; album search and artist browse were blocked from the build machine, so
  album cards in search and singles/related were not checked against a live answer.


- **On the phone:** release music-v1083 (`938eedb`, "Music 1.0"), installed through Obtainium. The
  owner uses it daily. Commits after it are docs only.
- **What the app is:** a local lossless player first (library, tags, lyrics, equalizer, ReplayGain,
  DSD, CUE, widget, tile, App Functions), joined to YouTube Music (search, home shelves, moods,
  mixes, sign-in, stream cache) and Soulseek (lossless downloads into the library). Feature status
  per line: `docs/PRODUCT_SPEC.md`; task status checked against the code: `docs/ROADMAP.md`.
- **Next:** milestone M10, spec `docs/specs/M10_ONLINE.md`. The owner's report from the phone, root
  causes found in the code, what to build, acceptance numbers, order. Do D4 first: the freezes on
  YouTube Music pages and after Play all come from `owned()` in `ui/OnlinePages.kt`, which scans the
  whole library with regexes for every song, in the composable body, on every recomposition
  `[код]`; `YouTubeMusic.Companion.Index` already does the matching fast.
- **Measured on the phone:** nothing formally (no frame stats, battery or cold start numbers yet).
  Everything else below is "compiled and unit-tested" at most, unless it says otherwise.
- **Known open items outside M10:** cookies of the YouTube Music sign-in are stored without Keystore
  encryption (ROADMAP M5); the hand-written baseline profile; APE/WavPack/TTA/Musepack; no golden
  comparison for the screenshot gallery.
- **Build:** `docs/INSTALL.md`, "Build environment for agents".
- **Workflow:** `AGENTS.md`. Every push to `main` is built, signed and released to the phone by CI,
  so `main` must always pass.

# History (newest first)
# Second functional batch: towards the full spec (Claude, 2026-11-15)

Everything in PRODUCT_SPEC.md is now done in code except S6 (APE, WavPack, TTA, Musepack: need own
extractors with an FFmpeg decoder) and S8 (real Bluetooth codec: only the Earbuds app knows it;
the owner asked not to touch Earbuds). Nothing of this batch is phone-verified yet. New pieces:

- Sound: balance/mono/swap (`dsp/ChannelMix.kt`, per output), Battery saver offload (only while no
  processing is active), DSD (DSF/DFF) to 88.2 kHz PCM (`dsp/DsdToPcm.kt`, `playback/DsdExtractor.kt`;
  shows up only if Android indexes the .dsf/.dff file), Cast (`playback/CastSupport.kt`: Media3
  CastPlayer + a LAN file server started only while casting), resume on headphone connect.
- Library: Home and Stats tabs, filter chips, letter rail, disc headers, compilations, hidden
  folders (`library/LibraryFolders.kt`), sidecar .cue, playlist duplicates (user.db v3, MIGRATION_2_3).
- Tags: writer for M4A (`Mp4Writer.kt`, moves stco/co64), Ogg/Opus (`OggWriter.kt`, same page count),
  covers, ratings (RATING/POPM, read back), rename/move by template, MANAGE_MEDIA for no prompts.
  `TagRewrite` gained `start` (a splice, not only a new file start). Tag reader version 2 re-reads
  every file once.
- Lyrics: QRC and Lyricify parsers, LRC writer, timing editor, lock-screen line (`LockScreenLyrics.kt`,
  a ForwardingPlayer only the session sees; history and widget read the real ExoPlayer).
- System: widget and QS tile (`NowPlayingWidget.kt`), MediaButtonReceiver, App Functions for Gemini
  (`MusicFunctions.kt` + `playback/QuickControl.kt`).

Verified locally: all unit and UI tests, lint debug/release, assembleRelease.

# Functional batch: tags, lyrics online, CUE, library views (Claude, 2026-11-15)

Branch `agent/m2-design`, written in one pass per the owner ("write it all, then check"). Status of
each feature is in PRODUCT_SPEC.md. Added:

- Tag editor (`ui/TagEditing.kt`, `core/tags/TagWriter.kt`): FLAC Vorbis comments and MP3 ID3v2.4,
  one song (song actions, "Edit tags") or many (selection toolbar). One MediaStore write request per
  batch; in place when the old padding fits, otherwise a temp copy that is read back before it
  replaces the file. Cue tracks (`#` ids) are skipped. Covers, M4A, Ogg, rename by template: todo.
- Online lyrics (`core/lyrics/OnlineLyrics.kt`, `KrcParser.kt`, `Json.kt`): Kugou KRC (word timing,
  translation) first, then LRCLIB; length must match within 4 s; saved in `filesDir/lyrics`
  (`ui/DownloadedLyrics.kt`), only on the owner's tap in the lyrics sheet. ID3 SYLT read as LRC.
- Embedded CUESHEET (`core/model/CueSheet.kt`): one-file albums become tracks, played with Media3
  clipping. Sidecar `.cue` is todo (needs the folder grant path used by sidecar lyrics).
- Sleep timer "when this song ends", playback resumption from the system media card, USB DAC
  bit-perfect switch (signal path sheet; unverified without a DAC).
- Library: Years and Composers tabs, search shows matching artists and albums above songs, sorts by
  date added, most played and rating (biggest first).
- Not done on purpose: playlist duplicates (needs `playlist_entries` keyed by position, user.db v3
  and position-based UI); per-output volume (Android already keeps it per device); widgets (next);
  APE/WavPack/DSD (Media3 has no extractors; FFmpeg decoder alone is not enough).

Verified locally: all unit and UI tests, `lintDebug`, `lintRelease`, `assembleRelease`. Not on the phone.

# Design pass and merge of the Codex batch (Claude, 2026-11-15)

Agents work in turns as their limits allow (owner): Codex ran out, Claude continues from
`agent/m3-replaygain` (`c9eb4cf`). Branch `agent/m2-design` = that batch + two commits:

- `334d7eb`: LibraryBrowserTest lacked four imports, so the app's unit tests did not compile.
- `ecb946c`: design pass. Material Symbols variable font (`ui/Symbols.kt`, `docs/design/symbols.py`)
  replaces `AppIcons` (Icons.kt is now unused); Flex typography with opsz/ROND; player page,
  mini player, track rows, album header, search pill, section chips, song-actions bottom sheet
  (`ui/PlayerScreens.kt`). Wallpaper colors only, per DESIGN_DIRECTION.md.

Verified locally (cloud sandbox, Gradle 9.9.1): all module tests pass (217 core/library/playback
earlier in this session plus 27 app tests after the design pass), `lintDebug`, `lintRelease`,
`assembleRelease` pass on the Codex batch. Renders from the UI tests reviewed by eye. Not run on the
phone yet. Plan: when CI is green on `agent/m2-design`, fast-forward `main` to it (release for the
phone), then delete `Icons.kt`, continue the M2 list and the owner's local-player list.

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
