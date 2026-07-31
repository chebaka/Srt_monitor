# Build status

Prepared source project for `chebaka/Srt_monitor`.

Implemented shell:

- applicationId `com.chebaka.srtmonitor`
- profile-name based encrypted multi-profile storage using Android Keystore AES-GCM
- SRT account, route, date/time, passenger, seat, and payment fields
- notification permission request and foreground service
- app notification/status callback path
- Chaquopy Python integration point
- Gradle wrapper for reproducible local Android builds

Not yet production-ready:

- The checked-in `srt_engine.py` is integrated with Chaquopy and contains monitoring, reservation, and optional payment logic.
- Live SRT login, reservation, and payment still need testing inside Chaquopy on a real Android device.
- APK must not be advertised as production-ready until on-device tests pass.

Build command on a computer:

```bash
cd android
./gradlew assembleDebug
```
