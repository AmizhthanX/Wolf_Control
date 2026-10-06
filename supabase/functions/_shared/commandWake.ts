import { admin } from "./db.ts";
import { decryptDeviceSecret, hmacSha256Hex, sha256Hex } from "./crypto.ts";

// No action, username, credentials or command signature is broadcast.
// This HMAC hint only accelerates the usual independently verified poll.
export async function wakeAgent(deviceId: string, commandId: string): Promise<void> {
  try {
    const { data, error } = await admin().from("device_secrets")
      .select("ciphertext, iv").eq("device_id", deviceId).single();
    if (error || !data) throw new Error("wake credential unavailable");
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    const secret = await decryptDeviceSecret(data.ciphertext, data.iv);
    let signature: string;
    try {
      signature = await hmacSha256Hex(secret,
        ["WOLFWAKE1", deviceId, commandId, expiresAt].join("\n"));
    } finally { secret.fill(0); }
    const topic = "wolfpc-wake:" + await sha256Hex(deviceId);
    const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const res = await fetch(Deno.env.get("SUPABASE_URL") + "/realtime/v1/api/broadcast", {
      method: "POST",
      headers: { apikey: key, authorization: "Bearer " + key, "content-type": "application/json" },
      body: JSON.stringify({ messages: [{
        topic, event: "wake", private: false,
        payload: { command_id: commandId, expires_at: expiresAt, signature },
      }] }),
      signal: AbortSignal.timeout(2500),
    });
    if (!res.ok) throw new Error("broadcast rejected");
    await res.body?.cancel();
  } catch {
    // A lost hint must not turn a durable enqueue into an error.
    console.warn("agent wake unavailable; periodic polling will deliver the command");
  }
}
