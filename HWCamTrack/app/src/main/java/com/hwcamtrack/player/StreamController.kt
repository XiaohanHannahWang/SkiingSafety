package com.hwcamtrack.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.data.CamProtocol
import com.hwcamtrack.vision.analysis.BitmapFrameSource
import com.hwcamtrack.vision.analysis.FrameSource
import com.hwcamtrack.vision.analysis.TextureViewFrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放会话的统一入口：按协议（必要时按摄像头特性）挑一个播放实现，
 * 负责引擎自动回退，并把"当前画面在哪里"暴露给人体分析流水线。
 *
 * | 协议 | 实现 | 渲染目标 |
 * |------|------|----------|
 * | RTSP（普通） | ExoPlayer + RtspMediaSource | [TextureView] |
 * | RTSP（要求 Content-Base 命名空间） | [RtspNativePlayer] | [TextureView] |
 * | HTTP-FLV / 渐进式 | ExoPlayer + Extractor | [TextureView] |
 * | HTTP MJPEG | [MjpegPlayer] | [ImageView] |
 * | 裸 H.264 / TCP | [RawH264Player] | [TextureView] |
 *
 * ## 为什么渲染目标要这样分
 *
 * 播放器常见做法是输出到 `PlayerView` / `SurfaceView`。但 **SurfaceView 的画面由系统
 * 独立图层合成，应用层拿不到像素**，而本 App 必须把每一帧送进人体检测器，
 * 所以整条链路都改走 TextureView。
 *
 * 所以这里把三条件（ExoPlayer、自研 RTSP、裸 H.264）全部改成渲染到同一个
 * [TextureView]：TextureView 的内容就在应用自己的图形层里，`getBitmap()`
 * 可以直接取到像素；MediaCodec 也能直接输出到 `Surface(textureView.surfaceTexture)`，
 * 解码代码一行都不用改。
 *
 * MJPEG 保持用 [ImageView]：它本来就是逐帧 `BitmapFactory` 解出来的位图，
 * 直接复用同一块位图，比再拷进纹理省一次开销。
 *
 * 于是分析引擎只需要两个 [FrameSource] 实现就能覆盖全部五种协议。
 */
class StreamController(
    private val context: Context,
    /** 承载 ExoPlayer / 自研 MediaCodec 通道输出的纹理视图（由布局提供） */
    private val videoTextureView: TextureView,
    /** 承载 MJPEG 帧的图片视图（由布局提供） */
    private val mjpegImageView: ImageView,
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit
) {

    /** 当前画面由谁渲染；分析引擎据此决定从哪里抓帧。 */
    enum class Renderer {
        /** 没有会话 */
        NONE,

        /** 画面在 [videoTextureView] 上 */
        TEXTURE,

        /** 画面在 [mjpegImageView] 上 */
        MJPEG
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val textureFrameSource = TextureViewFrameSource { videoTextureView }
    private val mjpegFrameSource = BitmapFrameSource { mjpegPlayer?.currentFrameBitmap() }

    private var exoPlayer: ExoStreamPlayer? = null
    private var mjpegPlayer: MjpegPlayer? = null
    private var rawPlayer: RawH264Player? = null
    private var nativeRtspPlayer: RtspNativePlayer? = null

    private var currentEndpoint: CamEndpoint? = null
    private var usingNativeRtsp = false
    private var lastForceRtpTcp = true
    private var decoderPreference = DecoderPreference.AUTO

    /** 当前会话是否还允许"换引擎再试一次"。 */
    private var engineRetryAvailable = false

    @Volatile
    private var rendererState = Renderer.NONE

    /**
     * 绑定在 [videoTextureView] 的 SurfaceTexture 上的输出 Surface。
     *
     * 整个 Activity 生命周期内只建一次并反复复用：MediaCodec 在同一个 Surface 上
     * 反复 configure/stop 是正常用法，而"频繁 release 一个还有解码器在用的 Surface"
     * 才是真正会崩的操作。
     */
    private var boundSurfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null

    /** TextureView 的 SurfaceTexture 还没就绪时，把"起播动作"挂在这里等回调。 */
    private var pendingSurfaceStart: ((Surface) -> Unit)? = null

    /**
     * TextureView 的 SurfaceTexture 回调。
     *
     * 必须写在 [init] **之前**：Kotlin 的属性初始化与 init 块严格按书写顺序执行，
     * 若把监听器声明在 init 后面，init 里读到的还是 null，监听器就永远不会生效。
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            val action = pendingSurfaceStart ?: return
            pendingSurfaceStart = null
            val surface = obtainOutputSurface() ?: return
            action(surface)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            // 视图被移除：先停掉自研通道，再作废 Surface，返回 true 让 TextureView 释放纹理
            rawPlayer?.stop()
            nativeRtspPlayer?.stop()
            pendingSurfaceStart = null
            releaseOutputSurface()
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    init {
        videoTextureView.surfaceTextureListener = surfaceTextureListener
    }

    // ------------------------------------------------------------------ 对外接口

    /** 当前画面从哪里取（null 表示没有画面）。 */
    fun currentFrameSource(): FrameSource? = when (rendererState) {
        Renderer.TEXTURE -> textureFrameSource
        Renderer.MJPEG -> mjpegFrameSource
        Renderer.NONE -> null
    }

    /** 当前渲染方式，界面用来决定是否需要清空跟踪框。 */
    val renderer: Renderer get() = rendererState

    fun open(endpoint: CamEndpoint, forceRtpTcp: Boolean, preference: DecoderPreference) {
        teardownPlayers()

        currentEndpoint = endpoint
        lastForceRtpTcp = forceRtpTcp
        decoderPreference = preference
        engineRetryAvailable = endpoint.protocol == CamProtocol.RTSP

        val preferNative = endpoint.protocol == CamProtocol.RTSP && endpoint.rtspRequiresContentBase
        if (preferNative) {
            postLog("This camera requires the Content-Base namespace; using the native RTSP channel")
        }

        postLog("Opening session: ${endpoint.protocol.label} -> ${endpoint.displayUri} (decoder: ${preference.label})")
        openWithEngine(endpoint, preferNative, forceRtpTcp)
    }

    fun close() {
        teardownPlayers()
        currentEndpoint = null
        engineRetryAvailable = false
        usingNativeRtsp = false
        postState(PlaybackState.Idle)
    }

    fun release() {
        close()
        videoTextureView.surfaceTextureListener = null
        releaseOutputSurface()
        scope.cancel()
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    // ------------------------------------------------------------------ 引擎选择

    private fun openWithEngine(endpoint: CamEndpoint, useNativeRtsp: Boolean, forceRtpTcp: Boolean) {
        when (endpoint.protocol) {
            CamProtocol.HTTP_MJPEG -> openMjpeg(endpoint)
            CamProtocol.RAW_H264_TCP -> openRaw(endpoint)
            CamProtocol.RTSP ->
                if (useNativeRtsp) openNativeRtsp(endpoint) else openExo(endpoint, forceRtpTcp)

            else -> openExo(endpoint, forceRtpTcp)
        }
    }

    /**
     * 所有播放器的状态都先经过这里：RTSP 上一个引擎失败时，自动换另一个再试一次。
     *
     * ⚠️ 这个方法是**从播放器的 IO 线程**调用的，所以这里绝不能直接碰 UI：
     * 发日志必须走 [postLog]、发状态必须走 [postState]，两者都会编组回主线程。
     * 注意：如果在这里直接调 `onLog(...)`，一旦断流就会在 worker 线程上碰 UI，
     * 抛 `CalledFromWrongThreadException` —— 表现就是"一出错就闪退"。
     */
    private fun handleState(state: PlaybackState) {
        if (state is PlaybackState.Error) {
            val endpoint = currentEndpoint
            if (endpoint != null && endpoint.protocol == CamProtocol.RTSP && engineRetryAvailable) {
                engineRetryAvailable = false
                val switchToNative = !usingNativeRtsp
                postLog(
                    "Playback failed (${state.message}); switching to " +
                        (if (switchToNative) "the native RTSP channel" else "Media3") + " to retry..."
                )
                // 从播放器回调里直接拆播放器不安全，放到下一个主线程消息里做
                mainHandler.post {
                    teardownPlayers()
                    openWithEngine(endpoint, switchToNative, lastForceRtpTcp)
                }
                return
            }
        }
        postState(state)
    }

    private fun openExo(endpoint: CamEndpoint, forceRtpTcp: Boolean) {
        usingNativeRtsp = false
        showTextureRenderer()
        val player = ExoStreamPlayer(context, httpClient, videoTextureView, ::handleState, ::postLog)
        exoPlayer = player
        player.start(endpoint, forceRtpTcp)
    }

    private fun openMjpeg(endpoint: CamEndpoint) {
        usingNativeRtsp = false
        rendererState = Renderer.MJPEG
        // TextureView 始终保持可见：它的 SurfaceTexture 一旦被销毁重建，
        // 下一次连自研通道就得重新等一轮回调，没必要冒这个险。
        videoTextureView.visibility = View.VISIBLE
        mjpegImageView.visibility = View.VISIBLE

        mjpegPlayer = MjpegPlayer(
            okHttpClient = httpClient,
            // 切回主线程设置位图，天然形成背压：UI 没画完就不再解码下一帧
            onFrame = { bitmap -> withContext(Dispatchers.Main) { mjpegImageView.setImageBitmap(bitmap) } },
            onState = ::handleState,
            onLog = ::postLog
        ).also { it.start(endpoint, scope) }
    }

    private fun openRaw(endpoint: CamEndpoint) {
        usingNativeRtsp = false
        showTextureRenderer()
        val player = RawH264Player(decoderPreference, ::handleState, ::postLog)
        rawPlayer = player
        startOnTextureSurface(endpoint) { ep, surface -> player.start(ep, surface, scope) }
    }

    private fun openNativeRtsp(endpoint: CamEndpoint) {
        usingNativeRtsp = true
        showTextureRenderer()
        val player = RtspNativePlayer(decoderPreference, ::handleState, ::postLog)
        nativeRtspPlayer = player
        startOnTextureSurface(endpoint) { ep, surface -> player.start(ep, surface, scope) }
    }

    private fun showTextureRenderer() {
        rendererState = Renderer.TEXTURE
        mjpegImageView.visibility = View.GONE
        mjpegImageView.setImageDrawable(null)
        videoTextureView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ SurfaceTexture 生命周期

    /**
     * 两条自研 MediaCodec 通道都需要一个 Surface，并且必须等 TextureView 真正可用。
     *
     * Activity 刚起来时视图还没走完布局，`isAvailable` 会是 false；
     * 这时把起播动作挂到 [pendingSurfaceStart]，等 `onSurfaceTextureAvailable` 再执行。
     */
    private fun startOnTextureSurface(endpoint: CamEndpoint, start: (CamEndpoint, Surface) -> Unit) {
        val action: (Surface) -> Unit = { surface -> start(endpoint, surface) }

        val surface = obtainOutputSurface()
        if (surface != null) {
            action(surface)
            return
        }
        pendingSurfaceStart = action
    }

    private fun obtainOutputSurface(): Surface? {
        if (!videoTextureView.isAvailable) return null
        val texture = videoTextureView.surfaceTexture ?: return null

        val cached = outputSurface
        if (cached != null && boundSurfaceTexture === texture && cached.isValid) return cached

        // SurfaceTexture 换了（视图被移除又重新挂载），旧 Surface 必须作废
        releaseOutputSurface()
        val surface = runCatching { Surface(texture) }.getOrNull() ?: return null
        boundSurfaceTexture = texture
        outputSurface = surface
        return surface
    }

    private fun releaseOutputSurface() {
        outputSurface?.let { surface -> runCatching { surface.release() } }
        outputSurface = null
        boundSurfaceTexture = null
    }

    // ------------------------------------------------------------------ 收尾与线程编组

    private fun teardownPlayers() {
        exoPlayer?.let {
            it.stop()
            it.release()
        }
        exoPlayer = null

        mjpegPlayer?.stop()
        mjpegPlayer = null

        rawPlayer?.stop()
        rawPlayer = null

        nativeRtspPlayer?.stop()
        nativeRtspPlayer = null

        // 注意：这里**不释放** outputSurface —— 它属于 TextureView 的 SurfaceTexture，
        // 由 onSurfaceTextureDestroyed 负责收尾；重连时还能直接复用。
        pendingSurfaceStart = null
        rendererState = Renderer.NONE
        mjpegImageView.visibility = View.GONE
        mjpegImageView.setImageDrawable(null)
        videoTextureView.visibility = View.VISIBLE
    }

    private fun postState(state: PlaybackState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onState(state)
        } else {
            mainHandler.post { onState(state) }
        }
    }

    private fun postLog(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onLog(message)
        } else {
            mainHandler.post { onLog(message) }
        }
    }
}
