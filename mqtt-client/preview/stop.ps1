$ErrorActionPreference = 'Stop'
$record = Join-Path $PSScriptRoot '.runtime/preview.process.json'
if (!(Test-Path -LiteralPath $record)) { Write-Output 'Preview is not running'; exit 0 }
$saved = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
$existing = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.pid)" -ErrorAction SilentlyContinue
if ($existing -and $existing.CommandLine.Contains((Join-Path $PSScriptRoot 'server.mjs'))) { Stop-Process -Id $saved.pid; Write-Output 'Preview stopped' }
Remove-Item -LiteralPath $record
