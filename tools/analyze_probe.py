"""speedprobe가 남긴 CSV에서 차속 수신 주기, 지연, 가감속, 기어·주행 규제 변화를 요약한다.

예) python tools/analyze_probe.py probe-logs/probe_xxx.csv
"""
import csv
import statistics
import sys

SPEED = "PERF_VEHICLE_SPEED"
STATUS_AVAILABLE = "0"


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(len(s) * p))]


def main(path):
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서 한글이 깨지지 않게
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    t0 = int(rows[1]["recv_ns"]) if len(rows) > 1 else 0
    rel = lambda ns: (int(ns) - t0) / 1e9

    speed = [(int(r["event_ns"]), int(r["recv_ns"]), float(r["value"]))
             for r in rows if r["kind"] == "PROP" and r["prop"] == SPEED and r["status"] == STATUS_AVAILABLE]
    if len(speed) < 2:
        print("차속 표본이 부족합니다.")
        return

    span = (speed[-1][1] - speed[0][1]) / 1e9
    gaps = [(b[0] - a[0]) / 1e6 for a, b in zip(speed, speed[1:]) if b[0] > a[0]]
    latency = [(r - e) / 1e6 for e, r, _ in speed]
    moving = [s for s in speed if s[2] > 0.1]
    print(f"차속 표본 {len(speed)}건, {span:.1f}초, 평균 {(len(speed) - 1) / span:.1f}Hz")
    if len(moving) > 1:
        mspan = (moving[-1][1] - moving[0][1]) / 1e9
        print(f"  주행 중(>0.1m/s) {len(moving)}건, {mspan:.1f}초, 평균 {(len(moving) - 1) / mspan:.1f}Hz")
    print(f"  이벤트 간격(ms) 중앙 {statistics.median(gaps):.0f}, 95% {pct(gaps, 0.95):.0f}, 최대 {max(gaps):.0f}")
    print(f"  수신 지연(ms) 중앙 {statistics.median(latency):.1f}, 95% {pct(latency, 0.95):.1f}, 최대 {max(latency):.1f}")

    # 비교 기준 표본: 창(초) 이상 앞선 가장 최근 표본. 0.5초는 speedprobe 화면, 1초는 core-judge 판정과 같다
    for window, max_age in ((0.5, 2.5), (1.0, 2.0)):
        accel = []
        j = 0
        w, m = int(window * 1e9), int(max_age * 1e9)
        for i, (e, _, v) in enumerate(speed):
            while j + 1 < i and speed[j + 1][0] <= e - w:
                j += 1
            if speed[j][0] <= e - w and e - speed[j][0] <= m:
                accel.append((rel(speed[i][1]), (v - speed[j][2]) / ((e - speed[j][0]) / 1e9)))
        if accel:
            hi = max(accel, key=lambda x: x[1])
            lo = min(accel, key=lambda x: x[1])
            print(f"  {window}초 창: 최대 가속 {hi[1]:+.2f}m/s2 ({hi[0]:.1f}초), 최대 감속 {lo[1]:+.2f}m/s2 ({lo[0]:.1f}초)")
    print(f"  최고 차속 {max(s[2] for s in speed) * 3.6:.1f}km/h")

    print("기어·주행 규제 변화")
    for r in rows:
        if r["kind"] == "UX" or (r["kind"] == "PROP" and r["prop"] == "GEAR_SELECTION"):
            label = "주행 규제" if r["kind"] == "UX" else "기어"
            print(f"  {rel(r['recv_ns']):7.2f}초 {label}: {r['value']}")


if __name__ == "__main__":
    main(sys.argv[1])
