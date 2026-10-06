// device-ack — a device reports what it did with a command.
//
// Acks are idempotent by design (PROTOCOL §6). The ESP32 fires its relay and
// *then* reports; if the report fails it retries. Re-acking a finished command
// must not be an error, or the firmware would have to distinguish "my ack was
// lost" from "my relay pulse was lost" — and it cannot.

import { fail, handler, json } from "../_shared/http.ts";
import { admin, audit } from "../_shared/db.ts";
import { authenticateDevice } from "../_shared/deviceAuth.ts";

const ALLOWED = ["ACKNOWLEDGED", "EXECUTED", "FAILED"] as const;

const serve = handler(async (req) => {
  const device = await authenticateDevice(req, "device-ack");

  const commandId = device.json.command_id;
  const status = device.json.status;
  const err = typeof device.json.error === "string"
    ? device.json.error.slice(0, 500)
    : null;

  if (typeof commandId !== "string") fail("bad_request", "command_id required");
  if (!ALLOWED.includes(status)) fail("bad_request", "status must be ACKNOWLEDGED, EXECUTED or FAILED");

  const { data, error } = await admin().rpc("wolf_ack_command", {
    command_uuid: commandId,
    target_pc: device.pcId,
    new_status: status,
    err,
  });

  if (error) {
    console.error("ack failed", error.message);
    fail("internal", "could not record acknowledgement");
  }
  if (!data) fail("not_found", "no such command for this pc");

  // deno-lint-ignore no-explicit-any
  const row = data as any;

  // Only log the transitions worth reading later. An ACKNOWLEDGED for every
  // command would bury the executions and failures that actually matter.
  if (status !== "ACKNOWLEDGED") {
    await audit({
      userId: row.user_id ?? null,
      deviceId: device.id,
      pcId: device.pcId,
      action: `execute:${row.command}`,
      result: status === "EXECUTED" ? "ok" : "error",
      req,
      metadata: { command_id: commandId, error: err },
    });
  }

  return json({ command_id: commandId, status: row.status });
});

Deno.serve(serve);
