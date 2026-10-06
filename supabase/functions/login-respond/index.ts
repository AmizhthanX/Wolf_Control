// login-respond — the phone answers a Windows login challenge. PRD §15.
//
// The signature is produced by a Keystore key that requires a fresh biometric
// for every single operation, so a signature here is evidence that a fingerprint
// was presented to *this* phone within the last few seconds.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { authenticateUser } from "../_shared/userAuth.ts";
import { verifyEcdsaP256 } from "../_shared/crypto.ts";

/** Distinct prefix from the command envelope, so neither can be replayed as the other. */
function loginSigningString(challengeId: string, pcId: string, challenge: string): string {
  return ["WOLFLOGIN1", challengeId, pcId, challenge].join("\n");
}

const serve = handler(async (req) => {
  const user = await authenticateUser(req);

  // deno-lint-ignore no-explicit-any
  let body: any;
  try {
    body = await req.json();
  } catch {
    fail("bad_request", "body is not valid json");
  }

  const challengeId = body.challenge_id;
  const decision = body.decision === "DENY" ? "DENIED" : "APPROVED";
  const signature = body.signature;

  if (typeof challengeId !== "string") fail("bad_request", "challenge_id required");

  const db = admin();

  const { data: ch, error: chErr } = await db
    .from("login_challenges")
    .select("id, pc_id, challenge, result, expires_at, pcs!inner(owner_id)")
    .eq("id", challengeId)
    .maybeSingle();

  if (chErr) fail("internal", "challenge lookup failed");
  if (!ch) fail("not_found", "no such challenge");
  // deno-lint-ignore no-explicit-any
  if ((ch as any).pcs.owner_id !== user.id) fail("forbidden", "not your pc");
  if (ch.result !== "PENDING") fail("conflict", "challenge is no longer open");
  if (Date.parse(ch.expires_at) < Date.now()) {
    await db.from("login_challenges").update({ result: "EXPIRED" }).eq("id", challengeId);
    fail("expired", "challenge expired");
  }

  if (decision === "DENIED") {
    await db.from("login_challenges")
      .update({ result: "DENIED", used_at: new Date().toISOString() })
      .eq("id", challengeId);
    await audit({ userId: user.id, pcId: ch.pc_id, action: "login:respond",
                  result: "denied", req, metadata: { decision: "DENIED" } });
    return json({ result: "DENIED" });
  }

  if (typeof signature !== "string") fail("bad_request", "signature required to approve");

  const message = loginSigningString(ch.id, ch.pc_id, ch.challenge);

  const { data: keys } = await db
    .from("device_keys")
    .select("device_id, public_key, devices!inner(owner_id, device_type, status)")
    .is("revoked_at", null)
    .eq("devices.owner_id", user.id)
    .eq("devices.device_type", "PHONE")
    .eq("devices.status", "PAIRED");

  let responder: string | null = null;
  for (const k of keys ?? []) {
    if (await verifyEcdsaP256(k.public_key, signature, message)) {
      responder = k.device_id;
      break;
    }
  }

  if (!responder) {
    await audit({ userId: user.id, pcId: ch.pc_id, action: "login:respond",
                  result: "denied", req, metadata: { reason: "bad_signature" } });
    fail("bad_signature", "signature did not verify against any paired phone");
  }

  // Claim the challenge only if it is still PENDING. Two phones answering at
  // once must not both count as the approval.
  const { data: updated, error: upErr } = await db
    .from("login_challenges")
    .update({
      result: "APPROVED",
      signature,
      responder_device_id: responder,
      used_at: new Date().toISOString(),
    })
    .eq("id", challengeId)
    .eq("result", "PENDING")
    .select("id")
    .maybeSingle();

  if (upErr) fail("internal", "could not record response");
  if (!updated) fail("conflict", "challenge was already answered");

  await audit({ userId: user.id, deviceId: responder, pcId: ch.pc_id,
                action: "login:respond", result: "ok", req });

  return json({ result: "APPROVED", challenge_id: challengeId });
});

Deno.serve(serve);
