# Pixel first-run checklist

Status: not run on a physical device. JVM, Robolectric and CI builds do not satisfy this checklist.
Record the tested commit, APK versionCode, Pixel build, and WF-1000XM5 firmware in HANDOFF.md.

## Library and permissions

1. Open Music without granting audio permission. The explanation stays usable; no crash.
2. Deny audio access, then retry. After permanent denial, Allow opens the app's system settings.
3. Grant access. Songs populate, with title/artist and only known audio properties.
4. Browse Albums, Artists, Genres and Folders. A compilation groups under album artist; identically
   named albums by different artists and same-named folders at different paths stay separate.
5. Open a multi-disc album and tap Play all. Verify disc/track order, then select a middle track.
6. Search an accented artist without accents, a partial title, and an artist plus title prefix.
   Clear search, rotate the phone and return to the same collection.
7. Copy several files into Music while the app is visible. After MediaStore indexes them, one
   settled refresh updates the list. Remove a file and check it disappears. While the app is
   backgrounded, the UI observer stops; an active playback service may keep watching source changes.
   Returning skips a full scan if the MediaStore version/generation is unchanged.
8. Revoke audio permission in Settings and return. Regrant and retry. A provider failure must not
   be presented as an empty successful scan or delete the saved library.

## Playback and lifecycle

1. Play known 16-bit and 24-bit FLAC files, MP3, AAC/M4A and Opus samples. Confirm audible playback
   and metadata. Unknown M4A codec or bit depth should be absent, not guessed.
2. Tap a song immediately after opening Music. The selection survives controller connection.
3. Pause while buffering; it must remain paused when buffering completes. Resume a paused track.
4. Let the last track end and press Play. It restarts. Try a missing/corrupt file: a visible error
   offers retry and the next song remains reachable.
5. Seek while paused, then use the notification to seek/change tracks. Position should update.
6. Turn the screen off with the player page open. Audio continues; the UI position loop and output
   observer stop. Return, then rotate: there must still be only one playback session.
7. Test notification and lock-screen play/pause/previous/next, and tapping the notification.
8. Test WF-1000XM5 buttons, disconnection, an incoming call and removing an earbud. Wear detection
   depends on an earbud media command; audio-becoming-noisy alone is not evidence for it.
9. Swipe the app away while playing (continues), then while paused/ended (stops).
10. Use font scale 2, light/dark theme and a narrow/split window. Scroll the player page and reach
    every control. Review appearance and TalkBack labels by hand.
11. Set a 5-minute sleep timer, close the player page and turn off the screen. Music must pause
    once at expiry. Replace a 5-minute timer with 15 minutes and verify the old deadline does not
    pause playback. Cancel a timer, reconnect the UI, and verify the displayed active state.

## Evidence to capture

With authorized ADB access, after each relevant state:

```sh
adb shell am start -W ua.olex.music/.MainActivity
adb shell dumpsys activity services ua.olex.music
adb shell dumpsys media_session
adb shell dumpsys media.audio_flinger
adb shell dumpsys power
```

Do not put private filenames, notification contents or device identifiers into public logs.
Record pass/fail/not-run per scenario, including reproduction steps for failures. Audio bit depth
and codec measurements follow AUDIO.md. Baseline profiles, screenshot goldens and automated
accessibility checks remain separate M2/M9 gates.

## Personal library and queue recovery

1. Add an album to a new playlist, rename it, move/remove songs, then delete the playlist.
   Favourites and music files remain intact; canceling Delete changes nothing.
2. Save favourites, play songs, restart the app. Playlists, favourites and recent history remain.
   Pause/resume and buffering do not duplicate a history start; repeating a track does.
3. Move or remove a saved file and rescan. Missing entries remain visibly unavailable; a moved
   matching recording resolves to its current file. Test with two different versions of a song.
4. Add the same song twice to the playback queue. Move/remove one occurrence; the other remains.
   Open a row menu, let playback advance, then act: the chosen occurrence must still be targeted.
5. Enable shuffle, use Play next, and confirm the inserted song is actually next. Test repeat off,
   repeat all and repeat one separately. Queued songs must not interrupt the current song.
6. Pause and swipe away, then reopen. Queue, shuffle/repeat and position restore paused. Press Play.
   Kill the process while playing, reopen, and check recovery to within the last 60-second checkpoint.
   Explicitly selecting a new song during startup must override the saved queue.
7. Remove every queue item, restart, and confirm the old queue does not return. An unavailable saved
   recording must retain its queue position and show a reason; linked available sources are resolved
   again at restore. A moved-file match still requires unambiguous recording evidence.

## Outputs and sound quality

1. With WF-1000XM5 and a paired Bluetooth speaker available, use Output to switch earbuds to speaker
   to phone speakers. Verify the route label updates even when both Bluetooth devices stay connected.
   These are system media routes; network casting is not implemented.
2. Disconnect the active earbuds/speaker during playback. Audio must pause rather than unexpectedly
   continue on phone speakers. Reconnection must not start playback without the expected media command.
3. Check physical volume keys before and during playback: they adjust media volume. Switch output
   and confirm Android retains its own device volume policy; the app adds no volume boost.
4. Open Audio path while playing 16/44.1 and 24/96 FLAC. Record decoder and AudioTrack encoding/rate.
   Compare with audio_flinger and Bluetooth dumps from AUDIO.md. The AudioTrack report is not proof
   of final mixer format, LDAC 990 kbps, audible transparency or bit-perfect output.
5. Play a known gapless album across track boundaries with the screen off for 30 minutes on each
   relevant output. Test incoming calls and notification interruptions. Record audible gaps or clicks,
   route, battery loss and any thermal change. No claim of Poweramp/Symfonium parity is verified yet.


## Audit continuation acceptance (2026-11-17)

Not run here. Record phone firmware, app version and actual result for each scenario.

1. Save an online song in Liked, a playlist and history. Restart without network: its metadata
   remains in Songs, Home, search and smart lists. A matching downloaded file replaces the online
   row once, while removing that file brings back the saved online reference. Live takes remain
   separate. A played online song older than the 200-event history page remains searchable.
2. Start an online mix, turn the screen off and listen past the original queue. Confirm that the
   service appends songs without reopening Music. Replace the queue during a slow radio request;
   none of the old mix's result may enter the new queue. Process-death mix cursor recovery is open.
3. Offline, select a queue containing an uncached online song, a cached song and a local file.
   All three occurrences must remain in place. Playback starts at an available occurrence and
   skips a later unavailable song without deleting it. With no available songs the queue is kept
   paused. Reconnect and confirm the original unavailable occurrence can play.
4. Watch the cache marks while a stream finishes caching, then clear the cache or lower its limit.
   Cached marks must disappear on eviction without changing tracks or reopening the screen.
   Change the image budget and immediately leave Settings; covers must still load afterwards.
5. Open a playlist longer than one page containing deliberate repetitions, including the same
   video on either side of a page boundary. Confirm count and order. Interrupt the network during
   pagination and reopen; no previously accepted occurrence may duplicate or disappear. Check
   account playlist discovery with enough playlists to require a continuation.
6. Retime an online song, then play with Music hidden and the lock screen visible. Edited text,
   sidecar priority for a file, and the saved offset must agree with the full player. Review word
   filling at 120 Hz with Perfetto or composition tracing; JVM rendering is not a frame-time result.
7. Enable shuffle and repeat, seek within a mixed queue and restart the process. Resume from the
   system card. Verify current occurrence, position, actual Next order, repeat and Playing from.
   Repeat with a new explicit selection during startup: the new selection must win.
8. Search for a saved online song through a system browser or voice entry point, then play it.
   Physical Android Auto and Gemini behavior remains unverified by the JVM suite.

## Central source policy acceptance (service-source-policy)

Not run on Pixel/Sony. These scenarios supplement, not replace, the tests above.

1. Start an online track, download its matching file, and rescan while it plays. Audio must not
   restart or pause. A later queued occurrence may use the file, keeping its original context/key.
2. Remove the selected local source during playback. Verify a retained YouTube reserve can recover
   at the same position, with no second history start. Pause during recovery: it must stay paused.
   With no reserve, retain the unavailable row and skip in the actual shuffle order only if playing.
3. Save two same-title takes with incompatible lengths, then add a file close to both. Neither
   saved take may claim that file automatically. Check named remixes, live venues and CUE clips too.
4. Select new queues rapidly during startup and system resumption. The latest explicit selection
   wins. Clear before restore completes, restart, and verify the old queue does not return.
5. Change sources from the app, external browser, widget and notification. Confirm equivalent source
   choice and metadata. Repeat while offline and with local audio permission denied/revoked.
6. Start a long manual scan, then select a cached or online song. Selection must use the current
   snapshot without waiting for the full scan. Measure actual command latency and rare long frames.

## Sleep timer follow-up

1. Set a timed stop, lock the screen, and verify the session pauses at the deadline.
2. During the fade, manually pause and resume, then verify that the original deadline still stops playback.
3. During the fade, cancel or replace the timer. Verify the old deadline does not stop playback and
   that replacing the timer does not cancel a manual fade.
4. Run End of this song on the last item and on a queue with a following item. Verify the timer clears,
   the next item does not start, and the expected queue position is retained. Repeat with shuffle and XM5.
5. Repeat timed playback over Cast. End-of-track currently relies on the local ExoPlayer end event.

## Downloads and player feedback

1. Start an album download, lock the Pixel, and verify the service notification stays while work is
   queued. Tap a download notification and check its row is shown first. Cancel a waiting job and
   an active transfer; Retry must start only the selected job.
2. Stop the process during the second file of an album and reopen Music. Confirm the first file is
   retained once, the pending row is cleaned, and the incomplete transfer restarts. Check behaviour
   with notifications denied and after forcing a data-sync foreground timeout.
3. Pull a predictive back gesture on an online artist or Settings, cancel it, then commit it. Check
   the previous scroll position and the mini player. Test missing file and network failure feedback.

## Account edits, radio and adaptive controls

1. Like a song on both devices, then unlike an imported song remotely. Refresh in Music and
   verify only that favourite is removed. Disconnect while toggling likes, then reconnect.
2. Edit a remote playlist containing two occurrences of one song. Move the second, remove the
   first, and verify the remaining occurrence. Change the playlist on the web while its editor is
   open and verify stale order is rejected rather than overwritten.
3. Open a playlist from Playing from, then start a radio. Let a mix occurrence play, kill the
   app process and reopen playback. Verify the cursor resumes without changing the queue order.
4. Verify the system media heart with the app closed, the transport at large display size,
   Home at maximum font size, and gapless offload with and without ReplayGain/EQ.


## Now Playing cover and paused progress

1. Pause partway through a local song. Press Home and reopen Music. The thumb, elapsed time and
   mini-player line must show the paused position before Play. Repeat after the process is killed,
   and seek back to zero while paused to verify an actual zero still appears immediately.
2. Switch rapidly using Next, Previous and cover swipes. Try a row with no cover and an online
   cover that loads slowly. Check for placeholder flashes, a second slide, or late artwork.
3. Turn lyrics on and off during a long Playing from title, including at large font/display scale.
   The overflow and lyric-settings button stay in their horizontal slots. Try TalkBack while the
   lyrics options fade out.
4. Switch between albums with square and non-square videos, songs of the same album, a missing
   file, and a known corrupt video. Check the first frame, cropping, looping and still fallback.
   Pause, leave the app, open lyrics and switch on Battery Saver. The video must not run offscreen.
5. Tap Play and then Next rapidly. The transport buttons retain their positions across play,
   pause and buffering. Review touch response and cover transitions at 120 Hz and with system
   animations disabled.
6. Hold a queue row at the top or bottom edge without moving your finger. The list scrolls with
   the row still under your finger. Repeat at large font, and after an external edit to the queue.

7. Open Now Playing by tap and swipe, then collapse with the chevron, downward drag and system
   back. Cancel a back gesture halfway. Check the cover reaches the same thumbnail each time.
   Repeat from inline lyrics and while a new video is preparing. Use two tracks with different
   covers, repeat the swipe in both directions and watch the centre when the title changes.

## Core Perfection phone-only baseline

Use the already installed build and record its Obtainium tag if visible. The same steps can be
repeated on the candidate later. Use the same track, Wi-Fi, output route and audio settings.
Choose one local FLAC with an identifiable original on YouTube Music. Start with Music paused
and no active download. This test records source choice, visible behaviour and perceived delay.

1. In Android Settings, open Music's app info and tap **Force stop**. Open Music normally.
   In YouTube Music Search, tap the original recording once as soon as its row appears. Wait
   for sound before considering a second tap. In Now Playing, record **FLAC**, **online**, or
   **unclear** and the number of taps. Check whether recommendations appeared before or after
   sound, if it was visible.
2. Stop or pause the track, return to the same search, and choose the same recording again.
   Record the same fields for this warm case. If a second tap was genuinely required to produce
   sound, record whether the source changed on that second tap.
3. Search for a clearly distinct live version or edit that is not in the library. Check that it
   does not select the studio FLAC. With airplane mode and Bluetooth manually restored if needed,
   play the FLAC through Library Search. Record whether either case selected an unexpected source.
4. Optional late-file case: while a stream is playing, add or finish importing its local copy
   through the normal phone workflow. Record whether the current audio restarts, switches or
   continues, then replay and record the source. Use a second recording for the fresh first-tap
   baseline if the first has already entered play history.

Use the delay buckets **under 1 s**, **1 to 3 s**, **over 3 s**, or **no audio**. They are
subjective estimates. One failure is useful evidence. One success is a single observation.
A phone-only report cannot establish exact audio onset, frame timing or attributed energy.
No track title, email, network name or file path is required in the shared report.

## Core Perfection first tap

Status: awaiting Pixel 10 Pro XL / Android 17 and WF-1000XM5. Record app version code, exact build
fingerprint, Wi-Fi/cellular, signed-in/public mode and codec where available. Use the signed branch
candidate after CI and owner certificate verification. Use a uniquely identifiable local FLAC and
the matching original video. Start with a recording that has no existing play history.

1. Cold-start Music. Open YouTube Music Search and tap the first matching row as soon as it appears.
   Verify that the player indicates FLAC on the first tap, starts it at the intended position and
   adds one history event. Repeat from warm Search, full Songs results and a Home card.
2. Repeat with the same title and a distinct live/edit version. Verify the unlinked version never
   plays the studio FLAC. With a title-only unknown item the stream can still play if its metadata
   does not arrive within the budget.
3. With slow Wi-Fi, tap an owned file. Listen for the seed before recommendations arrive. Pause,
   skip, or replace the queue before the provider answers and confirm the old continuation cannot
   resume or repopulate it. Repeat with healthy playback while importing a better eligible file.
4. Record audible onset with a test tone and external capture if available. In a debuggable build,
   `adb logcat -s MusicCore` provides redacted source-stage counts and an `isPlaying` event proxy.
   Report capture type separately. Repeat on the owner-signed release build for physical acceptance.
