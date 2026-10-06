// enqueue-command — the phone asks for something to happen.
//
// This is the only way a command row is ever created. The Android app cannot
// insert into `commands` directly (RLS denies it), because only this function
// can check the biometric-backed ECDSA signature first. A stolen Supabase
// session with no phone key is therefore not enough to power-cycle the PC.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { wakeAgent } from "../_shared/commandWake.ts";
import { assertOwnsPc, authenticateUser } from "../_shared/userAuth.ts";
import {
  assertFreshWindow,
  parseEnvelope,
  TARGET,
  verifyEnvelopeSignature,
} from "../_shared/envelope.ts";

const MAX_COMMANDS_PER_MINUTE = 20;

const serve = handler(async (req) => {
  const user = await authenticateUser(req);

  // deno-lint-ignore no-explicit-any
  let body: any;
  try {
    body = await req.json();
  } catch {
    fail("bad_request", "body is not valid json");
  }

  const envelope = parseEnvelope(body.envelope ?? body);
  const pcId = envelope.device_id;
  const target = TARGET[envelope.command];

  // Cheap checks before the expensive ones: window, then ownership, then rate,
  // then the signature.
  assertFreshWindow(envelope);
  await assertOwnsPc(user.id, pcId);

  const db = admin();

  const { data: rate, error: rateErr } = await db.rpc("wolf_recent_command_count", {
    uid: user.id,
    window_secs: 60,
  });
  if (rateErr) fail("internal", "rate check failed");
  if ((rate ?? 0) >= MAX_COMMANDS_PER_MINUTE) {
    await audit({
      userId: user.id,
      pcId,
      action: `enqueue:${envelope.command}`,
      result: "denied",
      req,
      metadata: { reason: "rate_limited" },
    });
    fail("rate_limited", "too many commands, slow down");
  }

  const issuer = await verifyEnvelopeSignature(envelope, user.id);

  // The command must have somewhere to go. Refusing here gives the app a clean
  // "agent not installed" rather than a command that silently rots to EXPIRED.
  const { data: targetDevice, error: tErr } = await db
    .from("devices")
    .select("id, status")
    .eq("pc_id", pcId)
    .eq("device_type", target)
    .maybeSingle();
  if (tErr) fail("internal", "target lookup failed");
  if (!targetDevice) {
    fail("not_found", `no ${target.toLowerCase()} is registered for this pc`);
  }
  if (targetDevice.status !== "PAIRED") fail("forbidden", `${target} is not paired`);

  const { error: insErr } = await db.from("commands").insert({
    id: envelope.command_id,
    pc_id: pcId,
    user_id: user.id,
    issuer_device_id: issuer.deviceId,
    target,
    command: envelope.command,
    status: "QUEUED",
    nonce: envelope.nonce,
    signature: envelope.signature,
    issued_at: envelope.timestamp,
    expires_at: envelope.expires_at,
  });

  if (insErr) {
    // 23505 is a unique violation: either this command_id or this
    // (phone, nonce) pair has been seen. Both mean a replay.
    if (insErr.code === "23505") {
      await audit({
        userId: user.id,
        deviceId: issuer.deviceId,
        pcId,
        action: `enqueue:${envelope.command}`,
        result: "denied",
        req,
        metadata: { reason: "replay" },
      });
      fail("replay", "this command has already been submitted");
    }
    console.error("command insert failed", insErr.message);
    fail("internal", "could not queue command");
  }

  if (target === "AGENT") await wakeAgent(targetDevice.id, envelope.command_id);

  await audit({
    userId: user.id,
    deviceId: issuer.deviceId,
    pcId,
    action: `enqueue:${envelope.command}`,
    result: "ok",
    req,
    metadata: { command_id: envelope.command_id, target },
  });

  return json({
    command_id: envelope.command_id,
    status: "QUEUED",
    target,
    expires_at: envelope.expires_at,
  });
});

Deno.serve(serve);
