param(
  [string]$NodePath = '',
  [int]$Port = 8800
)
$ErrorActionPreference = 'Stop'
if (-not $NodePath) {
  $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
  if (-not $nodeCommand) { throw 'Install Node.js 20+ or supply -NodePath' }
  $NodePath = $nodeCommand.Source
}
$runtime = Join-Path $PSScriptRoot '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$entry = Join-Path $PSScriptRoot 'server.mjs'
$record = Join-Path $runtime 'preview.process.json'
if (Test-Path -LiteralPath $record) {
  $saved = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
  $existing = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.pid)" -ErrorAction SilentlyContinue
  if ($existing -and $existing.CommandLine.Contains($entry)) {
    Write-Output "Simple preview already running on port $($saved.port) (PID $($saved.pid))"
    exit 0
  }
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is already in use" }
$process = Start-Process -FilePath $NodePath -ArgumentList @('"' + $entry + '"', "$Port") -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'preview.out.log') -RedirectStandardError (Join-Path $runtime 'preview.err.log')
@{ pid = $process.Id; entry = $entry; port = $Port; startedAt = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $record
Write-Output "Simple remote style preview started on port $Port (PID $($process.Id))"
