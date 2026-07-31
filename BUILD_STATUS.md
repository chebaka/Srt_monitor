# Build status

Prepared source project for `chebaka/Srt_monitor`.

Implemented shell:

- applicationId `com.chebaka.srtmonitor`
- profile-name based encrypted multi-profile storage using Android Keystore AES-GCM
- SRT account, route, date/time, passenger, seat, and payment fields
- notification permission request and foreground service
- app notification/status callback path
- Chaquopy Python integration point
- GitHub Actions Android build workflow

Not yet production-ready:

- The checked-in `srt_engine.py` is deliberately a placeholder and does not make network calls, reserve, or pay.
- The verified desktop Python engine must be ported and tested inside Chaquopy on a real Android device.
- APK must not be advertised as functional until GitHub Actions build and on-device tests pass.

Build command on a computer:

```bash
cd android
./gradlew assembleDebug
```
