# Supabase control plane

Schema, RLS and eight Edge Functions. See `../docs/DEPLOY.md` for the full
runbook and `../docs/PROTOCOL.md` for what goes over the wire.

```bash
supabase link --project-ref <ref>
supabase db push
supabase secrets set WOLF_DEVICE_KEY=<from: node ../tools/provision/provision.mjs genkey>
for fn in enqueue-command device-poll device-ack agent-status \
          pair-device create-pairing-code login-challenge login-respond; do
  supabase functions deploy "$fn"
done
```

## Migrations

| File                            | What it establishes                                  |
| ------------------------------- | ---------------------------------------------------- |
| `..._schema.sql`                | tables, enums, indexes, the auth.users mirror trigger |
| `..._rls.sql`                   | row level security — read the header comment first    |
| `..._functions.sql`             | claim / ack / heartbeat / expire / sweep              |
| `..._realtime_and_cron.sql`     | the realtime publication and pg_cron jobs             |

## The authorisation model in one paragraph

The phone may read its own world and write only its own preferences. It cannot
insert a command: RLS denies it, and `enqueue-command` is the sole writer,
because only a function can verify the biometric-backed ECDSA signature before a
row exists. A stolen JWT with no phone key is therefore not enough to move
hardware. `device_secrets` and `device_nonces` have RLS enabled and **no
policies at all**, so every non-service role sees an empty table.

## Function secrets

| Secret                  | Purpose                                                  |
| ----------------------- | -------------------------------------------------------- |
| `WOLF_DEVICE_KEY`       | AES-256-GCM key wrapping every device secret. 32 bytes, base64. |
| `WOLF_POLL_INTERVAL_MS` | Returned to devices on every poll; change it here, not in firmware. |

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are injected by the platform.

## verify_jwt

Off for the four device endpoints — the ESP32 and the agent hold no JWT and
authenticate with an HMAC their handlers verify. On for the three phone-facing
ones, so an unauthenticated request never reaches our code. See `config.toml`.

## A note on quota

`device-poll` at the PRD's 2 second interval is roughly 1.3 M invocations a
month against a 500 K free-tier ceiling. `DEPLOY.md` has the three ways to deal
with that; the shortest is `WOLF_POLL_INTERVAL_MS=8000`, which needs no code
change anywhere.
