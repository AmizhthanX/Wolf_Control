<#
.SYNOPSIS
  Remove the WolfPC credential provider.

.DESCRIPTION
  Deletes the registration and, where possible, the DLL. The tile disappears
  from the login screen; nothing else about signing in changes, because this
  provider never filtered anything away.

  This is the recovery script. It is written so it can be run from Safe Mode,
  where third-party credential providers are not loaded, and it does the
  registry work before it tries to touch any file - so a DLL held open by a
  running LogonUI cannot stop the thing that actually matters.

.EXAMPLE
  .\unregister.ps1
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$KeepDll)

$ErrorActionPreference = 'Stop'

$Clsid      = '{DC970930-8A57-4F93-8494-F5F2328AAF87}'
$InstallDir = Join-Path $env:ProgramFiles 'WolfPC'
$TargetDll  = Join-Path $InstallDir 'WolfCredentialProvider.dll'

$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run this from an elevated PowerShell prompt.'
}

# Registration first, always. Removing this key is what takes the tile off the
# login screen, and it cannot fail for a reason as mundane as a file being in
# use. If the script dies later, the machine is already recovered.
$providerKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$Clsid"
if (Test-Path $providerKey) {
  if ($PSCmdlet.ShouldProcess($providerKey, 'remove the credential provider registration')) {
    Remove-Item $providerKey -Recurse -Force
    Write-Host 'unregistered from the login screen'
  }
} else {
  Write-Host 'was not registered'
}

$clsidKey = "HKLM:\SOFTWARE\Classes\CLSID\$Clsid"
if (Test-Path $clsidKey) {
  if ($PSCmdlet.ShouldProcess($clsidKey, 'remove the COM registration')) {
    Remove-Item $clsidKey -Recurse -Force
    Write-Host 'COM registration removed'
  }
}

if (-not $KeepDll -and (Test-Path $TargetDll)) {
  if ($PSCmdlet.ShouldProcess($TargetDll, 'delete the DLL')) {
    try {
      Remove-Item $TargetDll -Force
      Write-Host 'DLL removed'
    } catch {
      # Expected while LogonUI still holds it. Harmless: an unregistered DLL is
      # never loaded again, and it goes on the next reboot.
      Write-Host 'DLL is still in use; it will go on the next reboot (harmless).'
    }
  }
}

Write-Host ''
Write-Host '  Done. Sign in with your password as usual.' -ForegroundColor Green
Write-Host ''
