// agent-status — the Windows agent reports what Windows is actually doing.
//
// The agent is the *only* source of windows_state. The controller can see that
// its Wi-Fi is up and that a relay clicked, and that tells it nothing about
// whether Windows booted (PRD §28), so nothing here infers one from the other.

import { fail, handler, json } from "../_shared/http.ts";
import { admin } from "../_shared/db.ts";
import { authenticateDevice } from "../_shared/deviceAuth.ts";

const WINDOWS_STATES = [
  "OFF", "BOOTING", "LOGIN_SCREEN", "LOGGED_IN",
  "SHUTTING_DOWN", "RESTARTING", "UNKNOWN",
] as const;

const SESSION_STATES = ["NONE", "LOCKED", "ACTIVE"] as const;

const serve = handler(async (req) => {
  const device = await authenticateDevice(req, "agent-status", ["AGENT"]);

  const windowsState = device.json.windows_state;
  const sessionState = device.json.session_state ?? "NONE";

  if (!WINDOWS_STATES.includes(windowsState)) fail("bad_request", "unknown windows_state");
  if (!SESSION_STATES.includes(sessionState)) fail("bad_request", "unknown session_state");

  const db = admin();

  await db.rpc("wolf_heartbeat", {
    dev_id: device.id,
    fw: typeof device.json.agent_version === "string" ? device.json.agent_version : null,
    meta: typeof device.json.username === "string"
      ? { username: device.json.username }
      : {},
  });

  const { error } = await db
    .from("pc_status")
    .update({
      windows_state: windowsState,
      session_state: sessionState,
      // Only the agent can know this: it owns the pipe the credential provider
      // connects to. The app uses it to avoid offering an unlock button that
      // cannot possibly work.
      phone_login_available: device.json.phone_login_available === true,
      // Reported rather than guessed: DHCP moves this, and the app caches it
      // so the LAN path still has somewhere to dial when the cloud is gone.
      agent_lan_address: typeof device.json.lan_address === "string"
        ? device.json.lan_address.slice(0, 64)
        : null,
      agent_network_private: device.json.network_private !== false,
      windows_agent_online: true,
      agent_last_seen: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    })
    .eq("pc_id", device.pcId);

  if (error) {
    console.error("status update failed", error.message);
    fail("internal", "could not record status");
  }

  return json({ ok: true, pc_id: device.pcId, windows_state: windowsState });
});

Deno.serve(serve);
