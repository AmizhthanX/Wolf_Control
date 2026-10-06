// Crypto primitives shared by every Edge Function.
//
// Only WebCrypto is used — no third-party crypto in the request path. The
// encodings here are the ones PROTOCOL.md pins down, and the ESP32, the agent
// and the Android app all have to agree with them byte for byte.

const enc = new TextEncoder();

export function toHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function fromHex(hex: string): Uint8Array {
  if (hex.length % 2 !== 0 || /[^0-9a-fA-F]/.test(hex)) {
    throw new Error("not hex");
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export function b64urlDecode(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + pad;
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64Decode(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export async function sha256Hex(data: string | Uint8Array): Promise<string> {
  const buf = typeof data === "string" ? enc.encode(data) : data;
  // deno-lint-ignore no-explicit-any
  const digest = await crypto.subtle.digest("SHA-256", buf as any);
  return toHex(new Uint8Array(digest));
}

/** Length-independent comparison, so a wrong signature leaks no timing. */
export function timingSafeEqual(a: string, b: string): boolean {
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  // Fold the length difference into the result instead of returning early.
  let diff = ab.length ^ bb.length;
  const n = Math.max(ab.length, bb.length);
  for (let i = 0; i < n; i++) {
    diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  }
  return diff === 0;
}

export async function hmacSha256Hex(secret: Uint8Array, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    // deno-lint-ignore no-explicit-any
    secret as any,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  // deno-lint-ignore no-explicit-any
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message) as any);
  return toHex(new Uint8Array(sig));
}

/**
 * Verify an ECDSA P-256 / SHA-256 signature made by the phone's Keystore key.
 *
 * `publicKeySpki` is base64url-encoded SPKI (what Android's
 * `PublicKey.getEncoded()` returns) and `signatureP1363` is base64url of the
 * 64-byte r||s form. Android produces DER, so the app converts before sending;
 * WebCrypto only accepts P1363, which is why the protocol standardises on it.
 */
export async function verifyEcdsaP256(
  publicKeySpki: string,
  signatureP1363: string,
  message: string,
): Promise<boolean> {
  let key: CryptoKey;
  let sig: Uint8Array;
  try {
    key = await crypto.subtle.importKey(
      "spki",
      // deno-lint-ignore no-explicit-any
      b64urlDecode(publicKeySpki) as any,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    sig = b64urlDecode(signatureP1363);
  } catch {
    return false;
  }
  if (sig.length !== 64) return false;
  try {
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      // deno-lint-ignore no-explicit-any
      sig as any,
      // deno-lint-ignore no-explicit-any
      enc.encode(message) as any,
    );
  } catch {
    return false;
  }
}

/**
 * Unwrap a device secret stored in `device_secrets`.
 *
 * The wrapping key lives in the WOLF_DEVICE_KEY function secret and never in
 * Postgres, so a database dump on its own yields no usable device credential
 * (PRD §10).
 */
export async function decryptDeviceSecret(
  ciphertextB64: string,
  ivB64: string,
): Promise<Uint8Array> {
  const raw = Deno.env.get("WOLF_DEVICE_KEY");
  if (!raw) throw new Error("WOLF_DEVICE_KEY is not set");
  const keyBytes = b64Decode(raw);
  if (keyBytes.length !== 32) throw new Error("WOLF_DEVICE_KEY must be 32 bytes, base64");

  const key = await crypto.subtle.importKey(
    "raw",
    // deno-lint-ignore no-explicit-any
    keyBytes as any,
    { name: "AES-GCM" },
    false,
    ["decrypt"],
  );
  const plain = await crypto.subtle.decrypt(
    // deno-lint-ignore no-explicit-any
    { name: "AES-GCM", iv: b64Decode(ivB64) as any },
    key,
    // deno-lint-ignore no-explicit-any
    b64Decode(ciphertextB64) as any,
  );
  return new Uint8Array(plain);
}

export async function encryptDeviceSecret(
  secret: Uint8Array,
): Promise<{ ciphertext: string; iv: string }> {
  const raw = Deno.env.get("WOLF_DEVICE_KEY");
  if (!raw) throw new Error("WOLF_DEVICE_KEY is not set");
  const key = await crypto.subtle.importKey(
    "raw",
    // deno-lint-ignore no-explicit-any
    b64Decode(raw) as any,
    { name: "AES-GCM" },
    false,
    ["encrypt"],
  );
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    // deno-lint-ignore no-explicit-any
    secret as any,
  );
  const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
  return { ciphertext: b64(new Uint8Array(ct)), iv: b64(iv) };
}
