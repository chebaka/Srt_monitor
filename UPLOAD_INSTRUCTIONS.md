# Uploading this project

The source is prepared under `/var/minis/workspace/srt-watch-android`.

Upload the folder contents to:

`https://github.com/chebaka/Srt_monitor`

Do not upload the original SRT project's `private/` directory, logs, `.env`, or card files.

After upload, open **Actions → Android APK → Run workflow**. The workflow builds a debug APK and publishes it as the `srt-watch-debug-apk` artifact.

The current checked-in engine is wired to the Android service and includes the verified SRTrain monitoring/reservation/payment path. Keep automatic payment off until the APK is installed and the end-to-end result is independently checked on the target phone.
