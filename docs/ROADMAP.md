# Roadmap and task board

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
backup and restore of user data (M1, new).

## M0 Foundation
- [x] Gradle project, version catalog, five modules, CI workflow, agent docs (session 1)
- [x] `:core:model`: Track, TrackId, AudioFormat, SongKey with tests (session 1)
- [x] `:core:lyrics`: LRC, word-timed LRC, TTML parsers with tests (session 1)
- [x] `:core:playback`: PlaybackService, PlayerFactory, PlayerConnection, OutputRoute (session 1, compiled only)
- [x] `:core:library`: Room cache, MediaStore scanner, LibraryRepository (session 1, compiled only)
- [x] `:app`: songs list, mini player, player page with the signal path line (session 1, compiled only)
- [ ] First run on the phone (physical device required): grant access, list fills, a FLAC plays, notification and lock screen
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
- [ ] File scanner: chosen roots (default `Music/`), "All files access" flow, incremental by
      mtime+size, replaces MediaStoreScanner behind LibraryRepository. Done when: 10 000 files
      rescan with no changes in under 2 s on the phone.
- [x] Library model (`92c4fdf`, CI 43550547422): albums, artists (album artist grouping), genres, folders; sort rules (disc,
      track; articles ignored); queries as Flows.
- [x] Search (`92c4fdf`, CI 43550547422): Room FTS over title, artist, album; prefix and diacritic-insensitive.
- [x] `user.db` (`f1ba244`, CI 43571427496, integrated into main): playlists, favourites, play history, lyric offsets, with exported schemas and a
      migration test. Keys by SongKey + path.
- [ ] Artwork: embedded picture or `cover.*`/`folder.*` in the folder, decoded once into a disk
      cache at 3 sizes; Coil fetcher for `track:` models.
- [x] Watch for file changes (`5aa0d92`, CI 43564927822) while the app is open (ContentObserver/FileObserver) instead of only
      on start.

- [ ] Playlists: m3u/m3u8 import and export; smart playlists (rules over tags, play counts, dates).
- [ ] Ratings, play counts, recently added and recently played views.
- [ ] Cue sheets (`.cue` beside a file and embedded CUESHEET): one file shown and played as its tracks.
- [ ] Export and import of `user.db` (playlists, favourites, history) to a file the owner keeps.

## M2 Design system and the real shell (`:app`)

Owner design direction and reviewed PixelPlayer reference: DESIGN_DIRECTION.md. Codex continues design while Claude is unavailable (owner, 2026-11-14).
- [~] Bring the Earbuds design system (Codex, 2026-11-14): Google Sans Flex, Material Symbols variable font (subset by
      a script as in `DKurocl/xm5` `docs/icon/symbols.py`), EmphasizedEasing, haptics vocabulary.
      Replace `AppIcons`; add a CI guard against other icon sources.
- [ ] Navigation: Home, Library (Albums, Artists, Songs, Playlists, Folders), Search, Downloads;
      floating toolbar; predictive back.
- [~] Player page (Codex, 2026-11-14, owner prioritized functionality): container transform from the mini player, wallpaper-driven color scheme, queue
      sheet with drag to reorder, signal path sheet, sleep timer.
      Sleep timer verified at `4560b91`, CI 43566729401. Queue, shuffle/repeat, recovery and
      output/diagnostics verified at `f1ba244`, CI 43571427496. Drag reordering and cosmetic
      transforms are still open; owner deferred customization in favor of player reliability.
- [ ] Album and artist pages; multi-select with batch actions.
- [ ] Launcher icon and splash hand-off (the pattern in Earbuds `SplashHandoff.kt`).
- [ ] Screenshot goldens (Robolectric, `-PminSdk=35` while it lacks API 37) for every page in
      dark/light and font scale 1 and 2; accessibility test on an emulator; reviewed by eye.

## M3 Audio quality (`:core:playback`), see AUDIO.md
- [ ] Measure bit depth and sample rate reaching the mixer; fix if not float/24-bit.
- [ ] Earbuds provider: read-only ContentProvider in `DKurocl/xm5` (signature permission) with
      codec, battery, mode; client here; codec in the signal path.
- [ ] ReplayGain/R128: track and album mode, pre-amp, clipping prevention; pure logic tested.
- [ ] Gapless verified with a gapless album (FLAC, MP3, Opus).
- [ ] Offload for lossy with screen off after a battery measurement.
- [ ] USB DAC bit-perfect mode.
- [ ] Extension decoders for ALAC/APE/WavPack/DSD.
- [ ] Android Auto: browse tree (`onGetLibraryRoot`, `onGetChildren`, search), tested in the
      Desktop Head Unit.

- [~] Equalizer (Codex, 2026-11-14, `agent/m3-equalizer`) as an audio processor that keeps float samples: parametric bands, pre-amp, presets
      per output device, AutoEq profile import (WF-1000XM5 first). Pure DSP code tested against
      reference curves. Decide after measuring whether Kotlin is fast enough or the inner loop
      moves to C++ with NEON.
- Crossfade and decorative transitions are excluded by the owner (2026-11-14). Gapless remains required.
- [ ] Rules for outputs: resume on earbuds connect (optional), what headset buttons do, volume per output.

## M4 Lyrics (`:core:lyrics`, new `:core:lyrics-net`, `:app`)
- [ ] KRC (Kugou), QRC and SYLT parsers with tests.
- [ ] LRCLIB and Kugou clients; matching by SongKey and length; pure Kotlin with recorded-response tests.
- [ ] Lyrics store: sidecar, embedded, fetched; save fetched as sidecar; per-track offset.
- [ ] Lyrics page: word fill, spring scroll, blur, tap to seek, backing vocals, duets, gap dots;
      draw-phase only, no per-frame recomposition (check with the composition tracing).
- [ ] Editor: offset nudge, tap-to-time lines, save.

## M5 Streaming (new `:source:youtube`, `:source:soundcloud`)
- [ ] InnerTube client: search (songs, albums, artists), album, artist, radio (`next`), home.
- [ ] Stream resolution through NewPipeExtractor in `onAddMediaItems`; Media3 cache; errors surface
      as a skip with a message, never a silent stall.
- [ ] Unified search: local, YouTube Music, SoundCloud in one result list, owned items marked.
- [ ] Mix from any track with local-first substitution (`LibraryRepository.owned`).
- [ ] Optional account (cookie) for personal recommendations, stored encrypted with Android Keystore.

## M6 Soulseek (new `:core:soulseek`, `:core:organizer`)
- [ ] Message codec (little-endian, zlib search replies) for server and peer messages, with tests
      from captured bytes in the Nicotine+ docs.
- [ ] Session: login, search, peer connections incl. indirect (PierceFirewall), transfers with
      queue position, resume; tested against a fake server.
- [ ] Sharing: answer browse and search for the library folder; upload queue.
- [ ] Search UX: grouped by album, ranked (RESEARCH.md), filters, one tap to take an album.
- [ ] Organizer: path template, tag-based move, cover and lyrics fetch, rescan; pure logic tested.
- [ ] Transfers as a dataSync foreground service that exists only while transferring.

## M7 Tag editor (`:core:tags`, `:app`)
- [ ] Write tags and pictures (FLAC, MP3, M4A, Opus); single and batch; undo until confirmed.
- [ ] Lookups: MusicBrainz, Discogs; cover search.
- [ ] Rename/move by template (shares `:core:organizer`).

## M8 Animated covers (new `:core:motion`)
- [ ] Sidecar `cover.mp4`/`cover.webp` looping on the player page, muted, only while visible.
- [ ] Apple Music motion artwork lookup and one-time download (unofficial, see RESEARCH.md);
      failure is silent and leaves the still cover.

## M9 System and release gates
- [ ] Widgets (Glance 1.2.0), Quick Settings tile if useful, App Functions for Gemini.
- [ ] Baseline profile generated on the phone; cold start measured (`am start -W`).
- [ ] Background check: no service, job or alarm when not playing (`dumpsys`).
- [ ] Release checklist from `.agents/skills/pixel-apk/references/quality.md` wired into CI.
