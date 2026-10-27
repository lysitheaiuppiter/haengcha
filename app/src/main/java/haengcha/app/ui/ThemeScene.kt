package haengcha.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import haengcha.judge.JoseonTheme
import haengcha.judge.Region
import org.json.JSONObject

/**
 * 정차·주행 화면 배경. 세 겹을 같은 비율·같은 정렬로 겹친다.
 * 1. 움직이는 배경(assets/motion/bg_*.mp4): 고래가 뛰어오르고 등대 불빛이 도는 11초 루프 영상. [paused]면 멈춘 한 프레임
 * 2. 정지 배경(assets/scenes/bg_*.webp): 영상이 뜨기 전에 보이다가 영상이 그려지면 서서히 사라진다. 재생에 실패하면 남는다
 * 3. 탈것 레이어(assets/scenes/vehicle_<테마>.webp): 단상 위 지금 테마 탈것(투명 배경).
 *    [rear](주행 중)면 vehicle_<테마>_rear.webp: 단상 없이 배경 지평선의 정면 소실점으로 달려가는 뒷모습
 * 배경은 지역 밖이면 궁궐 마당, 울산이면 밤바다와 고래, 횡성이면 들판과 한우이고 고래 빛깔·한우 메달은 단골 등급([grade])을 따른다.
 * 도감 지역 13곳은 지역마다 상징 모형을 세운 장면 하나를 쓴다.
 * 그림과 영상은 tools/render3d로 Blender 모델을 three.js에서 렌더한 것이다. landscape는 탈것이 오른쪽(왼쪽은 카드 자리),
 * portrait는 아래쪽에 있다.
 */
@Composable
fun ThemeScene(theme: JoseonTheme, region: Region?, grade: Int, paused: Boolean, rear: Boolean = false, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        val suffix = if (portrait) "_portrait" else ""
        // 화면 비율이 그림과 달라 잘릴 때도 탈것이 남도록 탈것 쪽에 붙인다
        val align = if (portrait) Alignment.BottomCenter else Alignment.CenterEnd
        // 울산·횡성은 단골 등급마다, 도감 지역은 지역마다 장면이 하나다
        val bg = "bg_" + (region?.let { if (it.graded) "${it.name.lowercase()}_$grade" else it.name.lowercase() } ?: "palace") + suffix
        var playing by remember(bg) { mutableStateOf(false) }
        val stillAlpha by animateFloatAsState(if (playing) 0f else 1f, tween(600), label = "still")

        AndroidView(
            factory = { LoopVideoView(it) },
            update = { it.bind("motion/$bg.mp4", paused, portrait) { playing = true } },
            modifier = Modifier.fillMaxSize(),
        )
        if (stillAlpha > 0f) {
            assetBitmap("scenes/$bg.webp")?.let {
                Image(it, region?.let { r -> "${r.label} ${r.theme} 테마" }, Modifier.fillMaxSize(), align, ContentScale.Crop, stillAlpha)
            }
        }
        // 주행 중에는 단상 없이 화면 안쪽으로 달려가는 뒷모습
        assetBitmap("scenes/vehicle_${theme.name.lowercase()}${if (rear) "_rear" else ""}$suffix.webp")?.let {
            Image(it, "${theme.label} 단상", Modifier.fillMaxSize(), align, ContentScale.Crop)
        }
    }
}

/** assets 안의 그림. 없으면 null. */
@Composable
internal fun assetBitmap(path: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        runCatching { context.assets.open(path).use(BitmapFactory::decodeStream) }.getOrNull()?.asImageBitmap()
    }
}

/**
 * assets의 영상을 소리 없이 반복 재생하는 TextureView. 정지 그림과 같게 잘라(center crop) 탈것 쪽에 붙인다.
 * 처음 열 때는 정지 그림과 같은 순간(motion.json의 stillMs)으로 건너뛰어 그림에서 영상으로 자연스럽게 넘어간다.
 */
private class LoopVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var asset: String? = null
    private var paused = false
    private var portrait = false
    private var onPlaying: () -> Unit = {}
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var videoW = 0
    private var videoH = 0

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun bind(asset: String, paused: Boolean, portrait: Boolean, onPlaying: () -> Unit) {
        this.onPlaying = onPlaying
        if (portrait != this.portrait) {
            this.portrait = portrait
            fit()
        }
        if (asset != this.asset) {
            this.asset = asset
            this.paused = paused
            open()
        } else if (paused != this.paused) {
            this.paused = paused
            player?.takeIf { prepared }?.let { if (paused) it.pause() else it.start() }
        }
    }

    private fun open() {
        release()
        val s = surface ?: return
        val name = asset ?: return
        val mp = MediaPlayer()
        player = mp
        runCatching {
            context.assets.openFd(name).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setSurface(s)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                prepared = true
                videoW = it.videoWidth
                videoH = it.videoHeight
                fit()
                it.seekTo(stillMs(context), MediaPlayer.SEEK_CLOSEST)
                if (!paused) it.start()
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onPlaying()
                false
            }
            // 멈춘 채로 열면(주행 중 영상 금지) 건너뛴 프레임이 그려지는 것으로 충분하다
            mp.setOnSeekCompleteListener { if (paused) onPlaying() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "영상 재생 실패 $name ($what, $extra)")
                true
            }
            mp.prepareAsync()
        }.onFailure {
            Log.w(TAG, "영상 열기 실패 $name", it)
            release()
        }
    }

    /** 영상을 화면에 꽉 차게 잘라 정지 그림(ContentScale.Crop)과 같은 자리에 맞춘다. */
    private fun fit() {
        if (videoW == 0 || width == 0) return
        val scale = maxOf(width.toFloat() / videoW, height.toFloat() / videoH)
        val w = videoW * scale
        val h = videoH * scale
        // landscape는 오른쪽, portrait는 아래쪽에 붙인다
        val dx = if (portrait) (width - w) / 2 else width - w
        val dy = if (portrait) height - h else (height - h) / 2
        setTransform(Matrix().apply {
            setScale(w / width, h / height)
            postTranslate(dx, dy)
        })
    }

    private fun release() {
        player?.release()
        player = null
        prepared = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        open()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fit()

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private companion object {
        const val TAG = "ThemeScene"
        private var still: Long? = null

        /** 정지 그림과 같은 순간(고래가 떠오르는 중)의 영상 시각. */
        fun stillMs(context: Context): Long = still ?: runCatching {
            JSONObject(context.assets.open("motion/motion.json").bufferedReader().readText()).getLong("stillMs")
        }.getOrDefault(0L).also { still = it }
    }
}
