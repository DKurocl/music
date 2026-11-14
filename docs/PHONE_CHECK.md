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
   backgrounded, its ContentObserver is unregistered; returning triggers a fresh scan.
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
