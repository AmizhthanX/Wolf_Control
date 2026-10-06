#include "envelope.h"
#include "config.h"
#include "store.h"
#include "wolf_crypto.h"
#include "netlink.h"

const char* envelopeCheckName(EnvelopeCheck c) {
  switch (c) {
    case EnvelopeCheck::Ok:             return "ok";
    case EnvelopeCheck::Malformed:      return "malformed";
    case EnvelopeCheck::WrongDevice:    return "wrong_device";
    case EnvelopeCheck::UnknownCommand: return "unknown_command";
    case EnvelopeCheck::Expired:        return "expired";
    case EnvelopeCheck::Replay:         return "replay";
    case EnvelopeCheck::BadSignature:   return "bad_signature";
    case EnvelopeCheck::NoTrustedKey:   return "no_trusted_key";
    case EnvelopeCheck::ClockUnsynced:  return "clock_unsynced";
  }
  return "unknown";
}

// ------------------------------------------------------------ parsing ----

static String readStr(JsonObjectConst obj, const char* key, size_t maxLen = 256) {
  JsonVariantConst v = obj[key];
  if (!v.is<const char*>()) return String();
  String s = v.as<const char*>();
  if (s.length() == 0 || s.length() > maxLen) return String();
  return s;
}

bool Envelope::fromJson(JsonObjectConst obj) {
  commandId = readStr(obj, "command_id", 64);
  deviceId  = readStr(obj, "device_id", 64);
  command   = readStr(obj, "command", 32);
  timestamp = readStr(obj, "timestamp", 32);
  expiresAt = readStr(obj, "expires_at", 32);
  nonce     = readStr(obj, "nonce", 32);
  signature = readStr(obj, "signature", 256);
  publicKey = readStr(obj, "public_key", 256);

  return commandId.length() && deviceId.length() && command.length() &&
         timestamp.length() && expiresAt.length() && nonce.length() &&
         signature.length();
}

String Envelope::signingString() const {
  String s;
  s.reserve(160);
  s += "WOLFPC1\n";
  s += commandId; s += '\n';
  s += deviceId;  s += '\n';
  s += command;   s += '\n';
  s += timestamp; s += '\n';
  s += expiresAt; s += '\n';
  s += nonce;
  return s;
}

// --------------------------------------------------------------- time ----

// Days since the epoch for a civil date. Howard Hinnant's algorithm — used
// instead of mktime() because mktime applies the local timezone, and this
// firmware deliberately never sets one.
static long daysFromCivil(int y, unsigned m, unsigned d) {
  y -= m <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = (unsigned)(y - era * 400);
  const unsigned doy = (153u * (m + (m > 2 ? -3 : 9)) + 2u) / 5u + d - 1u;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return (long)era * 146097L + (long)doe - 719468L;
}

time_t parseRfc3339Utc(const String& s) {
  int Y, M, D, h, m, sec;
  if (sscanf(s.c_str(), "%4d-%2d-%2dT%2d:%2d:%2dZ", &Y, &M, &D, &h, &m, &sec) != 6) {
    return 0;
  }
  if (M < 1 || M > 12 || D < 1 || D > 31 || h > 23 || m > 59 || sec > 60) return 0;
  const long days = daysFromCivil(Y, (unsigned)M, (unsigned)D);
  return (time_t)(days * 86400L + h * 3600L + m * 60L + sec);
}

// ------------------------------------------------------- replay cache ----

static String g_seen[REPLAY_CACHE_SIZE];
static uint8_t g_seenNext = 0;

bool alreadySeen(const String& commandId) {
  for (uint8_t i = 0; i < REPLAY_CACHE_SIZE; i++) {
    if (g_seen[i].length() && g_seen[i] == commandId) return true;
  }
  return false;
}

void rememberCommand(const String& commandId) {
  g_seen[g_seenNext] = commandId;
  g_seenNext = (uint8_t)((g_seenNext + 1) % REPLAY_CACHE_SIZE);
}

// ------------------------------------------------------- verification ----

static bool signatureVerifies(const Envelope& e, EnvelopeCheck& why) {
  const String message = e.signingString();

  // A key attached to the envelope is a hint, not an authority: it still has to
  // be one we pinned at pairing time, or a compromised control plane could
  // simply attach its own key along with a matching signature.
  const uint8_t n = store::trustedKeyCount();
  if (n == 0) {
    why = EnvelopeCheck::NoTrustedKey;
    return false;
  }

  for (uint8_t i = 0; i < n; i++) {
    const String key = store::trustedKey(i);
    if (key.length() == 0) continue;
    if (e.publicKey.length() > 0 && e.publicKey != key) continue;
    if (wolfcrypto::verifyEcdsaP256(key, e.signature, message)) return true;
  }

  why = EnvelopeCheck::BadSignature;
  return false;
}

EnvelopeCheck verifyEnvelope(const Envelope& e, bool requireSyncedClock) {
  if (e.commandId.length() == 0 || e.signature.length() == 0) {
    return EnvelopeCheck::Malformed;
  }
  if (e.nonce.length() != 16) return EnvelopeCheck::Malformed;

  // Only the two hardware commands belong to the controller. A SHUTDOWN that
  // somehow arrives here is a routing bug, and pulsing a relay in response
  // would turn it into data loss. (PRD §3)
  if (e.command != "POWER_ON" && e.command != "HARD_RESET") {
    return EnvelopeCheck::UnknownCommand;
  }

  if (alreadySeen(e.commandId)) return EnvelopeCheck::Replay;

  const time_t expires = parseRfc3339Utc(e.expiresAt);
  const time_t issued  = parseRfc3339Utc(e.timestamp);
  if (expires == 0 || issued == 0) return EnvelopeCheck::Malformed;

  if (netlink::clockSynced()) {
    const time_t now = time(nullptr);
    if (now > expires) return EnvelopeCheck::Expired;
    if (issued > now + CLOCK_SKEW_SECONDS) return EnvelopeCheck::Expired;
  } else if (requireSyncedClock) {
    return EnvelopeCheck::ClockUnsynced;
  }
  // Unsynced clock on the local path: expiry cannot be judged, so the nonce
  // cache and the signature are what stand between an attacker and the relay.
  // Documented in PROTOCOL §5 and surfaced by /api/status.

  EnvelopeCheck why = EnvelopeCheck::BadSignature;
  if (!signatureVerifies(e, why)) return why;

  return EnvelopeCheck::Ok;
}
