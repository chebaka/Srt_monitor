# SRT Watch Android

Android app project for the SRT reservation monitor.

## Current scope

- Native Android UI for SRT account and trip conditions
- Android Keystore AES-GCM encrypted profile storage
- Foreground Service and Android notification channel
- Chaquopy integration point for the verified Python SRT engine
- Telegram is not used by the Android app

## Build on a computer

Requirements:

- Android Studio Ladybug or newer
- JDK 17
- Android SDK Platform 35 and Build Tools
- Internet access for Gradle, AndroidX, Chaquopy, and Python dependencies

```bash
cd android
./gradlew assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Important status

The Android shell, encrypted multi-profile storage, foreground service, UI, and Python bridge are present. The verified desktop SRT engine still needs to be ported into `android/app/src/main/python/srt_engine.py` and tested on-device before this can be called a production APK. The checked-in Python module is an explicit non-monitoring placeholder; it does not claim to book or pay. Do not use automatic payment until that end-to-end Android test passes.

## Security

- Do not commit `.env`, card files, passwords, tokens, or logs.
- Release signing keys must be kept outside the repository.
- Automatic payment is high risk and should remain off until reservation and post-payment verification pass on the target device.
