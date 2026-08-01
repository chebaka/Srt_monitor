# Build status

Prepared source project for `chebaka/Srt_monitor`.

Implemented shell:

- applicationId `com.chebaka.srtmonitor`
- profile-name based encrypted multi-profile storage using Android Keystore AES-GCM
- SRT/KORAIL account, route, date/time, passenger, seat, and payment fields
- KTX, ITX-새마을, ITX-청춘, 새마을, 무궁화 selection
- notification permission request and foreground service
- app notification/status callback path
- Chaquopy Python integration point
- Gradle wrapper for reproducible local Android builds

Not yet production-ready:

- The checked-in `srt_engine.py` is integrated with Chaquopy for SRT monitoring, reservation, and optional payment.
- The checked-in `korail_engine.py` is integrated with Chaquopy for KORAIL monitoring and reservation; KORAIL automatic payment is blocked.
- Live SRT/KORAIL login and reservation still need testing inside Chaquopy on a real Android device.
- APK must not be advertised as production-ready until on-device tests pass.

Build command on a computer:

```bash
cd android
./gradlew assembleDebug
```
