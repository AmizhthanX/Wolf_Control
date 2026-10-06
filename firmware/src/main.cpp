// WolfPC controller — ESP32 firmware entry point.
//
// Boot order is a safety property, not a style choice. Relays are driven
// inactive before the radio, the filesystem or anything else comes up, so that
// a crash during init can never leave a contact closed across the motherboard's
// power switch. (PRD §7)

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "cloud.h"
#include "config.h"
#include "envelope.h"
#include "localapi.h"
#include "netlink.h"
#include "relay.h"
#include "store.h"
#include "wolf_crypto.h"

static uint32_t g_lastBanner = 0;

// --------------------------------------------------------- serial console ----
//
// Enough to recover a board that is on the wrong Wi-Fi or was provisioned with
// the wrong secret, without unscrewing the case and reflashing.

static void handleSerialLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  const int sp = line.indexOf(' ');
  const String cmd = sp < 0 ? line : line.substring(0, sp);
  const String rest = sp < 0 ? String() : line.substring(sp + 1);

  if (cmd == "help") {
    Serial.println(
      "commands:\n"
      "  status               show runtime state\n"
      "  config               show stored configuration\n"
      "  wifi <ssid> <pass>   set wifi credentials\n"
      "  device <id>          set device id\n"
      "  secret <64 hex>      set device secret\n"
      "  cloud <url> <anon>   set supabase url and anon key\n"
      "  test power           pulse the power relay (bench use)\n"
      "  test reset           pulse the reset relay (bench use)\n"
      "  reboot               restart the controller");
    return;
  }

  if (cmd == "status") {
    Serial.printf("wifi=%s ip=%s rssi=%d clock=%s cloud=%s fails=%lu relay=%s uptime=%lus\n",
                  netlink::connected() ? "up" : "down", netlink::ip().c_str(),
                  netlink::rssi(), netlink::clockSynced() ? "synced" : "unsynced",
                  cloud::online() ? "online" : "offline",
                  (unsigned long)cloud::consecutiveFailures(),
                  relays.stateName(), (unsigned long)netlink::uptimeSeconds());
    return;
  }

  if (cmd == "config") { store::dump(); return; }

  if (cmd == "wifi") {
    const int s2 = rest.indexOf(' ');
    if (s2 < 0) { Serial.println("usage: wifi <ssid> <password>"); return; }
    store::setWifi(rest.substring(0, s2), rest.substring(s2 + 1));
    Serial.println("saved; reboot to apply");
    return;
  }

  if (cmd == "device") { store::setDeviceId(rest); Serial.println("saved"); return; }
  if (cmd == "secret") { store::setDeviceSecret(rest); return; }

  if (cmd == "cloud") {
    const int s2 = rest.indexOf(' ');
    if (s2 < 0) { Serial.println("usage: cloud <url> <anon key>"); return; }
    store::setCloud(rest.substring(0, s2), rest.substring(s2 + 1));
    Serial.println("saved");
    return;
  }

  if (cmd == "test") {
    // Bench testing only — deliberately unauthenticated, and deliberately only
    // reachable by someone holding a USB cable to the board.
    if (rest == "power") {
      Serial.printf("power pulse: %s\n",
        pulseResultName(relays.pulse(RelayChannel::Power, RELAY_POWER_PULSE_MS)));
    } else if (rest == "reset") {
      Serial.printf("reset pulse: %s\n",
        pulseResultName(relays.pulse(RelayChannel::Reset, RELAY_RESET_PULSE_MS)));
    } else {
      Serial.println("usage: test power | test reset");
    }
    return;
  }

  if (cmd == "reboot") { Serial.println("rebooting"); delay(50); ESP.restart(); }

  Serial.println("unknown command; try: help");
}

static void pumpSerial() {
  static String buffer;
  while (Serial.available()) {
    const char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (buffer.length()) { handleSerialLine(buffer); buffer = ""; }
    } else if (buffer.length() < 200) {
      buffer += c;
    }
  }
}

// ------------------------------------------------------------------ setup ----

void setup() {
  // First. Before anything that can block, fail or print.
  relays.begin();

  Serial.begin(SERIAL_BAUD);
  delay(100);
  Serial.printf("\n\nWolfPC controller %s\n", WOLF_FIRMWARE_VERSION);
  Serial.println("relays parked inactive");

  store::begin();
  store::dump();

  if (!store::provisioned()) {
    Serial.println("\n[!] this controller is not provisioned.");
    Serial.println("    run: node tools/provision/provision.mjs init --email you@example.com");
    Serial.println("    then set 'device' and 'secret' here, or reflash with credentials.h\n");
  }

  netlink::begin();
  localapi::begin();
  cloud::begin();

  // The watchdog is the answer to "what if the firmware wedges while a relay
  // is closed". A reset re-runs relays.begin(), which parks both channels.
  esp_task_wdt_init(WATCHDOG_TIMEOUT_S, true);
  esp_task_wdt_add(nullptr);
  Serial.printf("watchdog armed at %ds\n", WATCHDOG_TIMEOUT_S);
}

// ------------------------------------------------------------------- loop ----

void loop() {
  esp_task_wdt_reset();

  // Relay release first, every iteration, ahead of anything that can block.
  // Nothing below may delay a contact opening.
  relays.tick();

  netlink::tick();
  localapi::tick();
  cloud::tick();
  pumpSerial();

  if (millis() - g_lastBanner > 60000) {
    g_lastBanner = millis();
    Serial.printf("[hb] up=%lus wifi=%s cloud=%s heap=%lu relay=%s\n",
                  (unsigned long)netlink::uptimeSeconds(),
                  netlink::connected() ? "up" : "down",
                  cloud::online() ? "ok" : "down",
                  (unsigned long)ESP.getFreeHeap(), relays.stateName());
  }

  delay(5);   // keeps the IDF idle task fed without adding real latency
}
