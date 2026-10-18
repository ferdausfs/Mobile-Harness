# Getting the exact install-failure reason from your phone — Termux only (no PC)

When Android's package installer shows **"App not installed"** for a browser-downloaded
APK, the real reason (`INSTALL_FAILED_…`) is only in the system log. You can read it
from the phone itself using Termux + wireless debugging. No PC is needed at any step.

Everything here is read-only except `adb install` at step 6, which installs the APK
you choose.

## 0. Install Termux

- Install **Termux** from F-Droid (https://f-droid.org/en/packages/com.termux/) — the
  Play Store build is outdated and cannot install `android-tools`.
- In Termux run: `pkg update && pkg install android-tools`
  (this gives you `adb` and `apkanalyzer`-free basic tools).

## 1. Enable Wireless debugging on the phone

- Settings → Developer options → **Wireless debugging** → ON.
  (Developer options: Settings → About phone → tap "Build number" 7 times if not visible.)
- Tap **Wireless debugging** (the text itself) to open its detail page — keep it open.
- If you previously paired with a PC you can reuse that, but pairing fresh from the
  phone works too (step 2).

## 2. Pair adb from Termux (one time)

On the Wireless debugging page tap **Pair device with pairing code**. A dialog shows
a 6-digit code and a `IP:PORT` **pairing** port (it is different from the connection
port). In Termux:

```sh
adb pair 192.168.x.x:PAIRPORT
# type the 6-digit code when asked; "Successfully paired" is expected
```

The phone and the Termux process must be on the same Wi-Fi network. Hotspot also works.

## 3. Connect adb

Back on the Wireless debugging main page, note the `IP:PORT` under the page title
(the **connection** port, NOT the pairing port):

```sh
adb connect 192.168.x.x:CONNECTPORT
adb devices        # must list the device as "device", not "offline"
```

If it goes offline: `adb disconnect` then connect again. Wireless debugging resets
its ports every time you toggle it — always re-read the current `IP:PORT`.

## 4. Look for leftover package state (explains many "App not installed" cases)

```sh
adb shell pm list users
adb shell pm list packages -u | grep jarves
```

- `-u` includes **uninstalled** packages. If `package:com.jarves.mh` appears even
  though the launcher shows no Mobile Harness, there is a hidden leftover entry —
  that alone makes fresh installs fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  ("App not installed").
- Repeat the same check for the test app:
  `adb shell pm list packages -u | grep installtest`

## 5. Try the install from adb and capture the exact reason

```sh
# path example — adjust to where your browser saved the file:
adb install -r /sdcard/Download/mobile-harness-online-v1.0.24.apk
```

Unlike the GUI installer, `adb install` **prints the exact failure on the terminal**,
for example:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.jarves.mh signatures do not match
previously installed version; ignoring!
```

Send that line — it names the cause directly. Common outputs and what they mean:

| adb output | Meaning |
|---|---|
| `Success` | the APK is fine; the earlier GUI failure was device state or the installer UI |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | signature/leftover conflict — step 4 will usually show the culprit |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` / `NOT_APK` | the file on the phone is corrupted/truncated — re-download and re-check size |
| `INSTALL_FAILED_ABORTED` | something on the device killed the install (security software/One UI) — repeat right after with step 6 running |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | Play Protect/verifier rejected it despite the toggle |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | wrong-architecture APK (all Mobile Harness releases are arm64-v8a) |

## 6. Read the system install log (works even for GUI-installer attempts)

Reproduce the failure once from the GUI (or skip if step 5 already failed), then:

```sh
adb logcat -d | grep -i -E "PackageInstaller|installd|INSTALL_FAILED"
```

The most recent `INSTALL_FAILED_*` lines are the verdict of the last install attempt.
To capture a fresh attempt live: run `adb logcat -c` (clear), do the failing install
immediately, then run `adb logcat -d | grep -i -E "PackageInstaller|installd|INSTALL_FAILED"`.

Send back (paste or screenshot):
1. the output of step 4 (both greps),
2. the exact `adb install` result line from step 5,
3. the last ~20 lines from step 6.

## 7. Useful extras

```sh
adb shell pm path com.jarves.mh          # shows whether the app is really installed and where
adb shell dumpsys package com.jarves.mh | grep -E "versionName|versionCode|pkgFlags|signatures" | head
adb shell df -h /data                    # free storage on the data partition
getprop ro.build.version.sdk             # Android SDK int of the phone
getprop ro.product.cpu.abilist           # the phone's ABI list
```

`adb install -r` re-installs over the existing copy and keeps app data; it does NOT
downgrade. For a strictly fresh test use `adb uninstall com.jarves.mh` first.
