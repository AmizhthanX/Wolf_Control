#include "wolf_crypto.h"

#include <esp_system.h>
#include <mbedtls/md.h>
#include <mbedtls/pk.h>

namespace wolfcrypto {

static const char* kHexDigits = "0123456789abcdef";

String toHex(const uint8_t* data, size_t len) {
  String out;
  out.reserve(len * 2);
  for (size_t i = 0; i < len; i++) {
    out += kHexDigits[data[i] >> 4];
    out += kHexDigits[data[i] & 0x0f];
  }
  return out;
}

static int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

bool fromHex(const String& hex, uint8_t* out, size_t outLen) {
  if (hex.length() != outLen * 2) return false;
  for (size_t i = 0; i < outLen; i++) {
    const int hi = hexVal(hex[i * 2]);
    const int lo = hexVal(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
}

int b64urlDecode(const String& in, uint8_t* out, size_t outCap) {
  auto val = [](char c) -> int {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
  };

  uint32_t acc = 0;
  int bits = 0;
  size_t written = 0;

  for (size_t i = 0; i < in.length(); i++) {
    const char c = in[i];
    if (c == '=' ) continue;        // tolerate padding even though we never emit it
    const int v = val(c);
    if (v < 0) return -1;
    acc = (acc << 6) | (uint32_t)v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      if (written >= outCap) return -1;
      out[written++] = (uint8_t)((acc >> bits) & 0xff);
    }
  }
  return (int)written;
}

String sha256Hex(const uint8_t* data, size_t len) {
  uint8_t digest[32];
  const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == nullptr || mbedtls_md(info, data, len, digest) != 0) return String();
  return toHex(digest, sizeof(digest));
}

String sha256Hex(const String& s) {
  return sha256Hex((const uint8_t*)s.c_str(), s.length());
}

String hmacSha256Hex(const uint8_t* key, size_t keyLen, const String& message) {
  uint8_t mac[32];
  const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == nullptr) return String();

  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  String out;
  if (mbedtls_md_setup(&ctx, info, 1) == 0 &&
      mbedtls_md_hmac_starts(&ctx, key, keyLen) == 0 &&
      mbedtls_md_hmac_update(&ctx, (const uint8_t*)message.c_str(), message.length()) == 0 &&
      mbedtls_md_hmac_finish(&ctx, mac) == 0) {
    out = toHex(mac, sizeof(mac));
  }
  mbedtls_md_free(&ctx);
  return out;
}

/**
 * Re-encode a 64-byte r||s signature as the DER SEQUENCE that mbedtls_pk_verify
 * expects.
 *
 * Going through pk_verify rather than poking at the ecp_keypair struct keeps
 * this file working across both mbedTLS 2.x and 3.x, where those struct fields
 * moved behind MBEDTLS_PRIVATE().
 *
 * The fiddly part is that DER INTEGERs are signed: a value whose top bit is set
 * needs a leading zero byte, or it would be read as negative.
 */
static int p1363ToDer(const uint8_t* raw, uint8_t* der, size_t derCap) {
  auto emitInt = [](const uint8_t* v, uint8_t* out) -> int {
    size_t off = 0;
    while (off < 31 && v[off] == 0) off++;      // strip leading zeros
    const size_t len = 32 - off;
    const bool pad = (v[off] & 0x80) != 0;
    out[0] = 0x02;
    out[1] = (uint8_t)(len + (pad ? 1 : 0));
    size_t i = 2;
    if (pad) out[i++] = 0x00;
    memcpy(out + i, v + off, len);
    return (int)(i + len);
  };

  uint8_t rPart[36], sPart[36];
  const int rLen = emitInt(raw, rPart);
  const int sLen = emitInt(raw + 32, sPart);
  const size_t body = (size_t)(rLen + sLen);

  // A P-256 signature body is always well under 128 bytes, so the SEQUENCE
  // length is a single short-form byte.
  if (body + 2 > derCap || body > 127) return -1;

  der[0] = 0x30;
  der[1] = (uint8_t)body;
  memcpy(der + 2, rPart, (size_t)rLen);
  memcpy(der + 2 + rLen, sPart, (size_t)sLen);
  return (int)(body + 2);
}

bool verifyEcdsaP256(const String& spkiB64url, const String& sigB64url, const String& message) {
  uint8_t spki[160];
  const int spkiLen = b64urlDecode(spkiB64url, spki, sizeof(spki));
  if (spkiLen <= 0) return false;

  uint8_t rawSig[64];
  const int sigLen = b64urlDecode(sigB64url, rawSig, sizeof(rawSig));
  if (sigLen != 64) return false;

  uint8_t der[80];
  const int derLen = p1363ToDer(rawSig, der, sizeof(der));
  if (derLen <= 0) return false;

  uint8_t hash[32];
  const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == nullptr) return false;
  if (mbedtls_md(info, (const uint8_t*)message.c_str(), message.length(), hash) != 0) {
    return false;
  }

  mbedtls_pk_context pk;
  mbedtls_pk_init(&pk);
  bool ok = false;
  if (mbedtls_pk_parse_public_key(&pk, spki, (size_t)spkiLen) == 0 &&
      mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECDSA)) {
    ok = mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, sizeof(hash),
                           der, (size_t)derLen) == 0;
  }
  mbedtls_pk_free(&pk);
  return ok;
}

bool timingSafeEqual(const String& a, const String& b) {
  unsigned diff = (unsigned)(a.length() ^ b.length());
  const size_t n = a.length() > b.length() ? a.length() : b.length();
  for (size_t i = 0; i < n; i++) {
    const uint8_t ca = i < a.length() ? (uint8_t)a[i] : 0;
    const uint8_t cb = i < b.length() ? (uint8_t)b[i] : 0;
    diff |= (unsigned)(ca ^ cb);
  }
  return diff == 0;
}

void randomBytes(uint8_t* out, size_t len) {
  // esp_fill_random draws from the hardware RNG, which is properly seeded once
  // the radio is up — which it always is by the time we send a request.
  esp_fill_random(out, len);
}

String randomHex(size_t bytes) {
  uint8_t buf[32];
  if (bytes > sizeof(buf)) bytes = sizeof(buf);
  randomBytes(buf, bytes);
  return toHex(buf, bytes);
}

}  // namespace wolfcrypto
