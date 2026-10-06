# Architecture

Four components, three independent control paths, one hard rule: the case
buttons always work.

```
                                 INTERNET
                                     │
                            ┌────────▼────────┐
                            │    SUPABASE     │
                            │  auth · postgres│
                            │  RLS · functions│
                            │  realtime       │
                            └────┬───────┬────┘
                                 │       │
                  ┌──────────────┘       └───────────────┐
                  │                                      │
            ANDROID APP                            WINDOWS AGENT
        BiometricPrompt                          LocalSystem service
        Keystore P-256                           session + power
                  │                                      │
                  └──────────────┐       ┌───────────────┘
                                 │       │
                             HOME LAN    │
                                 │       │
                          ┌──────▼───────▼──┐
                          │      ESP32      │
                          │ relays · local  │
                          │ API · watchdog  │
                          └────────┬────────┘
                                   │
                        ┌──────────┴──────────┐
                    relay CH1             relay CH2
                     PWR_SW                RESET_SW
                        └──────────┬──────────┘
                              B450 AORUS M
                        ┌──────────┴──────────┐
                  case power button    case reset button
                        └─── always work ─────┘
```

---

## Who owns what

| Decision                                | Owner          | Why it cannot live elsewhere                          |
| --------------------------------------- | -------------- | ----------------------------------------------------- |
| "the user really asked for this"        | Android app    | only the phone has the biometric and the private key  |
| "this command is authentic and fresh"   | device, locally| a compromised cloud must not be able to forge one     |
| "who is allowed to see what"            | Postgres RLS   | one place, enforced for REST and Realtime alike       |
| "is Windows running"                    | Windows agent  | the ESP32 genuinely cannot know (PRD §28)             |
| "did a relay actually fire"             | ESP32          | it is the only thing holding the contact              |
| "the machine can always be switched on" | the wiring     | no software involved, by design (PRD §26)             |

---

## Why the cloud is a relay, not an authority

Supabase queues commands, holds history and pushes state. It does not decide
whether a command is legitimate — both the ESP32 and the Windows agent verify
the phone's ECDSA signature themselves, against keys pinned at pairing.

It costs a little: `device-poll` has to ship the signature and the signing key
with every command, the ESP32 spends ~30 ms per verification, and the controller
carries a key store in NVS. What it buys is that a total compromise of the
control plane still cannot press the power button.

## Why the ESP32 polls instead of listening

PRD §9 rules out a public IP, port forwarding and any inbound path to the
controller, and the design has to survive CGNAT. That leaves outbound only. v1
polls an Edge Function every 2 s (PRD §22), which is simple, survives NAT, and
recovers from a dropped connection without any reconnection logic.

The cost is quota — see the note in `DEPLOY.md`. `poll_interval_ms` is returned
by the server on every poll so the interval can be changed without touching
firmware, and `cloud.cpp` is the only file that would change if the channel
moved to Realtime or MQTT.

## Why two devices for one PC

`wolfpc-001` (controller) and `PC-001` (agent) are separate identities with
separate secrets, grouped by `pc_id`. The envelope is addressed to the *pc*, and
`TARGET` in `envelope.ts` routes each command to whichever side can actually
perform it:

- `POWER_ON`, `HARD_RESET` → controller, because Windows is off
- `SHUTDOWN`, `RESTART`, `LOGIN` → agent, because these must be graceful

That split is PRD §3, and it is the reason a shutdown never becomes a power cut.

## Why the LAN path verifies cryptographically

PRD §18 wants cloud authentication even on the LAN; PRD §29 wants the LAN to
work when the cloud is unreachable. Both hold only if the local check is
cryptographic rather than online: the controller pins the paired phone's public
key and verifies the same envelope locally that the cloud would have verified.

An attacker who finds `wolfpc.local` holds no signing key, which is the property
§18 is really asking for.

## State, honestly

`windows_state` is owned by the agent and nothing else writes it. When the agent
goes quiet the state becomes `UNKNOWN`, never `OFF` — nothing in the MVP can
observe that a PC lost power, and a confident "OFF" that really means "I cannot
tell" is how someone hard-resets a machine that was merely offline.

A power-LED sense wire into a spare GPIO would let the controller report actual
power state. It is the most valuable single upgrade to this design and is not in
the MVP.

---

## Repository map

```
docs/                    PRD, this file, protocol, hardware, deploy, security
supabase/
  migrations/            schema · RLS · dispatch functions · realtime + cron
  functions/
    _shared/             crypto, http, db, device auth, user auth, envelope
    enqueue-command/     phone → QUEUED command (the only writer)
    device-poll/         heartbeat + claim + trusted key refresh
    device-ack/          forward-only status transitions
    agent-status/        windows_state, agent only
    pair-device/         redeem a code, register a phone key
    create-pairing-code/ agent asks for a code to display
    login-challenge/     Phase 8, agent side
    login-respond/       Phase 8, phone side
firmware/
  include/config.h       pins, pulse widths, timeouts — read the comments
  src/relay.*            the only code allowed to move a relay
  src/envelope.*         parse, verify, replay cache
  src/cloud.*            outbound polling and acks
  src/localapi.*         the LAN fallback
  src/store.*            NVS: wifi, identity, pinned keys
agent/
  src/WolfAgent/         .NET 9 service: workers, LAN API, session, power
    Pairing/PairingQr.cs the pairing QR payload and its console rendering
  install/install.ps1    publish · service · firewall · configure
android/
  app/src/main/java/...  Compose UI, Keystore signing, LAN-then-cloud sending
    ui/scan/             CameraX + ML Kit pairing QR scanner
credential-provider/     Phase 8 (design only in the MVP)
tools/provision/         create the pc, devices and secrets
tools/simulator/         fake controller + fake agent, for app work with no hardware
```
