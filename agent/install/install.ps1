<#
.SYNOPSIS
  Build, install and configure the WolfPC Windows agent as a LocalSystem service.

.DESCRIPTION
  The agent has to be running before anyone logs in, so it is installed as an
  auto-start LocalSystem service rather than a scheduled task or a startup item.
  That is also what lets it report the login screen (PRD §14) and, later, serve
  the Phase 8 credential provider.

  The firewall rule is scoped to the Private profile only. The LAN API is a
  fallback for when your own internet is down (PRD §29); it has no business
  being reachable from a coffee shop network.

.EXAMPLE
  .\install.ps1 -PcId PC-001 -SupabaseUrl https://abc.supabase.co `
                -AnonKey eyJ... -DeviceSecret 0123abcd...

.EXAMPLE
  .\install.ps1 -Uninstall
#>
[CmdletBinding(DefaultParameterSetName = 'Install')]
param(
  [Parameter(ParameterSetName = 'Install')][string]$PcId = 'PC-001',
  [Parameter(ParameterSetName = 'Install')][string]$DeviceId,
  [Parameter(ParameterSetName = 'Install')][string]$SupabaseUrl,
  [Parameter(ParameterSetName = 'Install')][string]$AnonKey,
  [Parameter(ParameterSetName = 'Install')][string]$DeviceSecret,
  [Parameter(ParameterSetName = 'Install')][int]$LocalApiPort = 8443,
  [Parameter(ParameterSetName = 'Install')][switch]$SkipBuild,
  [Parameter(ParameterSetName = 'Uninstall')][switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

$ServiceName = 'WolfPCAgent'
$DisplayName = 'WolfPC Remote Agent'
$InstallDir  = Join-Path $env:ProgramFiles 'WolfPC'
$ExePath     = Join-Path $InstallDir 'WolfAgent.exe'
$ProjectDir  = Join-Path (Split-Path -Parent $PSScriptRoot) 'src\WolfAgent'
$FirewallRule = 'WolfPC Agent LAN API'

function Assert-Admin {
  $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($identity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this from an elevated PowerShell prompt.'
  }
}

function Remove-Agent {
  if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Write-Host "stopping $ServiceName"
    Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    # sc.exe rather than Remove-Service so this works the same on older hosts.
    & sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 2
  }

  Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue

  if (Test-Path $InstallDir) {
    Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
  }

  Write-Host ''
  Write-Host 'Agent removed.' -ForegroundColor Green
  Write-Host "Configuration and the sealed device secret are left in $env:ProgramData\WolfPC."
  Write-Host 'Delete that folder too if you are decommissioning this PC.'
}

Assert-Admin

if ($Uninstall) { Remove-Agent; return }

if (-not $DeviceId) { $DeviceId = $PcId }

# ------------------------------------------------------- stop, then build ----
#
# The service has to stop before the publish, not after: we publish straight
# into the install directory, and a running WolfAgent.exe is a locked file that
# fails the build outright. It is only stopped here, not deleted, so a failed
# build can put it straight back.

$wasRunning = $false
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
  $wasRunning = $existing.Status -eq 'Running'
  Write-Host 'stopping the running service so its binary can be replaced'
  Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue

  # Stop-Service returns before the process has actually exited, and the file
  # stays locked until it has. Wait for the handle to go rather than guessing
  # at a sleep duration.
  $deadline = (Get-Date).AddSeconds(20)
  while ((Get-Date) -lt $deadline) {
    if (-not (Test-Path $ExePath)) { break }
    try {
      $stream = [System.IO.File]::Open($ExePath, 'Open', 'ReadWrite', 'None')
      $stream.Close()
      break
    } catch {
      Start-Sleep -Milliseconds 400
    }
  }
}

if (-not $SkipBuild) {
  Write-Host 'publishing WolfAgent (self-contained, single file)...'
  & dotnet publish $ProjectDir -c Release -o $InstallDir --nologo
  if ($LASTEXITCODE -ne 0) {
    if ($wasRunning) {
      Write-Warning 'build failed; restarting the previous version so the PC stays controllable'
      Start-Service -Name $ServiceName -ErrorAction SilentlyContinue
    }
    throw 'dotnet publish failed'
  }
} elseif (-not (Test-Path $ExePath)) {
  throw "-SkipBuild was given but $ExePath does not exist"
}

# ------------------------------------------------------------- eventlog ----
# Created here, elevated, so the service itself never needs the privilege.

if (-not [System.Diagnostics.EventLog]::SourceExists($ServiceName)) {
  New-EventLog -LogName Application -Source $ServiceName
  Write-Host "created event log source $ServiceName"
}

# -------------------------------------------------------------- service ----

if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
  # Already stopped above, before the build. Removing the registration here so
  # the settings below (failure actions, account, display name) are re-applied
  # rather than silently kept from a previous install.
  & sc.exe delete $ServiceName | Out-Null
  Start-Sleep -Seconds 2
}

Write-Host "creating service $ServiceName"
& sc.exe create $ServiceName binPath= "`"$ExePath`"" start= auto obj= LocalSystem DisplayName= "$DisplayName" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "sc create failed with $LASTEXITCODE" }

& sc.exe description $ServiceName 'Remote power control and status reporting for WolfPC.' | Out-Null

# Restart on failure: 5 s, 15 s, then every minute. A crashed agent means no
# remote shutdown and no status, and nobody is at the keyboard to notice.
& sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null

# ---------------------------------------------------------- configuration ----

if ($SupabaseUrl -and $AnonKey -and $DeviceSecret) {
  Write-Host 'writing configuration'
  & $ExePath configure --pc $PcId --device $DeviceId --url $SupabaseUrl `
                       --anon $AnonKey --secret $DeviceSecret --port $LocalApiPort
  if ($LASTEXITCODE -ne 0) { throw 'configuration failed' }
} else {
  # Absent arguments are not the same as absent configuration. Re-running this
  # script bare is the normal way to deploy a new build, and warning that the
  # credentials are missing — when they are sealed on disk and working — sends
  # people off to re-enter a device secret for no reason.
  & $ExePath status *> $null
  if ($LASTEXITCODE -eq 0) {
    Write-Host 'keeping the existing configuration'
  } else {
    Write-Warning 'not configured yet; run "WolfAgent.exe configure" before starting the service'
  }
}

# ------------------------------------------------------------- firewall ----

Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue |
  Remove-NetFirewallRule -ErrorAction SilentlyContinue

New-NetFirewallRule -DisplayName $FirewallRule `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort $LocalApiPort `
  -Profile Private -Program $ExePath | Out-Null
Write-Host "opened TCP $LocalApiPort on the Private profile only"

# A Private-scoped rule does nothing on a network Windows has classified as
# Public, and the failure is silent: the rule exists, is enabled, and never
# applies. Worth saying out loud, because the symptom lands much later as "the
# phone cannot reach the PC on the LAN" with a firewall rule sitting there
# looking correct.
$public = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
  Where-Object { $_.NetworkCategory -ne 'Private' -and $_.NetworkCategory -ne 'DomainAuthenticated' }

if ($public) {
  Write-Host ''
  foreach ($profile in $public) {
    Write-Warning ("network '{0}' on {1} is classified {2}" -f `
      $profile.Name, $profile.InterfaceAlias, $profile.NetworkCategory)
  }
  Write-Host @"
  The firewall rule above applies to Private networks only, so the LAN fallback
  will not work while your home network is classified Public. Cloud control is
  unaffected.

  To fix it, on this PC:
    Settings > Network & internet > Ethernet (or Wi-Fi)
    set 'Network profile type' to Private

  Private is the correct setting for a network you own. Widening the firewall
  rule instead would open this port on every coffee-shop network you join.
"@
}

# ---------------------------------------------------------------- start ----

Start-Service -Name $ServiceName
Start-Sleep -Seconds 2
$service = Get-Service -Name $ServiceName

Write-Host ''
Write-Host "$DisplayName is $($service.Status)." -ForegroundColor Green
Write-Host ''
Write-Host 'Next:'
Write-Host "  1. `"$ExePath`" status    - check it is provisioned, note the certificate fingerprint"
Write-Host "  2. `"$ExePath`" pair      - get a pairing code for the Android app"
Write-Host ''
