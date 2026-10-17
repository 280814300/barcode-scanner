# 条码扫描 · 离线多码商品条码识别 App

一款面向手机 / 平板等移动设备的**纯本地、零联网**条码识别工具，专为零售、仓储、盘点和商品管理场景设计。
识别结果只保存在本机，绝不对外上传，符合企业数据本地化合规要求。

> 包名：`com.maoge.barcodescanner` ｜ 最低支持 Android 6.0（API 23）｜ 完全离线运行

---

## 为什么不用网页方案

早期原型基于网页（PWA），但移动端存在两道硬墙：

1. **摄像头安全环境限制**：Android Chrome 仅在 HTTPS 或 localhost 下才放行 `getUserMedia`，直接用 `file://` 打开本地网页即便已授权摄像头也会被拦截。
2. **WebView 不支持摄像头**：把网页套壳成 App，Android WebView 根本无法调用 `getUserMedia` 取流。

因此最终采用 **原生 Android（Kotlin）+ CameraX 相机 + ZXing 离线解码** 重写，安装即用、无需联网下载任何模型。

---

## 功能特性

### 1. 多码同框识别
单个商品常同时带有多种条码（如 **69 码 / EAN-13、SN / Code128、IMEI** 等），且彼此紧邻、处于同一取景区。
基于 CameraX 图像分析 + ZXing 多码解码，可一次性解析画面内**所有**条码，并以青色框实时标注每个码的位置与内容。

### 2. 两种采集模式（顶部切换）
- **手动模式**：直接点选取景框中某个条码，或点击下方对应条码气泡，只采集指定的那一个。
- **批量模式**：点击「识别全部」，把扫描区内**所有条码一次性关联为同一商品的多个条码**。

### 3. 重复标注
启动即加载历史日归档的全部条码值做全局比对；扫描到库中已存在的码，自动标记「🔁 重复」，便于快速发现重复录入或二次到货。

### 4. 本批复制
「复制本批」一键把本次连续扫码采集到的所有条码复制到剪贴板（每行一个），方便整批粘贴到微信、表格或 ERP。

### 5. 按天归档
数据按天落盘为 `barcodes_YYYY-MM-DD.json`（应用私有目录，不外发）。
- 「完成商品并保存」后归档；首页「今日归档」实时展示当天已存商品及其条码。
- 支持导出「今日 CSV / 今日 JSON」，通过系统分享发到微信 / 邮件（CSV 带 BOM，Excel 中文不乱码）。
- 附带：闪光灯开关、手动录入、关键字筛选、清空（带确认）。

---

## 隐私与离线

- 应用**仅申请摄像头权限**，无任何网络权限（安装包权限清单里只有 `CAMERA`）。
- 所有扫码数据仅存于本机存储，导出文件也只生成在本地，全程不出设备。
- 离线解码，不依赖云端识别服务，断网环境同样可用。

---

## 下载与安装

- 在 [Releases](../../releases) 页面下载 `app-release.apk`。
- 将 APK 传到手机（数据线 / 微信文件传输 / U 盘）。
- 手机「设置 → 安全」开启**允许未知来源应用安装**，点击 APK 安装。
- 首次打开请求摄像头权限，允许即可使用。

> 本安装包为自签名 release 版本，可直接覆盖同签名旧版升级。

---

## 从源码构建

### 环境要求
- **JDK 17**（AGP 8.x 不兼容 JDK 21+，请勿用过高版本 JDK）
- Android SDK（Platform 34 + Build-Tools 34）
- 本仓库已内置 Gradle Wrapper，无需另行安装 Gradle

### 步骤
```bash
# 1. 克隆
git clone <本仓库地址>
cd BarcodeApp

# 2. （可选但推荐）配置你自己的签名 keystore
#    复制下方内容到 local.properties（该文件已被 .gitignore 忽略）：
#    barcode.storeFile=你的.keystore
#    barcode.storePassword=***
#    barcode.keyAlias=***
#    barcode.keyPassword=***
#    若不配置，assembleRelease 仍会产出未签名 APK，需自行签名后安装。

# 3. 编译 release
./gradlew assembleRelease

# 产出：app/build/outputs/apk/release/app-release.apk
```

---

## 目录结构

```
BarcodeApp/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/maoge/barcodescanner/
│       │   ├── MainActivity.kt        # 主交互：双模式、重复标注、本批复制、按天归档
│       │   ├── ZxingMultiDecoder.kt    # ZXing 多码离线解码（含坐标）
│       │   ├── BarcodeOverlayView.kt   # 取景框检测框绘制 + 点选命中
│       │   ├── Models.kt               # 数据模型（商品 / 条码 / 当日归档）
│       │   └── DataStore.kt            # 本地 JSON 持久化（按天归档）
│       ├── res/                        # 布局、图标、字符串、主题
│       └── xml/file_paths.xml          # FileProvider 导出分享路径
├── build.gradle / settings.gradle
├── gradle.properties
├── .gitignore
└── README.md / DEVLOG.md
```

---

## 已知限制与后续方向

- **紧邻一维码识别**：69 码 / SN / IMEI 紧贴时，ZXing 多码偶有漏识或误并。如对稳定性要求极高，可平滑替换为 Google ML Kit 条码识别（首次需联网下载一次小型模型，之后离线，识别率与多码稳定性更佳）。
- 当前为自签名 release 包，仅供店内 / 内部自用；若需上架应用商店，请使用正式签名 keystore。

---

## 更新日志

详见 [DEVLOG.md](DEVLOG.md)。

## License

本项目仅供学习与交流使用，遵循 MIT License。
