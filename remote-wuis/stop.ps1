param([string]$Instance = '')
$ErrorActionPreference = 'Stop'
if ($Instance -and $Instance -notmatch '^[a-zA-Z0-9_-]{1,40}$') { throw 'Invalid instance name' }
$runtime = Join-Path $PSScriptRoot '.runtime'
if ($Instance) { $runtime = Join-Path $runtime $Instance }
foreach ($mode in @('api', 'mqtt')) {
  $record = Join-Path $runtime "$mode.process.json"
  if (!(Test-Path -LiteralPath $record)) { continue }
  $saved = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
  $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($saved.pid)" -ErrorAction SilentlyContinue
  if ($process -and $process.CommandLine.Contains($saved.entry) -and (!$saved.config -or $process.CommandLine.Contains($saved.config))) {
    Stop-Process -Id $saved.pid
    Write-Output "Stopped $mode remote ($($saved.pid))"
  }
  Remove-Item -LiteralPath $record
}
