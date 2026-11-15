# Product spec: the player that competes with Poweramp, Symfonium, PixelPlayer and LastWave

Paper first (owner, 2026-11-15). This is the target the app is built toward; ROADMAP.md is the order
of work. Every item has a priority, a done-when test, and today's status. Nothing here is a claim
that we are better: that is decided by measurements on the owner's phone (section 9).

Target: Pixel 10 Pro XL (Tensor G5, Android 17), Sony WF-1000XM5, phone speaker, Bluetooth speakers.
Priorities: P0 = without it the app loses to all four; P1 = needed to match the best of them;
P2 = makes it better than them. Status: `done` built and tested in code, `part` partly built,
`todo` not built. "Done" never means phone-verified; PHONE_CHECK.md records that.

## 1. What each competitor wins at, and what we take

| App | Why people choose it | What we must match | What we do not copy |
|---|---|---|---|
| Poweramp | Sound engine: hi-res output, DSP, EQ, ReplayGain, every format, tag editor, folder library, deep settings | Format coverage, honest hi-res path, EQ quality, tag editing, CUE, headset rules | Settings maze, skins, its own audio engine replacing Android's (we use Media3 and measure) |
| Symfonium | Library power: smart playlists, filters, fast huge libraries, Android Auto, multi-source with offline cache, clean UI | Smart playlists, filters and sorting everywhere, scan speed, Android Auto quality, later multi-source | Paid server integrations we do not use (Plex, Jellyfin) unless asked |
| PixelPlayer | Looks like a Pixel app: Material 3 Expressive, smooth motion, lyrics, widgets | Visual and motion quality, widgets, lyrics presentation | Visualizers, theme pickers (owner: wallpaper colors only) |
| LastWave | YouTube Music with lossless add-ons, word-synced lyrics (Kugou KRC), downloads with LRC | Word lyrics with online sources, streaming joined to the library (M5) | Liquid-glass look |

Our own edge, none of the four has all of it: one song identity across local files, streams and
downloads (SongKey); built for one phone and one pair of earbuds and measured on them; an honest
signal path (file, decoder, output, and the real LDAC/AAC from the Earbuds app); Soulseek downloads
that land tagged and organized (M6).

## 2. Principles

1. Sound is never made worse by the app: no resampling, dithering or processing unless the owner
   turned it on; bit depth kept to the mixer (float output). Shown, not claimed.
2. The library is the files: tags read from the files, edits written back to the files, nothing
   lost when the database is rebuilt. User data (playlists, ratings, history) survives rescans.
3. Fast with 20 000 songs: nothing blocks the main thread, lists are virtualized, scans incremental.
4. No idle work: no service, timer or wake lock while not playing. Battery is a feature.
5. Every action reachable in two taps and by voice, Android Auto and headset where it makes sense.
6. Android's own controls where they exist (output switcher, media controls, volume) instead of imitations.

## 3. Library

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| L1 | Scan the phone's music, incremental by mtime+size | P0 | rescan of 10 000 unchanged files < 2 s on the phone | done (MediaStore + own tag reader), speed unmeasured |
| L2 | Tags from the files: multi-artist, album artist, disc/track, genres, year, ReplayGain, MusicBrainz, lyrics, real rate/depth | P0 | real-library check shows no file read wrong | done (14 fixture tests), real files pending |
| L3 | Choose music folders and exclusions (not only Music/) | P1 | a folder outside Music/ is scanned, an excluded one is not | done (Android indexes every folder; Folders tab hides and restores folders, nothing deleted) |
| L4 | Albums, artists (album-artist grouping), genres, folders, years, composers | P0 | all browsable with counts | done (years, composers added) |
| L5 | Album grid with covers, list/grid switch, fast scroller with letters | P1 | 2000 albums scroll without dropped frames | done (grid; letter rail on the title-sorted song list) |
| L6 | Sorting per view (title, artist, album, year, added, duration, plays, rating), remembered | P0 | each view keeps its sort | done (date added, most played, rating added) |
| L7 | Filters: format (lossless, hi-res), genre, decade, rating, unplayed | P1 | filter chips narrow any list | done (lossless, hi-res, unplayed, 4+ stars, decade, genre chips on every song list) |
| L8 | Search across songs, albums, artists, with accents and prefixes | P0 | "beyon" finds Beyoncé, results grouped | done (artists and albums above songs) |
| L9 | Embedded and folder artwork, cached at 3 sizes | P0 | covers in lists, player, notification, Auto | done |
| L10 | CUE sheets (beside a file and embedded) shown as tracks | P1 | a one-file album plays track by track, gapless | done (embedded CUESHEET and sidecar .cue beside the file, needs the folder chosen; split and clipped) |
| L11 | Multi-disc albums with disc headers | P1 | disc 2 starts under its own header | done (disc headers in multi-disc albums) |
| L12 | Compilations under "Various artists" without splitting the album | P1 | a compilation is one album | done (one folder, one album name, several artists, no album artist: one album under Various artists) |
| L13 | Library statistics: total time, top artists, plays per month | P2 | a stats page | done (Stats tab: size, quality, plays, time listened, top artists and songs, formats) |

## 4. Personal library

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| U1 | Playlists: create, rename, reorder, add many, duplicates allowed | P0 | duplicates can be added on purpose | done (user.db v3 keys entries by position; "Add again if already there"; M3U duplicates kept) |
| U2 | M3U/M3U8 import and export with relative paths | P0 | round trip with Poweramp's files | done |
| U3 | Favourites | P0 |  | done |
| U4 | Star ratings, written to the file tag (POPM/RATING) when asked | P1 | rating survives reinstall via the tag | done (optional: written as FLAC/Ogg RATING, MP3 POPM; ratings in files are read back) |
| U5 | Play counts, last played, history | P0 |  | done |
| U6 | Smart playlists with rules, match all/any, sort, limit; built-ins | P0 |  | done |
| U7 | Recently added | P0 |  | done (smart) |
| U8 | Home page that saves taps: continue, recently added, most played, smart lists | P1 | opening the app plays the usual thing in one tap | done (Home tab: shuffle all, played lately, added lately, smart lists, most played) |
| U9 | Export of user data | - | owner excluded | not planned |

## 5. Playback and queue

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| Q1 | Queue: play next, add, reorder by drag, remove, shuffle, repeat | P0 |  | done |
| Q2 | Queue and position restored after restart, paused | P0 |  | done |
| Q3 | Gapless on FLAC, MP3 (LAME), AAC (iTunSMPB), Opus | P0 | no click at track joins on a gapless album | delegated to Media3, unverified |
| Q4 | Sleep timer, also "end of track" | P0 |  | done (minutes and end of song) |
| Q5 | Resume from the system media controls after reboot (playback resumption) | P1 | the media card offers the last song after a reboot | done in code |
| Q6 | Headset rules: resume on connect (per device), pause on disconnect, long-press actions | P1 | XM5 connect resumes when enabled | done (play on connect per headphones, pause on disconnect) |
| Q7 | Per-output volume remembered | P2 | speaker and XM5 keep their own level | provided by Android (per-device stream volume); nothing to build |
| Q8 | Playback speed for audiobooks/podcasts | - | not a goal for music | not planned |
| Q9 | Crossfade | - | owner excluded | not planned |

## 6. Sound

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| S1 | Hi-res kept to the mixer (float output, no forced resampling) | P0 | dumpsys shows PCM_FLOAT at the file's rate | configured, unmeasured |
| S2 | ReplayGain track/album, pre-amp, clipping guard; R128 for Opus | P0 |  | done |
| S3 | Parametric EQ (frequency, gain, Q, type), presets, AutoEq import, per output | P0 |  | done |
| S4 | WF-1000XM5 correction profile | P1 |  | done (opt-in AutoEq) |
| S5 | Limiter / headroom management with EQ | P1 |  | done |
| S6 | Formats beyond Android: APE, WavPack, DSD (DSF/DFF), TTA, Musepack, ALAC edge cases | P1 | each plays from a test file | part (DSD: DSF and DFF play as 88.2 kHz PCM; APE, WavPack, TTA, Musepack still need their own extractors with an FFmpeg decoder) |
| S7 | USB DAC bit-perfect (preferred mixer attributes) | P1 | a DAC reports the file's rate and depth | done in code (switch in signal path), needs a DAC to verify |
| S8 | Real codec shown: LDAC/AAC/SBC and LDAC bitrate, from the Earbuds app | P1 | signal path shows "LDAC 990" only when the earbuds say so | blocked: only the Earbuds app knows the real codec (Sony protocol); it needs a small read-only share, and the owner asked not to touch Earbuds |
| S9 | Audio offload for lossy files with screen off | P2 | battery saving measured, no feature lost | done (Battery saver switch: offload only while EQ, channels and ReplayGain are off) |
| S10 | Balance, mono, channel swap | P2 | | done (per output, works with EQ off) |
| S11 | Visualizer | - | owner excluded | not planned |

## 7. Lyrics

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| Y1 | Embedded and sidecar LRC/TTML/TXT | P0 |  | done |
| Y2 | Word-by-word fill, duets, backing vocals, translation | P0 |  | done |
| Y3 | Online lyrics: LRCLIB (line), Kugou KRC (word), saved beside the file | P1 | a song without lyrics gets synced lyrics in one tap | done (Kugou KRC, then LRCLIB; saved in app storage, length checked) |
| Y4 | KRC, QRC, SYLT, Lyricify formats | P1 | parser tests per format | done (KRC, QRC, Lyricify Syllable, ID3 SYLT) |
| Y5 | Offset nudge; line and word timing editor | P1 | fix a late line and save it to the sidecar | done (offset; "Fix timing": tap lines while playing, saved per song in app storage) |
| Y6 | Lyrics on the lock screen / Now Bar line | P2 | current line visible on lock screen | done (optional: the sung line replaces the artist on the lock screen while the app is in the background) |

## 8. System, editing and integration

| # | Feature | P | Done when | Status |
|---|---|---|---|---|
| X1 | Notification and lock-screen controls with artwork | P0 |  | done, phone check pending |
| X2 | Android Auto: browse, search, play from a collection | P0 | works in the Desktop Head Unit | done in code, DHU pending |
| X3 | Assistant: "play X on Music" | P0 |  | done in code |
| X4 | Home-screen widgets (now playing, quick favourites) | P1 | Glance widget updates without polling | done (now-playing widget, pushed on change, no polling) |
| X5 | Tag editor: one song or a whole album, cover, write back, rename/move by template | P0 | edit tags of a FLAC album and see them in Poweramp | done (FLAC, MP3, M4A, Ogg, Opus; one song or many; cover; rename and move by template; no prompt with "manage media") |
| X6 | Quick Settings tile, App Functions for Gemini | P2 | | done (play/pause tile; App Functions: play music, control playback, now playing) |
| X7 | Cast (Chromecast / Nest) | P2 | play a FLAC to a Nest speaker | done in code (Cast via the output switcher; files served on Wi-Fi only while casting); needs a Nest to verify |
| X8 | Streaming (YouTube Music, SoundCloud) joined to the library | after local | M5 | todo |
| X9 | Soulseek search and downloads into the library tree | after local | M6 | todo |

## 9. How "competitive" is decided

Measured on the owner's Pixel, same files, same settings, each app installed side by side:

| Measure | Target | How |
|---|---|---|
| Cold start to a usable list | < 400 ms, not slower than Symfonium | `am start -W`, 5 runs |
| First scan / rescan of 10 000 files | < 60 s / < 2 s | log timing |
| Scrolling 10 000 songs | 0 janky frames at 120 Hz | `dumpsys gfxinfo framestats` |
| Battery, 1 h FLAC over LDAC, screen off | not worse than Poweramp | `dumpsys batterystats` |
| Output format | file rate, float, no resample | `dumpsys media.audio_flinger` |
| Gapless | no gap or click | gapless test album, by ear and waveform of a USB capture |
| Feature table above | every P0 done, P1 done or consciously deferred | this file |

## 10. Release path

1. Phone check of what exists (PHONE_CHECK.md). Fix what it finds.
2. Phone check of everything built on 2026-11-15 (Q3 gapless proof, X7 Cast, S7 DAC, DSD files).
3. Remaining: S6 APE/WavPack/TTA/Musepack (own extractors + FFmpeg decoder), S8 (needs the Earbuds app).
4. Measurements (section 9) against the four apps.
5. Design pass across every screen and state (DESIGN_DIRECTION.md), with goldens.
6. Then streaming (M5) and Soulseek (M6).
