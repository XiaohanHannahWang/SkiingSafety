# CamTrack · 摄像头人体跟踪与面积比告警

Android / Kotlin 应用。手机先连上摄像头的 WiFi 热点，App 会自动发现并连接摄像头、
实时播放它的视频流，同时**在本机离线**做人体检测与多目标跟踪；当同一个跟踪目标在
前后两帧的**框面积比**超过门限时，框变红，并给出画面横幅、告警记录和"滴滴"报警声。
界面支持**中文 / English** 切换。

> **English** — An Android/Kotlin app that auto-discovers a Wi-Fi camera, streams its
> video, tracks people fully on-device, and raises an area-ratio alarm (red box,
> on-screen banner, alarm log and a synthesised beep) when one tracked person's box
> area grows past a configurable threshold between consecutive frames.
> The UI is available in English and Chinese.

---

## 功能

| # | 能力 | 说明 |
|---|---|---|
| 1 | 自动获取摄像头地址 / 端口 / 协议格式 | 五步发现：历史记录 → SSDP → 候选地址端口快扫 → 协议握手 → 兜底全网段；结果里带协议、编码、分辨率、厂商、置信度 |
| 2 | 连接 / 断开 | 一键连接，自动挑最优端点；断开或切后台时主动释放连接（摄像头通常只允许一个客户端） |
| 3 | 实时播放视频流 | RTSP(RTP over TCP/UDP)、HTTP MJPEG、HTTP-FLV、HTTP 渐进式、裸 H.264 over TCP |
| 4 | 人体检测与跟踪 | TFLite EfficientDet-Lite0（可多人）或 ML Kit Pose（单人），配 IoU 跟踪器分配稳定 ID |
| 5 | 面积比告警 | 面积 = 框宽 × 框高；`面积比 = 本帧面积 ÷ 上一帧同一 ID 面积`；达到门限即变**红框** |
| 6 | 界面告警 | 画面顶部红色横幅（2.5 s）、「告警记录」页签（带时间戳与累计次数）、**"滴滴"报警声** |
| 7 | 报警声可调 | 时长 1~10 秒可设，可一键静音，可"试听"；音色由程序合成，不占安装包体积 |
| 8 | 中英双语 | 跟随系统 / 中文 / English，应用内切换并持久化 |

---

## 运行要求

| 项 | 值 |
|---|---|
| 最低系统 | Android 7.0（API 24） |
| 编译 / 目标 SDK | 35 |
| 构建工具链 | AGP 8.7.3 · Gradle 8.9 · Kotlin 2.0.21 · JDK 17+ |
| 权限 | 网络、WiFi 状态；读取 SSID 需要 `NEARBY_WIFI_DEVICES`(33+) / `ACCESS_FINE_LOCATION`(≤32) |
| 运行时依赖 | 全部离线：检测模型随 APK 打包，无网络服务 |

> App 不访问手机自身的摄像头，因此不需要 `CAMERA` 权限——它读的是**外置摄像头通过
> WiFi 推过来的流**。

---

## 编译与安装

### Android Studio（推荐）

1. **File → Open** 选择本工程根目录，等待 Gradle 同步；
2. 若提示缺少 SDK，按提示安装 **Android SDK Platform 35** 与 **Build-Tools 36.0.0**
   （`app/build.gradle.kts` 里显式固定了 `buildToolsVersion = "36.0.0"`）；
3. `local.properties` 由 Android Studio 自动生成（内容是本机 SDK 路径），不要提交；
4. 连接手机或启动模拟器，点 **Run**。

### 命令行

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建会从 Google / Maven Central 下载依赖，需要网络；之后走本地缓存。

### 关于签名

工程内置了 `keystore/debug.keystore`——就是标准的 Android 调试签名
（storepass / keypass 均为 `android`），仅用于本地安装调试。
**正式发布请替换成你自己的 keystore**，并修改 `app/build.gradle.kts` 里的 `signingConfigs`。

---

## 使用

1. 手机连上摄像头的 WiFi 热点（多数摄像头开机后会广播自己的 SSID）；
2. 打开 App，点「自动扫描」。首次约 3~8 秒；之后同一热点会秒连（端点按 SSID 记在本地）；
3. 扫描完自动连最优端点并出画。也可以点列表里的其它端点切换，或点「手动输入」
   直接填地址 / 端口 / 协议；
4. 画面里出现的人会被**黄色框**跟踪，框上标注 ID 与当前面积比；
5. 拖主界面的「面积比门限」滑杆设定灵敏度（默认 1.50）；
6. 面积比达到门限 → 框变**红色**，顶部弹出红色横幅，记入「告警记录」，并响"滴滴"声；
7. 「检测设置」里可以调语言、检测器、分析帧率、最小告警框面积、对称检测、
   报警声时长、RTP 传输方式与解码方式。

---

## 界面

自上而下：标题栏 → 状态卡片（网络 / 连接状态灯 / 端点信息 / 跟踪统计）→ 画面区
（视频 + 跟踪框覆盖层 + 红色告警横幅）→ 门限卡片（面积比滑杆 + 报警声开关）
→ 控制条（扫描 / 连接 / 断开、手动输入 / 检测设置）→ 信息面板
（发现结果 / 运行日志 / 告警记录 三个页签）。

画面区与信息面板按权重分配剩余高度，屏幕矮时一起收缩，面板可整体收起把空间让给画面。

---

## 工作原理

### 1. 自动发现

不同厂商的记录仪 / 网络摄像头会复用少数几套成熟方案（Novatek、Allwinner、
HiSilicon、SigmaStar、Goke、Ambarella、Realtek…），同一套方案的端口、RTSP/MJPEG
路径和 HTTP 指纹几乎一致。所以"自动发现"不需要认识每一个品牌，只要
**先按方案库给出高概率候选，再用真实握手验证**：

1. **历史记录**：同一 SSID 上次连通过谁，直接验证那一个（毫秒级）；
2. **SSDP**：支持 UPnP 的设备会自己报地址；
3. **端口快扫**：对少量高概率地址（网关 / DNS / 常见网关位）并行做 TCP 连接；
4. **协议握手**：对开放端口发 RTSP `OPTIONS`/`DESCRIBE`（从 SDP 读出编码与分辨率），
   或抓 HTTP 指纹后确认 MJPEG / FLV 路径；
5. **兜底全网段**：前四步颗粒无收时才扫整个 /24。

拿到高置信度结果就提前收敛。

### 2. 取流与渲染

`StreamController` 按协议选择实现，并负责引擎自动回退：

| 协议 | 实现 | 渲染目标 |
|---|---|---|
| RTSP（普通） | ExoPlayer + `RtspMediaSource` | `TextureView` |
| RTSP（要求 Content-Base 命名空间） | 自研 `RtspNativePlayer` | `TextureView` |
| HTTP-FLV / 渐进式 | ExoPlayer + Extractor | `TextureView` |
| HTTP MJPEG | 自研 `MjpegPlayer`（手撕 multipart） | `ImageView` |
| 裸 H.264 / TCP | 自研 `RawH264Player` + `MediaCodec` | `TextureView` |

**为什么不用 `PlayerView`/`SurfaceView`**：SurfaceView 的画面由系统独立图层合成，
应用层拿不到像素，而人体检测必须逐帧取图。所以整条链路统一渲染到 `TextureView`
（`getBitmap()` 可取到当前帧），MediaCodec 也能直接输出到
`Surface(textureView.surfaceTexture)`。

几个实际踩过的坑，代码里都有对应处理：

* **Content-Base**：部分服务器（联咏方案 + LIVE555）要求 `PLAY` 的 URL 按
  `Content-Base` 解析，Media3 一律发到原始会话 URI，会 `404 Stream Not Found`。
  自研通道把 URL 拼对即可。
* **RTCP 保活**：这类服务器收不到任何 RTCP 反馈时约 30 秒会 RST 掉连接，
  所以自研通道每 5 秒回一个最小的 RTCP RR。
* **RTP over TCP 优先**：摄像头 AP 常有客户端隔离或极小的 UDP 缓冲，走 TCP 更稳。
* **MJPEG 背压**：解码在主线程之外，`setImageBitmap` 在主线程；不等 UI 消费就继续
  解码会在低端机上 OOM，所以帧回调做成 suspend 形成背压。
* **解码线程独立**：网络线程只把访问单元丢进有界队列，避免解码节奏反过来卡住读 socket。

### 3. 人体检测与跟踪

* 抓帧在主线程（`TextureView.getBitmap` 要与渲染同步），检测与跟踪在单线程
  Executor（模型推理是 CPU 重活），结果回调统一 post 回主线程；
* 送进检测器的图像最宽 640 px，尺寸只由视频分辨率决定，复用同一块 Bitmap；
* 检测器二选一：TFLite EfficientDet-Lite0（COCO `person` 类，可多人）或
  ML Kit Pose（骨骼关键点外接矩形，单人，无需额外模型文件）；
* `IouTracker` 做贪心 IoU 匹配：上一帧的框与本帧检测框按 IoU 从大到小配对，
  配对成功复用原 ID 并把「本帧面积 / 上帧面积」记下来；未配对的检测新建 ID，
  连续丢失 8 帧的目标删除；高度重叠的重复框会被抑制。

### 4. 面积比判定

```
area  = box.width × box.height
ratio = area(本分析帧) / area(上一分析帧, 同一个 ID)
```

* `ratio ≥ 门限` 且两帧面积都不小于「最小告警框面积」→ 该目标进入告警状态，
  框画成红色，并产生一条 `AlarmEvent`；
* 门限 1.00~5.00、步进 0.01，主界面滑杆实时可调；每帧取一次不可变快照，
  拖滑杆不会让正在推理的那一帧读到写了一半的状态；
* 勾选「对称检测」时改用 `max/min` 作为面积比，面积骤缩同样告警；
* 同一目标两次告警之间有 1.5 秒去抖，避免刷屏。

### 5. 报警声

`AlarmTonePlayer` 用 `AudioTrack` 按样点合成 PCM，而不是播音频文件：

* 需求是"持续 N 秒"，时长能精确到毫秒；不往 APK 里塞 wav/ogg；
* 没有 `SoundPool`/`MediaPlayer` 的启动延迟，报警声该响就立刻响；
* 声音是一个 730 ms 的循环——120 ms 音 + 90 ms 间隔 + 120 ms 音 + 400 ms 静音，
  读起来就是"滴滴……滴滴……"；每个音两端各 5 ms 淡入淡出，避免爆音；
* 写入按 1024 帧（约 23 ms）分块，按"断开"最多几十毫秒就静音；
* 走系统「闹钟」音量（`USAGE_ALARM`），静音 / 勿扰下依然能响；若闹钟音量为 0，
  App 会在运行日志里提示。

### 6. 多语言

* 英文在 `res/values/strings.xml`（默认回退），中文在 `res/values-zh/strings.xml`，
  两个文件 key 完全一致；
* 切换用 `AppCompatDelegate.setApplicationLocales()`（Android 官方 per-app language
  方案，API 21+ 由 AppCompat 兼容），选择结果持久化，重启后第一帧就生效；
* API 33+ 还会出现在系统的「应用语言」设置页（`res/xml/locales_config.xml`）；
* **诊断日志（「运行日志」页签）统一输出英文技术文本**，不随界面语言变化，便于
  对照日志检索与问题反馈。

---

## 可调参数

| 参数 | 位置 | 默认 | 范围 |
|---|---|---|---|
| 面积比门限 | 主界面滑杆 | 1.50 | 1.00 ~ 5.00 |
| 报警声音开关 | 主界面 | 开 | — |
| 报警声时长 | 检测设置 | 3 秒 | 1 ~ 10 秒 |
| 语言 | 检测设置 | 跟随系统 | 系统 / 中文 / English |
| 检测器 | 检测设置 | EfficientDet-Lite0 | 两种 |
| 分析帧率 | 检测设置 | 5 fps | 1 ~ 15 fps |
| 最小告警框面积 | 检测设置 | 400 px² | 100 ~ 5000 px² |
| 对称检测 | 检测设置 | 关 | — |
| 显示跟踪框 | 检测设置 | 开 | — |
| RTP over TCP | 检测设置 | 开 | — |
| 强制软解 | 检测设置 | 关 | — |

参数保存在 `SharedPreferences`（`SettingsStore`）。取流相关开关需要重新连接才生效。

### 怎么选门限

* `1.50` 表示"面积比上一帧大了一半"。想更灵敏调到 `1.20 ~ 1.30`，想更稳调到 `2.00` 以上；
* 画面里远处的人老是误报，就把「最小告警框面积」调大；
* 低端机跑不动 EfficientDet 或只盯一个人时，换成 ML Kit Pose。

---

## 代码结构

```
app/src/main/java/com/hwcamtrack/
├── MainActivity.kt              界面与业务主线
├── TrackApp.kt                  Application：崩溃堆栈留存
├── data/                        端点模型与端点缓存
│   ├── CamEndpoint.kt           地址 + 端口 + 协议 + 编码格式
│   ├── CamProtocol.kt           支持的协议族
│   ├── ProfileCatalog.kt        各方案常用端口、路径、请求头
│   └── EndpointStore.kt         按 SSID 记住上次成功的端点
├── net/                         自动发现与协议握手
│   ├── CamDiscoverer.kt         五步发现引擎
│   ├── SsdpProbe.kt / SocketProbe.kt
│   ├── RtspClient.kt / SdpParser.kt
│   ├── HttpFingerprint.kt
│   ├── H264RtpDepacketizer.kt / RtpPacket.kt / InterleavedReader.kt
│   ├── SpsParser.kt
│   └── NetworkInfoProvider.kt
├── player/                      取流与渲染
│   ├── StreamController.kt      引擎选择 / 回退 / 画面来源
│   ├── ExoStreamPlayer.kt       RTSP、HTTP-FLV、渐进式
│   ├── RtspNativePlayer.kt      自研 RTSP（处理 Content-Base、RTCP 保活）
│   ├── RawH264Player.kt         裸 H.264 over TCP
│   ├── H264AccessUnitDecoder.kt 访问单元组装 + MediaCodec
│   ├── MjpegPlayer.kt           multipart MJPEG
│   └── PlaybackState.kt
├── vision/                      人体检测与跟踪
│   ├── detect/                  PersonDetector 与两种实现
│   ├── track/                   IouTracker（面积比在这里算）/ TrackerModels
│   └── analysis/                AnalysisEngine / FrameSource
├── settings/SettingsStore.kt    全部可调参数
├── audio/AlarmTonePlayer.kt     "滴滴"报警声
├── ui/                          跟踪框覆盖层、画面比例容器、端点列表
└── util/Base64Codec.kt
```

`res/values/strings.xml`（英文）与 `res/values-zh/strings.xml`（中文）成对维护；
`app/src/main/assets/efficientdet_lite0.tflite` 是随包携带的检测模型。

---

## 已知限制

* 跟踪是贪心 IoU 级的多目标跟踪，不做遮挡后重认（ReID）：目标长时间交叉或离开画面再
  回来会分配新 ID，该帧不产生面积比。对"面积比突变告警"这个场景够用。
* IoU 门限 0.25：目标在两帧之间移动过大时可能配不上，提高分析帧率可以缓解。
* H.265 只能走 Media3 通道；自研 RTSP 通道只解 H.264。
* APK 约 120 MB：TFLite 与 ML Kit 各带四套 ABI 的原生库。只在真机发布时可把
  `app/build.gradle.kts` 里的 `abiFilters` 收成 `arm64-v8a` + `armeabi-v7a`，
  体积能砍一半以上。
* 画面区与信息面板按权重分配高度，极端矮屏（< 560 dp）建议收起信息面板。
* 需要在真实摄像头热点下联调才能覆盖各家固件的差异；不同品牌可能需要在
  `ProfileCatalog` 里补一条路径候选。

---

## 第三方组件

| 组件 | 用途 | 许可 |
|---|---|---|
| AndroidX / Material Components | 界面基础 | Apache-2.0 |
| Media3 (ExoPlayer) | RTSP / HTTP-FLV / 渐进式播放 | Apache-2.0 |
| OkHttp | HTTP 探测与长连接取流 | Apache-2.0 |
| TensorFlow Lite Task Library | 人体检测推理 | Apache-2.0 |
| ML Kit Pose Detection | 单人骨骼关键点检测 | ML Kit 服务条款 |
| efficientdet_lite0.tflite | 检测模型 | Apache-2.0（TF Model Garden / MediaPipe） |

---

## 免责声明

本项目用于学习与自建监控场景。请在**你拥有或已获授权**的摄像头上使用；自动扫描只
针对当前所连局域网，请勿在公共网络中使用。
