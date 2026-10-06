// Service-role Postgres access plus audit logging.
//
// Every function in this project runs with the service role and enforces its
// own authorisation, because the two callers that matter — the ESP32 and the
// agent — have no Supabase JWT at all; they authenticate with a device HMAC.
// RLS still protects the direct PostgREST path the Android app uses.

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { clientIp } from "./http.ts";

let cached: SupabaseClient | null = null;

export function admin(): SupabaseClient {
  if (cached) return cached;
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new Error("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing");
  cached = createClient(url, key, { auth: { persistSession: false } });
  return cached;
}

export interface AuditEntry {
  userId?: string | null;
  deviceId?: string | null;
  pcId?: string | null;
  action: string;
  result: "ok" | "denied" | "error";
  req?: Request;
  metadata?: Record<string, unknown>;
}

/**
 * Audit writes are deliberately fire-and-forget: a logging failure must never
 * turn a successful POWER_ON into an error the user sees. Failures go to the
 * function log instead.
 */
export async function audit(entry: AuditEntry): Promise<void> {
  try {
    const { error } = await admin().from("audit_logs").insert({
      user_id: entry.userId ?? null,
      device_id: entry.deviceId ?? null,
      pc_id: entry.pcId ?? null,
      action: entry.action,
      result: entry.result,
      ip: entry.req ? clientIp(entry.req) : null,
      metadata: entry.metadata ?? {},
    });
    if (error) console.error("audit insert failed", error.message);
  } catch (e) {
    console.error("audit threw", e instanceof Error ? e.message : String(e));
  }
}
