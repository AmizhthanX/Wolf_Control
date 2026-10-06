# Refresh, Lock, and faster Windows commands

Android and Windows agent version 1.1.0:

- The home-screen Refresh icon reads status again, shows a spinner, and prevents
  overlapping refresh requests. Automatic refresh continues to work.
- LOCK PC sends a biometric-signed LOCK command. Windows retains the user session
  and open applications, as with Win+L; it does not log out. The button is offered
  when Windows is signed in and unlocked.
- LOCK is routed to the agent through either cloud or certificate-pinned LAN.
  Windows requires LockWorkStation to execute on the interactive desktop, so the
  LocalSystem service launches its own fixed helper mode using the console user's
  token. The helper has no network or configuration access.
- Command enqueue broadcasts an HMAC-authenticated wake hint through Supabase
  Realtime. The agent wakes its existing poll loop immediately. The hint carries
  only a random command identifier, expiry, and HMAC; it cannot authorize a lock,
  unlock, shutdown, or restart. Commands still require the paired phone signature.
  Expired, altered, wrong-device, and repeated hints are ignored.
- The 30-second fallback poll remains for disconnected sockets and lost hints.
  Continuous fast Edge Function polling is not required.
- The agent executes locally before its terminal acknowledgement, avoiding an
  extra cloud request ahead of the action.
- Shutdown/restart use a five-second countdown, with FORCE_SELF and FORCE_OTHERS.
  GRACE_OVERRIDE is removed: Microsoft documents that it overrides the countdown
  and cannot be combined with a positive grace period. Save work before confirming;
  these Windows flags can force logged-in sessions closed.
- Provider connection changes now trigger a status report, so Unlock availability
  does not have to wait for the regular heartbeat after locking.

Shutdown delivery latency and Windows' final time to power off are different.
Apps, updates, and drivers can still extend the latter. A successful API return
means Windows accepted the request, not that the machine has already powered off.

## Deployment

The database migration adds LOCK to wolf_command. Deploy enqueue-command after
pushing it. Publish the agent to .tools/agent-update, then run
agent/install/update.ps1 as Administrator. This update script preserves the
configuration and firewall and restores the previous binary on a copy/start
failure. Install the new APK with adb install -r.

## Verification

Agent checks exercise signed LOCK acceptance, command substitution, replay,
valid/tampered/expired wake messages, and cross-device wake rejection. The
--live-wake harness listens without fetching or executing commands and verifies
real Supabase delivery and duplicate suppression.

The cloud envelope test checks LOCK routing and its expiry limits. Android's
debug APK and the self-contained Windows agent must both build. End-to-end
locking should be checked from the updated phone. Shutdown timing requires a
separate test after saving work; do not shut down the working PC merely to run
automated verification.

## Useful next additions

1. Diagnostics showing last contact, Wi-Fi signal, and cloud/local command path.
2. A visible queued/delivered/started command indicator, distinct from completion.
3. A cancel-shutdown command for the countdown window.
4. Power-state sensing from the motherboard LED, to distinguish powered-off from
   Windows-agent-offline even when Windows cannot report.
5. A signed release APK and a simple version/update screen.

References: [Windows LockWorkStation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-lockworkstation),
[Windows InitiateShutdown](https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-initiateshutdownw),
[Supabase Broadcast](https://supabase.com/docs/guides/realtime/broadcast).
