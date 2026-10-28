package haengcha.app.voice

/**
 * 화면 문구를 합성 엔진이 바르게 읽도록 고친다. 화면에는 원래 문구를 쓰고, 소리로만 바꿔 읽는다.
 * 예전 녹음(tools/make_voice_clips.py의 SPOKEN)에서 고쳐 읽던 표기와 같다.
 */
internal object Spoken {

    private val words = listOf(
        "투뿔(1++) 한우" to "투뿔 한우",
        "1+ 한우" to "원플러스 한우",
        "3등급 한우" to "삼등급 한우",
        "2등급 한우" to "이등급 한우",
        "1등급 한우" to "일등급 한우",
        "N서울타워" to "엔 서울타워",
        "김광석다시그리기길" to "김광석 다시 그리기 길",
        "전주한옥마을" to "전주 한옥마을",
        "청주고인쇄박물관" to "청주 고인쇄 박물관",
    )
    private val level = Regex("""Lv\.(\d+)""")

    fun of(text: String): String =
        words.fold(text) { t, (from, to) -> t.replace(from, to) }.replace(level, "레벨 $1")
}
