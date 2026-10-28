"""행차 안전 안내의 예비 녹음을 Supertonic 3(소개 영상 나레이션과 같은 목소리)로 합성해 앱 assets에 넣는다.

앱은 모든 안내를 Pleos TextToSpeech SDK(ON_DEVICE)로 합성한다. SDK를 쓸 수 없는 차량에서도 안전과 관련된 안내
(어린이보호구역, 안전띠, 휴식)는 들리도록 이 문장들만 녹음해 두고, 앱은 180ms 묵음 녹음(_preroll.ogg) 뒤에 이어 재생한다.
녹음은 Ogg Opus(32kbps, 모노)로 넣는다.

준비: pip install sherpa-onnx soundfile numpy
      모델 sherpa-onnx-supertonic-3-tts-int8-2026-05-11 (영상 프로젝트 CLAUDE_CODE_GUIDE.md 참조), ffmpeg
예)  python tools/make_voice_clips.py --model-dir <모델 폴더>

문장(text)은 core-judge Announcements의 상수와 같아야 한다. 앱 단위 테스트(VoiceAssetsTest)가 어긋난 문장을 잡는다.
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf

# (파일 이름, 문장) — core-judge Announcements와 같아야 한다
PHRASES = [
    ("school_zone", "어린이보호구역입니다. 천천히!"),
    ("belt", "안전띠를 매셔야 개근으로 셉니다."),
    ("rest_due", "오래 달리셨습니다. 역마를 갈아탈 때입니다. 쉬어 가세요."),
    ("rest_area_ahead", "앞길에 휴게소가 있습니다."),
    ("rest_search", "가까운 휴게소를 내비게이션에 띄웠습니다."),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--sid", type=int, default=0, help="영상과 같은 0번 목소리")
    ap.add_argument("--speed", type=float, default=1.1)
    ap.add_argument("--rate", type=int, default=24000, help="Opus에 넣을 표본율(48000·24000·16000 등)")
    ap.add_argument("--bitrate", default="32k", help="Opus 비트레이트")
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
        for name, text in PHRASES:
            g = so.GenerationConfig()
            g.sid, g.speed, g.extra, g.num_steps = a.sid, a.speed, {"lang": "ko"}, 8
            r = tts.generate(text, g)
            x = np.asarray(r.samples, dtype=np.float32)
            # 앞뒤 무음은 잘라 낸다. 선행 묵음은 앱이 붙인다
            idx = np.where(np.abs(x) > 0.01)[0]
            if len(idx):
                x = x[max(0, idx[0] - r.sample_rate // 100): min(len(x), idx[-1] + r.sample_rate // 20)]
            raw = os.path.join(tmp, name + ".wav")
            sf.write(raw, x, r.sample_rate)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", raw, "-ar", str(a.rate), "-ac", "1", "-c:a", "libopus",
                            "-b:a", a.bitrate, "-application", "voip", "-map_metadata", "-1",
                            os.path.join(a.out, name + ".ogg")], check=True)
            index.append(f"{text}\t{name}.ogg")
            print(f"{name}: {len(x) / r.sample_rate:.2f}초  {text}")

    # 선행 묵음(Pleos 권장 150~200ms). 앱이 예비 녹음 앞에 재생한다
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono", "-t", "0.18", "-c:a", "libopus",
                    "-b:a", a.bitrate, "-application", "voip", os.path.join(a.out, "_preroll.ogg")], check=True)

    with open(os.path.join(a.out, "index.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(index) + "\n")
    print(f"완료: {len(index)}개 → {os.path.abspath(a.out)}")


if __name__ == "__main__":
    main()
