package com.hwcamtrack

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hwcamtrack.audio.AlarmTonePlayer
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.data.CamProtocol
import com.hwcamtrack.databinding.ActivityMainBinding
import com.hwcamtrack.databinding.DialogManualEndpointBinding
import com.hwcamtrack.databinding.DialogSettingsBinding
import com.hwcamtrack.net.CamDiscoverer
import com.hwcamtrack.net.DiscoveryEvent
import com.hwcamtrack.net.NetworkContext
import com.hwcamtrack.net.NetworkInfoProvider
import com.hwcamtrack.player.DecoderPreference
import com.hwcamtrack.player.PlaybackState
import com.hwcamtrack.player.StreamController
import com.hwcamtrack.settings.DetectorKind
import com.hwcamtrack.settings.SettingsStore
import com.hwcamtrack.ui.EndpointAdapter
import com.hwcamtrack.ui.TrackingOverlayView
import com.hwcamtrack.vision.analysis.AnalysisEngine
import com.hwcamtrack.vision.track.AlarmEvent
import com.hwcamtrack.vision.track.TrackerOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主界面：串起"取流 → 分析 → 告警"这条主线。
 *
 * ```
 *  自动扫描 → 拿到端点（地址 + 端口 + 协议 + 编码格式） → 连接 → 出画
 *                                                            ↓
 *                              抓帧 → 人体检测 → IoU 跟踪 → 面积比判定
 *                                                            ↓
 *                                       黄色框 / 超门限变红框 + 界面告警
 * ```
 *
 * 分工：
 *  - 取流链路在 `data` / `net` / `player`
 *  - 检测跟踪链路在 `vision`
 *  - 本 Activity 只做三件事：把两条链路接起来、把状态画到界面上、把用户操作分派下去
 */
class MainActivity : AppCompatActivity() {

    /** 底部信息面板的三个页签 */
    private enum class PanelTab { RESULTS, LOG, ALARM }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var streamController: StreamController
    private lateinit var analysis: AnalysisEngine
    private lateinit var discoverer: CamDiscoverer
    private lateinit var networkInfoProvider: NetworkInfoProvider
    private lateinit var endpointAdapter: EndpointAdapter
    private lateinit var alarmTone: AlarmTonePlayer

    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logLines = ArrayDeque<String>()
    private val alarmLines = ArrayList<String>()

    private var discoveryJob: Job? = null
    private var networkContext: NetworkContext? = null
    private var selectedEndpoint: CamEndpoint? = null

    /** 播放中切到后台再回来时，自动恢复上一次的连接。 */
    private var resumeEndpointOnStart: CamEndpoint? = null
    private var isStreaming = false

    /** 该时间戳之前不做分析（刚连上时画面还在跳变，面积比没有意义）。 */
    private var suppressAnalysisUntil = 0L

    private var panelTab = PanelTab.RESULTS
    private var panelExpanded = true
    private var alarmCount = 0

    private val hideAlarmBanner = Runnable {
        if (::binding.isInitialized) binding.alarmBanner.visibility = View.GONE
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { granted -> !granted }) {
            appendLog(getString(R.string.permission_denied))
        }
        refreshNetworkInfo()
    }

    // ================================================================== 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        networkInfoProvider = NetworkInfoProvider(this)
        discoverer = CamDiscoverer(this)

        // 流控制器把"画面在哪里"暴露给分析引擎，是两条链路唯一的接缝
        streamController = StreamController(
            context = this,
            videoTextureView = binding.videoTexture,
            mjpegImageView = binding.mjpegImage,
            onState = ::onPlaybackState,
            onLog = ::appendLog
        )

        analysis = AnalysisEngine(
            appContext = applicationContext,
            frameSourceProvider = { streamController.currentFrameSource() },
            settingsProvider = { settings.snapshot() },
            suppressUntilProvider = { suppressAnalysisUntil },
            listener = analysisListener
        )

        endpointAdapter = EndpointAdapter { endpoint -> selectEndpoint(endpoint, autoConnect = true) }
        binding.rvEndpoints.layoutManager = LinearLayoutManager(this)
        binding.rvEndpoints.adapter = endpointAdapter

        // 还不知道视频尺寸时先按 16:9 摆放，避免第一帧把画面拉变形
        binding.aspectFrame.aspectRatio = DEFAULT_ASPECT_RATIO

        setupPanel()
        setupControls()
        setupThreshold()
        setupAlarmSound()

        setStateUi(R.string.state_idle, R.color.state_idle)
        showPlaceholder(getString(R.string.player_placeholder))
        switchPanelTab(PanelTab.RESULTS)
        renderAlarmLog()
        updatePanelEmpty()
        requestPermissionsIfNeeded()
        showLastCrashIfAny()
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkInfo()
    }

    override fun onStart() {
        super.onStart()
        // 从后台回来时恢复上一次的会话
        resumeEndpointOnStart?.let { endpoint ->
            resumeEndpointOnStart = null
            connect(endpoint)
        }
    }

    override fun onStop() {
        super.onStop()
        analysis.stop()
        alarmTone.stop()
        // 摄像头一般只允许一个客户端，切后台就主动让出连接
        if (isStreaming) {
            resumeEndpointOnStart = selectedEndpoint
            appendLog("Moved to background; released the camera connection")
        }
        streamController.close()
        binding.trackingOverlay.clear()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        discoveryJob?.cancel()
        alarmTone.release()
        analysis.release()
        streamController.release()
        super.onDestroy()
    }

    /**
     * 上次运行如果崩溃过，把堆栈直接打到日志面板里。
     *
     * 用户手机上的故障现场没法导 logcat，把栈留在 App 自己能看到的地方，
     * "用一会儿就闪退"就能变成一段可复制的文字。
     */
    private fun showLastCrashIfAny() {
        val crash = TrackApp.consumeLastCrash(this) ?: return
        appendLog("======== LAST CRASH ========")
        crash.lineSequence().forEach { line -> appendLog(line) }
        appendLog("══════════════════════════════")
        appendLog("Please send the lines above to the developer; they pinpoint the failing line.")
        switchPanelTab(PanelTab.LOG)
    }

    // ================================================================== 界面初始化

    private fun setupPanel() {
        binding.tabResults.setOnClickListener { switchPanelTab(PanelTab.RESULTS) }
        binding.tabLog.setOnClickListener { switchPanelTab(PanelTab.LOG) }
        binding.tabAlarm.setOnClickListener { switchPanelTab(PanelTab.ALARM) }

        binding.btnPanelToggle.setOnClickListener {
            panelExpanded = !panelExpanded
            binding.panelContent.visibility = if (panelExpanded) View.VISIBLE else View.GONE
            binding.btnPanelToggle.setText(
                if (panelExpanded) R.string.panel_collapse else R.string.panel_expand
            )
            updatePanelEmpty()
        }
    }

    private fun setupControls() {
        binding.btnScan.setOnClickListener { toggleScan() }
        binding.btnConnect.setOnClickListener {
            val endpoint = selectedEndpoint
            if (endpoint == null) {
                toast(getString(R.string.results_empty))
                switchPanelTab(PanelTab.RESULTS)
            } else {
                connect(endpoint)
            }
        }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnManual.setOnClickListener { showManualDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    /** 面积比门限滑杆：范围 1.00 ~ 5.00，步进 0.01。 */
    private fun setupThreshold() {
        val seekBar = binding.sbThreshold
        seekBar.max = ((SettingsStore.MAX_THRESHOLD - SettingsStore.MIN_THRESHOLD) * 100f).roundToInt()
        seekBar.progress = ((settings.thresholdRatio - SettingsStore.MIN_THRESHOLD) * 100f)
            .roundToInt()
            .coerceIn(0, seekBar.max)
        renderThreshold()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.thresholdRatio = SettingsStore.MIN_THRESHOLD + progress / 100f
                renderThreshold()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun renderThreshold() {
        binding.tvThreshold.text =
            String.format(Locale.US, "%.2f", settings.thresholdRatio)
    }

    /**
     * 报警声。
     *
     * 主界面上只放一个开关（现场随手就能静音），时长放在「检测设置」里。
     * 打开开关时立刻响一次，用户马上就知道能不能听到、音量合不合适。
     */
    private fun setupAlarmSound() {
        alarmTone = AlarmTonePlayer()

        binding.swAlarmSound.isChecked = settings.alarmSoundEnabled
        binding.swAlarmSound.setOnCheckedChangeListener { _, checked ->
            settings.alarmSoundEnabled = checked
            renderAlarmSoundLabel()
            if (checked) {
                warnIfAlarmVolumeIsZero()
                alarmTone.play(settings.alarmSoundMillis)
            } else {
                alarmTone.stop()
            }
        }
        renderAlarmSoundLabel()
    }

    private fun renderAlarmSoundLabel() {
        binding.tvAlarmSound.text = if (settings.alarmSoundEnabled) {
            getString(R.string.alarm_sound_value, secondsOfAlarmSound())
        } else {
            getString(R.string.alarm_sound_off)
        }
    }

    private fun secondsOfAlarmSound(): Int = (settings.alarmSoundMillis / 1000L).toInt()

    /**
     * 报警声走的是系统"闹钟"音量（USAGE_ALARM），这样静音模式/勿扰下依然能响。
     * 代价是用户把闹钟音量拉到 0 时会听不到——这种情况明确提示一次。
     */
    private fun warnIfAlarmVolumeIsZero() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) <= 0) {
            appendLog(getString(R.string.alarm_sound_volume_zero))
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // ================================================================== 扫描

    private fun toggleScan() {
        val running = discoveryJob?.isActive == true
        if (running) {
            discoveryJob?.cancel()
            discoveryJob = null
            setScanning(false)
            appendLog("Scan cancelled")
            return
        }
        startScan()
    }

    private fun startScan() {
        refreshNetworkInfo()
        val net = networkContext ?: networkInfoProvider.current()

        toast(getString(if (net.isUsable) R.string.toast_scanning else R.string.toast_no_wifi))

        endpointAdapter.submit(emptyList())
        selectedEndpoint = null
        renderSelectedEndpoint()
        updatePanelEmpty()
        switchPanelTab(PanelTab.LOG)
        setScanning(true)

        discoveryJob = discoverer.discover(net)
            .onEach { event -> handleDiscoveryEvent(event) }
            .catch { error ->
                appendLog("x discovery failed: ${error.message}")
                setScanning(false)
            }
            .launchIn(lifecycleScope)
    }

    private fun handleDiscoveryEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.Log -> appendLog(event.message)

            is DiscoveryEvent.Found -> {
                endpointAdapter.add(event.endpoint)
                updatePanelEmpty()
            }

            is DiscoveryEvent.Finished -> {
                setScanning(false)
                if (event.endpoints.isEmpty()) return

                val best = event.endpoints.first()
                appendLog("Found ${event.endpoints.size} playable endpoint(s); best: ${best.displayUri}")
                selectEndpoint(best, autoConnect = true)
            }

            is DiscoveryEvent.Failed -> {
                appendLog("✗ ${event.message}")
                toast(event.message)
            }
        }
    }

    private fun setScanning(scanning: Boolean) {
        binding.btnScan.setText(if (scanning) R.string.btn_stop_scan else R.string.btn_scan)
        // 扫描期间顶部状态条也跟着变；已经在播放就不要覆盖"播放中"
        if (!isStreaming) {
            if (scanning) {
                setStateUi(R.string.state_scanning, R.color.state_busy)
            } else {
                setStateUi(R.string.state_idle, R.color.state_idle)
            }
        }
    }

    private fun selectEndpoint(endpoint: CamEndpoint, autoConnect: Boolean) {
        selectedEndpoint = endpoint
        endpointAdapter.selectedKey = endpoint.key
        endpointAdapter.notifyDataSetChanged()
        renderSelectedEndpoint()
        appendLog("Selected: ${endpoint.displayUri} (${endpoint.evidence.joinToString("; ")})")
        if (autoConnect) connect(endpoint)
    }

    // ================================================================== 连接 / 断开

    private fun connect(endpoint: CamEndpoint) {
        resumeEndpointOnStart = null
        showPlaceholder(null)
        binding.trackingOverlay.clear()
        clearAlarmBanner()
        alarmTone.stop()

        // 换了会话，跟踪 ID 和面积比的历史全部作废
        analysis.resetTracking()
        // 首帧往往是黑的或者还没稳定，先压住分析，避免开播就误告警
        suppressAnalysisUntil = SystemClock.uptimeMillis() + CONNECT_SETTLE_MS

        streamController.open(
            endpoint = endpoint,
            forceRtpTcp = settings.forceRtpTcp,
            preference = currentDecoderPreference()
        )
    }

    private fun disconnect() {
        resumeEndpointOnStart = null
        isStreaming = false
        analysis.stop()
        analysis.resetTracking()
        alarmTone.stop()
        streamController.close()
        binding.trackingOverlay.clear()
        clearAlarmBanner()
        showPlaceholder(getString(R.string.player_placeholder))
        appendLog("Disconnected")
        toast(getString(R.string.toast_disconnected))
    }

    private fun currentDecoderPreference(): DecoderPreference =
        if (settings.softwareDecode) DecoderPreference.SOFTWARE else DecoderPreference.AUTO

    private fun onPlaybackState(state: PlaybackState) {
        when (state) {
            is PlaybackState.Idle -> {
                isStreaming = false
                analysis.stop()
                keepScreenOn(false)
                setStateUi(R.string.state_idle, R.color.state_idle)
                showPlaceholder(getString(R.string.player_placeholder))
                binding.trackingOverlay.clear()
            }

            is PlaybackState.Connecting -> {
                isStreaming = false
                keepScreenOn(true)
                setStateUi(R.string.state_connecting, R.color.state_busy)
                showPlaceholder(null)
                binding.trackingOverlay.clear()
            }

            is PlaybackState.Buffering -> {
                isStreaming = false
                keepScreenOn(true)
                setStateUi(R.string.state_buffering, R.color.state_busy)
                showPlaceholder(null)
            }

            is PlaybackState.Playing -> {
                isStreaming = true
                keepScreenOn(true)
                setStateUi(R.string.state_playing, R.color.state_ok)
                showPlaceholder(null)
                if (state.width > 0 && state.height > 0) {
                    binding.aspectFrame.aspectRatio = state.width.toFloat() / state.height
                }
                renderSelectedEndpoint(state)
                // 画面出来了才开始抓帧分析；重复调用是安全的（start() 自身幂等）
                analysis.start()
            }

            is PlaybackState.Error -> {
                isStreaming = false
                analysis.stop()
                keepScreenOn(false)
                setStateUi(R.string.state_error, R.color.state_error)
                showPlaceholder(state.message)
                binding.trackingOverlay.clear()
                appendLog("✗ ${state.message}")
            }
        }
    }

    /**
     * 播放期间保持屏幕常亮。
     *
     * Android 默认大约 30 秒就息屏，而息屏会走 onStop → 断开连接，
     * 长时间监控会被硬生生打断——看起来就像"用一会儿就挂了"。
     */
    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ================================================================== 渲染

    private fun refreshNetworkInfo() {
        val net = networkInfoProvider.current()
        networkContext = net
        binding.tvNetwork.text = if (net.isWifi) net.summary else getString(R.string.net_unknown)
    }

    private fun setStateUi(stateRes: Int, colorRes: Int) {
        binding.tvState.setText(stateRes)
        binding.stateDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun showPlaceholder(text: String?) {
        if (text == null) {
            binding.tvPlaceholder.visibility = View.GONE
        } else {
            binding.tvPlaceholder.text = text
            binding.tvPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun renderSelectedEndpoint(playing: PlaybackState.Playing? = null) {
        val endpoint = selectedEndpoint
        if (endpoint == null) {
            binding.tvEndpoint.setText(R.string.endpoint_none)
            return
        }

        val parts = ArrayList<String>(4)
        parts += endpoint.displayUri
        parts += endpoint.protocol.label
        if (endpoint.vendor.isNotEmpty()) parts += endpoint.vendor
        parts += endpoint.videoSummary
        if (endpoint.rtspRequiresContentBase) parts += "Content-Base namespace"
        if (playing != null && playing.width > 0 && playing.height > 0) {
            parts += getString(R.string.info_actual_size, playing.width, playing.height)
        }

        binding.tvEndpoint.text = parts.joinToString(" · ")
    }

    // ================================================================== 信息面板

    private fun switchPanelTab(tab: PanelTab) {
        panelTab = tab
        binding.rvEndpoints.visibility = if (tab == PanelTab.RESULTS) View.VISIBLE else View.GONE
        binding.logScroll.visibility = if (tab == PanelTab.LOG) View.VISIBLE else View.GONE
        binding.alarmScroll.visibility = if (tab == PanelTab.ALARM) View.VISIBLE else View.GONE

        binding.tabResults.setTextColor(tabColor(tab == PanelTab.RESULTS))
        binding.tabLog.setTextColor(tabColor(tab == PanelTab.LOG))
        binding.tabAlarm.setTextColor(tabColor(tab == PanelTab.ALARM))

        if (!panelExpanded) {
            panelExpanded = true
            binding.panelContent.visibility = View.VISIBLE
            binding.btnPanelToggle.setText(R.string.panel_collapse)
        }
        if (tab == PanelTab.LOG) renderLog()
        updatePanelEmpty()
    }

    private fun tabColor(active: Boolean): Int = ContextCompat.getColor(
        this,
        if (active) R.color.brand else R.color.on_surface_muted
    )

    private fun updatePanelEmpty() {
        if (!panelExpanded) {
            binding.tvPanelEmpty.visibility = View.GONE
            return
        }
        val empty = when (panelTab) {
            PanelTab.RESULTS -> endpointAdapter.itemCount == 0
            PanelTab.LOG -> logLines.isEmpty()
            PanelTab.ALARM -> alarmLines.isEmpty()
        }
        binding.tvPanelEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.tvPanelEmpty.setText(
            when (panelTab) {
                PanelTab.RESULTS -> R.string.results_empty
                PanelTab.LOG -> R.string.log_empty
                PanelTab.ALARM -> R.string.alarm_empty
            }
        )
    }

    // ================================================================== 日志

    /**
     * 往日志面板追加一行。
     *
     * **这里是硬性线程保证**：只要有任何一个播放器/协程忘了编组线程，
     * 直接碰 `TextView.setText` 就会抛 `CalledFromWrongThreadException` 把 App 打崩
     * 所以在入口统一兜住——不在主线程就转过去。
     */
    private fun appendLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post { appendLog(message) }
            return
        }
        logLines.addLast("[${timeFormat.format(Date())}] $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        if (panelTab == PanelTab.LOG) renderLog()
    }

    private fun renderLog() {
        binding.tvLog.text = logLines.joinToString("\n")
        updatePanelEmpty()
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ================================================================== 分析结果

    private val analysisListener = object : AnalysisEngine.Listener {

        override fun onAnalyzed(
            imageWidth: Int,
            imageHeight: Int,
            output: TrackerOutput,
            inferenceMs: Long,
            frameIndex: Long,
            measuredFps: Float
        ) {
            submitOverlay(imageWidth, imageHeight, output)

            if (output.newAlarms.isNotEmpty()) showAlarms(output.newAlarms)

            binding.tvStats.text = getString(
                R.string.track_stats,
                frameIndex,
                output.results.size,
                inferenceMs,
                measuredFps,
                settings.thresholdRatio
            )
        }

        override fun onDetectorChanged(displayName: String) {
            appendLog("Detector ready: $displayName")
        }

        override fun onAnalysisError(message: String) {
            appendLog("Analysis error: $message")
        }
    }

    /** 把跟踪结果换算成覆盖层的框，并提交绘制。 */
    private fun submitOverlay(imageWidth: Int, imageHeight: Int, output: TrackerOutput) {
        if (!settings.showTrackingOverlay) {
            binding.trackingOverlay.clear()
            return
        }

        val boxes = output.results.map { result ->
            TrackingOverlayView.Box(
                id = result.id,
                rect = result.rect,
                ratio = result.ratio,
                ratioValid = result.ratioValid,
                // 面积比超门限 → 红框；正常跟踪 → 黄框
                alarmed = result.alarmed,
                missed = result.missedFrames > 0
            )
        }

        binding.trackingOverlay.submit(
            videoRectInOverlay(),
            imageWidth,
            imageHeight,
            boxes
        )
    }

    /**
     * 视频显示区域在覆盖层坐标系里的矩形。
     *
     * `TrackingOverlayView` 与视频视图是 `AspectRatioFrameLayout` 里尺寸完全相同的
     * 兄弟节点（该容器会把两者一起居中到同一个矩形），所以这里就是覆盖层自己的全部区域。
     */
    private fun videoRectInOverlay(): RectF {
        val overlay = binding.trackingOverlay
        return RectF(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())
    }

    // ================================================================== 告警显示

    /**
     * 界面告警由三部分组成：
     *  - 画面顶部的红色横幅：只显示最严重的那一条，几秒后自动消失
     *  - 面板「告警记录」页签里的滚动列表：保留历史，供事后排查
     *  - "滴滴……"报警声：持续 [SettingsStore.alarmSoundMillis] 毫秒，可在界面上开关与调时长
     *
     * 注意声音是由**新产生的告警事件**触发的（IouTracker 对同一目标有去抖），
     * 不是"只要还红着就一直响"——否则画面里人一直贴着脸，声音就停不下来了。
     */
    private fun showAlarms(alarms: List<AlarmEvent>) {
        if (settings.alarmSoundEnabled) {
            alarmTone.play(settings.alarmSoundMillis)
        }

        val worst = alarms.maxByOrNull { it.ratio } ?: alarms.first()
        val direction = getString(if (worst.grow) R.string.alarm_grow else R.string.alarm_shrink)

        binding.tvAlarmBanner.text = getString(
            R.string.alarm_banner,
            worst.trackId,
            direction,
            worst.ratio,
            worst.threshold
        )
        binding.alarmBanner.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideAlarmBanner)
        uiHandler.postDelayed(hideAlarmBanner, ALARM_BANNER_MS)

        val stamp = timeFormat.format(Date())
        for (alarm in alarms) {
            alarmCount++
            val arrow = if (alarm.grow) "↑" else "↓"
            alarmLines.add(
                0,
                getString(
                    R.string.alarm_line,
                    stamp,
                    alarm.trackId,
                    getString(if (alarm.grow) R.string.alarm_grow else R.string.alarm_shrink),
                    alarm.ratio,
                    arrow,
                    alarm.prevArea,
                    alarm.area
                )
            )
        }
        while (alarmLines.size > MAX_ALARM_LINES) {
            alarmLines.removeAt(alarmLines.size - 1)
        }
        renderAlarmLog()
    }

    private fun renderAlarmLog() {
        binding.tvAlarmLog.text = alarmLines.joinToString("\n")
        binding.tabAlarm.text = if (alarmCount > 0) {
            "${getString(R.string.tab_alarm)} ($alarmCount)"
        } else {
            getString(R.string.tab_alarm)
        }
        updatePanelEmpty()
    }

    private fun clearAlarmBanner() {
        uiHandler.removeCallbacks(hideAlarmBanner)
        binding.alarmBanner.visibility = View.GONE
    }

    // ================================================================== 设置与手动输入

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)

        // ---------------- 语言 ----------------
        setupLanguageSelector(dialogBinding)

        // ---------------- 检测器 ----------------
        val labels = DetectorKind.entries.map { getString(it.labelRes) }.toTypedArray()
        dialogBinding.spDetector.adapter = ArrayAdapter(this, R.layout.item_spinner, labels)
        dialogBinding.spDetector.setSelection(
            if (settings.detector == DetectorKind.POSE) 1 else 0,
            false
        )
        dialogBinding.spDetector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.detector = DetectorKind.entries[position.coerceIn(0, DetectorKind.entries.size - 1)]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // ---------------- 分析帧率 ----------------
        dialogBinding.sbFps.max = SettingsStore.MAX_FPS - SettingsStore.MIN_FPS
        dialogBinding.sbFps.progress = settings.analysisFps - SettingsStore.MIN_FPS
        renderFpsLabel(dialogBinding)
        dialogBinding.sbFps.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.analysisFps = SettingsStore.MIN_FPS + progress
            renderFpsLabel(dialogBinding)
        })

        // ---------------- 最小告警框面积 ----------------
        dialogBinding.sbMinArea.max = MIN_AREA_MAX_STEPS
        dialogBinding.sbMinArea.progress =
            ((settings.minTrackAreaPx / MIN_AREA_STEP).roundToInt() - 1).coerceIn(0, MIN_AREA_MAX_STEPS)
        renderMinAreaLabel(dialogBinding, settings.minTrackAreaPx)
        dialogBinding.sbMinArea.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            val area = (progress + 1) * MIN_AREA_STEP
            settings.minTrackAreaPx = area
            renderMinAreaLabel(dialogBinding, area)
        })

        // ---------------- 报警声持续时长 ----------------
        val soundSteps = ((SettingsStore.MAX_ALARM_SOUND_MS - SettingsStore.MIN_ALARM_SOUND_MS) /
            SettingsStore.ALARM_SOUND_STEP_MS).toInt()
        dialogBinding.sbAlarmDuration.max = soundSteps
        dialogBinding.sbAlarmDuration.progress =
            ((settings.alarmSoundMillis - SettingsStore.MIN_ALARM_SOUND_MS) /
                SettingsStore.ALARM_SOUND_STEP_MS).toInt().coerceIn(0, soundSteps)
        renderAlarmDurationLabel(dialogBinding)
        dialogBinding.sbAlarmDuration.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            settings.alarmSoundMillis =
                SettingsStore.MIN_ALARM_SOUND_MS + progress * SettingsStore.ALARM_SOUND_STEP_MS
            renderAlarmDurationLabel(dialogBinding)
            renderAlarmSoundLabel()
        })
        dialogBinding.btnTestSound.setOnClickListener {
            warnIfAlarmVolumeIsZero()
            alarmTone.play(settings.alarmSoundMillis)
        }

        // ---------------- 开关 ----------------
        dialogBinding.swSymmetric.isChecked = settings.symmetric
        dialogBinding.swSymmetric.setOnCheckedChangeListener { _, checked -> settings.symmetric = checked }

        dialogBinding.swShowTracking.isChecked = settings.showTrackingOverlay
        dialogBinding.swShowTracking.setOnCheckedChangeListener { _, checked ->
            settings.showTrackingOverlay = checked
            if (!checked) binding.trackingOverlay.clear()
        }

        dialogBinding.swRtpTcp.isChecked = settings.forceRtpTcp
        dialogBinding.swRtpTcp.setOnCheckedChangeListener { _, checked ->
            settings.forceRtpTcp = checked
            appendLog(if (checked) "RTSP transport: RTP over TCP (recommended, survives AP isolation)" else "RTSP transport: RTP over UDP")
        }

        dialogBinding.swSoftwareDecode.isChecked = settings.softwareDecode
        dialogBinding.swSoftwareDecode.setOnCheckedChangeListener { _, checked ->
            settings.softwareDecode = checked
            appendLog(if (checked) "Decoder mode: software only" else "Decoder mode: hardware preferred (auto fallback)")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun renderFpsLabel(dialogBinding: DialogSettingsBinding) {
        dialogBinding.tvFpsLabel.text = getString(R.string.label_analysis_fps, settings.analysisFps)
    }

    private fun renderMinAreaLabel(dialogBinding: DialogSettingsBinding, area: Float) {
        dialogBinding.tvMinAreaLabel.text =
            getString(R.string.label_min_area, area.roundToInt())
    }

    private fun renderAlarmDurationLabel(dialogBinding: DialogSettingsBinding) {
        dialogBinding.tvAlarmDurationLabel.text =
            getString(R.string.label_alarm_duration, secondsOfAlarmSound())
    }

    /**
     * 界面语言选择。
     *
     * 用 `AppCompatDelegate.setApplicationLocales()`，而不是自己改 Configuration：
     *
     *  - 它是 Android 官方的 per-app language 方案，API 21+ 由 AppCompat 兼容实现；
     *  - 选择结果由 AppCompat 持久化（见 AndroidManifest 里的 `autoStoreLocales` 服务），
     *    下次启动在第一帧就生效，不会先闪一下旧语言；
     *  - API 33+ 还会同步到系统的"应用语言"设置页（见 `res/xml/locales_config.xml`）。
     *
     * 设置之后系统会自动重建 Activity，所以这里不需要手动重新加载界面。
     */
    private fun setupLanguageSelector(dialogBinding: DialogSettingsBinding) {
        // 空串表示"跟随系统"
        val options = listOf(
            "" to getString(R.string.language_system),
            "zh" to getString(R.string.language_zh),
            "en" to getString(R.string.language_en)
        )

        dialogBinding.spLanguage.adapter =
            ArrayAdapter(this, R.layout.item_spinner, options.map { it.second })

        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = options.indexOfFirst { it.first.isNotEmpty() && currentTags.startsWith(it.first) }
        dialogBinding.spLanguage.setSelection(if (selected >= 0) selected else 0, false)

        dialogBinding.spLanguage.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val tag = options[position.coerceIn(options.indices)].first
                    val target = if (tag.isEmpty()) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                    // 选的就是当前语言时不要再触发一次 Activity 重建
                    if (target != AppCompatDelegate.getApplicationLocales()) {
                        AppCompatDelegate.setApplicationLocales(target)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun simpleSeekListener(onProgress: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) =
                onProgress(progress)

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun showManualDialog() {
        val dialogBinding = DialogManualEndpointBinding.inflate(layoutInflater)
        val protocols = CamProtocol.manualChoices

        dialogBinding.spProtocol.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            protocols.map { it.label }
        )

        networkContext?.gateway?.let { dialogBinding.etHost.setText(it) }
        networkContext?.ssid?.let { appendLog("Current hotspot: $it") }
        dialogBinding.etPort.setText(protocols.first().defaultPort.toString())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_manual_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = dialogBinding.etHost.text?.toString()?.trim().orEmpty()
                val port = dialogBinding.etPort.text?.toString()?.trim()?.toIntOrNull()
                val path = dialogBinding.etPath.text?.toString()?.trim().orEmpty()
                val protocol = protocols[dialogBinding.spProtocol.selectedItemPosition]

                when {
                    host.isEmpty() -> dialogBinding.tilHost.error = getString(R.string.error_host_required)

                    port == null || port !in 1..65535 -> {
                        dialogBinding.tilHost.error = null
                        dialogBinding.tilPort.error = getString(R.string.error_port_invalid)
                    }

                    else -> {
                        dialog.dismiss()
                        val endpoint = CamEndpoint(
                            host = host,
                            port = port,
                            protocol = protocol,
                            path = path,
                            vendor = getString(R.string.vendor_manual),
                            confidence = 100,
                            evidence = listOf("user input")
                        )
                        endpointAdapter.add(endpoint)
                        updatePanelEmpty()
                        selectEndpoint(endpoint, autoConnect = true)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MAX_LOG_LINES = 500
        const val MAX_ALARM_LINES = 60
        const val ALARM_BANNER_MS = 2500L

        /** 刚连上时压住分析的时间：等画面稳定再开始算面积比 */
        const val CONNECT_SETTLE_MS = 900L

        /** 视频比例未知时的兜底值 */
        const val DEFAULT_ASPECT_RATIO = 16f / 9f

        /** 最小告警框面积滑杆：1~50 档，每档 100 px² */
        const val MIN_AREA_STEP = 100f
        const val MIN_AREA_MAX_STEPS = 49
    }
}
