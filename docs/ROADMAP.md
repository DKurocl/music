# Roadmap and task board

Status: `[ ]` open, `[~]` taken (agent, date), `[x]` done (commit). Take the first open task of the
lowest open milestone unless the owner says otherwise. Each task ends with tests where logic is
involved, an updated HANDOFF.md, and CI green. "Done when" is the acceptance test.

Milestones M1, M2 and M4 to M8 touch different modules and can run in parallel by different agents.
Files many tasks touch (`AppGraph`, `settings.gradle.kts`, the version catalog) change in small
separate commits to keep merges trivial.

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
- [ ] `:core:tags`: TagLib over JNI. Read all fields incl. multi-value artists, album artist, disc,
      ReplayGain, MusicBrainz ids, embedded lyrics and pictures, real sample rate and bit depth.
      Done when: a test folder of FLAC, MP3, M4A, Opus, WAV files reads back expected values on a
      device test, and the `.so` is 16 KB aligned.
- [ ] File scanner: chosen roots (default `Music/`), "All files access" flow, incremental by
      mtime+size, replaces MediaStoreScanner behind LibraryRepository. Done when: 10 000 files
      rescan with no changes in under 2 s on the phone.
- [x] Library model (`92c4fdf`, CI 43550547422): albums, artists (album artist grouping), genres, folders; sort rules (disc,
      track; articles ignored); queries as Flows.
- [x] Search (`92c4fdf`, CI 43550547422): Room FTS over title, artist, album; prefix and diacritic-insensitive.
- [~] `user.db` (Codex, 2026-11-14, owner requested a complete personal-library block): playlists, favourites, play history, lyric offsets, with exported schemas and a
      migration test. Keys by SongKey + path.
- [ ] Artwork: embedded picture or `cover.*`/`folder.*` in the folder, decoded once into a disk
      cache at 3 sizes; Coil fetcher for `track:` models.
- [x] Watch for file changes (`5aa0d92`, CI 43564927822) while the app is open (ContentObserver/FileObserver) instead of only
      on start.

## M2 Design system and the real shell (`:app`)
- [ ] Bring the Earbuds design system: Google Sans Flex, Material Symbols variable font (subset by
      a script as in `DKurocl/xm5` `docs/icon/symbols.py`), EmphasizedEasing, haptics vocabulary.
      Replace `AppIcons`; add a CI guard against other icon sources.
- [ ] Navigation: Home, Library (Albums, Artists, Songs, Playlists, Folders), Search, Downloads;
      floating toolbar; predictive back.
- [~] Player page (Codex, 2026-11-14, owner prioritized functionality): container transform from the mini player, artwork-driven color scheme, queue
      sheet with drag to reorder, signal path sheet, sleep timer.
      Sleep timer verified at `4560b91`, CI 43566729401. Queue, shuffle/repeat, recovery and
      output/diagnostics implemented at `e2b5d37`; final CI pending. Drag reordering and cosmetic
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
