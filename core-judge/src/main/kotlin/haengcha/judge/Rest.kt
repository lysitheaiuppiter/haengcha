package haengcha.judge

/**
 * 역참에서 쉬어가기 규칙(임시값). [driveLimitMin] 넘게 쉬지 않고 달리면 쉬어 가라고 알리고,
 * 그 뒤 [restMin] 넘게 쉬고 다시 떠나면 보너스를 준다. 그보다 짧게 선 것은 쉰 것으로 보지 않는다.
 */
data class RestRule(val driveLimitMin: Double = 120.0, val restMin: Double = 15.0, val bonusXp: Int = 20)

/**
 * 쉬지 않고 이어 달린 시간을 잰다. 시각은 벽시계(ms)라 재부팅을 넘어 이어진다.
 * 주행 사이에 [RestRule.restMin]보다 짧게 섰으면 같은 운전으로 본다.
 */
class RestTracker(var rule: RestRule = RestRule()) {
    /** 지금 이어지는 운전을 시작한 시각. */
    var sessionStartMs: Long? = null
        private set
    /** 마지막 주행을 끝낸 시각. */
    var lastEndMs: Long? = null
        private set
    /** 쉬어 가라고 알린 뒤 아직 쉬지 않았다. */
    var due = false
        private set

    /** 주행을 시작할 때 부른다. 쉬어 가라고 알린 뒤 충분히 쉬었으면 true(휴식 보너스). */
    fun onTripStart(nowMs: Long): Boolean {
        val rested = lastEndMs?.let { nowMs - it >= rule.restMin * MIN_MS } ?: false
        if (sessionStartMs != null && !rested) return false
        val bonus = due && rested
        sessionStartMs = nowMs
        due = false
        return bonus
    }

    /** 주행 중 주기적으로 부른다. 쉬어 갈 때가 된 순간 한 번 true. */
    fun onDriving(nowMs: Long): Boolean {
        val start = sessionStartMs ?: return false
        if (due || nowMs - start < rule.driveLimitMin * MIN_MS) return false
        due = true
        return true
    }

    fun onTripEnd(nowMs: Long) {
        lastEndMs = nowMs
    }

    /** 쉬는 중이면 보너스까지 남은 시간(ms). 쉬어 갈 때가 아니거나 주행 중이면 null. */
    fun restLeftMs(nowMs: Long, inTrip: Boolean): Long? {
        if (!due || inTrip) return null
        val end = lastEndMs ?: return null
        return (rule.restMin * MIN_MS - (nowMs - end)).toLong().coerceAtLeast(0)
    }

    fun encode(): String = listOf(sessionStartMs ?: "", lastEndMs ?: "", if (due) 1 else 0).joinToString(",")

    fun decode(s: String?) {
        val f = s?.split(',')?.takeIf { it.size == 3 } ?: return
        sessionStartMs = f[0].toLongOrNull()
        lastEndMs = f[1].toLongOrNull()
        due = f[2] == "1"
    }

    private companion object {
        const val MIN_MS = 60_000.0
    }
}

/**
 * 안내 타이밍(기획서 9.3). 내비게이션의 다음 회전이 [holdM] 안이면 행차 음성을 미뤘다가 회전을 지나면 낸다.
 * 회전 정보가 멈춰 있어도 [maxHoldSec]보다 오래 미루지는 않는다.
 */
data class TurnHoldRule(val holdM: Double = 300.0, val maxHoldSec: Double = 30.0)

/** 미뤄 둔 안내를 들고 있다가 낼 때가 되면 돌려준다. */
class VoiceGate(private val rule: TurnHoldRule = TurnHoldRule()) {
    private val pending = ArrayDeque<List<String>>()
    private var since: Long? = null
    var nextTurnM: Double? = null
        private set

    val holding get() = pending.isNotEmpty()

    private fun nearTurn() = nextTurnM?.let { it < rule.holdM } ?: false

    /** 지금 말할 안내 묶음들. 회전 앞이면 비워 두고 미룬다. [urgent]는 미루지 않는다(어린이보호구역 등). */
    fun offer(lines: List<String>, nowMs: Long, urgent: Boolean = false): List<List<String>> {
        if (urgent) return listOf(lines)
        if (!nearTurn() && pending.isEmpty()) return listOf(lines)
        if (pending.isEmpty()) since = nowMs
        pending += lines
        return release(nowMs)
    }

    /** 다음 회전까지 거리가 바뀌었을 때. 경로가 없으면 null. */
    fun onNextTurn(distanceM: Double?, nowMs: Long): List<List<String>> {
        nextTurnM = distanceM
        return release(nowMs)
    }

    fun tick(nowMs: Long): List<List<String>> = release(nowMs)

    private fun release(nowMs: Long): List<List<String>> {
        if (pending.isEmpty()) return emptyList()
        val waited = since?.let { (nowMs - it) / 1000.0 } ?: 0.0
        if (nearTurn() && waited < rule.maxHoldSec) return emptyList()
        val out = pending.toList()
        pending.clear()
        since = null
        return out
    }
}
