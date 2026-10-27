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
import haengcha.judge.Region
import haengcha.judge.Weather
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
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
 * 정차 화면 그림을 찍은 카메라(tools/render3d/stage.js의 FRAME). 주행 중 초롱을 같은 원근으로 그려
 * 배경 지평선, 탈것 뒷모습과 소실점을 맞춘다. 화면 비율이 그림과 같다고 본다(landscape 두 칸, portrait 한 칸).
 */
private class SceneCamera(private val w: Float, private val h: Float) {
    private val wide = w > h
    private val camY = if (wide) 3.0f else 3.6f
    private val camZ = if (wide) 12.4f else 14.3f
    private val shiftX = if (wide) 0.15f else 0f
    private val shiftY = if (wide) 0.06f else 0.2f
    private val pitch = atan2(camY - 1f, camZ)
    private val tanHalf = tan(Math.toRadians(15.0)).toFloat()

    /** 세계 좌표(m)의 화면 위치와 그 깊이에서 1m의 픽셀 수. 카메라 뒤면 null. */
    fun project(x: Float, y: Float, z: Float): Pair<Offset, Float>? {
        val dy = y - camY
        val dz = z - camZ
        val depth = -dy * sin(pitch) - dz * cos(pitch)
        if (depth < 0.5f) return null
        val up = dy * cos(pitch) - dz * sin(pitch)
        val ppm = h / (2 * tanHalf * depth)
        return Offset(w * (0.5f + shiftX) + x * ppm, h * (0.5f + shiftY) - up * ppm) to ppm
    }
}

/**
 * 주행 중 야행: 앞으로 달리며 길 양옆에 선 초롱 장대를 지나친다. 탈것 뒷모습 앞쪽 지평선(소실점)에서 나타난 초롱이
 * 다가오며 커지고 양옆으로 빠져나간다. 길·차선은 그리지 않는다(내비게이션처럼 보이지 않게).
 * 앞이 바다인 장면([shoreZ]가 물가)에서는 물 위 구간의 초롱을 장대 없이 물에 뜬 유등으로 그리고, 물가를 지나면 장대 초롱이 된다
 * (바뀌는 자리에서는 하나가 흐려지며 다른 하나가 나타난다).
 * [paused](영상 금지 규제)면 1초에 한 번만 나아간다. 이때도 한 번에 장대 간격의 절반보다 적게 움직여 거꾸로 가 보이지 않는다.
 */
@Composable
fun LanternProcession(paused: Boolean, shoreZ: Float? = null, modifier: Modifier = Modifier) {
    val t = loopTime(10_000, paused, "procession")
    Canvas(modifier.fillMaxSize()) {
        val cam = SceneCamera(size.width, size.height)
        // 길 반폭, 나타나는 곳과 지나가는 곳(탈것은 z=-5.5, 카메라는 z=12~14)
        val half = 2.8f
        val far = -70f
        val near = 8f
        val perSide = 4
        val items = (0 until perSide).flatMap { i ->
            listOf(-1, 1).map { side -> ((t + (i + if (side > 0) 0.5f else 0f) / perSide) % 1f) to side }
        }.sortedBy { it.first }
        clipRect {
            items.forEach { (u, side) ->
                val z = far + (near - far) * u
                val a = (u / 0.1f).coerceAtMost(1f) * ((1f - u) / 0.08f).coerceIn(0f, 1f)
                val glow = 0.9f + 0.1f * sin(2 * PI * (t * 8 + u)).toFloat()
                if (shoreZ == null) {
                    poleLantern(cam, side * half, z, a, glow)
                } else {
                    val onWater = a * ((shoreZ - z) / SHORE_FADE_M).coerceIn(0f, 1f)
                    if (onWater > 0.01f) floatingLantern(cam, side * (half - 0.3f), z, onWater, t + u)
                    val onLand = a * ((z - shoreZ) / SHORE_FADE_M).coerceIn(0f, 1f)
                    if (onLand > 0.01f) poleLantern(cam, side * half, z, onLand, glow)
                }
            }
        }
    }
}

/** 물가 너비(m). 물 위 유등과 장대 초롱이 이만큼에 걸쳐 바뀐다. */
private const val SHORE_FADE_M = 2.5f

/** 앞이 바다인 장면의 물가 깊이(z, m). 정차 화면 그림의 물가와 같다(tools/render3d: 울산 SHORE_Z, towns.js의 -9.5). */
internal val Region.seaFrontZ: Float?
    get() = when (this) {
        Region.ULSAN -> -6f
        Region.BUSAN, Region.YEOSU, Region.GANGNEUNG -> -9.5f
        else -> null
    }

/** 길 쪽으로 뻗은 팔에 초롱을 매단 장대(높이 2.7m, x는 장대 자리)와 땅에 번진 불빛. */
private fun DrawScope.poleLantern(cam: SceneCamera, x: Float, z: Float, a: Float, glow: Float) {
    val (ground, ppm) = cam.project(x, 0f, z) ?: return
    val toRoad = if (x < 0) 0.5f else -0.5f
    val top = cam.project(x, 2.7f, z)?.first ?: return
    val hook = cam.project(x + toRoad, 2.7f, z)?.first ?: return
    val r = 0.17f * ppm
    drawOval(LanternGlow.copy(alpha = 0.16f * a), Offset(ground.x - 0.9f * ppm, ground.y - 0.15f * ppm), Size(1.8f * ppm, 0.3f * ppm))
    drawLine(LanternWood.copy(alpha = 0.9f * a), ground, top, strokeWidth = (0.07f * ppm).coerceAtLeast(1f), cap = StrokeCap.Round)
    drawLine(LanternWood.copy(alpha = 0.9f * a), top, hook, strokeWidth = (0.05f * ppm).coerceAtLeast(1f), cap = StrokeCap.Round)
    val hang = cam.project(x + toRoad, 2.45f, z)?.first ?: hook
    lantern(Offset(hook.x, hang.y - r * 0.3f), r, glow, alpha = a)
}

/** 물에 뜬 유등: 작은 받침 위 초롱이 물결에 흔들리고, 물에 불빛이 길게 비친다. */
private fun DrawScope.floatingLantern(cam: SceneCamera, x: Float, z: Float, a: Float, phase: Float) {
    val bob = 0.05f * sin(2 * PI * phase * 3).toFloat()
    val (water, ppm) = cam.project(x, 0f, z) ?: return
    val base = cam.project(x, 0.12f + bob, z)?.first ?: return
    val r = 0.15f * ppm
    drawRect(
        Brush.verticalGradient(listOf(LanternGlow.copy(alpha = 0.35f * a), Color.Transparent), water.y, water.y + 1.4f * ppm),
        Offset(water.x - 0.12f * ppm, water.y), Size(0.24f * ppm, 1.4f * ppm),
    )
    drawOval(LanternWood.copy(alpha = 0.85f * a), Offset(base.x - 0.35f * ppm, base.y - 0.05f * ppm), Size(0.7f * ppm, 0.12f * ppm))
    lantern(Offset(base.x, base.y - r * 2.9f), r, glow = 0.95f, alpha = a)
}

/** 청사초롱 하나. [top]은 매단 고리 위치, [r]은 몸통 반폭. 위는 청색, 아래는 홍색 비단이고 안에서 불빛이 번진다. */
private fun DrawScope.lantern(top: Offset, r: Float, glow: Float, alpha: Float = 1f) {
    val h = r * 2.4f
    val bodyTop = top.y + r * 0.3f
    val center = Offset(top.x, bodyTop + h / 2)
    drawCircle(Brush.radialGradient(listOf(LanternGlow.copy(alpha = 0.5f * glow * alpha), Color.Transparent), center, r * 3.5f), r * 3.5f, center)
    val body = Size(r * 2, h)
    val corner = Offset(top.x - r, bodyTop)
    drawOval(LanternRed.copy(alpha = alpha), corner, body)
    clipRect(top = bodyTop, bottom = bodyTop + h * 0.42f) { drawOval(LanternBlue.copy(alpha = alpha), corner, body) }
    drawOval(Brush.radialGradient(listOf(Color(0xE6FFE3A0).copy(alpha = 0.85f * glow * alpha), Color.Transparent), center, r * 1.1f), corner, body)
    drawRoundRect(LanternWood.copy(alpha = alpha), Offset(top.x - r * 0.7f, top.y), Size(r * 1.4f, r * 0.35f), CornerRadius(r * 0.1f))
    drawRoundRect(LanternWood.copy(alpha = alpha), Offset(top.x - r * 0.7f, bodyTop + h - r * 0.15f), Size(r * 1.4f, r * 0.35f), CornerRadius(r * 0.1f))
    drawLine(LanternRed.copy(alpha = alpha), Offset(top.x, bodyTop + h + r * 0.2f), Offset(top.x, bodyTop + h + r * 1.0f), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
}
