# WolfPC Android app

Kotlin, Jetpack Compose, minSdk 29.

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Easiest path is to open `android/` in Android Studio, which supplies a suitable
JDK. From the command line the build pins a **Java 17 toolchain** and downloads
it if needed — AGP's system-modules transform fails on newer JDKs, and on
JetBrains "nomod" runtimes that ship without `jlink`.

## First run

Run `WolfAgent.exe pair` on the PC first — it prints a QR.

1. **Setup** — tap **Scan the QR on your PC**. That fills in the project URL and
   anon key, the pairing code, and the PC's local address and certificate
   fingerprint in one go. Typing them by hand works identically.
2. **Sign in** — the single account that owns the PC.
3. **Pair** — the code is already filled in if you scanned; tap to pair. This is
   when the Keystore signing key is created.
4. **Settings → Home network** — only if you did not scan.

The camera permission is used by the scanner and nothing else. Refusing it
leaves the app fully usable: every field the QR fills can be typed.

## How a button press becomes a relay pulse

```
tap
 └─ build envelope        command id, nonce, expiry fixed here
     └─ BiometricPrompt   Keystore signs, one authorisation per signature
         └─ LAN attempt   2 s timeout, controller or agent
             └─ cloud     enqueue-command
```

The envelope is signed *before* a path is chosen, so the same bytes are valid on
either route and the fallback cannot alter what was authorised.

## Notes

- **QR scanning** is ML Kit's bundled barcode model over CameraX. It runs
  entirely on device — no Play Services download, no network, and nothing about
  the camera frames leaves the phone. Only QR format is recognised, and only
  `wolfpc://pair` URIs are acted on.

- **Cleartext** is permitted only for `wolfpc.local` — see
  `res/xml/network_security_config.xml` for why, and add your controller's IP
  there if you address it numerically.
- **Backups are off.** The signing key is not exportable, so a restored backup
  would look paired and be unable to sign anything.
- **Re-enrolling a fingerprint destroys the key** by design. The app detects it
  and asks you to pair again.
- **Release builds** need a signing config; none is committed. Create one in
  `app/build.gradle.kts` with a keystore kept outside the repository.
