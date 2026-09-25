# Redmi Battery Diagnostic

A simple read-only Android app for showing battery and charging data on Xiaomi/Redmi devices with clear labels instead of MB/MU style codes.

## Data shown

- Battery level (SOC)
- Charging state
- Battery voltage
- Instant and average battery current
- Battery temperature
- Android battery health state
- Connected power source and battery technology
- Charge counter
- Estimated instantaneous battery power
- If accessible through Xiaomi/HyperOS kernel sysfs: charging protocol, USB input voltage, USB current limit, Type-C CC orientation, Type-C mode, thermal charge control, charger temperature, cycle count, charge_full, charge_full_design and calculated SOH

## Privacy and safety

- No Internet permission
- No storage permission
- No root required
- Read-only: the app does not write battery, charger, radio or system settings
- Xiaomi/HyperOS may block some sysfs values. Those fields are shown as "Erisim yok" in the app.

## Xiaomi 6485 button

The button copies `*#*#6485#*#*` to the clipboard and opens the phone dialer. Android/Xiaomi security may prevent a third-party app from directly executing a secret code. If so, paste or type the code manually in the dialer.

## Build APK online with GitHub Actions

No Android Studio or local compiler is required.

1. Create a new empty GitHub repository.
2. Extract this ZIP on your PC or phone.
3. Upload the CONTENTS of this project folder to the root of the GitHub repository. Make sure `.github/workflows/build-apk.yml` is also uploaded.
4. Commit the uploaded files to the `main` branch.
5. Open the repository's `Actions` tab.
6. Open `Build Android APK`.
7. The first upload to `main` starts a build automatically. You can also choose `Run workflow` manually.
8. Wait for the green check mark.
9. Open the completed run and download the `RedmiBatteryDiag-APK` artifact.
10. Extract the downloaded artifact ZIP. Inside it is `RedmiBatteryDiag.apk`.
11. Transfer that APK to the Redmi phone and install it. Android may ask you to allow installation from the browser/file-manager source you used.

## Technical build settings

- Android Gradle Plugin: 8.7.3
- Gradle: 8.9 in GitHub Actions
- Java: 17
- compileSdk: 35
- targetSdk: 35
- minSdk: 23

## Important limitation

The Xiaomi 6485 FactoryKit screen is a privileged Xiaomi component. A normal third-party APK cannot reliably read every value exposed by FactoryKit on every HyperOS/MIUI build. This app reads Android public battery APIs first, then attempts read-only access to commonly exposed `/sys/class/power_supply/...` files. Values blocked by SELinux or vendor permissions will remain unavailable without elevated privileges.
