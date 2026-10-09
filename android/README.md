![WolfPC Remote — phone and PC](../docs/assets/wolfpc-hero.png)

# Android app

**Your WolfPC controls, with biometric approval built into every command.**

The app puts power, restart, shutdown, reset, lock, and phone unlock in one place.
You can refresh status, inspect command history, and pair by scanning the PC's QR.
It is built with Kotlin and Jetpack Compose for **Android 10 and newer**
(`minSdk 29`). Current app version: **1.1.0**.

[Back to WolfPC](../README.md) · [Setup guide](../docs/DEPLOY.md) · [Notification behavior](../docs/NOTIFICATIONS.md)

## Build and install

Open this directory in Android Studio, or use the Gradle wrapper. The build
configures a Java 17 toolchain and can download it when needed. You'll also need
the Android SDK; keep its machine-specific path in the ignored `local.properties`.

From the repository root, in PowerShell:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS or Linux, use `./gradlew` in place of `.\gradlew.bat`.

If `adb devices` is empty, unlock the phone, enable USB debugging, reconnect a
data-capable cable, and accept the debugging prompt. On Windows, if ADB is not
on PATH, invoke `platform-tools/adb.exe` from your Android SDK with PowerShell's
`&` operator.

## Pair in a few steps

First, run this on the PC:

```powershell
& "C:\Program Files\WolfPC\WolfAgent.exe" pair
```

Then on the phone:

1. **Setup:** tap **Scan the QR on your PC**.
2. **Sign in:** use the owner account created in Supabase.
3. **Pair:** confirm the prefilled code. The app creates its signing key in the
   Android Keystore.
4. **Home:** check controller and agent availability, then tap Refresh.

Scanning also carries over local addresses and the agent certificate fingerprint.
If you prefer manual entry, fill in Setup and Pair, then **Settings → Home
network**. The camera permission is used only for scanning; manual setup remains
available if you decline it.

## What each control needs

| Control | Requires |
| --- | --- |
| Power on / Hard reset | Reachable ESP32 controller |
| Shutdown / Restart | Reachable Windows agent |
| Lock PC | Agent and an unlocked Windows session |
| Sign in / Unlock | Agent, local enrollment, and the connected Wolf credential tile |
| Refresh | A reachable status source; no biometric prompt |

Notifications come from events the app observes. They can stop when Android
stops its process. This project does not include Firebase push delivery.

## Behind the buttons

![Signed command routes through LAN or Supabase](../docs/assets/wolfpc-architecture.png)

A command gets its identifier, nonce, and expiry before biometric approval. The
Keystore signs it, then the app tries the LAN route and falls back to Supabase.
Changing routes does not change the approved command.

The agent connection uses certificate-pinned HTTPS. The controller connection
uses HTTP, with a per-request guard restricting destinations to private,
link-local, or `.local` addresses. The device still verifies the signed envelope.

## Privacy choices

- QR decoding uses ML Kit's bundled model through CameraX, on the phone.
- The private signing key is non-exportable; app backups are disabled.
- Changing enrolled biometrics invalidates the signing key and requires pairing
  again.
- Unpairing revokes the server-side phone key before clearing the local pairing;
  if revocation cannot be delivered, the app keeps the pairing so you can retry.
- Production APK signing needs your own keystore and signing configuration.
  Keep both outside the committed source.

## Finding the code

| Area | Responsibility |
| --- | --- |
| `ui/home/` | PC status, Refresh, and action buttons |
| `ui/WolfViewModel.kt` | State, refresh coordination, pairing, command flow |
| `domain/CommandSender.kt` | Biometric approval and signed command delivery |
| `data/net/` | Supabase and LAN connections |
| `notif/StateNotifier.kt` | Event-to-notification transitions |

For a successful request that does not change the PC, inspect command history
and the receiving device's logs. “Request sent” describes delivery, not physical
completion. Save work before shutdown or restart.
