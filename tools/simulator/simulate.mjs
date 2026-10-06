#!/usr/bin/env node
// Fake controller and fake Windows agent.
//
// Runs the real protocol against the real control plane — same HMAC scheme,
// same poll/ack lifecycle, same signature verification — so the Android app can
// be developed and the Edge Functions exercised before any hardware exists, and
// so a failing command can be diagnosed without opening the case.
//
//   node simulate.mjs controller --id wolfpc-001 --secret <hex>
//   node simulate.mjs agent      --id PC-001     --secret <hex>
//   node simulate.mjs both       --controller-secret <hex> --agent-secret <hex>
//
// Reads SUPABASE_URL and SUPABASE_ANON_KEY from the environment or ./.env.

import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

// --------------------------------------------------------------- config ----

function loadEnv() {
  const file = path.join(here, ".env");
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
  }
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--")) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}

// --------------------------------------------------------------- crypto ----

const sha256Hex = (s) => crypto.createHash("sha256").update(s).digest("hex");

function signRequest(deviceId, secretHex, functionName, body) {
  const timestamp = Math.floor(Date.now() / 1000);
  const nonce = crypto.randomBytes(16).toString("hex");
  const message = [
    deviceId,
    String(timestamp),
    nonce,
    "POST",
    `/functions/v1/${functionName}`,
    sha256Hex(body),
  ].join("\n");

  const signature = crypto
    .createHmac("sha256", Buffer.from(secretHex, "hex"))
    .update(message)
    .digest("hex");

  return {
    "x-wolf-device": deviceId,
    "x-wolf-timestamp": String(timestamp),
    "x-wolf-nonce": nonce,
    "x-wolf-signature": signature,
  };
}

async function callFunction(deviceId, secretHex, functionName, payload) {
  const body = JSON.stringify(payload);
  const url = `${process.env.SUPABASE_URL.replace(/\/$/, "")}/functions/v1/${functionName}`;

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      apikey: process.env.SUPABASE_ANON_KEY ?? "",
      authorization: `Bearer ${process.env.SUPABASE_ANON_KEY ?? ""}`,
      ...signRequest(deviceId, secretHex, functionName, body),
    },
    body,
  });

  const text = await response.text();
  if (!response.ok) throw new Error(`${functionName} -> ${response.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

/** The same verification the firmware performs, so a bad signature fails here too. */
function verifyEnvelope(envelope, spkiB64Url) {
  if (!spkiB64Url) return false;
  const message = [
    "WOLFPC1",
    envelope.command_id,
    envelope.device_id,
    envelope.command,
    envelope.timestamp,
    envelope.expires_at,
    envelope.nonce,
  ].join("\n");

  const key = crypto.createPublicKey({
    key: Buffer.from(spkiB64Url.replace(/-/g, "+").replace(/_/g, "/"), "base64"),
    format: "der",
    type: "spki",
  });

  return crypto.verify(
    "sha256",
    Buffer.from(message),
    { key, dsaEncoding: "ieee-p1363" },
    Buffer.from(envelope.signature.replace(/-/g, "+").replace(/_/g, "/"), "base64"),
  );
}

// ----------------------------------------------------------- controller ----

const seen = new Set();

async function runController(deviceId, secret, { localPort }) {
  console.log(`[controller] ${deviceId} polling`);
  let relayState = "idle";
  let trustedKeys = [];

  if (localPort) startLocalApi(localPort, deviceId, () => ({ relayState, trustedKeys }),
                               (envelope) => executeLocally(envelope, trustedKeys, (s) => { relayState = s; }));

  for (;;) {
    try {
      const result = await callFunction(deviceId, secret, "device-poll", {
        firmware_version: "1.0.0-sim",
        uptime_s: Math.floor(process.uptime()),
        rssi: -55,
        relay_state: relayState,
      });

      trustedKeys = result.trusted_keys ?? [];

      for (const envelope of result.commands ?? []) {
        if (seen.has(envelope.command_id)) continue;
        seen.add(envelope.command_id);

        const ok = verifyEnvelope(envelope, envelope.public_key ?? trustedKeys[0]);
        if (!ok) {
          console.log(`[controller] REJECT ${envelope.command} - bad signature`);
          await callFunction(deviceId, secret, "device-ack", {
            command_id: envelope.command_id, status: "FAILED", error: "bad_signature",
          });
          continue;
        }

        console.log(`[controller] ${envelope.command} verified, pulsing relay`);
        await callFunction(deviceId, secret, "device-ack", {
          command_id: envelope.command_id, status: "ACKNOWLEDGED",
        });

        relayState = envelope.command === "POWER_ON" ? "power" : "reset";
        await sleep(envelope.command === "POWER_ON" ? 1000 : 350);
        relayState = "idle";

        await callFunction(deviceId, secret, "device-ack", {
          command_id: envelope.command_id, status: "EXECUTED",
        });
        console.log(`[controller] ${envelope.command} done`);
      }

      await sleep(result.poll_interval_ms ?? 2000);
    } catch (err) {
      console.error("[controller]", err.message);
      await sleep(5000);
    }
  }
}

function executeLocally(envelope, trustedKeys, setRelay) {
  const ok = trustedKeys.some((k) => {
    try { return verifyEnvelope(envelope, k); } catch { return false; }
  });
  if (!ok) return { status: 401, body: { error: { code: "bad_signature", message: "rejected" } } };
  if (seen.has(envelope.command_id)) {
    return { status: 409, body: { error: { code: "replay", message: "already seen" } } };
  }
  seen.add(envelope.command_id);

  setRelay(envelope.command === "POWER_ON" ? "power" : "reset");
  setTimeout(() => setRelay("idle"), envelope.command === "POWER_ON" ? 1000 : 350);
  console.log(`[controller] LAN ${envelope.command} executed`);
  return { status: 200, body: { command_id: envelope.command_id, status: "EXECUTED" } };
}

/** Mirrors the firmware's LAN routes so the app's local path can be tested. */
function startLocalApi(port, deviceId, snapshot, execute) {
  http.createServer((req, res) => {
    const send = (status, body) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };

    if (req.method === "GET" && req.url === "/api/status") {
      const { relayState, trustedKeys } = snapshot();
      return send(200, {
        device_id: deviceId,
        firmware_version: "1.0.0-sim",
        uptime_s: Math.floor(process.uptime()),
        wifi_connected: true,
        relay_state: relayState,
        trusted_keys: trustedKeys.length,
        clock: "synced",
      });
    }

    if (req.method === "POST" && (req.url === "/api/power" || req.url === "/api/reset")) {
      let body = "";
      req.on("data", (chunk) => { body += chunk; if (body.length > 8192) req.destroy(); });
      req.on("end", () => {
        try {
          const envelope = JSON.parse(body);
          const expected = req.url === "/api/power" ? "POWER_ON" : "HARD_RESET";
          if (envelope.command !== expected) {
            return send(400, { error: { code: "bad_request", message: "wrong endpoint" } });
          }
          const result = execute(envelope);
          send(result.status, result.body);
        } catch {
          send(400, { error: { code: "bad_request", message: "bad json" } });
        }
      });
      return;
    }

    send(404, { error: { code: "not_found", message: "no" } });
  }).listen(port, () => console.log(`[controller] LAN api on http://localhost:${port}/api/status`));
}

// ---------------------------------------------------------------- agent ----

async function runAgent(deviceId, secret) {
  console.log(`[agent] ${deviceId} polling`);

  // Walks a plausible boot sequence so the app's status UI can be seen
  // changing without a real PC.
  const script = ["BOOTING", "LOGIN_SCREEN", "LOGGED_IN"];
  let step = 0;
  let windowsState = script[0];

  setInterval(() => {
    if (step < script.length - 1) windowsState = script[++step];
  }, 20000);

  setInterval(async () => {
    try {
      await callFunction(deviceId, secret, "agent-status", {
        windows_state: windowsState,
        session_state: windowsState === "LOGGED_IN" ? "ACTIVE" : "NONE",
        agent_version: "1.0.0-sim",
      });
    } catch (err) {
      console.error("[agent] status", err.message);
    }
  }, 10000);

  for (;;) {
    try {
      const result = await callFunction(deviceId, secret, "device-poll", {
        firmware_version: "1.0.0-sim",
        uptime_s: Math.floor(process.uptime()),
      });

      for (const envelope of result.commands ?? []) {
        if (seen.has(envelope.command_id)) continue;
        seen.add(envelope.command_id);

        const ok = verifyEnvelope(envelope, envelope.public_key ?? (result.trusted_keys ?? [])[0]);
        console.log(`[agent] ${envelope.command} signature ${ok ? "ok" : "REJECTED"}`);

        await callFunction(deviceId, secret, "device-ack", {
          command_id: envelope.command_id,
          status: ok ? "EXECUTED" : "FAILED",
          error: ok ? null : "bad_signature",
        });

        if (ok && envelope.command === "SHUTDOWN") windowsState = "SHUTTING_DOWN";
        if (ok && envelope.command === "RESTART") windowsState = "RESTARTING";
      }

      await sleep(3000);
    } catch (err) {
      console.error("[agent]", err.message);
      await sleep(5000);
    }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ----------------------------------------------------------------- main ----

loadEnv();
const args = parseArgs(process.argv.slice(2));
const mode = args._[0];

if (!process.env.SUPABASE_URL) {
  console.error("SUPABASE_URL is not set (environment or tools/simulator/.env)");
  process.exit(1);
}

if (mode === "controller") {
  await runController(args.id ?? "wolfpc-001", requireSecret(args.secret),
                      { localPort: Number(args.port ?? 8080) });
} else if (mode === "agent") {
  await runAgent(args.id ?? "PC-001", requireSecret(args.secret));
} else if (mode === "both") {
  runController(args.controller ?? "wolfpc-001", requireSecret(args["controller-secret"]),
                { localPort: Number(args.port ?? 8080) });
  await runAgent(args.agent ?? "PC-001", requireSecret(args["agent-secret"]));
} else {
  console.log(`
WolfPC simulator

  node simulate.mjs controller --secret <hex> [--id wolfpc-001] [--port 8080]
  node simulate.mjs agent      --secret <hex> [--id PC-001]
  node simulate.mjs both --controller-secret <hex> --agent-secret <hex>
`);
  process.exit(mode ? 1 : 0);
}

function requireSecret(value) {
  if (!value || !/^[0-9a-f]{64}$/i.test(value)) {
    console.error("a 64-character hex device secret is required (--secret)");
    process.exit(1);
  }
  return value.toLowerCase();
}
