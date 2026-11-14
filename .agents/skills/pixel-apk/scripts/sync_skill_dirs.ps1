param([string]$Root = '.')
$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $Root).Path
$source = Join-Path $resolved '.agents\skills\pixel-apk'
$destination = Join-Path $resolved '.claude\skills\pixel-apk'
if (!(Test-Path -LiteralPath (Join-Path $source 'SKILL.md'))) { throw 'Missing source skill' }
if (Test-Path -LiteralPath $destination) { throw 'Destination exists; refusing overwrite' }
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Recurse
foreach ($name in @('AGENTS.md','CLAUDE.md')) {
    $file = Join-Path $resolved $name
    if (!(Test-Path -LiteralPath $file) -or !(Select-String -LiteralPath $file -SimpleMatch '.agents/skills/pixel-apk/SKILL.md' -Quiet)) {
        Add-Content -LiteralPath $file -Value 'For Pixel APK tasks, read .agents/skills/pixel-apk/SKILL.md.'
    }
}

