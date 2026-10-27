"""Pleos Connect Virtual Console로 정해진 주행 시나리오를 재생하고, 보낸 값을 CSV로 남긴다.

먼저 에뮬레이터를 켜고 `adb forward tcp:8088 tcp:8088`을 실행한다.
브라우저에 Virtual Console을 열어 두면 그쪽 물리 계산이 값을 덮어쓸 수 있으니 닫고 실행한다.
콘솔은 1초마다 세션 heartbeat를 받아야 값을 받으므로(3초 넘으면 503) 브라우저 대신 이 스크립트가 보낸다.

시나리오에 경로(ROUTES)가 있으면 `adb emu geo fix`로 에뮬레이터 GPS 위치도 1초마다 옮긴다.
시나리오에 차량 신호 변화(EVENTS)가 있으면 출발 전·주행 중·주차 후에 Virtual Console 값(전조등, 와이퍼, 경고등,
적산거리, 배터리 잔량)도 바꾼다. Pleos Vehicle SDK가 이 값을 앱에 넘긴다.
주행 없이 값만 바꾸려면 --preset을 쓴다(예: 대장간 퀘스트 시연 --preset tpms-on).

예) python tools/vc_drive.py --out probe-logs/drive_sent.csv
    python tools/vc_drive.py --scenario calm --out probe-logs/calm_sent.csv
    python tools/vc_drive.py --scenario ulsan --out probe-logs/ulsan_sent.csv
    python tools/vc_drive.py --scenario eco --out probe-logs/eco_sent.csv
    python tools/vc_drive.py --preset tpms-on
"""
import argparse
import csv
import json
import math
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:8088"
CLIENT = f"vc-drive-{uuid.uuid4()}"

# 시나리오별 (구간 길이 초, 시작 km/h, 끝 km/h, 설명)
SCENARIOS = {
    # 급가속·급감속이 한 번씩 들어간 43초 주행
    "harsh": [
        (5.0, 0, 0, "정차(D)"),
        (10.0, 0, 50, "완만한 가속 1.4m/s2"),
        (5.0, 50, 50, "정속"),
        (3.0, 50, 90, "급가속 3.7m/s2"),
        (7.0, 90, 90, "정속"),
        (2.5, 90, 40, "급감속 5.6m/s2"),
        (7.5, 40, 0, "완만한 감속 1.5m/s2"),
        (3.0, 0, 0, "정차"),
    ],
    # 급가속·급감속 없이 정체 구간을 지나는 230초 출퇴근. core-judge 기준 안전 4구간, 평정심 2구간, 50XP
    "calm": [
        (5.0, 0, 0, "정차(D)"),
        (15.0, 0, 40, "출발 0.7m/s2"),
        (100.0, 40, 40, "정속 40km/h"),
        (20.0, 40, 15, "정체 진입"),
        (70.0, 15, 15, "정체 15km/h"),
        (15.0, 15, 0, "정지"),
        (5.0, 0, 0, "정차"),
    ],
    # 울산 밖에서 출발해 울산에 들어가고, 시연 어린이보호구역을 25km/h로 지나는 155초 주행
    "ulsan": [
        (5.0, 0, 0, "정차(D)"),
        (15.0, 0, 40, "출발"),
        (30.0, 40, 40, "정속, 25초에 울산 진입"),
        (10.0, 40, 25, "보호구역 앞 감속"),
        (40.0, 25, 25, "62~96초 어린이보호구역 25km/h"),
        (10.0, 25, 40, "가속"),
        (20.0, 40, 40, "정속"),
        (20.0, 40, 0, "정지"),
        (5.0, 0, 0, "정차"),
    ],
    # 짧은 출퇴근 80초(급가속·급감속 없음). 안전띠·역참 시연에 쓴다
    "short": [
        (5.0, 0, 0, "정차(D)"),
        (15.0, 0, 40, "출발"),
        (40.0, 40, 40, "정속"),
        (15.0, 40, 0, "정지"),
        (5.0, 0, 0, "정차"),
    ],
    # 대왕암공원 쪽으로 가는 100초 주행(명소 도착 스탬프 시연)
    "landmark": [
        (5.0, 0, 0, "정차(D)"),
        (15.0, 0, 40, "출발"),
        (60.0, 40, 40, "정속"),
        (15.0, 40, 0, "정지"),
        (5.0, 0, 0, "도착"),
    ],
}
# 검약 행차(calm과 같은 차속, 적산거리 +25km·잔량 3%), 비 오는 밤(calm과 같은 차속, 전조등·와이퍼), 안전띠 안 맴(short)
SCENARIOS["eco"] = SCENARIOS["calm"]
SCENARIOS["rainynight"] = SCENARIOS["calm"]
SCENARIOS["unbelted"] = SCENARIOS["short"]

# Virtual Console 값(엔드포인트, 쿼리). 콘솔 카탈로그(/vehicle/catalog)의 write_endpoint 형식이다
PRESETS = {
    "headlights-on": ("/vehicle/lights", {"feature": "headlamp_mode", "value": "LowBeam"}),
    "headlights-off": ("/vehicle/lights", {"feature": "headlamp_mode", "value": "Off"}),
    "wiper-on": ("/vehicle/body", {"front_wiper": "Low"}),
    "wiper-off": ("/vehicle/body", {"front_wiper": "Off"}),
    "tpms-on": ("/vehicle/telltales/simulator", {"type": "TPMS", "state": "On", "color": "Yellow"}),
    "tpms-off": ("/vehicle/telltales/simulator", {"type": "TPMS", "state": "Off", "color": "Yellow"}),
    "oil-on": ("/vehicle/telltales/simulator", {"type": "EngineOilPressure", "state": "On", "color": "Red"}),
    "oil-off": ("/vehicle/telltales/simulator", {"type": "EngineOilPressure", "state": "Off", "color": "Red"}),
    "unbelted": ("/vehicle/telltales/simulator", {"type": "SeatBelt", "state": "On", "color": "Red"}),
    "belted": ("/vehicle/telltales/simulator", {"type": "SeatBelt", "state": "Off", "color": "Red"}),
}


def odometer_add(km):
    # 콘솔은 적산거리를 줄이지 못하게 막으므로(400 traveled_distance_must_not_decrease) 지금 값에 더한다
    return "odo+", km


def soc(percent):
    return "/vehicle/ev-battery", {"field": "soc", "value": percent}


# 시나리오별 차량 신호 변화. before는 기어를 넣기 전, during은 (시작 초, 값), after는 P단으로 돌린 뒤
EVENTS = {
    "eco": {
        "before": [soc(80)],
        "during": [(200, odometer_add(25)), (200, soc(77))],
    },
    "rainynight": {
        "before": [PRESETS["headlights-on"], PRESETS["wiper-on"]],
        "after": [PRESETS["headlights-off"], PRESETS["wiper-off"]],
    },
    "unbelted": {
        "before": [PRESETS["unbelted"]],
        "after": [PRESETS["belted"]],
    },
}

# 시나리오별 (시작 초, 위도, 경도). 다음 지점 시각까지 그 위치에 머문다
ROUTES = {
    "ulsan": [
        (0, 35.8000, 129.2000),   # 경주 쪽, 울산 범위 밖
        (25, 35.6000, 129.3200),  # 울산 북구
        (62, 35.5440, 129.3300),  # 시연 어린이보호구역(core-judge SchoolZone.DEMO)
        (96, 35.5300, 129.3100),  # 보호구역을 벗어남
    ],
    # 앱 DebugReceiver의 시연용 대왕암공원 좌표(35.4925, 129.4395)로 다가간다
    "landmark": [
        (0, 35.5150, 129.4250),
        (30, 35.5050, 129.4320),
        (60, 35.4980, 129.4370),
        (85, 35.4930, 129.4393),
    ],
}


def request(path, method):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=2) as r:
        r.read()


def put(path):
    request(path, "PUT")


def put_value(endpoint, params):
    if endpoint == "odo+":
        with urllib.request.urlopen(BASE + "/vehicle/state", timeout=2) as r:
            now = json.load(r)["odometer"]["traveled_distance_km"]
        endpoint, params = "/vehicle/odometer", {"traveled_distance_km": math.ceil(now) + params}
    put(endpoint + "?" + urllib.parse.urlencode(params))


def heartbeat(closing=False):
    request(f"/browser/session?client={CLIENT}" + ("&closing=1" if closing else ""), "POST")


def controls(kph, accelerator=0, brake=0):
    # 콘솔은 정수 km/h만 받는다(소수면 400 invalid_speed)
    put(f"/vehicle/controls?speed={round(kph)}&accelerator={accelerator}&brake={brake}&steering=0")


def adb():
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    return os.path.join(sdk, "platform-tools", "adb")


def geo_fix(lat, lon):
    # adb emu geo fix는 경도, 위도 순서다
    subprocess.run([adb(), "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"], check=False, capture_output=True)


def position(route, t):
    here = route[0]
    for p in route:
        if p[0] <= t:
            here = p
    return here[1], here[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenario", choices=sorted(SCENARIOS), default="harsh")
    ap.add_argument("--hz", type=float, default=10.0, help="값을 보내는 주기")
    ap.add_argument("--out", help="보낸 차속 CSV(주행할 때 필요)")
    ap.add_argument("--preset", action="append", choices=sorted(PRESETS), help="주행 없이 콘솔 값만 바꾼다(여러 번 줄 수 있음)")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서 한글이 깨지지 않게

    if a.preset:
        heartbeat()
        for name in a.preset:
            put_value(*PRESETS[name])
            print(f"설정: {name}")
        time.sleep(1.0)
        heartbeat(closing=True)
        return
    if not a.out:
        ap.error("주행 시나리오에는 --out이 필요합니다")
    events = EVENTS.get(a.scenario, {})
    pending = sorted(events.get("during", []), key=lambda e: e[0])

    step = 1.0 / a.hz
    route = ROUTES.get(a.scenario)
    if route:
        geo_fix(*position(route, 0))
        time.sleep(2)  # 앱이 출발 위치를 받은 뒤 기어를 넣는다(도착 판정은 출발 위치도 본다)
    heartbeat()
    for e in events.get("before", []):
        put_value(*e)
    if events.get("before"):
        time.sleep(1.5)  # 앱이 출발 전 값을 받도록 기어를 넣기 전에 잠깐 둔다
        heartbeat()
    put("/vehicle/gear?gear=Drive")
    try:
        with open(a.out, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["host_t", "speed_kph", "segment"])
            t0 = time.monotonic()
            last_beat = t0
            tick = 0
            for dur, v0, v1, name in SCENARIOS[a.scenario]:
                n = max(1, round(dur * a.hz))
                for i in range(n):
                    if time.monotonic() - last_beat >= 1.0:
                        heartbeat()
                        last_beat = time.monotonic()
                        if route:
                            geo_fix(*position(route, last_beat - t0))
                        while pending and pending[0][0] <= last_beat - t0:
                            put_value(*pending.pop(0)[1])
                    kph = v0 + (v1 - v0) * (i + 1) / n
                    controls(kph, accelerator=30 if v1 > v0 else 0, brake=40 if v1 < v0 else 0)
                    w.writerow([f"{time.monotonic() - t0:.3f}", round(kph), name])
                    tick += 1
                    time.sleep(max(0.0, t0 + tick * step - time.monotonic()))
        controls(0)
        put("/vehicle/gear?gear=Park")
        for e in events.get("after", []):
            time.sleep(1.0)
            heartbeat()
            put_value(*e)
    finally:
        heartbeat(closing=True)
    print(f"완료: {tick}회 전송, {time.monotonic() - t0:.1f}초")


if __name__ == "__main__":
    main()
