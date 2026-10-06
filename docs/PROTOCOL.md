# Wire protocol

Everything on the wire is defined here. Firmware, agent, app and Edge Functions all implement
this document; if they disagree, this document wins.

## 1. Roles

| Role         | Identity                          | Authenticates with                          |
| ------------ | --------------------------------- | ------------------------------------------- |
| `user`       | Supabase Auth user                | Supabase JWT (email + password)             |
| `phone`      | Android install, EC P-256 keypair | JWT **plus** an ECDSA signature per command |
| `controller` | ESP32, `wolfpc-001`               | device HMAC-SHA256 (section 3)              |
| `agent`      | Windows service, `PC-001`         | device HMAC-SHA256 (section 3)              |

The phone's private key lives in the Android Keystore, is generated with
`setUserAuthenticationRequired(true)` and is unusable without a biometric prompt. The server and
the ESP32 hold only the public key. (PRD §12)

## 2. Command envelope

The canonical envelope, exactly as PRD §13:

```json
{
  "command_id": "9f1c0e4a-1b2d-4f3a-8c5e-7a6b5c4d3e2f",
  "device_id":  "PC-001",
  "command":    "POWER_ON",
  "timestamp":  "2026-09-22T10:31:04Z",
  "expires_at": "2026-09-22T10:31:34Z",
  "nonce":      "3f7a1c9e5b2d8046",
  "signature":  "base64url of an ECDSA-P256-SHA256 signature over the signing string"
}
```

### Signing string

Signing is over a byte string, never over re-serialised JSON, so that key order and whitespace
can never change a signature. Seven lines joined by single newlines, no trailing newline:

```
WOLFPC1
<command_id>
<device_id>
<command>
<timestamp>
<expires_at>
<nonce>
```

`timestamp` and `expires_at` are RFC 3339 UTC with a trailing `Z` and no fractional seconds.
`nonce` is 16 lowercase hex characters (8 random bytes). The signature is ECDSA P-256 over
SHA-256, encoded as **base64url without padding**, in IEEE P1363 form (r followed by s, 64 bytes
total) rather than DER. Android's `Signature` class emits DER, so the app converts before
sending; the Edge Functions and the ESP32 both expect P1363.

### Lifetimes

| Command      | expires_at minus timestamp | Target       |
| ------------ | -------------------------- | ------------ |
| `POWER_ON`   | 30 s                       | `controller` |
| `HARD_RESET` | 15 s                       | `controller` |
| `SHUTDOWN`   | 60 s                       | `agent`      |
| `RESTART`    | 60 s                       | `agent`      |
| `LOGIN`      | 60 s                       | `agent`      |

`HARD_RESET` is deliberately the shortest: a captured envelope is useless 15 s later. (PRD §13)

## 3. Device authentication (ESP32 and Windows Agent)

Each device holds a `device_id` and a 32-byte `device_secret`, stored in ESP32 NVS or, on
Windows, DPAPI-sealed to the machine. The secret is never transmitted. Every request carries:

```
x-wolf-device:    wolfpc-001
x-wolf-timestamp: 1758537064
x-wolf-nonce:     6b1f9c2e40a7d3815ec6b0947f2a1d38
x-wolf-signature: hex of HMAC-SHA256(secret, msg)
```

where `msg` is six lines joined by single newlines, no trailing newline:

```
<device_id>
<unix_timestamp>
<nonce>
<HTTP METHOD>
/functions/v1/<name>
<lowercase hex sha256 of the raw request body>
```

The server rejects a request when the timestamp is more than **120 s** from server time, or when
`(device_id, nonce)` already exists in `device_nonces`. Nonces older than the window are swept.

The secret is stored server-side **encrypted** (AES-256-GCM, key `WOLF_DEVICE_KEY` held only in
Edge Function secrets, never in the database), satisfying PRD §10's "no plaintext device secret
in the database". A dump of Postgres alone does not yield a working device credential.

## 4. Edge Function endpoints

All are `POST`, all under `/functions/v1/`.

| Endpoint          | Caller      | Purpose                                                        |
| ----------------- | ----------- | -------------------------------------------------------------- |
| `enqueue-command` | phone (JWT) | validate and verify the signature, insert a `QUEUED` command    |
| `device-poll`     | device HMAC | heartbeat plus claim pending commands, marking them `SENT`      |
| `device-ack`      | device HMAC | move a command to `ACKNOWLEDGED`, `EXECUTED` or `FAILED`        |
| `agent-status`    | agent HMAC  | report Windows and session state into `pc_status`               |
| `pair-device`     | phone (JWT) | redeem a pairing code, register the phone's public key          |
| `login-challenge` | agent HMAC  | create a one-time login challenge for the credential provider   |
| `login-respond`   | phone (JWT) | answer a challenge with a biometric-gated signature             |

### device-poll

Request:

```json
{ "firmware_version": "1.0.0", "uptime_s": 8412, "rssi": -54, "relay_state": "idle" }
```

Response:

```json
{
  "server_time": "2026-09-22T10:31:04Z",
  "poll_interval_ms": 2000,
  "commands": [ { "command_id": "...", "public_key": "base64url SPKI" } ]
}
```

At most 4 commands are returned per poll. `poll_interval_ms` lets the server slow a device down
without a firmware change. `public_key` is the signing phone's key, so the controller verifies
end-to-end and does not have to take the Edge Function's word for it.

### device-ack

```json
{ "command_id": "...", "status": "EXECUTED", "error": null, "executed_at": "..." }
```

## 5. Local API (ESP32, LAN, no internet)

`http://wolfpc.local` — mDNS, port 80, LAN only. PRD §8.

| Route         | Method | Body                              |
| ------------- | ------ | --------------------------------- |
| `/api/status` | GET    | none                              |
| `/api/power`  | POST   | a full signed envelope, section 2 |
| `/api/reset`  | POST   | a full signed envelope, section 2 |

PRD §18 asks for cloud authentication even on the LAN, and PRD §29 asks for LAN control to keep
working when the cloud is unreachable. Those two can only both hold if the LAN check is
*cryptographic rather than online*: the ESP32 pins the paired phone's public key at pairing time
and verifies locally the same ECDSA envelope the cloud would have verified. An attacker who
discovers `wolfpc.local` holds no signing key and so cannot power-cycle the PC, which is the
property §18 is really asking for.

Local replay defence: the ESP32 keeps the last 16 `(command_id, nonce)` pairs in RAM and rejects
repeats, and rejects any envelope whose `expires_at` has passed according to its SNTP-synced
clock. If SNTP has never succeeded the local API still enforces the nonce cache and the envelope
size limit, accepts a wider skew, and reports `"clock": "unsynced"` from `/api/status` so the app
can warn.

## 5a. Pairing payload (the QR)

`WolfAgent.exe pair` renders a QR carrying a `wolfpc://pair` URI. PRD §17.

```
wolfpc://pair?v=1
  &url=https%3A%2F%2Fabc.supabase.co   project URL, must be https
  &key=<anon key>                      public; identifies the project only
  &pc=PC-001                           the pc id the envelope addresses
  &code=WOLF-482917                    single use, ten minutes
  &ctrl=wolfpc.local                   controller on the LAN
  &host=192.168.1.20:8443              agent on the LAN
  &fp=<64 lowercase hex>               SHA-256 of the agent's TLS certificate
```

Only `v` and the scheme are required; the app applies whatever else is present
and leaves the rest to be typed. A payload whose `url` is not `https://`, whose
`fp` is not 64 hex characters, or whose `code` is not `WOLF-` plus six digits is
rejected whole rather than applied in part — the project URL decides where every
token the app holds gets sent, so a partial apply is worse than none.

Nothing in it is a secret worth protecting beyond the code's ten minute life.
The anon key is public, the fingerprint is a hash of a certificate the agent
presents to anyone who connects, and the code still has to be redeemed by an
account that already owns that pc.

At roughly 430 characters this encodes as a version 16 QR at error correction
level M — comfortably scannable from a monitor.

## 6. Command state machine

```
QUEUED --device-poll--> SENT --device-ack--> ACKNOWLEDGED --device-ack--> EXECUTED
   |                     |                        |
   |                     +------------------------+--> FAILED
   |
   +-- expires_at passed, swept --> EXPIRED
```

A command may only move forward. `EXECUTED`, `FAILED` and `EXPIRED` are terminal; a second
`device-ack` against a terminal command is accepted idempotently and changes nothing, which is
what lets a device safely retry an ack it is not sure landed.

## 7. Status model (PRD §28)

Controller state and Windows state are independent signals and are never inferred from one
another.

```
controller_online    := controller device last_seen > now() - 15s
windows_agent_online := agent      device last_seen > now() - 30s

windows_state in OFF | BOOTING | LOGIN_SCREEN | LOGGED_IN | SHUTTING_DOWN | RESTARTING | UNKNOWN
session_state in NONE | LOCKED | ACTIVE
```

The agent owns `windows_state` while it is online. When the agent goes offline the state becomes
`UNKNOWN`, not `OFF` — the MVP has no power-LED sense wire, so nothing in the system can actually
observe that the PC is off, and the app says "Windows: unknown, agent not reachable" instead of
pretending. `OFF` is only asserted when the agent reported a completed shutdown before going
quiet.

## 8. Realtime (PRD §21)

The app subscribes to Postgres changes on `commands` and `pc_status`, filtered to its own
`device_id`, over an authenticated connection with RLS enforced. There is no public channel and
no broadcast of command contents.

## 9. Errors

Every endpoint returns `{"error": {"code": "...", "message": "..."}}` with a real HTTP status.

Codes: `bad_request`, `unauthenticated`, `forbidden`, `not_found`, `replay`, `expired`,
`bad_signature`, `rate_limited`, `conflict`, `internal`.

Messages are safe to log: they never contain key material, nonces, tokens or password data.
