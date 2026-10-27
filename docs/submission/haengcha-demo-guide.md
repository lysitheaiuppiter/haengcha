# 행차 시연 안내 (심사용)

Pleos Connect 에뮬레이터에서 행차의 주요 기능을 20분 안팎에 직접 확인하는 순서입니다. 팀 COC · 행차 0.2.0

## 1. 어떤 파일을 쓰나요

| 파일 | 용도 |
|---|---|
| `haengcha-0.2.0-demo.apk` | **시연용.** 시연 명령(기간 줄이기, 명소 경로, 기온 바꾸기 등)이 들어 있고, 에뮬레이터에서 주행 중 화면(계기판)이 보입니다 |
| `haengcha-0.2.0-release.apk` | 출시용. 시연 명령이 없고, 직접 설치하면 주행 중 화면이 시스템 화면 '주행 중에는 사용할 수 없습니다'로 가려집니다(주행 중 사용 인정 전) |
| `haengcha-0.2.0-source.zip` | 소스. 주행 시나리오를 재생하는 `tools/vc_drive.py`가 들어 있습니다 |

두 APK는 같은 키로 서명해 기록을 지우지 않고 서로 덮어 설치할 수 있습니다.

## 2. 준비 (5분)

필요한 것: Pleos Connect 에뮬레이터(시스템 이미지 `pleos_car_emulator`, Pleos Connect 2.1.0), `adb`, Python 3(표준 라이브러리만 씀). 아래 명령은 소스 압축본을 푼 `haengcha` 폴더에서 실행합니다.

```bash
adb install -r --user 10 haengcha-0.2.0-demo.apk
adb shell am start --user 10 -n haengcha.app/.MainActivity
adb forward tcp:8088 tcp:8088
```

- 에뮬레이터의 운전자 계정은 user 10입니다. 처음 실행하면 위치 권한 창이 뜹니다. **'앱 사용 중에만 허용'** 을 누르면 차속과 위치 권한이 함께 허용됩니다.
- **화면 배치:** 앱 영역 오른쪽 끝의 `⋮` 손잡이를 왼쪽으로 끌어 두 칸으로 나눠 둡니다. 행차가 가운데 칸으로 열리면 스스로 내비게이션을 가운데로 불러 **[Driving View | 내비게이션 | 행차]** 로 자리 잡습니다(길 안내를 운전자 가까이에 둠, 운전자 메뉴 '화면 배치'에서 끔). 한 칸 화면에서도 모든 기능이 보이도록 만들었습니다.
- 주행은 에뮬레이터의 Virtual Console을 `tools/vc_drive.py`로 움직여 만듭니다. 브라우저로 Virtual Console(`http://127.0.0.1:8088`)을 열어 두면 값이 덮어써지니 닫아 둡니다.
- 시연 명령은 모두 같은 꼴입니다. 아래 표에서는 `시연 명령 …` 으로 줄여 씁니다.

```bash
adb shell am broadcast --user 10 -a haengcha.app.DEBUG -p haengcha.app <옵션>
```

## 3. 시연 순서

| 순서 | 할 일 | 보이는 것 |
|---|---|---|
| 1. 정차 화면 | 그대로 둡니다. 위쪽 메뉴의 도감·원정·동반자·상점·운전자를 차례로 엽니다 | 3D 단상의 탈것과 움직이는 배경, 레벨·오늘 경험치·개근·마패 카드. 도감은 탈것·칭호 34종(희귀도별 테두리, 숨은 칭호)·지역 도감 14곳, 원정은 영남대로 지도와 명소 31곳 스탬프·곳간, 동반자는 11종의 합류 조건과 진행 |
| 2. 주행 계기판 | `python tools/vc_drive.py --scenario calm --out calm.csv` (230초) | D단에 들어가면 "주상전하 납시오!"와 계기판. 탈것은 화면 안쪽으로 달려가는 뒷모습. 이번 주행 거리·경험치, 1초마다 차오르는 1분 구간 게이지, 지난 구간 칸, 옛길 원정 막대. 1분마다 "+10XP" 알림 띠. P단 후 결과 음성 |
| 3. 대장간 퀘스트 | `python tools/vc_drive.py --preset tpms-on` → 1분 넘게 지난 뒤 `--preset tpms-off` (그사이 4번을 해도 됩니다) | 타이어 경고등이 켜지면 "말편자를 갈 때입니다" 퀘스트 카드, 꺼지면 완료 연출과 개근 보호권 1장 |
| 4. 검약 행차·옛길 원정 | `python tools/vc_drive.py --scenario eco --out eco.csv` (230초) | 주차 후 '검약 행차'(전비, 곳간에 쌓인 쌀)와 옛길 지점 스탬프 연출 |
| 5. 비 오는 밤 | `python tools/vc_drive.py --scenario rainynight --out rain.csv` (230초) | 출발 때 "야행을 나섭니다", 밤 장면, 지평선에서 다가와 양옆으로 지나가는 초롱 장대(바다 위는 물에 뜬 유등), 빗줄기. 주차 후 빗길 차분 운전 보너스. 눈은 `시연 명령 --ef temp -3`과 `--preset wiper-on`으로 보고, `시연 명령 --ez tempReset true`와 `--preset wiper-off`로 되돌립니다 |
| 6. 지역 테마·어린이보호구역 | `python tools/vc_drive.py --scenario ulsan --out ulsan.csv` (155초) | 울산 진입 안내와 고래 밤바다 장면, 시연 어린이보호구역에서 "천천히!"와 아이 지킴이 보너스. 지역 진입 안내는 지역마다 하루 한 번이라 7번보다 먼저 합니다 |
| 7. 명소 도착 | `시연 명령 --es route DAEWANGAM` → `python tools/vc_drive.py --scenario landmark --out landmark.csv` (100초) | 내비게이션에 대왕암공원 경로. 회전이 가까운 동안 계기판에 '안내 대기'(행차 음성을 미룸). 주차 후 대왕암공원 도착 스탬프 |
| 8. 안전띠와 개근 보호권 | `시연 명령 --es seed ULSAN --ei seedDays 2` → `python tools/vc_drive.py --scenario unbelted --out unbelted.csv` | 출발 때 "안전띠를 매셔야 개근으로 셉니다". 3번에서 받은 보호권으로 주차 후 '개근 보호권 사용'과 함께 개근이 이어짐 |
| 9. 암행어사 | `시연 명령 --es inspection "20,120,60"` → `python tools/vc_drive.py --scenario calm --out calm2.csv` | 주행 중에는 아무 표시가 없고, 주차 후 "암행어사 출두요!"와 마패 |
| 10. 역참에서 쉬어가기 | `시연 명령 --es rest "1,0.5"` → `python tools/vc_drive.py --scenario short --out short.csv` → 30초 쉬고 다시 `short` | 1분 뒤 "역마를 갈아탈 때입니다"와 내비게이션의 휴게소 검색 결과. 주차하면 휴식 카드가 남은 시간을 세고, 다시 떠나면 "역참에서 잘 쉬셨습니다"와 휴식 보너스. 줄인 기준은 앱을 다시 켤 때까지 남아 맨 마지막에 합니다 |

| 11. 도감·칭호·동반자 | 운전자 메뉴에서 '운전자 추가' 후 새 운전자를 고르고 `시연 명령 --ez seedTour true` → `python tools/vc_drive.py --scenario rainynight --out tour.csv` (230초) → `adb emu geo fix 129.0750 35.1798` | 지난 전국 유람 기록(6개 지역, 명소 2곳, 야행 4번)으로 도감과 대표 칭호가 찹니다. 주행 뒤 "부엉이가 밤길 행차에 합류했습니다", 숨은 칭호 '비 오는 밤의 나그네'와 '달빛 행차꾼'. 부엉이가 정차 화면 오른쪽 아래에 따라다니고, 위치를 부산으로 옮기면 정차 화면이 빨간 등대·광안대교 장면으로 바뀝니다 |

- 축하 연출은 '확인'이나 뒤로 가기 키로 넘깁니다.
- 각 시나리오는 끝나면 P단으로 돌아갑니다. 다음 시나리오는 앞 시나리오의 음성이 끝난 뒤 시작합니다.

## 4. 알아 둘 점

- 행차는 주행 중 조작을 요구하지 않습니다. 주행 중에는 짧은 음성과 계기판만 쓰고, 보상과 연출은 차를 세운 뒤에 보여 줍니다.
- 에뮬레이터 차량의 한계를 이렇게 메웠습니다.
    - 주행 전비를 주지 않아 배터리 잔량과 적산거리로 계산합니다.
    - 바깥 기온이 25도로 고정이라 눈 장면은 시연 명령으로 기온을 바꿔 봅니다.
    - 운전석 안전띠 체결 신호가 늘 '안 맴'이라 안전띠 경고등으로 판정합니다(`--preset unbelted`/`belted`).
    - 내비게이션의 차 위치가 움직이지 않아, 명소 도착은 앱이 받는 위치와 내비게이션 목적지 좌표로 판정합니다.
- 차속·기어·주행 규제는 AAOS Car API로, 배터리·적산거리·경고등·전조등·와이퍼·안전띠는 Pleos Vehicle SDK로, 목적지·회전 안내·휴게소 검색은 Pleos NaviHelper SDK로 받습니다.
- 안내 음성은 미리 녹음한 문장입니다(Pleos TTS SDK 준비 중).

## 5. 막힐 때

| 증상 | 해결 |
|---|---|
| `vc_drive.py`가 `HTTP Error 503` | 브라우저의 Virtual Console을 닫고 다시 실행합니다. `adb forward tcp:8088 tcp:8088`을 확인합니다 |
| 행차가 화면에 없음 | 작업 막대의 행차 아이콘을 누릅니다 |
| 주행 중 화면이 가려짐 | 출시용 APK입니다. 시연용 APK를 설치합니다 |
| 처음부터 다시 | `adb shell pm clear --user 10 haengcha.app` 뒤 앱을 다시 실행합니다. 기록과 함께 권한도 지워지므로 위치 권한 창에서 다시 '앱 사용 중에만 허용'을 누릅니다 |
| 기록·로그 확인 | `adb logcat -s DriveController VoiceGuide` |

자세한 빌드·실행 방법은 소스의 `README.md`에 있습니다.
