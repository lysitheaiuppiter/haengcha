package haengcha.app.ui

import haengcha.judge.JoseonTheme
import haengcha.judge.Region
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** 지역마다 정차 화면 그림과 움직이는 배경이 있는지 확인한다. 지역을 늘리고 tools/render3d로 장면을 만들지 않았으면 여기서 걸린다. */
class SceneAssetsTest {

    private val assets = File("src/main/assets")

    @Test
    fun `모든 지역에 정지 그림과 배경 영상이 있다`() {
        val names = listOf("bg_palace") + Region.entries.flatMap { r ->
            val key = r.name.lowercase()
            if (r.graded) r.grades.indices.map { "bg_${key}_$it" } else listOf("bg_$key")
        }
        val missing = names.flatMap { n -> listOf(n, "${n}_portrait") }.flatMap { n ->
            listOf("scenes/$n.webp", "motion/$n.mp4").filterNot { File(assets, it).isFile }
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `테마마다 정차 단상과 주행 뒷모습 레이어가 있다`() {
        val missing = JoseonTheme.entries.flatMap { t ->
            val key = t.name.lowercase()
            listOf("", "_rear").flatMap { kind -> listOf("", "_portrait").map { "scenes/vehicle_$key$kind$it.webp" } }
        }.filterNot { File(assets, it).isFile }
        assertEquals(emptyList(), missing)
    }
}
