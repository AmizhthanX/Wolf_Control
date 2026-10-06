#pragma once
#include <Arduino.h>

// Crypto helpers, all on top of the mbedTLS that ships with the ESP32 core.
// The encodings match PROTOCOL.md exactly; if one of these functions disagrees
// with the Edge Functions, nothing authenticates.

namespace wolfcrypto {

// Lowercase hex, both directions.
String toHex(const uint8_t* data, size_t len);
bool   fromHex(const String& hex, uint8_t* out, size_t outLen);

// base64url, no padding. Returns the number of bytes written, or -1.
int b64urlDecode(const String& in, uint8_t* out, size_t outCap);

String sha256Hex(const uint8_t* data, size_t len);
String sha256Hex(const String& s);

String hmacSha256Hex(const uint8_t* key, size_t keyLen, const String& message);

/**
 * Verify an ECDSA P-256 / SHA-256 signature.
 *
 * @param spkiB64url  the phone's public key, base64url SPKI, as stored in
 *                    device_keys.public_key
 * @param sigB64url   base64url of the 64-byte r||s (IEEE P1363) signature
 * @param message     the exact signing string (PROTOCOL §2)
 *
 * Returns false on any malformed input rather than throwing, because every
 * caller is a request handler that should answer 401 and move on.
 */
bool verifyEcdsaP256(const String& spkiB64url, const String& sigB64url, const String& message);

/** Comparison whose duration does not depend on where the first difference is. */
bool timingSafeEqual(const String& a, const String& b);

/** Hardware RNG. Used for request nonces. */
void randomBytes(uint8_t* out, size_t len);
String randomHex(size_t bytes);

}  // namespace wolfcrypto
