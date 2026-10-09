![WolfPC Remote](../docs/assets/wolfpc-hero.png)

# Windows agent

**The bridge between your phone and Windows.**

The agent starts as a LocalSystem service before anyone signs in. It reports
whether Windows is at the sign-in screen, locked, or logged in; handles shutdown,
restart, and lock requests; and verifies phone approvals for the Wolf sign-in tile.

[Back to WolfPC](../README.md) · [Deployment guide](../docs/DEPLOY.md) · [Phone unlock](../docs/PHASE8-LOGIN.md)

## Install on your PC

You'll need Windows 11 and the .NET 9 SDK to build. The installer publishes a
self-contained executable, so the installed service does not need a separate
.NET runtime.

From the repository root, open **PowerShell as Administrator**. Replace the
example values with your own provisioning details:

```powershell
cd agent/install
.\install.ps1 -PcId "PC-001" `
    -SupabaseUrl "https://your-project.supabase.co" `
    -AnonKey "YOUR_ANON_KEY" `
    -DeviceSecret "YOUR_64_HEX_AGENT_SECRET"
```

The installer registers `WolfPCAgent`, protects the device secret with DPAPI,
and opens TCP 8443 on the **Private** firewall profile. Check that your home
network has the correct profile in Windows Settings.

## Check it, then pair your phone

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" status
& "C:\Program Files\WolfPC\WolfAgent.exe" pair
```

Scan the QR in the Android app. It carries the project settings, pairing code,
local addresses, and agent certificate fingerprint. If the console QR is hard to
read, open `%ProgramData%\WolfPC\pairing.png`.

The `&` matters in PowerShell: it runs a quoted executable path.

## What happens after a tap

![WolfPC command routes](../docs/assets/wolfpc-architecture.png)

The agent accepts signed commands through cloud polling or its pinned HTTPS LAN
API. It checks the phone key, expiry, and replay protection before executing.
Supabase Realtime can wake the poll loop when a Windows command is queued;
the wake hint does not authorize an action on its own.

Locking uses a helper in the interactive user's desktop session. Shutdown and
restart use a five-second countdown after delivery and can force applications
closed. **Save work before confirming them.** Windows updates, applications,
and drivers can extend the final time to power off.

## Add phone unlock

Enrollment stores a DPAPI-sealed copy of your Windows password on this PC.
It does not upload the password to Supabase. Read the
[credential provider README](../credential-provider/README.md) before installing
the sign-in tile.

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" enroll-login
& "C:\Program Files\WolfPC\WolfAgent.exe" login-test
```

Use an Administrator terminal for enrollment. Enter the **Windows account
password**, not the Windows Hello PIN. Re-enroll after changing that password.
`login-test` exercises approval and one-time credential release without using
the sign-in screen.

To remove the enrolled credential:

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" forget-login
```

## Update without replacing your settings

From the repository root, in Administrator PowerShell:

```powershell
dotnet publish agent/src/WolfAgent -c Release -o .tools/agent-update
.\agent\install\update.ps1
```

The update script replaces the installed binary, retains configuration, and
attempts to restore the previous binary if copying or startup fails. It does
not change firewall rules. To uninstall the service, use
`agent/install/install.ps1 -Uninstall`.

## Where to look when something goes wrong

| Location | What you'll find |
| --- | --- |
| `C:\Program Files\WolfPC\` | Installed executable |
| `%ProgramData%\WolfPC\agent.json` | Agent configuration |
| `%ProgramData%\WolfPC\device.secret` | DPAPI-protected device secret |
| `%ProgramData%\WolfPC\logon.cred` | Locally sealed enrolled credential |
| `%ProgramData%\WolfPC\lan.pfx` | LAN certificate |
| Event Viewer → Application → `WolfPCAgent` | Service and provider logs |

For an unreachable LAN endpoint, check the service, the PC's current address,
the Private network profile, and the certificate fingerprint in the app. For
phone unlock, check enrollment and whether the Wolf tile is connected.

## For contributors

`CommandWorker` handles actions, `StatusWorker` reports state, `SessionMonitor`
reads Windows sessions, `SessionLocker` locks the desktop, and `LoginBroker`
coordinates the credential provider. `CommandWakeup` receives authenticated
Realtime hints; `EnvelopeVerifier` remains the command authorization boundary.

Run the focused checks from the repository root:

```powershell
dotnet run --project agent/tests/WolfAgent.Checks -c Release
```

These check command signing, replay rejection, and wake validation. They do not
shut down or lock the working PC.
