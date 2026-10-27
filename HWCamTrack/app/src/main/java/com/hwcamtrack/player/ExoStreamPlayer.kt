package com.hwcamtrack.player

import android.content.Context
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.data.CamProtocol
import com.hwcamtrack.data.ProfileCatalog
import okhttp3.OkHttpClient

/**
 * 基于 Media3/ExoPlayer 的播放器，负责 RTSP(RTP) 和 HTTP-FLV 两类流。
 *
 * - **RTSP**：默认强制 RTP over TCP。摄像头的 AP 往往有客户端隔离或者极小的
 *   UDP 缓冲，走 UDP 经常花屏/丢包，走 TCP 稳定得多（代价是延迟略高）。
 * - **HTTP-FLV**：`.flv` 带 FLV 头，ExoPlayer 的 FlvExtractor 直接能解。
 * - 其余 HTTP 渐进式流按容器自动识别。
 *
 * MJPEG 不在这里——ExoPlayer 不支持 multipart MJPEG，由 [MjpegPlayer] 自己解析。
 *
 * ## 渲染到 TextureView
 *
 * `PlayerView` 的内核是 SurfaceView，画面在独立图层上，应用层取不到像素。
 * 本 App 需要逐帧做人体检测，所以用 `setVideoTextureView(...)` 输出到调用方提供的
 * [TextureView]，`TextureView.getBitmap()` 就能拿到当前帧。
 * 画面比例由布局里的 `AspectRatioFrameLayout` 负责。
 */
class ExoStreamPlayer(
    context: Context,
    okHttpClient: OkHttpClient,
    private val videoTextureView: TextureView,
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit
) {

    private val dataSourceFactory: OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttpClient).setUserAgent(ProfileCatalog.USER_AGENT)

    private var current: CamEndpoint? = null

    /** 视频尺寸（媒体 format 里的，未必等于显示尺寸）。 */
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    init {
        player.playWhenReady = true
        player.setVideoTextureView(videoTextureView)
        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                val endpoint = current ?: return
                when (playbackState) {
                    Player.STATE_BUFFERING -> onState(PlaybackState.Buffering(endpoint))
                    Player.STATE_READY -> onState(
                        PlaybackState.Playing(
                            endpoint = endpoint,
                            width = videoWidth,
                            height = videoHeight,
                            note = "ExoPlayer"
                        )
                    )

                    Player.STATE_ENDED -> onState(PlaybackState.Error("Stream ended", endpoint))
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val endpoint = current ?: return
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    onLog("Video size: ${videoWidth}x$videoHeight")
                    onState(
                        PlaybackState.Playing(
                            endpoint = endpoint,
                            width = videoWidth,
                            height = videoHeight,
                            note = "ExoPlayer"
                        )
                    )
                }
            }

            override fun onRenderedFirstFrame() {
                onLog("First frame rendered")
            }

            override fun onPlayerError(error: PlaybackException) {
                val endpoint = current
                onLog("Playback error: ${error.errorCodeName} ${error.message.orEmpty()}")
                onState(PlaybackState.Error(describe(error), endpoint))
            }
        })
    }

    fun start(endpoint: CamEndpoint, forceRtpTcp: Boolean) {
        current = endpoint
        videoWidth = 0
        videoHeight = 0
        onState(PlaybackState.Connecting(endpoint))
        onLog("Playing ${endpoint.displayUri} (${endpoint.protocol.label})")

        runCatching {
            player.setMediaSource(buildMediaSource(endpoint, forceRtpTcp))
            player.prepare()
        }.onFailure { error ->
            onLog("Cannot start player: ${error.message}")
            onState(PlaybackState.Error(error.message ?: "Player failed to start", endpoint))
        }
    }

    fun stop() {
        current = null
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun release() {
        runCatching { player.release() }
    }

    private fun buildMediaSource(endpoint: CamEndpoint, forceRtpTcp: Boolean): MediaSource {
        val item = MediaItem.fromUri(endpoint.uri)
        return when (endpoint.protocol) {
            CamProtocol.RTSP -> {
                onLog("RTSP transport: ${if (forceRtpTcp) "RTP over TCP" else "RTP over UDP"}")
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceRtpTcp)
                    .setUserAgent(ProfileCatalog.USER_AGENT)
                    .createMediaSource(item)
            }

            // HTTP-FLV 与其余 HTTP 渐进式流都交给同一个 progressive 通道，
            // DefaultExtractorsFactory 会按容器魔数自己挑 extractor。
            else -> ProgressiveMediaSource
                .Factory(dataSourceFactory, DefaultExtractorsFactory())
                .createMediaSource(item)
        }
    }

    /** 把 ExoPlayer 的错误码翻译成用户能照做的建议。 */
    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Cannot reach the camera. Check that the phone is still on the camera hotspot and that host/port are correct."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The camera returned an error status (usually a wrong path or authentication required)."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "This URL is not a parsable video stream. Try another protocol or path."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "No matching decoder on this device (H.265 on low-end phones). Try another stream profile."

        else -> error.message ?: error.errorCodeName
    }
}
