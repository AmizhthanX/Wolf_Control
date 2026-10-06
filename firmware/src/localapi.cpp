#include "localapi.h"
#include "cloud.h"
#include "config.h"
#include "envelope.h"
#include "netlink.h"
#include "relay.h"
#include "store.h"

#include <ArduinoJson.h>
#include <WebServer.h>

namespace localapi {

static WebServer server(80);
static uint32_t g_lastAttempt = 0;
static uint32_t g_rejects = 0;

static void sendJson(int code, const JsonDocument& doc) {
  String out;
  serializeJson(doc, out);
  server.send(code, "application/json", out);
}

static void sendError(int code, const char* errCode, const char* message) {
  JsonDocument doc;
  doc["error"]["code"] = errCode;
  doc["error"]["message"] = message;
  sendJson(code, doc);
}

static void handleStatus() {
  JsonDocument doc;
  doc["device_id"]        = store::deviceId();
  doc["firmware_version"] = WOLF_FIRMWARE_VERSION;
  doc["uptime_s"]         = netlink::uptimeSeconds();
  doc["wifi_connected"]   = netlink::connected();
  doc["rssi"]             = netlink::rssi();
  doc["ip"]               = netlink::ip();
  doc["relay_state"]      = relays.stateName();
  doc["trusted_keys"]     = store::trustedKeyCount();
  doc["cloud_online"]     = cloud::online();
  doc["cloud_failures"]   = cloud::consecutiveFailures();

  // The app shows a warning when this is "unsynced": expiry cannot be enforced
  // in that state, and the user deserves to know the weaker rule is in force.
  doc["clock"] = netlink::clockSynced() ? "synced" : "unsynced";
  doc["rejected_requests"] = g_rejects;

  sendJson(200, doc);
}

/** Shared body for /api/power and /api/reset. */
static void handleAction(const char* expectedCommand, RelayChannel channel, uint32_t pulseMs) {
  // Two accepted actions cannot arrive back to back; the relay cooldown would
  // refuse the second anyway, and rejecting early keeps an attacker from using
  // the endpoint as a signature oracle.
  if (millis() - g_lastAttempt < 500) {
    sendError(429, "rate_limited", "too fast");
    return;
  }
  g_lastAttempt = millis();

  if (!server.hasArg("plain")) {
    sendError(400, "bad_request", "expected a json envelope");
    return;
  }
  const String body = server.arg("plain");
  if (body.length() > MAX_BODY_BYTES) {
    sendError(400, "bad_request", "body too large");
    return;
  }

  JsonDocument doc;
  if (deserializeJson(doc, body) != DeserializationError::Ok) {
    sendError(400, "bad_request", "body is not valid json");
    return;
  }

  Envelope e;
  if (!e.fromJson(doc.as<JsonObjectConst>())) {
    sendError(400, "bad_request", "envelope is incomplete");
    return;
  }

  // The route and the signed command have to agree, or a POWER_ON envelope
  // could be replayed against /api/reset to cause a hard reset the user never
  // authorised.
  if (e.command != expectedCommand) {
    g_rejects++;
    sendError(400, "bad_request", "command does not match this endpoint");
    return;
  }

  const EnvelopeCheck check = verifyEnvelope(e, /*requireSyncedClock=*/false);
  if (check != EnvelopeCheck::Ok) {
    g_rejects++;
    Serial.printf("[local] rejected %s: %s\n", e.command.c_str(), envelopeCheckName(check));
    const int code = (check == EnvelopeCheck::Replay)       ? 409
                   : (check == EnvelopeCheck::Expired)      ? 410
                   : (check == EnvelopeCheck::BadSignature) ? 401
                   : (check == EnvelopeCheck::NoTrustedKey) ? 403
                                                            : 400;
    sendError(code, envelopeCheckName(check), "envelope rejected");
    return;
  }

  rememberCommand(e.commandId);

  const PulseResult r = relays.pulse(channel, pulseMs);
  if (r != PulseResult::Started) {
    sendError(409, "conflict", pulseResultName(r));
    return;
  }

  Serial.printf("[local] executed %s (%s)\n", e.command.c_str(), e.commandId.c_str());

  JsonDocument res;
  res["command_id"] = e.commandId;
  res["status"] = "EXECUTED";
  res["pulse_ms"] = pulseMs;
  sendJson(200, res);
}

void begin() {
  server.on("/api/status", HTTP_GET, handleStatus);

  server.on("/api/power", HTTP_POST, []() {
    handleAction("POWER_ON", RelayChannel::Power, RELAY_POWER_PULSE_MS);
  });

  server.on("/api/reset", HTTP_POST, []() {
    handleAction("HARD_RESET", RelayChannel::Reset, RELAY_RESET_PULSE_MS);
  });

  // Anything else, including the bare root, gets a flat 404. The controller
  // advertises nothing about itself to an unauthenticated caller.
  server.onNotFound([]() { sendError(404, "not_found", "no"); });

  server.begin();
  Serial.printf("[local] api listening on http://%s.local/api/status\n", MDNS_HOSTNAME);
}

void tick() {
  server.handleClient();
}

}  // namespace localapi
