package haengcha.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import haengcha.judge.Weather
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

private val LanternBlue = Color(0xFF2E4F9E)
private val LanternRed = Color(0xFFC9372C)
private val LanternWood = Color(0xFF3B2416)
private val LanternGlow = Color(0xFFFFC56B)

/**
 * 0~1을 [periodMs]마다 반복하는 시간. [stepped](영상 금지 규제)면 초당 1프레임을 넘지 않게 1초에 한 번만 바뀐다
 * (CarUxRestrictions NO_VIDEO: 초당 1프레임을 넘는 움직임 금지).
 */
@Composable
private fun loopTime(periodMs: Int, stepped: Boolean, label: String): Float {
    if (stepped) {
        val step by produceState(0) {
            while (true) {
                delay(1_000)
                value++
            }
        }
        return (step * 1_000f / periodMs) % 1f
    }
    val t = rememberInfiniteTransition(label = label)
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label)
    return v
}

/**
 * 장면 위에 얹는 밤·날씨 연출(야행, 날씨 반응 장면). 지역 장면 영상은 낮 그림이라 밤에는 어둡게 덮고 별과 처마 초롱을 단다.
 * 와이퍼가 돌면 비, 바깥 기온이 낮으면 눈을 뿌린다. [paused](영상 금지 규제)면 1초에 한 번만 바뀐다.
 */
@Composable
fun AtmosphereOverlay(night: Boolean, weather: Weather?, paused: Boolean, modifier: Modifier = Modifier) {
    if (!night && weather == null) return
    val t = loopTime(6_000, paused, "atmosphere")
    val stars = remember { List(70) { Triple(Random(it).nextFloat(), Random(it + 100).nextFloat() * 0.45f, Random(it + 200).nextFloat()) } }
    val drops = remember { List(140) { Triple(Random(it + 300).nextFloat(), Random(it + 400).nextFloat(), 0.6f + Random(it + 500).nextFloat() * 0.8f) } }
    Canvas(modifier.fillMaxSize()) {
        if (night) {
            drawRect(Brush.verticalGradient(listOf(Color(0xCC07122A), Color(0x99101A33), Color(0x80140F1E))))
            stars.forEach { (x, y, phase) ->
                val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(2 * PI * (t * 2 + phase)).toFloat())
                drawCircle(Color.White.copy(alpha = a * 0.9f), radius = 1.2f + phase * 1.6f, center = Offset(x * size.width, y * size.height))
            }
            // 처마 끝에서 늘어진 줄에 매단 초롱
            val r = size.minDimension * 0.016f
            val n = (size.width / (r * 11)).toInt().coerceIn(5, 14)
            fun ropeY(x: Float): Float {
                val u = x / size.width * 2 - 1
                return size.height * (0.035f + 0.03f * (1 - u * u))
            }
            val rope = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, ropeY(0f))
                for (k in 1..40) (size.width * k / 40).let { lineTo(it, ropeY(it)) }
            }
            drawPath(rope, LanternWood.copy(alpha = 0.85f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
            for (i in 0 until n) {
                val x = size.width * (i + 0.5f) / n
                val sway = r * 0.3f * sin(2 * PI * (t + i * 0.13)).toFloat()
                val y = ropeY(x)
                drawLine(LanternWood.copy(alpha = 0.85f), Offset(x, y), Offset(x + sway, y + r * 1.2f), strokeWidth = 1.5f)
                lantern(Offset(x + sway, y + r * 1.2f), r, glow = 0.85f + 0.15f * sin(2 * PI * (t * 3 + i * 0.3)).toFloat())
            }
        }
        when (weather) {
            Weather.RAIN -> {
                drawRect(Color(0x26203040))
                drops.forEach { (x, y, speed) ->
                    val len = size.height * 0.05f * speed
                    val py = ((y + t * 4 * speed) % 1f) * (size.height + len) - len
                    val px = x * size.width + py * 0.18f - size.height * 0.09f
                    drawLine(Color(0x66CFE3FF), Offset(px, py), Offset(px + len * 0.18f, py + len), strokeWidth = 2f, cap = StrokeCap.Round)
                }
            }
            Weather.SNOW -> {
                drawRect(Color(0x1FE8F0FF))
                drops.forEach { (x, y, speed) ->
                    val py = ((y + t * speed) % 1f) * size.height
                    val px = (x * size.width + 24f * sin(2 * PI * (t * 2 + x * 5)).toFloat()) % size.width
                    drawCircle(Color.White.copy(alpha = 0.85f), radius = 2f + speed * 3f, center = Offset(px, py))
                }
            }
            null -> Unit
        }
    }
}

/**
 * 주행 중 야행: 장면 아래쪽을 지나가는 초롱 행렬. [paused](영상 금지 규제)면 1초에 한 칸씩 나아간다.
 */
@Composable
fun LanternProcession(paused: Boolean, modifier: Modifier = Modifier) {
    val t = loopTime(12_000, paused, "procession")
    Canvas(modifier.fillMaxSize()) {
        // 장면 지평선 높이를 지나가는 작은 행렬
        val r = size.minDimension * 0.018f
        val gap = r * 7f
        val baseY = size.height * 0.5f
        val count = (size.width / gap).toInt() + 2
        clipRect {
            for (i in 0 until count) {
                val x = (i * gap + t * gap * 2) % (count * gap) - gap
                val bob = r * 0.25f * sin(2 * PI * (t * 6 + i * 0.4)).toFloat()
                // 초롱을 든 장대
                drawLine(LanternWood.copy(alpha = 0.9f), Offset(x - r * 1.6f, baseY + r * 3.5f), Offset(x, baseY - r * 1.4f + bob), strokeWidth = r * 0.18f, cap = StrokeCap.Round)
                lantern(Offset(x, baseY - r * 1.4f + bob), r, glow = 1f)
            }
        }
    }
}

/** 청사초롱 하나. [top]은 매단 고리 위치, [r]은 몸통 반폭. 위는 청색, 아래는 홍색 비단이고 안에서 불빛이 번진다. */
private fun DrawScope.lantern(top: Offset, r: Float, glow: Float) {
    val h = r * 2.4f
    val bodyTop = top.y + r * 0.3f
    val center = Offset(top.x, bodyTop + h / 2)
    drawCircle(Brush.radialGradient(listOf(LanternGlow.copy(alpha = 0.5f * glow), Color.Transparent), center, r * 3.5f), r * 3.5f, center)
    val body = Size(r * 2, h)
    val corner = Offset(top.x - r, bodyTop)
    drawOval(LanternRed, corner, body)
    clipRect(top = bodyTop, bottom = bodyTop + h * 0.42f) { drawOval(LanternBlue, corner, body) }
    drawOval(Brush.radialGradient(listOf(Color(0xE6FFE3A0).copy(alpha = 0.85f * glow), Color.Transparent), center, r * 1.1f), corner, body)
    drawRoundRect(LanternWood, Offset(top.x - r * 0.7f, top.y), Size(r * 1.4f, r * 0.35f), CornerRadius(r * 0.1f))
    drawRoundRect(LanternWood, Offset(top.x - r * 0.7f, bodyTop + h - r * 0.15f), Size(r * 1.4f, r * 0.35f), CornerRadius(r * 0.1f))
    drawLine(LanternRed, Offset(top.x, bodyTop + h + r * 0.2f), Offset(top.x, bodyTop + h + r * 1.0f), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
}
