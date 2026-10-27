---
id: PLAN-2026-10-26-haengcha-app
type: plan
status: in-progress
created: 2026-10-26
updated: 2026-10-27
scope: 행차 앱 MVP 개발 계획, 개발 환경, 에뮬레이터 검증 결과(차속, 판정, 감찰, 음성, 지역, 어린이보호구역, 주행 복원, 정차 화면, Pleos Vehicle·NaviHelper SDK 연동 기능, 세로 화면·시연 빌드, 지역 도감·칭호·동반자)
related:
  - C:\Users\smuge\OneDrive\Repository\HyundaiMotors_APP\행차_기획서_6차.md (4·8·9·12·13·15장)
  - README.md (빌드·실행·에뮬레이터 시연 순서)
  - speedprobe/
  - core-judge/
  - vehicle/
  - app/
  - tools/vc_drive.py
  - tools/analyze_probe.py
  - tools/make_voice_clips.py
  - tools/models/
  - tools/render3d/
  - docs/submission/haengcha-demo-guide.md
last_verified_commit: 1fe1709 이후 커밋하지 않은 작업 트리(2026-10-27, 5.6~5.9의 검증 결과와 같은 코드)
---

# 행차 앱 개발 계획

## 1. 목표

2026-11-15 공모전 제출. 범위는 기획서 12.1의 MVP를 따른다. 실제로 동작해야 하는 것은 주행 판정 → 경험치·레벨 → 테마 해금 → 지역 진입과 암행어사 감찰이고, 나머지는 화면 목업이다.

## 2. 개발 환경 (2026-10-26 구성)

| 항목 | 내용 |
|---|---|
| IDE | Android Studio 2026.2.1.8 |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`. 플랫폼 34·35, build-tools 34.0.0·35.0.0, 에뮬레이터 37.2.12, platform-tools 37.0.1 |
| Pleos 에뮬레이터 | `system-images;android-34;pleos_car_emulator;x86_64` (Pleos Connect v2.1.0). 사용자 저장소 `~/.android/repositories.cfg`에 Pleos 사이트 등록. 신형 `android` CLI는 사용자 저장소를 읽지 않아 cmdline-tools 22.0의 sdkmanager로 설치함 |
| 가상 기기 | `Pleos_Connect_API34` (Pleos Connect 기기 정의, 메모리 4GB, 콜드 부팅, 하드웨어 그래픽) |
| Virtual Console | 에뮬레이터 내장. `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088` |
| 빌드 기준 | AGP 8.8.2, Kotlin 2.0.0, Gradle 8.10.2, compileSdk 35, minSdk 28 (Pleos Vehicle SDK 샘플 기준선) |
| Pleos 프로젝트 | Workspace '행차'. Client Secret은 저장소에 넣지 않는다 |

Pleos SDK 제공 상태 (Workspace 기준, 2026-10-26)

| SDK | 상태 | 받는 곳 |
|---|---|---|
| Vehicle State | 사용 가능 (조회 11개 항목) | `ai.pleos.playground:Vehicle:2.3.2` |
| NaviHelper | 사용 가능 (2개 항목) | `ai.pleos.playground:NaviHelper:3.0.5` |
| FusedLocation, TextToSpeech, SpeechToText, Vehicle Control | 준비 중 | - |

Maven 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`는 인증 없이 받을 수 있다. 위치는 Android 위치 API(`LocationManager`)로, 음성은 미리 녹음한 문장으로 대체한다(5.2).

렌더 도구
- Blender 5.2(파이썬 모듈 `bpy`)로 모델을 만든다. 스크래치 가상환경에 설치해 쓰며 저장소에는 넣지 않는다.
- three.js 장면은 Node 22 + `puppeteer-core`로 시스템 Chrome을 띄워 찍는다. 헤드리스 Chrome이 호스트 GPU(D3D11, AMD Radeon 780M)를 쓰게 해 11초 영상 한 편(264프레임)을 15초 안팎에 뽑는다. 인코딩·WebP 변환은 ffmpeg.

## 3. 저장소 구조

| 경로 | 상태 | 내용 |
|---|---|---|
| `speedprobe/` | 완료 | 차속 검증 앱. 차속·기어·주차 브레이크·충전 포트·주행 규제를 구독해 CSV로 남긴다. `PleosProbe`는 Vehicle·NaviHelper SDK와 AAOS 프로퍼티 값을 logcat(`PleosProbe`)에 남긴다(5.4) |
| `tools/vc_drive.py` | 완료 | Virtual Console API로 주행 시나리오(`harsh`, `calm`, `ulsan`, `eco`, `rainynight`, `unbelted`, `short`, `landmark`)를 재생한다. `ulsan`·`landmark`는 `adb emu geo fix`로 GPS도 옮긴다. 시나리오마다 전조등·와이퍼·경고등·적산거리·잔량을 바꾸고, `--preset`으로 주행 없이 값만 바꾼다 |
| `tools/analyze_probe.py` | 완료 | 프로브 CSV에서 수신 주기, 지연, 가감속, 기어·주행 규제 변화를 요약한다 |
| `core-judge/` | 완료 | 판정 엔진(순수 Kotlin, JVM 테스트). 급가속·급감속, 1분 구간 경험치·평정심, 레벨·조선 테마, 개근, 주행 시작·종료(`TripTracker`), 암행어사 감찰(`Inspection`), 지역·단골 등급(`Places`), 어린이보호구역·아이 지킴이(`SchoolZone`), 주행 일지(`TripJournal`), 동반자(`Companion`), 기록 형식(`TripRecord`)·진행 요약, 안내 문구(`Announcements`). SDK 연동 판정: 검약·빗길·야행·안전띠(`Extras`), 옛길 원정(`Journey`), 대장간 퀘스트(`Maintenance`), 명소 도착(`Landmarks`), 역참·안내 타이밍(`Rest`), 개근 보호권(`Progress`). 주행 엔진(`haengcha.drive`): `DriveEngine`(신호·위치 → 주행 시작·종료 → 판정 → 저장 → 음성 → 축하 연출), 기능별 부품(`QuestDesk`, `RestStop`, `ArrivalWatch`, `LiveBoard`, `SceneSignals`), 바깥 연결 인터페이스(`Clock`, `Scheduler`, `RecordStore`, `JournalStore`, `DriveSettings`, `VehicleListener`, `NaviListener`), 화면 상태(`DriveUiState`) |
| `vehicle/` | 완료 | 수집 계층. AAOS Car API로 차속(최대 주기)·기어·충전 포트·주행 규제(영상 금지 여부 포함)·바깥 기온, Pleos Vehicle SDK(`PleosVehicleSignals`)로 잔량·전비·적산거리·경고등·엔진오일·안전띠·전조등·와이퍼, NaviHelper(`PleosNavi`)로 목적지·도착·회전 안내를 받고 휴게소 검색 화면을 요청한다 |
| `app/` | 완료(시연판) | `DriveController`(주행 엔진을 Android에 잇는 연결 층: SystemClock, 메인 Handler, 차량 신호·내비게이션 구독), `LocationSource`, `TripStore`(운전자별 `trips*.csv`), `TripJournalFile`(진행 중 주행 일지), `CollectService`(위치 유형 포그라운드 서비스), `AppPrefs`(운전자·콜라보 보유), `VoiceGuide`, Compose 화면(정차 화면, 주행 계기판, 어린이보호구역, 도감·쉬어가는 행차, 동반자, 콜라보 상점, 운전자 전환, 축하 연출). 디버그 빌드 전용 `DebugReceiver`. 패키지명은 임시 `haengcha.app` |
| `app/src/main/assets/voice/` | 완료 | 안내 문장 75개 녹음(SDK 연동 기능 34개 추가, `--only-missing`으로 기존 녹음은 그대로)(소개 영상과 같은 Supertonic 3 0번 목소리, 22.05kHz 모노). `tools/make_voice_clips.py`로 다시 만든다 |
| `app/src/main/assets/motion/` | 완료 | 움직이는 배경 22편(궁궐, 울산 등급 5, 횡성 등급 5 × landscape·portrait), 11초 반복 H.264, 합계 5.4MB. `motion.json`은 정지 그림과 같은 순간의 영상 시각 |
| `app/src/main/assets/scenes/`, `icons/` | 완료 | 정지 배경 22장, 탈것 레이어 10장(투명), 도감 아이콘 5장 |
| `assets3d/models/` | 완료 | Blender 모델 glTF 7종(소달구지·인력거·가마·꽃가마·어가 행렬·고래·한우) |
| `tools/models/` | 완료 | `joseon_set.py`(조선 탈것, 영상 프로젝트 스크립트에 소달구지 추가), `region_set.py`(고래·한우) |
| `tools/render3d/` | 완료 | three.js 무대(`stage.js`)와 렌더 스크립트(`render.mjs`). 정지 그림·탈것 레이어·움직이는 배경·도감 아이콘·앱 아이콘을 만든다 |
| `docs/submission/app_icon_512.png` | 완료 | 제출용 512px 앱 아이콘 |

설계 원칙
- 수집은 Application 수명에 묶어, 내비게이션이 앞에 있어도 이어지게 한다 (기획서 9.2).
- 주행 모드 전환은 기어가 아니라 `CarUxRestrictions` 상태를 기준으로 한다.
- 가감속은 1초 이상 창으로 계산한다 (5장 결과).
- 좌표는 저장하지 않는다. 기록에는 지난 지역 이름과 아이 지킴이 횟수만 남긴다. 내비게이션 목적지 좌표도 도착 판정에만 쓰고 기록에는 명소 이름만 남긴다.
- Pleos SDK 값은 항목마다 처음 읽기를 해 보고, 차량이 지원하지 않으면 그 기능만 빠지거나 다른 값으로 대신한다(전비 → 잔량·적산거리, 적산거리 → 차속으로 잰 거리, 안전띠 경고등 → 체결 신호).
- 주행 중 화면의 움직임은 차량이 보내는 주행 규제를 따른다. 영상 금지(`UX_RESTRICTIONS_NO_VIDEO`)가 걸리면 배경 영상을 멈춘다.

## 4. 일정

| 기간 | 할 일 |
|---|---|
| 10/26 | 개발 환경, 차속 검증 앱과 재생·분석 도구, 판정 엔진, 수집 계층, 앱(주행 시작·종료, 판정, 저장, 감찰, 녹음 음성, 지역·단골, 어린이보호구역, 주행 복원, 포그라운드 서비스, 축하 연출, 쉬어가는 행차, 동반자·콜라보 상점·운전자 전환 목업), 3D 정차 화면과 움직이는 배경, 앱 아이콘 (완료) |
| 10/27 | Pleos Vehicle·NaviHelper SDK 실측(5.4)과 연동 기능 9개: 검약 행차, 대장간 퀘스트·개근 보호권, 역참, 옛길 원정, 야행, 날씨 장면, 안전띠 개근, 도착 스탬프, 안내 타이밍 (완료) |
| 10/27 | 앱의 `DriveController`(692줄)를 순수 Kotlin 주행 엔진(`core-judge`의 `haengcha.drive`)과 기능별 부품으로 나누고, 앱에는 연결 층만 남김. 가짜 시계·저장소·차량 신호로 주행을 재생하는 통합 테스트 16개 추가(5.5) (완료) |
| 10/27 | 세로(한 칸) 화면 점검, 시연 빌드(출시 키 서명, `-demo`)와 심사용 안내문(5.6). 지역 도감 14곳·명소 31곳·칭호 34종·동반자 11종, 도감 지역 12곳 3D 장면(5.7). 주행 중 탈것 뒷모습과 소실점 초롱, 화면 배치 기본값(5.8). 녹음 Opus 전환, 바다 장면 유등, 두 칸 화면 확인(5.9) (완료) |
| 10/28 ~ 11/12 | Pleos 문의(DO 인정 절차, TTS SDK 공개 일정, Driving View 테마 연동), 출시 서명 방식 결정과 서명 APK, 데브박스·실차 확인, 시연 영상에 앱 화면 반영 |
| 11/13 ~ 11/15 | 통합 테스트, 제출 |

정해야 할 것
- 앱 패키지명. 지금 검증 앱은 임시로 `haengcha.speedprobe`를 쓴다.
- ~~앱 카테고리~~ → **Mobility Services로 추진 (2026-10-26 결정).** 근거는 5.1. Workspace 프로젝트 카테고리는 만든 뒤에 바꿀 수 없어서, 기존 '엔터테인먼트' 프로젝트를 지우고 '모빌리티 서비스'로 새로 만들었다(Project ID 바뀜, SDK 상태는 같음). 마켓 카테고리는 Pleos App Market Console에 앱을 제출할 때 따로 고르므로 그때도 Mobility Services를 고른다([App Review Submission](https://document.pleos.ai/en/docs/pleos-only/distribute/app-submission-guide)).
- 급가속·급감속 임계값과 경험치 규칙 (지금은 아래 4.1의 임시값).

### 4.1 판정 규칙 (core-judge, 임시값)

| 규칙 | 값 | 근거 |
|---|---|---|
| 가감속 계산 | 1초 전 속도와 현재 속도의 차이 | 한국교통안전공단 운행기록 분석 방식(아래 출처) |
| 급가속 / 급감속 | +3.0 / −3.5 m/s² 이상 | 임시값. 공단 공식 수치를 찾지 못함. 완만한 주행(1~2.5m/s²)과 시나리오의 급가속(3.7)·급감속(−5.6)을 가르는 값 |
| 한 건으로 묶기 | 기준을 벗어난 뒤 3초 안에 다시 넘으면 같은 건 | 공단 과속 판정의 "간격 3초 이내 1건" 규칙을 빌림 |
| 수신 끊김 | 비교 표본이 2초 넘게 오래되면 판정 안 함 | 데이터 공백에서 오탐 방지 |
| 경험치 | 1분 구간 중 30초 이상 달렸고 급가속·급감속이 없으면 10XP | 기획서 4.1 "급가속·급감속이 적은 구간에 경험치". 깎는 규칙 없음 |
| 평정심 보너스 | 위 구간의 평균 차속이 20km/h 미만이면 +5XP | 기획서 4.1 정체 구간 보너스 |
| 레벨 | Lv.0에서 시작, Lv.n 누적 경험치 50·n·(n+1)(Lv.1 100, Lv.4 1000). 소달구지 Lv.0, 인력거 Lv.1, 가마 Lv.4, 꽃가마 Lv.8, 어가 행렬 Lv.12 | 소달구지·인력거 단계는 2026-10-26 요청. 가마 Lv.4는 기획서 부록 B |
| 지역 | 울산(시청 반경 20km, 고래), 횡성(군청 반경 15km, 한우). 그 지역을 지난 주행이 있었던 날 수로 단골 등급: 1·3·7·15·30일에 1~5단계. 지역 진입 음성은 지역마다 하루 한 번, 주행 중 경계를 넘을 때만 | 기획서 4.3, 부록 A. 범위·일수는 임시값 |
| 어린이보호구역 | 시연용 지정 좌표 1곳(울산 시내, 반경 300m). 들어서면 다른 안내 없이 "어린이보호구역입니다. 천천히!". 10초 넘게 머물며 한 번도 30km/h를 넘지 않으면 아이 지킴이 +20XP | 기획서 4.7. 공공데이터 연동 전 |
| 동반자 | 이달 주행 10회면 거북이 합류. 판매하지 않음 | 기획서 4.8, 임시값 |
| 개근 | 하루 5분 이상 달린 날만 셈. 급가속·급감속을 10km당 1건까지 봐줌. 운전 안 한 날은 끊기지 않음 | 기획서 4.4 |
| 암행어사 감찰 | 하루 최대 2회, 주행마다 60% 확률. 주행 시작 1~15분 뒤부터 10분. 그동안 급가속·급감속 0건이고 5분 이상 달렸으면 통과(마패, +30XP). 급가속·급감속이 있으면 실패(알리지 않음, 하루 횟수에 포함). 주행이 먼저 끝나거나 거의 서 있었으면 미완료(하루 횟수 제외) | 기획서 4.6 |
| 주행으로 보지 않음 | 30초 미만 움직인 주행은 저장·안내하지 않음 | D단에 잠깐 넣었다 뺀 경우 |
| 검약 행차 | 3km 이상 달리고 이번 주행 전비 6km/kWh 이상이면 +15XP. 전비는 SDK 주행 전비, 없으면 적산거리 ÷ (잔량 감소% × 77.4kWh). 잔량이 1% 넘게 줄었을 때만 계산. 5km/kWh 기준보다 아낀 kWh를 곳간 쌀 섬으로 쌓음 | 기획서 4.1. 배터리 용량은 에뮬레이터 차량 값 |
| 빗길·눈길 | 와이퍼를 켜고 움직인 시간이 2분 이상이고 급가속·급감속이 없으면 +15XP. 와이퍼를 켠 동안 바깥 기온 1도 이하면 눈길 | 기획서 4.1, 임시값 |
| 대장간 퀘스트 | 경고등 TPMS → 타이어, ENGINE_OIL_PRESSURE·오일 잔량 낮음 → 엔진오일. 1분 넘게 열려 있다가 경고가 꺼지면 보호권 1장(그보다 짧으면 조용히 닫음). 열림 안내는 3초 뒤에도 열려 있을 때만 | 기획서 4.4 |
| 개근 보호권·안전띠 | 기준을 못 지킨 날 보호권이 있으면 한 장 쓰고 이어 감(개근 일수에는 넣지 않음). 안전띠를 매지 않고 출발한 주행이 있는 날은 기준을 못 지킨 날 | 기획서 4.4 |
| 옛길 원정 | 영남대로 380km, 지점 14곳(판교 22, 용인 40 … 동래 380km). 주행마다 적산거리 차이(없으면 차속 거리)를 쌓음 | 960리를 380km로 잡은 대략값 |
| 역참 | 쉬지 않고 120분 달리면 안내(경로 위 휴게소가 30km 안이면 '앞길에 휴게소', 아니면 휴게소 검색 화면). 그 뒤 15분 넘게 쉬고 떠나면 +20XP. 15분보다 짧게 선 것은 이어 달린 것 | 임시값 |
| 명소 도착 | 내비게이션 도착 알림, 또는 목적지 300m 밖에서 출발해 300m 안에서 주행을 끝냄(출발 위치를 모르면 끝낸 곳만 봄). 목적지 이름으로 명소 7곳을 알아봄. 첫 도착 +20XP | 기획서 4.9 |
| 주행 중 화면 | 1초마다 갱신. 끝난 1분 구간만 경험치로 보여 주고(감찰 보너스 제외) 진행 중 구간은 지난 초와 급조작 유무만. 알림 띠는 6초: 옛길 지점 > 무사고 5분마다 > 구간 경험치 순. 영상 금지 규제 중 연출은 1초에 한 번만 바뀜 | 기획서 9.1, CarUxRestrictions NO_VIDEO(초당 1프레임 초과 금지) |
| 안내 타이밍 | 주행 중 다음 회전이 300m 안이면 행차 음성을 미루고 회전을 지나거나 30초가 지나면 냄. 어린이보호구역 안내와 주차 중 안내는 미루지 않음 | 기획서 9.3 |

출처: [운전 습관 개선을 위한 위험 운전 분석 어플리케이션의 설계 및 구현 (KoreaScience)](https://www.koreascience.or.kr/article/CFKO201531751944204.pdf). 공단 11대 위험운전행동 기준을 인용하며 1초 전 속도 차이로 판단한다고 설명하고, 과속 유형 표에 3초 규칙이 나온다.

## 5. 1주차 차속 검증 결과 (2026-10-26, 에뮬레이터)

시나리오는 `tools/vc_drive.py` 기본값이다. 정차 5초, 0→50km/h 10초, 정속 5초, 50→90km/h 3초(급가속), 정속 7초, 90→40km/h 2.5초(급감속), 40→0km/h 7.5초, 정차 3초 순서로, 10Hz로 전송했다.

| 항목 | 결과 |
|---|---|
| `PERF_VEHICLE_SPEED` 설정 | CONTINUOUS, 지원 10~20Hz, 20Hz 요청으로 구독 성공 |
| 수신 주기 (정차, 앱 첫 실행) | 12.5Hz, 이벤트 간격 50~402ms |
| 수신 주기 (주행 재생) | 10.1Hz, 이벤트 간격 중앙 100ms, 95% 102ms, 최대 147ms |
| 수신 지연 (VHAL 타임스탬프 → 콜백) | 중앙 2.0ms, 95% 10.5ms, 최대 189ms |
| 가감속 (설계값) | +3.70 / −5.56 m/s² |
| 가감속 (보낸 값, 0.5초 창) | +3.89 / −5.84 m/s² |
| 가감속 (보낸 값, 1초·2초 창) | +3.89 / −5.64, +3.75 / −5.58 m/s² |
| 가감속 (앱 측정, 0.5초 창) | +4.19 / −6.32 m/s² |
| 가감속 (같은 기록, 1초 창 = core-judge) | +4.05 / −5.94 m/s² |
| 권한 | `CAR_SPEED`는 실행 중 동의 권한이고 위치 권한 그룹이라 시스템 창에 '위치 정보 액세스'로 뜬다. `CAR_POWERTRAIN`, `CAR_ENERGY_PORTS`는 일반 권한으로 자동 부여 |
| 주행 규제 | D단에 넣는 순간(정차 중 포함) `do=true`, `restrictions=0x50`(영상 금지·설정 금지). P단으로 돌아가면 해제 |
| 화면 | `distractionOptimized`를 선언한 디버그 앱은 주행 중에도 표시됨 |

정리
- API, 권한, 구독은 문제없다. 기어·주차 브레이크·충전 포트도 같은 방식으로 읽힌다.
- 0.5초 창 가감속은 전송 지터와 정수 km/h 때문에 설계값보다 13~14%, 보낸 값보다 8% 크게 나왔다. 판정은 1초 이상 창이나 회귀로 계산한다.
- 에뮬레이터 수치는 Virtual Console의 송신 주기(10Hz, 액추에이터 100ms 틱)를 보여 줄 뿐 실차 주기가 아니다.

### 5.1 앱 골격 검증 (2026-10-26, 에뮬레이터)

| 시나리오 | 저장된 기록 | 기대값 |
|---|---|---|
| `calm` (230초 출퇴근, 정체 구간 포함, 행차 앱이 앞에 있음) | 1.670km, 3.63분, 급가속 0·급감속 0, 안전 4구간, 평정심 2구간, **+50XP** | 이론 거리 1,670m, 50XP |
| `harsh` (43초, 차속 검증 앱이 앞에 있고 행차 앱은 뒤) | 0.459km, 급가속 1·급감속 1, 0XP | 이론 거리 약 459m |

- 기어 D → 주행 시작, P → 종료와 저장, 정차 화면 갱신(Lv.1, 오늘 50XP, 지난 주행 목록)까지 이어진다.
- 다른 앱이 앞에 있어도 기록은 이어진다(두 번째 시나리오).
- **주행 중에는 행차 앱 화면이 시스템에 막힌다**("주행 중에는 사용할 수 없습니다"). `CarPackageManager.isActivityDistractionOptimized`로 물어보니 `distractionOptimized`를 선언한 두 앱 모두 `false`였다. 에뮬레이터는 user 빌드라 직접 설치한 앱의 DO 선언을 인정하지 않는다. 차속 검증 앱이 주행 중에 막히지 않은 것은 홈 화면 쪽 예외 동작으로 보이며 원인은 확인하지 못했다.
- Pleos 정책상 DO가 아닌 앱은 주차 중에만 쓸 수 있다([Driving Restriction Policy by Region](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)). 주행 중 기능이 제한적으로 허용될 수 있는 카테고리는 Music/Audio, Maps/Navigation, Mobility Services이고, Mobility Services 예시에 "주행과 밀접한 차량 관리 서비스"가 있다([App Categories](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-category)). Lifestyle·Entertainment는 목록에 없다.
- **결정 (2026-10-26):** 앱 카테고리는 Mobility Services로 추진한다. 그러면서도 **주행 중 화면이 허용되지 않아도 동작하게** 유지한다. 수집과 판정은 화면과 무관하게 돌고, 주행 중 안내는 짧은 음성으로 하고, 보상은 정차 후에 보여 준다. 주행 중 정적 한 줄(기획서 9.1)은 카테고리 심사에서 허용될 때만 보이는 화면이다. 지금 코드는 규제 중이면 그 한 줄을 그리고, 허용되지 않으면 시스템이 가린다. 어느 쪽이든 수집 흐름에는 영향이 없다.
- portrait(한 칸) 화면은 코드에는 있지만 에뮬레이터에서 띄워 보지 못했다. Pleos 홈 화면의 앱 영역은 1682×1210(landscape)이었다.

### 5.2 암행어사 감찰과 음성 안내 검증 (2026-10-26, 에뮬레이터)

디버그 빌드에서 감찰 시간을 줄여(`--es inspection "시작초,감찰초,최소주행초"`) 시험했다.

| 시나리오 | 결과 |
|---|---|
| `calm` + 감찰 20초 뒤 120초 | 통과. 기록 `xp=80`(50 + 마패 30), `inspected=1, mapae=1`. 안내 문장 "…마패를 하사합니다. Lv.1 달성! 인력거 테마가 열렸습니다. 오늘도 안전하게, 행차하셨습니다." |
| `harsh` + 감찰 15초 뒤 25초 | 실패(급가속·급감속 2건). 기록 `inspected=1, mapae=0`. 출두 없이 마무리 인사만 재생 |
| `calm` (그날 감찰 2회를 이미 함) | 하루 최대 2회 제한에 걸려 감찰하지 않음(`감찰=null`), 50XP |
| `calm` + 감찰 20초 뒤 120초, 디버그로 하루 제한 해제 | 통과, +80XP. 출두와 마무리 두 문장을 이어 붙여(묵음은 맨 앞에만) 끝까지 재생. 정차 화면에 마패 2개·"암행어사 출두 · 마패 하사" 표시 |
| 기어 D → P (움직임 없음) | 주행으로 보지 않음. 저장·안내 없음 |

- 음성은 탑승("주상전하 납시오!")과 주차 후 안내 모두 `NOTIFICATION` 용도, `GAIN_TRANSIENT_MAY_DUCK` 포커스를 받아(grant) 끝까지 재생됐다.
- **Pleos 에뮬레이터의 음성 엔진(`ai.pleos.playground.caas`)은 Android 표준 `TextToSpeech` API로 쓸 수 없다.** TTS 서비스 인텐트 필터에 `DEFAULT` 카테고리가 없어 엔진 목록에 잡히지 않는다(앱이 본 엔진 목록이 비어 있음). `pleos.car.permission.TTS_SERVICE`(일반 권한)와 `<queries>`를 선언해도 같다. 그래서 고정 문장은 녹음으로 재생하고, 녹음이 없는 문장은 쓸 수 있는 TTS 엔진이 있을 때만 합성한다.
- 선행 묵음은 Pleos 가이드대로 데이터 앞에 180ms를 붙인다. 여러 문장은 하나로 이어 맨 앞에만 넣는다.

Virtual Console API (페이지 스크립트에서 확인)
- `POST /browser/session?client=<id>`를 1초마다 보내야 한다. 3초를 넘기면 `PUT` 요청이 `503 emulator_inactive`로 거부된다.
- `PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive`
- `PUT /vehicle/controls?speed=<정수 km/h>&accelerator=<%>&brake=<%>&steering=<deg>`. 소수 차속은 `400 invalid_speed`

### 5.3 지역·어린이보호구역·주행 복원·정차 화면 검증 (2026-10-26, 에뮬레이터)

| 시나리오 | 결과 |
|---|---|
| `ulsan` (155초, 울산 밖 출발 → 25초에 울산 → 62~96초 시연 보호구역 25km/h) | 탑승 안내 → "울산에 오셨습니다. 고래 테마가 켜집니다." → "어린이보호구역입니다. 천천히!" → 통과(최고 25.0km/h, 아이 지킴이) → 주차 후 "아이 지킴이! … Lv.2 달성! …". 기록 `regions=ULSAN, guardian=1`, 경험치에 보너스 20 포함 |
| `calm`을 횡성에서 재생하다 70초에 `am force-stop`, 6초 뒤 다시 실행 | "일지의 주행 이어 감: 표본 690개, 지역 [HOENGSEONG]". 주차 후 저장 1.578km(끊긴 8초만큼 줄어듦), 안전 4구간·평정심 2구간·50XP로 끊기지 않은 주행과 같음. 탑승 안내는 다시 나오지 않음 |
| 새 운전자 3, 시연 기록(울산 방문 2일, 이달 주행 9회) 뒤 `ulsan` | 주차 후 축하 연출이 차례로: 아이 지킴이 → 인력거 테마가 열렸습니다 → 단골 등급이 올랐습니다(동 고래) → 이달의 동반자 거북이 합류. 음성도 같은 순서 |
| 충전 포트 연결(디버그 브로드캐스트) | "쉬어가는 행차입니다. 오늘 모은 도감을 정리해 볼까요?" 음성과 함께 도감이 '쉬어가는 행차'로 열림 |
| D단 주행 중 앱 영역을 2초 간격으로 캡처 | 차이 없음(배경 영상 멈춤, 규제값 0x50에 영상 금지 포함). P단에서는 고래 도약·등대 회전이 이어짐 |

- 위치 권한(`ACCESS_FINE_LOCATION`)과 `CAR_SPEED`를 한 번에 요청한다. 위치 수신은 에뮬레이터 GPS 제공자, `adb emu geo fix`로 바로 반영됐다.
- 포그라운드 서비스는 `dumpsys`에서 `isForeground=true types=0x8`(location)로 확인했다.
- **Pleos 에뮬레이터의 WebView(ai.pleos.android.webview 131)는 캔버스·WebGL·이미지·그라데이션을 화면에 합성하지 못한다.** 시험 페이지에서 단색 바탕과 글자만 보였고, 2D 캔버스 픽셀을 읽으면 그림은 그려져 있었다(`getImageData`로 하늘색 확인). WebGL 2.0(ANGLE, 에뮬레이터 GLES 3.1)도 컨텍스트는 생긴다. 소프트웨어 레이어로 바꿔도 같았고 로그에 `eglDestroyContext … EGL_BAD_MATCH`가 남았다. 그래서 three.js 장면을 앱에서 실시간으로 돌리지 않고, 같은 장면을 11초 반복 영상으로 미리 렌더해 `MediaPlayer` + `TextureView`로 재생한다.
- Pleos 홈 화면은 앱을 TaskView로 띄워, 이미 떠 있는 화면에 `am start`로 보낸 인텐트가 `onNewIntent`로 오지 않는다. 디버그 명령은 디버그 빌드 전용 브로드캐스트 수신기로 받는다.

### 5.4 Pleos Vehicle·NaviHelper SDK 실측과 연동 기능 검증 (2026-10-27, 에뮬레이터)

`speedprobe`의 `PleosProbe`로 SDK 값을 읽고, Virtual Console(`/vehicle/catalog`의 쓰기 항목)로 값을 바꿔 콜백이 오는지 봤다. API는 Maven에 함께 올라온 javadoc jar(`Vehicle-2.3.2-javadoc.jar`, `NaviHelper-3.0.5-javadoc.jar`)와 document.pleos.ai 문서로 확인했다.

| 값 | 결과 | Virtual Console로 바꾸기 |
|---|---|---|
| 배터리 잔량(EvBattery SOC) | 읽힘(100%) | `ev-battery?field=soc` → 콜백 40 |
| 주행 전비·순간 전비, 연비·연료량, 드라이브 모드 | 미지원(`ERROR_NOT_SUPPORTED_PROPERTY`, carPropertyConfig에 없음) | - |
| 적산거리(Odometer) | 읽힘(0km) | `odometer?traveled_distance_km` → 콜백 1234. 줄이는 값은 400(`traveled_distance_must_not_decrease`). 주행해도 저절로 늘지 않음 |
| 주행 시간·주행 거리(Trip) | 미지원 | - |
| 브레이크 페달 | 읽힘 | 감속 때 true |
| 경고등(Telltales) | 읽힘. `PRIVATE_CAR_SAFETY`가 에뮬레이터에서는 normal 등급 | `telltales/simulator?type=TPMS|EngineOilPressure|SeatBelt&state=On|Off` → 콜백(TPMS, ENGINE_OIL_PRESSURE, SEAT_BELT) |
| 엔진오일·브레이크액 잔량, 워셔액 경고, 운전석 안전띠 경고 | 미지원 | - |
| 운전석 안전띠 체결(Seat) | 읽힘, 늘 false | 바꿀 항목 없음 → 안전띠는 경고등으로 판정 |
| 전조등·미등·상향등(Light) | 읽힘 | `lights?feature=headlamp_mode&value=LowBeam|Off` → 전조등 ON/OFF |
| 와이퍼(Wiper, 앞유리) | 읽힘 | `body?front_wiper=Low|Off` → ON/OFF |
| 타이어 저압 기준값(Tire) | 읽힘(137kPa). 실제 공기압은 AAOS `TIRE_PRESSURE`인데 `CAR_TIRES`가 signature 등급 | - |
| 바깥 기온(AAOS `ENV_OUTSIDE_TEMPERATURE`) | 읽힘, 25도 고정 | 없음 |
| NaviHelper 연결·현재 위치·경로 상태·목적지 | 읽힘 | - |
| NaviHelper 경로 요청 | `requestRoute` → `onRouteStarted`(판교역 3.1km, 780초), `onNavigationStatus(ROUTE_DRIVE)`, `onDrivingInfo`, `onTBTInfo`(TURN_LEFT 80m 기업지원허브 방면, TURN_RIGHT 380m …) | - |
| 내비게이션 차 위치 | 고정(37.4127, 127.0947). `adb emu geo fix`가 만드는 NMEA 문장의 체크섬이 틀려 내비게이션(`GpsLocationSource`)이 버림. `geo nmea`로 보낸 문장은 앱에 닿지 않음 | 회전 거리 감소·도착 알림은 재현 못 함 |

연동 기능 시연 (디버그 빌드, `tools/vc_drive.py` 시나리오)

| 시나리오 | 결과 |
|---|---|
| 앱 시작 직후 | 경고등 첫 조회값과 구독값이 엇갈려 엔진오일 퀘스트가 열렸다 바로 닫히는 일이 있었다. 1분 미만이면 조용히 닫고(보호권 없음) 열림 안내도 3초 뒤 확인하도록 고쳐, 다시 설치한 뒤에는 "짧게 켜졌다 꺼짐 [OIL]"으로만 남음 |
| `--preset tpms-on` | 3초 뒤 "대장간 퀘스트입니다. 말편자를 갈 때입니다.", 정차 화면에 퀘스트 카드. Driving View에도 타이어 경고등 |
| `eco`(잔량 80→77%, 적산거리 +25km) | 기록 `km_per_kwh=10.77, odo_km=25.000, eco=1, belt=1`, 65XP(50+15). 주차 후 "검약 행차! … 옛길 원정, 판교에 이르렀습니다." 연출 '이번 주행 전비 10.8km/kWh · 곳간에 쌀 2.7섬 · +15XP' |
| `--preset tpms-off`(연 지 4분 뒤) | "정비를 마쳤습니다. 개근 보호권을 드립니다.", 오늘 카드에 '보호권 1' |
| `rainynight` | 출발 때 "주상전하 납시오!" → "야행을 나섭니다. 초롱을 밝힙니다.", 주행 중 밤 장면·초롱 행렬·빗줄기와 '안내 대기'(남아 있던 경로의 다음 회전 80m). 기록 `weather=RAIN, weather_calm=1, night=1`, 65XP. 이때 적산거리가 늘지 않아(`odo_km=0`) 원정 거리가 0으로 쌓이는 것을 보고, 원정·곳간 거리는 적산거리와 차속 거리 중 큰 값을 쓰도록 고침 |
| 지난 기록(울산 2일) 뒤 `unbelted` | 출발 때 "안전띠를 매셔야 개근으로 셉니다." 기록 `belt=0`. 주차 후 "개근 보호권으로 개근 기록을 지켰습니다." 개근 3일 → 2일(오늘은 세지 않음), 보호권 1 → 0 |
| 같은 주행의 계기판 | 1초마다 1분 구간 게이지(18초 → 40초)와 거리(0.1 → 0.5km)가 바뀌고, 60초에 "+10XP · 1분 안전 구간" 알림 띠와 금색 구간 칸 1개. 옛길 막대 '판교 → 용인 역참 1.3km 남음'이 줄어듦 |
| 역참 기준 1분·30초로 `short` | 앞 주행과 21초 간격이라 같은 운전으로 이어져 시작 직후 '쉬어 갈 때'. 회전 앞이라 음성은 30초 미뤄진 뒤 "오래 달리셨습니다. 역마를 갈아탈 때입니다. 쉬어 가세요. 가까운 휴게소를 내비게이션에 띄웠습니다."와 함께 내비게이션에 '휴게소' 검색 결과(4.8km 하이패스센터 부산방면 등)가 열림. 주차 후 휴식 카드가 남은 시간을 셈 |
| 30초 넘게 쉬고 `short` | "주상전하 납시오! 역참에서 잘 쉬셨습니다. 다시 길을 나섭니다." 기록 `rested=1`, 30XP(10+20). 주차 후 "옛길 원정, 용인에 이르렀습니다." |
| `--es route DAEWANGAM` 뒤 `landmark` | 내비게이션 경로 시작. 주행 줄에 '안내 대기'(다음 회전 80m)와 '쉬어 갈 때', 탑승 음성은 약 30초 뒤에 남. 울산 진입 안내. 주차 후 기록 `regions=ULSAN, stamp=DAEWANGAM`, 40XP(20+20), "대왕암공원 도착! 스탬프를 찍었습니다. 단골 등급이 올랐습니다. 동 고래입니다." 도착 판정은 앱 위치와 내비게이션 목적지 좌표(약 56m)로 함 |
| 원정 패널 | 영남대로 지도에 용인까지 금색, '다음 양지(주막)까지 18.7km', 대왕암공원 스탬프, 곳간 쌀 2.7섬, 보호권 0장, 역참 휴식 1회 |
| 눈 오는 밤(`--ef temp -3`, 와이퍼·전조등) | 처음에는 에뮬레이터가 25도를 1초마다 다시 보내 비로 돌아갔다. 디버그 기온을 고정하도록 고친 뒤 눈 장면 확인 |

- 시연 중 남은 일: 축하 연출은 화면 '확인'을 눌러 넘기는데, Pleos 홈의 TaskView 안 화면은 `uiautomator dump`에 나오지 않아 좌표로 눌렀다.
- 회전 앞에서 미룬 '쉬어 갈 때' 안내가 주차한 뒤에 나올 수 있다(최대 30초 미룸). 주차 중 안내는 미루지 않게 바꿨다.

### 5.5 주행 엔진 분리와 통합 테스트 (2026-10-27)

- 이유: 앱의 `DriveController`가 주행 시작·종료, 일지, 퀘스트, 역참, 도착, 계기판, 음성 미루기, 축하 연출을 한 클래스(692줄)에서 맡았고, Android 의존(Handler, SystemClock, SharedPreferences, 파일) 때문에 자동 시험이 없었다. 5.4의 버그 넷(퀘스트 흔들림, 적산거리 0, 제자리 도착, 기온 덮어쓰기)은 모두 에뮬레이터에서만 잡혔다.
- 구조: 판정 로직을 `core-judge`의 `haengcha.drive.DriveEngine`으로 옮기고 바깥 일은 인터페이스로 받는다(`Clock`, `Scheduler`, `RecordStore`, `JournalStore`, `DriveSettings`, 음성·휴게소 검색 함수). 기능은 부품으로 나눴다: `QuestDesk`(대장간 퀘스트), `RestStop`(역참), `ArrivalWatch`(명소 도착), `LiveBoard`(계기판·알림 띠), `SceneSignals`(야행·날씨), `celebrationsFor`(축하 연출). 신호 인터페이스(`VehicleListener`, `NaviListener`, `SignalStatus`)와 화면 상태(`DriveUiState`, `Celebration`, `LiveView`, `Profile`)도 `haengcha.drive`로 옮겨, `vehicle`이 엔진에 바로 신호를 넘긴다. 앱의 `DriveController`는 SystemClock·메인 Handler·`AppPrefs`·`TripStore`·`TripJournalFile`을 엔진에 잇는 93줄 연결 층이 됐다.
- 시험대(`core-judge/src/test/kotlin/haengcha/drive/Rig.kt`): 가짜 시계 겸 예약 실행기, 메모리 기록·일지·설정. 기어와 차속을 10Hz로 넣고 중간에 차량 신호를 끼울 수 있다. 엔진을 같은 일지로 다시 띄워 프로세스 재시작을 흉내 낸다.
- 통합 테스트 16개(`DriveEngineTest`): 차분한 주행 저장·탑승/마감 안내, 퀘스트 흔들림 무시, 퀘스트 완료와 보호권, 주행 중 켜진 경고는 주차 후 안내, 검약 행차와 옛길 지점, 적산거리 0이어도 원정 진행, 제자리 주행은 도착 아님·밖에서 들어오면 스탬프·두 번째는 보너스 없음, 내비게이션 도착 알림, 안전띠와 보호권, 역참 안내·휴게소 검색·휴식 보너스, 경로 위 휴게소, 회전 앞 음성 미루기와 주차 중 즉시 안내, 어린이보호구역 안내는 미루지 않음, 계기판 +10XP 알림 띠, 시연 기온 고정, 같은 부팅 이어 가기·재부팅 뒤 조용한 마감.
- 확인: 처음 실패한 2개는 시험 시나리오 문제였다(하루 5분 미만 주행은 개근에 세지 않음, 휴식 남은 시간은 주차 뒤 1초 갱신에서 셈). 고친 뒤 `core-judge` 95개, 앱 8개 통과. 리팩터링한 출시 빌드를 에뮬레이터에 덮어 설치해 경고등 퀘스트 열림(3초 확인)·`short` 주행 저장·퀘스트 완료와 보호권 연출을 확인했다. 제출용 0.2.0 APK·압축본도 리팩터링한 소스로 다시 만들었다(6장).

### 5.6 세로(한 칸) 화면과 시연 빌드 (2026-10-27, 에뮬레이터)

- 한 칸 화면(840×1212)에서 메뉴 칩이 테마 표시와 겹쳐, 좁은 화면에서는 메뉴를 카드 열 맨 위 줄로 옮겼다. 알림 카드(퀘스트 > 휴식 > 단골)는 탈것을 가리지 않게 한 칸에서 가장 앞선 한 장만 둔다. 명소 이름은 낱말 단위로 줄을 바꾼다.
- 화면 배치: Pleos 홈에서 작업 막대 아이콘을 누르면 그 앱이 가운데 칸으로 오고 앞서 가운데 있던 앱이 오른쪽으로 밀린다. 행차를 누르고 2~3초 뒤 내비게이션을 누르면 [Driving View | 내비게이션 | 행차]가 된다. 길 안내를 운전자 가까이 두는 이 배치를 안내문에 권장으로 적었다.
- 시연 빌드: 디버그 빌드를 출시 키로 서명하고 `versionNameSuffix = "-demo"`를 붙였다. 출시 APK와 같은 키라 기록을 지우지 않고 서로 덮어 설치된다. 직접 설치한 출시 빌드는 주행 중 화면이 시스템 화면으로 가려지므로 주행 계기판 시연은 시연 빌드로 한다.
- 심사용 안내문 `docs/submission/haengcha-demo-guide.md`(PDF는 Chrome 인쇄로 만들고 저장소에는 넣지 않음). `pm clear`는 런타임 권한도 지우므로 다시 허용하라고 적었다.
- 안내문 순서대로 다시 달려 울산 진입·어린이보호구역·아이 지킴이, 암행어사 감찰(시작 20초 뒤 2분, 통과 후 "암행어사 출두요!"와 마패)을 확인했다.

### 5.7 지역 도감·칭호·동반자 확장 (2026-10-27)

- 지역 14곳: 울산·횡성(단골 등급마다 장면)에 서울·부산·대구·인천·광주·대전·경주·전주·강릉·제주·여수·안동을 더했다. 도감 지역은 상징(서울 해치, 부산 바다 등), 지역마다 다른 진입 인사, 공통 단골 등급(나그네 → 명예 주민)을 쓴다. 범위는 시청 중심 원(10~18km)이고 서로 겹치지 않음을 테스트로 확인한다.
- 도감 지역 3D 장면 12곳: `tools/render3d/towns.js`. 기존 무대(단상·조명·카메라)에 지역 상징을 three.js 기본 도형으로 빚어 세웠다(해치 석상, 빨간 등대와 나는 갈매기, 사과나무, 소반 위 짜장면, 주상절리, 한빛탑, 신라 금관, 돌솥비빔밥, 커피, 돌하르방, 떠 있는 거북선, 턱이 움직이는 하회탈). 먼 풍경은 2D(서울타워, 광안대교 불빛, 첨성대, 청사초롱, 해돋이, 줄불놀이 등). 움직임은 모두 11초 루프에 맞췄다. 처음 렌더에서 모형 색이 바래 보여(sRGB 값을 선형으로 바꾸지 않음) 재질 색을 선형으로 바꿔 넣었다.
- 명소 31곳(지역마다 2곳 이상). 열쇠말이 다른 명소와 겹치지 않음을 테스트로 확인한다.
- 칭호 34종, 희귀도(일반·희귀·영웅·전설)와 숨은 칭호 2종. 기록에서 계산하므로 저장하지 않는다. 대표 칭호(가장 어려운 것)를 정차 화면에 단다. 주차 후 새 칭호는 두 개까지 음성으로 알린다.
- 주행 보상 동반자 11종(전설 청룡·봉황·구미호 포함). 합류 조건과 진행을 보여 주고, 합류한 동반자 하나를 골라 정차 화면에 데리고 다닌다(새로 합류하면 바로 따라옴). 운전자마다 고른 동반자를 설정에 둔다.
- 서울 상징은 기획서 4.3을 따라 해치로 했고, 같은 이름이 겹치지 않게 아이 지킴이 동반자는 삽살개로 했다.
- 안내 음성 142문장을 같은 목소리로 더 녹음했다(모두 217문장). 시연 명령 `--ez seedTour true`(지난 전국 유람 기록)를 더했다.
- 에뮬레이터: 유람 기록을 넣으면 도감(칭호 희귀도 테두리, 숨은 칭호, 지역 14곳 격자)과 대표 칭호·동반자(닭)가 보였고, `rainynight` 뒤 "부엉이가 밤길 행차에 합류했습니다", 숨은 칭호 '비 오는 밤의 나그네', '달빛 행차꾼'이 차례로 나왔다.

### 5.8 주행 화면 원근과 화면 배치 (2026-10-27, 에뮬레이터)

- 주행 중 탈것이 정차 화면처럼 옆모습으로 서 있는데 초롱이 옆으로 흘러가 어색했다. 주행 중에는 단상 없는 뒷모습 레이어(`vehicle_<테마>_rear`, `render.mjs`의 rear 작업)를 쓴다. 정차 화면과 같은 카메라로 찍어 배경 지평선과 원근이 맞고, 탈것은 계기판에 가리지 않게 단상보다 앞(z=-5.5, 울산 물가 바로 앞)에 둔다.
- 야행 초롱은 같은 카메라 투영(`SceneCamera`)으로 길 양옆 장대가 정면 소실점에서 다가와 지나가게 그린다. 길·차선은 그리지 않는다. 영상 금지 규제(1초 1프레임)에서는 한 번에 장대 간격의 절반보다 적게 나아가 거꾸로 가 보이지 않게 했다(10초 주기, 한쪽 4개).
- 화면 배치 기본값: 행차가 세 칸 가운데 칸에서 처음 열리면 내비게이션(`ai.umos.maps.android.navigation.app`)을 앞으로 부른다. Pleos 홈이 앞에 온 앱을 가운데에 두고 그전 가운데 앱을 오른쪽으로 미는 규칙을 이용한다. 창이 칸에 앉기 전에는 위치가 (0,0)으로 보여, 포커스를 받은 뒤 0.3~1.8초 사이에 다시 본다. 운전자 메뉴 '화면 배치'에서 끈다. 에뮬레이터에서 앱을 새로 켜면 [Driving View | 내비게이션 | 행차]로 바뀌는 것을 확인했다.

### 5.9 녹음 압축과 바다 장면 초롱, 두 칸 화면 (2026-10-27, 에뮬레이터)

- 안내 녹음 217개를 WAV(23MB, APK에서 압축되지 않음)에서 Ogg Opus 32kbps 모노(2.7MB)로 바꿨다. 출시 APK 60.1MB → 39.4MB, 시연 APK 65.7MB → 44.7MB. (디버그 APK는 증분 패키징이 지운 자리를 남겨 크게 보일 수 있어 APK 파일을 지우고 다시 만들었다.)
- 처음에는 MediaCodec으로 풀어 PCM으로 이었으나 에뮬레이터에서 3초 문장을 푸는 데 1.3초가 걸려(패킷마다 코덱 왕복) 어린이보호구역 안내처럼 급한 문장에 맞지 않았다. 지금은 180ms 묵음 녹음(`_preroll.ogg`)과 문장 녹음들을 MediaPlayer로 준비해 `setNextMediaPlayer`로 끊김 없이 잇는다. 요청부터 재생 시작까지 0.12초, 두 문장 안내(마패·마감)도 끝까지 이어 재생됐다. 끝 알림이 오지 않으면 길이+3초 뒤 안전 타이머가 마감한다. 합성 TTS(WAV)는 전처럼 묵음을 붙인 파일 하나로 재생한다.
- 앞이 바다인 장면(울산 물가 z=-6, 부산·강릉·여수 -9.5)에서는 물 위 초롱을 장대 대신 물에 뜬 유등(받침, 물에 비친 불빛)으로 그리고 물가 2.5m에 걸쳐 장대 초롱으로 바꾼다. 다른 안 두 가지(바다 위 잔교, 물가 앞에서만 초롱)는 길처럼 보이거나 초롱이 탈것 바로 앞에서 갑자기 나타나 고르지 않았다.
- 두 칸(가로, 1682×1210) 주행 화면: 화면 나눔을 합쳐 확인했다. 탈것 뒷모습이 배경의 정면 소실점(가로 0.65) 쪽에 서고 왼쪽 아래 계기판에 가리지 않으며, 유등·장대 초롱이 같은 원근으로 지나간다.

## 6. 검증 명령

판정 엔진·주행 엔진과 앱 단위 테스트 (2026-10-27 결과: 117개 통과. `core-judge` 105(판정 87, 주행 엔진 통합 18), 앱 12(녹음 형식·용량, 장면 파일 확인 포함))

```bash
./gradlew :core-judge:test :app:testDebugUnitTest
```

기록 재생 테스트는 5장의 에뮬레이터 기록(`core-judge/src/test/resources/probe/vc_drive_2026-10-26.csv`)에서 급가속 1건(최대 4.0m/s² 안팎), 급감속 1건(−5.9m/s² 안팎), 거리 0.42~0.50km를 확인한다.

차속 검증 앱. Git Bash에서는 adb 경로 변환을 막으려고 `MSYS_NO_PATHCONV=1`을 붙인다.

```bash
./gradlew :speedprobe:assembleDebug
adb install -r speedprobe/build/outputs/apk/debug/speedprobe-debug.apk
adb shell am start -n haengcha.speedprobe/.ProbeActivity
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py --out probe-logs/drive_sent.csv
adb shell run-as haengcha.speedprobe --user 10 ls -t files
adb exec-out run-as haengcha.speedprobe --user 10 cat files/<파일> > probe-logs/<파일>
python tools/analyze_probe.py probe-logs/<파일>
```

AAOS는 운전자 계정(user 10)의 외부 저장소를 adb로 읽을 수 없어서, 로그는 앱 내부 저장소에 쓰고 run-as로 꺼낸다.

Pleos SDK 값 확인(5.4). 검증 앱을 띄우면 `PleosProbe`가 값을 logcat에 남기고, 4초 뒤 판교역으로 경로를 요청한다. 실행 중 동의 권한(`CAR_ENERGY` 등)은 `pm grant`로 준다.

```bash
adb shell pm grant --user 10 haengcha.speedprobe android.car.permission.CAR_ENERGY
adb shell am start --user 10 -n haengcha.speedprobe/.ProbeActivity
adb logcat -s PleosProbe
python tools/vc_drive.py --preset headlights-on --preset wiper-on --preset tpms-on
curl -s http://127.0.0.1:8088/vehicle/catalog   # 콘솔이 바꿀 수 있는 값 목록
```

연동 기능 시연 순서는 README '시연 순서' 10~15에 있다.

행차 앱 전체 흐름 (처음 실행 때 '위치 정보 액세스' 창에서 허용)

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n haengcha.app/.MainActivity
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py --scenario ulsan --out probe-logs/ulsan_sent.csv
adb exec-out run-as haengcha.app --user 10 cat files/trips.csv
adb logcat -d -s VoiceGuide:* DriveController:*
```

디버그 빌드 시연 도구(브로드캐스트, user 10)

```bash
adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es inspection "20,120,60"
adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --ez charging true
adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es seed ULSAN --ei seedDays 2 --ei seedMonthTrips 7
```

`inspection`은 감찰 시작 초·감찰 초·최소 주행 초, `seed`는 지금 운전자에게 지난 며칠의 주행 기록을 넣는다(경험치는 적게 넣어 다음 주행에서 레벨업·해금이 함께 보이게 한다). 운전자 2·3은 정차 화면 오른쪽 위 운전자 메뉴에서 추가한다.

모델·정차 화면 그림 다시 만들기

```bash
python tools/models/joseon_set.py --out assets3d/models -- ox_cart rickshaw gama kkotgama eoga
python tools/models/region_set.py --out assets3d/models -- whale hanwoo
cd tools/render3d && npm install && cd ../..
node tools/render3d/render.mjs
node tools/render3d/render.mjs --only motion_bg_ulsan vehicle_rickshaw
```

Blender 스크립트는 `bpy`가 설치된 파이썬으로 실행한다. 렌더 전체(탈것 10, 정지 배경 22, 영상 22, 아이콘 6)는 GPU로 약 7분 걸린다.

출시 APK (2026-10-27)

```bash
./gradlew :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

- 서명 키는 저장소 밖 `%USERPROFILE%\.android-keys\haengcha-release.jks`(PKCS12, 별칭 `haengcha`, RSA 4096, 2054-03-14까지)에 있다. 비밀번호는 같은 폴더의 `haengcha-release.properties`에만 있고 저장소·문서에는 넣지 않는다. 다른 위치의 파일을 쓰려면 `-PhaengchaSigning=<파일>`.
- 인증서 SHA-256 `7F:47:56:DA:38:CF:F4:A1:74:41:D9:76:20:56:A2:2D:EE:DB:21:5B:23:A0:6A:E3:5B:8B:D2:07:D1:C4:E4:DD`, 서명 방식 v2.
- 결과(0.1.0): `app-release.apk` 28.4MB, 검증 통과. 디버그 전용 `DebugReceiver`는 들어가지 않는다. 제출용 사본은 `docs/submission/haengcha-0.1.0-release.apk`(SHA-256 `3e14a330…09a1a7`, `.gitignore`의 `*.apk`로 저장소 밖).
- 결과(0.2.0, versionCode 2, 2026-10-27): Pleos SDK 연동 기능과 주행 계기판을 넣은 빌드. `app-release.apk` 36.5MB(SDK 라이브러리와 녹음 75개로 늘어남), 같은 키·v2 서명 검증 통과, `pleos.car.permission.*` 조회 권한 11개, `DebugReceiver` 없음. 제출용 사본은 `docs/submission/haengcha-0.2.0-release.apk`. 도착 판정을 고쳐 같은 0.2.0으로 다시 빌드했고(`b181396c…edc6a`, 처음 만든 것은 `a54ef907…23b1e9`), 주행 엔진 분리(5.5) 뒤 한 번 더 빌드했다(36.5MB, SHA-256 `3956405b…0611c0`, 같은 키·v2 서명 검증 통과). - 0.2.0 출시 빌드 에뮬레이터 확인(2026-10-27): 디버그 앱을 지우고 설치. 첫 실행 권한 창 한 번으로 `CAR_SPEED`·정밀/대략 위치 허용, `pleos.car.permission.*` 11개는 설치 때 허용, 포그라운드 서비스 `isForeground=true types=0x8`. Vehicle SDK는 전비·엔진오일만 미지원으로 남음(디버그와 같음). `eco` 주행 기록 `km_per_kwh=10.77, odo_km=25.000, eco=1, belt=1`, 주차 후 검약 행차 연출과 "검약 행차! … 옛길 원정, 판교에 이르렀습니다." 재생.
- **출시 빌드는 주행 중 앱 영역이 시스템 화면 '주행 중에는 사용할 수 없습니다'로 가려졌다**(D단 30·65·150·215초 캡처 모두). 같은 에뮬레이터에서 디버그 빌드는 주행 계기판이 보였다. 직접 설치한 출시 빌드는 DO를 인정받지 못하기 때문으로 보이며, 0.1.0 출시 확인 때 '배경 멈춤'으로 적은 것도 이 차단 화면이었을 수 있다. 주행 계기판은 App Market에서 DO를 인정받아야 실차에서 보이고, 그 전까지 에뮬레이터 시연은 디버그 빌드로 한다. 기록·판정·음성은 가려져도 그대로 동작했다.
- 같은 주행에 `regions=ULSAN, stamp=DAEWANGAM`이 붙었다. 앞선 디버그 시연에서 에뮬레이터 GPS가 대왕암공원 근처에 남아 있었고 내비게이션 경로도 대왕암공원으로 남아 있어, 제자리에서 출발해 제자리에서 끝난 주행이 '목적지 300m 안에서 끝남'으로 도착 판정됐다. 출발 위치도 반경 밖이었을 때만 도착으로 보도록 고쳤다. 고친 출시 빌드로 같은 상태(GPS 대왕암공원 근처, 내비게이션 목적지 대왕암공원)에서 `short`를 달리면 `stamp`가 비고, `landmark`(약 2.7km 밖에서 출발)는 `stamp=DAEWANGAM`(이미 받은 명소라 보너스 없이 20XP)으로 저장됐다. `vc_drive.py`는 경로가 있는 시나리오에서 첫 GPS를 보낸 뒤 2초 기다렸다가 기어를 넣는다(앱이 출발 위치를 받게).
- 디버그 빌드와 서명이 달라 덮어 설치할 수 없어, 디버그 앱을 지우고(시험 기록 삭제) 설치했다.
- 릴리스 에뮬레이터 확인(2026-10-27): 첫 실행 권한 창 한 번으로 `CAR_SPEED`·정밀/대략 위치가 함께 허용됨. 포그라운드 서비스 `isForeground=true types=0x8`. 정차 화면 움직이는 배경 재생. `ulsan` 주행에서 탑승 → 울산 진입 → 어린이보호구역 안내가 끝까지 재생되고, D단 중 앱 영역 2초 간격 차이 없음(배경 멈춤), 통과 최고 25.0km/h로 아이 지킴이, 기록 1.209km·40XP·`ULSAN,1` 저장, 주차 후 아이 지킴이 → 울산 고래 테마(노말 고래 단골) 연출. 디버그 명령 브로드캐스트는 반응 없음.

안내 녹음 다시 만들기(문장을 바꿨을 때)

```bash
python tools/make_voice_clips.py --model-dir <sherpa-onnx-supertonic-3-tts-int8-2026-05-11 폴더>
```

제출용 소스 압축본 (2026-10-27)

```bash
python - <<'PY'
import os, subprocess, zipfile
files = sorted(f for f in subprocess.run(['git', 'ls-files', '-co', '--exclude-standard'], capture_output=True, text=True, check=True).stdout.split('\n') if f and os.path.isfile(f))
with zipfile.ZipFile('docs/submission/haengcha-0.2.0-source.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for f in files:
        z.write(f, 'haengcha/' + f)
PY
```

- `.gitignore`를 따르는 파일 가운데 지금 있는 것만 담는다(지운 예전 WAV 녹음은 빠진다. 빌드 결과, `local.properties`, `probe-logs/`, `node_modules/`, APK·압축본 제외). 서명 키와 비밀번호 파일은 저장소 밖이라 들어가지 않는다.
- 결과(0.1.0): 200개 파일, 12.0MB. 시험으로 짧은 경로에 풀어 `local.properties` 없이 `ANDROID_HOME`만 주고 `assembleDebug`, `:core-judge:test`, `:app:testDebugUnitTest`를 돌려 통과했다(디버그 APK 2개, 테스트 55개).
- 결과(0.2.0): 249개 파일, 15.1MB. 같은 방법으로 풀어(스크래치 폴더, `local.properties` 없음) 빌드·테스트가 통과했다(디버그 APK 2개, 테스트 87개, 도착 판정을 고친 뒤 다시 확인).
- 결과(0.2.0, 주행 엔진 분리 뒤): 256개 파일. 최종 압축본을 그대로 풀어 빌드·테스트가 통과했다(디버그 APK 2개, 테스트 103개). 시험한 압축본과 최종본은 이 문서·README·`.gitignore` 같은 문서만 다르다.
- 결과(0.2.0, 도감 확장·주행 뒷모습 뒤): 463개 파일, 36.1MB. 압축본을 그대로 풀어 `ANDROID_HOME`만 주고 빌드·테스트가 통과했다(디버그 APK 2개, 테스트 115개). 최종본은 이 결과 줄만 다르다. 안내문 PDF는 `.gitignore`로 뺐다.
- 결과(0.2.0, 녹음 Opus 전환 뒤): 464개 파일, 18.8MB(녹음 WAV를 빼서 36.1MB에서 줄었다). 풀어서 빌드·테스트가 통과했다(디버그 APK 2개, 테스트 117개). 최종본은 이 결과 줄만 다르다.

## 7. 남은 위험

- 주행 중 화면은 DO 인정 여부와 앱 카테고리에 달려 있다(5.1). Mobility Services 심사에서 '차량 관리 서비스'로 인정받지 못할 수 있고, 정식 배포(Pleos App Market) 때 DO가 어떻게 인정되는지는 문서에 없어 partnership@pleos.ai 문의가 필요하다. 어느 경우든 주행 중 화면 없이 동작하므로 기능 범위는 줄지 않는다.
- 안내 녹음은 Supertonic 3(OpenRAIL-M 라이선스)로 만들었다. 상업 배포 전에 라이선스 조건을 확인한다. 문장을 바꾸면 녹음을 다시 만들어야 하고, 녹음 누락은 앱 단위 테스트가 잡는다.
- 음성 용도를 `NOTIFICATION`으로 정했지만 Pleos Interaction Matrix의 실제 값(문서에서 표가 텍스트로 나오지 않음)과 내비게이션 동시 재생은 확인하지 못했다. 행차 앱이 뒤에 있을 때의 재생도 아직 확인하지 않았다.
- 테마 탈것을 왼쪽 Driving View(차량 모델 자리)에 띄우는 것이 원래 구상에 가깝지만, Driving View는 앱이 숨기거나 끌 수 없는 시스템 고정 영역이고 바꾸는 공개 API가 없다([App Structure and Behavior Model](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)). 앱이 할 수 있는 것은 오른쪽 앱 영역(정차 화면)뿐이다.
- 정차 화면은 미리 렌더한 영상·그림이라 탈것은 움직이지 않고 배경만 움직인다. 에뮬레이터 WebView 문제(5.3)가 실차에도 있는지는 모른다. 실차에서 WebGL이 되면 실시간 장면을 다시 검토할 수 있다.
- 앱 크기: 출시 APK 39.4MB, 시연 APK 44.7MB(5.9). 남은 큰 몫은 코드 축소 없는 dex(22.4MB)와 움직이는 배경 영상(11.8MB)이다. R8을 켜면 dex를 크게 줄일 수 있지만 Pleos SDK의 리플렉션·AIDL 규칙을 확인해야 해 아직 끄고 둔다.
- 포그라운드 서비스는 화면이 보일 때 시작한다(위치 유형은 백그라운드에서 시작할 수 없음). 앱을 한 번도 열지 않고 부팅한 뒤에는 프로세스가 살아 있는 동안만 수집한다. 그래도 끊기면 주행 일지로 이어 가거나 마감한다.
- 지역 범위는 시청·군청 중심의 원이고 어린이보호구역은 시연용 지정 좌표 1곳이다. 행정 경계·공공데이터 연동은 사업화 단계다.
- 출시 서명 키는 이 PC에만 있다. 키를 잃으면 같은 패키지로 업데이트를 낼 수 없으니 회사 보관소 등에 따로 백업해야 한다. 릴리스는 코드 축소(R8) 없이 빌드한다.
- 급가속·급감속 임계값과 경험치·레벨·개근 규칙은 임시값이다. 공단 공식 수치를 확인하고, 실차 기록이 생기면 다시 맞춘다.
- 판정 엔진은 에뮬레이터 기록 하나와 합성 프로필로만 검증했다. 실차 기록의 잡음·공백에서는 아직 확인하지 않았다.
- 실차 차속 수신 주기를 아직 모른다. 데브박스나 실차를 확보하면 같은 프로브로 다시 잰다.
- `distractionOptimized` 선언이 없는 화면이 주행 중 실제로 막히는지 확인하지 않았다.
- FusedLocation, TTS, STT, Vehicle Control이 제출 전에 열리지 않으면 대체 구현으로 간다.
- 빌드 경고 3건은 API 35에서 새 메서드로 바뀐 `registerCallback`, `status`다. 에뮬레이터가 API 34라 지금 코드를 유지한다.
- 경고등 조회 권한 `PRIVATE_CAR_SAFETY`는 문서상 signature|privileged다. 에뮬레이터에서는 일반 권한이라 읽혔지만 출시 때 App Market 협의가 필요하다. 못 받으면 대장간 퀘스트는 엔진오일 잔량 신호만, 안전띠는 체결 신호만 쓴다.
- 에뮬레이터는 주행 전비·엔진오일 잔량을 지원하지 않고, 바깥 기온은 25도 고정, 안전띠 체결 신호는 늘 '안 맴', 내비게이션 차 위치는 고정이다. 전비 SDK 경로, 오일 잔량 퀘스트, 체결 신호 판정, 내비게이션 도착 알림, 회전 거리 감소는 실차·데브박스에서 확인해야 한다.
- 배터리 용량을 차량에서 읽지 않고 77.4kWh로 둔다. 차종마다 다르므로 실차에서는 용량 프로퍼티(`EV_CURRENT_BATTERY_CAPACITY`, 권한 필요)나 SDK 전비를 써야 한다.
- 도착 판정은 출발 위치를 모르면(출발 때 위치를 아직 못 받았거나 일지에서 이어 간 주행) 끝낸 곳만 본다. 좌표를 일지에 남기지 않기 때문이다.
- 옛길 지점 거리와 명소 이름 목록은 대략값·시연용이다. 명소는 내비게이션 목적지 이름으로 알아보므로 이름이 다르게 나오면 놓친다.
