// Supabase Auth (JWT) authentication for the Android app.

import { admin } from "./db.ts";
import { fail } from "./http.ts";

export interface AuthedUser {
  id: string;
  email: string;
}

export async function authenticateUser(req: Request): Promise<AuthedUser> {
  const header = req.headers.get("authorization");
  if (!header?.toLowerCase().startsWith("bearer ")) {
    fail("unauthenticated", "missing bearer token");
  }
  const token = header.slice(7).trim();

  // getUser() validates the signature and expiry against the project's keys;
  // decoding the JWT here by hand would accept a forged one.
  const { data, error } = await admin().auth.getUser(token);
  if (error || !data.user) fail("unauthenticated", "invalid or expired session");

  return { id: data.user.id, email: data.user.email ?? "" };
}

/** Confirm the caller owns this pc before anything touches hardware. */
export async function assertOwnsPc(userId: string, pcId: string): Promise<void> {
  const { data, error } = await admin()
    .from("pcs")
    .select("id")
    .eq("id", pcId)
    .eq("owner_id", userId)
    .maybeSingle();
  if (error) fail("internal", "pc lookup failed");
  if (!data) fail("forbidden", "not your pc");
}
