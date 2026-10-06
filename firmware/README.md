# ESP32 controller firmware

PlatformIO + Arduino framework. Builds to ~1.1 MB flash, ~50 KB RAM.

```bash
cp include/credentials.example.h include/credentials.h   # then fill it in
pio run                 # build
pio run -t upload       # flash
pio device monitor      # serial console, 115200
```

**Do the bench tests in `../docs/HARDWARE.md` before wiring anything to the
motherboard.** The section on relay boot behaviour is not optional reading;
an active-low relay board with no pull-up resistor presses the power button
every time the ESP32 resets.

## Serial console

```
help                 list commands
status               wifi, clock, cloud, relay, uptime
config               stored configuration, secret redacted
wifi <ssid> <pass>   change wifi without reflashing
device <id>          set the device id
secret <64 hex>      set the device secret
cloud <url> <anon>   set the Supabase project
test power           pulse the power relay   (bench use)
test reset           pulse the reset relay   (bench use)
reboot
```

Configuration is seeded from `credentials.h` on a blank chip and lives in NVS
after that, so a Wi-Fi password change does not mean opening the case.

## Layout

| File           | Responsibility                                          |
| -------------- | ------------------------------------------------------- |
| `relay.*`      | the only code allowed to move a relay; pulse clamping, cooldown, mutual exclusion |
| `envelope.*`   | envelope parsing, expiry, replay cache, signature check |
| `wolf_crypto.*`| HMAC, SHA-256, ECDSA P-256 verify, base64url, hex       |
| `store.*`      | NVS: wifi, identity, pinned phone keys                  |
| `netlink.*`    | wifi with backoff, SNTP, mDNS                           |
| `cloud.*`      | outbound polling, acks, key refresh                     |
| `localapi.*`   | the LAN fallback API                                    |
| `main.cpp`     | boot order, watchdog, serial console                    |

## Two invariants worth preserving

1. `relays.begin()` is the first statement in `setup()`, before serial, before
   storage, before the radio. Nothing may move ahead of it.
2. `relays.tick()` is the first call in `loop()`. It is what actually releases a
   closed contact, so nothing that can block may run before it.

## Configuration recovery (1.0.1)

Each boot checks configuration fields individually instead of relying on the
legacy seeded flag. Valid settings are mirrored in the wolfpc_backup NVS
namespace. Missing primary settings and malformed device secrets are restored
from that copy. Build defaults bootstrap settings only when no backup exists.
Serial updates also update the backup, including rotated device secrets. Normal
boots do not rewrite unchanged values. Trusted phone keys are deliberately not
backed up: cloud pairing refreshes them, and recovery must not revive revoked keys.

Both namespaces use the same flash partition. This helps with partial settings
loss, not failing hardware or a complete flash erase; build defaults may be
outdated after a rotation. Keep the local provisioning configuration current.
Storage write failures are logged without exposing credentials.

Recovery tests compile the actual store.cpp against an in-memory Preferences
substitute in tests/storage. They cover partial loss, malformed secrets, primary
namespace loss, serial overrides, rotated secrets, open-network passwords, and
unchanged-boot write counts. They do not simulate physical flash faults.
