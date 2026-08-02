# Build status

Prepared source project for `chebaka/Srt_monitor`.

Implemented shell:

- applicationId `com.chebaka.srtmonitor`
- profile-name based encrypted multi-profile storage using Android Keystore AES-GCM
- SRT/KORAIL account, route, date/time, passenger, seat, and payment fields
- Route-based KORAIL search across all available train types; no train-type selector
- Official KORAIL station data with cache/fallback, regional grouping, live search, recents, and favorites
- notification permission request and foreground service
- app notification/status callback path
- Chaquopy Python integration point
- Gradle wrapper for reproducible local Android builds

Not yet production-ready:

- The checked-in `srt_engine.py` is integrated with Chaquopy for SRT monitoring, reservation, and optional payment.
- The checked-in `korail_engine.py` is integrated with Chaquopy for route-based KORAIL monitoring and reservation; KORAIL automatic card payment is blocked, while the result notification offers official app/web payment and bounded paid-ticket verification until the reservation deadline.
- Live SRT/KORAIL login and reservation still need testing inside Chaquopy on a real Android device.
- APK must not be advertised as production-ready until on-device tests pass.

Build command on a computer:

```bash
cd android
./gradlew assembleDebug
```
