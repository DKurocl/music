# Design system: every element, and why it is that way

Owner rules (DESIGN_DIRECTION.md): wallpaper colours everywhere (artwork never recolours the UI),
Material 3 Expressive, Google Sans Flex, Material Symbols. References: Pixel apps on Android 16/17,
YouTube Music, LastWave, PixelPlayer; taken further where they are vague. Review without a phone:
`DesignGalleryTest` renders every main screen to `app/build/reports/ui-previews/gallery`.

## Principles behind every decision

1. **Music first, controls second.** Covers and names are the largest things; buttons are where the
   thumb is, not where the eye should rest.
2. **One way to do a thing, and a quicker way for those who know.** Every gesture has a visible
   equivalent (a button or a menu item), so nothing depends on discovering a gesture.
3. **Show state with shape and fill, not only colour** (accessibility, and it reads at a glance).
4. **Nothing empty or useless on screen.** Cards and sections appear only when they have content
   (Duplicates only when there are any; "Played lately" only after something was played).
5. **Every screen can go somewhere sensible.** From a song: its album and artist. From the player:
   its album. From a Home section: the full list.
6. **The thumb zone.** Navigation, mini player, floating toolbars and primary buttons are in the
   lower half; the top holds titles and rarely used actions.

## Structure

| Place | Purpose | Top bar | Why |
|---|---|---|---|
| Home | the usual thing in one tap | greeting, Settings | opening the app should start music, not ask where to go |
| Library | everything by kind | "Library", Search | browsing is a different mood from starting; kinds are chips, not more tabs, so the bar stays at three |
| Search | find by words | the field itself (no title) | the field is the page; recent searches and genres fill the empty state |

- **Short navigation bar (M3E)**: three items only — more would shrink targets and labels. The
  selected item fills its icon and thickens it (state by shape).
- **Back**: Library and Search go back to Home; an open album goes back to its grid; Home leaves.
  Predictive back previews each step.
- **Pages over Home** (Settings, Your listening, Duplicates) have a back arrow and keep the
  navigation bar, so the player stays one tap away.

## Home

- **Greeting as title** ("Good evening"): personal, and replaces a redundant "Home" label.
- **Three starts — Shuffle, Liked, New**: the three things people start most. A row of three is
  quicker to scan than one huge button and leaves room for music below. Shuffle is filled (most
  common); the others tonal. Liked/New are disabled, not hidden, when empty, so the row never jumps.
- **Played lately / Your artists / Added lately / Smart playlists**: horizontal rows so more fits
  above the fold; each heading is a full-width tap target with a chevron that opens the whole list.
- **Album cards**: tap opens the album; the round play button on the cover plays it directly —
  two intents, two targets, no long press needed.
- **Artist circles**: round, unlike albums, so artists and albums never look alike.
- **Cards — Your listening, Duplicates**: Duplicates only when there is something to tidy.
- **Most played**: a plain list at the bottom, for quick access to favourites by habit.

## Library

- **Kind chips** in order of use: Albums, Artists, Songs, Playlists, Smart, Favourites, Genres,
  Folders, Years, Composers, History. They scroll; the selected one shows a check (shape, not colour).
- **Album and artist order**: A–Z, Newest, Year (albums), Most played — one tap, kept per kind.
- **Song lists**: one row of chips — Sort first, then filters (Lossless, Hi-res, Unplayed, 4+ stars,
  Decade, Genre), then a Select button at the end. One row instead of two keeps songs higher.
- **Albums**: a grid of covers, two per row: covers are how albums are recognised.
- **Artists**: a grid of round photos, three per row (photos from Deezer, cached; the initial
  until then, so the grid never has holes).
- **Album page**: big cover, title, artist, then "year · songs · minutes · quality" in one line;
  Play all (filled), Shuffle, Add (tonal). Tracks show their number, not the same cover again, and
  quality only when it differs inside the album. Disc headers for multi-disc albums. The top bar
  shows a back arrow, and the album's name once the cover has scrolled away.
- **Letter rail**: appears while the list moves or is held, ticks at each letter, then hides, so it
  never covers the rows' menu buttons.

## Search

- Field focused on arrival (you came here to type). Empty state: Recent searches and Genres.
- Results in sections — Artists (round photos), Albums (covers), Songs — in that order, because a
  name typed is most often an artist or album. Opening a result remembers the search.
- No sort or filters: results are in relevance order.

## Song rows (everywhere)

- Cover, title, artist, quality tag (lossless in tertiary colour so it stands out), menu.
- The playing song: accent title and a small wave icon (state by shape).
- **Swipe right: Play next. Swipe left: Add to queue.** The colour and words appear under the finger,
  a tick at the threshold, the row springs back, a short message confirms. Both are also in the menu.
- **Long press: select.** Then a compact bar (close, count, clear, select all) at the top and a
  floating toolbar (Play, Play next, Queue, Playlist, Tags) near the thumb.

## Song actions sheet

Song on top (cover, title, artist · album), stars and play count, then actions ordered by how often
they are used: Play next, Add to queue, Go to album, Go to artist, Favourite, Add to playlist,
Edit tags, Equalizer.

## Mini player

A floating card above the navigation bar: cover, title, artist, play/pause, next, and a thin
progress line. Tap or swipe up opens the player; swipe sideways skips (the card follows the finger).
It hides only when nothing is queued.

## Player

Top to bottom, in the order the eye and thumb need it:
- **Close · "Playing from <album>" · Queue.** Playing-from opens that album.
- **Cover**, large: swipe sideways to skip, double tap to like (a heart blooms), long press for
  actions. It settles back slightly while paused (paused state you can see from across the room).
- **Title** (marquee once if long), **artist** (opens the artist), **like** button.
- **Seek bar**: Android 16's wave while playing, flat while paused or dragged; tap or drag anywhere.
- **Signal path** ("FLAC 24/96 · Bluetooth"): opens the audio path; under it, while a sleep timer
  runs, "Stops in 23 min".
- **Transport**: previous, play/pause, next. Play/pause is the biggest shape and morphs: circle when
  paused, wide rounded square when playing.
- **Actions**: shuffle, repeat, lyrics, sleep, output, more; on-state is a filled tonal container.
- **Up next** card: the next song, one tap to the queue.
- **Closing**: drag the page down (it follows the finger) or use predictive back (it shrinks).

## Settings

Grouped by what the owner thinks about — Sound, Playback, Library, Lyrics — in Android 16 style
containers (rows touch, groups breathe). Every row says what it does in one line. Options also live
where they matter (EQ in the player, play-on-connect in the audio path); Settings is where all are.

## Motion and sound

- Spatial springs (MaterialTheme.motionScheme) for anything that moves; effect springs for colour.
- Fades: 0.2 s down on pause, 0.3 s up on play, the last 30 s of a sleep timer (Settings can turn
  the pause/play fade off).

## Haptics

| Moment | Feedback |
|---|---|
| Play/pause, switches | TOGGLE_ON / TOGGLE_OFF |
| A swipe passes its threshold (skip, queue, close) | GESTURE_THRESHOLD_ACTIVATE |
| Letter rail, seeking, stars, navigation tab | SEGMENT_TICK |
| Long press to select | LONG_PRESS |
| Liked, saved, done | CONFIRM |
| Refused | REJECT |

## What Android does not allow (and so is not here)

- A music "pill" in the status bar: Live Updates are for time-bound tasks, not media; Samsung's Now
  Bar is Samsung's own system UI. The media card and lock-screen controls are the system's places.
