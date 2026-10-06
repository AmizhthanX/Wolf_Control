import { assertFreshWindow, MAX_LIFETIME_S, parseEnvelope, TARGET } from "./envelope.ts";

function check(ok: boolean, name: string) {
  if (!ok) throw new Error(name);
}

Deno.test("LOCK targets the agent and accepts a fresh bounded envelope", () => {
  const now = Date.now();
  const iso = (t: number) => new Date(t).toISOString().replace(/\.\d{3}Z$/, "Z");
  const envelope = parseEnvelope({
    command_id: "12345678-1234-4234-8234-123456789abc", device_id: "test-pc",
    command: "LOCK", timestamp: iso(now), expires_at: iso(now + 60_000),
    nonce: "0123456789abcdef", signature: "test-signature",
  });
  check(TARGET[envelope.command] === "AGENT", "wrong target");
  check(MAX_LIFETIME_S.LOCK === 60, "wrong lifetime");
  assertFreshWindow(envelope);
  let rejected = false;
  try { assertFreshWindow({ ...envelope, expires_at: iso(now + 120_000) }); }
  catch { rejected = true; }
  check(rejected, "overlong LOCK accepted");
});
