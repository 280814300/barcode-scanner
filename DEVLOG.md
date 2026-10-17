# 开发日志（DEVLOG）

> 记录「条码扫描」App 从网页原型到原生签名发布的完整演进。所有阶段均坚持一条红线：**数据本地处理、不外发**。

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
