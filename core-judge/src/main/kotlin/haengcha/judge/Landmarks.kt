package haengcha.judge

/**
 * 스탬프 투어 명소(기획서 4.9). 제출 범위는 울산·횡성이다.
 * 좌표를 따로 두지 않고 내비게이션 목적지 이름으로 알아본다. 이름에 [keywords] 중 하나가 들어 있으면(띄어쓰기 무시) 그 명소다.
 */
enum class Landmark(val label: String, val region: Region, val keywords: List<String>) {
    DAEWANGAM("대왕암공원", Region.ULSAN, listOf("대왕암")),
    JANGSAENGPO("장생포 고래문화마을", Region.ULSAN, listOf("장생포")),
    TAEHWAGANG("태화강 국가정원", Region.ULSAN, listOf("태화강국가정원")),
    GANJEOLGOT("간절곶", Region.ULSAN, listOf("간절곶")),
    HOENGSEONG_LAKE("횡성호수길", Region.HOENGSEONG, listOf("횡성호수길")),
    PUNGSUWON("풍수원성당", Region.HOENGSEONG, listOf("풍수원")),
    BIRCH_FOREST("미술관 자작나무숲", Region.HOENGSEONG, listOf("자작나무숲"));

    companion object {
        fun match(name: String?): Landmark? {
            val n = name?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { l -> l.keywords.any { n.contains(it.replace(" ", "")) } }
        }
    }
}

/**
 * 도착 판정(임시값). 내비게이션이 도착을 알리거나, 목적지 [radiusM] 밖에서 출발해 [radiusM] 안에서 주행을 끝내면 도착이다.
 * 목적지 근처에서 출발해 근처에서 끝난 주행(주차장 안에서 차를 옮긴 경우 등)은 도착이 아니다.
 * 명소에 처음 도착하면 [bonusXp]를 준다.
 */
data class ArrivalRule(val radiusM: Double = 300.0, val bonusXp: Int = 20)

/** 내비게이션 목적지. 좌표는 판정에만 쓰고 저장하지 않는다. */
data class Destination(val name: String, val point: GeoPoint)

/**
 * [start]에서 출발해 [end]에서 끝낸 주행이 목적지 [dest]에 닿았는지.
 * 출발 위치를 모르면(출발 때 아직 위치를 못 받음, 일지에서 이어 간 주행) 끝낸 곳만 본다. 좌표를 일지에 남기지 않기 때문이다.
 */
fun arrived(dest: Destination?, start: GeoPoint?, end: GeoPoint?, rule: ArrivalRule = ArrivalRule()): Boolean {
    if (dest == null || end == null) return false
    fun within(p: GeoPoint) = dest.point.distanceKm(p) * 1000 <= rule.radiusM
    return within(end) && (start == null || !within(start))
}
