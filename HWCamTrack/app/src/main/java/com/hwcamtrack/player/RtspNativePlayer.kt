package com.hwcamtrack.player

import android.view.Surface
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.net.H264RtpDepacketizer
import com.hwcamtrack.net.RtspClient
import com.hwcamtrack.net.RtpPacket
import com.hwcamtrack.net.SdpParser
import com.hwcamtrack.util.Base64Codec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 自研的 RTSP / RTP over TCP 取流通道。
 *
 * ## 为什么不用 ExoPlayer 的 RTSP
 *
 * Media3 的 `RtspClient.startPlayback()` 把 PLAY 发到**原始会话 URI**：
 *
 * ```java
 * public void startPlayback(long offsetMs) {
 *   messageSender.sendPlayRequest(uri, offsetMs, checkNotNull(sessionId));
 * }
 * ```
 *
 * 而 RFC 2326 规定 `a=control` 要相对 `Content-Base` 解析。联咏 Nvt 这套
 * LIVE555（v2013.07.03）会给每条流分配 `rtsp://host/<会话ID>/` 这样的命名空间：
 *
 * ```
 * DESCRIBE rtsp://192.168.1.254:554/
 *   -> Content-Base: rtsp://192.168.1.254/0000001B/
 *   -> a=control:track1
 *
 * SETUP rtsp://192.168.1.254/0000001B/track1   -> 200 OK      （Media3 拼对了）
 * PLAY  rtsp://192.168.1.254/0000001B/         -> 200 OK + 推流（Media3 拼错了）
 * PLAY  rtsp://192.168.1.254:554/              -> 404 Stream Not Found
 * ```
 *
 * 也就是说 Media3 对 SETUP 正确处理了 Content-Base，唯独 PLAY（以及 PAUSE /
 * TEARDOWN）用的是请求 URI，于是这类摄像头一律以 `ERROR_CODE_IO_UNSPECIFIED`
 * 失败。1.5.1 和 1.11.1 两个版本都是这个行为，升级依赖解决不了。
 *
 * ## 这条通道做什么
 *
 * 自己完成 OPTIONS / DESCRIBE / SETUP / PLAY（把 URL 按 Content-Base 拼对），
 * 然后按 RFC 2326 §10.12 读 TCP 交错帧、按 RFC 6184 解 H.264 的 RTP 载荷，
 * 最后交给 [H264AccessUnitDecoder] 解码。
 *
 * ## 还有一件必须做的事：回 RTCP 接收报告
 *
 * 实测这台联咏服务端在**收不到任何 RTCP 反馈**时，会在约 30 秒后直接 RST 掉连接：
 *
 * ```
 * 不发 RTCP：  [probe] 数据流异常结束（第 29 秒）：SocketException: Connection reset
 * 每 5 秒发 RR：[probe] 运行 95.0 秒，RTCP RR 18 次        ← 全程不断
 * ```
 *
 * 所以 [readLoop] 里会定期回一个最小的 RTCP RR（丢包率/抖动都填 0，
 * 目的只是让服务端看见客户端还活着）。
 *
 * 目前只处理 H.264；H.265 仍然走 Media3。
 */
class RtspNativePlayer(
    private val preference: DecoderPreference,
    private val onState: (PlaybackState) -> Unit,
    private val onLog: (String) -> Unit
) {

    private var job: Job? = null
    private var decoder: H264AccessUnitDecoder? = null

    @Volatile
    private var client: RtspClient? = null

    /** 用户主动断开时不要再报播放故障。 */
    @Volatile
    private var stopping = false

    /** PTS 换算用的状态（见 [rtpTimestampToUs]）。 */
    private var firstRtpTimestamp = -1L
    private var ptsAnchorUs = -1L

    fun start(endpoint: CamEndpoint, surface: Surface, scope: CoroutineScope) {
        stop()
        stopping = false
        firstRtpTimestamp = -1L
        ptsAnchorUs = -1L
        job = scope.launch(Dispatchers.IO) {
            try {
                run(endpoint, surface)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!stopping) {
                    onLog("RTSP stream interrupted: ${t.message}")
                    onState(PlaybackState.Error(t.message ?: "RTSP stream interrupted", endpoint))
                }
            }
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        client?.let { runCatching { it.close() } }
        client = null
        decoder?.release()
        decoder = null
    }

    private suspend fun run(endpoint: CamEndpoint, surface: Surface) {
        onState(PlaybackState.Connecting(endpoint))

        val unitDecoder = H264AccessUnitDecoder(
            surface = surface,
            preference = preference,
            onLog = onLog,
            onConfigured = { width, height ->
                onState(
                    PlaybackState.Playing(
                        endpoint = endpoint,
                        width = width,
                        height = height,
                        note = "Native RTSP / RTP over TCP / ${preference.label}"
                    )
                )
            }
        )
        unitDecoder.start()
        decoder = unitDecoder

        // 会话可能被摄像头主动掐断（有些机型会周期性断流），所以断流不算失败：
        // 只要**这一次会话曾经正常播过一段时间**，就重置失败计数、无限重连下去；
        // 只有"连都连不上"连续发生很多次，才认为摄像头真的没了。
        //
        // （一开始写的是"最多重连 4 次"，结果一台每 30 秒断一次的摄像头
        //   会在 2 分半钟后彻底报错——正好是用户看到的"不到 3 分钟就 error"。）
        var consecutiveFailures = 0
        while (!stopping) {
            val sessionStartedAt = System.currentTimeMillis()
            val failure = try {
                runSession(endpoint, unitDecoder)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                t
            }

            if (stopping) break

            val playedMillis = System.currentTimeMillis() - sessionStartedAt
            if (playedMillis >= PLAYED_ENOUGH_MS) {
                consecutiveFailures = 0
            }
            consecutiveFailures++

            if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                onState(
                    PlaybackState.Error(
                        "Failed to connect $MAX_CONSECUTIVE_FAILURES times in a row: " +
                            (failure?.message ?: "Peer closed the connection"),
                        endpoint
                    )
                )
                break
            }

            val reason = failure?.message ?: "Peer closed the connection"
            onLog(
                if (consecutiveFailures == 1) {
                    "Stream interrupted ($reason, played ${playedMillis / 1000}s); reconnecting..."
                } else {
                    "Reconnect failed ($reason, attempt $consecutiveFailures); retrying in ${RECONNECT_DELAY_MS}s..."
                }
            )
            delay(if (consecutiveFailures == 1) RECONNECT_DELAY_MS else BACKOFF_DELAY_MS)
            firstRtpTimestamp = -1L
            ptsAnchorUs = -1L
        }

        unitDecoder.release()
        if (decoder === unitDecoder) decoder = null
    }

    /** 建立一次 RTSP 会话，然后一直读到流结束或者抛异常。 */
    private suspend fun runSession(endpoint: CamEndpoint, unitDecoder: H264AccessUnitDecoder) {
        val rtsp = RtspClient(endpoint.host, endpoint.port, RTSP_TIMEOUT_MS)
        client = rtsp

        var sessionId: String? = null
        var aggregateUrl: String? = null

        try {
            rtsp.connect()
            val requestUri = endpoint.uri
            onLog("Native RTSP: DESCRIBE $requestUri")

            runCatching { rtsp.options(requestUri) }
                .getOrNull()
                ?.let { onLog("RTSP OPTIONS ${it.statusLine}") }

            val describe = rtsp.describe(requestUri)
            if (!describe.isSuccess) {
                throw IOException("DESCRIBE failed: ${describe.statusLine}")
            }

            // RFC 2326：a=control 与 PLAY/PAUSE 的 URL 都相对 Content-Base 解析
            val contentBase = describe.contentBase
            val namespace = contentBase ?: requestUri
            aggregateUrl = namespace
            onLog("Content-Base = ${contentBase ?: "(none, falling back to request URI)"}")

            val sdp = SdpParser.parse(describe.body)
            if (!sdp.hasVideo) throw IOException("No video track in SDP")
            onLog("SDP：${sdp.summary}")

            val codecName = sdp.videoCodec
            if (codecName != "H264") {
                throw IOException("The native RTSP channel only supports H.264, got ${codecName.ifEmpty { "unknown" }}")
            }

            // 用 SDP 里的 sprop-parameter-sets 预置 SPS/PPS，起播更快也更稳
            extractSprop(describe.body)?.let { sprop ->
                val parts = sprop.split(',')
                val sps = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Base64Codec.decode(it) }
                val pps = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Base64Codec.decode(it) }
                if (sps != null) unitDecoder.seedParameterSets(sps, pps)
                onLog("Seeded SPS(${sps?.size ?: 0}B) / PPS(${pps?.size ?: 0}B) from SDP")
            }

            val trackUrl = resolveTrackUrl(namespace, sdp.videoTrack?.control)
            onLog("track URL = $trackUrl")

            val setup = rtsp.setup(trackUrl, TRANSPORT_TCP)
            if (!setup.isSuccess) throw IOException("SETUP failed: ${setup.statusLine}")
            val session = setup.sessionId ?: throw IOException("SETUP returned no Session")
            sessionId = session
            onLog("SETUP OK · ${setup.headers["transport"].orEmpty()}")

            // 关键一步：PLAY 打到 Content-Base 命名空间
            val play = rtsp.play(namespace, session)
            if (play.isSuccess) {
                onLog("PLAY OK (aggregate URL $namespace)")
            } else {
                // 少数服务器只认 track URL，回退一次
                val retry = rtsp.play(trackUrl, session)
                if (!retry.isSuccess) throw IOException("PLAY failed: ${play.statusLine}")
                onLog("PLAY OK（track URL）")
            }

            rtsp.setReadTimeout(STREAM_READ_TIMEOUT_MS)

            // 注意：这里**不需要**再单独发 OPTIONS 保活。
            // 实测这台联咏服务端要的是 RTCP 接收报告（见 readLoop），
            // 只发 RTCP 就能稳定跑 95 秒以上，而之前每 30 秒必断。
            readLoop(rtsp, unitDecoder)
        } finally {
            val finishedSession = sessionId
            val finishedUrl = aggregateUrl
            if (finishedSession != null && finishedUrl != null) {
                runCatching { rtsp.teardown(finishedUrl, finishedSession) }
            }
            rtsp.close()
            client = null
        }
    }

    private fun readLoop(rtsp: RtspClient, decoder: H264AccessUnitDecoder) {
        val depacketizer = H264RtpDepacketizer()
        var rtpPackets = 0L
        var accessUnits = 0L
        var rtcpReports = 0L
        var sourceSsrc = 0L
        var lastSequence = -1
        var sequenceCycles = 0L
        var highestSequenceExtended = 0L
        var nextRtcpAt = System.currentTimeMillis() + RTCP_INTERVAL_MS

        while (!Thread.currentThread().isInterrupted) {
            val frame = rtsp.readInterleavedFrame() ?: break
            if (frame.channel != RTP_CHANNEL) continue // channel 1 是 RTCP

            val packet = RtpPacket.parse(frame.data) ?: continue
            rtpPackets++

            if (lastSequence >= 0 &&
                packet.sequenceNumber < lastSequence &&
                lastSequence - packet.sequenceNumber > 0x8000
            ) {
                // 16 位序号回绕，进位以拼出 32 位"扩展最高序号"
                sequenceCycles += 1L shl 16
            }
            lastSequence = packet.sequenceNumber
            sourceSsrc = packet.ssrc
            highestSequenceExtended = sequenceCycles + packet.sequenceNumber

            // ── 关键：定期回 RTCP 接收报告 ──────────────────────────────
            // 这台联咏服务端在收不到任何 RTCP 反馈时，会在约 30 秒后
            // 直接 RST 掉 TCP 连接（实测：不发 RTCP 第 29 秒断；
            // 每 5 秒发一次 RR，稳定跑 95 秒以上）。所以这一步不是"优化"，
            // 而是让会话能活下去的必要条件。
            val now = System.currentTimeMillis()
            if (now >= nextRtcpAt && sourceSsrc != 0L) {
                rtsp.sendRtcpReceiverReport(sourceSsrc, highestSequenceExtended)
                rtcpReports++
                nextRtcpAt = now + RTCP_INTERVAL_MS
                if (rtcpReports == 1L) {
                    onLog("Sending RTCP receiver reports every ${RTCP_INTERVAL_MS / 1000}s to keep the RTSP session alive")
                }
            }

            val accessUnit = depacketizer.onRtpPayload(
                frame.data, packet.payloadOffset, packet.payloadLength, packet.marker
            ) ?: continue

            accessUnits++
            decoder.submitAccessUnit(accessUnit, rtpTimestampToUs(packet.timestamp))
            // 解码在独立线程上跑，这里只管喂，不再阻塞网络读取

            if (accessUnits % 900L == 0L) {
                onLog("RTSP: $rtpPackets RTP packets / $accessUnits access units / $rtcpReports RTCP RR")
            }
        }

        onLog(
            "RTSP stream ended (RTP packets $rtpPackets, access units $accessUnits, RTCP RR $rtcpReports, " +
                "stray bytes discarded ${rtsp.discardedBytes})"
        )
    }

    /**
     * 把 RTP 时间戳（H.264 固定 90kHz）换算成 MediaCodec 用的 PTS（微秒）。
     *
     * **这是画面流畅与否的关键。** 摄像头采集每一帧时打一个 RTP 时间戳，
     * 它反映的是"这一帧是什么时候拍的"；而数据到达手机的时刻带着网络抖动
     * 和摄像头的发送突发性。如果拿到达时刻当 PTS，MediaCodec 就会按那个抖动的
     * 节奏去渲染，画面看起来就是一顿一顿的。
     *
     * 这里把第一帧锚定到"现在"，之后完全按 RTP 时间戳的间隔推进，
     * 于是渲染节奏跟着摄像头的采集时钟走，网络抖动被吸收掉。
     */
    private fun rtpTimestampToUs(rtpTimestamp: Long): Long {
        if (firstRtpTimestamp < 0) {
            firstRtpTimestamp = rtpTimestamp
            ptsAnchorUs = System.nanoTime() / 1_000L
        }
        // RTP 时间戳是 32 位的，会回绕
        val deltaTicks = (rtpTimestamp - firstRtpTimestamp) and 0xFFFFFFFFL
        return ptsAnchorUs + deltaTicks * 1_000_000L / RTP_CLOCK_HZ
    }

    /**
     * 这里曾经有一个每 30 秒发一次 OPTIONS 的保活循环。
     *
     * 实测证明它**没用**：这台摄像头断流的真正原因是收不到 RTCP 接收报告，
     * 而不是 RTSP 会话超时。现在已经去掉，改为在 [readLoop] 里回 RTCP RR——
     * 既解决了问题，又少了一个往数据流里插东西、可能打乱帧边界的来源。
     */

    /** RFC 2326：a=control 不是绝对 URL 时，相对命名空间解析。 */
    private fun resolveTrackUrl(namespace: String, control: String?): String = when {
        control.isNullOrEmpty() || control == "*" -> namespace
        control.startsWith("rtsp://") -> control
        else -> "${namespace.trimEnd('/')}/${control.trimStart('/')}"
    }

    /** 从 SDP 的 `a=fmtp` 里抠出 `sprop-parameter-sets`。 */
    private fun extractSprop(sdp: String): String? {
        val marker = "sprop-parameter-sets="
        for (line in sdp.lineSequence()) {
            val index = line.indexOf(marker, ignoreCase = true)
            if (index < 0) continue
            return line.substring(index + marker.length).substringBefore(';').trim()
        }
        return null
    }

    private companion object {
        const val RTSP_TIMEOUT_MS = 5000
        const val STREAM_READ_TIMEOUT_MS = 10_000
        /** RTCP 接收报告间隔。RFC 3550 建议视频约 5 秒，实测这个间隔足够保住会话。 */
        const val RTCP_INTERVAL_MS = 5_000L
        const val RTP_CHANNEL = 0

        /** 一次会话播够这么久，就认为"端点本身没问题"，失败计数清零。 */
        const val PLAYED_ENOUGH_MS = 10_000L

        /** 连续多少次"连都连不上"才彻底报错。 */
        const val MAX_CONSECUTIVE_FAILURES = 8

        const val RECONNECT_DELAY_MS = 1_500L
        const val BACKOFF_DELAY_MS = 5_000L

        /** H.264 的 RTP 时基固定 90kHz。 */
        const val RTP_CLOCK_HZ = 90_000L

        /** TCP 交错模式：单端口承载 RTP+RTCP，穿 AP 隔离/NAT 最稳。 */
        const val TRANSPORT_TCP = "RTP/AVP/TCP;unicast;interleaved=0-1"
    }
}
