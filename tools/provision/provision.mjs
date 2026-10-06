#!/usr/bin/env node
// WolfPC provisioning CLI.
//
// Creates the pc, its two device identities and their HMAC secrets, and prints
// the secrets exactly once so you can paste them into the firmware config and
// the agent installer. The secrets are stored in Postgres only in AES-256-GCM
// form, wrapped with WOLF_DEVICE_KEY (PRD §10), so there is no way to read them
// back out of the database later — losing them means re-provisioning.
//
//   node provision.mjs genkey
//   node provision.mjs init  --email you@example.com --pc PC-001 --name "Wolf PC"
//   node provision.mjs rotate --device wolfpc-001
//   node provision.mjs paircode --pc PC-001
//   node provision.mjs status --pc PC-001
//
// Reads SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY and WOLF_DEVICE_KEY from the
// environment or from a .env file beside this script.

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

function loadEnv() {
  const file = path.join(here, ".env");
  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
      if (m && !process.env[m[1]]) {
        process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
      }
    }
  }
}

function need(name) {
  const v = process.env[name];
  if (!v) {
    console.error(`missing ${name} (set it in the environment or in tools/provision/.env)`);
    process.exit(1);
  }
  return v;
}

// ------------------------------------------------------------------ rest ----

async function rest(pathname, { method = "GET", body, prefer } = {}) {
  const url = `${need("SUPABASE_URL").replace(/\/$/, "")}/rest/v1/${pathname}`;
  const key = need("SUPABASE_SERVICE_ROLE_KEY");
  const res = await fetch(url, {
    method,
    headers: {
      apikey: key,
      authorization: `Bearer ${key}`,
      "content-type": "application/json",
      ...(prefer ? { prefer } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${pathname} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function findUserByEmail(email) {
  const key = need("SUPABASE_SERVICE_ROLE_KEY");
  const url = `${need("SUPABASE_URL").replace(/\/$/, "")}/auth/v1/admin/users?per_page=200`;
  const res = await fetch(url, {
    headers: { apikey: key, authorization: `Bearer ${key}` },
  });
  if (!res.ok) throw new Error(`admin users -> ${res.status} ${await res.text()}`);
  const { users } = await res.json();
  const user = users.find((u) => (u.email ?? "").toLowerCase() === email.toLowerCase());
  if (!user) {
    throw new Error(
      `no auth user with email ${email}. Create it first in the Supabase dashboard ` +
      `(Authentication -> Users -> Add user), then re-run.`,
    );
  }
  return user;
}

// ---------------------------------------------------------------- crypto ----

function wrapSecret(secretBytes) {
  const keyB64 = need("WOLF_DEVICE_KEY");
  const key = Buffer.from(keyB64, "base64");
  if (key.length !== 32) {
    throw new Error("WOLF_DEVICE_KEY must decode to exactly 32 bytes; run `genkey`");
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const ct = Buffer.concat([cipher.update(secretBytes), cipher.final(), cipher.getAuthTag()]);
  // WebCrypto expects the GCM tag appended to the ciphertext, which is what
  // the Edge Functions decrypt, so the tag is concatenated rather than stored
  // in its own column.
  return { ciphertext: ct.toString("base64"), iv: iv.toString("base64") };
}

// ------------------------------------------------------------- commands ----

async function cmdGenkey() {
  console.log("\nWOLF_DEVICE_KEY=" + crypto.randomBytes(32).toString("base64"));
  console.log(`
Set this as an Edge Function secret and keep a copy somewhere safe:

  supabase secrets set WOLF_DEVICE_KEY=<the value above>

It wraps every device secret. Lose it and every device has to be re-provisioned;
leak it and anyone with a database dump can impersonate your hardware.
`);
}

async function upsertDevice(dev) {
  await rest("devices", {
    method: "POST",
    body: dev,
    prefer: "resolution=merge-duplicates,return=minimal",
  });
}

async function issueSecret(deviceId) {
  const secret = crypto.randomBytes(32);
  const { ciphertext, iv } = wrapSecret(secret);
  await rest("device_secrets", {
    method: "POST",
    body: { device_id: deviceId, ciphertext, iv, rotated_at: new Date().toISOString() },
    prefer: "resolution=merge-duplicates,return=minimal",
  });
  return secret.toString("hex");
}

async function cmdInit(args) {
  const email = args.email;
  const pcId = args.pc ?? "PC-001";
  const pcName = args.name ?? "Wolf PC";
  const controllerId = args.controller ?? "wolfpc-001";
  if (!email) throw new Error("--email is required");

  const user = await findUserByEmail(email);

  await rest("users", {
    method: "POST",
    body: { id: user.id, email: user.email },
    prefer: "resolution=merge-duplicates,return=minimal",
  });

  await rest("pcs", {
    method: "POST",
    body: { id: pcId, owner_id: user.id, name: pcName },
    prefer: "resolution=merge-duplicates,return=minimal",
  });

  await rest("pc_status", {
    method: "POST",
    body: { pc_id: pcId },
    prefer: "resolution=merge-duplicates,return=minimal",
  });

  await upsertDevice({
    id: controllerId, pc_id: pcId, owner_id: user.id, name: "ESP32 controller",
    device_type: "CONTROLLER", status: "PAIRED", paired_at: new Date().toISOString(),
  });
  await upsertDevice({
    id: pcId, pc_id: pcId, owner_id: user.id, name: pcName + " agent",
    device_type: "AGENT", status: "PAIRED", paired_at: new Date().toISOString(),
  });

  const controllerSecret = await issueSecret(controllerId);
  const agentSecret = await issueSecret(pcId);

  console.log(`
Provisioned ${pcId} for ${user.email}

  ESP32 controller
    WOLF_DEVICE_ID      ${controllerId}
    WOLF_DEVICE_SECRET  ${controllerSecret}

  Windows agent
    WOLF_DEVICE_ID      ${pcId}
    WOLF_DEVICE_SECRET  ${agentSecret}

Put the controller pair in firmware/include/credentials.h and the agent pair
into install.ps1 -DeviceSecret. This is the only time they are printed: they go
into Postgres encrypted and cannot be read back.
`);
}

async function cmdRotate(args) {
  if (!args.device) throw new Error("--device is required");
  const secret = await issueSecret(args.device);
  console.log(`\n${args.device} new secret: ${secret}\n`);
  console.log("Reflash or reinstall that device now — its old secret stops working immediately.\n");
}

async function cmdPaircode(args) {
  const pcId = args.pc ?? "PC-001";
  const code = "WOLF-" + String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
  const expires = new Date(Date.now() + 10 * 60_000).toISOString();
  await rest("pairing_codes", {
    method: "POST",
    body: { code, pc_id: pcId, expires_at: expires },
    prefer: "return=minimal",
  });
  console.log(`\n  ${code}\n\nValid for 10 minutes. Enter it in the Android app to pair.\n`);
}

/**
 * List the phones that can sign commands for a pc, and how many live keys each
 * holds.
 *
 * The count matters more than it looks: a device's trusted-key list is exactly
 * the set of live keys here, so a phone that was unpaired without being revoked
 * shows up as a key the hardware still honours.
 */
async function cmdPhones(args) {
  const pcId = args.pc ?? "PC-001";
  const phones = await rest(
    `devices?pc_id=eq.${encodeURIComponent(pcId)}&device_type=eq.PHONE` +
    `&select=id,name,status,paired_at,device_keys(id,revoked_at)&order=paired_at`,
  );

  if (phones.length === 0) {
    console.log("\nno phones paired\n");
    return;
  }

  console.log();
  for (const phone of phones) {
    const live = (phone.device_keys ?? []).filter((k) => k.revoked_at === null).length;
    const flag = phone.status === "REVOKED" ? "revoked"
      : live === 0 ? "no live key"
      : `${live} live key${live === 1 ? "" : "s"}`;
    console.log(`  ${phone.id}`);
    console.log(`    ${phone.name}  ·  ${phone.status}  ·  ${flag}  ·  paired ${phone.paired_at}`);
  }
  console.log(`
Devices trust every live key listed above. To cut one off:

  node provision.mjs revoke --device <id>
`);
}

/**
 * Revoke a phone that is no longer in your hands, or one unpaired before the
 * app learned to revoke for itself.
 *
 * Takes effect on the hardware within one poll: device-poll sends the shortened
 * key list, and the controller replaces its pinned set wholesale rather than
 * merging, so the revoked key stops working on the LAN too.
 */
async function cmdRevoke(args) {
  if (!args.device) throw new Error("--device is required (see: provision.mjs phones)");
  const now = new Date().toISOString();

  await rest(`device_keys?device_id=eq.${encodeURIComponent(args.device)}&revoked_at=is.null`, {
    method: "PATCH",
    body: { revoked_at: now },
    prefer: "return=minimal",
  });
  await rest(`devices?id=eq.${encodeURIComponent(args.device)}`, {
    method: "PATCH",
    body: { status: "REVOKED" },
    prefer: "return=minimal",
  });

  console.log(`
  ${args.device} revoked.

Your controller drops the key on its next poll, within a few seconds. If that
phone still has the app installed and paired, its commands will start being
refused with bad_signature.
`);
}

async function cmdStatus(args) {
  const pcId = args.pc ?? "PC-001";
  const [status] = await rest(`pc_status?pc_id=eq.${encodeURIComponent(pcId)}&select=*`);
  const devices = await rest(
    `devices?pc_id=eq.${encodeURIComponent(pcId)}&select=id,device_type,status,last_seen,firmware_version`,
  );
  console.log("\npc_status:", status ?? "(none)");
  console.log("\ndevices:");
  for (const d of devices) {
    console.log(`  ${d.id.padEnd(40)} ${d.device_type.padEnd(11)} ${d.status.padEnd(9)} last_seen=${d.last_seen ?? "never"}`);
  }
  console.log();
}

// ------------------------------------------------------------------ main ----

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--")) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
const cmd = args._[0];
loadEnv();

const table = {
  genkey: cmdGenkey,
  init: cmdInit,
  rotate: cmdRotate,
  paircode: cmdPaircode,
  status: cmdStatus,
  phones: cmdPhones,
  revoke: cmdRevoke,
};

if (!table[cmd]) {
  console.log(`
WolfPC provisioning

  node provision.mjs genkey
  node provision.mjs init     --email you@example.com [--pc PC-001] [--name "Wolf PC"]
  node provision.mjs rotate   --device wolfpc-001
  node provision.mjs paircode [--pc PC-001]
  node provision.mjs status   [--pc PC-001]
  node provision.mjs phones   [--pc PC-001]     which phones can sign commands
  node provision.mjs revoke   --device <id>     cut a phone off
`);
  process.exit(cmd ? 1 : 0);
}

try {
  await table[cmd](args);
} catch (err) {
  console.error("\nfailed:", err.message, "\n");
  process.exit(1);
}
