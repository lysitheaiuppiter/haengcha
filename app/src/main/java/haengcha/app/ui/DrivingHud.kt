package haengcha.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import haengcha.app.DriveUiState
import haengcha.app.LiveView
import haengcha.judge.LiveTrip
import haengcha.judge.SegmentMark
import haengcha.judge.Station
import haengcha.judge.Weather
import java.util.Locale

private val Calm = Color(0xFF7FD1C2)
private val Harsh = Color(0xFFB0675A)

/**
 * 주행 규제 중 화면(기획서 9.1). 조작할 것 없이 한눈에 보는 계기판이다.
 * 이번 행차(거리·이번 주행 경험치·1분 구간 게이지·안전 구간 연속), 옛길 원정 진행, 야행·날씨·정비·안내 대기 표시, 알림 띠.
 * 값은 1초에 한 번만 바뀌고, 영상 금지 규제 중에는 초롱 행렬·비·눈도 1초에 한 칸씩만 움직인다(초당 1프레임 이하).
 */
@Composable
internal fun DrivingScreen(state: DriveUiState) {
    val p = state.progress
    val live = state.live
    Box(Modifier.fillMaxSize()) {
        if (state.night) LanternProcession(paused = state.noVideo)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
            val wide = maxWidth > maxHeight && maxWidth >= 900.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TopLine(state)
                live?.banner?.let { Banner(it, Modifier.align(Alignment.CenterHorizontally)) }
                Spacer(Modifier.weight(1f))
                TripPanel(live?.trip ?: LiveTrip(), state, if (wide) Modifier.widthIn(max = 620.dp) else Modifier.fillMaxWidth())
                live?.let { JourneyStrip(it, p.themes.last().name.lowercase(), Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun TopLine(state: DriveUiState) {
    val p = state.progress
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            buildString {
                append("${p.themes.last().label} 행차")
                state.region?.let { append(" · ${it.label} ${it.theme}") }
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.glass().padding(horizontal = 22.dp, vertical = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        if (state.night) StatusChip("🏮", "야행")
        state.weather?.let { StatusChip(if (it == Weather.SNOW) "❄️" else "🌧️", it.label) }
        if (state.quests.isNotEmpty()) StatusChip("⚒️", "정비")
        if (state.restDue) StatusChip("🐎", "쉬어 갈 때")
        if (state.voiceHeld) StatusChip("🔇", "안내 대기")
    }
}

@Composable
private fun StatusChip(icon: String, label: String) {
    Row(
        Modifier.glass().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Emoji(icon, 26)
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Gold).padding(horizontal = 36.dp, vertical = 14.dp),
    )
}

/** 이번 행차: 거리와 경험치, 1분 구간 게이지, 안전 구간 칸. */
@Composable
private fun TripPanel(trip: LiveTrip, state: DriveUiState, modifier: Modifier) {
    val p = state.progress
    Row(
        modifier.glass().padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentGauge(trip, Modifier.size(150.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("이번 행차", fontSize = 20.sp, color = Muted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(String.format(Locale.ROOT, "%.1f", trip.distanceKm), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                Text("km", fontSize = 26.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
                Text("+${trip.xp}XP", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Gold, modifier = Modifier.padding(bottom = 4.dp))
            }
            SegmentPips(trip.marks)
            Text(
                (if (trip.safeRun > 0) "안전 구간 ${trip.safeRun}연속 · " else "") + "Lv.${p.level} · 개근 ${p.streak}일",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 진행 중인 1분 구간: 1초마다 차오르고, 급가속·급감속이 없으면 금색이다. 다 차면 +10XP. */
@Composable
private fun SegmentGauge(trip: LiveTrip, modifier: Modifier) {
    val sec = trip.segmentSec.coerceIn(0.0, 60.0)
    val color = if (trip.segmentClean) Gold else Muted
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension * 0.1f
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(Line, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(color, -90f, (sec / 60.0 * 360).toFloat(), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${sec.toInt()}초", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(if (trip.segmentClean) "+10XP 구간" else "다음 구간", fontSize = 16.sp, color = color)
        }
    }
}

/** 지난 1분 구간들(최근 12칸). 금색 안전, 옥색 평정심, 붉은 급조작, 빈칸 정차. */
@Composable
private fun SegmentPips(marks: List<SegmentMark>) {
    val shown = marks.takeLast(12)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(12) { i ->
            val m = shown.getOrNull(i)
            val shape = RoundedCornerShape(5.dp)
            val base = Modifier.size(width = 26.dp, height = 18.dp).clip(shape)
            when (m) {
                SegmentMark.SAFE -> Box(base.background(Gold))
                SegmentMark.CALM -> Box(base.background(Calm))
                SegmentMark.HARSH -> Box(base.background(Harsh))
                SegmentMark.IDLE, null -> Box(base.border(2.dp, Line, shape))
            }
        }
    }
}

/** 옛길 원정 진행: 지난 지점에서 다음 지점까지 막대와 지금 테마 탈것. 남은 거리가 줄어드는 것이 보인다. */
@Composable
private fun JourneyStrip(live: LiveView, themeKey: String, modifier: Modifier) {
    val j = live.journey
    val next = j.next
    val span = ((next?.km ?: Station.TOTAL_KM) - j.reached.km).toDouble()
    val f = ((j.lapKm - j.reached.km) / span).toFloat().coerceIn(0f, 1f)
    Row(
        modifier.glass().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.width(150.dp)) {
            Text("옛길 원정", fontSize = 18.sp, color = Muted)
            Text(j.reached.label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.weight(1f).height(64.dp)) {
            val icon = assetBitmap("icons/icon_$themeKey.webp")
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * 0.62f
                drawLine(Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 10f, cap = StrokeCap.Round)
                drawLine(Gold, Offset(0f, y), Offset(size.width * f, y), strokeWidth = 12f, cap = StrokeCap.Round)
                drawCircle(Gold, 10f, Offset(0f, y))
                drawCircle(Muted, 10f, Offset(size.width, y))
            }
            icon?.let {
                Image(it, null, Modifier.size(56.dp).align(Alignment.TopStart).offset(x = maxWidth * f - 28.dp))
            }
        }
        Column(Modifier.width(210.dp), horizontalAlignment = Alignment.End) {
            Text(next?.let { "${it.label} ${it.kind}" } ?: "한 바퀴 완주", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(String.format(Locale.ROOT, "%.1fkm 남음", j.kmToNext), fontSize = 22.sp, color = Gold, fontWeight = FontWeight.SemiBold)
        }
    }
}
