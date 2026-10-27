package haengcha.drive

import haengcha.judge.Buddy
import haengcha.judge.JoseonTheme
import haengcha.judge.JourneyStatus
import haengcha.judge.Landmark
import haengcha.judge.LiveTrip
import haengcha.judge.ProgressSummary
import haengcha.judge.Quest
import haengcha.judge.Region
import haengcha.judge.RegularStatus
import haengcha.judge.SchoolZone
import haengcha.judge.Station
import haengcha.judge.Title
import haengcha.judge.TripRecord
import haengcha.judge.Weather

/** 화면이 보는 상태. 주행 엔진이 만들고 앱 화면은 읽기만 한다. */
data class DriveUiState(
    val progress: ProgressSummary,
    /** 최근 주행부터. */
    val recentTrips: List<TripRecord>,
    val restricted: Boolean = false,
    /** 주행 규제에 영상 금지가 들어 있다. 실시간 장면을 마지막 프레임에서 멈춘다. */
    val noVideo: Boolean = false,
    val inTrip: Boolean = false,
    val collecting: Boolean = false,
    val missingSignals: List<String> = emptyList(),
    /** 위치를 받고 있는지(위치 권한). */
    val locating: Boolean = false,
    /** 지금 차가 있는 지역 테마. 제출 범위 밖이면 null. */
    val region: Region? = null,
    /** 지금 지나는 어린이보호구역. */
    val schoolZone: SchoolZone? = null,
    /** 충전 포트가 연결돼 있는지(쉬어가는 행차). */
    val charging: Boolean = false,
    /** 정차 후 차례로 보여 줄 축하 연출. */
    val celebrations: List<Celebration> = emptyList(),
    val profiles: List<Profile> = listOf(Profile.DEFAULT),
    val activeProfile: String = Profile.DEFAULT.id,
    /** 보유한 콜라보 동반자(시연용 목업). */
    val owned: Set<String> = emptySet(),
    /** 야행: 전조등이 켜져 있거나 저녁 7시~아침 6시. */
    val night: Boolean = false,
    /** 와이퍼가 돌고 있으면 비, 바깥 기온이 낮으면 눈. */
    val weather: Weather? = null,
    /** 열려 있는 대장간 퀘스트. */
    val quests: Set<Quest> = emptySet(),
    /** 쉬어 갈 때가 됐다(역참). */
    val restDue: Boolean = false,
    /** 쉬는 중이면 휴식 보너스까지 남은 분. */
    val restLeftMin: Int? = null,
    /** 다음 회전이 가까워 행차 음성을 미루고 있다. */
    val voiceHeld: Boolean = false,
    /** 이 차량에서 읽지 못하는 Pleos SDK 값. */
    val missingSdk: List<String> = emptyList(),
    /** 주행 중 화면에 보여 줄 이번 주행(1초마다 갱신). 주행 중이 아니면 null. */
    val live: LiveView? = null,
    /** 운전자가 고른 동반자. 아직 합류하지 않았으면(이달 거북이가 떠난 경우 등) [buddy]가 대신 고른다. */
    val chosenBuddy: Buddy? = null,
    /** 운전자가 고른 대표 칭호. 그 칭호가 없으면(운전자를 바꾼 경우 등) [title]이 가장 어려운 칭호를 단다. */
    val chosenTitle: Title? = null,
) {
    /** 정차 화면에 다는 대표 칭호. */
    val title: Title?
        get() = chosenTitle?.takeIf { it in progress.titles } ?: progress.titles.firstOrNull()

    /** 정차 화면에 함께 있는 동반자. 고른 동반자가 없으면 가장 늦게 열리는 동반자. */
    val buddy: Buddy?
        get() = chosenBuddy?.takeIf { it in progress.buddies } ?: Buddy.entries.lastOrNull { it in progress.buddies }
}

/** 주행 중 화면의 이번 주행: 지금까지 판정, 이번 주행을 더한 옛길 원정, 잠깐 띄우는 알림 띠. */
data class LiveView(val trip: LiveTrip, val journey: JourneyStatus, val banner: String? = null)

/** 정차 후 보여 줄 축하 연출. 해금 연출은 차를 세운 뒤에만 재생한다(기획서 4.2). */
sealed interface Celebration {
    data class ThemeUnlocked(val theme: JoseonTheme) : Celebration
    data class RegionJoined(val status: RegularStatus) : Celebration
    data class GradeUp(val status: RegularStatus) : Celebration
    data object Mapae : Celebration
    data object Guardian : Celebration
    data class BuddyJoined(val buddy: Buddy) : Celebration
    data class TitleEarned(val title: Title) : Celebration
    data class Eco(val kmPerKwh: Double, val rice: Double) : Celebration
    data class WeatherCalm(val weather: Weather) : Celebration
    data object Rested : Celebration
    data class StationReached(val station: Station) : Celebration
    data class Stamp(val landmark: Landmark) : Celebration
    data class QuestDone(val quest: Quest) : Celebration
    data object ShieldUsed : Celebration
}
