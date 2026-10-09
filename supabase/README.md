![WolfPC system overview](../docs/assets/wolfpc-architecture.png)

# Supabase backend

**The shared meeting point for your phone, controller, and PC.**

Supabase holds the owner account, paired phone keys, device presence, and command
queue. Edge Functions verify requests and deliver work. The devices still verify
phone signatures themselves before touching hardware or Windows.

[Back to WolfPC](../README.md) · [Deployment guide](../docs/DEPLOY.md) · [Security model](../docs/SECURITY.md)

## Deploy from the repository root

Install the Supabase CLI and sign in. Replace `YOUR_PROJECT_REF` with the reference
for your own project:

```powershell
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

If the command is unavailable on Windows, use `npx supabase` in place of
`supabase`. The CLI will prompt for credentials when it needs them.

Generate a device-wrapping key with:

```powershell
node tools/provision/provision.mjs genkey
```

Set `WOLF_DEVICE_KEY` in the project's Edge Function secrets to that value.
It must be a base64-encoded 32-byte key. Keep the same value in the ignored
provisioning `.env` file. Back it up securely: existing device secrets depend on it.

Deploy all eight functions from PowerShell:

```powershell
$functions = @(
    'enqueue-command', 'device-poll', 'device-ack', 'agent-status',
    'pair-device', 'create-pairing-code', 'login-challenge', 'login-respond'
)
foreach ($function in $functions) {
    supabase functions deploy $function
}
```

Create the owner account and provision the PC and its devices using the
[tools guide](../tools/README.md).

## What lives here

| Part | Purpose |
| --- | --- |
| `migrations/` | Schema, RLS, dispatch helpers, presence jobs, and later changes such as LOCK |
| `functions/enqueue-command/` | Validate and queue a signed phone command |
| `functions/device-poll/` | Authenticate devices, deliver work, refresh trusted keys |
| `functions/device-ack/` | Record execution outcomes |
| `functions/agent-status/` | Report Windows session and LAN facts |
| Pairing functions | Issue pairing codes and register or revoke phone keys |
| Login functions | Exchange sign-in challenges and signed approvals |
| `functions/_shared/` | Authentication, crypto, envelope rules, and agent wake hints |
| `config.toml` | Local project configuration and function JWT gates |

## Who can do what?

The signed-in phone reads its owner's data. It cannot insert a command directly:
RLS blocks that, and `enqueue-command` verifies the signature before creating
the row. Device-secret and nonce tables are inaccessible to ordinary client roles.

The controller and agent authenticate with device HMACs. Their endpoints disable
the platform JWT gate because those devices do not hold user JWTs; each handler
performs its own device verification. The phone-facing handlers verify ownership
and authorization as appropriate. See [config.toml](config.toml) and the function
code for the exact gates.

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are supplied to hosted functions
by Supabase. The service-role key belongs in trusted tooling and server code,
never in the phone app or controller firmware.

## Quick agent delivery without constant fast polling

Queuing an agent command also sends a short-lived, HMAC-authenticated Realtime
wake hint. The agent wakes its poll loop, fetches the command, and verifies the
phone signature. A wake message cannot authorize an action.

If a socket disconnects or a hint is missed, fallback polling continues. The
ESP32 still uses polling for cloud commands; the local route can avoid that wait.

## Plan your polling budget

There are three sources of recurring Edge Function calls: controller polling,
agent polling, and agent heartbeats. Count all three, plus user actions and
change-triggered updates.

For one always-running timer, a 30-day estimate is:

```text
monthly calls ≈ 2,592,000 / interval_in_seconds
```

Use `WOLF_POLL_INTERVAL_MS` to adjust the interval returned to devices, and
`agent/install/set-intervals.ps1` to configure the agent timers and estimate
their combined cost. Check your project's current allowance in Supabase rather
than relying on an old plan number. Keep heartbeat intervals compatible with
the presence thresholds so online devices do not appear offline.

## Check the command rules

With Deno installed, from the repository root:

```powershell
deno check supabase/functions/enqueue-command/index.ts
deno test supabase/functions/_shared/envelope_test.ts
```

For protocol details, read [PROTOCOL.md](../docs/PROTOCOL.md). For the installed
Refresh/Lock update and its verification limits, read
[UPDATES-1.1.md](../docs/UPDATES-1.1.md).
