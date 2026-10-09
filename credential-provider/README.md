![WolfPC phone approval and Windows](../docs/assets/wolfpc-hero.png)

# The Wolf sign-in tile

**Approve on your phone. Let Windows handle the sign-in.**

This C++ credential provider adds the Wolf tile to Windows' sign-in screen.
It talks to the agent over a named pipe, waits for a verified phone approval,
and submits the enrolled credential through Windows' normal authentication path.

[Back to WolfPC](../README.md) · [Agent setup](../agent/README.md) · [Phone-unlock design](../docs/PHASE8-LOGIN.md)

## What “phone unlock” means here

Your Windows password is enrolled and sealed locally by the agent using DPAPI.
After the paired phone signs an approval and the agent verifies it, the credential
is released once to the provider. The password is not sent to Supabase.

This is a credential provider, **not a custom LSA authentication package**.
Windows still authenticates with the enrolled credential. A local administrator
can access machine-protected data, so this does not protect against a compromised
administrator on the PC.

## Before installing

You'll need Windows 11, CMake, the Windows SDK, and Visual Studio's **Desktop
development with C++** workload. Test provider changes in a VM with a snapshot
first: this DLL runs inside the process that draws the sign-in screen.

Keep the Windows password tile available. Wolf does not filter it away, and a
working password sign-in is the fallback when the agent or phone is unavailable.

## Test the approval chain first

Install and pair the agent and app, then use an Administrator PowerShell:

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" enroll-login
& "C:\Program Files\WolfPC\WolfAgent.exe" login-test
```

Use the Windows account password, not a Hello PIN. Approve the challenge on the
phone. The harness verifies that a credential can be released once and that
claiming the same token again fails, without using LogonUI.

## Build and register

From this directory, in Administrator PowerShell:

```powershell
.\install\register.ps1
```

The script builds, copies, and registers the provider. Follow the
[phone-unlock guide](../docs/PHASE8-LOGIN.md) for setup and recovery details.

To build without installing:

```powershell
cmake -S . -B build -A x64
cmake --build build --config Release
```

The runtime is linked statically. The provider is for the account enrolled on
this PC; re-run enrollment after changing that account's Windows password.

## Try it on the sign-in screen

Lock Windows with Win+L, dismiss the lock-screen cover if necessary, and select
the Wolf tile. Give the provider a moment to connect to the agent. Refresh the
app and approve Unlock when it becomes available. If the flow fails, use the
Windows password tile and inspect the agent's Application event log.

## How the code stays responsive

Phone approval is a human action and can take several seconds. Pipe work runs
on a background thread instead of blocking the thread drawing the sign-in
screen. Once approval is ready, the provider asks Windows to enumerate the
credentials again and submit the result.

| Source | Role |
| --- | --- |
| `dll.cpp` | COM exports |
| `ClassFactory.cpp` | Creates provider instances |
| `Provider.cpp` | Tile enumeration and ready-approval notification |
| `Credential.cpp` | Tile state, enrolled-account binding, credential serialization |
| `PipeClient.cpp` | Timed, overlapped named-pipe communication |

Preserve the timeouts, background work, and password fallback when making changes.
Use both the console harness and the real C++ provider: they can reveal different
protocol problems.

## Remove or recover

From an Administrator terminal in this directory:

```powershell
.\install\unregister.ps1
```

If a modified provider prevents normal sign-in, use the recovery steps in the
phone-unlock guide to enter Safe Mode and unregister it. The script removes the
registration before attempting to remove the DLL.
