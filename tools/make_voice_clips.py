"""행차 음성 안내 문장을 Supertonic 3(소개 영상 나레이션과 같은 목소리)로 합성해 앱 assets에 넣는다.

Pleos 에뮬레이터의 음성 엔진은 TTS_SERVICE 필터에 DEFAULT 카테고리가 없어 Android 표준 TextToSpeech API로 보이지 않는다.
그래서 고정 문장은 미리 녹음해 두고, 선행 묵음(180ms)은 앱이 재생 직전에 PCM 앞에 붙인다.

준비: pip install sherpa-onnx soundfile numpy
      모델 sherpa-onnx-supertonic-3-tts-int8-2026-05-11 (영상 프로젝트 CLAUDE_CODE_GUIDE.md 참조), ffmpeg
예)  python tools/make_voice_clips.py --model-dir <모델 폴더>
     python tools/make_voice_clips.py --model-dir <모델 폴더> --only-missing   # 새 문장만 녹음하고 있던 녹음은 그대로 둔다

문장(text)은 core-judge Announcements가 만드는 문자열과 같아야 한다. 앱 단위 테스트가 빠진 문장을 잡는다.
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf

KOREAN_NUMBERS = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구", "십", "십일", "십이", "십삼", "십사", "십오",
                  "십육", "십칠", "십팔", "십구", "이십"]
MAX_LEVEL = 20
THEMES = ["인력거", "가마", "꽃가마", "어가 행렬"]  # 소달구지는 Lv.0이라 해금 안내가 없다
# (파일 이름, 지역, 테마, 단골 등급) — core-judge Region과 같아야 한다
REGIONS = [
    ("ulsan", "울산", "고래", ["노말 고래", "동 고래", "은 고래", "금 고래", "플래티넘 고래"]),
    ("hoengseong", "횡성", "한우", ["3등급 한우", "2등급 한우", "1등급 한우", "1+ 한우", "투뿔(1++) 한우"]),
]
# 등급 이름을 읽는 방법
SPOKEN = {"3등급 한우": "삼등급 한우", "2등급 한우": "이등급 한우", "1등급 한우": "일등급 한우",
          "1+ 한우": "원플러스 한우", "투뿔(1++) 한우": "투뿔 한우"}

# 대장간 퀘스트(파일 이름, 제목) — core-judge Quest와 같아야 한다
QUESTS = [("tire", "말편자를 갈 때입니다"), ("oil", "굴대에 기름칠할 때입니다")]
# 옛길 원정 지점(한양 출발 제외) — core-judge Station과 같아야 한다
STATIONS = [("pangyo", "판교"), ("yongin", "용인"), ("yangji", "양지"), ("juksan", "죽산"), ("chungju", "충주"),
            ("joryeong", "문경새재 조령관"), ("yugok", "유곡"), ("nakdong", "낙동나루"), ("seonsan", "선산"),
            ("daegu", "대구 경상감영"), ("cheongdo", "청도"), ("miryang", "밀양 영남루"), ("yangsan", "양산"), ("dongnae", "동래")]
# 명소 — core-judge Landmark와 같아야 한다
LANDMARKS = [("daewangam", "대왕암공원"), ("jangsaengpo", "장생포 고래문화마을"), ("taehwagang", "태화강 국가정원"),
             ("ganjeolgot", "간절곶"), ("hoengseong_lake", "횡성호수길"), ("pungsuwon", "풍수원성당"),
             ("birch_forest", "미술관 자작나무숲")]

# (파일 이름, 앱이 말하려는 문장, 실제로 읽힐 문장)
PHRASES = [
    ("boarding", "주상전하 납시오!", "주상전하 납시오!"),
    ("mapae", "암행어사 출두요! 아까 10분, 차분히 잘 달리셨습니다. 마패를 하사합니다.",
     "암행어사 출두요! 아까 십 분, 차분히 잘 달리셨습니다. 마패를 하사합니다."),
    ("closing", "오늘도 안전하게, 행차하셨습니다.", "오늘도 안전하게, 행차 하셨습니다."),
    *[(f"level_{n}", f"Lv.{n} 달성!", f"레벨 {KOREAN_NUMBERS[n]} 달성!") for n in range(1, MAX_LEVEL + 1)],
    *[(f"theme_{i}", f"{label} 테마가 열렸습니다.", f"{label} 테마가 열렸습니다.") for i, label in enumerate(THEMES, start=1)],
    ("school_zone", "어린이보호구역입니다. 천천히!", "어린이보호구역입니다. 천천히!"),
    ("guardian", "아이 지킴이! 어린이보호구역을 천천히 지나셨습니다.", "아이 지킴이! 어린이보호구역을 천천히 지나셨습니다."),
    ("charging", "쉬어가는 행차입니다. 오늘 모은 도감을 정리해 볼까요?", "쉬어가는 행차입니다. 오늘 모은 도감을 정리해 볼까요?"),
    ("companion", "이달의 동반자 거북이가 행차에 합류했습니다.", "이달의 동반자 거북이가 행차에 합류했습니다."),
    *[(f"region_{key}", f"{label}에 오셨습니다. {theme} 테마가 켜집니다.", f"{label}에 오셨습니다. {theme} 테마가 켜집니다.")
      for key, label, theme, _ in REGIONS],
    *[(f"grade_{key}_{i}", f"단골 등급이 올랐습니다. {g}입니다.", f"단골 등급이 올랐습니다. {SPOKEN.get(g, g)}입니다.")
      for key, _, _, grades in REGIONS for i, g in enumerate(grades) if i > 0],
    # Pleos Vehicle·NaviHelper SDK 연동 기능
    ("eco", "검약 행차! 아낀 기운이 곳간에 쌀로 쌓였습니다.", "검약 행차! 아낀 기운이 곳간에 쌀로 쌓였습니다."),
    ("rain_calm", "빗길을 차분히 달리셨습니다.", "빗길을 차분히 달리셨습니다."),
    ("snow_calm", "눈길을 차분히 달리셨습니다.", "눈길을 차분히 달리셨습니다."),
    ("night", "야행을 나섭니다. 초롱을 밝힙니다.", "야행을 나섭니다. 초롱을 밝힙니다."),
    ("belt", "안전띠를 매셔야 개근으로 셉니다.", "안전띠를 매셔야 개근으로 셉니다."),
    ("rest_due", "오래 달리셨습니다. 역마를 갈아탈 때입니다. 쉬어 가세요.", "오래 달리셨습니다. 역마를 갈아탈 때입니다. 쉬어 가세요."),
    ("rest_area_ahead", "앞길에 휴게소가 있습니다.", "앞길에 휴게소가 있습니다."),
    ("rest_search", "가까운 휴게소를 내비게이션에 띄웠습니다.", "가까운 휴게소를 내비게이션에 띄웠습니다."),
    ("rested", "역참에서 잘 쉬셨습니다. 다시 길을 나섭니다.", "역참에서 잘 쉬셨습니다. 다시 길을 나섭니다."),
    ("shield_used", "개근 보호권으로 개근 기록을 지켰습니다.", "개근 보호권으로 개근 기록을 지켰습니다."),
    ("quest_done", "정비를 마쳤습니다. 개근 보호권을 드립니다.", "정비를 마쳤습니다. 개근 보호권을 드립니다."),
    *[(f"quest_{key}", f"대장간 퀘스트입니다. {title}.", f"대장간 퀘스트입니다. {title}.") for key, title in QUESTS],
    *[(f"station_{key}", f"옛길 원정, {label}에 이르렀습니다.", f"옛길 원정, {label}에 이르렀습니다.") for key, label in STATIONS],
    *[(f"stamp_{key}", f"{label} 도착! 스탬프를 찍었습니다.", f"{label} 도착! 스탬프를 찍었습니다.") for key, label in LANDMARKS],
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--sid", type=int, default=0, help="영상과 같은 0번 목소리")
    ap.add_argument("--speed", type=float, default=1.1)
    ap.add_argument("--rate", type=int, default=22050, help="앱에 넣을 표본율")
    ap.add_argument("--only-missing", action="store_true", help="녹음 파일이 이미 있는 문장은 다시 만들지 않는다")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "voice"))
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    import sherpa_onnx as so
    d = a.model_dir.rstrip("/\\") + "/"
    cfg = so.OfflineTtsSupertonicModelConfig(
        duration_predictor=d + "duration_predictor.int8.onnx", text_encoder=d + "text_encoder.int8.onnx",
        vector_estimator=d + "vector_estimator.int8.onnx", vocoder=d + "vocoder.int8.onnx", tts_json=d + "tts.json",
        unicode_indexer=d + "unicode_indexer.bin", voice_style=d + "voice.bin")
    tts = so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(supertonic=cfg, num_threads=4)))

    os.makedirs(a.out, exist_ok=True)
    index = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, text, spoken in PHRASES:
            out = os.path.join(a.out, name + ".wav")
            if a.only_missing and os.path.exists(out):
                index.append(f"{text}\t{name}.wav")
                continue
            g = so.GenerationConfig()
            g.sid, g.speed, g.extra, g.num_steps = a.sid, a.speed, {"lang": "ko"}, 8
            r = tts.generate(spoken, g)
            x = np.asarray(r.samples, dtype=np.float32)
            # 앞뒤 무음은 잘라 낸다. 선행 묵음은 앱이 붙인다
            idx = np.where(np.abs(x) > 0.01)[0]
            if len(idx):
                x = x[max(0, idx[0] - r.sample_rate // 100): min(len(x), idx[-1] + r.sample_rate // 20)]
            raw = os.path.join(tmp, name + ".wav")
            sf.write(raw, x, r.sample_rate)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", raw, "-ar", str(a.rate), "-ac", "1",
                            "-c:a", "pcm_s16le", "-map_metadata", "-1", out], check=True)
            index.append(f"{text}\t{name}.wav")
            print(f"{name}: {len(x) / r.sample_rate:.2f}초  {text}")

    with open(os.path.join(a.out, "index.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(index) + "\n")
    print(f"완료: {len(index)}개 → {os.path.abspath(a.out)}")


if __name__ == "__main__":
    main()
