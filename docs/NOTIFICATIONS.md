# Notifications

PRD §23, delivered without any third party.

## The decision

Push was built on FCM and then deliberately removed. Supabase remains the whole
backend; the question was only *how a notification reaches a phone whose app is
closed*, and on Android the OS permits exactly one answer: Firebase Cloud
Messaging. Supabase has no push service, and neither does anyone else — FCM is
the only channel Google allows.

That meant a second Google account holding a service-account key, with Google
seeing each notification's title and one-line body. For a single-user system
that already tells you everything the moment you open it, that was not worth it.

So: **notifications come from what the app itself observes.** No Firebase, no
ntfy, no broker, nothing outside Supabase.

## What you get

While the app is running — foreground, or backgrounded but not yet swapped out
by Android:

| Notification         | When                                             | Channel |
| -------------------- | ------------------------------------------------ | ------- |
| PC turned on         | POWER_ON reaches EXECUTED                        | events  |
| Login screen         | Windows state becomes LOGIN_SCREEN               | events  |
| Signed in            | Windows state becomes LOGGED_IN                  | events  |
| Shutting down        | SHUTDOWN executes, or the state moves            | events  |
| Restarting           | RESTART executes, or the state moves             | events  |
| Shutdown complete    | Windows state becomes OFF                        | events  |
| Hard reset executed  | HARD_RESET reaches EXECUTED                      | alerts  |
| Command failed       | any command reaches FAILED or EXPIRED            | alerts  |
| Controller offline   | the ESP32 stops reporting in                     | alerts  |
| PC agent offline     | the Windows agent stops reporting in             | alerts  |

That is the whole of PRD §23. Two channels, so a failed command or a dead
controller can be loud without every routine "PC turned on" being loud.

The case that actually matters is covered: tap POWER ON, switch to another app,
get told when the PC reaches the login screen.

## Field-verified

Tested end to end on a Samsung Galaxy S928B, which is the hard case: One UI is
unusually aggressive about freezing background apps, and its Freecess service
was observed in logcat deciding whether to freeze this one. All four
notifications arrived — shutting down, agent offline, PC turned on, login
screen — with the app backgrounded and no battery exemption granted.

That is the whole risk of the no-push design in one test. If a future device
does freeze the app, the symptom is silence rather than anything misleading,
and the fix is Settings, Apps, WolfPC, Battery, Unrestricted.

## What you do not get

A phone whose app Android has fully evicted from memory will not light up. You
see the state the moment you open the app, and nothing is lost — but the phone
stays quiet until then.

If that ever becomes annoying, the honest options are FCM (a Google account, one
line of text visible to Google) or self-hosted ntfy / UnifiedPush (a server to
maintain, a persistent connection, Doze delays). Neither is in this build.

## How it works

`StateNotifier` compares each sync against the last one and notifies on
*transitions*. It is driven from `WolfViewModel.refresh()` rather than from the
Realtime callback, so the polling fallback produces notifications too — a
dropped socket should cost latency, not notifications.

Two behaviours worth knowing, because both look like bugs otherwise:

- **The first sync is silent.** There is nothing to compare against, and
  announcing whatever state the PC was already in would fire a notification on
  every app launch.
- **Commands seen for the first time are recorded silently.** On first sync the
  entire history is new; replaying it would be a wall of stale noise. Only a
  command that *changes* status while the app is watching notifies.

Controller and agent get separate offline alerts and are never collapsed into
one "offline" message. They mean different things about what you can still do:
no controller means no POWER ON and no HARD RESET, no agent means no shutdown,
restart or status. (PRD §28)

## What a notification may never contain

A lock screen is a public surface. Never:

- a password, in any form
- a nonce, a signature, a token, a key, anything biometric
- a pairing code
- a command id that has not executed

"PC reached the login screen" is fine. "Login challenge a7f3… awaiting
signature" is not. Every string the app can post lives in `StateNotifier`, in
one place, so this stays easy to audit.

## Permission

`POST_NOTIFICATIONS` is requested once the user reaches the working app, not at
first launch — a prompt in front of the first useful screen is the kind users
reflexively decline. Refusing it costs notifications and nothing else.

The `notification_preferences` table still exists with its per-event `prefs`
column. Nothing reads it today; it is where per-event switches would go, and it
is the row a push token would live in if push is ever added back.
