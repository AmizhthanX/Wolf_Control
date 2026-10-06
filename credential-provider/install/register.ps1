<#
.SYNOPSIS
  Install and register the WolfPC credential provider.

.DESCRIPTION
  Copies the DLL beside the agent and registers its CLSID with Windows.

  READ THIS FIRST. A credential provider is loaded by LogonUI, the process that
  draws the login screen. A bad one does not fail politely - it can leave a
  login screen with nothing usable on it, on a machine you then cannot log in
  to in order to fix it.

  Do this in a virtual machine first, with a snapshot taken beforehand. Windows
  11 Pro includes Hyper-V; the doc at docs/PHASE8-LOGIN.md explains the order.

  What keeps this recoverable on a real machine:

    - The password tile is never filtered away. This provider does not
      implement ICredentialProviderFilter at all, so every other way of signing
      in stays exactly where it was.
    - The provider is never the default tile unless an approval is already
      waiting, so a boot with the service down looks like a boot without this
      installed.
    - Every call it makes to the agent has a timeout. It cannot hang the login
      screen waiting for a service that is not answering.

  If it does go wrong: boot into Safe Mode (hold Shift, Restart, Troubleshoot,
  Advanced options, Startup Settings) where third-party credential providers
  are not loaded, and run unregister.ps1.

.EXAMPLE
  .\register.ps1
  Builds if needed, installs, registers.

.EXAMPLE
  .\register.ps1 -WhatIf
  Shows what it would do and touches nothing.
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
  [string]$DllPath,
  [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$Clsid       = '{DC970930-8A57-4F93-8494-F5F2328AAF87}'
$DisplayName = 'WolfPC Phone Unlock'
$InstallDir  = Join-Path $env:ProgramFiles 'WolfPC'
$TargetDll   = Join-Path $InstallDir 'WolfCredentialProvider.dll'
$ProjectDir  = Split-Path -Parent $PSScriptRoot

$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'Run this from an elevated PowerShell prompt.'
}

# ------------------------------------------------------------------- build ---

if (-not $DllPath) {
  $DllPath = Join-Path $ProjectDir 'build\Release\WolfCredentialProvider.dll'

  if (-not $SkipBuild) {
    $cmake = Get-Command cmake -ErrorAction SilentlyContinue
    if (-not $cmake) {
      $candidate = Join-Path $env:ProgramFiles `
        'Microsoft Visual Studio\18\Professional\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
      if (Test-Path $candidate) { $cmake = $candidate } else {
        throw 'cmake not found. Install the "Desktop development with C++" workload, or pass -SkipBuild with a prebuilt -DllPath.'
      }
    } else { $cmake = $cmake.Source }

    Write-Host 'building the credential provider...'
    & $cmake -S $ProjectDir -B (Join-Path $ProjectDir 'build') -A x64 | Out-Null
    & $cmake --build (Join-Path $ProjectDir 'build') --config Release | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
  }
}

if (-not (Test-Path $DllPath)) { throw "no DLL at $DllPath" }

# ----------------------------------------------------------------- install ---

if ($PSCmdlet.ShouldProcess($TargetDll, 'install the credential provider DLL')) {
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

  # LogonUI keeps a handle on the DLL for as long as it is running, and it is
  # always running. Replacing the file in place fails with a sharing violation
  # that reads like a permissions problem; the rename is what makes an upgrade
  # possible without a reboot.
  if (Test-Path $TargetDll) {
    $retired = "$TargetDll.old-$(Get-Date -Format yyyyMMddHHmmss)"
    try { Move-Item $TargetDll $retired -Force } catch {
      throw "could not move the existing DLL aside: $($_.Exception.Message)"
    }
  }

  Copy-Item $DllPath $TargetDll -Force
  Write-Host "installed $TargetDll"
}

# ---------------------------------------------------------------- register ---

$clsidKey    = "HKLM:\SOFTWARE\Classes\CLSID\$Clsid"
$inprocKey   = "$clsidKey\InprocServer32"
$providerKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$Clsid"

if ($PSCmdlet.ShouldProcess($Clsid, 'register the credential provider')) {
  New-Item -Path $clsidKey -Force | Out-Null
  Set-ItemProperty -Path $clsidKey -Name '(Default)' -Value $DisplayName

  New-Item -Path $inprocKey -Force | Out-Null
  Set-ItemProperty -Path $inprocKey -Name '(Default)' -Value $TargetDll

  # Apartment, not Free. LogonUI initialises its UI thread as an STA and calls
  # this provider from it; declaring Free would have COM marshal every call
  # through a thread of its own, which is both slower and a source of
  # re-entrancy bugs in a component that must never block.
  Set-ItemProperty -Path $inprocKey -Name 'ThreadingModel' -Value 'Apartment'

  New-Item -Path $providerKey -Force | Out-Null
  Set-ItemProperty -Path $providerKey -Name '(Default)' -Value $DisplayName

  Write-Host "registered $Clsid"
}

Write-Host ''
Write-Host '  Registered. Lock the screen (Win+L) to see the tile.' -ForegroundColor Green
Write-Host ''
Write-Host '  The password tile is still there and still works. If anything goes'
Write-Host '  wrong, boot Safe Mode and run unregister.ps1.'
Write-Host ''
