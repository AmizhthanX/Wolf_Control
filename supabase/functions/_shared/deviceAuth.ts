// Device HMAC authentication for the ESP32 and the Windows agent.
// PROTOCOL §3.

import { admin } from "./db.ts";
import { fail } from "./http.ts";
import { decryptDeviceSecret, hmacSha256Hex, sha256Hex, timingSafeEqual } from "./crypto.ts";

const SKEW_SECONDS = 120;

export interface AuthedDevice {
  id: string;
  pcId: string;
  type: "CONTROLLER" | "AGENT" | "PHONE";
  ownerId: string;
  status: string;
  body: string;
  // deno-lint-ignore no-explicit-any
  json: any;
}

/**
 * Authenticate a device request and return the device plus its parsed body.
 *
 * The body is read exactly once here and handed back, because the signature
 * covers the raw bytes: re-reading or re-serialising the body would change the
 * hash and break verification.
 */
export async function authenticateDevice(
  req: Request,
  functionName: string,
  allowedTypes: Array<"CONTROLLER" | "AGENT"> = ["CONTROLLER", "AGENT"],
): Promise<AuthedDevice> {
  const deviceId = req.headers.get("x-wolf-device");
  const tsHeader = req.headers.get("x-wolf-timestamp");
  const nonce = req.headers.get("x-wolf-nonce");
  const signature = req.headers.get("x-wolf-signature");

  if (!deviceId || !tsHeader || !nonce || !signature) {
    fail("unauthenticated", "missing device authentication headers");
  }
  if (!/^[0-9a-f]{32}$/.test(nonce)) {
    fail("bad_request", "nonce must be 32 lowercase hex characters");
  }

  const ts = Number(tsHeader);
  if (!Number.isFinite(ts)) fail("bad_request", "bad timestamp");
  const skew = Math.abs(Math.floor(Date.now() / 1000) - ts);
  if (skew > SKEW_SECONDS) {
    // Also the first symptom of an ESP32 whose SNTP never synced.
    fail("expired", `timestamp outside the ${SKEW_SECONDS}s window`);
  }

  const body = await req.text();
  if (body.length > 16 * 1024) fail("bad_request", "body too large");

  const db = admin();
  const { data: device, error: devErr } = await db
    .from("devices")
    .select("id, pc_id, device_type, owner_id, status")
    .eq("id", deviceId)
    .maybeSingle();

  if (devErr) fail("internal", "device lookup failed");
  if (!device) fail("unauthenticated", "unknown device");
  if (device.status === "REVOKED") fail("forbidden", "device revoked");
  if (!allowedTypes.includes(device.device_type)) {
    fail("forbidden", "device type not allowed on this endpoint");
  }
  if (!device.pc_id) fail("forbidden", "device is not attached to a pc");

  const { data: secretRow, error: secErr } = await db
    .from("device_secrets")
    .select("ciphertext, iv")
    .eq("device_id", deviceId)
    .maybeSingle();

  if (secErr) fail("internal", "secret lookup failed");
  if (!secretRow) fail("unauthenticated", "device has no credential");

  const secret = await decryptDeviceSecret(secretRow.ciphertext, secretRow.iv);
  const message = [
    deviceId,
    String(ts),
    nonce,
    req.method.toUpperCase(),
    `/functions/v1/${functionName}`,
    await sha256Hex(body),
  ].join("\n");

  const expected = await hmacSha256Hex(secret, message);
  secret.fill(0);
  if (!timingSafeEqual(expected, signature.toLowerCase())) {
    fail("bad_signature", "device signature did not verify");
  }

  // Burn the nonce. The primary key does the work: a concurrent replay loses
  // the insert race and is rejected, so the check is atomic rather than
  // read-then-write.
  const { error: nonceErr } = await db
    .from("device_nonces")
    .insert({ device_id: deviceId, nonce });
  if (nonceErr) {
    if (nonceErr.code === "23505") fail("replay", "nonce already used");
    fail("internal", "nonce store failed");
  }

  // deno-lint-ignore no-explicit-any
  let parsed: any = {};
  if (body.length > 0) {
    try {
      parsed = JSON.parse(body);
    } catch {
      fail("bad_request", "body is not valid json");
    }
  }

  return {
    id: device.id,
    pcId: device.pc_id,
    type: device.device_type,
    ownerId: device.owner_id,
    status: device.status,
    body,
    json: parsed,
  };
}
