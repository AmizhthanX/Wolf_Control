# Deployment runbook

Order matters: Supabase first, because everything else needs credentials that
only exist once the control plane does.

Prerequisites: Node 18+, the Supabase CLI, PlatformIO, .NET 9 SDK, Android
Studio (or a JDK 17+ and the Android SDK).

---

## 1. Supabase

### 1.1 Create the project and the single user

Create a project at supabase.com. Then, because sign-up is disabled by design
(`config.toml`), create your account by hand:

**Authentication → Users → Add user**, with a real email and a strong password.
This is the only account that will ever exist.

Note down, from **Settings → API**:

- the project URL, `https://<ref>.supabase.co`
- the **anon** key (public — it identifies the project and authorises nothing)
- the **service role** key (secret — it bypasses RLS entirely; it goes into your
  shell and into Edge Function secrets, never into the app, the firmware or git)

### 1.2 Apply the migrations

```bash
supabase link --project-ref <ref>
supabase db push
```

That creates the schema, the RLS policies, the dispatch functions, the realtime
publication and the cron jobs.

### 1.3 Set the function secrets

Generate the key that wraps every device secret:

```bash
node tools/provision/provision.mjs genkey
```

```bash
supabase secrets set WOLF_DEVICE_KEY=<the base64 value it printed>
supabase secrets set WOLF_POLL_INTERVAL_MS=2000
```

Keep a copy of `WOLF_DEVICE_KEY` somewhere safe. Lose it and every device has to
be re-provisioned; leak it and a database dump becomes a working credential.

### 1.4 Deploy the functions

```bash
supabase functions deploy enqueue-command
supabase functions deploy device-poll
supabase functions deploy device-ack
supabase functions deploy agent-status
supabase functions deploy pair-device
supabase functions deploy create-pairing-code
supabase functions deploy login-challenge
supabase functions deploy login-respond
```

### 1.5 Provision the PC and its two device identities

```bash
cd tools/provision
cp .env.example .env        # fill in url, service role key, WOLF_DEVICE_KEY
node provision.mjs init --email you@example.com --pc PC-001 --name "Wolf PC"
```

It prints two device secrets, once. One goes in the firmware, one in the agent.
They are stored encrypted and cannot be read back.

---

## The polling budget — set this before you leave it running

Three timers each cost an Edge Function invocation every time they fire. At the
defaults, and the PRD's 2 second controller poll:

| Source                       | Interval | Per 30 days   |
| ---------------------------- | -------- | ------------: |
| Controller `device-poll`     | 2 s      | 1,296,000     |
| Agent `device-poll`          | 3 s      |   864,000     |
| Agent `agent-status`         | 10 s     |   259,000     |
|                              |          | **2,419,000** |

The free tier allows **500,000 a month**. That is nearly five times over, and
the controller alone is 2.6× over because it never sleeps — the month's quota
goes in about six days, after which nothing works until it rolls over.

The settings below land at roughly **431,000 a month**:

```bash
supabase secrets set WOLF_POLL_INTERVAL_MS=10000
```

```powershell
# elevated, on the PC
.gent\install\set-intervals.ps1
```

The controller picks its interval up from the server on the next poll — no
reflash. The agent needs the script, which also restarts the service.

**This costs almost nothing in practice.** On the home network commands go
straight to the device over the LAN and never wait for a poll at all, and state
changes are still reported the instant they happen — the interval is only the
idle keepalive. Away from home, POWER ON averages 5 seconds and a shutdown 15,
which is not the difference that matters when the PC is in another city.

Do not slow the heartbeat past 30 s without widening the presence thresholds in
`wolf_age_out_presence`, or devices flap to offline between their own reports.
They currently allow 35 s for the controller and 100 s for the agent, roughly
three missed reports each.

If you want the original 2 second responsiveness, the Pro plan's included quota
covers it comfortably. The structural fix is to move the device channel off
Edge Functions onto Realtime, which has no per-message invocation cost;
`cloud.cpp` is the only firmware file that would change.

---

## 2. ESP32 firmware

Do the bench tests in `HARDWARE.md` first. Do not skip phase 1.

```bash
cd firmware
cp include/credentials.example.h include/credentials.h
# fill in wifi, device id, device secret, project url, anon key
pio run -t upload
pio device monitor
```

You should see:

```
WolfPC controller 1.0.0
relays parked inactive
[store] seeded NVS from credentials.h
[net] connected, ip 192.168.x.x
[net] clock synced
[local] api listening on http://wolfpc.local/api/status
watchdog armed at 30s
```

Credentials also go in over the serial console (`help`), so a Wi-Fi password
change does not mean opening the case.

---

## 3. Windows agent

From an **elevated** PowerShell prompt:

```powershell
cd agent\install
.\install.ps1 -PcId PC-001 `
              -SupabaseUrl https://<ref>.supabase.co `
              -AnonKey <anon key> `
              -DeviceSecret <the agent secret from provisioning>
```

This publishes a self-contained build to `C:\Program Files\WolfPC`, registers
`WolfPCAgent` as an auto-start LocalSystem service, opens TCP 8443 on the
**Private** firewall profile only, and starts it.

Check it:

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" status
```

Note the certificate fingerprint it prints — the app pins it for the LAN path.

### The firewall rule only works on a Private network

The installer opens TCP 8443 on the **Private** profile, deliberately: this port
should never be reachable from a network you do not own. But a Private-scoped
rule does nothing at all on a network Windows has classified **Public**, and the
failure is silent — the rule exists, is enabled, and never applies. The symptom
turns up much later as "the phone cannot reach the PC on the LAN".

Check with `Get-NetConnectionProfile`. If your home network says `Public`:

**Settings → Network & internet → Ethernet → Network profile type → Private**

Private is the correct classification for a network you own. Widening the rule
to Public instead would open the port on every network you ever join.

Only the agent is affected. The ESP32's own LAN API is plain HTTP on port 80 and
is not touched by this, so POWER ON and HARD RESET keep working over the LAN
either way — it is SHUTDOWN and RESTART that go quiet.

---

## 4. Android app

```bash
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On the PC, run:

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" pair
```

It prints a QR and a code. Then, in the app:

1. **Setup** — tap **Scan the QR on your PC**. One scan carries the project URL
   and anon key, the pairing code, and the PC's LAN address and certificate
   fingerprint, so there is nothing to type and no 64-character hex string to
   get wrong.
2. **Sign in** — the account you created in 1.1.
3. **Pair** — the code is prefilled; confirm.

If you would rather not use the camera, every field can be typed: the code on
the Pair screen, the project on Setup, and the LAN details under
**Settings → Home network**.

Pairing generates the phone's signing key inside the Keystore. It never leaves
the phone.

---

## 5. Verify the whole path

| Check                        | How                                              | Expect                        |
| ---------------------------- | ------------------------------------------------ | ----------------------------- |
| Controller is reporting      | `node provision.mjs status`                      | `last_seen` within seconds    |
| Agent is reporting           | same                                              | `last_seen` within 30 s       |
| Status reaches the app       | open the app                                      | two green dots                |
| POWER ON, cloud              | phone on mobile data, shut the PC down, tap it    | boots                         |
| POWER ON, LAN                | phone on home Wi-Fi, unplug the router's WAN      | still boots                   |
| SHUTDOWN                     | tap, confirm                                      | Windows closes gracefully     |
| HARD RESET                   | tap, type RESET, confirm                          | machine resets immediately    |
| Physical fallback            | unplug the ESP32                                  | case buttons still work       |
| Replay is refused            | re-send a captured envelope                       | `409 replay`                  |
| Expiry is enforced           | hold an envelope 30 s, then send                  | `410 expired`                 |

---

## Rotating a compromised device secret

```bash
node tools/provision/provision.mjs rotate --device wolfpc-001
```

The old secret stops working the moment the new row is written, so reflash or
reconfigure that device immediately afterwards.

## Revoking a lost phone

In the app on another device, or directly in the dashboard, set
`device_keys.revoked_at` for that phone. The next `device-poll` pushes the
shortened key list to the controller, which drops the pinned key — so the lost
phone stops working on the LAN too, not just through the cloud.
