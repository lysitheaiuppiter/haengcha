package haengcha.judge

/**
 * 대장간 퀘스트(기획서 4.4). 차량 경고등이 켜지면 정비 퀘스트를 열고, 꺼지면 정비를 마친 것으로 보고 개근 보호권을 준다.
 * 경고등은 Vehicle SDK의 경고등 종류(TelltaleType) 이름으로 받는다. 엔진오일은 오일 잔량 신호(낮음)로도 연다.
 */
enum class Quest(val label: String, val title: String, val telltales: Set<String>) {
    TIRE("타이어 공기압", "말편자를 갈 때입니다", setOf("TPMS")),
    OIL("엔진오일", "굴대에 기름칠할 때입니다", setOf("ENGINE_OIL_PRESSURE"));

    companion object {
        /** 지금 켜진 경고등으로 열려 있어야 할 퀘스트. */
        fun active(telltales: Set<String>, oilLow: Boolean = false): Set<Quest> =
            entries.filter { q -> q.telltales.any { it in telltales } || (q == OIL && oilLow) }.toSet()
    }
}

/**
 * 퀘스트 규칙(임시값). 경고등이 [minOpenSec]보다 짧게 켜졌다 꺼지면 정비한 것으로 보지 않고 조용히 닫는다
 * (차량 신호가 켜질 때 잠깐 흔들리는 경우와 보호권을 거저 받는 경우를 막는다).
 */
data class QuestRule(val minOpenSec: Double = 60.0)

/** 퀘스트 상태 변화: 새로 열린 것, 정비를 마친 것(보호권), 너무 짧아 조용히 닫은 것. */
data class QuestChange(val opened: Set<Quest>, val completed: Set<Quest>, val dropped: Set<Quest> = emptySet())

/** 열려 있던 퀘스트 [open](퀘스트 → 연 시각 ms)과 지금 경고등으로 정한 [active]를 견줘 바뀐 것을 낸다. */
fun questChange(open: Map<Quest, Long>, active: Set<Quest>, nowMs: Long, rule: QuestRule = QuestRule()): QuestChange {
    val closed = open.keys - active
    val (done, dropped) = closed.partition { nowMs - open.getValue(it) >= rule.minOpenSec * 1000 }
    return QuestChange(active - open.keys, done.toSet(), dropped.toSet())
}
