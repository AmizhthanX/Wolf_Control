// login-challenge — the Windows credential provider asks the phone to prove
// itself. PRD §15, Phase 8.
//
// The agent creates a challenge when the Wolf tile is selected at the login
// screen, then polls this same endpoint until the phone answers or the window
// closes. Nothing here can log anybody in; it only collects a signature that
// the agent verifies locally before it unseals the credential.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { authenticateDevice } from "../_shared/deviceAuth.ts";
import { b64urlEncode } from "../_shared/crypto.ts";

// Short enough that a shoulder-surfed prompt is useless by the time anyone
// acts on it, long enough to pick up a phone and touch a sensor.
const CHALLENGE_TTL_MS = 60_000;

const serve = handler(async (req) => {
  const device = await authenticateDevice(req, "login-challenge", ["AGENT"]);
  const db = admin();
  const action = device.json.action ?? "create";

  if (action === "create") {
    // One open challenge at a time. Selecting the tile twice should reuse the
    // prompt already on the phone, not stack a second one.
    await db.from("login_challenges")
      .update({ result: "EXPIRED" })
      .eq("pc_id", device.pcId)
      .eq("result", "PENDING");

    const challenge = b64urlEncode(crypto.getRandomValues(new Uint8Array(32)));
    const expiresAt = new Date(Date.now() + CHALLENGE_TTL_MS).toISOString();

    const { data, error } = await db.from("login_challenges").insert({
      pc_id: device.pcId,
      challenge,
      expires_at: expiresAt,
    }).select("id, challenge, expires_at").single();

    if (error) {
      console.error("challenge insert failed", error.message);
      fail("internal", "could not create challenge");
    }

    await audit({ deviceId: device.id, pcId: device.pcId,
                  action: "login:challenge", result: "ok", req });

    return json({ challenge_id: data.id, challenge: data.challenge, expires_at: data.expires_at });
  }

  if (action === "poll") {
    const id = device.json.challenge_id;
    if (typeof id !== "string") fail("bad_request", "challenge_id required");

    const { data, error } = await db
      .from("login_challenges")
      .select("id, challenge, result, signature, responder_device_id, expires_at, used_at")
      .eq("id", id)
      .eq("pc_id", device.pcId)
      .maybeSingle();

    if (error) fail("internal", "challenge lookup failed");
    if (!data) fail("not_found", "no such challenge");

    let result = data.result;
    if (result === "PENDING" && Date.parse(data.expires_at) < Date.now()) {
      result = "EXPIRED";
      await db.from("login_challenges").update({ result }).eq("id", id);
    }

    // The agent needs the responder's public key to verify the signature
    // itself. That is the whole point: the cloud relays the answer, it does
    // not get to assert that the answer was valid.
    let publicKey: string | null = null;
    if (result === "APPROVED" && data.responder_device_id) {
      const { data: key } = await db
        .from("device_keys")
        .select("public_key")
        .eq("device_id", data.responder_device_id)
        .is("revoked_at", null)
        .maybeSingle();
      publicKey = key?.public_key ?? null;
    }

    return json({
      challenge_id: data.id,
      challenge: data.challenge,
      result,
      signature: result === "APPROVED" ? data.signature : null,
      public_key: publicKey,
      responder_device_id: data.responder_device_id,
    });
  }

  if (action === "cancel") {
    const id = device.json.challenge_id;
    if (typeof id !== "string") fail("bad_request", "challenge_id required");
    await db.from("login_challenges")
      .update({ result: "EXPIRED" })
      .eq("id", id)
      .eq("pc_id", device.pcId)
      .eq("result", "PENDING");
    return json({ ok: true });
  }

  fail("bad_request", "action must be create, poll or cancel");
});

Deno.serve(serve);
