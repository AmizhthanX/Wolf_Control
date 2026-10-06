// device-poll — the ESP32 and the Windows agent ask "anything for me?"
//
// One call does three things: heartbeat, claim work, and refresh the set of
// phone keys the device trusts. Folding them together matters because the
// controller polls every 2 s (PRD §22) and every extra round trip is another
// TLS handshake on a 240 MHz chip.

import { handler, json } from "../_shared/http.ts";
import { admin } from "../_shared/db.ts";
import { authenticateDevice } from "../_shared/deviceAuth.ts";

const DEFAULT_POLL_MS = Number(Deno.env.get("WOLF_POLL_INTERVAL_MS") ?? "2000");
const MAX_BATCH = 4;

const serve = handler(async (req) => {
  const device = await authenticateDevice(req, "device-poll");
  const db = admin();

  const meta: Record<string, unknown> = {};
  if (typeof device.json.uptime_s === "number") meta.uptime_s = device.json.uptime_s;
  if (typeof device.json.rssi === "number") meta.rssi = device.json.rssi;
  if (typeof device.json.relay_state === "string") meta.relay_state = device.json.relay_state;
  if (typeof device.json.ip === "string") meta.ip = device.json.ip;

  // The controller already reports its IP with every poll. Persisting it where
  // the app can read it is what lets the phone dial the ESP32 by address
  // instead of by mDNS name — which matters because a phone with strict
  // Private DNS resolves nothing at all, .local included, once the internet is
  // down. That is exactly when the LAN path is supposed to work.
  if (device.type === "CONTROLLER" && typeof device.json.ip === "string") {
    const ip = device.json.ip.slice(0, 45);
    if (ip !== "0.0.0.0") {
      await db.from("pc_status")
        .update({ controller_lan_address: ip })
        .eq("pc_id", device.pcId);
    }
  }

  await db.rpc("wolf_heartbeat", {
    dev_id: device.id,
    fw: typeof device.json.firmware_version === "string"
      ? device.json.firmware_version
      : null,
    meta,
  });

  const { data: claimed, error: claimErr } = await db.rpc("wolf_claim_commands", {
    target_pc: device.pcId,
    target_role: device.type,
    max_commands: MAX_BATCH,
  });
  if (claimErr) {
    console.error("claim failed", claimErr.message);
    return json({ error: { code: "internal", message: "claim failed" } }, 500);
  }

  // Ship each envelope with the public key that signed it, so the device can
  // verify end to end rather than trusting this function's say-so. If the
  // control plane were ever compromised, a forged command still would not
  // carry a valid phone signature.
  const rows = claimed ?? [];
  const issuerIds = [...new Set(rows.map((r: { issuer_device_id: string }) => r.issuer_device_id).filter(Boolean))];
  const keyByDevice = new Map<string, string>();

  if (issuerIds.length > 0) {
    const { data: keys } = await db
      .from("device_keys")
      .select("device_id, public_key")
      .in("device_id", issuerIds)
      .is("revoked_at", null);
    for (const k of keys ?? []) keyByDevice.set(k.device_id, k.public_key);
  }

  // deno-lint-ignore no-explicit-any
  const commands = rows.map((r: any) => ({
    command_id: r.id,
    device_id: r.pc_id,
    command: r.command,
    timestamp: new Date(r.issued_at).toISOString().replace(/\.\d{3}Z$/, "Z"),
    expires_at: new Date(r.expires_at).toISOString().replace(/\.\d{3}Z$/, "Z"),
    nonce: r.nonce,
    signature: r.signature,
    public_key: keyByDevice.get(r.issuer_device_id) ?? null,
  }));

  // Every device that verifies a signature needs these, which is both of them:
  // the controller for POWER_ON and HARD_RESET, the agent for SHUTDOWN,
  // RESTART and LOGIN. Neither takes this function's word that a command is
  // genuine, so neither can work without knowing which keys to trust.
  //
  // They also serve the offline LAN path (PROTOCOL §5): with no internet a
  // device cannot ask the cloud who to trust, so it has to already know.
  const { data: keys } = await db
    .from("device_keys")
    .select("public_key, devices!inner(pc_id, device_type, status)")
    .is("revoked_at", null)
    .eq("devices.pc_id", device.pcId)
    .eq("devices.device_type", "PHONE")
    .eq("devices.status", "PAIRED");

  const trustedKeys = (keys ?? []).map((k: { public_key: string }) => k.public_key);

  return json({
    server_time: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    poll_interval_ms: DEFAULT_POLL_MS,
    commands,
    trusted_keys: trustedKeys,
  });
});

Deno.serve(serve);
