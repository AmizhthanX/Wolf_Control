![WolfPC components and command paths](../docs/assets/wolfpc-architecture.png)

# Project tools

**Set up devices, check who is online, and test the protocol without relays.**

These Node.js scripts use built-in APIs and need no npm dependencies. Use Node
18 or newer. Provisioning is administrative work; simulation is for development
and diagnosis.

[Back to WolfPC](../README.md) · [Deployment guide](../docs/DEPLOY.md) · [Protocol](../docs/PROTOCOL.md)

## Provisioning: give the PC and controller their identities

From the repository root, in PowerShell:

```powershell
cd tools/provision
if (!(Test-Path .env)) {
    Copy-Item .env.example .env
}
node provision.mjs genkey
```

Fill in `.env` with your Supabase URL, service-role key, and `WOLF_DEVICE_KEY`.
The wrapping key must match the Edge Function secret. If you already provisioned
devices, keep the existing key rather than generating a replacement.

Create the owner account in Supabase before initializing:

```powershell
node provision.mjs init --email you@example.com --pc PC-001 --name "Wolf PC"
node provision.mjs status --pc PC-001
```

Initialization prints the controller and agent secrets. Put each in the correct
device's local configuration and keep them out of logs, screenshots, and Git.
The database stores encrypted device secrets; the tool does not offer a command
to display them again.

### Useful day-to-day commands

| Command | What it does |
| --- | --- |
| `node provision.mjs status --pc PC-001` | Show device presence |
| `node provision.mjs paircode --pc PC-001` | Create a pairing code without the agent CLI |
| `node provision.mjs phones --pc PC-001` | List paired phones and their active keys |
| `node provision.mjs revoke --device "PHONE_DEVICE_ID"` | Revoke a phone identity from the list above |
| `node provision.mjs rotate --device wolfpc-001` | Replace the controller's device secret |
| `node provision.mjs rotate --device PC-001` | Replace the agent's device secret |

**Rotation invalidates the old secret immediately.** Apply the newly printed
value to the device before expecting it to report again. For the controller,
also update your ignored build credentials so a future erase does not restore
an obsolete secret.

## Simulator: test before opening the case

The simulator runs fake controller and agent workers against a real Supabase
project. It verifies signed envelopes and records simulated outcomes instead
of pressing a relay or shutting down Windows. It is useful for checking pairing,
signing, queue delivery, and acknowledgements.

Use a test project or separate test device identities. Running simulators with
the same identities as live devices can claim their queued commands.

From the repository root:

```powershell
cd tools/simulator
if (!(Test-Path .env)) {
    Copy-Item .env.example .env
}
```

Fill in the project URL and anon key. Replace the following placeholders with
your test device secrets:

```powershell
node simulate.mjs both `
    --controller-secret "YOUR_64_HEX_TEST_CONTROLLER_SECRET" `
    --agent-secret "YOUR_64_HEX_TEST_AGENT_SECRET"
```

The fake controller exposes a local API on port 8080. From a physical phone,
use the development PC's reachable private LAN address and port, not
`localhost`—that would refer to the phone itself. The simulator is a protocol
aid, not a replacement for hardware, TLS, or Windows sign-in acceptance tests.

## Keep local credentials local

Real `.env` files are ignored by Git. Commit placeholder-only examples and
redacted diagnostics. A service-role key or device secret is sensitive; the
Supabase anon key is a public client identifier, though row access still depends
on the project's policies.

For focused checks of the actual firmware storage and Windows signing code,
see the [firmware tests](../firmware/README.md#recovery-tests) and
[agent checks](../agent/README.md#for-contributors).
