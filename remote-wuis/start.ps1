param(
  [string]$NodePath = (Get-Command node -ErrorAction Stop).Source,
  [string]$ConfigPath = (Join-Path $PSScriptRoot 'config.local.json'),
  [string]$Instance = ''
)
$ErrorActionPreference = 'Stop'
if ($Instance -and $Instance -notmatch '^[a-zA-Z0-9_-]{1,40}$') { throw 'Invalid instance name' }
$runtime = Join-Path $PSScriptRoot '.runtime'
if ($Instance) { $runtime = Join-Path $runtime $Instance }
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
foreach ($mode in @('api', 'mqtt')) {
  $entry = Join-Path $PSScriptRoot "$mode/app.mjs"
  $record = Join-Path $runtime "$mode.process.json"
  if (Test-Path -LiteralPath $record) {
    $saved = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.pid)" -ErrorAction SilentlyContinue
    if ($existing -and $existing.CommandLine.Contains($entry) -and (!$saved.config -or $existing.CommandLine.Contains($saved.config))) { Write-Output "$mode remote is already running ($($saved.pid))"; continue }
  }
  $process = Start-Process -FilePath $NodePath -ArgumentList @('"' + $entry + '"', '--config', '"' + $ConfigPath + '"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime "$mode.out.log") -RedirectStandardError (Join-Path $runtime "$mode.err.log")
  @{ pid = $process.Id; entry = $entry; config = $ConfigPath; startedAt = (Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $record
  $port = if ($mode -eq 'api') { $config.apiPort } else { $config.mqttPort }
  Write-Output "$mode remote started: http://$($config.bindAddress):$port (PID $($process.Id))"
}
