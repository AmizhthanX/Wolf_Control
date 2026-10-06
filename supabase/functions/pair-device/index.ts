// pair-device — redeem the code the agent is showing and register this phone.
// PRD §17.
//
// The phone generates its keypair in the Android Keystore and sends only the
// public half. The private key never exists outside the secure hardware, so
// pairing cannot leak a signing capability even if this request is observed.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { authenticateUser } from "../_shared/userAuth.ts";
import { b64urlDecode } from "../_shared/crypto.ts";

const serve = handler(async (req) => {
  const user = await authenticateUser(req);

  // deno-lint-ignore no-explicit-any
  let body: any;
  try {
    body = await req.json();
  } catch {
    fail("bad_request", "body is not valid json");
  }

  const code = typeof body.code === "string" ? body.code.trim().toUpperCase() : "";
  const publicKey = typeof body.public_key === "string" ? body.public_key : "";
  const deviceName = typeof body.device_name === "string"
    ? body.device_name.slice(0, 64)
    : "Android phone";

  if (!/^WOLF-\d{6}$/.test(code)) fail("bad_request", "pairing code must look like WOLF-123456");

  // A P-256 SPKI is 91 bytes. Checking the shape here keeps junk out of the
  // table and out of the ESP32's pinned-key store, where a bad key would waste
  // NVS and verification time forever.
  try {
    const spki = b64urlDecode(publicKey);
    if (spki.length < 80 || spki.length > 120) throw new Error("size");
  } catch {
    fail("bad_request", "public_key must be base64url SPKI for P-256");
  }

  const db = admin();

  const { data: pairing, error: pErr } = await db
    .from("pairing_codes")
    .select("code, pc_id, expires_at, redeemed_at")
    .eq("code", code)
    .maybeSingle();

  if (pErr) fail("internal", "pairing lookup failed");
  if (!pairing) {
    await audit({ userId: user.id, action: "pair", result: "denied", req,
                  metadata: { reason: "unknown_code" } });
    fail("not_found", "unknown pairing code");
  }
  if (pairing.redeemed_at) fail("conflict", "this pairing code has already been used");
  if (Date.parse(pairing.expires_at) < Date.now()) fail("expired", "pairing code expired");

  const { data: pc, error: pcErr } = await db
    .from("pcs")
    .select("id, owner_id, name")
    .eq("id", pairing.pc_id)
    .maybeSingle();
  if (pcErr) fail("internal", "pc lookup failed");
  if (!pc) fail("not_found", "pc missing");
  if (pc.owner_id !== user.id) {
    // The code is valid but belongs to somebody else's machine. Refuse, and
    // leave a trail — this is what an attempted hijack looks like.
    await audit({ userId: user.id, pcId: pc.id, action: "pair", result: "denied", req,
                  metadata: { reason: "wrong_owner" } });
    fail("forbidden", "that pc belongs to another account");
  }

  const deviceId = `phone-${crypto.randomUUID()}`;
  const now = new Date().toISOString();

  const { error: devErr } = await db.from("devices").insert({
    id: deviceId,
    pc_id: pc.id,
    owner_id: user.id,
    name: deviceName,
    device_type: "PHONE",
    status: "PAIRED",
    paired_at: now,
    metadata: { platform: "android" },
  });
  if (devErr) {
    console.error("phone insert failed", devErr.message);
    fail("internal", "could not register phone");
  }

  const { error: keyErr } = await db.from("device_keys").insert({
    device_id: deviceId,
    public_key: publicKey,
    key_type: "ECDSA_P256",
  });
  if (keyErr) {
    // Roll back the half-made device rather than leaving a phone that is
    // paired but can never sign anything.
    await db.from("devices").delete().eq("id", deviceId);
    fail("internal", "could not register key");
  }

  await db.from("pairing_codes")
    .update({ redeemed_at: now, redeemed_by: user.id })
    .eq("code", code);

  await db.from("notification_preferences")
    .upsert({ user_id: user.id }, { onConflict: "user_id" });

  await audit({ userId: user.id, deviceId, pcId: pc.id, action: "pair", result: "ok", req });

  return json({
    device_id: deviceId,
    pc_id: pc.id,
    pc_name: pc.name,
    paired_at: now,
  });
});

Deno.serve(serve);
