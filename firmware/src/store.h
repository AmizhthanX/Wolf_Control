#pragma once
#include <Arduino.h>
#include "config.h"

// Persistent configuration in NVS (PRD §7: "store Wi-Fi credentials securely",
// "store device credentials securely").
//
// NVS lives in its own flash partition and survives an OTA, which is what we
// want: a firmware update must not silently unpair the controller or forget
// which phones it trusts.
//
// Missing settings are recovered at boot from a second NVS namespace, then
// build defaults when no backup exists. Existing settings remain authoritative.
// Serial changes update the backup too; trusted phone keys are never restored.

namespace store {

void begin();

String wifiSsid();
String wifiPassword();
void   setWifi(const String& ssid, const String& password);

String deviceId();
String supabaseUrl();
String supabaseAnonKey();

/** Copies the 32-byte HMAC secret out. Returns false if none is stored. */
bool deviceSecret(uint8_t out[32]);
void setDeviceSecret(const String& hex64);
void setDeviceId(const String& id);
void setCloud(const String& url, const String& anonKey);

// --- trusted phone keys, pinned for the offline LAN path (PROTOCOL §5) ------

uint8_t trustedKeyCount();
String  trustedKey(uint8_t index);

/**
 * Replace the pinned set with what the server just sent.
 *
 * Wholesale replacement rather than merge is deliberate: revoking a lost phone
 * has to actually remove its key from the controller, and a merge would keep
 * honouring it forever.
 */
void setTrustedKeys(const String* keys, uint8_t count);

/** True once a device id and secret exist — i.e. this chip has been provisioned. */
bool provisioned();

void dump();  // prints config to serial, with the secret redacted

}  // namespace store
