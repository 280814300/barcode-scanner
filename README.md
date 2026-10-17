# 条码扫描 · 离线条码识别 App（v1.01 单码版）

一款面向手机 / 平板等移动设备的**纯本地、零联网**条码识别工具，用于零售、仓储、盘点等场景。
识别结果只保存在本机，绝不对外上传，符合企业数据本地化合规要求。

> 包名：`com.maoge.barcodescanner` ｜ 最低支持 Android 6.0（API 23）｜ 完全离线运行

**作者**：文学酒铺 ｜ **网站**：[wenxuejiupu.com](https://wenxuejiupu.com)

---

## 功能特性（v1.01）

- **单码实时识别**：基于 `zxing-android-embedded`（ZXing）的嵌入式相机预览，连续自动解码，识别即入列表。
  支持常见一维/二维码：EAN-13、EAN-8、Code128、Code39、UPC、QR 等。
- **本地列表**：识别结果自动写入本机 `barcodes.json`，带格式与时间，启动即加载历史。
- **点击编辑**：点击任意已扫描条码即可进入编辑，修改后确认自动保存。
- **复制单条**：每条「复制」按钮一键复制到剪贴板。
- **批量选择 / 批量复制 / 批量导出**：勾选多条后，顶部菜单「批量复制」一键复制全部到剪贴板；「导出 CSV / 导出 JSON」仅导出勾选项（未勾选则导出全部），通过系统分享发到微信 / 邮件（CSV 带 BOM，Excel 中文不乱码）。
- **辅助操作**：闪光灯开关、关键字筛选、清空（带确认）、单条删除、全选切换。

## 界面预览

> 以下截图来自 v1.01 真机界面（高保真还原）。

**主界面（扫码 + 本地列表）**

![主界面](https://github.com/280814300/barcode-scanner/releases/download/v1.01/shot-main.png)

**点击条码编辑**

![编辑条码](https://github.com/280814300/barcode-scanner/releases/download/v1.01/shot-edit.png)

**批量勾选 + 导出菜单**

![批量操作](https://github.com/280814300/barcode-scanner/releases/download/v1.01/shot-batch.png)

## 技术选型

采用**原生 Android（Kotlin）+ zxing-android-embedded** 重写：**安装即用、无需联网下载任何模型**，完全离线运行。

## 隐私与离线

- 应用**仅申请摄像头权限**，无任何网络权限（安装包权限清单里只有 `CAMERA`）。
- 所有扫码数据仅存于本机存储，导出文件也只生成在本地，全程不出设备。
- 离线解码，不依赖云端识别服务，断网环境同样可用。

---

## 下载与安装

- 在 [Releases](../../releases) 页面下载 `barcodescanner-v1.01.apk`。
- 将 APK 传到手机（数据线 / 微信文件传输 / U 盘）。
- 手机「设置 → 安全」开启**允许未知来源应用安装**，点击 APK 安装。
- 首次打开请求摄像头权限，允许即可使用。

> 本安装包为自签名 release 版本，可直接安装 / 覆盖升级。

---

## 从源码构建

### 环境要求
- **JDK 17**（AGP 8.x 不兼容 JDK 21+）
- Android SDK（Platform 34 + Build-Tools 34）
- 本仓库已内置 Gradle Wrapper，无需另行安装 Gradle

### 步骤
```bash
git clone <本仓库地址>
cd BarcodeApp

# （可选）配置你自己的签名 keystore：复制以下内容到 local.properties（该文件已被 .gitignore 忽略）
#   barcode.storeFile=你的.keystore
#   barcode.storePassword=***
#   barcode.keyAlias=***
#   barcode.keyPassword=***
# 若不配置，assembleRelease 仍会产出未签名 APK，需自行签名后安装。

./gradlew assembleRelease        # 或 assembleDebug
# 产出：app/build/outputs/apk/release/barcodescanner-v1.01.apk
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
│       │   ├── MainActivity.kt        # 主交互：连续扫码、复制、编辑、批量复制、批量导出、清空
│       │   ├── BarcodeRecord.kt       # 数据模型
│       │   ├── BarcodeAdapter.kt      # RecyclerView 列表适配器
│       │   └── DataStore.kt           # 本地 JSON 持久化
│       ├── res/                        # 布局、图标、字符串、主题、菜单、FileProvider 路径
│       └── xml/file_paths.xml
├── build.gradle / settings.gradle
├── gradle.properties / .gitignore
└── README.md / DEVLOG.md
```

---

## 版本说明

- **v1.01（当前）**：在 v1.0 基础上优化视觉比例——取景框与列表区按黄金分割分配，列表行整体缩小，删除按钮改为小号图标，界面更紧凑。功能同 v1.0：单码实时识别 + 本地列表 + 复制/编辑/批量复制/批量导出/筛选/清空，完全离线。
- 多码同框、按天归档、重复标注等增强能力曾在内部迭代过，本版本聚焦「稳定、准确、简单」的单码场景。
  若你的商品常带多个紧邻条码（69 码 / SN / IMEI）需要同框批量识别，可再评估升级方案。

## 作者与网站

- **作者**：文学酒铺
- **网站**：[https://wenxuejiupu.com](https://wenxuejiupu.com)

如果你觉得这个工具好用，欢迎到网站逛逛，或在本仓库提 Issue / PR。

## License

本项目仅供学习与交流使用，遵循 MIT License。
