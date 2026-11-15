# Design system: how the app looks, moves and answers the hand

Owner rules kept from DESIGN_DIRECTION.md: wallpaper colours everywhere (artwork never recolours the
UI), Material 3 Expressive, Google Sans Flex, Material Symbols. References: Pixel apps (Clock, Recorder,
Settings on Android 16/17), YouTube Music, LastWave and PixelPlayer, improved where they are vague.

## Structure (three places, one player)

| Place | What it is for | Top of the page |
|---|---|---|
| Home | One tap to the usual: shuffle all, played lately, added lately, your artists, smart lists, most played; cards to Stats and Duplicates | "Music", audio settings, rescan |
| Library | Everything by kind: Albums, Artists, Songs, Playlists, Smart, Favourites, Genres, Folders, Years, Composers, History (chips) | "Library", search |
| Search | One field; recent searches when empty; artists, albums and songs as you type | the field itself |

- Short navigation bar (M3E) at the bottom; the mini player floats just above it.
- Back from Library or Search goes to Home; back on Home leaves the app (Android's rule).
- Stats and Duplicates open as pages over Home with a back arrow and predictive back.

## The player

- Mini player: a rounded card above the navigation bar. Tap or swipe up opens the player; swipe left
  or right skips (the card follows the finger, snaps, gives a tick); the thin line is the progress.
- Player page: slides up from the mini player and follows the finger down to close; predictive back
  shrinks it toward the mini player before it closes.
- Artwork: swipe left/right for next/previous; double tap adds to favourites (a heart blooms);
  long press opens song actions. Settles back a little while paused.
- Progress: wavy while playing (the Android 16 media wave), flat while paused or dragged; drag or
  tap anywhere on it to seek; times under it.
- Transport: previous / play-pause / next; play-pause is the biggest shape and morphs between a
  circle (paused) and a wide rounded square (playing).
- Row of actions: shuffle, repeat, lyrics, sleep timer, output, more; each shows its state in
  shape and fill, not only colour.

## Lists

- Track row: cover, title, artist, quality tag. Swipe right: play next. Swipe left: add to queue
  (the colour and icon appear under the finger, a tick at the threshold, a short message after).
  Long press starts selection; then a floating toolbar (M3E) holds Play, Play next, Queue,
  Playlist, Tags, All, Close.
- Albums: grid of covers. Artists: grid of round photos (Deezer, fetched once, initial letter
  until then). Collections open a page with a large cover or photo, Play, Shuffle, Add.
- Title-sorted song lists have a letter rail; dragging it ticks at each letter.

## Motion

- Spatial springs from MaterialTheme.motionScheme for anything that moves; effects springs for
  colour and alpha. Nothing linear, nothing longer than it needs to be.
- Sound fades: 0.2 s down on pause, 0.3 s up on play; the last 30 s of a sleep timer fade out.

## Haptics (one vocabulary)

| Moment | Feedback |
|---|---|
| Play, pause, toggles on/off | TOGGLE_ON / TOGGLE_OFF |
| Swipe passes its threshold (skip, queue) | GESTURE_THRESHOLD_ACTIVATE |
| Letter rail, star rating, seek steps | SEGMENT_TICK |
| Long press to select | LONG_PRESS |
| Favourite, saved, done | CONFIRM |
| Refused (nothing next, no permission) | REJECT |
