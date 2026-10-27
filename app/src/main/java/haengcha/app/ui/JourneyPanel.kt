package haengcha.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haengcha.drive.DriveUiState
import haengcha.judge.EcoRule
import haengcha.judge.JourneyStatus
import haengcha.judge.Landmark
import haengcha.judge.Region
import haengcha.judge.Road
import java.util.Locale
import kotlin.math.sin

/** 큰 그림 글자(이모지). 시스템 컬러 이모지 글꼴로 그린다. */
@Composable
internal fun Emoji(symbol: String, size: Int, modifier: Modifier = Modifier) {
    Text(symbol, fontSize = size.sp, modifier = modifier)
}

/**
 * 원정 패널: 옛길 원정 세 길(영남·삼남·관동대로)의 진행, 지금 길의 지도와 지점 스탬프, 명소 도착 스탬프, 곳간과 행차 기록.
 * 정차 중에만 연다.
 */
@Composable
internal fun ColumnScope.JourneyContent(state: DriveUiState) {
    val p = state.progress
    val j = p.journey
    Text(
        String.format(Locale.ROOT, "%s · 누적 %.1fkm · 지점 스탬프 %d곳", if (j.lap > 0) "${j.lap + 1}바퀴째" else "첫 원정", j.totalKm, j.stamps),
        fontSize = 22.sp,
        color = Muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Road.entries.forEach { r -> RoadCard(r, j, Modifier.weight(1f)) }
    }
    JourneyMap(j, Modifier.fillMaxWidth().height(300.dp))
    Text(
        String.format(Locale.ROOT, "다음 %s(%s)까지 %.1fkm · 출퇴근만 해도 조금씩 나아갑니다", j.next.label, j.next.kind, j.kmToNext),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )

    Text("명소 스탬프 ${p.stamps.size}/${Landmark.entries.size}", fontSize = 20.sp, color = Muted, fontWeight = FontWeight.SemiBold)
    Text("내비게이션 목적지로 명소에 도착하면 스탬프를 찍습니다. 처음 찍으면 보너스 경험치를, 한 지역을 모두 돌면 '명소 완주' 칭호를 드립니다.", fontSize = 18.sp, color = Muted)
    // 찍은 스탬프가 있는 지역부터, 지역 안에서는 정해 둔 순서대로
    val order = Region.entries.sortedByDescending { r -> p.stamps.count { it.region == r } }
    TileGrid(Landmark.entries.sortedBy { order.indexOf(it.region) }, minTile = 200.dp, maxColumns = 5) { l, m ->
        val got = l in p.stamps
        Column(m.clip(RoundedCornerShape(16.dp)).background(Color(0x1FF5F1E8)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Emoji(if (got) "🏮" else l.region.emblem, 36, Modifier.alpha(if (got) 1f else 0.3f))
            // 좁은 칸에서는 낱말 단위로 줄을 바꾼다("장생포 / 고래문화마을")
            Text(l.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = TextStyle(lineBreak = LineBreak.Heading))
            Text(if (got) "스탬프 찍음" else "${l.region.label} 명소", fontSize = 15.sp, color = if (got) Gold else Muted)
        }
    }

    Text("곳간과 행차 기록", fontSize = 20.sp, color = Muted, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Record("🌾", "곳간", String.format(Locale.ROOT, "쌀 %.1f섬", p.rice), "검약 행차 ${p.ecoTrips}회", Modifier.weight(1f))
        Record("🛡️", "개근 보호권", "${p.shields}장", "대장간 퀘스트 보상", Modifier.weight(1f))
        Record("🐎", "역참 휴식", "${p.restCount}회", "두 시간마다 쉬어 가기", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Record("🏮", "야행", "${p.nightTrips}회", "전조등 켜고 달린 주행", Modifier.weight(1f))
        Record("🌧️", "빗길·눈길 차분", "${p.weatherCalmTrips}회", "와이퍼 켜고 급조작 없이", Modifier.weight(1f))
        Record("📜", "옛길 지점", "${j.stamps}곳", "역참·주막·관문·나루", Modifier.weight(1f))
    }
    Text(
        String.format(Locale.ROOT, "곳간: 전비 %.0fkm/kWh 기준보다 아낀 전력 1kWh를 쌀 한 섬으로 셉니다. 검약 행차 목표 전비는 %.0fkm/kWh입니다.", EcoRule().baselineKmPerKwh, EcoRule().targetKmPerKwh),
        fontSize = 17.sp,
        color = Muted,
    )
    if (state.missingSdk.isNotEmpty()) {
        Text(
            "이 차량에서 읽지 못하는 값: ${state.missingSdk.joinToString()}" +
                if ("전비" in state.missingSdk) " · 전비는 배터리 잔량과 적산거리로 계산합니다" else "",
            fontSize = 17.sp,
            color = Muted,
        )
    }
}

/** 옛길 한 길의 카드: 마친 길은 금색 완주, 지금 길은 진행 막대, 아직 안 간 길은 앞 길을 마치면 열린다고 알린다. */
@Composable
private fun RoadCard(r: Road, j: JourneyStatus, modifier: Modifier) {
    val now = r == j.road
    val done = j.done(r) && !now
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (now) Color(0x33F2B84B) else Color(0x1FF5F1E8))
            .then(if (now) Modifier.border(2.dp, Gold, RoundedCornerShape(16.dp)) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Emoji(if (done) "🏆" else if (now) "🐎" else "🔒", 26, Modifier.alpha(if (done || now) 1f else 0.5f))
            Text(r.label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (done || now) Cream else Muted)
        }
        Text("${r.stations.first().label.removePrefix("한양 ")} → ${r.destination.label} · ${r.km}km", fontSize = 15.sp, color = Muted)
        when {
            now -> {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Line)) {
                    Box(Modifier.fillMaxWidth(j.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Gold))
                }
                Text(String.format(Locale.ROOT, "%.0f%% · %s 지남", j.progress * 100, j.reached.label), fontSize = 15.sp, color = Gold)
            }
            done -> Text("완주 · 지점 ${r.stations.size - 1}곳", fontSize = 15.sp, color = Gold)
            else -> Text("${r.entriesBefore()}를 마치면 열립니다", fontSize = 15.sp, color = Muted)
        }
    }
}

private fun Road.entriesBefore() = Road.entries[(ordinal + Road.entries.size - 1) % Road.entries.size].label

@Composable
private fun Record(icon: String, label: String, value: String, note: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x1FF5F1E8)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Emoji(icon, 36)
        Column {
            Text(label, fontSize = 17.sp, color = Muted)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(note, fontSize = 15.sp, color = Muted)
        }
    }
}

/** 지금 걷는 길을 출발점(왼쪽)에서 도착점(오른쪽)으로 흐르는 길로 그리고 지난 구간을 금색으로 칠한다. 길마다 굽이가 다르다. */
@Composable
private fun JourneyMap(j: JourneyStatus, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x332A1C10))) {
        Canvas(Modifier.fillMaxWidth().height(300.dp)) {
            val padX = size.width * 0.05f
            fun at(km: Double): Offset {
                val u = (km / j.road.km).toFloat()
                val x = padX + u * (size.width - 2 * padX)
                val y = when (j.road) {
                    Road.YEONGNAM -> size.height * (0.28f + 0.42f * u + 0.12f * sin(u * 9.5f))
                    Road.SAMNAM -> size.height * (0.22f + 0.5f * u + 0.1f * sin(u * 7f + 1f))
                    Road.GWANDONG -> size.height * (0.62f - 0.2f * u + 0.14f * sin(u * 8f))
                }
                return Offset(x, y)
            }
            val path = Path().apply {
                moveTo(at(0.0).x, at(0.0).y)
                for (k in 1..j.road.km) at(k.toDouble()).let { lineTo(it.x, it.y) }
            }
            drawPath(path, Line, style = Stroke(10f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
            val done = Path().apply {
                moveTo(at(0.0).x, at(0.0).y)
                var k = 0.0
                while (k < j.roadKm) {
                    k = minOf(k + 1, j.roadKm)
                    at(k).let { lineTo(it.x, it.y) }
                }
            }
            drawPath(done, Gold, style = Stroke(12f, cap = StrokeCap.Round))
            j.road.stations.forEachIndexed { i, s ->
                val o = at(s.km.toDouble())
                val reached = s.km <= j.roadKm
                drawCircle(if (reached) Gold else Muted, radius = if (s.kind == "출발" || s.kind == "도착") 14f else 10f, center = o)
                drawCircle(Ink, radius = 4f, center = o)
                val label = measurer.measure(s.label, TextStyle(fontSize = 15.sp, color = if (reached) Cream else Muted, fontWeight = if (reached) FontWeight.Bold else FontWeight.Normal))
                val above = i % 2 == 0
                drawText(label, topLeft = Offset(o.x - label.size.width / 2f, if (above) o.y - 26f - label.size.height else o.y + 22f))
            }
            // 지금 위치
            val here = at(j.roadKm)
            drawCircle(Color(0x66F2B84B), radius = 30f, center = here)
            drawCircle(Gold, radius = 16f, center = here)
            drawCircle(Ink, radius = 7f, center = here)
        }
    }
}
