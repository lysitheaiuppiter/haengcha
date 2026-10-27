# 행차

안전·연비 운전을 '볼거리가 열리는 놀이'로 바꾸는 Pleos Connect 차량용 앱입니다. 부드럽게 달리면 경험치가 쌓여 조선 테마 탈것(소달구지 → 인력거 → 가마 → 꽃가마 → 어가 행렬)이 열리고, 울산·횡성에 들어서면 지역 테마가 켜지고, 전국 14개 지역 도감(지역마다 3D 장면)·명소 31곳 스탬프·칭호 34종·주행으로만 합류하는 동반자 11종을 모읍니다. Pleos Vehicle·NaviHelper SDK로 전비(검약 행차), 경고등(대장간 퀘스트), 적산거리(옛길 원정), 전조등(야행), 와이퍼(비·눈 장면), 안전띠, 내비게이션 목적지·회전 안내를 읽어 판정과 연출에 씁니다. 주행 중에는 조작이 없고, 결과는 차를 세운 뒤 보여 줍니다.

2026 Pleos Connect 앱 공모전 출품작(팀 COC). 기능·설계 근거는 기획서(`행차_기획서_6차.md`), 개발 기록과 검증 결과는 [docs/plans/2026-10-26-haengcha-app-plan.md](docs/plans/2026-10-26-haengcha-app-plan.md)에 있습니다.

## 구성

| 경로 | 내용 |
|---|---|
| `app/` | 행차 앱. 주행 엔진을 Android에 잇는 연결 층(`DriveController`: 시계, Handler, 설정·기록·일지, 차량 신호·내비게이션), 위치(`LocationSource`), 기록(`TripStore`), 진행 중 주행 일지(`TripJournalFile`), 포그라운드 서비스(`CollectService`), 녹음 음성 안내(`VoiceGuide`), Compose 화면 |
| `core-judge/` | 순수 Kotlin. 판정 규칙(`haengcha.judge`: 급가속·급감속, 경험치·레벨, 개근과 보호권, 암행어사 감찰, 지역·단골 등급, 어린이보호구역, 칭호·동반자, 검약 행차, 빗길·눈길, 대장간 퀘스트, 옛길 원정, 명소 도착, 역참, 안내 타이밍, 주행 일지)과 주행 엔진(`haengcha.drive`: 신호 → 주행 판단 → 저장 → 안내·축하 연출, 기능별 부품). 가짜 시계와 차량 신호로 주행을 재생하는 통합 테스트가 있다 |
| `vehicle/` | 수집 계층. AAOS Car API(차속·기어·충전 포트·주행 규제·바깥 기온), Pleos Vehicle SDK(`PleosVehicleSignals`), NaviHelper SDK(`PleosNavi`) |
| `speedprobe/` | 차속 수신을 재고(`SpeedProbe`), Pleos SDK 값이 나오는지 확인하는(`PleosProbe`) 검증 앱 |
| `assets3d/models/` | Blender로 만든 3D 모델(glTF) 7종 |
| `tools/` | 주행 시나리오 재생(`vc_drive.py`), 프로브 분석, 음성 녹음 생성, Blender 모델 스크립트(`models/`), 정차 화면 렌더(`render3d/`, 도감 지역 장면은 `towns.js`) |
| `docs/` | 개발 계획·검증 기록, 제출용 앱 아이콘 |

앱에 들어가는 그림·영상·음성(`app/src/main/assets`)은 모두 저장소에 있어서, 빌드만 하면 됩니다. 다시 만드는 방법은 아래 '그림·음성 다시 만들기'에 있습니다.

## Pleos SDK 연동 기능

`ai.pleos.playground:Vehicle:2.3.2`와 `ai.pleos.playground:NaviHelper:3.0.5`를 씁니다(공개 Maven 저장소, 인증 없음). 항목마다 처음 읽기를 해 보고, 차량이 지원하지 않으면 그 기능만 빠집니다.

| 기능 | 읽는 값 | 판정(임시값) | 에뮬레이터 |
|---|---|---|---|
| 검약 행차 | 이번 주행 전비, 없으면 배터리 잔량(%)과 적산거리로 계산 | 3km 넘게 달리고 전비 6km/kWh 이상이면 +15XP. 5km/kWh 기준보다 아낀 1kWh를 곳간 쌀 한 섬으로 쌓음 | 전비는 미지원이라 잔량·적산거리로 계산. 둘 다 Virtual Console로 바꿈 |
| 대장간 퀘스트 | 경고등(TPMS, 엔진오일 압력), 엔진오일 잔량 | 경고등이 켜지면 퀘스트가 열리고, 1분 넘게 켜져 있다가 꺼지면 개근 보호권 1장 | 경고등은 Virtual Console로 켜고 끔. 오일 잔량은 미지원 |
| 개근 보호권 | 위 퀘스트 보상 | 개근이 끊길 날을 한 장 써서 이어 감 | 동작 |
| 안전띠 개근 | 운전석 안전띠 경고등, 없으면 체결 신호 | 안전띠를 매지 않고 출발한 날은 개근으로 세지 않음. 출발 때 음성 안내 | 체결 신호는 늘 '안 맴'이라 경고등으로 판정 |
| 옛길 원정 | 적산거리(없으면 차속으로 잰 거리) | 영남대로 380km를 따라 역참·주막·관문 14곳 스탬프. 동래에 닿으면 다음 바퀴 | 적산거리를 Virtual Console로 바꿈 |
| 야행 | 전조등(Low beam) | 전조등을 켜고 달리면 야행 기록, 주행 중 길 양옆을 지나가는 초롱 장대, 정차 화면 밤 장면(저녁 7시~아침 6시 포함) | 동작 |
| 날씨 장면 | 와이퍼, 바깥 기온(AAOS) | 와이퍼가 돌면 비, 1도 이하면 눈. 2분 넘게 젖은 길을 급조작 없이 달리면 +15XP | 기온은 25도 고정이라 눈은 디버그로 기온을 바꿔 시연 |
| 역참에서 쉬어가기 | NaviHelper 회전 안내(휴게소), 휴게소 검색 화면 | 2시간 쉬지 않고 달리면 안내하고, 경로 위 휴게소가 없으면 내비게이션에 휴게소 검색을 띄움. 15분 넘게 쉬고 떠나면 +20XP | 동작(디버그로 시간을 줄여 시연) |
| 도착 스탬프 | NaviHelper 목적지·도착 | 내비게이션이 도착을 알리거나, 목적지 300m 밖에서 출발해 300m 안에서 주행을 끝내면 도착. 목적지 이름이 명소(14개 지역 31곳)면 스탬프, 첫 도착 +20XP | 내비게이션 위치가 움직이지 않아 앱 위치와 목적지 좌표로 판정 |
| 안내 타이밍 | NaviHelper 다음 회전 거리 | 다음 회전이 300m 안이면 행차 음성을 미루고, 지나면 냄(최대 30초, 어린이보호구역 안내는 미루지 않음) | 동작 |

## 준비물

- JDK 17 (Android Studio에 들어 있는 JBR을 써도 됩니다)
- Android SDK: platform 35, build-tools 35.0.0, platform-tools, emulator. 위치는 `local.properties`의 `sdk.dir`이나 `ANDROID_HOME`으로 알려 줍니다.
- Pleos Connect 에뮬레이터
  1. SDK Manager에 사용자 저장소를 추가합니다: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
  2. 시스템 이미지 `system-images;android-34;pleos_car_emulator;x86_64`(Pleos Connect 2.1.0)를 설치합니다.
  3. Pleos Connect 기기 정의로 AVD를 만듭니다. 메모리 4GB, 하드웨어 그래픽을 권합니다. 이 문서는 AVD 이름을 `Pleos_Connect_API34`로 씁니다.
- Python 3 (주행 시나리오 재생, 표준 라이브러리만 씀)

빌드 기준은 AGP 8.8.2, Kotlin 2.0.0, Gradle 8.10.2(래퍼 포함), compileSdk 35, minSdk 28입니다.

## 빌드

```bash
./gradlew :app:assembleDebug
./gradlew :core-judge:test :app:testDebugUnitTest
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 나옵니다. 아래 시연 순서의 일부(감찰 시간 줄이기, 충전 신호, 지난 기록 넣기)는 디버그 빌드에서만 됩니다.

출시 APK는 서명 정보를 저장소 밖 파일에서 읽습니다. 기본 위치는 `~/.android-keys/haengcha-release.properties`이고 `-PhaengchaSigning=<파일>`로 바꿀 수 있습니다. 파일이 없으면 서명하지 않은 APK가 나옵니다.

```properties
storeFile=<키스토어 경로>
storePassword=<비밀번호>
keyAlias=<별칭>
keyPassword=<비밀번호>
```

```bash
./gradlew :app:assembleRelease
```

## 에뮬레이터에서 실행

```bash
emulator -avd Pleos_Connect_API34
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n haengcha.app/.MainActivity
```

- Pleos 에뮬레이터의 운전자 계정은 user 10입니다. `am start`, `am broadcast`, `run-as`에 `--user 10`을 붙입니다.
- 처음 실행하면 위치 권한 창이 뜹니다. '앱 사용 중에만 허용'을 누르면 차속(`CAR_SPEED`, 위치 권한 그룹)과 위치 권한이 함께 허용됩니다.
- 앱은 홈 화면 오른쪽 앱 영역에 뜹니다. 왼쪽 Driving View는 시스템 영역이라 앱이 바꿀 수 없습니다.

주행은 에뮬레이터 내장 Virtual Console을 스크립트로 움직여 만듭니다. 브라우저로 Virtual Console을 열어 두면 값이 덮어써지니 닫고 실행합니다.

```bash
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py --scenario calm --out probe-logs/calm.csv
```

| 시나리오 | 길이 | 내용 |
|---|---|---|
| `calm` | 230초 | 정체 구간이 있는 출퇴근. 급가속·급감속 없음. 50XP(안전 4구간, 평정심 2구간) |
| `harsh` | 43초 | 급가속·급감속이 한 번씩. 경험치 없음 |
| `ulsan` | 155초 | 울산 밖에서 출발해 25초에 울산 진입, 62~96초에 시연 어린이보호구역을 25km/h로 통과. GPS도 `adb emu geo fix`로 함께 옮김 |
| `eco` | 230초 | `calm`과 같은 차속. 출발 전 잔량 80%, 200초에 적산거리 +25km·잔량 77%로 바꿈(전비 10.8km/kWh, 옛길 판교 도착) |
| `rainynight` | 230초 | `calm`과 같은 차속. 전조등·와이퍼를 켜고 달리고 주차 후 끔(야행, 빗길 차분 운전) |
| `unbelted` | 80초 | 안전띠 경고등을 켠 채 출발하고 주차 후 끔 |
| `short` | 80초 | 급조작 없는 짧은 주행(역참 시연) |
| `landmark` | 100초 | GPS를 시연용 대왕암공원 좌표 쪽으로 옮김(명소 도착 스탬프) |

주행 없이 콘솔 값만 바꿀 때는 `--preset`을 씁니다: `tpms-on/off`, `oil-on/off`, `unbelted`/`belted`, `headlights-on/off`, `wiper-on/off`.

```bash
python tools/vc_drive.py --preset tpms-on
```

로그와 기록은 이렇게 봅니다(기록 파일은 디버그 빌드에서만 꺼낼 수 있습니다).

```bash
adb logcat -s DriveController VoiceGuide
adb exec-out run-as haengcha.app --user 10 cat files/trips.csv
```

## 모으는 재미

모두 주행 기록에서 계산하므로 따로 저장하지 않습니다(조건은 임시값).

| 모음 | 내용 |
|---|---|
| 지역 도감 | 지역에 들어서면 정차 화면이 그 지역 장면으로 바뀐다. 울산(고래)·횡성(한우)은 단골 등급마다 장면이 달라지는 지역 테마이고, 나머지 12곳은 지역 상징 모형이 선 장면 하나와 공통 단골 등급(나그네 → 명예 주민): 서울 해치 석상과 서울타워, 부산 빨간 등대·갈매기와 광안대교, 대구 사과나무, 인천 짜장면과 차이나타운 패루, 광주 서석대 주상절리와 억새, 대전 한빛탑과 탐조등, 경주 금관과 첨성대, 전주 돌솥비빔밥과 청사초롱, 강릉 커피와 해돋이, 제주 돌하르방과 유채꽃, 여수 거북선과 돌산대교, 안동 하회탈과 줄불놀이. 지역마다 다른 진입 인사 |
| 명소 스탬프 | 지역마다 2~4곳, 모두 31곳. 한 지역을 다 돌면 '명소 완주' 칭호 |
| 칭호 | 34종. 희귀도는 일반·희귀·영웅·전설(첫 행차, 달빛 행차꾼, 팔도 유람객부터 백일 개근 영의정·만 리 행차꾼·방방곡곡 대장정·명소 도감 완성·어사또 같은 전설 7종까지). 얻기 전까지 이름과 조건을 감춘 숨은 칭호 2종. 도감에서 얻은 칭호를 누르면 정차 화면에 대표 칭호로 단다(고르기 전에는 가장 어려운 칭호) |
| 동반자 | 거북이(이달 주행 10번)·닭(개근 7일)·부엉이(야행 5번)·다람쥐(곳간 쌀 5섬)·까치(명소 3곳), 희귀 삽살개(아이 지킴이 5번)·호랑이(마패 3개)·도깨비(문경새재 넘기), 전설 청룡(개근 100일)·봉황(지역 도감 14곳)·구미호(누적 4,000km). 판매하지 않고, 합류한 동반자 하나를 골라 정차 화면에 데리고 다닌다 |

## 시연 순서

앱 영역을 두 칸으로 나눠 둔 상태에서 행차가 가운데 칸으로 열리면, 행차가 내비게이션을 앞으로 불러 **[Driving View | 내비게이션 | 행차]** 로 자리 잡습니다(길 안내를 운전자 가까이에 둠). 운전자 메뉴의 '화면 배치'에서 끌 수 있습니다.

처음 설치한 상태에서 1~9가 약 15분, 10~16이 약 20분입니다. 디버그 빌드를 씁니다. 같은 흐름을 에뮬레이터에서 녹화한 시연 영상(`haengcha_demo.mp4`, 3분 4초, 앱 안내 음성 포함)을 제출 자료와 함께 냅니다.

1. **정차 화면.** 궁궐 마당 단상 위에 소달구지, Lv.0. 배경에서 초롱 불빛이 일렁입니다. 오른쪽 위 메뉴는 도감·동반자·상점·운전자입니다.
2. **차분한 출퇴근.** `calm`을 재생합니다. D단에 들어가면 "주상전하 납시오!"와 함께 화면이 계기판으로 바뀌고 탈것은 단상에서 내려와 화면 안쪽(배경 지평선)으로 달려가는 뒷모습이 됩니다. 이번 주행 거리와 경험치, 1초마다 차오르는 1분 안전 구간 게이지, 지난 구간 칸, 옛길 원정의 다음 역참까지 남은 거리가 보이고, 구간을 무사히 마치면 "+10XP" 알림 띠가 뜹니다. 배경 영상은 멈추고 값과 연출은 1초에 한 번만 바뀝니다(주행 규제의 영상 금지를 따름). P단으로 돌아오면 +50XP와 "오늘도 안전하게, 행차하셨습니다."가 나옵니다.
3. **급가속·급감속.** `harsh`를 재생합니다. 경험치를 주지 않을 뿐 깎지 않습니다.
4. **울산 진입과 어린이보호구역.** `ulsan`을 재생합니다. 25초쯤 "울산에 오셨습니다. 고래 테마가 켜집니다.", 62초쯤 "어린이보호구역입니다. 천천히!"가 나오고, 주차 후 아이 지킴이(+20XP)와 울산 고래 테마 연출이 차례로 뜹니다. 정차 화면 배경은 고래가 뛰어오르는 밤바다로 바뀝니다.
5. **레벨업·해금·단골 등급·동반자.** 운전자 메뉴에서 '운전자 추가'로 새 운전자를 만들어 바꾼 뒤, 지난 기록을 넣고 `ulsan`을 재생합니다. 지난 기록은 울산을 다녀간 날 2일과 이달 주행 7회이고 경험치는 적게 들어갑니다. 주차 후 아이 지킴이 → 인력거 테마 열림(Lv.1) → 단골 등급 상승(동 고래) → 동반자 거북이 합류가 차례로 뜹니다.
   ```bash
   adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es seed ULSAN --ei seedDays 2 --ei seedMonthTrips 7
   ```
6. **암행어사.** 감찰을 주행 시작 20초 뒤부터 2분으로 줄이고 `calm`을 재생합니다. 주행 중에는 아무 표시가 없고, 주차 후 "암행어사 출두요!"와 마패(+30XP)가 나옵니다. 바꾼 감찰 규칙은 앱을 다시 시작할 때까지 남아, 그동안의 주행은 모두 감찰합니다.
   ```bash
   adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es inspection "20,120,60"
   ```
7. **쉬어가는 행차.** 충전 포트 연결을 흉내 내면 안내 음성과 함께 도감이 '쉬어가는 행차'로 열립니다.
   ```bash
   adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --ez charging true
   ```
8. **정차 중 패널.** 동반자(8종의 합류 조건과 진행, 합류한 동반자 고르기), 콜라보 상점(가상 캐릭터, 확정 구매 흐름만 있고 결제 없음), 운전자 전환을 차례로 엽니다.
9. **주행 이어 가기.** `calm` 재생 중에 앱을 강제 종료하고 다시 띄웁니다. 로그에 "일지의 주행 이어 감"이 남고, 주차하면 경험치·구간 판정이 끊기지 않은 주행과 같게 저장됩니다(끊긴 몇 초만큼 거리만 줄어듭니다).
   ```bash
   adb shell am force-stop haengcha.app
   adb shell am start --user 10 -n haengcha.app/.MainActivity
   ```

10. **대장간 퀘스트.** `--preset tpms-on`으로 타이어 경고등을 켜면 "대장간 퀘스트입니다. 말편자를 갈 때입니다."와 함께 정차 화면에 퀘스트 카드가 뜹니다. 1분 넘게 지난 뒤 `--preset tpms-off`로 끄면 "정비를 마쳤습니다. 개근 보호권을 드립니다."와 완료 연출이 나오고, 오늘 카드에 보호권이 붙습니다.
11. **검약 행차와 옛길 원정.** `eco`를 재생합니다. 주차 후 검약 행차(전비, 곳간 쌀)와 옛길 원정 판교 스탬프 연출이 나옵니다. 메뉴의 '원정'에서 영남대로 지도, 명소 스탬프, 곳간을 봅니다.
12. **비 오는 밤.** `rainynight`를 재생합니다. 출발하면 "야행을 나섭니다. 초롱을 밝힙니다."가 나오고, 장면이 밤으로 바뀌고, 길 양옆에 선 초롱 장대가 지평선(소실점)에서 다가와 탈것 옆을 지나가며 빗줄기가 내립니다. 울산·부산·강릉·여수처럼 앞이 바다인 장면에서는 물 위 초롱이 물에 뜬 유등이 되고 물가를 지나면 장대 초롱이 됩니다(주행 규제의 영상 금지 중에는 1초에 한 번만 나아감). 주차 후 빗길 차분 운전(+15XP)이 나옵니다. 눈은 기온을 낮춰 봅니다(고정한 기온은 `--ez tempReset true`나 앱을 다시 시작하면 풀립니다).
    ```bash
    adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --ef temp -3
    ```
13. **안전띠와 보호권.** 지난 기록(`--es seed ULSAN --ei seedDays 2`)을 넣어 개근을 이어 둔 뒤 `unbelted`를 재생합니다. 출발하면 "안전띠를 매셔야 개근으로 셉니다."가 나오고, 주차 후 보호권으로 개근이 이어집니다(보호권이 없으면 끊깁니다).
14. **역참에서 쉬어가기.** 기준을 1분 운전·30초 휴식으로 줄이고 `short`를 재생합니다. 1분이 지나면 "오래 달리셨습니다. 역마를 갈아탈 때입니다."와 함께 내비게이션에 휴게소 검색이 뜹니다. 주차하면 휴식 카드가 남은 시간을 세고, 30초 뒤 다시 `short`를 재생하면 "역참에서 잘 쉬셨습니다."와 휴식 보너스가 나옵니다.
    ```bash
    adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es rest "1,0.5"
    ```
15. **명소 도착과 안내 타이밍.** 내비게이션에 대왕암공원 경로를 잡고 `landmark`를 재생합니다. 다음 회전이 가까운 동안 주행 줄에 '안내 대기'가 붙고 행차 음성이 미뤄집니다. 주차하면 대왕암공원 도착 스탬프(+20XP)가 나옵니다.
    ```bash
    adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --es route DAEWANGAM
    ```
16. **도감·칭호·동반자.** 새 운전자로 바꾼 뒤 전국 유람 기록(서울·부산·경주·전주·강릉·제주 6곳, 명소 2곳, 야행 4번)을 넣습니다. 도감에 지역 6곳과 칭호가 차고, 정차 화면에 대표 칭호가 붙습니다. 이어서 `rainynight`를 재생하면 다섯 번째 야행으로 "부엉이가 밤길 행차에 합류했습니다."와 '달빛 행차꾼' 칭호가 나오고, 부엉이가 정차 화면 오른쪽 아래에 따라다닙니다. `ulsan`을 재생하면 일곱 번째 지역으로 '팔도 유람객'이 붙습니다. 비 오는 밤길을 차분히 달리면 숨은 칭호 '비 오는 밤의 나그네'도 열립니다. 위치를 부산으로 옮기면(`adb emu geo fix 129.0750 35.1798`) 정차 화면이 빨간 등대와 광안대교 장면으로 바뀝니다.
    ```bash
    adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app --ez seedTour true
    ```

위치를 직접 옮길 때는 `adb emu geo fix <경도> <위도>`를 씁니다. 울산은 `129.3114 35.5384`, 횡성은 `127.9850 37.4918`, 부산은 `129.0750 35.1798`입니다.

## 그림·음성 다시 만들기

모두 저장소에 결과물이 있으므로 고칠 때만 필요합니다.

```bash
# 안내 음성 녹음(문장을 바꿨을 때). sherpa-onnx, soundfile, numpy, ffmpeg와 Supertonic 3 모델이 필요
python tools/make_voice_clips.py --model-dir <sherpa-onnx-supertonic-3-tts-int8-2026-05-11 폴더>
# 새 문장만 녹음하고 있던 녹음은 그대로 둘 때
python tools/make_voice_clips.py --model-dir <모델 폴더> --only-missing

# 3D 모델(Blender 5.2 파이썬 모듈 bpy가 설치된 파이썬으로)
python tools/models/joseon_set.py --out assets3d/models -- ox_cart rickshaw gama kkotgama eoga
python tools/models/region_set.py --out assets3d/models -- whale hanwoo

# 정차 화면 그림·배경 영상·아이콘(Node 22, Chrome, ffmpeg)
cd tools/render3d && npm install && cd ../..
node tools/render3d/render.mjs
```

`render.mjs`는 헤드리스 Chrome을 호스트 GPU로 띄워 three.js 장면을 찍습니다. 전체(탈것 레이어 20(정차 단상·주행 뒷모습), 정지 배경 46, 11초 반복 배경 영상 46, 아이콘 6)는 GPU에서 15분쯤 걸립니다. `--only motion_bg_ulsan bg_busan vehicle_rickshaw`처럼 일부만 다시 만들 수 있습니다. 도감 지역 장면은 `tools/render3d/towns.js`에 있습니다.

## 알아 둘 점

- 정차 화면의 움직이는 배경은 미리 렌더한 영상입니다. Pleos 에뮬레이터의 WebView가 캔버스·WebGL을 화면에 합성하지 못해 앱 안에서 three.js를 실시간으로 돌리지 않습니다.
- 직접 설치한 출시 빌드는 에뮬레이터에서 주행 중 사용(DO)을 인정받지 못해, D단에서는 시스템이 행차 화면을 '주행 중에는 사용할 수 없습니다'로 가립니다. 디버그 빌드는 주행 계기판이 보이므로 주행 화면 시연은 디버그 빌드로 합니다. 기록·판정·음성 안내는 화면과 상관없이 동작합니다.
- 안내 녹음은 Ogg Opus(32kbps, 217문장 2.7MB)입니다. WAV는 APK 안에서 압축되지 않아 23MB였습니다. 앱은 녹음을 풀어 잇지 않고 180ms 묵음 녹음 뒤에 문장 녹음들을 끊김 없이 이어 재생합니다(에뮬레이터 코덱으로 미리 풀면 3초 문장에 1초 넘게 걸림). 요청부터 재생까지 0.1초 남짓입니다.
- 위치는 Android 위치 API, 음성은 미리 녹음한 문장입니다. Pleos FusedLocation·TTS SDK가 준비 중이기 때문입니다. 차속·기어·주행 규제는 Pleos SDK에 없어 AAOS Car API로 읽습니다.
- 경고등(Telltale) 조회 권한 `PRIVATE_CAR_SAFETY`는 문서상 signature|privileged입니다. 에뮬레이터에서는 일반 권한이라 읽히지만, 출시 때 App Market과 협의해야 합니다. 못 받으면 대장간 퀘스트는 엔진오일 잔량 신호만, 안전띠는 체결 신호만 씁니다.
- 에뮬레이터 내비게이션은 차 위치가 움직이지 않습니다(에뮬레이터 GPS의 NMEA 문장 체크섬이 맞지 않아 버림). 그래서 회전 거리가 줄지 않고 도착 알림도 오지 않아, 도착은 앱 위치와 목적지 좌표로 판정합니다. 실차에서는 두 방식이 함께 동작합니다.
- 배터리 용량은 차량에서 읽지 못해 에뮬레이터 차량 값(77.4kWh)으로 전비를 계산합니다.
- 도감 지역 12곳의 상징 모형은 Blender 모델 대신 three.js 기본 도형으로 빚었고(`tools/render3d/towns.js`), 장면은 지역마다 하나입니다(단골 등급마다 바뀌는 장면은 울산·횡성만). 도감·축하 연출의 지역 상징은 시스템 이모지 글꼴로 그립니다.
- 급가속·급감속 기준, 경험치·등급·칭호·동반자 규칙, 검약·빗길·역참·퀘스트·명소 규칙, 옛길 지점 거리, 지역 범위(시청·군청 중심의 원), 어린이보호구역(시연용 지정 좌표 1곳)은 임시값입니다.
- 패키지명 `haengcha.app`은 임시 이름입니다.

## 출처

- three.js, GLTFLoader, RoomEnvironment (`tools/render3d/vendor`): MIT
- Microsoft Fluent Emoji 3D 아이콘(지역·동반자·마패·아이 지킴이·콜라보 캐릭터): MIT, [라이선스](app/src/main/assets/licenses/FluentEmoji-MIT.txt)
- 원정·퀘스트·도감·칭호·동반자 패널과 축하 연출의 그림 글자는 기기의 시스템 이모지 글꼴로 그립니다(앱에 넣지 않음).
- Pleos Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5: Pleos Playground 공개 Maven 저장소
- 안내 음성: Supertonic 3(sherpa-onnx int8, OpenRAIL-M)로 합성
- 급가속·급감속 판정 방식: [운전 습관 개선을 위한 위험 운전 분석 어플리케이션의 설계 및 구현 (KoreaScience)](https://www.koreascience.or.kr/article/CFKO201531751944204.pdf)
- 3D 모델, 정차 화면 그림·영상, 앱 아이콘은 팀에서 만들었습니다.
