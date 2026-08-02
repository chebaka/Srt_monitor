# Rail Watch 프로젝트 연구노트

최종 갱신: 2026-08-02

## 목적

기존 SRT 모니터를 기준선으로 보존하면서 KORAIL의 KTX·ITX·새마을·무궁화 열차를 역 구간 기준으로 모두 모니터링하고 예약할 수 있는 Android 앱으로 확장한다. SRT와 KORAIL 경로는 별도 엔진으로 유지하고, 검증되지 않은 자동결제는 실행하지 않는다.

## 현재 Git 상태

- 기준선: `main` 브랜치
- 구현 브랜치: `feature/korail-monitor`
- 초기 구현 커밋: `beb2452 feat(rail): add KORAIL monitoring path`
- 현재 구현: KORAIL 열차 종류 선택 제거, 전체 열차 조회, 운영사별 역 목록 분리
- 기준선 보존 원칙: SRT 기존 엔진을 직접 교체하지 않고 서비스에서 엔진을 분기한다.

## 현재 구조

- `android/app/src/main/python/srt_engine.py`
  - 기존 SRT 로그인·조회·예약·선택적 결제 경로
- `android/app/src/main/python/korail_engine.py`
  - KORAIL 로그인·조회·좌석 필터·예약 경로
  - 예약 완료 후 결제 필요 상태로 종료
  - 자동결제와 KORAIL 창가 우선은 입력 단계에서 차단
- `MonitorService.kt`
  - 프로필의 `operator`가 `KORAIL`이면 `korail_engine`, 그 외에는 `srt_engine` 호출
- `ProfileStore.kt`
  - 기존 프로필과 호환되도록 `operator` 기본값 `SRT` 유지
  - 기존 저장 JSON의 `trainType` 값은 무시
  - Android Keystore AES-GCM 암호화 유지
- `MainActivity.kt`
  - 운영사 선택만 제공하며 KORAIL 열차 종류 선택은 제공하지 않음
  - SRT와 KORAIL 선택에 따라 출발역·도착역 목록을 교체
  - KORAIL 역 목록은 공식 `stationdata` 기준 281개 스냅샷
  - 출발역·도착역·날짜·시간으로 KORAIL 전체 열차 조회
  - KORAIL 선택 시 자동결제·창가 우선 비활성화

## 지원 열차

| 조회 대상 | 사용자 입력 | KORAIL 조회 코드 |
|---|---|---|
| KTX·ITX·새마을·무궁화 등 전체 | 열차 종류 없음 | `TrainType.ALL` 기본값 (`109`) |

## 의존성 결정

- SRT: `SRTrain==2.6.7`
- KORAIL: `korail2` 공식 PyPI 0.4.0 소스 아카이브
- Chaquopy에서 `install("korail2==0.4.0")`는 버전 검색 실패
- 따라서 `build.gradle.kts`에 PyPI 소스 아카이브 URL을 고정해 설치
- `korail2`는 조회·예약을 제공하지만 결제 API는 제공하지 않으므로 자동결제 구현에 사용하지 않는다.

참고:

- https://pypi.org/project/SRTrain/
- https://pypi.org/project/korail2/
- https://github.com/carpedm20/korail2
- https://smart.letskorail.com/classes/com.korail.mobile.common.stationdata

## 검증 완료

- `py -3 -m py_compile android/app/src/main/python/srt_engine.py android/app/src/main/python/korail_engine.py`
- KORAIL 설정 검증·전체 열차 조회 경로·자동결제 차단 self-check 통과
- `android/gradlew.bat assembleDebug` 통과
- KORAIL 전체 열차 조회 self-check 통과: 검색 호출에 `train_type` 필터 없음
- KORAIL 선택 시 전용 역 목록으로 교체되는 UI 경로 반영
- x86_64 ABI 포함 재빌드 통과: `arm64-v8a`, `x86_64`
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- x86_64 AVD에 APK 재설치 성공 및 `MainActivity` 실행 확인
- UIAutomator 덤프에서 철도 선택 영역과 KORAIL 안내 문구 확인, `열차 종류` 항목 없음 확인
- `git diff --check` 통과

## 아직 검증하지 못한 것

- 실제 KORAIL 로그인·열차 조회·예약
- 실제 SRT 로그인·예약·결제
- 네트워크 타임아웃과 KORAIL API 응답 변화에 대한 실기기 확인

## 에뮬레이터 상태

- AVD 존재:
  - `srt-api35-arm64`
  - `srt-api35-x86_64`
- 앱 APK ABI: `arm64-v8a`, `x86_64`
- 현재 테스트 기기: `emulator-5554`, `x86_64`, boot completed
- `emulator -accel-check`: WHPX 사용 가능
- arm64 AVD는 x86_64 호스트에서 QEMU가 지원하지 않아 실행 불가
- x86_64 AVD로 전환해 설치·실행 검증 완료

## 안전 규칙

- 비밀번호·카드번호·카드 비밀번호·인증번호를 이 파일이나 Git에 기록하지 않는다.
- 실제 예약은 좌석을 발견하면 발생할 수 있으므로 테스트 전 자동결제는 꺼둔다.
- KORAIL 결제 호출을 추가하지 않는다.
- 실예약·결제·Git push는 실행 직전 확인한다.
- 기존 예약 조회 실패를 신규 예약 가능으로 간주하지 않는다.

## 다음 작업

1. 실제 계정 없이 가능한 UI·설정 검증을 유지한다.
2. 실제 계정으로 조회만 가능한 테스트 조건을 먼저 확인한다.
3. 예약 테스트는 사용자가 명시적으로 승인한 뒤 진행한다.
4. 실기기 테스트 결과와 API 오류를 이 노트에 추가한다.
