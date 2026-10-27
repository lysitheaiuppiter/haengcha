package haengcha.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haengcha.drive.DriveUiState
import haengcha.app.R
import haengcha.judge.JoseonTheme
import haengcha.judge.Levels
import haengcha.judge.ProgressSummary
import haengcha.judge.Region
import haengcha.judge.RegularStatus
import haengcha.judge.TripRecord
import java.util.Locale

// 소개 영상과 같은 색
internal val Ink = Color(0xFF0B2B2A)
internal val Panel = Color(0xFF123A39)
internal val Line = Color(0xFF24504D)
internal val Gold = Color(0xFFF2B84B)
internal val Cream = Color(0xFFF5F1E8)
internal val Muted = Color(0xFF9FBDB7)
/** 배경 그림 위에 얹는 카드. 그림이 밝아도 글씨가 읽히게 어둡게 깐다. */
internal val Glass = Color(0xD90B1A1C)
internal val GlassLine = Color(0x40F5F1E8)

@Composable
fun HaengchaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold, onPrimary = Ink,
            background = Ink, onBackground = Cream,
            surface = Panel, onSurface = Cream,
        ),
        content = content,
    )
}

@Composable
fun HaengchaScreen(state: DriveUiState, actions: ScreenActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 어린이보호구역에서는 정차 중이어도 그림 없이 정적 안내만 둔다(기획서 4.7)
        if (state.schoolZone != null) {
            SchoolZoneLine()
            return@Surface
        }
        // 움직이는 배경은 정차·주행 화면이 함께 쓴다(화면을 바꿔도 영상을 다시 열지 않게 여기 둔다)
        val p = state.progress
        Box(Modifier.fillMaxSize()) {
            ThemeScene(p.themes.last(), state.region, p.gradeIn(state.region), paused = state.noVideo)
            AtmosphereOverlay(state.night, state.weather, paused = state.noVideo)
            if (state.restricted) DrivingScreen(state) else ParkedScreen(state, actions)
        }
    }
}

/** 지금 지역의 단골 등급(0부터). 처음 온 지역이면 0. */
internal fun ProgressSummary.gradeIn(region: Region?) = regulars.firstOrNull { it.region == region }?.grade ?: 0

@Composable
private fun SchoolZoneLine() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.icon_guardian), null, Modifier.size(96.dp))
            Text("어린이보호구역 · 천천히", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Gold)
        }
    }
}

/**
 * 정차 중: 지금 테마 탈것을 올린 단상 그림 위에 레벨·오늘·마패 카드와 아래 띠를 얹는다.
 * 거리·도착 시각 같은 내비게이션 정보는 내비 앱과 겹치지 않게 넣지 않는다.
 * landscape(두 칸)는 카드를 왼쪽에 세로로 두고, portrait(한 칸)는 폭을 꽉 채워 쌓는다(기획서 9.2).
 * 오른쪽 위 메뉴로 도감·동반자·상점·운전자 패널을 연다. 충전을 시작하면 도감이 '쉬어가는 행차'로 열린다.
 * 주행에서 얻은 해금·마패 같은 축하 연출은 차를 세운 뒤 이 화면 위에 한 장씩 띄운다.
 */
@Composable
private fun ParkedScreen(state: DriveUiState, actions: ScreenActions) {
    val p = state.progress
    val theme = p.themes.last()
    var panel by remember { mutableStateOf<PanelKind?>(null) }
    LaunchedEffect(state.charging) { if (state.charging) panel = PanelKind.DOGAM }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            val wide = maxWidth > maxHeight && maxWidth >= 900.dp
            Column(
                if (wide) Modifier.width(460.dp) else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ThemeBadge(theme)
                LevelCard(p)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SmallCard("오늘", "${p.todayXp}XP", "개근 ${p.streak}일" + if (p.shields > 0) " · 보호권 ${p.shields}" else "", Modifier.weight(1f))
                    SmallCard("마패", "${p.mapaeCount}개", "암행어사 출두", Modifier.weight(1f))
                }
                if (state.quests.isNotEmpty()) QuestCard(state)
                if (state.restDue && !state.inTrip) RestCard(state.restLeftMin)
                // 지금 있는 지역, 아니면 최근에 간 지역의 단골 기록
                (state.region ?: p.regulars.firstOrNull()?.region)?.let { region ->
                    RegionCard(region, p.regulars.firstOrNull { it.region == region }, here = region == state.region)
                }
                StatusLine(state, actions.requestPermission)
            }
            MenuChips(state, onOpen = { panel = it }, modifier = Modifier.align(Alignment.TopEnd))
            BottomBar(
                p,
                state.recentTrips.firstOrNull(),
                Modifier.align(Alignment.BottomCenter).then(if (wide) Modifier.widthIn(max = 980.dp) else Modifier.fillMaxWidth()),
            )
        }
        when (panel) {
            PanelKind.DOGAM -> PanelOverlay(
                if (state.charging) "쉬어가는 행차" else "도감",
                if (state.charging) "충전하는 동안 오늘 모은 도감을 정리해 볼까요?" else null,
                onClose = { panel = null },
            ) { DogamContent(p) }
            PanelKind.JOURNEY -> PanelOverlay("옛길 원정", "영남대로 · 한양에서 동래까지", onClose = { panel = null }) { JourneyContent(state) }
            PanelKind.COMPANION -> PanelOverlay("동반자", null, onClose = { panel = null }) { CompanionContent(state) }
            PanelKind.SHOP -> PanelOverlay("콜라보 동반자 상점", "시연용", onClose = { panel = null }) { ShopContent(state, actions.buy) }
            PanelKind.PROFILE -> PanelOverlay("운전자", null, onClose = { panel = null }) { ProfileContent(state, actions) }
            null -> Unit
        }
        state.celebrations.firstOrNull()?.let { CelebrationOverlay(it, actions.dismissCelebration) }
    }
}

internal fun Modifier.glass() = this
    .background(Glass, RoundedCornerShape(20.dp))
    .border(1.dp, GlassLine, RoundedCornerShape(20.dp))

@Composable
private fun ThemeBadge(theme: JoseonTheme) {
    Text(
        "지금 테마 · ${theme.label}",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.glass().padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun LevelCard(p: ProgressSummary) {
    Column(Modifier.fillMaxWidth().glass().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lv.${p.level}", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
        LinearProgressIndicator(
            progress = { p.levelProgress },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            color = Gold,
            trackColor = Line,
        )
        Text("다음 레벨까지 ${p.xpToNextLevel}XP · 누적 ${p.totalXp}XP", fontSize = 20.sp, color = Muted)
    }
}

@Composable
private fun SmallCard(label: String, value: String, note: String, modifier: Modifier) {
    Column(modifier.glass().padding(20.dp)) {
        Text(label, fontSize = 20.sp, color = Muted)
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(note, fontSize = 18.sp, color = Muted)
    }
}

internal val Region.icon
    get() = when (this) {
        Region.ULSAN -> R.drawable.region_whale
        Region.HOENGSEONG -> R.drawable.region_hanwoo
    }

/** 지역 테마와 단골 등급(기획서 4.3). 지역 진입 연출은 정차 후 이 카드로 보여 준다. */
@Composable
private fun RegionCard(region: Region, status: RegularStatus?, here: Boolean) {
    Row(
        Modifier.fillMaxWidth().glass().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(region.icon), null, Modifier.size(88.dp))
        Column {
            Text("${if (here) "지금" else "최근"} ${region.label} · ${region.theme} 테마", fontSize = 20.sp, color = Muted)
            Text(status?.let { "${it.gradeLabel} 단골" } ?: "첫 행차 전", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    status == null -> "이 지역을 달리면 단골 기록이 시작됩니다"
                    status.daysToNext == null -> "방문 ${status.visitDays}일 · 최상위 단골"
                    else -> "방문 ${status.visitDays}일 · ${status.daysToNext}일 더 오면 ${region.grades[status.grade + 1]}"
                },
                fontSize = 18.sp,
                color = Muted,
            )
        }
    }
}

/** 대장간 퀘스트(기획서 4.4). 경고등이 꺼지면 마친 것으로 보고 개근 보호권을 준다. */
@Composable
private fun QuestCard(state: DriveUiState) {
    Row(
        Modifier.fillMaxWidth().glass().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji("⚒️", 44)
        Column {
            Text("대장간 퀘스트 · " + state.quests.joinToString { it.label }, fontSize = 18.sp, color = Muted)
            Text(state.quests.first().title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Gold)
            Text("정비해서 경고등이 꺼지면 개근 보호권 1장", fontSize = 17.sp, color = Muted)
        }
    }
}

/** 역참에서 쉬어가기: 쉬어 갈 때가 된 뒤 차를 세우면 보너스까지 남은 시간을 보여 준다. */
@Composable
private fun RestCard(leftMin: Int?) {
    Row(
        Modifier.fillMaxWidth().glass().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji("🐎", 44)
        Column {
            Text("역참에서 쉬어가기", fontSize = 18.sp, color = Muted)
            Text(
                if (leftMin == null || leftMin > 0) "${leftMin ?: "-"}분 더 쉬면 휴식 보너스" else "충분히 쉬었습니다",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Gold,
            )
            Text("말을 갈아타고 다시 떠나면 +${haengcha.judge.RestRule().bonusXp}XP", fontSize = 17.sp, color = Muted)
        }
    }
}

/** 지난 주행에서 Vehicle·NaviHelper SDK로 판정한 것. */
private fun TripRecord.tags(): String = listOfNotNull(
    kmPerKwh?.let { String.format(Locale.ROOT, "전비 %.1f", it) + if (eco) " 검약" else "" },
    weather?.let { it.label + if (weatherCalm) " 차분" else "" },
    "야행".takeIf { night },
    "역참 휴식".takeIf { rested },
    stamp?.let { "${it.label} 도착" },
    "안전띠 안 맴".takeIf { belt == false },
).joinToString(" · ")

/** 아래 띠: 다음 테마까지 남은 경험치와 지난 주행 결과. */
@Composable
private fun BottomBar(p: ProgressSummary, lastTrip: TripRecord?, modifier: Modifier) {
    val next = JoseonTheme.entries.firstOrNull { it.unlockLevel > p.level }
    Row(
        modifier.glass().padding(horizontal = 32.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("다음 테마", fontSize = 18.sp, color = Muted)
            Text(
                if (next == null) "모든 조선 테마를 열었습니다" else "${next.label} · ${Levels.xpFor(next.unlockLevel) - p.totalXp}XP 남음",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("지난 주행", fontSize = 18.sp, color = Muted)
            if (lastTrip == null) {
                Text("아직 기록된 주행이 없습니다", fontSize = 22.sp)
            } else {
                Text("%.1fkm · +%dXP".format(Locale.ROOT, lastTrip.distanceKm, lastTrip.xp), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                // 감찰은 통과했을 때만 드러낸다. 실패는 알리지 않는다(기획서 4.6)
                if (lastTrip.mapae) Text("암행어사 출두 · 마패 하사", fontSize = 18.sp, color = Gold, fontWeight = FontWeight.Bold)
                lastTrip.tags().takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 18.sp, color = Muted) }
            }
        }
    }
}

/** 수집을 못 하는 상태일 때만 보인다. */
@Composable
private fun StatusLine(state: DriveUiState, onRequestPermission: () -> Unit) {
    when {
        !state.collecting -> Column(
            Modifier.fillMaxWidth().glass().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("차속 권한이 있어야 주행을 기록합니다.", fontSize = 20.sp)
            Button(onClick = onRequestPermission, modifier = Modifier.heightIn(min = 64.dp)) {
                Text("권한 허용", fontSize = 22.sp)
            }
        }
        !state.locating -> Column(
            Modifier.fillMaxWidth().glass().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("위치 권한을 허용하면 지역 테마와 어린이보호구역 안내가 켜집니다.", fontSize = 20.sp)
            Button(onClick = onRequestPermission, modifier = Modifier.heightIn(min = 64.dp)) {
                Text("권한 허용", fontSize = 22.sp)
            }
        }
        state.missingSignals.isNotEmpty() -> Text(
            "이 차량에서 읽지 못하는 신호: ${state.missingSignals.joinToString()}",
            fontSize = 18.sp,
            modifier = Modifier.fillMaxWidth().glass().padding(16.dp),
        )
    }
}
