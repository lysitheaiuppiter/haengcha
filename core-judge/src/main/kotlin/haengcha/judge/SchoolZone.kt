package haengcha.judge

/**
 * 어린이보호구역(기획서 4.7). 제출 시연은 지정 좌표 한 곳으로 하고, 공공데이터 연동은 사업화 단계에서 붙인다.
 */
data class SchoolZone(val name: String, val center: GeoPoint, val radiusM: Double) {
    operator fun contains(p: GeoPoint) = center.distanceKm(p) * 1000 <= radiusM

    companion object {
        /** 시연용 지정 좌표(울산 시내). 실제 보호구역 위치가 아니다. */
        val DEMO = listOf(SchoolZone("시연 구역", GeoPoint(35.5440, 129.3300), 300.0))

        fun at(p: GeoPoint, zones: List<SchoolZone> = DEMO): SchoolZone? = zones.firstOrNull { p in it }
    }
}

/**
 * 아이 지킴이 규칙(임시값). 구역 안에서 [minInsideSec] 이상 머물며 한 번도 [maxKph]를 넘지 않고 지나면 [bonusXp]를 준다.
 */
data class GuardianRule(val maxKph: Double = 30.0, val minInsideSec: Double = 10.0, val bonusXp: Int = 20)

/** 어린이보호구역 한 번 통과를 지켜본다. 들어갈 때 만들고, 차속을 넣고, 나올 때 [finish]로 결과를 받는다. */
class SchoolZonePass(private val enterNanos: Long, private val rule: GuardianRule = GuardianRule()) {
    var maxKph = 0.0
        private set

    fun onSpeed(sample: SpeedSample) {
        if (sample.tNanos >= enterNanos) maxKph = maxOf(maxKph, sample.mps * 3.6)
    }

    /** 아이 지킴이 보너스를 받을 통과였는지. */
    fun finish(exitNanos: Long): Boolean =
        (exitNanos - enterNanos) / 1e9 >= rule.minInsideSec && maxKph <= rule.maxKph
}
