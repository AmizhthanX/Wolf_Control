# Security model

What each control actually buys, and what it does not. PRD §24.

---

## The short version

Three independent things must hold before a relay moves:

1. a valid Supabase session belonging to the account that owns the PC,
2. an ECDSA signature made by a key that only exists inside a specific phone's
   secure hardware, released only by a fresh biometric,
3. the envelope being fresh, unexpired and never seen before.

No single compromise defeats all three. That is the whole design.

---

## Threats, and what stops them

### Someone finds the ESP32 on your LAN

Port-scanning the home network turns up `wolfpc.local` on port 80. They can read
`/api/status` — device id, firmware version, uptime, whether it is talking to the
cloud. That is intentional: it is the same information the app needs to show you.

`/api/power` and `/api/reset` accept nothing but a full ECDSA-signed envelope
verified against a key pinned at pairing. There is no LAN-trust shortcut and no
"local" mode with weaker rules, which is what PRD §18 asks for.

*Not covered:* someone on your LAN can see that a POWER_ON happened, because the
controller speaks plain HTTP (see the note in `LanClient.kt` for why). They learn
the timing of your commands and nothing else.

### A replayed command

Three overlapping defences: a unique index on `(issuer_device_id, nonce)` so a
second insert of the same envelope fails outright; `expires_at` of 15–60 s
depending on the command; and a 16-entry ring of recent command ids in the
controller's RAM plus a bounded set in the agent, so even a device that never
reaches the cloud refuses a repeat.

Hard reset gets the shortest life — 15 s — because it is the one command that
destroys work.

### A stolen phone

The signing key requires a **fresh biometric for every single signature**
(`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`). An unlocked,
stolen phone still cannot sign without a matching finger.

Enrolling a new fingerprint destroys the key
(`setInvalidatedByBiometricEnrollment(true)`), so a thief who knows the PIN
cannot add their own finger and inherit the capability. The app detects this and
asks you to pair again.

Revoke the phone from any other device: set `device_keys.revoked_at`. The
controller drops the pinned key on its next poll, so the stolen phone loses the
LAN path too.

### A compromised Supabase session

An attacker with your JWT can *read* your status and history. They cannot queue
a command: RLS denies inserts on `commands`, and `enqueue-command` verifies the
envelope signature before writing a row. Without the phone's private key, which
never left the phone, there is no signature to produce.

### A compromised control plane

If Supabase itself were compromised, an attacker could see your command history
and inject rows into `commands` directly. They still could not make hardware
move: both the controller and the agent verify the phone's signature themselves
against keys they pinned at pairing time, and treat the `public_key` attached to
a delivered envelope as a hint rather than an authority.

This is why `device-poll` ships the signature and the key with every command
instead of just saying "do this".

### A database dump

Device secrets are stored AES-256-GCM encrypted under `WOLF_DEVICE_KEY`, which
lives only in Edge Function secrets. A dump of Postgres yields ciphertext, and
no working device credential. (PRD §10)

The dump does contain your command history and your PC's state timeline. It
contains no passwords, no private keys, and nothing biometric.

### A relay that sticks or a firmware that wedges

`RelayBank` clamps every pulse to `RELAY_MAX_PULSE_MS`, releases from `loop()`
before anything that can block, and refuses a second pulse inside a 4 s
cooldown. A 30 s task watchdog resets the chip if the loop stops running, and
`relays.begin()` — the first statement in `setup()` — parks both channels.

### Rate and abuse

`enqueue-command` refuses more than 20 commands a minute per user and writes a
denied audit row. Device requests carry a 120 s timestamp window and a
single-use nonce, both enforced in Postgres rather than in application memory,
so a second function instance cannot be raced.

---

## What is deliberately *not* protected

**An attacker with physical access to the case.** They can press the buttons.
Nothing here is a physical security control, and PRD §26 requires those buttons
to keep working.

**A local administrator on the PC.** They can unseal the DPAPI-protected agent
secret. They could also just shut the machine down; that is not a boundary worth
defending.

**Confidentiality of LAN traffic to the controller.** Plain HTTP, by design; the
payload is a signed envelope with nothing secret in it.

The app's network security config permits cleartext, and `LanClient` narrows it
to RFC 1918 and link-local addresses and `.local` names, checked on every
request. The platform config used to carry that restriction itself, naming only
`wolfpc.local` — tighter, and unworkable once the app had to dial addresses
rather than names, because a phone with strict Private DNS resolves nothing at
all while the internet is down. A static allow-list cannot express "whatever
private address the controller reports today", so the rule moved to where it can
be evaluated. Supabase is unaffected: its URL is validated as `https://`
wherever it is accepted.

**The agent's LAN port on an untrusted network.** The firewall rule is scoped to
the Private profile, so the port closes itself when you take a laptop onto a
network you do not own. The cost is that the LAN path silently does nothing if
your home network is misclassified as Public — see `DEPLOY.md`. That trade is
deliberate: a fallback that quietly fails is better than a port that quietly
opens.

**Availability.** A determined attacker on your LAN can flood the ESP32's single
TCP socket and stop the local path working. The cloud path and the physical
buttons are unaffected — which is exactly why there are three independent paths.

---

## Where secrets live

| Secret                   | Lives in                                   | Ever transmitted? |
| ------------------------ | ------------------------------------------ | ----------------- |
| Phone signing key        | Android Keystore, non-exportable           | never             |
| Windows password         | nowhere in this system                     | never             |
| `WOLF_DEVICE_KEY`        | Edge Function secrets                      | never             |
| Controller device secret | ESP32 NVS; encrypted in Postgres           | never (HMAC only) |
| Agent device secret      | DPAPI machine scope; encrypted in Postgres | never (HMAC only) |
| Supabase service role    | your shell and CI only                     | never to a client |
| Supabase anon key        | app, firmware, agent                       | freely — it is public |

The anon key being in the firmware is fine. It identifies the project; the
device HMAC is what authenticates.

---

## Audit

Every enqueue, execution, pairing attempt and login response writes to
`audit_logs` with the result and the caller's IP. Denials are logged as
carefully as successes — a run of `denied / wrong_owner` pairing attempts is the
signal that someone is guessing codes.

Messages are written to be safe to read: PROTOCOL §9 forbids key material,
nonces and password data in any error string that leaves a function.
