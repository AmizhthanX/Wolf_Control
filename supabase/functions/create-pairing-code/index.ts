// create-pairing-code — the Windows agent asks for a code to show the user.
// PRD §17, steps 3 and 4.
//
// The code is short and human-readable because it is read off a screen and
// typed into a phone. What keeps that safe is not its entropy but its context:
// it lives for ten minutes, it is single use, and redeeming it still requires a
// signed-in session that owns the pc. A stranger who guesses one gets told the
// pc belongs to someone else.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { authenticateDevice } from "../_shared/deviceAuth.ts";

const TTL_MS = 10 * 60_000;
const MAX_ACTIVE = 3;

const serve = handler(async (req) => {
  const device = await authenticateDevice(req, "create-pairing-code", ["AGENT"]);
  const db = admin();

  // Stop an automated caller from filling the space with live codes.
  const { count } = await db
    .from("pairing_codes")
    .select("code", { count: "exact", head: true })
    .eq("pc_id", device.pcId)
    .is("redeemed_at", null)
    .gt("expires_at", new Date().toISOString());

  if ((count ?? 0) >= MAX_ACTIVE) {
    fail("rate_limited", "too many unused pairing codes; wait for them to expire");
  }

  // Try a few times in the unlikely event of a collision with a live code.
  for (let attempt = 0; attempt < 5; attempt++) {
    const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
    const code = `WOLF-${String(n).padStart(6, "0")}`;
    const expiresAt = new Date(Date.now() + TTL_MS).toISOString();

    const { error } = await db.from("pairing_codes").insert({
      code,
      pc_id: device.pcId,
      expires_at: expiresAt,
    });

    if (!error) {
      await audit({ deviceId: device.id, pcId: device.pcId,
                    action: "pairing:create", result: "ok", req });
      return json({ code, expires_at: expiresAt });
    }
    if (error.code !== "23505") {
      console.error("pairing insert failed", error.message);
      fail("internal", "could not create pairing code");
    }
  }

  fail("internal", "could not allocate a pairing code");
});

Deno.serve(serve);
