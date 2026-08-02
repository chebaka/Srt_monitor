# Rail Watch Android

Android app project for the SRT and KORAIL reservation monitor.

## Current scope

- Native Android UI for SRT/KORAIL account and trip conditions
- Route-based KORAIL search covering all available train types (KTX, ITX, Saemaeul, Mugunghwa, etc.) without a train-type selector
- Official KORAIL station data with cache/fallback, regional grouping, live search, recents, and favorites
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

The Android shell, encrypted multi-profile storage, foreground service, UI, and Python bridge are present. `srt_engine.py` handles SRT; `korail_engine.py` handles route-based KORAIL monitoring and reservation across train types. After a KORAIL reservation, the result notification opens installed KORAIL Talk when available, offers the official web reservation list/payment route, and automatically checks the paid ticket list until the payment deadline. KORAIL automatic card payment is intentionally disabled because the selected client does not provide a verified payment API. Live SRT/KORAIL login and reservation still need real-device verification before production use.

## Security

- Do not commit `.env`, card files, passwords, tokens, or logs.
- Release signing keys must be kept outside the repository.
- Automatic payment is high risk. It remains available only on the existing SRT path; KORAIL uses an official payment-page handoff and never sends card data to an unofficial endpoint.
