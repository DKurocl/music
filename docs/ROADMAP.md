# Roadmap and task board

**Read this first (2026-11-17).** Milestones M0 to M9 below were written before most of the work and
were not ticked as it landed. Each open line now carries a "Status 2026-11-17" note from a read of
the code: `[x]` means it exists in code (not that it was checked on the phone), `[ ]` is really
open. The owner's next priority is **M10** at the end (spec: `docs/specs/M10_ONLINE.md`). The
"delivery order" section below is from 2026-11-15 and is history: streaming, Soulseek and themes were
done since.

Status: `[ ]` open, `[~]` taken (agent, date), `[x]` done (commit). Take the first open task of the
lowest open milestone unless the owner says otherwise. Each task ends with tests where logic is
involved, an updated HANDOFF.md, and CI green. "Done when" is the acceptance test.

Milestones M1, M2 and M4 to M8 touch different modules and can run in parallel by different agents.
Files many tasks touch (`AppGraph`, `settings.gradle.kts`, the version catalog) change in small
separate commits to keep merges trivial.

## What "the best it can be" means here (owner, 2026-11-14)

Battery, sound quality, speed and features, each pushed as far as this phone allows, and each
proven by a number measured on the phone, not assumed. Until measured, a target is a target.

| Area | Target on the Pixel 10 Pro XL | How it is measured |
|---|---|---|
| Battery, screen off, FLAC over LDAC | under 2 % per hour from the app's side | `dumpsys batterystats --charged ua.olex.music` after 2 h |
| Idle | no service, wake lock, job or alarm when not playing | `dumpsys activity services`, `power`, `jobscheduler`, `alarm` |
| Cold start to the library on screen | under 400 ms | `am start -W` (TotalTime), release build with baseline profile |
| Rescan, nothing changed, 10 000 files | under 2 s | log line around `LibraryRepository.rescan` |
| First scan, 10 000 files | under 60 s, list usable while it runs | same |
| Scrolling and the lyrics page | no dropped frames at 120 Hz | `dumpsys gfxinfo ua.olex.music framestats` |
| Sound | file rate and bit depth reach the mixer unchanged; gapless; no clipping with ReplayGain | AUDIO.md, "To measure" |
| Track change, local file | under 100 ms to audio | log around `onMediaItemTransition` |

A change that makes a number worse needs a reason in the commit. Features are tracked below against
Poweramp and Symfonium as the reference for a local player.

## Owner's current delivery order (Pixel only, Android 17+)

- [~] Playback and artwork reliability (Codex, 2026-11-18, `agent/playback-ui-reliability`):
      one pending selection across mixes and manual app entry points, cold-start player intents,
      motion surface lifecycle, cover-cache locking and widget update ordering (3/23/24/35/67/68).


- [x] Continue the 80-point audit (Codex, 2026-11-17, `bc99d27`, CI 44114015206): shared
      saved-song catalogue (51; 50 partial), playlist pagination/occurrences (60), service mix refill
      (21), shared session lyrics and draw-phase word timing (33/34), cache budgets/events (8/54),
      offline queue retention (53). Auxiliary priority and resumption remain partial (45/74).
      Full backlog and limits: `AUDIT_CONTINUATION.md`; physical acceptance: `PHONE_CHECK.md`.
- [x] Recording/source foundation (Codex, 2026-11-17, `45be07f`, CI 44172917731): user.db v4,
      durable recording IDs and source reserves, shared settings/lyrics/stats (47/48/63 foundation).
      441 tests, both lints, debug/release builds and owner signing passed. Released as music-v1116; APK and existing signer verified.
- [x] Service source policy (52, Codex, 2026-11-17, implementation `6cc00d7`, CI 44191397033):
      one full-evidence policy for app/browser/enqueue/refill/recovery/startup/resumption; retained
      unavailable occurrences, source preference, stable history/queue identity and generation-aware
      refresh (41). 480 tests, both lints/builds and owner signer passed. Observer follow-up reacts to
      durable recording changes too; integration remains gated on the final branch head's CI.
- [ ] Persistent online pages and album/artist identities (49/64).
- [ ] Account-scoped bidirectional likes and playlist operation/conflict sync (56/58/59).

1. Accurate library: integrate the existing tag reader, embedded/folder artwork, incremental scans
   and chosen music roots. Preserve all credited artists and honest audio properties.
2. Audio: album/track ReplayGain with clipping guard, verify gapless and the 24-bit path on the Pixel.
   Add optional XM5 correction and output-specific processing only with measured behavior.
3. Reliability: Sony media buttons/wear detection, focus/calls, Bluetooth-speaker transfers,
   phone-speaker fallback and long screen-off sessions. Verify state recovery on the actual phone.
4. Daily use: drag queue ordering, multiselect, playlist import/export and personal-data backup;
   synchronized local/embedded lyrics. Design work belongs to Claude's current task.
5. Acceptance: measure startup, scan time, 120 Hz frame times, battery and actual audio output.
   The targets above are not measured results. No Poweramp/Symfonium parity claim until these pass.

Network libraries are explicitly deferred by the owner (2026-11-15) and are not a current parity deficit.
Streaming, Soulseek and themes are deferred. Visualizers are excluded by the owner. USB DAC output, network casting and rare
formats are lower priority until the owner has a concrete use. Bluetooth output uses Android media
routes; it does not imply a network casting protocol. Keep minSdk 37, target 37 and arm64 only.

## Local player parity (the owner's order: this first, streaming and downloads after)

What a Poweramp/Symfonium-class local player has, and where each item lives in this plan:
tag-accurate library (M1), ReplayGain with pre-amp and clipping guard (M3), gapless verified (M3),
equalizer with per-output presets and AutoEq headphone profiles (M3, new), crossfade and fade on
pause/seek (M3, new), every common format incl. ALAC/APE/WavPack/DSD and cue sheets (M3, M1),
hi-res and USB bit-perfect output (M3), synced and word-timed lyrics (M4), embedded and folder
artwork (M1), playlists with m3u import/export and smart playlists (M1, new), ratings, play counts,
recently added (M1), folder browsing (done), queue editing (done), sleep timer (done), resume on
connect and headset button rules (M3, new), Android Auto (M3), widgets (M9), tag editor (M7),
user data retained across updates (implemented); backup/export is excluded by the owner.

## M0 Foundation
- [x] Gradle project, version catalog, five modules, CI workflow, agent docs (session 1)
- [x] `:core:model`: Track, TrackId, AudioFormat, SongKey with tests (session 1)
- [x] `:core:lyrics`: LRC, word-timed LRC, TTML parsers with tests (session 1)
- [x] `:core:playback`: PlaybackService, PlayerFactory, PlayerConnection, OutputRoute (session 1, compiled only)
- [x] `:core:library`: Room cache, MediaStore scanner, LibraryRepository (session 1, compiled only)
- [x] `:app`: songs list, mini player, player page with the signal path line (session 1, compiled only)
- [x] First run on the phone (physical device required): grant access, list fills, a FLAC plays, notification and lock screen
      Status 2026-11-17 (Claude, from the code at 938eedb): the owner uses the app daily on the phone since 2026-11-16; the PHONE_CHECK.md list itself is not recorded.
      controls work, earbud buttons work, pulling an earbud pauses. Record results in HANDOFF.
      Failure handling and regression tests are implemented; physical-device checks remain pending.
- [x] First-run hardening: preserve the library on failure, lifecycle-bound controller/progress,
      buffering-aware controls, retry UI and regression tests (`04bef1b`, CI 43549776145).
- [x] Owner signing secrets configured; signed build/release verified in CI 43546746908 (`90ded6f`).
      Installation through Obtainium remains a physical-device check.

## M1 Library done right (`:core:library`, new `:core:tags`)
- [x] `:core:tags` reader (Claude, 2026-11-14, branch `agent/m1-tags`): pure Kotlin, FLAC, MP3, Ogg
      Vorbis, Opus, M4A, WAV; multi-value artists, album artist, disc, ReplayGain and R128,
      MusicBrainz ids, lyrics, pictures as byte ranges, real sample rate and bit depth, gapless
      delay and padding. 14 unit tests on byte fixtures.
- [x] Wire the reader into the scan (`85d7748`, CI 43638096372; software acceptance verified,
      physical-device acceptance pending) (`:core:library`). MediaStore still lists the files; for each
      new or changed file (mtime + size) open it (`contentResolver.openFileDescriptor(uri, "r")`,
      `ChannelSource(FileInputStream(fd).channel)`) and take `TagReader.read`: title, all artists,
      album artist, track/disc, year, genres, codec, rate, depth, duration, ReplayGain, cover range,
      delay/padding. Null result: keep the MediaStore values. Store artists and genres as lists
      (join table or separator column), gains and the cover range as columns; bump `library.db`.
      Unchanged files are not opened again. Physical follow-up: a unit test with a fake file source shows
      tag values winning over MediaStore values and an unchanged file not being reopened; on the
      phone a 24/96 FLAC shows "FLAC 24/96" and two artists show as two.
- [ ] Real-file check of the reader on the phone: a debug screen or instrumentation test that runs
      `TagReader` over the whole library and reports files that returned null or disagree with
      MediaStore on duration by more than 1 s. Fix every class of failure found.
- [x] Incremental cache writes (`023bc1c`, CI 43651556524, 167 tests): unchanged scans
      must not rewrite rows/FTS or invalidate library observers; changed scans update only the delta
      atomically. SQLite regression tests cover no-op, deletion, metadata edits and rollback.
- [x] File scanner: chosen roots (default `Music/`), "All files access" flow, incremental by
      Status 2026-11-17 (Claude, from the code at 938eedb): chosen folders and hidden folders in LibraryFolders.kt and Settings; the 2 s rescan target is not measured.
      mtime+size, replaces MediaStoreScanner behind LibraryRepository. Done when: 10 000 files
      rescan with no changes in under 2 s on the phone.
- [x] Library model (`92c4fdf`, CI 43550547422): albums, artists (album artist grouping), genres, folders; sort rules (disc,
      track; articles ignored); queries as Flows.
- [x] Search (`92c4fdf`, CI 43550547422): Room FTS over title, artist, album; prefix and diacritic-insensitive.
- [x] `user.db` (`f1ba244`, CI 43571427496, integrated into main): playlists, favourites, play history, lyric offsets, with exported schemas and a
      migration test. Keys by SongKey + path.
- [x] Artwork (Codex, 2026-11-15, `agent/m3-replaygain`): embedded -> selected-folder
      Status 2026-11-17 (Claude, from the code at 938eedb): in code (artwork provider, ArtworkUris); not phone-verified.
      `cover.*`/`folder.*`/`front.*` -> MediaStore. Private content provider consumed by Coil and
      media metadata, deduplicated bounded three-size disk cache, wallpaper colors preserved.
      Source implementation and local checks complete; real Android/Pixel acceptance pending.
- [x] Watch for file changes (`5aa0d92`, CI 43564927822) while the app is open (ContentObserver/FileObserver) instead of only
      on start.

- [x] Playlists: m3u/m3u8 import and export (Codex, 2026-11-15, `agent/m3-replaygain`);
      Status 2026-11-17 (Claude, from the code at 938eedb): m3u import/export, duplicates and smart playlists are in code (PlaylistDocuments.kt, SmartPlaylistBrowserTest).
      codec, preview, path resolution, missing-file retention and transactional save implemented.
      102 JVM / 9 focused user-repository tests pass; Android picker/UI checks deferred by owner.
      Repeated playlist occurrences and smart playlist rules remain open.
- [x] Library sort choices (Codex, 2026-11-15, `agent/m3-replaygain`): persisted per song/search/collection
      Status 2026-11-17 (Claude, from the code at 938eedb): in code.
      scope, off-main sorting, visible-order playback; pure tests pass, Compose check deferred.
- [x] Ratings, play counts, recently added and recently played views.
      Status 2026-11-17 (Claude, from the code at 938eedb): in code (SongStats, StatsPage, Home shelves).
- [x] Cue sheets (`.cue` beside a file and embedded CUESHEET): one file shown and played as its tracks.
      Status 2026-11-17 (Claude, from the code at 938eedb): sidecar .cue in code (CueSheet.kt); embedded CUESHEET not checked.
- Export/import backup of user.db excluded by the owner (2026-11-14); persistence across updates is sufficient.

## M2 Design system and the real shell (`:app`)

Owner design direction and reviewed PixelPlayer reference: DESIGN_DIRECTION.md. Codex continues design while Claude is unavailable (owner, 2026-11-14).
- [x] Bring the Earbuds design system (Codex 2026-11-14, finished by Claude 2026-11-15, `ecb946c`): Google Sans Flex, Material Symbols variable font (subset by
      a script as in `DKurocl/xm5` `docs/icon/symbols.py`), EmphasizedEasing, haptics vocabulary.
      Replace `AppIcons`; add a CI guard against other icon sources.
- [x] Navigation: Home, Library (Albums, Artists, Songs, Playlists, Folders), Search, Downloads;
      Status 2026-11-17 (Claude, from the code at 938eedb): the shell is Home, Playlists, Search; Settings, Stats and Duplicates open over Home; downloads live in Settings.
      floating toolbar; predictive back.
- [x] Player page (Codex, 2026-11-14, owner prioritized functionality): container transform from the mini player, wallpaper-driven color scheme, queue
      Status 2026-11-17 (Claude, from the code at 938eedb): in code.
      sheet with drag to reorder, signal path sheet, sleep timer.
      Sleep timer verified at `4560b91`, CI 43566729401. Queue, shuffle/repeat, recovery and
      output/diagnostics verified at `f1ba244`, CI 43571427496. Drag reordering and cosmetic
      transforms are still open; owner deferred customization in favor of player reliability.
- [x] Album and artist pages; multi-select with batch actions (Codex, 2026-11-15):
      Status 2026-11-17 (Claude, from the code at 938eedb): in code.
      library/search/collection selection and play/next/queue/playlist batches implemented locally;
      pure and bulk-command tests pass, Compose interaction deferred.
- [x] Launcher icon and splash hand-off (the pattern in Earbuds `SplashHandoff.kt`).
      Status 2026-11-17 (Claude, from the code at 938eedb): SplashHandoff.kt and the launcher icon are in code.
- [ ] Screenshot goldens (Robolectric, `-PminSdk=35` while it lacks API 37) for every page in
      Status 2026-11-17 (Claude, from the code at 938eedb): gallery renders exist (DesignGalleryTest, WidgetGalleryTest write PNGs to app/build/reports/ui-previews); no golden comparison yet.
      dark/light and font scale 1 and 2; accessibility test on an emulator; reviewed by eye.

## M3 Audio quality (`:core:playback`), see AUDIO.md
- [ ] Measure bit depth and sample rate reaching the mixer; fix if not float/24-bit.
- [ ] Earbuds provider: read-only ContentProvider in `DKurocl/xm5` (signature permission) with
      codec, battery, mode; client here; codec in the signal path.
- [x] ReplayGain/R128 (Codex, 2026-11-15, `agent/m3-replaygain`): track and album mode, pre-amp, clipping prevention; sample-associated gain before sink buffering, with pure and integration tests.
      Status 2026-11-17 (Claude, from the code at 938eedb): in code (core/dsp PcmReplayGain).
- [ ] Gapless verified with a gapless album (FLAC, MP3, Opus).
- [ ] Offload for lossy with screen off after a battery measurement.
      Status 2026-11-17 (Claude, from the code at 938eedb): offload exists for Battery saver (only without processing); the battery measurement is not done.
- [x] USB DAC bit-perfect mode.
      Status 2026-11-17 (Claude, from the code at 938eedb): UsbBitPerfect.kt in code; never tried with a DAC.
- [ ] Extension decoders for ALAC/APE/WavPack/DSD.
      Status 2026-11-17 (Claude, from the code at 938eedb): DSD done (DsdExtractor, DsdToPcm); APE, WavPack, TTA, Musepack open (PRODUCT_SPEC S6).
- [x] Android Auto: browse tree (`onGetLibraryRoot`, `onGetChildren`, search), tested in the
      Status 2026-11-17 (Claude, from the code at 938eedb): browse tree in PlaybackService (onGetLibraryRoot, onGetChildren); not tried in a car or the DHU.
      Desktop Head Unit.

- [x] Equalizer (Codex, 2026-11-14, `agent/m3-equalizer`) as an audio processor that keeps float samples: parametric bands, pre-amp, presets
      Status 2026-11-17 (Claude, from the code at 938eedb): in code (EqualizerSheet, core/dsp).
      per output device, AutoEq profile import (WF-1000XM5 first). Pure DSP code tested against
      reference curves. Core float/PCM16 EQ, route-isolated settings and WF-1000XM5 preset
      verified at `4aa3efe`, CI 43640064817 (156 cases). Frequency/Q editing and external profile
      import are being completed on `agent/m3-replaygain`. Measure on the phone before deciding whether any loop needs C++/NEON.
- Crossfade and decorative transitions are excluded by the owner (2026-11-14). Gapless remains required.
- [x] Rules for outputs: resume on earbuds connect (optional), what headset buttons do, volume per output.
      Status 2026-11-17 (Claude, from the code at 938eedb): HeadsetRules.kt in code.

## M4 Lyrics (`:core:lyrics`, new `:core:lyrics-net`, `:app`)
- [x] KRC (Kugou), QRC and SYLT parsers with tests.
      Status 2026-11-17 (Claude, from the code at 938eedb): KRC, QRC and Lyricify parsers in core/lyrics; SYLT read by core/tags (Id3.kt).
- [x] LRCLIB and Kugou clients; matching by SongKey and length; pure Kotlin with recorded-response tests.
      Status 2026-11-17 (Claude, from the code at 938eedb): Kugou word-timed then LRCLIB (OnlineLyrics.kt, DownloadedLyrics.kt).
- [x] Lyrics store (Codex, 2026-11-15, `agent/m3-replaygain`): local LRC/TTML/TXT sidecars
      Status 2026-11-17 (Claude, from the code at 938eedb): in code; found lyrics are saved next to the file.
      via explicitly chosen internal-storage folders, embedded fallback, reload and per-track offsets
      implemented. 114 pure / 4 focused storage tests pass; source compiles, Android picker UI pending.
      Fetching and writing sidecars remain open.
- [x] Lyrics page: word fill, spring scroll, blur, tap to seek, backing vocals, duets, gap dots;
      Status 2026-11-17 (Claude, from the code at 938eedb): InlineLyrics in LyricsSheet.kt (lyrics in the cover area).
      draw-phase only, no per-frame recomposition (check with the composition tracing).
- [x] Editor: offset nudge, tap-to-time lines, save.
      Status 2026-11-17 (Claude, from the code at 938eedb): timing editor in LyricsSheet.kt.

## M5 Streaming (new `:source:youtube`, `:source:soundcloud`)
- [x] InnerTube client: search (songs, albums, artists), album, artist, radio (`next`), home.
      Status 2026-11-17 (Claude, from the code at 938eedb): in :app (ui/YouTubeMusic.kt, InnerTube WEB_REMIX), not a separate :source:youtube module.
- [x] Stream resolution through NewPipeExtractor in `onAddMediaItems`; Media3 cache; errors surface
      Status 2026-11-17 (Claude, from the code at 938eedb): ytm:// resolved through NewPipe in core/playback (OnlineStreams.kt), stream cache in CacheBudget.kt.
      as a skip with a message, never a silent stall.
- [~] Unified search: local, YouTube Music, SoundCloud in one result list, owned items marked.
      Status 2026-11-17 (Claude, from the code at 938eedb): library and YouTube Music songs in Search; YouTube Music artists and albums are M10 D1; SoundCloud is not started.
- [x] Mix from any track with local-first substitution (`LibraryRepository.owned`).
      Status 2026-11-17 (Claude, from the code at 938eedb): Mix.kt, LibraryViewModel.startMix; local copies swapped in.
- [ ] Optional account (cookie) for personal recommendations, stored encrypted with Android Keystore.
      Status 2026-11-17 (Claude, from the code at 938eedb): sign-in and the personal home work (YouTubeLogin, YouTubeAccount); the cookies are stored without Android Keystore encryption. Open: encrypt them.

## M6 Soulseek (new `:core:soulseek`, `:core:organizer`)
- [x] Message codec (little-endian, zlib search replies) for server and peer messages, with tests
      Status 2026-11-17 (Claude, from the code at 938eedb): in ui/../soulseek/SoulseekClient.kt (in :app, not a :core module).
      from captured bytes in the Nicotine+ docs.
- [x] Session: login, search, peer connections incl. indirect (PierceFirewall), transfers with
      Status 2026-11-17 (Claude, from the code at 938eedb): login, search, peer connections and downloads; outgoing connections only.
      queue position, resume; tested against a fake server.
- [ ] Sharing: answer browse and search for the library folder; upload queue.
      Status 2026-11-17 (Claude, from the code at 938eedb): not done on purpose so far: the client shares nothing and never uploads (SoulseekClient.kt).
- [ ] Search UX: grouped by album, ranked (RESEARCH.md), filters, one tap to take an album.
      Status 2026-11-17 (Claude, from the code at 938eedb): search from exact to loose and a lossy fallback exist (SoulseekDownloads.kt); grouping and filters are open.
- [x] Organizer: path template, tag-based move, cover and lyrics fetch, rescan; pure logic tested.
      Status 2026-11-17 (Claude, from the code at 938eedb): rename and move by template in the tag editor (TagEditing.kt).
- [ ] Transfers as a dataSync foreground service that exists only while transferring.
      Status 2026-11-17 (Claude, from the code at 938eedb): downloads run inside the app without a foreground service today.

## M7 Tag editor (`:core:tags`, `:app`)
- [x] Write tags and pictures (FLAC, MP3, M4A, Opus); single and batch; undo until confirmed.
      Status 2026-11-17 (Claude, from the code at 938eedb): FLAC, MP3, M4A (Mp4Writer), Ogg/Opus (OggWriter) in core/tags; single and batch.
- [x] Lookups: MusicBrainz, Discogs; cover search.
      Status 2026-11-17 (Claude, from the code at 938eedb): online tags and cover lookup in OnlineTags.kt.
- [x] Rename/move by template (shares `:core:organizer`).
      Status 2026-11-17 (Claude, from the code at 938eedb): in the tag editor.

## M8 Animated covers (new `:core:motion`)
- [x] Sidecar `cover.mp4`/`cover.webp` looping on the player page, muted, only while visible.
      Status 2026-11-17 (Claude, from the code at 938eedb): MotionCovers.kt.
- [x] Apple Music motion artwork lookup and one-time download (unofficial, see RESEARCH.md);
      Status 2026-11-17 (Claude, from the code at 938eedb): AppleMotion.kt.
      failure is silent and leaves the still cover.

## M9 System and release gates
- [x] Widgets (Glance 1.2.0), Quick Settings tile if useful, App Functions for Gemini.
      Status 2026-11-17 (Claude, from the code at 938eedb): RemoteViews widget and QS tile (NowPlayingWidget.kt), App Functions (MusicFunctions.kt); not Glance.
- [ ] Baseline profile generated on the phone; cold start measured (`am start -W`).
      Status 2026-11-17 (Claude, from the code at 938eedb): app/src/main/baseline-prof.txt is hand-written wildcard rules; a profile generated on the phone and the cold start measurement are open.
- [ ] Background check: no service, job or alarm when not playing (`dumpsys`).
- [ ] Release checklist from `.agents/skills/pixel-apk/references/quality.md` wired into CI.

## M10 YouTube Music as part of one library (`:app`, spec `docs/specs/M10_ONLINE.md`)
Owner's report from the phone, 2026-11-17. Do the tasks in this order; each keeps `main` releasable.
- [ ] D4: song ownership computed once, off the main thread, with `Index`; pages open from the card's
      title and cover with placeholder rows; in-memory page cache; continuations in the background.
      Done when: no frame over 32 ms opening a 100 song playlist or on Play all (spec, "Done when" 1 to 4).
- [ ] D5: online pages get the shared top app bar (no Home greeting over them); 16 dp under the header
      buttons; back keeps the previous scroll position.
- [ ] D3: player format line is codec and rate only; "Playing from" names the playlist, album, mix,
      mood or Search, never "YouTube Music".
- [ ] D2: one source mark per row (quality tag, cloud, cloud_done, downloading with progress); one row
      height; covers on mixed playlists; Download in the row menu; marks update in place.
- [ ] D1: one artist page merging library and YouTube Music (Popular, In your library, Albums,
      Singles and EPs, Fans also like, About, Start radio); Search shows YouTube Music artists and
      albums. Parser tests on saved answers.
- [ ] D6: design review of every state in the gallery, goldens committed.

