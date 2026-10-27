package haengcha.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haengcha.app.R
import haengcha.drive.DriveUiState
import haengcha.judge.Buddy
import haengcha.judge.ProgressSummary
import haengcha.judge.Rarity
import haengcha.judge.Region
import haengcha.judge.RegularStatus
import haengcha.judge.Title
import haengcha.judge.goal
import java.util.Locale

/*
 * 지역 도감·칭호·동반자 화면 조각(기획서 4.3, 4.5, 4.8). 패널은 한 칸(portrait)부터 넓은 칸까지 쓰므로
 * 타일은 폭에 맞춰 열 수를 정하는 격자에 담는다.
 */

/** 지역 상징. 장면이 있는 지역은 그림, 도감 지역은 이모지. */
@Composable
internal fun RegionEmblem(region: Region, size: Dp, modifier: Modifier = Modifier) {
    when (region) {
        Region.ULSAN -> Image(painterResource(R.drawable.region_whale), null, modifier.size(size))
        Region.HOENGSEONG -> Image(painterResource(R.drawable.region_hanwoo), null, modifier.size(size))
        else -> Box(modifier.size(size), contentAlignment = Alignment.Center) { Emoji(region.emblem, (size.value * 0.6f).toInt()) }
    }
}

/** 동반자 그림. 거북이는 그려 둔 그림, 나머지는 이모지. */
@Composable
internal fun BuddyPicture(buddy: Buddy, size: Dp, modifier: Modifier = Modifier) {
    if (buddy == Buddy.TURTLE) {
        Image(painterResource(R.drawable.companion_turtle), buddy.label, modifier.size(size))
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) { Emoji(buddy.emoji, (size.value * 0.6f).toInt()) }
    }
}

/** 폭에 맞춰 열 수를 정하는 격자. 한 줄의 타일은 높이를 맞춘다. */
@Composable
internal fun <T> TileGrid(items: List<T>, minTile: Dp, maxColumns: Int = 4, tile: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = (maxWidth / minTile).toInt().coerceIn(1, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(cols).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 희귀도 색. 일반은 테두리가 없다. */
internal val Rarity.color: Color
    get() = when (this) {
        Rarity.COMMON -> Muted
        Rarity.RARE -> Color(0xFF7FB8FF)
        Rarity.EPIC -> Color(0xFFC99BFF)
        Rarity.LEGEND -> Gold
    }

/** [on]은 고른 것(금색 바탕), [rarity]는 얻은 희귀·영웅·전설의 테두리 색. */
internal fun Modifier.collectTile(on: Boolean = false, rarity: Rarity? = null): Modifier {
    val edge = when {
        on -> Gold
        rarity != null && rarity != Rarity.COMMON -> rarity.color
        else -> null
    }
    return this
        .clip(RoundedCornerShape(16.dp))
        .background(if (on) Color(0x33F2B84B) else if (rarity == Rarity.LEGEND) Color(0x26F2B84B) else Color(0x1FF5F1E8))
        .then(if (edge != null) Modifier.border(2.dp, edge, RoundedCornerShape(16.dp)) else Modifier)
        .padding(14.dp)
}

/** 희귀 이상이면 작은 희귀도 표시. */
@Composable
private fun RarityTag(r: Rarity) {
    if (r == Rarity.COMMON) return
    Text(r.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = r.color)
}

/** 지역 도감: 다녀간 지역 수와 지역마다 상징·단골 등급. */
@Composable
internal fun ColumnScope.RegionSection(p: ProgressSummary) {
    Text("지역 도감 ${p.regulars.size}/${Region.entries.size}", fontSize = 20.sp, color = Muted, fontWeight = FontWeight.SemiBold)
    TileGrid(Region.entries.sortedByDescending { r -> p.regulars.any { it.region == r } }, minTile = 260.dp) { r, m ->
        RegionTile(r, p.regulars.firstOrNull { it.region == r }, m)
    }
}

@Composable
private fun RegionTile(r: Region, status: RegularStatus?, modifier: Modifier) {
    Row(modifier.collectTile(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RegionEmblem(r, 72.dp, Modifier.alpha(if (status != null) 1f else 0.3f))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${r.label} ${r.theme}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(status?.let { "${it.gradeLabel} · ${it.visitDays}일" } ?: "아직 못 가 본 곳", fontSize = 17.sp, color = if (status != null) Gold else Muted)
            // 단골 등급 다섯 칸
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                r.grades.indices.forEach { i ->
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (status != null && i <= status.grade) Gold else Line))
                }
            }
        }
    }
}

/**
 * 칭호: 얻은 것부터, 아직 못 얻은 것은 조건을 보여 준다. 얻은 칭호를 누르면 정차 화면에 다는 대표 칭호([chosen])가 된다
 * ([onChoose]가 null이면 고를 수 없음, 주행 중).
 */
@Composable
internal fun ColumnScope.TitleSection(p: ProgressSummary, chosen: Title?, onChoose: ((Title) -> Unit)?) {
    Text(
        "칭호 ${p.titles.size}/${Title.entries.size}" + (chosen?.let { " · 대표 칭호 ${it.emoji} ${it.label}" } ?: ""),
        fontSize = 20.sp,
        color = Muted,
        fontWeight = FontWeight.SemiBold,
    )
    if (p.titles.size > 1 && onChoose != null) Text("얻은 칭호를 누르면 대표 칭호로 답니다", fontSize = 17.sp, color = Muted)
    // 못 얻은 것은 쉬운 것부터, 숨은 칭호는 맨 뒤에 이름과 조건을 감춘다
    val locked = Title.entries.filter { it !in p.titles }.sortedWith(compareBy({ it.hidden }, { it.tier }))
    TileGrid(p.titles + locked, minTile = 230.dp) { t, m ->
        val got = t in p.titles
        val masked = t.hidden && !got
        val tile = m.clip(RoundedCornerShape(16.dp))
            .then(if (got && onChoose != null) Modifier.clickable { onChoose(t) } else Modifier)
            .collectTile(on = t == chosen, rarity = t.rarity.takeIf { got })
        Row(tile, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Emoji(if (masked) "❔" else t.emoji, 34, Modifier.alpha(if (got) 1f else 0.3f))
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RarityTag(if (masked) Rarity.RARE else t.rarity)
                    if (t == chosen) Text("대표", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Gold)
                }
                Text(if (masked) "숨은 칭호" else t.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (got) Cream else Muted)
                Text(if (masked) "어떤 주행에서 열릴까요?" else t.desc, fontSize = 15.sp, color = if (got) Gold else Muted)
            }
        }
    }
}

/** 주행 보상 동반자(기획서 4.8): 합류한 동반자 가운데 하나를 골라 정차 화면에 데리고 다닌다. */
@Composable
internal fun ColumnScope.BuddySection(state: DriveUiState, onChoose: (Buddy) -> Unit) {
    val p = state.progress
    val current = state.buddy
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current != null) BuddyPicture(current, 150.dp) else Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) { Emoji("❔", 90) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current?.let { "함께하는 동반자 · ${it.label}" } ?: "아직 합류한 동반자가 없습니다", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(current?.cheer ?: "달리다 보면 하나씩 합류합니다. 이달 주행 10번이면 거북이가 먼저 옵니다.", fontSize = 20.sp, color = Gold)
            Text("동반자 ${p.buddies.size}/${Buddy.entries.size} · 합류한 동반자를 누르면 데리고 다닙니다", fontSize = 17.sp, color = Muted)
        }
    }
    TileGrid(Buddy.entries, minTile = 220.dp) { b, m ->
        val g = b.goal(p)
        val joined = b in p.buddies
        Column(
            m.clip(RoundedCornerShape(16.dp)).clickable(enabled = joined && !state.inTrip) { onChoose(b) }
                .collectTile(on = b == current, rarity = b.rarity.takeIf { joined })
                .heightIn(min = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BuddyPicture(b, 72.dp, Modifier.alpha(if (joined) 1f else if (b.rarity == Rarity.LEGEND) 0.15f else 0.3f))
            RarityTag(b.rarity)
            Text(b.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    b == current -> "함께하는 중"
                    joined -> "합류함"
                    else -> b.condition
                },
                fontSize = 16.sp,
                color = if (joined) Gold else Muted,
                textAlign = TextAlign.Center,
            )
            if (!joined) {
                LinearProgressIndicator(progress = { g.ratio }, Modifier.fillMaxWidth().height(8.dp), color = Gold, trackColor = Line)
                Text(goalText(b, g.have, g.need), fontSize = 14.sp, color = Muted)
            }
        }
    }
    Text("주행 보상 동반자는 판매하지 않습니다. 거북이는 달이 바뀌면 다시 모읍니다.", fontSize = 17.sp, color = Muted)
}

private fun goalText(b: Buddy, have: Double, need: Double) = when (b) {
    Buddy.SQUIRREL -> String.format(Locale.ROOT, "%.1f/%.0f섬", have, need)
    Buddy.DOKKAEBI, Buddy.GUMIHO -> String.format(Locale.ROOT, "%.0f/%.0fkm", have, need)
    Buddy.ROOSTER, Buddy.DRAGON -> "${have.toInt()}/${need.toInt()}일"
    else -> "${have.toInt()}/${need.toInt()}"
}

/** 정차 화면에서 동반자가 하는 말. */
internal val Buddy.cheer: String
    get() = when (this) {
        Buddy.TURTLE -> "천천히 가도 끝까지 갑니다"
        Buddy.ROOSTER -> "오늘도 개근 도장 찍으러 가요!"
        Buddy.OWL -> "밤길은 제가 지켜볼게요"
        Buddy.SQUIRREL -> "아낀 기운은 곳간에 차곡차곡"
        Buddy.MAGPIE -> "다음 명소는 어디로 갈까요?"
        Buddy.SAPSAL -> "아이들 곁에서는 천천히!"
        Buddy.TIGER -> "암행어사 호위는 제게 맡기세요"
        Buddy.DOKKAEBI -> "새재 너머 동래까지 가 봅시다"
        Buddy.DRAGON -> "백 일을 지켜 온 길, 구름 위로 모십니다"
        Buddy.PHOENIX -> "팔도 하늘을 함께 날았지요"
        Buddy.GUMIHO -> "만 리를 함께 왔으니 아홉 꼬리로 지켜 드리지요"
    }

/**
 * 정차 화면 오른쪽 아래에서 둥실거리는 동반자와 말풍선. 주행 중에는 그리지 않는다.
 * 움직임은 위아래로 천천히 오가는 것뿐이다(정차 중이라 영상 금지 규제와 무관).
 */
@Composable
internal fun BuddyFloat(buddy: Buddy, modifier: Modifier = Modifier) {
    val bob = rememberInfiniteTransition(label = "buddy")
    val dy by bob.animateFloat(0f, -10f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buddy.cheer,
            fontSize = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.glass().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        BuddyPicture(buddy, 96.dp, Modifier.graphicsLayer { translationY = dy * density })
    }
}
