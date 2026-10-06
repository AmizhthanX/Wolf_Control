#include "netlink.h"
#include "config.h"
#include "store.h"

#include <ESPmDNS.h>
#include <WiFi.h>
#include <time.h>

namespace netlink {

static uint32_t g_retryDelay = WIFI_RETRY_BASE_MS;
static uint32_t g_lastAttempt = 0;
static uint32_t g_connectedAt = 0;
static bool     g_mdnsUp = false;
static bool     g_clockSynced = false;
static uint32_t g_lastSntpCheck = 0;

static void startMdns() {
  if (g_mdnsUp) return;
  if (MDNS.begin(MDNS_HOSTNAME)) {
    MDNS.addService("http", "tcp", 80);
    g_mdnsUp = true;
    Serial.printf("[net] mdns up: http://%s.local\n", MDNS_HOSTNAME);
  } else {
    Serial.println("[net] mdns failed to start");
  }
}

static void beginConnect() {
  const String ssid = store::wifiSsid();
  if (ssid.length() == 0) {
    Serial.println("[net] no ssid configured - use the serial console: wifi <ssid> <password>");
    return;
  }
  Serial.printf("[net] connecting to %s\n", ssid.c_str());
  WiFi.disconnect(true, true);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  // Keeping the radio out of power save costs a little current from a rail
  // that has amps to spare, and removes the multi-second wake latency that
  // would otherwise sit in front of every POWER_ON.
  WiFi.setSleep(false);
  WiFi.setHostname(MDNS_HOSTNAME);
  WiFi.begin(ssid.c_str(), store::wifiPassword().c_str());
  g_lastAttempt = millis();
}

void begin() {
  WiFi.persistent(false);
  beginConnect();
  configTime(0, 0, SNTP_SERVER_1, SNTP_SERVER_2);   // UTC, always
}

void tick() {
  const bool up = WiFi.status() == WL_CONNECTED;

  if (up) {
    if (g_connectedAt == 0) {
      g_connectedAt = millis();
      g_retryDelay = WIFI_RETRY_BASE_MS;
      Serial.printf("[net] connected, ip %s, rssi %d\n",
                    WiFi.localIP().toString().c_str(), WiFi.RSSI());
      startMdns();
    }

    // A clock that has never been set reads as 1970. Anything past 2023 means
    // SNTP has answered at least once, which is all the envelope checks need.
    if (!g_clockSynced && millis() - g_lastSntpCheck > 1000) {
      g_lastSntpCheck = millis();
      if (time(nullptr) > 1700000000) {
        g_clockSynced = true;
        Serial.println("[net] clock synced");
      }
    }
    return;
  }

  // Disconnected.
  if (g_connectedAt != 0) {
    Serial.println("[net] wifi lost");
    g_connectedAt = 0;
    g_mdnsUp = false;
    MDNS.end();
  }

  if (millis() - g_lastAttempt >= g_retryDelay) {
    beginConnect();
    g_retryDelay = g_retryDelay * 2;
    if (g_retryDelay > WIFI_RETRY_MAX_MS) g_retryDelay = WIFI_RETRY_MAX_MS;
  }
}

bool connected()    { return WiFi.status() == WL_CONNECTED; }
bool clockSynced()  { return g_clockSynced; }
String ip()         { return connected() ? WiFi.localIP().toString() : String("0.0.0.0"); }
int rssi()          { return connected() ? WiFi.RSSI() : 0; }
uint32_t uptimeSeconds() { return millis() / 1000; }

uint32_t connectedForSeconds() {
  return g_connectedAt == 0 ? 0 : (millis() - g_connectedAt) / 1000;
}

}  // namespace netlink
