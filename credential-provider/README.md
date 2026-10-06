# Wolf Credential Provider (Phase 8)

The tile that signs you in when your phone approves it.

```powershell
.\install\register.ps1      # build, install, register
.\install\unregister.ps1    # take it off again
```

**Do this in a virtual machine first, with a snapshot.** A credential provider
is loaded by LogonUI, and a bad one can leave a login screen with nothing
usable on it — on a machine you then cannot log in to in order to fix it.

Read `../docs/PHASE8-LOGIN.md` first. It covers the design, why this is a
credential provider **without** a custom LSA authentication package, and the
pipe protocol it speaks.

## Test it without the login screen

`WolfAgent.exe login-test` speaks the same pipe protocol from a console and
stands in for this DLL. Use it first, and keep using it. A bug in the agent,
the phone or the crypto costs a console window to find; the same bug behind a
credential provider costs a VM, a snapshot and a reboot.

## How it is put together

| File | What it does |
| ---- | ------------ |
| `dll.cpp` | `DllGetClassObject`, `DllCanUnloadNow` — the only two exports |
| `ClassFactory.cpp` | hands `WolfProvider` instances to LogonUI |
| `Provider.cpp` | one tile; reports auto-logon once an approval is waiting |
| `Credential.cpp` | the state machine, and the `KERB_INTERACTIVE_UNLOCK_LOGON` packing |
| `PipeClient.cpp` | the named pipe, overlapped, everything timed out |

### Why there is a background thread

Approving a login is a human act with a 60 second budget, and
`GetSerialization` runs on the thread that draws the login screen. Blocking
there freezes the whole screen — no tiles, no password box, nothing to do but
hold the power button.

So submitting starts a worker and returns `CPGSR_NO_CREDENTIAL_NOT_FINISHED`,
which keeps the tile alive without holding the thread. When the worker has a
credential it tells the provider, LogonUI re-enumerates, sees auto-logon, and
calls `GetSerialization` again — this time with something to return. That round
trip is the only way to submit a credential from off the UI thread.

### The two rules

1. **Never filter away the password tile.** This provider does not implement
   `ICredentialProviderFilter` at all. It is the recovery path, and Microsoft's
   own guidance is to leave a system provider available.
2. **Never block.** Every pipe call has a timeout, which is why `PipeClient`
   uses overlapped I/O rather than plain `ReadFile`.

The tile is also never the default unless an approval is already waiting, so a
boot with the agent down looks like a boot without any of this installed.

## If something goes wrong

Boot into Safe Mode — hold Shift, Restart, Troubleshoot, Advanced options,
Startup Settings — where third-party credential providers are not loaded, and
run `install\unregister.ps1`. That script removes the registry key before it
touches any file, so the step that recovers the machine cannot fail because a
DLL is in use.

## Building by hand

```powershell
cmake -S . -B build -A x64
cmake --build build --config Release
```

Needs the **Desktop development with C++** workload: MSVC and the Windows SDK.
The runtime is linked statically, so there is no redistributable to install and
nothing to go missing at the one moment nobody can log in to fix it.
