#pragma once
#include <Arduino.h>
#include <ArduinoJson.h>

// The signed command envelope as the controller sees it. PROTOCOL §2.
//
// Verification here is the last line of defence and the one that matters most:
// it is what makes a compromised control plane insufficient to press the power
// button. Supabase relays envelopes; it cannot forge one.

enum class EnvelopeCheck : uint8_t {
  Ok,
  Malformed,
  WrongDevice,
  UnknownCommand,
  Expired,
  Replay,
  BadSignature,
  NoTrustedKey,
  ClockUnsynced,
};

const char* envelopeCheckName(EnvelopeCheck c);

struct Envelope {
  String commandId;
  String deviceId;
  String command;
  String timestamp;
  String expiresAt;
  String nonce;
  String signature;
  String publicKey;   // optional; supplied by device-poll

  bool fromJson(JsonObjectConst obj);

  /** The exact bytes that were signed. Never rebuilt from re-serialised JSON. */
  String signingString() const;
};

/** Parse an RFC3339 UTC string ending in Z. Returns 0 if it is not one. */
time_t parseRfc3339Utc(const String& s);

/**
 * Full check: shape, addressee, expiry, replay, signature.
 *
 * @param requireSyncedClock  true for the cloud path, where SNTP has certainly
 *        run. The local path passes false so that a controller which booted
 *        during an internet outage can still be used from the LAN — it keeps
 *        the nonce cache and the signature check, and only relaxes expiry.
 */
EnvelopeCheck verifyEnvelope(const Envelope& e, bool requireSyncedClock);

/** Remember a command id so the same envelope cannot execute twice. */
void rememberCommand(const String& commandId);
bool alreadySeen(const String& commandId);
