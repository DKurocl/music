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

## Home (expressive)

Why it changed: a list of grey rows read as plain Material 3. Material 3 Expressive asks for shape,
colour, scale and motion with intent; Home is where that pays most, because it sets the mood.

- **Greeting as title** ("Good evening").
- **Bento** (one large, two small): the album to jump back into fills the large card (cover edge to
  edge, name in white on a soft shade, a play button in the 9-sided cookie shape); beside it
  **Shuffle all** (tertiary container, icon in the Sunny shape) and **Liked** (secondary container,
  icon in the Heart shape). Different sizes say what matters most; colours come from the wallpaper's
  three accent families.
- **Play what's new**: a full-width pill below the bento, the third most common start.
- **Your artists**: each photo in a different M3E shape (cookie, clover, arch, burst...): playful,
  and artists can never be mistaken for album covers.
- **Played lately / Added lately**: M3E multi-browse carousels: one large cover, smaller ones beside it
  that grow as they come in; names show only on the large one.
- **Made for you**: smart playlists as colourful pills.
- **Listening card**: one big number ("16 h listened · 232 plays") with a soft shape in the corner;
  Duplicates next to it only when there are any.
- **On repeat**: the five most played with large ranks in the accent colour.

## Library

- **Kind selector**: a connected M3E button group with the four main kinds — Albums, Artists,
  Songs, Playlists. Four equal targets fit the width with no scrolling, the selected one fills and
  rounds fully. The rest (genres, years, folders, history...) moved to Search's tiles.
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

## Search (as in Google Play)

- **First tap on Search** shows the field and browse tiles; **a second tap** focuses the field and
  opens the keyboard (Google Play's behaviour: browsing first, typing when asked). A microphone in
  the field searches by voice.
- **Browse tiles** in two columns: Genres, Years, Favourites, Smart playlists, History, Folders,
  Composers, Lossless, Hi-res, Never played. Name on the left, a tonal icon on the right (colours
  rotate through the wallpaper's accents). A tile opens its list with a back arrow; tiles that would
  be empty (no hi-res files, no composers) are not shown. Recent searches sit above the tiles.
- **Results** in sections — Artists (photos), Albums (covers), Songs — in relevance order.

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

## Feel: small things that make it breathe (used sparingly)

- **Squish on press**: cards, tiles and covers give 3–6 % while pressed and spring back.
- **Rubber band**: a thing dragged where it cannot go (the mini player downward, the cover when
  there is no next song) follows a little with resistance and springs back, instead of ignoring the finger.
- **One icon at a time**: only the icon just activated animates — the chosen navigation item hops
  and fills; a switch that turns on (shuffle, repeat, timer) hops. Nothing animates in bulk.
- **Rows as soft segments**: list rows are inset with rounded corners; the playing song is a light
  tonal pill, selected songs a stronger one.
- **Loading** uses the M3E morphing shape instead of a line of text.

## Mini player

A floating card above the navigation bar, with no solid band behind it: the page scrolls under it
and fades softly toward the bottom (transparent to surface), so it reads as floating, as Google's
players do; lists end with room so their last row can rise above it. Cover, title, artist,
play/pause, next, and a thin progress line. Tap or swipe up opens the player; swipe sideways skips (the card follows the finger).
It hides only when nothing is queued.

## Player

Top to bottom, in the order the eye and thumb need it:
- **Close · "Playing from <album>" · Queue.** Playing-from opens that album.
- **Cover**, large: swipe sideways to skip, double tap to like (a heart blooms), long press for
  actions. It settles back slightly while paused (paused state you can see from across the room).
- **Title** (marquee once if long), **artist** (opens the artist), **like** button.
- **Seek bar**: Android 16's wave while playing, flat while paused or dragged; tap or drag anywhere.
- **Signal path** ("FLAC 24/96 · 850 kbps · Bluetooth", parts divided by dots): opens the audio path; under it, while a sleep timer
  runs, "Stops in 23 min".
- **Transport**: shuffle · previous · play/pause · next · repeat. Shuffle and repeat are small and at
  the ends (they belong to playback, as on Google's media controls); play/pause is the biggest shape
  and morphs: circle when paused, wide rounded square when playing.
- **Actions pill**: lyrics, sleep timer, output, queue in one light rounded pill (M3E floating
  toolbar style). Song actions moved to the top right (⋮), where Google's players keep them.
- **Up next** card: the next song, one tap to the queue.
- **Closing**: drag the page down (it follows the finger) or use predictive back (it shrinks).

## Your listening

One hero number (hours listened, with plays) on a primary card with a soft shape; three small facts
(songs, albums, artists) each with its icon in its own shape; "How your library sounds" as one bar
split by format with a legend; artists you play most in M3E shapes with rounded bars; songs on
repeat with large ranks. Few numbers, large; the rest is shape and colour.

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
