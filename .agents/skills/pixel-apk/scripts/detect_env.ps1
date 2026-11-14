$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'detect_env.py')
exit $LASTEXITCODE

