![WolfPC ESP32 controller overview](../docs/assets/wolfpc-controller.png)

# ESP32 controller

**The part of WolfPC that can wake your PC while Windows is off.**

The controller joins Wi-Fi, receives signed commands, and briefly closes one of
two relay contacts. Channel 1 acts like the case power button; channel 2 acts
like the reset button. It also exposes a local API so the phone can reach it on
your home network.

[Back to WolfPC](../README.md) · [Hardware guide](../docs/HARDWARE.md) · [Protocol](../docs/PROTOCOL.md)

## Before you connect the motherboard

Do the [bench tests](../docs/HARDWARE.md#phase-1--bench-no-pc-involved) first.
Both relays must remain inactive through startup and repeated ESP32 resets.
Relay input behavior varies: the reference opto-isolated board passed without
external pull-ups. Check your board rather than fitting a resistor blindly.

Use **COM + NO dry contacts**, in parallel with the case buttons. Do not feed
GPIO, 5 V, or ESP32 ground into the motherboard's power/reset button pins.
For an ATX standby tap, follow the protected supply and fuse arrangement in
the hardware guide. Do not open the PSU.

## Build and flash

You'll need PlatformIO, a data-capable USB cable, and the USB serial driver for
your board. This project uses the Arduino framework and the `esp32dev` target.

From the repository root, in PowerShell:

```powershell
cd firmware
if (!(Test-Path include/credentials.h)) {
    Copy-Item include/credentials.example.h include/credentials.h
}
```

Edit `include/credentials.h` with your Wi-Fi settings, controller device ID,
controller secret, Supabase URL, and anon key. The real file is ignored by Git.
Use the controller secret, not the agent secret or service-role key.

```powershell
pio run
pio run -t upload
pio device monitor
```

The console runs at **115200 baud**. If automatic port selection fails, pass
your board's port with `--upload-port COM5` or `--port COM5`; replace COM5 with
the port on your machine. Close the monitor before flashing again.

## What a healthy boot looks like

The relays stay quiet. The serial log then shows Wi-Fi connecting, the clock
synchronizing, and the local API starting. Use `status` to check connectivity
and relay state, then confirm the device is reporting through the app or
provisioning tool.

| Setting | Current value |
| --- | --- |
| Firmware version | 1.0.1 |
| Power relay | GPIO 18 / 1,000 ms pulse |
| Reset relay | GPIO 19 / 350 ms pulse |
| Maximum pulse | 2,000 ms |
| Cooldown | 4,000 ms |
| Watchdog | 30 seconds |
| Local status | `http://wolfpc.local/api/status` |

Timing and polarity live in [include/config.h](include/config.h). The server can
adjust the cloud polling interval without a reflash.

## The serial console is your repair kit

| Command | Use it to… |
| --- | --- |
| `help` | List available commands |
| `status` | Inspect Wi-Fi, clock, cloud, relay state, and uptime |
| `config` | Inspect stored settings with the device secret redacted |
| `wifi <ssid> <password>` | Change the Wi-Fi settings |
| `device <id>` | Set the controller identity |
| `secret <64-hex-secret>` | Apply a new controller secret after rotation |
| `cloud <url> <anon-key>` | Change the Supabase connection |
| `test power` / `test reset` | Exercise relay pulses on the bench |
| `reboot` | Restart the controller |

Relay tests operate real hardware if it is connected. Keep motherboard leads
disconnected while you are bench-testing.

## Settings that survive a restart

Settings live in NVS flash. At startup, the firmware checks each field rather
than trusting a single “already configured” flag. Valid settings are mirrored
into the `wolfpc_backup` namespace; missing primary values and malformed device
secrets can be restored from it. Serial changes update the backup too.

Build defaults are used when no backup exists. Trusted phone keys are **not**
backed up: the cloud refreshes them, so recovery does not restore revoked keys.
Unchanged settings are not rewritten on every boot.

Both namespaces share the same flash partition. A full erase or failing flash
can remove both. Keep build credentials current after a secret rotation.

## Finding the right file

| Source | Responsibility |
| --- | --- |
| `relay.*` | Pulses, limits, cooldown, and mutual exclusion |
| `envelope.*` | Signed command parsing, expiry, and replay checks |
| `wolf_crypto.*` | HMAC, SHA-256, ECDSA P-256, encoding |
| `store.*` | Configuration, NVS recovery, pinned phone keys |
| `netlink.*` | Wi-Fi reconnects, SNTP, mDNS |
| `cloud.*` | Polling, acknowledgements, key refresh |
| `localapi.*` | Direct LAN requests |
| `main.cpp` | Boot order, watchdog, serial console |

When changing the firmware, preserve two ordering rules: `relays.begin()` runs
first in `setup()`, and `relays.tick()` runs first in `loop()`. The latter
releases an active pulse before other work can block.

## Recovery tests

The [storage tests](tests/storage) compile the real storage implementation
against an in-memory Preferences substitute. They cover partial loss, malformed
secrets, serial overrides, rotated secrets, and unnecessary flash writes.

From this directory, with CMake and a C++ compiler installed:

```powershell
cmake -S tests/storage -B tests/storage/build
cmake --build tests/storage/build --config Release
```

Run the resulting `storage_test` executable. Visual Studio generators place it
under `tests/storage/build/Release/`; single-configuration generators usually
place it directly in `tests/storage/build/`. These tests do not simulate physical
flash faults.

## If the controller goes offline

Start with USB serial and `status`. Check Wi-Fi association, clock sync, and
cloud connectivity separately. If Wi-Fi works but the cloud does not, inspect
`config` and verify the device ID and provisioning settings. If an input LED
lights but a relay never clicks, check the board's coil supply and JD-VCC
arrangement using the hardware guide.
