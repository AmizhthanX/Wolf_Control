#pragma once

// WolfPC controller — compile-time configuration.
//
// Everything here is a decision that affects hardware behaviour. Read the
// comments before changing a number; several of them are safety limits rather
// than preferences.

// ---------------------------------------------------------------- pins ----
//
// PRD §7. Neither pin is a strapping pin and neither is driven by the ROM
// bootloader, which is exactly why they were chosen.
//
// IMPORTANT, and the single most dangerous detail in this project: between
// power-on and the first line of setup(), an ESP32 GPIO is a floating input.
// On a relay board that is ACTIVE LOW, floating reads as "on", so the relay
// would click every time the ESP32 resets — booting or resetting the PC by
// accident.
//
// The fix is a hardware pull-up, not code: fit a 10k resistor from each relay
// input to 3V3 (the 10k resistors listed in PRD §4). The firmware also drives
// both pins inactive as its very first action, but that happens milliseconds
// too late on its own. See docs/HARDWARE.md.

#define RELAY_POWER_PIN 18
#define RELAY_RESET_PIN 19

// Most cheap blue 2-channel boards are active LOW. Check yours with a meter
// before wiring anything to the motherboard: drive the pin high and see
// whether the relay is energised.
#define RELAY_ACTIVE_LOW 1

// -------------------------------------------------------------- timing ----

// PRD §7: about one second for a power press, 300–500 ms for reset.
#define RELAY_POWER_PULSE_MS 1000
#define RELAY_RESET_PULSE_MS 350

// Hard ceiling enforced by the relay state machine regardless of what any
// caller asks for. Nothing may weld a relay closed. (PRD §7 "never leave a
// relay permanently activated")
#define RELAY_MAX_PULSE_MS 2000

// Refuse a second pulse within this window. Protects against a double tap in
// the app, a retried command and a poll that returns the same work twice.
#define RELAY_COOLDOWN_MS 4000

// ------------------------------------------------------------ watchdog ----
//
// Generous enough to survive a slow TLS handshake on a weak signal, short
// enough that a wedged controller recovers before you notice. (PRD §7)
#define WATCHDOG_TIMEOUT_S 30

// ---------------------------------------------------------------- wifi ----

#define WIFI_CONNECT_TIMEOUT_MS 20000
#define WIFI_RETRY_BASE_MS       2000
#define WIFI_RETRY_MAX_MS       60000

// mDNS name, so the app can find the controller with no internet. (PRD §8)
#define MDNS_HOSTNAME "wolfpc"

// --------------------------------------------------------------- clock ----

#define SNTP_SERVER_1 "pool.ntp.org"
#define SNTP_SERVER_2 "time.google.com"

// Envelope expiry is meaningless without a real clock, so the cloud path
// refuses to run until SNTP has landed at least once.
#define SNTP_TIMEOUT_MS 15000

// ---------------------------------------------------------------- cloud ----

// Overridden by whatever poll_interval_ms the server returns, so you can slow
// the fleet down without reflashing. (PRD §22)
#define CLOUD_POLL_INTERVAL_MS 2000
#define CLOUD_POLL_MIN_MS      1000
#define CLOUD_POLL_MAX_MS     60000

#define CLOUD_HTTP_TIMEOUT_MS 10000

// Back off after consecutive failures so a Supabase outage does not turn into
// a hot loop that burns the radio and the watchdog.
#define CLOUD_FAIL_BACKOFF_MS 15000

// --------------------------------------------------------------- limits ----

#define MAX_TRUSTED_KEYS       4    // paired phones whose keys we pin locally
#define REPLAY_CACHE_SIZE     16    // recent command ids, PROTOCOL §5
#define MAX_BODY_BYTES      8192
#define CLOCK_SKEW_SECONDS   120

// ---------------------------------------------------------------- misc ----

#ifndef WOLF_FIRMWARE_VERSION
#define WOLF_FIRMWARE_VERSION "0.0.0-dev"
#endif

#define SERIAL_BAUD 115200
