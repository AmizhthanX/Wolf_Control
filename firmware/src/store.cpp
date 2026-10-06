#include "store.h"
#include "wolf_crypto.h"

#include <Preferences.h>

#if __has_include("credentials.h")
#include "credentials.h"
#else
#warning "firmware/include/credentials.h not found - copy credentials.example.h and fill it in"
#define CFG_WIFI_SSID ""
#define CFG_WIFI_PASSWORD ""
#define CFG_DEVICE_ID ""
#define CFG_DEVICE_SECRET ""
#define CFG_SUPABASE_URL ""
#define CFG_SUPABASE_ANON_KEY ""
#endif

namespace store {

static Preferences prefs;
static Preferences backup;
static const char* NS = "wolfpc";

static String get(const char* key, const String& fallback = String()) {
  return prefs.isKey(key) ? prefs.getString(key, fallback) : fallback;
}

// Keep a second copy in a separate NVS namespace. Never restore phone keys:
// resurrecting a revoked key would bypass revocation.
static bool saveString(const char* key, const String& value) {
  if (get(key) != value || !prefs.isKey(key)) {
    prefs.putString(key, value);
    if (!prefs.isKey(key) || prefs.getString(key) != value) {
      Serial.printf("[store] write failed: %s\n", key);
      return false;
    }
  }
  if (!backup.isKey(key) || backup.getString(key) != value) {
    backup.putString(key, value);
    if (!backup.isKey(key) || backup.getString(key) != value) {
      Serial.printf("[store] backup write failed: %s\n", key);
      return false;
    }
  }
  return true;
}

static void recoverString(const char* key, const char* compiled, bool allowEmpty = false) {
  String value = get(key);
  const bool valid = prefs.isKey(key) && (allowEmpty || value.length() > 0);
  if (!valid) {
    value = backup.isKey(key) ? backup.getString(key) : String(compiled);
    if (!allowEmpty && value.length() == 0) return;
    if (saveString(key, value)) Serial.printf("[store] recovered: %s\n", key);
  } else {
    saveString(key, value);
  }
}

static bool saveSecret(const uint8_t* secret) {
  if (prefs.putBytes("secret", secret, 32) != 32) {
    Serial.println("[store] secret write failed");
    return false;
  }
  if (backup.putBytes("secret", secret, 32) != 32) {
    Serial.println("[store] secret backup write failed");
    return false;
  }
  return true;
}

void begin() {
  if (!prefs.begin(NS, false) || !backup.begin("wolfpc_backup", false)) {
    Serial.println("[store] could not open configuration storage");
    return;
  }
  // Repair individual missing fields even if the old seeded flag survived.
  // Existing values win, so serial updates are never reset to build defaults.
  recoverString("ssid", CFG_WIFI_SSID);
  recoverString("pass", CFG_WIFI_PASSWORD, true);
  recoverString("devid", CFG_DEVICE_ID);
  recoverString("url", CFG_SUPABASE_URL);
  recoverString("anon", CFG_SUPABASE_ANON_KEY);

  uint8_t secret[32]{};
  if (prefs.getBytesLength("secret") == 32 && prefs.getBytes("secret", secret, 32) == 32) {
    uint8_t saved[32]{};
    if (backup.getBytesLength("secret") != 32 ||
        backup.getBytes("secret", saved, 32) != 32 || memcmp(secret, saved, 32) != 0) {
      if (backup.putBytes("secret", secret, 32) != 32)
        Serial.println("[store] secret backup write failed");
    }
    memset(saved, 0, sizeof(saved));
  } else if (backup.getBytesLength("secret") == 32 && backup.getBytes("secret", secret, 32) == 32) {
    if (saveSecret(secret)) Serial.println("[store] recovered secret from backup");
  } else if (!backup.isKey("secret") && strlen(CFG_DEVICE_SECRET) == 64 &&
             wolfcrypto::fromHex(CFG_DEVICE_SECRET, secret, sizeof(secret))) {
    if (saveSecret(secret)) Serial.println("[store] recovered secret from build configuration");
  } else {
    Serial.println("[store] secret unavailable; provisioning required");
  }
  memset(secret, 0, sizeof(secret));
}

String wifiSsid()        { return get("ssid"); }
String wifiPassword()    { return get("pass"); }
String deviceId()        { return get("devid"); }
String supabaseUrl()     { return get("url"); }
String supabaseAnonKey() { return get("anon"); }

void setWifi(const String& ssid, const String& password) {
  saveString("ssid", ssid);
  saveString("pass", password);
}

void setDeviceId(const String& id) { saveString("devid", id); }

void setCloud(const String& url, const String& anonKey) {
  saveString("url", url);
  saveString("anon", anonKey);
}

bool deviceSecret(uint8_t out[32]) {
  return prefs.getBytesLength("secret") == 32 && prefs.getBytes("secret", out, 32) == 32;
}

void setDeviceSecret(const String& hex64) {
  uint8_t secret[32];
  if (!wolfcrypto::fromHex(hex64, secret, sizeof(secret))) {
    Serial.println("[store] device secret must be 64 hex characters");
    return;
  }
  const bool saved = saveSecret(secret);
  memset(secret, 0, sizeof(secret));
  Serial.println(saved ? "[store] device secret updated and backed up" : "[store] device secret save incomplete");
}

uint8_t trustedKeyCount() {
  const int n = prefs.getUChar("keyn", 0);
  return (uint8_t)(n > MAX_TRUSTED_KEYS ? MAX_TRUSTED_KEYS : n);
}

String trustedKey(uint8_t index) {
  if (index >= trustedKeyCount()) return String();
  char key[8];
  snprintf(key, sizeof(key), "key%u", (unsigned)index);
  return get(key);
}

void setTrustedKeys(const String* keys, uint8_t count) {
  if (count > MAX_TRUSTED_KEYS) count = MAX_TRUSTED_KEYS;

  // Skip the write when nothing changed. NVS is flash, and this is called on
  // every poll — a needless write every two seconds would wear the sector out
  // in weeks.
  bool same = (count == trustedKeyCount());
  if (same) {
    for (uint8_t i = 0; i < count; i++) {
      if (trustedKey(i) != keys[i]) { same = false; break; }
    }
  }
  if (same) return;

  const uint8_t previous = trustedKeyCount();
  char key[8];
  for (uint8_t i = 0; i < count; i++) {
    snprintf(key, sizeof(key), "key%u", (unsigned)i);
    prefs.putString(key, keys[i]);
  }
  for (uint8_t i = count; i < previous; i++) {
    snprintf(key, sizeof(key), "key%u", (unsigned)i);
    prefs.remove(key);
  }
  prefs.putUChar("keyn", count);
  Serial.printf("[store] pinned %u trusted phone key(s)\n", (unsigned)count);
}

bool provisioned() {
  uint8_t secret[32];
  return deviceId().length() > 0 && deviceSecret(secret);
}

void dump() {
  Serial.println("---- wolfpc config ----");
  Serial.printf("  device id    : %s\n", deviceId().c_str());
  Serial.printf("  wifi ssid    : %s\n", wifiSsid().c_str());
  Serial.printf("  supabase url : %s\n", supabaseUrl().c_str());
  uint8_t s[32];
  Serial.printf("  device secret: %s\n", deviceSecret(s) ? "set (hidden)" : "MISSING");
  Serial.printf("  trusted keys : %u\n", (unsigned)trustedKeyCount());
  Serial.printf("  firmware     : %s\n", WOLF_FIRMWARE_VERSION);
  Serial.println("-----------------------");
}

}  // namespace store
