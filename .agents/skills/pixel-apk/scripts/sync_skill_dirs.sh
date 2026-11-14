#!/usr/bin/env bash
set -euo pipefail
root="$(cd "${1:-.}" && pwd)"
src="$root/.agents/skills/pixel-apk"
dst="$root/.claude/skills/pixel-apk"
test -f "$src/SKILL.md"
test ! -e "$dst" && test ! -L "$dst" || { echo "Destination exists; refusing overwrite" >&2; exit 2; }
mkdir -p "$root/.claude/skills"
ln -s ../../.agents/skills/pixel-apk "$dst" || cp -R "$src" "$dst"
for f in AGENTS.md CLAUDE.md; do
  touch "$root/$f"
  grep -qF '.agents/skills/pixel-apk/SKILL.md' "$root/$f" || printf '\nFor Pixel APK tasks, read .agents/skills/pixel-apk/SKILL.md.\n' >> "$root/$f"
done

