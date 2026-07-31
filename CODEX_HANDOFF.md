# Codex 인수인계 — SRT Watch Android

## 0. 목표

GitHub 저장소 `https://github.com/chebaka/Srt_monitor`의 Android 프로젝트를 완성해, 아내를 포함한 다른 사용자가 각자 자신의 SRT 계정·카드정보·모니터링 조건을 입력하고 Android 앱에서 SRT 취소표 모니터링 → 예약 → 선택적 자동결제 → 시스템 알림을 사용할 수 있게 한다.

**현재 APK가 완성된 것으로 말하지 말 것.** 현재 저장소에는 Android 셸과 검증된 Python 엔진의 이식본이 들어 있지만, 실제 Android 빌드와 기기 종단 테스트가 아직 필요하다.

## 1. 확인된 데스크톱 기준 동작

원본 프로젝트는 `/var/minis/workspace/srt-booking`에 있었지만, 비밀정보·로그는 저장소에 절대 올리지 않았다.

검증된 흐름:

```text
SRT 로그인
→ 열차 조회
→ 좌석 발견
→ 예약
→ pay_with_card()
→ get_reservations()로 paid 재확인
```

데스크톱에서 실제 예약+결제 성공 로그가 있었다. SRTrain 버전은 `2.6.7`.

중요한 결제 필드:

- `number`: 하이픈 없는 카드번호
- `password`: 카드 비밀번호 앞 2자리
- `validation_number`: 개인카드 명의자 생년월일 `YYMMDD` / 법인카드 사업자번호
- `expire_date`: SRTrain 요구 형식 `YYMM`
- `card_type`: 현재 구현은 개인카드 `J`
- 결제 성공은 `pay_with_card()` 반환만 믿지 말고 `client.get_reservations()`에서 같은 예약의 `paid == True`를 확인한다.

## 2. 현재 저장소 구조

```text
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/chebaka/srtmonitor/
        │   ├── MainActivity.kt
        │   ├── MonitorService.kt
        │   ├── ProfileStore.kt
        │   ├── StatusCallback.kt
        │   └── StatusProxy.kt
        ├── python/srt_engine.py
        └── res/
            ├── drawable/ic_stat_srt.xml
            └── values/{strings.xml,styles.xml}
```

패키지:

```text
applicationId: com.chebaka.srtmonitor
namespace: com.chebaka.srtmonitor
minSdk: 26
targetSdk: 35
compileSdk: 35
```

## 3. 가장 먼저 할 일: 빌드부터 검증

컴퓨터에서:

```bash
git clone https://github.com/chebaka/Srt_monitor.git
cd Srt_monitor/android
./gradlew assembleDebug
```

현재 저장소에 Gradle wrapper가 없을 수 있다. 그 경우 Android Studio에서 Gradle 8.11.1을 선택하거나 다음처럼 한다:

```bash
gradle assembleDebug
```

필요 환경:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- 인터넷 연결
- Android Studio Ladybug 이상 권장

현재 `build.gradle.kts`는 다음을 사용한다:

- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Chaquopy 17.0.0
- Python 3.12
- `SRTrain==2.6.7`

빌드가 실패하면 전체 로그를 반복 전달하지 말고 첫 번째 원인 50~150줄만 확인한다. 특히:

- Chaquopy가 Python 3.12/aarch64 또는 지원 ABI를 제공하는지
- SRTrain 의존성 설치 가능 여부
- AndroidX/compileSdk/Gradle 호환성
- `Theme.AppCompat` 및 `androidx.appcompat` 의존성
- Kotlin 컴파일 오류

## 4. Python 엔진

파일:

```text
android/app/src/main/python/srt_engine.py
```

현재 엔진은 데스크톱 검증 코드를 Android 호출형으로 옮긴 상태다.

주요 함수:

```python
run_monitor_json(config_json, callback)
stop_monitor()
```

`config_json`은 Android Keystore에서 복호화한 `MonitorConfig.toJson()`이다. Python 파일에는 계정·카드 원문을 넣지 않는다.

상태 전달:

```python
callback.onStatus("메시지")
```

현재 흐름:

1. config 필수값 검사
2. `SRT(...).login()`
3. 기존 중복 예약 확인
4. 열차 조회
5. 일반실/특실 좌석 확인
6. 예약
7. 자동결제 ON이면 `pay_with_card()`
8. `get_reservations()`로 `paid` 재확인
9. 성공/실패 상태 callback

필수 개선:

- Android에서 Chaquopy의 Python-to-Kotlin callback 호출이 실제로 동작하는지 테스트
- `SRT.netfunnel_helper`가 Android 패키지에서 정상 동작하는지 확인
- 프로세스 중단 시 `_stop` 이벤트가 HTTP 대기 중에도 적절히 처리되는지 확인
- 오류 메시지에서 계정·카드·토큰·세션을 제거
- `timeFrom/timeTo`는 `HHMMSS`, date는 `YYYYMMDD`로 유지
- 역명은 trim하고 `수서역` 같은 접미사를 제거할지 명시적으로 결정
- 예약 성공 후 결제 실패 시 예약번호를 알리고 결제 전 상태를 명확히 표시
- 예약/결제 중복 실행 방지: 하나의 Service에 하나의 작업만 허용

## 5. Android UI/서비스

`MainActivity.kt`:

- 프로필 이름
- SRT 아이디/비밀번호
- 출발·도착역
- 날짜/시간 선택
- 승객 수
- 일반실/특실·창측
- 자동결제
- 카드번호/비밀번호/YYMM/생년월일 또는 사업자번호
- 저장/시작/중지

현재 UI는 기능 검증용 단순 View 기반이다. production 전 개선:

- 역 선택 버튼+검색 목록
- 날짜 달력 UI
- 시간 드롭다운
- 명확한 입력 검증과 오류 표시
- 카드 입력 마스킹
- 자동결제 토글 기본 OFF
- 결제 직전 요약 및 명시적 승인 정책 결정
- 프로필 목록/전환/삭제
- 여러 프로필이 저장되며 활성 프로필 하나를 Service가 사용

`ProfileStore.kt`:

- Android Keystore AES-GCM
- 암호화된 전체 프로필 JSON을 SharedPreferences에 저장
- 프로필 이름별 저장
- active profile 저장

보완:

- 프로필 이름 empty/특수문자 검증
- 암호화 실패 시 조용히 null로 삼지 말고 사용자에게 오류 표시
- 백업 차단은 Manifest의 `allowBackup=false`로 유지
- 로그/Crashlytics에 민감정보 전송 금지
- 화면 캡처 차단 필요 여부 검토

`MonitorService.kt`:

- `ForegroundService`
- 알림 채널 `srt_monitor`
- Python 엔진 호출
- 상태 브로드캐스트
- 예약/결제 결과 시스템 알림

보완:

- Android 13+ 알림 권한 확인
- Android 14+ foreground service 정책/유형 확인
- 서비스 시작 제한과 battery optimization 안내
- 중지 버튼을 notification action으로 추가
- 서비스가 종료되면 상태를 정확히 저장
- 작업 완료/실패 시 foreground notification 정리
- 재시작 시 같은 예약을 다시 결제하지 않도록 작업 ID/예약번호 저장

## 6. 자동결제 안전 기준

자동결제는 반드시 기본 OFF로 둔다. APK가 다음을 모두 통과하기 전에는 자동결제 테스트를 하지 않는다:

1. debug APK 빌드 성공
2. 실제 기기 설치 성공
3. 앱 재시작 후 암호화 프로필 복호화 성공
4. 알림 권한 승인
5. 화면 OFF 상태에서 Foreground Service 유지
6. SRT 로그인 성공
7. 좌석 없음 폴링 성공
8. 예약만 모드에서 예약 성공
9. 예약번호와 결제 전 상태 표시
10. 테스트 카드/예약을 정리
11. 자동결제 ON에서 한 번 실행
12. 예약내역 `paid == True` 재조회 확인

금지:

- 카드값을 소스/로그/채팅에 출력
- 결제 API 요청 원문 로그
- 결제 성공 응답만으로 성공 판정
- 네트워크 오류 후 무조건 결제 재시도
- 같은 예약에 대한 중복 결제

## 7. APK 빌드·설치·검증

Debug:

```bash
cd android
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

또는 Android Studio Run.

Release는 새 keystore를 별도로 생성하고 저장소에 절대 올리지 않는다. 사용자가 최종 keystore를 제공하지 않으면 debug APK만 만든다.

기기 검증:

```bash
adb shell pm list packages | grep srtmonitor
adb shell dumpsys package com.chebaka.srtmonitor
adb logcat | grep -i -E 'SRT|Chaquopy|MonitorService|AndroidRuntime'
```

Shizuku는 설치/패키지 확인에 사용할 수 있지만 빌드 도구가 아니다.

## 8. GitHub Actions

현재 저장소에는 Actions 파일이 권한 문제로 업로드되지 않았을 수 있다. `.github/workflows/android.yml`을 컴퓨터에서 추가한다:

- checkout
- JDK 17
- Android SDK Platform 35/Build Tools 35.0.0
- Gradle 8.11.1
- `gradle assembleDebug`
- APK artifact upload

GitHub fine-grained token을 사용할 경우 `Contents: Read and write`가 필요하다. workflow 파일 생성/수정은 토큰 정책에 따라 별도 `Actions` 권한이 필요할 수 있다.

## 9. 성공 기준

다음이 모두 확인될 때만 완료라고 보고한다:

- GitHub 저장소에 소스가 있음
- 컴퓨터에서 Gradle build 성공
- APK 실제 생성
- APK 설치 성공
- 프로필별 암호화 저장/전환 성공
- 시스템 알림 권한 및 Foreground Service 동작
- 화면 OFF 모니터링 동작
- 좌석 발견/예약 성공
- 자동결제 성공 후 예약내역 `paid=True` 확인
- 민감정보가 GitHub, 로그, APK 리소스에 하드코딩되지 않음

문제가 생기면 먼저 실제 로그와 기기 상태를 확인하고, 추측으로 코드를 반복 수정하지 않는다.
