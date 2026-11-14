# Pixel player design direction

Owner direction, 2026-11-14: Google-quality behavior and finish for Pixel 10 Pro XL, Android 17+.
No visualizers. No theme marketplace, shape sliders or decorative settings. Sound quality and daily
listening are the priority. Claude exhausted its quota; the owner assigned Codex the complete local-player delivery
within the 12-hour travel window, including design and metadata integration. Coordinate through main, ROADMAP and HANDOFF before touching shared UI files.

## Reference reviewed

[docs] https://github.com/PixelPlayerHQ/PixelPlayer
[media] Published README screenshots `assets/screenshot1.jpg` and `assets/screenshot2.jpg`, inspected
on 2026-11-14. These are project screenshots, not screenshots of our app or evidence of runtime quality.

Useful observed patterns: a clear artwork-led player, strong typography hierarchy, a prominent
play/pause control, visibly grouped shuffle/repeat/favourite controls, direct queue/lyrics access,
a persistent mini player and labelled bottom navigation. Use Material 3 Expressive components,
Google Sans Flex and Material Symbols consistently with the owner's Earbuds app.

The Home screenshot spends much of its space on an oversized mix headline and decorative artwork.
For this owner's local listening, prioritize immediate access to albums, recent listening and the
current queue. A new Home page is useful only if it reduces taps. Settings and decorative controls
must not displace listening actions. Use the system wallpaper palette everywhere, including the player and modal surfaces. Album artwork never recolors the UI. Finish functionality before a complete design pass across every visible state.

## Acceptance

- Play/pause, previous/next, seek, queue and output are obvious and reachable one-handed.
- Shuffle/repeat status has an accessible description and does not rely on color alone.
- Text, artwork and controls remain usable at font scale 2, in split view and in light/dark modes.
- Motion explains navigation or state changes; no perpetual idle animations. Reduced-motion and
  screen-off behavior need runtime review. A static screenshot cannot establish smoothness.
- Phone gestures, predictive back, media volume keys, lock-screen controls and system output routing
  behave consistently with Android. No imitation of system UI where a real system control exists.
- Review actual screenshots and interactions from our build. Do not substitute reference screenshots
  for our own goldens. Measure frame times on the Pixel before claiming 120 Hz performance.

The reference is inspiration for interaction/design. No source or assets were copied in this review.
