# Music: instructions for every agent

A private music player for one phone: Oleksandr's Pixel 10 Pro XL (Tensor G5, Android 17) with
Sony WF-1000XM5 earbuds. Local lossless files first, streaming and downloads joined to them, at the
quality bar of Google's own Material 3 Expressive apps. Delivered through Obtainium from this
repository's releases.

Any agent (Claude, Codex, others) continues the same work from these files. Read them in this order:

1. `AGENTS.md` (this file): rules and how to work here.
2. `docs/HANDOFF.md`: the state right now, what is verified and what is only on paper.
3. `docs/ROADMAP.md`: the task board. Pick the first open task of the lowest open milestone.
4. `docs/ARCHITECTURE.md`: modules, boundaries and the decisions already made (do not reopen them
   without a reason written down).
5. `docs/AUDIO.md`: the sound path on this phone. `docs/RESEARCH.md`: other players and what to take.
6. `.agents/skills/pixel-apk/SKILL.md`: the owner's skill for building and delivering his Pixel apps.
   Its rules apply here; where this file is more specific, this file wins.

## Rules

- Talk to the owner in Ukrainian. App UI, code, comments, commits and docs are in English.
- Never write U+2013 or U+2014 (en and em dashes) in code, docs or UI text.
- UI colors come only from the system wallpaper palette (Material You), including the player,
  sheets and dialogs. Never recolor the app from album artwork. Functionality first, then a complete
  design review of every visible state (owner, 2026-11-14). No visualizers.
- One phone, newest Android only: `minSdk = 37`. No compatibility branches, no `Build.VERSION` checks
  for older releases, arm64-v8a only. (`-PminSdk=35` exists only for tools that lag, like Robolectric.)
- Pins in `gradle/libs.versions.toml` are deliberate. Do not downgrade silently; a raise is its own
  commit with the reason. Kotlin, the Compose compiler plugin and KSP move together.
- No analytics, no backend of our own, no accounts required. Network is used only for what the owner
  asked for: lyrics, streaming, downloads, artwork.
- No DRM circumvention and no decrypted exports of protected streams. Sources that need it are out.
- Secrets never enter the repository, logs or chat: the owner key, its passwords, cookies, tokens.
- Say what is verified and how. Label claims in docs and reports: `[код]` read in source,
  `[docs]` official documentation (with URL), `[AOSP]`, `[медіа]`, `[аналіз]` reasoning,
  `[не знайдено]`. Compiled, unit-tested, emulator-tested and measured on the phone are four
  different things; never promote one to another. Mark examples that were not compiled `verified: ні`.
- Never fabricate goldens, baseline profiles, test results or screenshots. A missing gate blocks a
  release; it is not skipped quietly.
- Comments say why, not what. Public types get a KDoc that a new agent can act on.

## How to work

- Build and test (JDK 17 or newer, Android SDK with `platforms;android-37.0`, Gradle 9.9.1):

  ```sh
  ./gradlew test -PminSdk=35                     # Robolectric API 35; pure Kotlin tests run here too
  ./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
  ```

  The minSdk override is for tests only. APKs are built separately with the default minSdk 37.

- CI (`.github/workflows/build.yml`) runs on every push: tests, lint, release build. On `main`, with
  the owner key in the repository secrets, it publishes a release `music-v<versionCode>` that
  Obtainium installs. Without the key nothing is published.
- One shared line: `main`. Everything every agent does ends up there, and every agent starts from
  it. A task branch (`agent/<milestone>-<short-name>`, or the branch your harness assigns) lives only
  while its task is in work:
  1. Start: fetch and branch from the newest `main`.
  2. Work in small commits; push the branch so CI runs.
  3. Finish: when CI is green, rebase onto the newest `main` if it moved, then fast-forward `main`
     to the branch (or merge its pull request) and delete the branch.
  Do not end a session with finished work outside `main`; if it cannot go in (CI red, the owner's
  word needed), say so at the top of `docs/HANDOFF.md` with the branch name. Never build on
  another agent's unmerged branch when `main` can be used; never force-push `main`. `main` must
  always build and pass CI: each `main` build is released to the owner's phone.
- Claim a task before you start: in `docs/ROADMAP.md` change `[ ]` to `[~]` and add your agent name
  and the date. When done change it to `[x]` and add the commit. One agent per task.
- End every session by updating `docs/HANDOFF.md`: what changed, what is verified (with the command
  and its result), what is only on paper, what failed, what to do next. The next agent starts there.
- New decision (a library, a format, a boundary): add a numbered entry to "Decisions" in
  `docs/ARCHITECTURE.md` in the same commit. New dependency: only through the version catalog.
- Put logic that needs no Android into the pure Kotlin modules and test it there. A parser, a
  matcher, a protocol codec or a ranking rule without tests is not done.
- Keep modules apart: `:core:*` never depends on `:app`; `:core:model` depends on nothing; screens
  never touch ExoPlayer, Room or the network directly, only the classes `AppGraph` hands out.
- Small commits, each building and passing tests, with a message that says what changed for the
  owner and why.

## Quality gates before a release

Unit tests, `lintRelease`, an R8 release build that starts, screenshot goldens reviewed by eye,
accessibility checks, cold start and background checks, signed with the owner key
(`.agents/skills/pixel-apk/references/quality.md`). Gates not built yet are listed in
`docs/ROADMAP.md`; until then a release says which gates were not run.
