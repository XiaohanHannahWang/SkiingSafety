package com.hwcamtrack.player

import com.hwcamtrack.data.CamEndpoint

/** 播放器的对外状态，用来驱动顶部状态条。 */
sealed interface PlaybackState {

    /** 空闲，没有会话。 */
    data object Idle : PlaybackState

    /** 正在建立连接。 */
    data class Connecting(val endpoint: CamEndpoint) : PlaybackState

    /** 已连上但还在缓冲（等首个关键帧 / 等 SDP 协商完）。 */
    data class Buffering(val endpoint: CamEndpoint) : PlaybackState

    /** 正常出画。 */
    data class Playing(
        val endpoint: CamEndpoint,
        val width: Int = 0,
        val height: Int = 0,
        val note: String = ""
    ) : PlaybackState

    /** 失败，交给 UI 展示原因和排错建议。 */
    data class Error(val message: String, val endpoint: CamEndpoint? = null) : PlaybackState
}
