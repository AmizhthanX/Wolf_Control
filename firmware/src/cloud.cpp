#include "cloud.h"
#include "config.h"
#include "envelope.h"
#include "netlink.h"
#include "relay.h"
#include "store.h"
#include "wolf_crypto.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>

// Root certificates.
//
// The IDF ships a Mozilla root bundle and the Arduino core embeds it, so the
// normal build validates Supabase's certificate against real roots. If your
// core lacks the bundle symbol, set -DWOLF_TLS_ROOT_PEM='"...pem..."'.
//
// There is no setInsecure() path here on purpose. This link carries the
// credential that can power-cycle a machine; accepting any certificate would
// hand it to whoever owns the coffee-shop router.
#if !defined(WOLF_TLS_ROOT_PEM) && __has_include(<esp_crt_bundle.h>)
#include <esp_crt_bundle.h>
extern const uint8_t rootca_crt_bundle_start[] asm("_binary_x509_crt_bundle_start");
#define WOLF_USE_CRT_BUNDLE 1
#endif

namespace cloud {

static uint32_t g_lastPoll = 0;
static uint32_t g_interval = CLOUD_POLL_INTERVAL_MS;
static uint32_t g_failures = 0;
static bool     g_online = false;

// A command whose relay pulse is still running. We only report EXECUTED once
// the contact has actually opened again, so the app's "done" means done.
static String   g_pendingAck;
static bool     g_pendingWasPower = false;
static uint8_t  g_pendingAttempts = 0;

// A completion report is worth retrying — the user is staring at the app
// waiting for it — but not forever, or a long cloud outage would leave the
// controller stuck reporting a pulse it fired ten minutes ago.
#define PENDING_ACK_MAX_ATTEMPTS 5

static void applyTls(WiFiClientSecure& client) {
#if defined(WOLF_TLS_ROOT_PEM)
  client.setCACert(WOLF_TLS_ROOT_PEM);
#elif defined(WOLF_USE_CRT_BUNDLE)
  client.setCACertBundle(rootca_crt_bundle_start);
#else
#error "No TLS trust anchor available: define WOLF_TLS_ROOT_PEM with your root CA."
#endif
  client.setTimeout(CLOUD_HTTP_TIMEOUT_MS / 1000);
}

/**
 * POST a signed request to an Edge Function.
 *
 * The HMAC covers the method, the function path and a hash of the exact body
 * bytes (PROTOCOL §3), so a proxy cannot retarget a signed request at a
 * different endpoint.
 */
static int postSigned(const String& functionName, const String& body, String& response) {
  const String base = store::supabaseUrl();
  const String anon = store::supabaseAnonKey();
  const String deviceId = store::deviceId();
  uint8_t secret[32];

  if (base.length() == 0 || deviceId.length() == 0 || !store::deviceSecret(secret)) {
    Serial.println("[cloud] not provisioned");
    return -1;
  }

  const String path = "/functions/v1/" + functionName;
  const String nonce = wolfcrypto::randomHex(16);
  const String ts = String((uint32_t)time(nullptr));

  String message;
  message.reserve(200);
  message += deviceId;  message += '\n';
  message += ts;        message += '\n';
  message += nonce;     message += '\n';
  message += "POST";    message += '\n';
  message += path;      message += '\n';
  message += wolfcrypto::sha256Hex(body);

  const String signature = wolfcrypto::hmacSha256Hex(secret, sizeof(secret), message);
  memset(secret, 0, sizeof(secret));
  if (signature.length() == 0) return -1;

  WiFiClientSecure client;
  applyTls(client);

  HTTPClient http;
  http.setTimeout(CLOUD_HTTP_TIMEOUT_MS);
  http.setConnectTimeout(CLOUD_HTTP_TIMEOUT_MS);
  if (!http.begin(client, base + path)) return -1;

  http.addHeader("content-type", "application/json");
  http.addHeader("apikey", anon);
  http.addHeader("x-wolf-device", deviceId);
  http.addHeader("x-wolf-timestamp", ts);
  http.addHeader("x-wolf-nonce", nonce);
  http.addHeader("x-wolf-signature", signature);

  const int code = http.POST((uint8_t*)body.c_str(), body.length());
  if (code > 0) {
    response = http.getString();
    if (response.length() > MAX_BODY_BYTES) response.remove(MAX_BODY_BYTES);
  }
  http.end();
  return code;
}

static bool ack(const String& commandId, const char* status, const char* error) {
  JsonDocument doc;
  doc["command_id"] = commandId;
  doc["status"] = status;
  if (error) doc["error"] = error;

  String body;
  serializeJson(doc, body);

  String response;
  const int code = postSigned("device-ack", body, response);
  if (code != 200) {
    // Not fatal, and safe to retry: acks are idempotent server-side. What must
    // never happen is re-pulsing the relay because an ack was lost, and the
    // replay cache is what prevents that.
    Serial.printf("[cloud] ack %s -> http %d\n", status, code);
    return false;
  }
  return true;
}

static void execute(const Envelope& e) {
  rememberCommand(e.commandId);
  ack(e.commandId, "ACKNOWLEDGED", nullptr);

  const bool isPower = e.command == "POWER_ON";
  const PulseResult r = relays.pulse(
      isPower ? RelayChannel::Power : RelayChannel::Reset,
      isPower ? RELAY_POWER_PULSE_MS : RELAY_RESET_PULSE_MS);

  if (r != PulseResult::Started) {
    Serial.printf("[cloud] %s refused: %s\n", e.command.c_str(), pulseResultName(r));
    ack(e.commandId, "FAILED", pulseResultName(r));
    return;
  }

  g_pendingAck = e.commandId;
  g_pendingWasPower = isPower;
  g_pendingAttempts = 0;
}

static void poll() {
  JsonDocument doc;
  doc["firmware_version"] = WOLF_FIRMWARE_VERSION;
  doc["uptime_s"] = netlink::uptimeSeconds();
  doc["rssi"] = netlink::rssi();
  doc["relay_state"] = relays.stateName();
  doc["ip"] = netlink::ip();

  String body;
  serializeJson(doc, body);

  String response;
  const int code = postSigned("device-poll", body, response);

  if (code != 200) {
    g_failures++;
    g_online = false;
    Serial.printf("[cloud] poll failed: http %d (%lu in a row)\n",
                  code, (unsigned long)g_failures);
    return;
  }

  g_failures = 0;
  g_online = true;

  JsonDocument res;
  if (deserializeJson(res, response) != DeserializationError::Ok) {
    Serial.println("[cloud] poll response was not json");
    return;
  }

  const uint32_t suggested = res["poll_interval_ms"] | CLOUD_POLL_INTERVAL_MS;
  g_interval = constrain(suggested, (uint32_t)CLOUD_POLL_MIN_MS, (uint32_t)CLOUD_POLL_MAX_MS);

  // Refresh the pinned key set, so revoking a phone in the app actually stops
  // it working on the LAN too.
  JsonArrayConst keys = res["trusted_keys"].as<JsonArrayConst>();
  if (!keys.isNull()) {
    String pinned[MAX_TRUSTED_KEYS];
    uint8_t n = 0;
    for (JsonVariantConst k : keys) {
      if (n >= MAX_TRUSTED_KEYS) break;
      if (k.is<const char*>()) pinned[n++] = k.as<const char*>();
    }
    store::setTrustedKeys(pinned, n);
  }

  JsonArrayConst commands = res["commands"].as<JsonArrayConst>();
  if (commands.isNull()) return;

  for (JsonVariantConst item : commands) {
    Envelope e;
    if (!e.fromJson(item.as<JsonObjectConst>())) {
      Serial.println("[cloud] skipping malformed envelope");
      continue;
    }
    // No check on e.deviceId here: the envelope is addressed to the *pc* id,
    // which is deliberately not the controller's own id (PROTOCOL §2). The
    // signature covers that field, so a retargeted envelope fails verification.

    const EnvelopeCheck check = verifyEnvelope(e, /*requireSyncedClock=*/true);
    if (check != EnvelopeCheck::Ok) {
      Serial.printf("[cloud] rejecting %s: %s\n",
                    e.command.c_str(), envelopeCheckName(check));
      rememberCommand(e.commandId);
      ack(e.commandId, "FAILED", envelopeCheckName(check));
      continue;
    }

    Serial.printf("[cloud] executing %s (%s)\n", e.command.c_str(), e.commandId.c_str());
    execute(e);

    // One hardware action per poll. If two arrived, the second is still QUEUED
    // and will come back next time — by which point the cooldown has passed.
    break;
  }
}

void begin() {
  g_lastPoll = 0;
  g_interval = CLOUD_POLL_INTERVAL_MS;
}

void tick() {
  // Report completion as soon as the contact opens, and keep trying if the
  // report itself does not get through.
  if (g_pendingAck.length() > 0 && !relays.busy() && netlink::connected()) {
    if (ack(g_pendingAck, "EXECUTED", nullptr)) {
      Serial.printf("[cloud] %s complete\n", g_pendingWasPower ? "POWER_ON" : "HARD_RESET");
      g_pendingAck = "";
    } else if (++g_pendingAttempts >= PENDING_ACK_MAX_ATTEMPTS) {
      Serial.printf("[cloud] giving up reporting %s; the relay did fire\n",
                    g_pendingWasPower ? "POWER_ON" : "HARD_RESET");
      g_pendingAck = "";
    }
  }

  if (!netlink::connected() || !store::provisioned()) return;

  // Refuse to run the cloud path on an unsynced clock: without real time,
  // expiry checks are meaningless and the request HMAC would be outside the
  // server's window anyway.
  if (!netlink::clockSynced()) return;

  uint32_t wait = g_interval;
  if (g_failures > 0) {
    wait = CLOUD_FAIL_BACKOFF_MS;               // stop hammering a dead endpoint
    if (g_failures > 8) wait = CLOUD_POLL_MAX_MS;
  }

  if (millis() - g_lastPoll < wait) return;
  g_lastPoll = millis();
  poll();
}

bool     online()              { return g_online; }
uint32_t lastPollAt()          { return g_lastPoll; }
uint32_t consecutiveFailures() { return g_failures; }
uint32_t pollIntervalMs()      { return g_interval; }

}  // namespace cloud
