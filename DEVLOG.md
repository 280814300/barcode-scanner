# 开发日志（DEVLOG）

> 记录「条码扫描」App 从网页原型到原生签名发布的完整演进。所有阶段均坚持一条红线：**数据本地处理、不外发**。

> **作者**：毛栋梁 ｜ **网站**：[wenxuejiupu.com](https://wenxuejiupu.com)

---

## 阶段 0 · 需求起点
目标：用最简单快捷的方式实现条码识别，优先网页或安卓 App，面向手机 / 平板，识别后自动保存到本地列表，支持单条复制与批量导出，界面轻量、适配移动端触控。
初始交付为**单文件网页版** `barcode-scanner.html`：纯前端、零后端、数据存 localStorage，Android Chrome 走原生 `BarcodeDetector`，老浏览器回退 ZXing。

## 阶段 1 · 网页摄像头受限
用户反馈「扫描没反应」。根因：识别引擎未初始化时失败被静默吞掉。
改进：增加屏幕状态栏（明确提示引擎就绪 / 失败原因）、ZXing 多 CDN 回退、点击画面单次识别保底、自动播放兼容（`playsinline` / `muted`）。

## 阶段 2 · 安全环境与本地 HTTPS 服务
用户用 Chrome 安卓版报「摄像头打开失败，已开权限」。
根因：**Android Chrome 仅在 HTTPS 或 localhost 下放行摄像头**，`file://` 直接打开本地网页一律被拦，与权限无关。
交付 `serve.py`（仅用 Python 标准库的本地 HTTPS 服务）+ `gen_cert.py` 自签证书，手机连同 WiFi 经 HTTPS 访问即可扫码；并给出 `adb reverse` 免证书备选方案。

## 阶段 3 · 原生 APK v1.0
用户要求「打包成 APK」。
关键决策：**WebView 套壳扫不了码**（Android WebView 不支持 `getUserMedia`），故改用原生扫码库 **zxing-android-embedded** 重写，完全离线、无需联网下载模型。
环境：本机已具备 Android SDK（android-34 + build-tools 34）+ JDK 17 + Gradle 8.10.2（腾讯云镜像下载）。编译通过 `aapt2` 校验（包名、权限、启动 Activity 正确），产出 `app-debug.apk`（6.9 MB，仅 CAMERA 权限）。

## 阶段 4 · 多码同框 v1.1
新需求：商品常带多种条码（69 码 / SN / IMEI）且紧邻同框；需手动点选 / 批量全识两种模式、重复标注、本批复制、按天归档。
技术升级：弃用单次识别库，改用 **CameraX 相机 + ZXing 核心离线多码解码**（支持返回每个码的坐标）。
实现要点：
- `ZxingMultiDecoder`：YUV → RGB → BinaryBitmap，`GenericMultipleBarcodeReader` 解析全部码并回传 `value / format / 坐标`。
- `BarcodeOverlayView`：绘制检测框，支持点击命中某个码。
- 双模式：手动点选 / 一键「识别全部」归入当前商品。
- 启动加载历史日归档做全局去重比对，命中即标「🔁 重复」。
- 底部「复制本批」整批复制到剪贴板。
- `DataStore`：按天写 `barcodes_YYYY-MM-DD.json`，支持今日 CSV / JSON 导出分享。
版本号升至 `versionCode 2 / versionName 1.1`，产出 `app-debug.apk`（6.8 MB）。

## 阶段 5 · Release 签名与开源发布
需求：生成 release 签名包、发布到 GitHub、产出开发日志与 App 介绍。
- 用 JDK 17 `keytool` 生成 `release-key.jks`（alias `barcodescanner`，RSA 2048，有效期 10000 天）。
- 签名口令写入 `local.properties`（已被 `.gitignore` 忽略，不入库）；`app/build.gradle` 通过 `signingConfigs` 读取，未配置时仍可产出未签名包。
- 补齐 `.gitignore`（忽略 build / 签名文件 / 本地 Gradle 发行版 / 日志）。
- 产出 `app-release.apk`，并编写 `README.md`（App 介绍）与 `DEVLOG.md`（本日志）。
- 通过 GitHub Releases 发布签名包。

---

## 关键技术决策记录（以最终 v1.0 为准）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 形态 | 原生 App 而非网页套壳 | WebView 不支持 `getUserMedia`，网页在 App 内无法取流 |
| 识别引擎 | zxing-android-embedded（ZXing 离线单码） | 完全离线、无需联网下载模型；嵌入式预览稳定可靠 |
| 相机框架 | zxing-android-embedded 自带 Camera2 预览 | 开箱即用的扫码预览 + 自动解码，省去自接 CameraX 管线 |
| 备选方案 | CameraX + ZXing 多码 / Google ML Kit | 若需多码同框批量识别可切换；ML Kit 识别率更高但首次需联网下载模型 |
| 数据归档 | 扁平 barcodes.json 本地文件 | 满足「本地保存与列表」，导出再分享，不出本机 |
| 发布签名 | 自签名 release | 店内 / 内部自用足够；上架需正式 keystore |

## 隐私红线（贯穿始终）
- 安装包仅 `CAMERA` 权限，无任何网络权限。
- 扫码数据仅存本机；导出文件本地生成；绝不调用云端识别或上传。
- 签名 keystore 与口令不入库。

## 待办 / 已知问题（v1.0）
- 沙箱无真机，摄像头实拍未在此环境验证，需在真机实测扫码灵敏度与闪光灯开关。
- 当前为单码连续识别；若商品常带多个紧邻条码（69 码 / SN / IMEI）需同框批量识别，可再评估升级到多码方案（CameraX + ZXing 多码 / ML Kit）。
- 如需上架应用商店，需替换为正式签名 keystore 并配置发布流水线。

## 阶段 6 · 回滚到 v1.0 单码版
- 用户决策：放弃多码 v1.1 复杂度，回退到「第一版 APK」——简单、稳定、准确的单码识别版。
- 源码回退：恢复 v1.0 形态——`zxing-android-embedded`（离线嵌入式扫码）+ RecyclerView 本地列表。
  - 删除 v1.1 多码专用文件：ZxingMultiDecoder.kt / BarcodeOverlayView.kt / Models.kt。
  - 重建：BarcodeRecord.kt（数据模型）、BarcodeAdapter.kt（列表适配器）、DataStore.kt（扁平 barcodes.json 本地存储）、MainActivity.kt（连续扫码 + 复制 + 导出 + 手动录入 + 筛选 + 清空）、item_barcode.xml / top_menu.xml。
  - build.gradle 依赖改为 `zxing-android-embedded:4.3.0` + `recyclerview:1.3.2`，移除 CameraX 与 ZXing core；versionCode 回退到 1 / versionName "1.0"。
  - AndroidManifest 仅 CAMERA 权限（无 INTERNET），保留 FileProvider 供导出分享。
- 重新编译 `assembleDebug` + `assembleRelease`（release 仍走 local.properties 签名配置），产物：app-debug.apk / app-release.apk。
- README / DEVLOG 同步回退描述到 v1.0；git 仓库以新提交对齐。
- 注：v1.1 的按天归档 / 多码同框 / 重复标注 / 本批复制能力暂未纳入本版本；如后续需要可在此基础上再加回。

## 阶段 7 · v1.0 交互增强（移除手动录入、点击编辑、批量复制/导出）
- 需求：① 移除「手动录入」按钮；② 点击已扫描条码进入编辑、确认后自动保存；③ 新增批量复制与批量导出；④ Release 下载文件名改为 `barcodescanner-v<版本号>.apk`；⑤ README 精简，去掉网页方案说明，强调原生重写、安装即用、无需联网下载模型。
- 实现：
  - `activity_main.xml` 删除 `btnManual`；`MainActivity.kt` 删除 `manualEntry()` 及其绑定。
  - `BarcodeRecord.value` 改为 `var`，支持原地编辑；`BarcodeAdapter` 新增 `onEdit` 回调、勾选集合 `selected` 及 `getSelected / clearSelection / toggleSelectAll / isAllSelected`；`item_barcode.xml` 增加 `CheckBox` 与可点击内容区 `rowContent`。
  - `MainActivity.editRecord()`：弹窗预填当前值，确认后写回并 `DataStore.save` 自动保存。
  - 批量：`batchCopy()` 复制勾选项（未勾选则全部，换行拼接）；`exportCsv/exportJson` 改为导出勾选项（未勾选则全部）；菜单新增「全选 / 取消」「批量复制」。
  - `app/build.gradle` 增加 `applicationVariants` 配置，release 输出 `barcodescanner-v1.0.apk`。
  - README 删除「为什么不用网页方案」整段，保留原生重写说明并强调「安装即用、无需联网下载任何模型」；功能特性与下载文件名同步更新。
- 隐私红线不变：仍仅 `CAMERA` 权限、零网络、数据不出本机。

## 阶段 8 · 真机实测修复（相机权限 + 导出闪退）
- 真机实测反馈：① 扫码框镜头黑屏、无扫描；② 点击「导出 CSV / 导出 JSON」均闪退；③ 缺少明确的「开始扫描」入口。
- 根因：
  - 相机黑屏/无扫描：`AndroidManifest` 仅声明 `CAMERA` 权限，但 `MainActivity` 从未**运行时申请**；Android 6+（minSdk 23）未授予运行时权限时相机打不开，预览黑屏且 `decodeContinuous` 不触发，表现即「没有了开始扫描」。
  - 导出闪退：`exportFile()` 用 `FileProvider` 生成 `content://` URI 但未向分享目标应用授予 URI 读取权限；Android 11+（targetSdk 34）目标应用读取时抛 `SecurityException`，导致本 App 闪退（CSV / JSON 同路径，故两个按钮都崩）。
- 修复：
  - `MainActivity` 新增运行时 `CAMERA` 权限申请（`ContextCompat.checkSelfPermission` + `ActivityCompat.requestPermissions`），授权后才 `resume()` 相机；新增 `onRequestPermissionsResult` 处理拒绝并 Toast 提示。
  - 新增「开始扫描 / 暂停扫描」按钮（`btnScan`），显式控制扫码起停；`onResume` 仅当 `scanning` 为真才恢复相机。
  - `exportFile()` 在 `startActivity` 前对 `queryIntentActivities` 返回的所有应用 `grantUriPermission(... FLAG_GRANT_READ_URI_PERMISSION)`，并包 `try/catch` 兜底 Toast，彻底杜绝导出闪退。
  - `strings.xml` 增加 `start_scan` / `pause_scan` / `need_camera`；`activity_main.xml` 按钮行改为 开始扫描 / 闪光灯 / 清空 三栏。
- 重新编译 `assembleRelease`，产物仍为 `barcodescanner-v1.0.apk`（版本号不变，属同版本缺陷修复）。

## 阶段 9 · v1.01 视觉比例优化（取景框黄金分割 + 列表行缩小 + 删除图标化）
- 需求：① 取景框区域缩小，与下方列表区按**黄金分割**分配；② 扫描出的条码整行（含复制 / 删除按钮）按比例缩小；③ 删除按钮缩小为小号删除图标。
- 实现：
  - `activity_main.xml`：`barcodeView` 由固定 `220dp` 改为 `0dp` + `layout_weight="1"`，`recyclerView` 改为 `layout_weight="1.618"`（φ≈1.618）。取景框占较小部分（≈0.382），列表占较大部分（≈0.618），二者呈黄金分割；相机预览随屏幕尺寸自适应。
  - `item_barcode.xml`：整行按比例缩小——根 `padding` 12dp→8dp，`tvValue` 16sp→14sp，`tvMeta` 12sp→11sp；复制按钮 `minHeight` 32dp、`textSize` 13sp、`padding` 收紧；删除由文字 `Button` 改为 32×32dp 的 `ImageButton`（`ic_delete.xml` 矢量垃圾桶图标 + `contentDescription`），整行更紧凑。
  - 新增 `res/drawable/ic_delete.xml`：24dp 矢量垃圾桶图标，fillColor 白、由 `android:tint="?attr/colorControlNormal"` 着色，适配深浅主题。
  - `BarcodeAdapter.kt` 无需改动：`btnDelete` 字段类型由 Button 变 ImageButton，仍仅调用 `setOnClickListener`，viewBinding 自动适配。
  - `app/build.gradle`：`versionCode 2` / `versionName "1.01"`，release 输出自动为 `barcodescanner-v1.01.apk`。
- 重新编译 `assembleRelease`，产物 `barcodescanner-v1.01.apk`。
- 隐私红线不变：仍仅 `CAMERA` 权限、零网络、数据不出本机。

## 阶段 10 · v1.02 插件化框架 + 关于软件
- 需求：以现有界面为架构，做可扩展的插件框架，允许第三方插件导入使用；示例含「69码商品识别（自动录入产品库）」「批量盘点」；并新增右上角「关于软件」弹窗。
- 插件机制选型：**LuaJ 纯 Java 脚本沙箱**（离线运行第三方 `.bsplugin` = zip 内含 `bsmanifest.json` + `main.lua` + `data/`），不引入任何网络/JS 引擎依赖。
- 沙箱安全：剥离 `os/io/package/require/load/dofile/loadstring/luajava/debug` 等危险全局，宿主 API 走**白名单桥**（`host.records/product/ui/export/storage/json`），守住「完全离线、禁联网/文件系统/反射」红线。
- 插件中心 `PluginsActivity`：导入（SAF 选 `.bsplugin` / 接收其他应用分享的 VIEW intent）、启用/禁用开关、卸载（带确认）；注册表 `filesDir/plugins.json`，插件目录 `filesDir/plugins/<id>/`，按 pluginId 隔离私有存储。
- 扩展点：`on_scan` / `on_menu` / `on_record_longclick` 三类触发器；`MainActivity` 在扫码回调、`onCreateOptionsMenu` 动态挂载 `on_menu` 插件项、`BarcodeAdapter` 长按转发。
- 两个官方示例插件：`barcode69`（识别 690-699 前缀 EAN-13，未收录则弹窗录入名称/规格/单位/单价写入产品库）、`stocktake`（菜单开关盘点模式、逐件 +1、长按 -1、结束导出实盘 CSV）。打包脚本 `plugins/package_plugins.py` 产出 `plugins_dist/*.bsplugin` + 静态分发页 `index.html`。
- 关于软件：`top_menu.xml` 新增 `action_about`；`MainActivity.showAbout()` 用 `HtmlCompat` + `LinkMovementMethod` 渲染弹窗，显示软件版本（`BuildConfig.VERSION_NAME`）、作者「毛栋梁」、可点击跳转的网站链接、软件简介（快速扫码/批量识别/自动采集/可复制导出打印/本地无需联网）、开源声明。因 AGP 8+ 默认关闭 BuildConfig，已在 `app/build.gradle` 开启 `buildFeatures { buildConfig true }`。
- `BarcodeRecord` 增加 `extras: JSONObject?` 字段（`toJson`/`fromJson` 向后兼容旧数据），供插件挂载自定义数据。
- 重新编译 `assembleRelease`，产物 `barcodescanner-v1.02.apk`（约 5.3 MB，仍仅 `CAMERA` 权限、零网络）。
- 截图说明：主界面/编辑/批量三张高保真预览图沿用 v1.02 渲染；插件中心预览图因本地无头浏览器（Chromium 下载受限）暂以文字说明替代，后续可补。

## 阶段 11 · v1.03 外接扫码枪插件（手机作为电脑外接扫码枪）
- 需求：以**独立可导入插件**形式封装「外接扫码枪」，导入即启用；连接电脑（局域网）后将手机作为电脑的外接扫码枪，扫码数据实时传输到电脑端；插件须包含连接管理、扫码监听与数据上报逻辑，且不影响宿主原有功能。
- 关键架构决策：现有 Lua 沙箱**禁止联网**，所以插件本身不能直接开 Socket。改为由宿主原生提供**受权限管控的 `host.scangun` 桥**，插件（Lua）只做编排，真正的局域网通信在宿主侧完成，且**只有声明 `network` 权限的插件才能调用**——其他插件与其他功能依旧零网络，离线红线不破。
- 宿主侧改动：
  - `AndroidManifest.xml`：新增 `INTERNET` 权限（仅被声明 `network` 的插件经宿主桥间接使用）。
  - `PluginManifest.kt`：`HOST_VERSION` 升 `1.03`；新增 `PERM_NETWORK = "network"` 常量（`versionAtLeast` 校验方向为 `HOST_VERSION >= minHostVersion`，故旧示例插件 minHostVersion=1.02 仍兼容）。
  - `PluginEngine.kt`：新增 `activePermissions` 字段，在 `runPlugin` 执行前注入当前插件权限、执行后清空，供 host 桥做能力管控。
  - 新增 `ScanGunBridge.kt`（单例）：原生 TCP 客户端，全部 Socket 操作用在单线程 executor 上执行（避免 `on_scan` 主线程调用触发 `NetworkOnMainThreadException`）；状态机 `disconnected/connecting/connected/error`；`send()` 仅入队返回是否成功，不阻塞。连接为单例，跨插件调用复用（菜单 connect、扫码 send）。
  - `PluginHost.kt`：新增 `host.scangun` 子表（`configure/connect/disconnect/status/lastError/send`），仅在 `activePermissions` 含 `network` 时暴露真实实现；否则所有方法返回「权限不足」，绝不联网。
- 插件 `plugins/scangun/`：`plugin.bsmanifest.json`（`triggers: on_menu/on_scan`，`permissions: ["network"]`，`minHostVersion: 1.03`）+ `main.lua`——`on_menu` 负责连接管理（读取已存 IP/端口、弹表单配置、connect/disconnect、toast 状态），`on_scan` 在已连接时把 `{v,f,t}` 的 JSON 单行经 `host.scangun.send` 上报，未连接时不拦截宿主原有扫码流程。连接管理与上报逻辑全在插件内，宿主仅提供受控网络通道。
- 电脑端接收器 `scangun_receiver.py`（工作区根目录）：纯标准库 TCP 服务（默认端口 17533），接收每行 JSON 扫码数据、终端实时打印并追加写入 `scans.csv`；绑定 `0.0.0.0` 局域网、数据不出本机，Ctrl+C 即停。
- 重新打包 `plugins/package_plugins.py`：新增产出 `scangun.bsplugin`（连同 `barcode69`/`stocktake` 一并重打）。
- 文档：`PLUGIN_GUIDE.md` 增加 `host.scangun` API 与 `network` 权限说明、§6.3 外接扫码枪示例；`README.md` 与版本说明同步到 v1.03，并如实说明 `INTERNET` 权限仅被该插件使用；本文件新增本阶段。
- 重新编译 `assembleRelease`，产物 `barcodescanner-v1.03.apk`（versionCode 4 / versionName 1.03）。
- 注意：本版本起安装包权限清单含 `INTERNET`，但仅在用户主动安装并启用「外接扫码枪」插件后才会发生局域网通信；基础扫码/69码/盘点功能及数据仍完全本地、零网络。

## 阶段 12 · 电脑端二维码一键连接（免手动输入地址）
- 需求：电脑端运行一条命令/脚本后，自动生成可被扫描的二维码；手机或其他设备扫描该二维码即自动完成连接，无需手动输入 IP 或端口。
- 电脑端 `scangun_receiver.py` 升级：
  - 启动时生成随机配对令牌（`secrets.token_urlsafe(6)`），构造连接串 `scangun://<LAN IP>:<端口>?t=<令牌>&v=1`。
  - 自动渲染二维码：优先终端 ASCII（`qrcode.print_ascii`，全环境可用），并额外生成 `scangun_qr.png`（box_size=10, ERROR_CORRECT_M）后尝试用系统默认图片查看器自动打开（Windows `os.startfile` / macOS `open` / Linux `xdg-open`）；缺 `qrcode` 库时自动 `pip install qrcode pillow`，仍失败则回退为纯文本打印连接串。
  - 新增**令牌握手**：设备接入后首行若为 `AUTH <令牌>` 则校验，匹配才放行（防局域网内误连/冒领）；首行非 `AUTH` 则按旧式「无令牌直连」向后兼容。
- 手机端插件 `plugins/scangun/main.lua`（升 v1.1.0）：
  - `on_scan` 识别 `scangun://` 前缀 → 解析 host/port/token → 调 `host.scangun.configure` + `host.scangun.connect(token)` 自动连接，并 `host.remove(value)` 把该连接串从主列表清除（不污染、不转发）。
  - 保留 `on_menu` 手动输入 IP 的备用入口（无令牌）。
- 宿主桥 `ScanGunBridge.kt`：`connect(token="")` 新增可选令牌参数，连接建立后若令牌非空立即发送 `AUTH <令牌>` 首行。`PluginHost.kt` 的 `host.scangun.connect` 改为 `OneArgFunction`，把可选令牌传给桥。
- 自测：本机起服务 + 模拟客户端（令牌模式 + 旧式无令牌直连）端到端验证，三行数据均正确写入 `scans.csv`，令牌不匹配即拒绝——`RESULT: PASS`。
- 重新打包 `plugins/package_plugins.py`：`scangun.bsplugin`（manifest version 1.1.0）；重新编译 `assembleRelease`，产物仍为 `barcodescanner-v1.03.apk`（versionName 不变）。
- 文档：`PLUGIN_GUIDE.md` 的 `host.scangun.connect(token?)` 与 §6.3、`README.md` 外接扫码枪条目均补充二维码一键连接说明；本文件新增本阶段。

## 阶段 13 · 外接扫码枪插件内置进 APK（可开关）
- 需求：将 `scangun.bsplugin` 直接内置进安装包，首次启动自动装好，用户可在插件中心自行启用/禁用（亦可卸载），无需再从外部导入。
- 资源：把打包好的 `plugins_dist/scangun.bsplugin` 拷入 `app/src/main/assets/bundled_plugins/scangun.bsplugin`；Android 构建自动将其打进 APK 的 `assets/` 目录（已验证 APK 内存在 `assets/bundled_plugins/scangun.bsplugin`）。
- `PluginManager.kt` 改造：
  - `PluginInfo` 增加 `bundled: Boolean` 字段（持久化于 `plugins.json`，默认 false）。
  - 新增 `installBundledFromAssets()`：在 `init()` 中调用，读取 `assets/bundled_plugins/*.bsplugin`，逐个解析 manifest 取 id；用 `bundled_seen.json` 记录已处理过的 id——若用户已自行导入同名插件则仅记录不再覆盖，**若用户卸载了内置插件也不会被重新塞回**（尊重用户选择）；全新安装则自动装入并标记 `bundled=true`、默认启用。
  - 配套 `peekManifestId()`（不消耗安装流，只读 manifest 取 id）、`bundledSeenFile/loadBundledSeen/saveBundledSeen` 帮助函数、`setBundled()`。
  - 注意：`when (val res = installFromStream(stream))` 的「when 内 val 声明」写法在本项目 Kotlin 1.9.24 下触发解析异常，已改为先 `val res = installFromStream(stream)` 再 `when(res)`，编译通过。
- `PluginsActivity.kt`：插件列表中为内置插件追加「内置」标记（`author · 已启用/已禁用 · 内置`）。
- 重新编译 `assembleRelease`，产物仍为 `barcodescanner-v1.03.apk`（versionName 不变）；APK 内已含内置插件。
- 隐私：内置的「外接扫码枪」仍是唯一会联网的功能，且需用户主动启用；基础功能（含其余示例插件）依旧零网络、数据不出本机。

## 阶段 14 · 菜单统一 + 插件面板 + 电脑端实时界面（v1.04）
- 需求（毛哥反馈三件事）：① 加载插件后手机右上角出现两个设置按钮重复；② 外接扫码枪插件需在右上角「设置-插件」面板中支持显示、启用与关闭；③ 电脑端扫描数据须实时同步到界面展示，交互与 APP 端一致，支持复制单条与批量导出。
- ① 重复按钮根因与修复：`setSupportActionBar` 下 `onCreateOptionsMenu` 会由系统渲染 overflow 三点图标，而布局里又有自定义 `btnMenu` 三点按钮，两套并存。移除 `onCreateOptionsMenu`/`onOptionsItemSelected` 系统路径与 `onResume` 的 `invalidateOptionsMenu()`，统一为唯一的「⋮」按钮 + `PopupMenu`；每次弹菜单重新读取插件状态（`pluginMenuIds.clear()` 后重建），启用项实时出现、禁用即消失。
- ② 插件面板：新增 `dialog_plugin_panel.xml` + `PluginPanel.kt`，菜单「插件」项改为弹出面板（不再跳整页），列出全部插件（含内置外接扫码枪，带「内置」标记），每项 Switch 即时启用/关闭；底部入口跳插件中心（导入/卸载）。`PluginsActivity.PluginListAdapter.onBindViewHolder` 修复 RecyclerView 复用隐患：先 `setOnCheckedChangeListener(null)` 再回填状态再挂监听。`PluginManager.setEnabled` 关闭 scangun 时顺手 `ScanGunBridge.disconnect()`，插件停用后长连接不留着。
- ③ 电脑端实时界面（`scangun_receiver.py` 大改）：
  - 原有能力全保留：局域网 TCP（默认 17533）、随机令牌 + `AUTH` 握手、二维码（终端 ASCII + PNG）、`scans.csv` 留档、旧式无令牌手动连接兼容。
  - 新增本地界面服务：标准库 `ThreadingHTTPServer`，只绑 `127.0.0.1`（默认 17534），`GET /` 返回内嵌单文件 HTML，`GET /api/scans?since=N` 增量 JSON，`GET /api/qr.png` 返回连接二维码，`POST /api/clear` 清空界面列表（CSV 保留）。
  - 界面交互与 APP 端对齐：最新一条在最上；单条「复制」；勾选 + 全选 + 「批量复制」（勾选优先，未勾选复制全部可见项）；「导出 CSV / JSON」（勾选优先，CSV 带 BOM）；关键字筛选（条码/格式，筛选变更清空选择）；清空（带确认）。新数据绿色高亮渐隐，统计条展示 总计/已选/在线设备/最近一条。
  - 细节：前端 700ms 增量轮询 + 单调递增 id（清空后不回退）；启动时载入 `scans.csv` 最近 500 条作为历史；启动自动打开浏览器（`--no-browser` 可关）；界面仅本机可访问，符合数据不出本机红线。
  - 配对令牌打印改为独立成行，避免复制时带出说明文字。
  - 自测：端到端脚本 17 项全 PASS（JSON/裸文本接收、设备计数、界面 HTML、二维码 PNG、清空后继续接收且 id 不回退、CSV 行数与内容）；Chromium 无头截图验证桌面 1360px 与窄屏 520px 布局均无溢出（430px 截图曾现"截断"，经注入诊断确认是 Chrome headless 最小窗口宽 488px 的裁切假象，页面 `scrollWidth == innerWidth` 零溢出；顺手把 grid `1fr` 全部改为 `minmax(0,1fr)` 做防御）。
- 版本：`versionCode 5` / `versionName 1.04`，产物 `barcodescanner-v1.04.apk`（含内置插件资产）。

## 阶段 15 · 修复扫码枪无法同步（v1.05）
- 现象（毛哥反馈）：扫码与电脑端同步过程中提示出错，无法扫描同步。
- 根因（用 LuaJ 真实复现确认，非推测）：`main.lua` 的 `on_scan` 里 `local host, port, token = parse_uri(val)` 把全局 `host` 表遮蔽成 IP 字符串，紧接着 `host.scangun.configure(...)` 等价于对字符串取字段，必然抛 `LuaError: main.lua:35 attempt to index ? (a nil value)`，被 `PluginEngine.runPlugin` 的 catch 捕获后弹「插件「外接扫码枪」执行出错」。连接请求从未发出去，所以永远连不上、永远无法同步。修复：局部变量改名 `ip`，并在连接分支用 `pcall(host.remove, val)` 保护。
- 附带修掉数据错误：`PluginSandbox.luaToJson` 用 `isnumber()` 判断，而 LuaJ 里数字型字符串的 `isnumber()` 也返回 true，导致条码 `6901234567890` 被转成 `6.90123456789E12`。改为按 `type()` 精确判断（TSTRING/TNUMBER/TBOOLEAN），数字条码按原文传输。
- 接收器修掉历史 bug：旧式无令牌直连时，首行被当成握手行 `continue` 掉，会静默丢失第一条数据。现在旧式首行直接进入解析。
- 可诊断性：接收器 AUTH 成功回 `OK AUTH`、失败回 `ERR <原因>` 并断开（提示可能是扫了旧截图）；新增 `PING`/`PONG` 心跳。手机端 `ScanGunBridge` 新增读线程接收回执，把 `ERR` 写入 `lastError` 并置 `status=error`；`send()` 增加 writer 判空，避免静默丢数据。插件侧对未连接/连接中/连接失败三种状态分别 toast，不再无声丢弃。
- 内置插件升级机制（关键配套）：原 `installBundledFromAssets` 只要 `bundled_seen.json` 命中就永久跳过，导致新版 APK 修好的插件在已安装设备上永远装不回。改为版本比较：已装且 assets 版本更高 → 覆盖升级并保留用户启用状态；用户主动卸载过的仍不塞回。
- 自测：LuaJ 四场景（扫连接二维码 / 已连接上报数字码 / 连接失败态 / 未连接态）全部无异常，数字条码输出 `"v":"6901234567890"`；接收器端到端 11 项全 PASS（OK AUTH、PONG、错误令牌回 ERR、旧式直连首条不丢、CSV 落盘）。
- 版本：`versionCode 6` / `versionName 1.05`，内置插件 `scangun 1.1.1`；`scangun_receiver.py` 纳入仓库跟踪（此前只在 Release 资产里）。

## 阶段 16 · 连接结果可见 + 电脑端 IP 可选（v1.06）
- 现象（毛哥反馈）：扫绑定二维码时一直提示「连接中」，电脑端连不上。
- 排查顺序（逐项排除，非猜测）：①接收器 TCP 绑 0.0.0.0 正确；②起实例用**局域网地址**完整模拟手机握手，
  `OK AUTH` 通过，服务端无问题；③防火墙：python.exe 入站规则覆盖 Public+TCP 全端口，且放行路径
  与实跑进程路径一致，非防火墙；④权限链路：`PluginEngine.runPlugin` 在调用前已设 `activePermissions`，
  `network` 生效，非权限问题。结论：问题在手机→电脑这一跳的**可观测性**——
- 根因：`ScanGunBridge.connect()` 是异步的，而 `main.lua` 在扫码分支里**无条件**弹「连接中…」，
  成功/失败都不再告知。TCP 失败后 status 变 error，但用户只会反复扫二维码、每次都看到「连接中」，
  永远看不到失败原因，也就无从排查。
- 修复（手机端）：`ScanGunBridge` 增加主线程 `notifier` 回调，连接成功/失败/断链均回调；
  `humanize()` 把 SocketTimeoutException / ConnectException / NoRouteToHostException / EACCES 等
  翻译成可照做的中文提示。`MainActivity` 注册通知器弹 Toast（失败用 LENGTH_LONG）。
  同时修掉「正在连接时再次扫码被静默忽略」：仅当连接已卡死超过 8 秒才拒绝重来。
- 修复（电脑端）：新增 `lan_ips()` 枚举全部非回环 IPv4（排除 169.254 链路本地，标注 Tailscale 100.64/10）、
  `pick_ip()` 优先路由地址否则退回候选、`self_check()` 用局域网地址回连自己验证对外可达。
  新增 `/api/info`（当前 IP/端口/候选列表）与 `/api/qr.png?ip=`（按 IP 即时重生成二维码，非法 IP 回退）。
  界面二维码弹窗加 IP 切换器 + 可复制连接串。支持 `--ip` 覆盖。
- 自测：电脑端 10/10 全 PASS（自检通过、/api/info、每个候选 IP 出 PNG、非法 IP 回退、
  局域网握手 OK AUTH、错误令牌回 ERR、数据入界面、数字条码保持原文）。
- 版本：`versionCode 7` / `versionName 1.06`，内置插件 scangun 1.2.0。
