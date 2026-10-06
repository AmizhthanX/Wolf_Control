# WolfPC Remote — Power & Secure Login System

Remote power control and phone-based login for a single Windows 11 PC, built from four
cooperating components plus an always-available physical fallback.

```
Android app  ──┐
               ├── Supabase (auth · postgres · RLS · edge functions · realtime)
Windows agent ─┤
ESP32 + relays ┘
```

| Path                   | What it is                                                              |
| ---------------------- | ----------------------------------------------------------------------- |
| `docs/`                | PRD, architecture, wire protocol, hardware runbook, security model       |
| `supabase/`            | SQL migrations (schema + RLS) and Deno Edge Functions — the control plane |
| `firmware/`            | ESP32 firmware (PlatformIO / Arduino): relays, local API, cloud polling   |
| `agent/`               | Windows Agent — .NET 9 LocalSystem service + session helper               |
| `android/`             | Android app — Kotlin + Jetpack Compose, BiometricPrompt + Keystore        |
| `credential-provider/` | Phase 8 Windows credential provider (C++ COM)                            |
| `tools/simulator/`     | Fake ESP32 + fake Windows agent, so the app can be developed with no hardware |

## The four operations

| Operation      | Path                                            | Works when Windows is off |
| -------------- | ----------------------------------------------- | ------------------------- |
| **POWER ON**   | app → cloud/LAN → ESP32 → relay CH1 → `PWR_SW`  | yes                       |
| **SHUTDOWN**   | app → cloud/LAN → Windows Agent → graceful      | no                        |
| **RESTART**    | app → cloud/LAN → Windows Agent → graceful      | no                        |
| **HARD RESET** | app → cloud/LAN → ESP32 → relay CH2 → `RESET_SW`| yes                       |

The physical Power and Reset buttons stay wired in parallel and keep working even if the
ESP32, Wi-Fi, internet, Supabase and phone are all dead. See `docs/HARDWARE.md`.

## Start here

1. `docs/ARCHITECTURE.md` — how the pieces fit and which component owns which decision
2. `docs/PROTOCOL.md` — command envelope, signing, replay defence, state machines
3. `docs/HARDWARE.md` — phases 1–3: bench test, motherboard wiring, 5VSB standby power
4. `docs/DEPLOY.md` — deploy Supabase, flash the ESP32, install the agent, build the app
5. `docs/SECURITY.md` — threat model and what each control actually buys you

## Build status

Every component builds independently; nothing here requires the others to be present to
compile. See each component's own README for its toolchain.
