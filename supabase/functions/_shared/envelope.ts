// The signed command envelope: parsing, the canonical signing string, and
// verification against a phone's registered public key. PROTOCOL §2.

import { fail } from "./http.ts";
import { verifyEcdsaP256 } from "./crypto.ts";
import { admin } from "./db.ts";

export const COMMANDS = ["POWER_ON", "SHUTDOWN", "RESTART", "HARD_RESET", "LOGIN", "LOCK"] as const;
export type WolfCommand = typeof COMMANDS[number];

/** Which side of the system actually performs each command. PRD §3. */
export const TARGET: Record<WolfCommand, "CONTROLLER" | "AGENT"> = {
  POWER_ON: "CONTROLLER",
  HARD_RESET: "CONTROLLER",
  SHUTDOWN: "AGENT",
  RESTART: "AGENT",
  LOGIN: "AGENT",
  LOCK: "AGENT",
};

/**
 * Maximum permitted lifetime per command, in seconds.
 *
 * HARD_RESET gets the shortest window on purpose: it is the one command that
 * can destroy unsaved work, so a captured envelope should be worthless almost
 * immediately.
 */
export const MAX_LIFETIME_S: Record<WolfCommand, number> = {
  POWER_ON: 30,
  HARD_RESET: 15,
  SHUTDOWN: 60,
  RESTART: 60,
  LOGIN: 60,
  LOCK: 60,
};

export interface Envelope {
  command_id: string;
  device_id: string;
  command: WolfCommand;
  timestamp: string;
  expires_at: string;
  nonce: string;
  signature: string;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const RFC3339_Z = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

// deno-lint-ignore no-explicit-any
export function parseEnvelope(raw: any): Envelope {
  if (!raw || typeof raw !== "object") fail("bad_request", "envelope missing");

  const str = (k: string): string => {
    const v = raw[k];
    if (typeof v !== "string" || v.length === 0 || v.length > 512) {
      fail("bad_request", `envelope field ${k} is missing or malformed`);
    }
    return v;
  };

  const command_id = str("command_id");
  const device_id = str("device_id");
  const command = str("command");
  const timestamp = str("timestamp");
  const expires_at = str("expires_at");
  const nonce = str("nonce");
  const signature = str("signature");

  if (!UUID_RE.test(command_id)) fail("bad_request", "command_id must be a uuid");
  if (!COMMANDS.includes(command as WolfCommand)) fail("bad_request", "unknown command");
  if (!RFC3339_Z.test(timestamp) || !RFC3339_Z.test(expires_at)) {
    fail("bad_request", "timestamps must be RFC3339 UTC with a trailing Z");
  }
  if (!/^[0-9a-f]{16}$/.test(nonce)) fail("bad_request", "nonce must be 16 lowercase hex chars");
  if (device_id.length > 64) fail("bad_request", "device_id too long");

  return {
    command_id,
    device_id,
    command: command as WolfCommand,
    timestamp,
    expires_at,
    nonce,
    signature,
  };
}

/**
 * The exact bytes that get signed. Seven lines, single newlines, no trailing
 * newline. Never derived from re-serialised JSON — key order and whitespace
 * must not be able to change a signature.
 */
export function signingString(e: Envelope): string {
  return [
    "WOLFPC1",
    e.command_id,
    e.device_id,
    e.command,
    e.timestamp,
    e.expires_at,
    e.nonce,
  ].join("\n");
}

/** Reject an envelope whose window is absent, backwards, too long, or over. */
export function assertFreshWindow(e: Envelope, skewSeconds = 120): void {
  const issued = Date.parse(e.timestamp);
  const expires = Date.parse(e.expires_at);
  if (!Number.isFinite(issued) || !Number.isFinite(expires)) {
    fail("bad_request", "unparseable timestamps");
  }
  const now = Date.now();
  if (expires <= issued) fail("bad_request", "expires_at must be after timestamp");

  const lifetime = (expires - issued) / 1000;
  if (lifetime > MAX_LIFETIME_S[e.command]) {
    fail("bad_request", `${e.command} may not live longer than ${MAX_LIFETIME_S[e.command]}s`);
  }
  if (issued - now > skewSeconds * 1000) fail("bad_request", "envelope is from the future");
  if (expires < now) fail("expired", "envelope already expired");
}

export interface VerifiedIssuer {
  deviceId: string;
  publicKey: string;
}

/**
 * Verify the envelope against the phones registered to this user.
 *
 * A user may have more than one paired phone and the envelope does not name
 * which one signed it, so every live key for that user is tried. The count is
 * tiny and each verify is sub-millisecond, and it means the app never has to
 * track which key id it is using.
 */
export async function verifyEnvelopeSignature(
  e: Envelope,
  userId: string,
): Promise<VerifiedIssuer> {
  const { data, error } = await admin()
    .from("device_keys")
    .select("device_id, public_key, devices!inner(owner_id, status, device_type)")
    .is("revoked_at", null)
    .eq("key_type", "ECDSA_P256")
    .eq("devices.owner_id", userId)
    .eq("devices.device_type", "PHONE")
    .eq("devices.status", "PAIRED");

  if (error) fail("internal", "key lookup failed");
  if (!data || data.length === 0) fail("forbidden", "no paired phone key for this account");

  const message = signingString(e);
  for (const row of data) {
    if (await verifyEcdsaP256(row.public_key, e.signature, message)) {
      return { deviceId: row.device_id, publicKey: row.public_key };
    }
  }
  fail("bad_signature", "envelope signature did not verify against any paired phone");
}
