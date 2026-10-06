<#
.SYNOPSIS
  Change how often the agent talks to the cloud, and restart it.

.DESCRIPTION
  The agent polls for commands and reports its state on two timers. Both cost
  an Edge Function invocation every time they fire, and the free tier allows
  500,000 a month across everything — which the defaults blow through in about
  a week. See docs/DEPLOY.md for the arithmetic.

  Slowing these costs very little in practice: commands sent from the home
  network go straight to the PC over the LAN and never wait for a poll at all,
  and state changes are still reported the moment they happen. The interval is
  only the idle keepalive, and only matters when you are away from home.

  Do not set the heartbeat above 30 seconds without also widening the server's
  presence threshold, or the agent will be marked offline between its own
  reports. That threshold lives in the wolf_age_out_presence migration.

.EXAMPLE
  .\set-intervals.ps1
  Applies the recommended 30 second poll and 30 second heartbeat.

.EXAMPLE
  .\set-intervals.ps1 -PollSeconds 10 -HeartbeatSeconds 15
  Faster, for a project on a paid plan.
#>
[CmdletBinding()]
param(
  [ValidateRange(2, 300)][int]$PollSeconds = 30,
  [ValidateRange(5, 300)][int]$HeartbeatSeconds = 30,

  # Not applied by this script — the controller takes its interval from the
  # WOLF_POLL_INTERVAL_MS function secret. It is here so the arithmetic below
  # can report the whole system's cost rather than the agent's share, which on
  # its own reads as comfortably under budget while the total is over it.
  [ValidateRange(1, 300)][int]$ControllerSeconds = 10,

  [int]$MonthlyBudget = 500000
)

$ErrorActionPreference = 'Stop'

$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run this from an elevated PowerShell prompt.'
}

$configPath = Join-Path $env:ProgramData 'WolfPC\agent.json'
if (-not (Test-Path $configPath)) {
  throw "No agent configuration at $configPath. Install the agent first."
}

$config = Get-Content $configPath -Raw | ConvertFrom-Json

$before = [pscustomobject]@{
  PollIntervalMs      = $config.PollIntervalMs
  HeartbeatIntervalMs = $config.HeartbeatIntervalMs
}

$config.PollIntervalMs      = $PollSeconds * 1000
$config.HeartbeatIntervalMs = $HeartbeatSeconds * 1000

# ConvertTo-Json defaults to a depth of 2, which silently flattens anything
# nested into the literal string "System.Object[]". This config is flat today
# and a depth here costs nothing against the day it is not.
$config | ConvertTo-Json -Depth 10 | Set-Content $configPath -Encoding utf8

Write-Host ''
Write-Host ("  poll      {0,6} ms  ->  {1,6} ms" -f $before.PollIntervalMs, $config.PollIntervalMs)
Write-Host ("  heartbeat {0,6} ms  ->  {1,6} ms" -f $before.HeartbeatIntervalMs, $config.HeartbeatIntervalMs)

$agentMonthly      = [math]::Round((86400 / $PollSeconds + 86400 / $HeartbeatSeconds) * 30)
$controllerMonthly = [math]::Round(86400 / $ControllerSeconds * 30)
$totalMonthly      = $agentMonthly + $controllerMonthly

Write-Host ''
Write-Host '  Edge Function invocations a month, with everything running constantly:'
Write-Host ''
Write-Host ("    agent       {0,12:N0}" -f $agentMonthly)
Write-Host ("    controller  {0,12:N0}   (at {1}s; set by WOLF_POLL_INTERVAL_MS, not by this script)" -f $controllerMonthly, $ControllerSeconds)
Write-Host ("    total       {0,12:N0}" -f $totalMonthly)
Write-Host ''

if ($totalMonthly -gt $MonthlyBudget) {
  Write-Warning ("that is over a {0:N0} budget by {1:N0}. Slow the agent, raise WOLF_POLL_INTERVAL_MS, or use a paid plan." -f $MonthlyBudget, ($totalMonthly - $MonthlyBudget))
} else {
  Write-Host ("  within a {0:N0} budget, with {1:N0} to spare." -f $MonthlyBudget, ($MonthlyBudget - $totalMonthly)) -ForegroundColor Green
}

if (Get-Service -Name WolfPCAgent -ErrorAction SilentlyContinue) {
  Restart-Service -Name WolfPCAgent -Force
  Start-Sleep -Seconds 2
  $service = Get-Service -Name WolfPCAgent
  Write-Host ''
  Write-Host ("  WolfPCAgent is {0}." -f $service.Status) -ForegroundColor Green
} else {
  Write-Warning 'WolfPCAgent is not installed; the new settings apply when it next starts.'
}

Write-Host ''
