---
name: pixel-apk
description: Build, test, sign and deliver private single-screen Android APKs for Oleksandr's Pixel using Kotlin, Compose Material 3 Expressive, a shared owner key and Obtainium. Use for personal Android utilities, Quick Settings tiles, widgets, alarms, Bluetooth accessories and VPN apps, or reviews against the Pixel design reference. Support Codex and Claude, GitHub Actions first, cloud second and Windows CLI third.
---

# Pixel APK

## Rules

Respond in Ukrainian and write app UI in English. Never emit U+2013 or U+2014 in authored text or code. Minimize questions. Label factual conclusions [код], [AOSP], [docs], [медіа], [аналіз] or [не знайдено]. Distinguish source inspection, compilation, emulator tests and physical-device measurements. Record sources and commands. Never promote a handoff claim to a verified fact without evidence. Mark uncompiled examples `verified: ні`. Preserve fixed pins; do not silently downgrade.

## Execution flow

1. Run `python scripts/detect_env.py` (or .ps1/.sh entry point). Read [build.md](references/build.md). Use b for authenticated CI, c for cloud with working toolchain, a for Windows CLI, none otherwise. An offline cache is usable only after an actual offline build succeeds.
2. Run `python scripts/check_versions.py --network`. Check pinned artifacts and embedded Kotlin against the Compose compiler plugin. Stop build on missing versions. Never install Android Studio, WSL or Docker. Bootstrap SDK only with verified archive checksum and license acceptance.
3. State a one-line specification: task, app name, primary action, persisted state and system features. Default DKurocl/apps, minSdk 35, targetSdk 37. Read [design.md](references/design.md) and relevant sections of [system-apis.md](references/system-apis.md). Keep Nano, App Functions and Shizuku off unless requested.
4. Run `new_app.py --root <repo> --name <slug> --title <English-title> --package <id> --offset <integer>`. Refuse overwrite. Preserve package and signer on migration; inspect current xm5/VPN versionCode before setting OFFSET.
5. Implement the task beyond the scaffold. Follow [quality.md](references/quality.md): unit, visual, accessibility, platform, cold start/background, lint, baseline profile, R8. Never fabricate goldens, profiles, passing tests or screenshots. Missing gate blocks release.
6. Build, zipalign, sign with existing owner key, apksigner verify and compare certificate SHA-256 against trusted owner record. Use sign_local.sh with passwords in environment variables. Never echo, log, commit or include keys in artifacts. Do not generate a replacement owner key.
7. Deliver only the tested signed artifact. Tag AND title: app-v<versionCode>; Obtainium title regex ^app-. Do not overwrite releases or change repository visibility. Verify downloaded artifact identity and increasing versionCode. Honor review-only requests.
8. Return APK when available and short Ukrainian report: Перевірено, Лише на папері, Не вдалося. Include skipped/failed gates. If blocked, deliver source/workflow and explicitly state no signed APK was produced.

## App constraints

Keep app data local; no analytics or cloud backend. Avoid FGS except task-required services such as active VPN. Encrypt secrets with Android Keystore and exclude cloud backup and device transfer. Handle denied/revoked permissions, disconnected devices and unavailable features.
Use genuine M3E components, dynamic color, accessible semantic status and flexible large-font layouts. Never rely only on color. No idle animation loops or stale data disguised as current. Preserve the distinctions between AOSP, proprietary Pixel apps and measured target firmware.

## Portable layout

Keep canonical repository copy in .agents/skills/pixel-apk. Use sync_skill_dirs.sh or .ps1 to create the Claude copy without clobbering existing files. Preserve root instructions and add one pointer line to AGENTS.md and CLAUDE.md. Repository export and ChatGPT installation are separate statuses.

## Resources

The archive includes a standalone QuickFocus reference project at examples/quickfocus. Its README covers the device checks and the manual promotion of a signed CI artifact into an Obtainium release. Treat it as uncompiled until CI produces evidence.

Use [comparison.md](references/comparison.md) to route specialist AGP, Glance and AppFunctions work to official Android references. Keep the owner's fixed versions and delivery workflow.

Read references/build.md for pins/delivery, references/design.md for UI, references/system-apis.md for feature contracts and references/quality.md for gates/trial scenarios. Use assets/template-monorepo and scripts as scaffolding, never as evidence of compilation.

