# WolfPC Windows agent

.NET 9, runs as an auto-start LocalSystem service so it is up before anyone logs
in — which is what lets it report the login screen and, in Phase 8, serve the
credential provider.

## Install

From an elevated PowerShell prompt:

```powershell
cd install
.\install.ps1 -PcId PC-001 -SupabaseUrl https://xxx.supabase.co `
              -AnonKey <anon> -DeviceSecret <64 hex>
```

Uninstall with `.\install.ps1 -Uninstall`.

## Commands

```powershell
WolfAgent.exe status      # configuration and the LAN certificate fingerprint
WolfAgent.exe pair        # a pairing QR (and code) for the app
WolfAgent.exe configure   # change configuration without reinstalling
```

## What it does

| Piece               | Responsibility                                            |
| ------------------- | --------------------------------------------------------- |
| `CommandWorker`     | polls for SHUTDOWN / RESTART / LOGIN, verifies, executes   |
| `StatusWorker`      | the only writer of `windows_state`                         |
| `SessionMonitor`    | login screen vs logged in vs locked, via the WTS API       |
| `PowerController`   | graceful shutdown and restart through `InitiateShutdown`   |
| `LocalApiRoutes`    | the LAN fallback on 8443, TLS with a pinned self-signed cert |
| `LoginBroker`       | named pipe to the Phase 8 credential provider              |
| `EnvelopeVerifier`  | verifies the phone's signature locally, not on trust       |
| `PairingQr`         | the pairing QR: payload, ANSI console render, PNG fallback  |

## Where things live

| Path                                        | What                                 |
| ------------------------------------------- | ------------------------------------ |
| `C:\Program Files\WolfPC\`                  | the binary                           |
| `%ProgramData%\WolfPC\agent.json`           | configuration                        |
| `%ProgramData%\WolfPC\device.secret`        | HMAC secret, DPAPI machine scope     |
| `%ProgramData%\WolfPC\lan.pfx`              | the LAN certificate                  |
| `%ProgramData%\WolfPC\pairing.png`         | last pairing QR, if the terminal mangles it |
| Event Viewer → Application → `WolfPCAgent`  | logs                                 |

The service has no console, so the event log is where its history actually is.
