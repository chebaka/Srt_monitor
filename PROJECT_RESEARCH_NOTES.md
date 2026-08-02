# Rail Watch 프로젝트 연구노트

최종 갱신: 2026-08-02

## 목적

기존 SRT 모니터를 기준선으로 보존하면서 KORAIL의 KTX·ITX·새마을·무궁화 열차를 역 구간 기준으로 모두 모니터링하고 예약할 수 있는 Android 앱으로 확장한다. SRT와 KORAIL 경로는 별도 엔진으로 유지하고, 자동결제는 명시적으로 켠 경우에만 한 번 시도하며 실패하면 공식 결제로 넘긴다.

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
  - 조회는 역명을 API에 전달하고, 역코드는 프로필 및 기존 예약 식별값으로 검증
  - `search_train_allday`로 시간대 전체를 페이지 조회하며 열차 종류 필터를 전달하지 않음
  - 예약 완료 후 결제기한까지 발권 목록을 제한적으로 자동 확인하고, 기한 만료·연속 오류 시 종료
  - 공식 결제 후 `tickets()`로 같은 구간·출발/도착 역코드·열차번호의 발권 목록을 재조회하는 결제 확인 경로 제공
  - KORAIL 자동결제는 명시적 opt-in 입력과 카드 형식 검증 후 한 번 시도하고, 창가 우선은 계속 차단
- `MonitorService.kt`
  - 프로필의 `operator`가 `KORAIL`이면 `korail_engine`, 그 외에는 `srt_engine` 호출
  - KORAIL 예약 완료 알림에서 설치된 코레일톡을 우선 열고, 공식 웹 예약목록·결제 화면을 보조 액션으로 제공
  - 예약 완료 알림에서 공식 결제 후 발권 상태 확인 액션 제공
  - 공식 앱/웹에서 결제하면 같은 세션의 발권 목록을 최대 결제기한까지 60초 간격으로 자동 확인
  - 서비스가 재시작되어도 기존 미발권 예약을 다시 찾아 결제기한까지 확인을 재개
- `ProfileStore.kt`
  - 기존 프로필과 호환되도록 `operator` 기본값 `SRT` 유지
  - 기존 저장 JSON의 `trainType` 값은 무시
  - Android Keystore AES-GCM 암호화 유지
- `MainActivity.kt`
  - 운영사 선택만 제공하며 KORAIL 열차 종류 선택은 제공하지 않음
  - SRT와 KORAIL 선택에 따라 출발역·도착역 목록을 교체
  - KORAIL 역 목록은 `StationRepository`의 공식 `stationdata` 조회 결과를 사용
  - 24시간 캐시와 281개 번들 스냅샷을 함께 두어 네트워크 실패·오프라인에서도 동작
  - 역명·역코드(`stn_nm`·`stn_cd`)를 검증하고 프로필에 역코드를 저장
  - 역 목록은 즐겨찾기·최근 선택을 먼저 보여준 뒤 수도권·강원권·충청권·전라권·경상권·기타 지역으로 그룹화
  - 입력 문자열을 포함하는 역만 즉시 필터링하고, 별표 버튼으로 철도별 즐겨찾기를 저장
  - 출발역·도착역·날짜·시간으로 KORAIL 전체 열차 조회
  - KORAIL 선택 시 자동결제 opt-in과 카드 입력을 제공하고 창가 우선은 비활성화

- `StationRepository.kt`
  - 공개 KORAIL stationdata 엔드포인트에서 역명·역코드를 갱신
  - HTTPS·응답 크기 제한·타임아웃·JSON 구조·4자리 역코드 검증 적용
  - 캐시가 없거나 갱신에 실패하면 번들된 공식 281개 스냅샷으로 폴백

## 지원 열차

| 조회 대상 | 사용자 입력 | KORAIL 조회 코드 |
|---|---|---|
| KTX·ITX·새마을·무궁화 등 전체 | 열차 종류 없음 | `TrainType.ALL` 기본값 (`109`) |

## 의존성 결정

- SRT: `SRTrain==2.6.7`
- KORAIL: `korail2` 공식 PyPI 0.4.0 소스 아카이브
- Chaquopy에서 `install("korail2==0.4.0")`는 버전 검색 실패
- 따라서 `build.gradle.kts`에 PyPI 소스 아카이브 URL을 고정해 설치
- `korail2`는 조회·예약을 제공하지만 결제 메서드는 제공하지 않는다.
- pinned `korail2==0.4.0` 소스에는 결제 메서드가 없으므로, 결제 필드 매핑은 MIT 라이선스의 보관된 `srtgo` KTX 클라이언트가 사용하는 현재 모바일 엔드포인트 구현을 참고해 최소 포팅했다: https://github.com/lapis42/srtgo/blob/main/srtgo/ktx.py
- 이 결제 경로는 공개 파트너 API가 아닌 코레일 모바일 엔드포인트 기반의 실험 기능이다. 요청은 한 번만 보내고, 네트워크 오류 후 재시도하지 않아 중복 승인 위험을 줄인다.
- KORAIL 공식 안내는 코레일톡 또는 공식 웹 결제 화면을 제공하므로, 자동결제 실패·미설정 시 공식 화면으로 handoff한다.
- 코레일톡 Android 패키지는 `com.korail.talk`이며, 앱이 없을 때는 공식 웹 예약목록 URL(`https://www.korail.com/ticket/reservation/list`)로 fallback한다.
- KORAIL 공식 보도자료는 반복 자동입력 매크로를 차단·제재한다고 안내하므로, 좌석 조회 간격은 30~60초로 제한한다. 좌석 발견 후 조회를 멈추고, 결제 UI 매크로 대신 모바일 결제 요청을 한 번 시도하며 실패하면 공식 화면과 발권 목록 확인으로 전환한다.

참고:

- https://pypi.org/project/SRTrain/
- https://pypi.org/project/korail2/
- https://github.com/carpedm20/korail2
- https://smart.letskorail.com/classes/com.korail.mobile.common.stationdata

## 검증 완료

- `py -3 -m py_compile android/app/src/main/python/srt_engine.py android/app/src/main/python/korail_engine.py`
- KORAIL 설정 검증·전체 열차 조회 경로·자동결제 요청 self-check 통과
- `android/gradlew.bat assembleDebug` 통과
- KORAIL 전체 열차 조회 self-check 통과: 검색 호출에 `train_type` 필터 없음
- KORAIL 전체 시간대 조회 self-check 통과: `search_train_allday` 호출에 `train_type` 필터 없음
- KORAIL 선택 시 전용 역 목록으로 교체되는 UI 경로 반영
- x86_64 ABI 포함 재빌드 통과: `arm64-v8a`, `x86_64`
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- x86_64 AVD에 최신 APK 재설치 성공 및 `MainActivity` 실행 확인
- UIAutomator 덤프에서 철도 선택 영역과 KORAIL 안내 문구 확인, `열차 종류` 항목 없음 확인
- 에뮬레이터에서 SRT·KORAIL 역 입력 `EXPO` → `여수EXPO` 필터 결과 선택 확인
- 에뮬레이터에서 KORAIL 창가 우선 비활성화·자동결제 opt-in UI 확인
- 에뮬레이터에서 출발역 즐겨찾기 별표 토글 확인
- KORAIL 결제 필요 알림에 공식 앱 액션과 공식 웹 예약목록·결제 액션을 붙이는 코드 경로 반영
- 코레일톡 설치 여부에 따른 공식 앱 우선·웹 fallback 결제 액션 반영
- 공식 결제 후 KORAIL 발권 목록 재조회 self-check 경로 반영
- 예약 후 결제기한까지 발권 목록을 자동 확인하는 bounded polling self-check 통과
- 서비스 재시작 시 기존 미발권 예약을 결제 대기 상태로 복원하는 경로 반영
- 결제 확인 시 출발/도착 역코드와 예약 알림의 열차번호까지 일치시키는 오탐 방지 반영
- 공식 stationdata 응답 281개·역코드 형식 확인
- `git diff --check` 통과

## 아직 검증하지 못한 것

- 실제 KORAIL 로그인·열차 조회·예약
- 실제 KORAIL 자동결제 성공·실패 응답과 카드사 추가인증/중복 승인 동작
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
- KORAIL 자동결제는 기본값을 끄고, 사용자가 켠 경우에만 한 번 호출한다.
- KORAIL 결제 응답·네트워크 오류 뒤에는 자동 재시도하지 않고 공식 결제 화면을 제공한다.
- 현재 경로는 공개 API/사업자 연동이 아닌 실험 기능으로 표시하고, 실제 결제 전 실기기 검증을 요구한다.
- 실예약·결제·Git push는 실행 직전 확인한다.
- 기존 예약 조회 실패를 신규 예약 가능으로 간주하지 않는다.

## 다음 작업

1. 실제 계정 없이 가능한 UI·설정·결제 요청 형식 검증을 유지한다.
2. 실제 계정으로 조회만 가능한 테스트 조건을 먼저 확인한다.
3. 실제 예약·결제 테스트는 사용자가 명시적으로 승인한 뒤 진행한다.
4. 실기기 테스트 결과와 API 오류를 이 노트에 추가한다.
