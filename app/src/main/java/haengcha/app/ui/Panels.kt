package haengcha.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haengcha.drive.Celebration
import haengcha.drive.DriveUiState
import haengcha.app.R
import haengcha.judge.Buddy
import haengcha.judge.Rarity
import haengcha.judge.Title
import haengcha.judge.josa
import haengcha.judge.GuardianRule
import haengcha.judge.ArrivalRule
import haengcha.judge.EcoRule
import haengcha.judge.InspectionRule
import haengcha.judge.RestRule
import haengcha.judge.Weather
import haengcha.judge.WeatherRule
import haengcha.judge.journey
import haengcha.judge.JoseonTheme
import haengcha.judge.ProgressSummary
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 정차 화면에서 여는 패널. 정차 중에만 연다(주행 규제 중에는 화면 자체가 한 줄로 바뀐다). */
enum class PanelKind { DOGAM, JOURNEY, COMPANION, SHOP, PROFILE }

/** 화면에서 컨트롤러로 보내는 동작. */
class ScreenActions(
    val requestPermission: () -> Unit,
    val dismissCelebration: () -> Unit,
    val switchProfile: (String) -> Unit,
    val addProfile: () -> Unit,
    val buy: (String) -> Unit,
    val chooseBuddy: (Buddy) -> Unit,
    val chooseTitle: (Title) -> Unit = {},
    /** 화면 배치: 내비게이션 가운데, 행차 오른쪽. */
    val naviCenter: () -> Boolean = { true },
    val setNaviCenter: (Boolean) -> Unit = {},
)

/** 콜라보 동반자(가상 캐릭터). 실제 IP는 라이선스 계약 이후 사업화 단계에서 다룬다(기획서 4.8, 12.1). */
private data class CollabItem(val id: String, val name: String, val desc: String, val price: String, @DrawableRes val icon: Int)

private val COLLAB = listOf(
    CollabItem("meeh", "메에 · 대관령 양", "양 떼 목장 테마로 지역 진입을 응원합니다", "2,900원 · 단품", R.drawable.collab_meeh),
    CollabItem("beot", "벚꽃 요정", "봄 시즌 패스 · 4월 진해 벚꽃 연출", "4,900원 · 시즌 패스", R.drawable.collab_beot),
    CollabItem("danpung", "단풍 요정", "가을 시즌 패스 · 10월 설악 단풍 연출", "4,900원 · 시즌 패스", R.drawable.collab_danpung),
)

@Composable
internal fun MenuChips(state: DriveUiState, onOpen: (PanelKind) -> Unit, modifier: Modifier = Modifier) {
    val profile = state.profiles.firstOrNull { it.id == state.activeProfile }?.name ?: "운전자"
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Chip("도감", onClick = { onOpen(PanelKind.DOGAM) })
        Chip("원정", onClick = { onOpen(PanelKind.JOURNEY) })
        Chip("동반자", buddy = state.buddy) { onOpen(PanelKind.COMPANION) }
        Chip("상점", onClick = { onOpen(PanelKind.SHOP) })
        Chip(profile, onClick = { onOpen(PanelKind.PROFILE) })
    }
}

@Composable
private fun Chip(text: String, buddy: Buddy? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp)).glass().clickable(onClick = onClick).padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buddy?.let { BuddyPicture(it, 40.dp) }
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 어둡게 깐 화면 위의 큰 패널. 뒤를 눌러도 닫히지 않게 터치를 막는다. 뒤로 가기 키(차량 물리 버튼 포함)로 닫는다. */
@Composable
internal fun PanelOverlay(title: String, subtitle: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(MutableInteractionSource(), null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth(0.94f).padding(vertical = 32.dp).glass().padding(32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    subtitle?.let { Text(it, fontSize = 20.sp, color = Muted) }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 64.dp)) { Text("닫기", fontSize = 22.sp) }
            }
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, fontSize = 20.sp, color = Muted, fontWeight = FontWeight.SemiBold)

private fun Modifier.tile() = this.clip(RoundedCornerShape(16.dp)).background(Color(0x1FF5F1E8)).padding(16.dp)

/** 도감. 충전 중에는 '쉬어가는 행차'로 열린다(기획서 부록 B). */
@Composable
internal fun ColumnScope.DogamContent(p: ProgressSummary, title: Title? = p.titles.firstOrNull(), onChooseTitle: ((Title) -> Unit)? = null) {
    SectionLabel("오늘")
    Text(
        String.format(Locale.ROOT, "주행 %d회 · %.1fkm · +%dXP · 안전주행 개근 %d일", p.tripsToday, p.kmToday, p.todayXp, p.streak),
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
    )
    SectionLabel("탈것 도감")
    TileGrid(JoseonTheme.entries, minTile = 170.dp, maxColumns = 5) { t, m ->
            val open = t in p.themes
            Column(m.tile(), horizontalAlignment = Alignment.CenterHorizontally) {
                assetBitmap("icons/icon_${t.name.lowercase()}.webp")?.let {
                    Image(it, t.label, Modifier.size(120.dp).alpha(if (open) 1f else 0.25f))
                }
                Text(t.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (open) "열림" else "Lv.${t.unlockLevel}에 열림", fontSize = 18.sp, color = if (open) Gold else Muted)
            }
    }
    SectionLabel("모은 것")
    TileGrid(listOf(0, 1, 2), minTile = 240.dp) { i, m ->
        when (i) {
            0 -> StatTile(R.drawable.icon_mapae, "마패", "${p.mapaeCount}개", m)
            1 -> StatTile(R.drawable.icon_guardian, "아이 지킴이", "${p.guardianCount}회", m)
            else -> StatTile(R.drawable.companion_turtle, "동반자", "${p.buddies.size}/${Buddy.entries.size}", m)
        }
    }
    TitleSection(p, title, onChooseTitle)
    RegionSection(p)
}

@Composable
private fun StatTile(@DrawableRes icon: Int, label: String, value: String, modifier: Modifier) {
    Row(modifier.tile(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.size(64.dp))
        Column {
            Text(label, fontSize = 18.sp, color = Muted)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 주행 보상 동반자와 보유한 콜라보 동반자(기획서 4.8). */
@Composable
internal fun ColumnScope.CompanionContent(state: DriveUiState, onChoose: (Buddy) -> Unit) {
    BuddySection(state, onChoose)
    val owned = COLLAB.filter { it.id in state.owned }
    if (owned.isNotEmpty()) {
        SectionLabel("콜라보 동반자")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            owned.forEach { item ->
                Row(Modifier.tile(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(item.icon), null, Modifier.size(56.dp))
                    Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("어린이보호구역에서는 콜라보 동반자를 쓰지 않습니다.", fontSize = 18.sp, color = Muted)
    }
}

/** 콜라보 동반자 상점(시연용 목업). 확정 구매만 있고 결제는 하지 않는다. */
@Composable
internal fun ColumnScope.ShopContent(state: DriveUiState, onBuy: (String) -> Unit) {
    var confirm by remember { mutableStateOf<CollabItem?>(null) }
    Text("가상 캐릭터로 구매 흐름만 보여 주는 시연 화면입니다. 실제 결제는 이루어지지 않습니다.", fontSize = 20.sp, color = Gold)
    Text("무엇을 사는지 보고 고르는 확정 구매이며 무작위 뽑기는 없습니다. 결제는 차량 소유자 계정에서만 합니다.", fontSize = 18.sp, color = Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        COLLAB.forEach { item ->
            val owned = item.id in state.owned
            Column(Modifier.weight(1f).tile(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(item.icon), item.name, Modifier.size(120.dp))
                Text(item.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(item.desc, fontSize = 17.sp, color = Muted, textAlign = TextAlign.Center)
                Text(item.price, fontSize = 20.sp, color = Gold, fontWeight = FontWeight.SemiBold)
                if (owned) {
                    Text("보유 중", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.heightIn(min = 64.dp).padding(top = 16.dp))
                } else {
                    Button(onClick = { confirm = item }, modifier = Modifier.heightIn(min = 64.dp)) { Text("구매", fontSize = 22.sp) }
                }
            }
        }
    }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("구매 확인", fontSize = 26.sp) },
            text = { Text("${item.name}(${item.price})을 구매할까요?\n시연용 화면이라 실제 결제는 진행되지 않습니다.", fontSize = 20.sp) },
            confirmButton = { TextButton(onClick = { onBuy(item.id); confirm = null }) { Text("구매", fontSize = 22.sp) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소", fontSize = 22.sp) } },
        )
    }
}

/** 운전자 프로필 전환(기획서 12.1 목업). 운전자마다 기록을 따로 쌓는다. */
@Composable
internal fun ColumnScope.ProfileContent(state: DriveUiState, actions: ScreenActions) {
    Text("운전자마다 레벨·도감·주행 기록을 따로 쌓습니다.", fontSize = 20.sp, color = Muted)
    state.profiles.forEach { profile ->
        val active = profile.id == state.activeProfile
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).tile().clickable(enabled = !active && !state.inTrip) { actions.switchProfile(profile.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(profile.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active) Text("지금 운전자", fontSize = 20.sp, color = Gold)
        }
    }
    if (state.inTrip) Text("주행 중에는 운전자를 바꿀 수 없습니다.", fontSize = 18.sp, color = Gold)
    OutlinedButton(onClick = actions.addProfile, modifier = Modifier.heightIn(min = 64.dp)) { Text("운전자 추가", fontSize = 22.sp) }
    SectionLabel("화면 배치")
    var naviCenter by remember { mutableStateOf(actions.naviCenter()) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).tile().clickable { naviCenter = !naviCenter; actions.setNaviCenter(naviCenter) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("내비게이션 가운데 · 행차 오른쪽", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("행차가 가운데 칸에서 열리면 내비게이션을 가운데로 불러 길 안내를 운전자 가까이 둡니다", fontSize = 17.sp, color = Muted)
        }
        Switch(checked = naviCenter, onCheckedChange = { naviCenter = it; actions.setNaviCenter(it) })
    }
}

/**
 * 정차 후 축하 연출(테마 해금, 지역 첫 방문, 단골 등급, 마패, 아이 지킴이, 동반자 합류).
 * 주행 중에는 보이지 않고 차를 세운 뒤 한 장씩 보여 준다. 뒤에서 빛줄기가 천천히 돈다.
 */
@Composable
internal fun CelebrationOverlay(c: Celebration, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val (title, subtitle) = when (c) {
        is Celebration.ThemeUnlocked -> "${c.theme.label} 테마가 열렸습니다" to "Lv.${c.theme.unlockLevel} 달성 · 정차 화면의 탈것이 바뀌었습니다"
        is Celebration.RegionJoined -> c.status.region.let { r ->
            if (r.graded) "${r.label} ${r.theme} 테마가 켜졌습니다" to "${c.status.gradeLabel} 단골이 되었습니다"
            else "지역 도감 · ${r.label} ${r.theme}" to "${r.label}의 ${c.status.gradeLabel}${c.status.gradeLabel.josa("이", "가")} 되었습니다"
        }
        is Celebration.GradeUp -> "단골 등급이 올랐습니다" to "${c.status.region.label} · ${c.status.gradeLabel}"
        Celebration.Mapae -> "암행어사 출두요!" to "아까 10분, 차분히 잘 달리셨습니다. 마패를 하사합니다 · +${InspectionRule().mapaeXp}XP"
        Celebration.Guardian -> "아이 지킴이" to "어린이보호구역을 천천히 지나셨습니다 · +${GuardianRule().bonusXp}XP"
        is Celebration.BuddyJoined -> (if (c.buddy.rarity == Rarity.LEGEND) "전설의 동반자 · ${c.buddy.label}" else "${c.buddy.label}${c.buddy.label.josa("이", "가")} 합류했습니다") to
            "${c.buddy.condition} · \"${c.buddy.cheer}\""
        is Celebration.TitleEarned -> c.title.let { t ->
            val kind = when {
                t.hidden -> "숨은 칭호 발견"
                t.rarity == Rarity.COMMON -> "새 칭호"
                else -> "${t.rarity.label} 칭호"
            }
            "$kind · ${t.label}" to t.desc
        }
        is Celebration.Eco -> "검약 행차" to String.format(
            Locale.ROOT, "이번 주행 전비 %.1fkm/kWh · 곳간에 쌀 %.1f섬 · +%dXP", c.kmPerKwh, c.rice, EcoRule().bonusXp,
        )
        is Celebration.WeatherCalm -> "${c.weather.label} 차분 운전" to "와이퍼를 켜고 급가속·급감속 없이 달리셨습니다 · +${WeatherRule().bonusXp}XP"
        Celebration.Rested -> "역참에서 쉬어 가셨습니다" to "말을 갈아타고 다시 길을 나섭니다 · +${RestRule().bonusXp}XP"
        is Celebration.StationReached -> "옛길 원정 · ${c.station.label}" to "${c.station.kind}에 스탬프를 찍었습니다" +
            (journey(c.station.km.toDouble()).next?.let { " · 다음은 ${it.label}" } ?: " · 한 바퀴를 마쳤습니다")
        is Celebration.Stamp -> "${c.landmark.label} 도착" to "${c.landmark.region.label} 명소 스탬프를 찍었습니다 · 첫 도착 +${ArrivalRule().bonusXp}XP"
        is Celebration.QuestDone -> "대장간 퀘스트 완료" to "${c.quest.label} 정비를 마쳤습니다 · 개근 보호권 1장"
        Celebration.ShieldUsed -> "개근 보호권 사용" to "끊길 뻔한 안전주행 개근 기록을 이었습니다"
    }
    val spin = rememberInfiniteTransition(label = "rays")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "angle")
    var shown by remember(c) { mutableStateOf(false) }
    LaunchedEffect(c) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, spring(dampingRatio = 0.55f), label = "scale")

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(MutableInteractionSource(), null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(Modifier.size(360.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
                    val r = size.minDimension / 2
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.45f), Color.Transparent), center, r), r, center)
                    val rays = Brush.radialGradient(listOf(Gold.copy(alpha = 0.5f), Color.Transparent), center, r)
                    repeat(12) { i ->
                        val a = i * PI / 6
                        val w = PI / 28
                        drawPath(
                            Path().apply {
                                moveTo(center.x, center.y)
                                lineTo(center.x + r * cos(a - w).toFloat(), center.y + r * sin(a - w).toFloat())
                                lineTo(center.x + r * cos(a + w).toFloat(), center.y + r * sin(a + w).toFloat())
                                close()
                            },
                            rays,
                        )
                    }
                }
                CelebrationIcon(c, Modifier.size(240.dp))
            }
            Text(title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Gold, textAlign = TextAlign.Center)
            Text(subtitle, fontSize = 24.sp, color = Cream, textAlign = TextAlign.Center)
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 72.dp).widthIn(min = 200.dp)) { Text("확인", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun CelebrationIcon(c: Celebration, modifier: Modifier) {
    when (c) {
        is Celebration.ThemeUnlocked -> assetBitmap("icons/icon_${c.theme.name.lowercase()}.webp")?.let { Image(it, c.theme.label, modifier) }
        is Celebration.RegionJoined -> Box(modifier, contentAlignment = Alignment.Center) { RegionEmblem(c.status.region, 200.dp) }
        is Celebration.GradeUp -> Box(modifier, contentAlignment = Alignment.Center) { RegionEmblem(c.status.region, 200.dp) }
        Celebration.Mapae -> Image(painterResource(R.drawable.icon_mapae), null, modifier)
        Celebration.Guardian -> Image(painterResource(R.drawable.icon_guardian), null, modifier)
        is Celebration.BuddyJoined -> Box(modifier, contentAlignment = Alignment.Center) { BuddyPicture(c.buddy, 220.dp) }
        is Celebration.TitleEarned -> CelebrationEmoji(c.title.emoji, modifier)
        is Celebration.Eco -> CelebrationEmoji("🌾", modifier)
        is Celebration.WeatherCalm -> CelebrationEmoji(if (c.weather == Weather.SNOW) "❄️" else "🌧️", modifier)
        Celebration.Rested -> CelebrationEmoji("🐎", modifier)
        is Celebration.StationReached -> CelebrationEmoji("📜", modifier)
        is Celebration.Stamp -> CelebrationEmoji("🏮", modifier)
        is Celebration.QuestDone -> CelebrationEmoji("⚒️", modifier)
        Celebration.ShieldUsed -> CelebrationEmoji("🛡️", modifier)
    }
}

@Composable
private fun CelebrationEmoji(symbol: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Emoji(symbol, 150) }
}
