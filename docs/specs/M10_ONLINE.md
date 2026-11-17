# M10: YouTube Music as part of one library (spec for the next agent)

Written by Claude on 2026-11-17 from the owner's report on the phone (Pixel 10 Pro XL, release
music-v1083) and a read of the code at `938eedb`. The owner hands this milestone to another agent
(ChatGPT/Codex). Read `AGENTS.md` first; its rules win over anything here. Every claim below is
labelled: `[код]` read in the source at `938eedb`, `[телефон]` seen on the owner's screenshots,
`[аналіз]` reasoning not yet measured. Nothing in this file was measured on the phone.

## The owner's report (2026-11-17, translated)

1. Make the integration better: an artist should be found both in the library and on YouTube Music,
   with a way to see the rest of their songs there.
2. Think of how to mark local (downloaded) songs versus plain YouTube Music songs.
3. Why does the player say "YouTube Music" under the progress bar?
4. Rethink the design.
5. Opening a YouTube Music playlist freezes for a couple of seconds and loads for long.
6. "Play all" freezes too, and the highlight of the playing row runs into the buttons.

"Think it all through again, very carefully, from the outside."

## What is wrong today, and why

### P1. Freezes on online pages and after Play all (items 5 and 6)

- `[код]` `OnlinePages.kt`, `owned(songs, library)` maps every online song with
  `YouTubeMusic.localCopy`, which is `library.songs.firstOrNull { sameSong(online, it) }`
  (`YouTubeMusic.kt`, `localCopy`/`sameSong`). `sameSong` calls `clean()` (regex based) on both
  titles and artists for every library song. That is playlist size x library size regex work.
- `[код]` `owned(...)` runs inside the composable body of `OnlineAlbumPage` and `OnlineArtistPage`
  (`is Load.Done -> { val songs = owned(raw, library) ... }`), not in `remember` and not off the main
  thread. It runs again on every recomposition of the page. `currentId` (the playing song) is a
  parameter of the page, so every song change, and Play all itself, recomposes the page and repeats
  the whole scan on the main thread.
- `[код]` A fast path already exists and is unused here: `YouTubeMusic.Companion.Index`
  (`keysOf`/`keyOf`/`copyOf`), built once per library in `LibraryViewModel.localIndex()` and used by
  `localFirst()` when playing. `MoreOnYouTubeMusic` and the online search results have the same
  problem as the pages.
- `[аналіз]` The long load itself is one InnerTube `browse` call (`YouTubeMusic.album`), done every
  time a page opens; nothing is cached, and the page shows only a spinner until the whole answer is
  parsed, although the card that opened it already knew the title and the cover.
- `[аналіз]` After Play all, the first online song waits for its stream address
  (`ytm://` resolved in `core/playback/OnlineStreams.kt` through NewPipe) before any sound. Measure
  this separately from the UI freeze; it is network time, not jank.

### P2. Online pages sit under Home's greeting (item 4)

- `[телефон]` A playlist opened from Home shows "Good afternoon" and the settings button above the
  page's own back arrow; while loading, the screen is that header, an arrow and a spinner.
- `[код]` `MusicRoot.kt`: the `TopAppBar` title is `greeting()` whenever `place == Place.HOME` and
  `page == null`; it ignores `onlineStack`. The online pages draw their own back `IconButton` as the
  first list item, so the screen has two headers.

### P3. The playing row's highlight (item 6)

- `[телефон]` On the album/playlist page the highlighted first row touches the Play all / Shuffle
  buttons (no gap), and the highlight looks taller under the text than above it.
- `[код]` `PlayerScreens.kt`, `TrackRowContent`: the highlight is the `ListItem` container colour,
  clipped to a 20 dp rounded shape with 8 dp side padding; the page header `Column` in
  `OnlineAlbumPage` ends right after the buttons row with no bottom spacing. Rows with a
  `QualityTag` and rows without one have different heights, so the list looks uneven.

### P4. "YouTube Music" in the player (item 3)

- `[код]` `YouTubeMusic.format()` returns `"<codec> · <kbps> kbps · YouTube Music"`, and
  `MusicRoot.kt` falls back to the plain text "YouTube Music" before the stream is chosen; the
  player shows it as the format line under the progress bar. `PlayerScreens.kt` also shows
  "Playing from YouTube Music" when an online song has no album.
- The owner does not want the brand there. The format line is about quality; the source belongs
  to a small, consistent mark (see D2), and "Playing from" should name what is really playing
  (the playlist, album, mix or "Search"), as Google's players do.

### P5. Local and online songs look alike (item 2)

- `[телефон]` In a YouTube Music playlist, songs the owner has as files show a FLAC tag and songs he
  does not have show nothing; online rows on album pages have no cover and no mark. Nothing says
  "this one streams" or "this one is downloading".

### P6. The artist is split in two (item 1)

- `[код]` The library artist page ends with `MoreOnYouTubeMusic`: at most 5 YouTube Music songs the
  library lacks and an "Open" button to a separate `OnlineArtistPage`. The online page has photo,
  songs, albums and videos (`OnlineArtist`), but no singles, no related artists, no radio, and the
  two pages never merge. Search shows library results, then a "YouTube Music" heading with songs only:
  no artists or albums from YouTube Music.

## What to build

### D1. One artist page

One page for an artist, whether it was opened from the library, Search, a song's menu or an online
card. The library and YouTube Music are two sources of the same page, not two pages.

- Header: photo (YouTube Music photo when the library has no artist image), name, a line such as
  "12 songs in your library · 2.1M monthly listeners" (only what is known), Play, Shuffle,
  **Start radio** (YouTube Music artist radio, cleaned by `Mix`, falls back to the local mix).
- Sections, each shown only when it has content, in this order:
  1. **Popular**: YouTube Music top songs, each swapped to the owner's file when he has it
     (via `Index`), with the source mark from D2. Show 5, "See all" opens the full list.
  2. **In your library**: the owner's songs by this artist (today's local section).
  3. **Albums** and **Singles and EPs**: one row each, library albums and YouTube Music albums
     merged by normalised title; an owned album shows the library cover and opens the library
     album; others open the online album page. Mark owned albums (D2).
  4. **Videos**: only if the owner turns it on (setting), off by default.
  5. **Fans also like**: related artists from YouTube Music (parse the artist page shelf).
  6. **About**: the bio (existing `AboutCard`), collapsed to 4 lines.
- Offline or YouTube Music off: the page is today's library page, with no empty online sections and
  no error text.
- Search: add **Artists** and **Albums** results from YouTube Music next to songs (one row of
  artists, one row of albums) under the owner's own results; a library artist and its YouTube Music
  twin appear once.

### D2. Marking where a song plays from

One small mark in the same place on every row (after the artist in the supporting line), so the eye
learns it once:

| State | Mark | Colour role |
|---|---|---|
| File, lossless | quality tag as today, "FLAC 16/44.1" | tertiaryContainer |
| File, lossy | quality tag, "MP3 320" | surfaceContainerHighest |
| Online only | small cloud symbol (Material Symbols `cloud`), no text | onSurfaceVariant |
| Online, kept in the stream cache | `cloud_done` | onSurfaceVariant |
| Downloading (Soulseek) | `downloading` with determinate progress ring | primary |

- Rows keep one height whatever the mark (fixed 72 dp two-line rows); the mark never changes the
  row's size.
- Online rows on album and playlist pages show the cover like other rows when the list mixes
  albums (playlists); album pages keep track numbers.
- The row's overflow menu offers **Download** for online songs (exists as a Soulseek action) and
  shows its state.
- A song that becomes a file while visible (download finished, hot swap in `LibraryViewModel`)
  changes its mark in place, without the list jumping.

### D3. The player's lines

- Format line: codec and rate only, the same shape as for files: "FLAC 16/44.1 · 1007 kbps",
  "Opus · 160 kbps", "AAC · 256 kbps". Before the stream is chosen: nothing (keep the line's
  height so the layout does not jump), not the word "YouTube Music".
- "Playing from": what the queue came from: the playlist or album title, "Mix from <song>", the mood,
  "Search", "Your library". Never the service name alone.
- The source mark from D2 may appear once in the player, next to the format line, for online
  songs.

### D4. Online pages that open instantly and never freeze

- Ownership (`owned`) is computed once per loaded list and library version, off the main thread,
  with `Index`, and kept: as part of the load result, or `remember(list, library)` around a
  precomputed map. No `firstOrNull` scan over the library in any composable.
- The page header renders at once from what the card already knows (title, subtitle, cover); the
  song list shows placeholder rows of real row height while it loads. No full-screen spinner.
- Responses are cached in memory (LRU, a few dozen pages) for the session and reused when the same
  page opens again; a pull to refresh reloads. Playlists longer than one page load their
  continuations in the background and append without moving what is on screen.
- Recomposition: `currentId` changes recompose only the rows whose state changed (keys are stable
  already; make the row lambdas stable and avoid passing the whole page state to every row).
- Play all: the UI reacts in the same frame (the mini player shows the first song with a buffering
  state); start resolving the first online song's stream when the page opens (warm the resolver for
  item 0 only, no audio fetched), and resolve the next song ahead while one plays.

### D5. The shell around online pages

- An online page gets the same top app bar as library pages: back arrow and the page title, the
  title collapsing into the bar on scroll. Home's greeting and settings button are not shown while
  an online page is open. Back returns to the exact scroll position of the screen it came from.
- The page header follows the library album page: cover, title, subtitle, then a button row with
  16 dp below it before the first song.

### D6. Design pass (whole M10)

- Follow `docs/DESIGN_SYSTEM.md` and `docs/DESIGN_DIRECTION.md`; Material You colours only.
- One row component for every song list (library, album, playlist, search, artist, queue) with the
  same height, the same mark position and the same playing state: today's tonal highlight, but
  inset by 8 dp from the screen edges and 4 dp from neighbouring rows, never touching other content.
- Review every visible state on the screenshot gallery (`DesignGalleryTest`): loading, empty,
  offline, error, one song, hundreds of songs, long titles, mixed local and online, playing,
  paused, downloading.

## Done when

1. Opening a cached online playlist of 100 songs with a 5 000 song library: first frame of the page
   under 100 ms; no frame over 32 ms while it fills (`dumpsys gfxinfo ua.olex.music framestats`).
   Uncached: header at once, songs within the network time, no frame over 32 ms.
2. Play all on that page: no frame over 32 ms; mini player shows the song within 100 ms of the tap;
   time to first audio for an online song is logged (target under 1.5 s on Wi-Fi, measured, not
   assumed).
3. A song change while an online page is open recomposes only the two affected rows (check with the
   Layout Inspector's recomposition counts or a test that counts compositions).
4. No composable calls `YouTubeMusic.localCopy`; a unit test covers `Index` matching for the cases
   `sameSong` covers today (extras like "(Remastered)", "feat.", accents, 3 s length tolerance).
5. The player never shows the text "YouTube Music" as a format or as "Playing from".
6. The artist page shows library and YouTube Music sections together, works offline, and Search
   shows YouTube Music artists and albums.
7. Gallery screenshots of every state in D6 are reviewed and committed as goldens; tests, lint debug
   and release, and the release build pass (`AGENTS.md`, "How to work").
8. `docs/HANDOFF.md` says what was measured on the phone and what was only built.

## Order that keeps the app releasable

1. D4 ownership off the main thread and memoised (fixes the freezes, smallest change), with tests.
2. D5 shell and header spacing (fixes the double header and the highlight touching the buttons).
3. D3 player lines.
4. D2 marks and one row height.
5. D1 artist page and Search, with parser tests on saved InnerTube answers (fixtures exist in
   `app/src/test/resources`, for example `ytm-radio.json`, `ytm-home-moods.json`).
6. D6 full design review with goldens.

Each step is its own commit on `main`'s branch flow from `AGENTS.md` and leaves the app working.

## Not in this milestone

- Signing in to YouTube Music is done (`YouTubeLogin`/`YouTubeAccount`) and stays as it is.
- No new streaming services, no DRM, no downloading of YouTube Music audio to the library (downloads
  stay Soulseek, lossless).
- No video playback page.
