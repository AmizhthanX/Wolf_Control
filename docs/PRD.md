# PRD — WolfPC Remote Power & Secure Login System (working copy)

This is the repository's working copy of the product requirements. Requirement numbering
follows the original PRD sections so that code comments can cite `PRD §7`, `PRD §26`, etc.

- **Target PC:** Windows 11 · Gigabyte B450 AORUS M · Corsair CV450 · Ethernet
- **Controller:** ESP32 dev board + 5 V 2-channel relay, permanently installed inside the case
- **Phone:** Android, biometric authentication
- **Cloud:** Supabase (Auth, Postgres, RLS, Edge Functions, Realtime)

## §2 Goals

From anywhere: power on, graceful shutdown, graceful restart, hard reset, status, notifications,
reach the Windows login screen, authenticate with phone biometrics, fall back to the Windows password.
On the home LAN with no internet: power on, hard reset, local control, physical buttons.
With no ESP32 at all: both physical buttons still work.

## §3 The four operations are distinct

| Operation  | Route                                   | Notes                                    |
| ---------- | --------------------------------------- | ---------------------------------------- |
| POWER ON   | ESP32 → relay CH1 → `PWR_SW`            | works with Windows fully off             |
| SHUTDOWN   | Windows Agent → graceful OS shutdown    | the ESP32 must **not** force this        |
| RESTART    | Windows Agent → graceful OS restart     | software restart, not a physical reset   |
| HARD RESET | ESP32 → relay CH2 → `RESET_SW`          | equals pressing the case Reset button    |

Hard reset requires strong confirmation: unsaved data can be lost.

## §5–6 Hardware and standby power

Relay contacts are used as **dry contacts** in parallel with the existing case buttons. The ESP32
must never inject 5 V, GND or GPIO into the motherboard `PW` / `RES` pins.

The ESP32 is powered from the PSU **+5 V standby rail (5VSB, 3 A / 15 W on the CV450)** through a
fused breakout, so it stays online while the PC is in S5. Do not open the PSU. Keep ErP disabled
during development.

## §7 ESP32 responsibilities

- `GPIO 18` → relay CH1 → POWER, pulse ≈ 1000 ms
- `GPIO 19` → relay CH2 → RESET, pulse ≈ 300–500 ms
- both relays OFF at boot, before Wi-Fi comes up
- never leave a relay latched; always release after the configured pulse
- reject duplicate commands, reject expired commands
- hardware watchdog; automatic recovery after Wi-Fi loss
- Wi-Fi and device credentials stored securely (NVS)
- no unauthenticated `/power` endpoint, ever

## §8 Offline mode

Internet down + phone on the home Wi-Fi ⇒ control still works through the ESP32's local
authenticated API (`POST /api/power`, `POST /api/reset`, `GET /api/status`, mDNS `wolfpc.local`).
The app prefers local, then cloud.

## §9 Worldwide mode

The ESP32 makes **outbound** connections only. No public IP, no port forwarding, no inbound
exposure of the ESP32's web server. Works behind CGNAT.

## §10 Supabase stores / never stores

Stores: user, device registration, device public keys, command history, device and PC status,
login challenges, login events, notification preferences, audit events.
Never stores: the Windows password in plaintext, the Android private key, the ESP32 device secret
in plaintext, phone fingerprint data.

## §13 Commands

`POWER_ON`, `SHUTDOWN`, `RESTART`, `HARD_RESET`, `LOGIN`. Every command carries
`command_id`, `device_id`, `command`, `timestamp`, `expires_at`, `nonce`, `signature`.

## §20 Schema

`users`, `devices`, `device_keys`, `commands`, `pc_status`, `login_challenges`, `audit_logs`.
Command states: `QUEUED`, `SENT`, `ACKNOWLEDGED`, `EXECUTED`, `FAILED`, `EXPIRED`.

## §22 ESP32 ↔ cloud

v1: ESP32 → HTTPS → Supabase Edge Function → Postgres, polling for commands (~2 s) while the PC
is off; acknowledge on execution. May be upgraded to a persistent realtime protocol later.

## §26 Physical buttons must always work

ESP32 dead, Wi-Fi dead, internet dead, Supabase down or app unavailable — the case Power and
Reset buttons still work. This is a hard requirement of the wiring, not of the software.

## §27 Confirmation rules

| Action        | Auth                  | Confirmation          |
| ------------- | --------------------- | --------------------- |
| Power ON      | biometric             | no                    |
| Shutdown      | biometric             | yes                   |
| Restart       | biometric             | yes                   |
| Hard reset    | biometric             | strong confirmation   |
| Windows login | biometric + challenge | yes                   |
| Pair device   | biometric + code      | yes                   |

## §28 Two independent state signals

Controller state (`ONLINE` / `OFFLINE`) and Windows state
(`OFF`, `BOOTING`, `LOGIN_SCREEN`, `LOGGED_IN`, `SHUTTING_DOWN`, `RESTARTING`, `UNKNOWN`)
are separate. The ESP32 cannot infer that Windows is running.

## §31 MVP

Everything above except the phone-to-Windows passwordless login, which is Phase 8 and is
validated in a VM before it ever touches the main PC. The Windows password tile stays enabled
as the permanent recovery path.
