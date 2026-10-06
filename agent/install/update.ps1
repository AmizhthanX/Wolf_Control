# Update an already configured service without changing its firewall or configuration.
param(
  [string]$StageDir = (Join-Path $PSScriptRoot '..\..\.tools\agent-update')
)
$ErrorActionPreference = 'Stop'
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run update.ps1 from an Administrator PowerShell.'
}
$stage = (Resolve-Path -LiteralPath $StageDir).Path
$source = Join-Path $stage 'WolfAgent.exe'
$target = Join-Path $env:ProgramFiles 'WolfPC\WolfAgent.exe'
$backup = "$target.previous"
$resultFile = Join-Path $stage 'install-result.json'
$replaced = $false
$copiedBackup = $false
try {
  if (-not (Test-Path -LiteralPath $source) -or -not (Test-Path -LiteralPath $target)) {
    throw 'The staged build or installed agent is missing.'
  }
  $service = Get-Service WolfPCAgent
  Stop-Service WolfPCAgent
  $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
  Copy-Item -LiteralPath $target -Destination $backup -Force
  $copiedBackup = $true
  $replaced = $true
  Copy-Item -LiteralPath $source -Destination $target -Force
  Start-Service WolfPCAgent
  (Get-Service WolfPCAgent).WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
  @{ success = $true; message = 'Agent updated; existing configuration retained.' } |
    ConvertTo-Json | Set-Content -LiteralPath $resultFile
  Write-Host 'Agent updated; existing configuration retained.'
} catch {
  $failure = $_.Exception.Message
  if ($replaced -and $copiedBackup) {
    Stop-Service WolfPCAgent -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $backup -Destination $target -Force -ErrorAction SilentlyContinue
  }
  Start-Service WolfPCAgent -ErrorAction SilentlyContinue
  @{ success = $false; message = $failure } |
    ConvertTo-Json | Set-Content -LiteralPath $resultFile
  Write-Error $failure
  exit 1
}
