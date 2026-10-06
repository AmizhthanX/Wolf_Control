# Phase 8 — unlocking Windows from the phone

Not in the MVP (PRD §31). This is the design, the wire format between the two
halves, and the rules for building it safely. Build it in a VM first; a broken
credential provider is an annoyance, a broken authentication package is a
machine you cannot log into.

---

## The approach, and why it is not the PRD's original one

PRD §15 sketches a custom credential provider **plus a custom LSA
authentication package**. True passwordless, and the strongest version of this
feature. It is also the version where a bug in a DLL loaded by `lsass.exe`
bricks interactive logon on that machine.

What is specified here instead: **credential provider only**, with the Windows
password sealed locally by DPAPI and released solely when the phone's signature
verifies. Windows still authenticates the user with its own Kerberos/NTLM path;
the phone decides *whether the credential is released*, not whether the user is
authentic.

What that costs: the password exists on the machine, DPAPI-sealed, rather than
not existing at all. An administrator on that PC could unseal it — but an
administrator on that PC can already do anything, so this is not a new boundary.

What it buys: no code in `lsass.exe`, no custom authentication package, and a
failure mode of "the tile does not work, use the password tile" rather than "the
machine will not log in".

If you later want the full §15 design, the phone and agent halves below do not
change; only the final submission step does.

---

## Components

```
LogonUI (SYSTEM, no network)          WolfAgent service (SYSTEM, networked)
┌──────────────────────────┐          ┌────────────────────────────────┐
│ WolfCredentialProvider   │  pipe    │ LoginBroker                    │
│  - shows the tile        │<-------->│  - creates challenges          │
│  - asks for a challenge  │          │  - polls for the response      │
│  - unseals on approval   │          │  - verifies the signature      │
│  - submits credentials   │          │                                │
└──────────────────────────┘          └───────────────┬────────────────┘
                                                      │
                                              login-challenge
                                              login-respond
                                                      │
                                              Android app: prompt,
                                              sign with Keystore key
```

The credential provider cannot reach the network — LogonUI runs in a restricted
context — so all network and crypto work lives in the service and the two talk
over a named pipe ACL'd to SYSTEM and administrators only.

## The flow

1. User selects the Wolf tile at the login screen.
2. Provider opens `\\.\pipe\WolfPC.Login` and asks for a challenge.
3. Agent calls `login-challenge` with `action: "create"`; the server stores 32
   random bytes with a 60 second expiry.
4. Agent polls `login-challenge` with `action: "poll"`.
5. The app shows a prompt. The user approves with a biometric, and the Keystore
   signs:

   ```
   WOLFLOGIN1
   <challenge_id>
   <pc_id>
   <challenge>
   ```

   A distinct prefix from the command envelope's `WOLFPC1`, so neither can ever
   be replayed as the other.
6. `login-respond` verifies the signature server-side and stores it.
7. The agent's next poll returns the signature and the responder's public key.
   **The agent verifies it again locally**, against a key it pinned at pairing —
   the cloud relays the answer, it does not get to assert that the answer was
   valid.
8. Agent tells the provider to proceed. The provider unseals the DPAPI-protected
   credential and returns it through `ICredentialProviderCredential::GetSerialization`.
9. Windows authenticates normally.

## Pipe protocol

One line per message, UTF-8, newline terminated. The service is the server.

| Provider sends | Service replies                  |
| -------------- | -------------------------------- |
| `PENDING`      | `NONE`, or `UNLOCK <command_id>` |

`LoginBroker.cs` implements this today. A `LOGIN` command arriving with no
provider connected is answered `FAILED` with "phone login is not installed on
this PC; use the password tile", which is what the MVP does.

## Non-negotiable rules

**Keep a system credential provider enabled.** Microsoft recommends it and it is
your only way back in when something goes wrong. Never filter the password tile
away.

**Build and test in a VM.** Take a snapshot before every install. A provider
that throws during `SetUsageScenario` can leave LogonUI showing nothing usable.

**Register a fallback path.** If the pipe does not answer within a few seconds,
the tile must fail visibly and let the user switch to the password tile, not
hang.

**Seal the credential at machine scope, with entropy**, exactly as
`AgentConfig` does for the device secret, and never write it anywhere else — not
to a log, not to a crash dump, not to a notification (PRD §16, §23).

**Never send the password anywhere.** Supabase sees the challenge, the signature
and the outcome. That is all it is ever allowed to see (PRD §10, §16).

## Proven, as of 2026-09-23

Everything above except the credential provider is built and has been run end to
end on the real hardware, with the real phone and the real project:

```
-> CHALLENGE
<- CHALLENGE c37688fd-...
<- WAITING
<- APPROVED acd2c2da...
   signature verified against a key this PC pinned
   credential released for DESKTOP-\saras - 13 characters
-> CLAIM (again)
<- ERR no credential available
```

That was `WolfAgent.exe login-test`, which speaks the pipe protocol from a
console and stands in for the provider. Use it. A bug in any of the above costs
a console window to find; the same bug behind a credential provider costs a
virtual machine, a snapshot and a reboot.

So what remains is genuinely only a tile: draw it, connect the pipe, hand the
result to Windows. Everything it depends on already works.

## Working, as of 2026-09-24

Phone unlock runs on the reference machine. Locked PC, tap Unlock in the app,
fingerprint, signed in - about one second, without touching the PC:

```
credential provider connected
provider: bound to the enrolled account
state LOGGED_IN/LOCKED
login requested by command 9d7552e3-...
credential released to the provider
provider: claimed; telling LogonUI to re-enumerate
credential provider disconnected
state LOGGED_IN/ACTIVE
```

### Two bugs that cost the most time, both worth knowing about

**A BOM on the pipe.** `Encoding.UTF8` makes `StreamWriter` emit one, so the
first reply was `EF BB BF` + `WOLF 1` and the provider's handshake never
matched: connect, hang up, retry, forever. `login-test` could not catch it,
because it is written in C# and `StreamReader` strips BOMs by default - the
harness built to de-risk the C++ shared an implementation with the server it was
testing, and was blind to the one class of bug only the other client could hit.

**`GetUserSid` returning `S_FALSE`.** Reasonable for a fresh logon, wrong for an
unlock. Windows matches an unlock tile against the session being unlocked, and a
credential with no SID is an "other user" tile that LogonUI will not auto-submit
for a session belonging to somebody specific. Every step before it succeeded -
challenge, approval, claim, release, `CredentialsChanged` - and the round trip
stopped one step short of `GetSerialization` in silence. The provider now asks
the agent (`WHO`) which account is enrolled and resolves its SID.

Both were found by making the provider able to talk. It runs inside LogonUI with
no console, no log and no debugger worth attaching, so it borrows the agent's
event log over a `LOG` verb. Diagnosing it any other way is guessing.

## Building it

A credential provider is an in-process COM server implementing
`ICredentialProvider` and `ICredentialProviderCredential2`, registered under:

```
HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\
  Credential Providers\{your-guid}
```

C++ and the Windows SDK. The official sample in the Windows-classic-samples
repository is the right starting point — start from the sample, get an empty
tile appearing in a VM, and only then add the pipe client.

Order of work:

1. tile appears in a VM, does nothing
2. tile talks to the pipe, logs what it gets
3. agent creates and polls challenges, phone answers, agent verifies
4. DPAPI seal and unseal of the credential, tested on its own
5. wire them together
6. only then, ever, on the real PC — with the password tile still enabled
