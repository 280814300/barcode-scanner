# 条码扫描 · 离线条码识别 App（v1.05 插件版）

一款面向手机 / 平板等移动设备的**纯本地、零联网**条码识别工具，用于零售、仓储、盘点等场景。
识别结果只保存在本机，绝不对外上传，符合企业数据本地化合规要求。

> 包名：`com.maoge.barcodescanner` ｜ 最低支持 Android 6.0（API 23）｜ 完全离线运行

**作者**：毛栋梁 ｜ **网站**：[wenxuejiupu.com](https://wenxuejiupu.com)

---

## 功能特性（v1.05）

- **统一菜单 + 插件面板**：右上角仅保留一个「⋮」设置按钮（修复了此前系统 overflow 与自定义按钮并存的重复问题）。点「插件」直接弹出插件面板，无需跳页即可查看、启用、关闭每个插件（含内置外接扫码枪）；导入与卸载仍走插件中心。
- **插件扩展**：支持以 Lua 脚本插件扩展功能，第三方插件也可导入使用。详见 [PLUGIN_GUIDE.md](./PLUGIN_GUIDE.md)。官方示例插件：`外接扫码枪` 已**随 APK 内置**（首次启动自动安装，可在插件面板/插件中心启用/禁用/卸载）；`69码商品识别`、`批量盘点` 仍为可导入的示例插件。
- **外接扫码枪（需联网权限，已内置）**：`外接扫码枪` 插件随安装包内置，首次启动自动装入并默认启用。**电脑上运行 `scangun_receiver.py` 即自动生成连接二维码并打开实时采集界面**：手机扫描二维码一键连接，此后每扫一条条码，电脑浏览器界面即时出现该数据（界面仅绑定本机 127.0.0.1，别的设备访问不到），交互与手机端一致——单条复制、勾选批量复制、全选、导出 CSV / JSON（勾选优先，未勾选导出全部）、关键字筛选、清空；数据同时追加写入 `scans.csv`。该插件是**唯一**会启用网络的功能，且基础功能仍完全离线。
- **单码实时识别**：基于 `zxing-android-embedded`（ZXing）的嵌入式相机预览，连续自动解码，识别即入列表。
  支持常见一维/二维码：EAN-13、EAN-8、Code128、Code39、UPC、QR 等。
- **本地列表**：识别结果自动写入本机 `barcodes.json`，带格式与时间，启动即加载历史。
- **点击编辑**：点击任意已扫描条码即可进入编辑，修改后确认自动保存。
- **复制单条**：每条「复制」按钮一键复制到剪贴板。
- **批量选择 / 批量复制 / 批量导出**：勾选多条后，顶部菜单「批量复制」一键复制全部到剪贴板；「导出 CSV / 导出 JSON」仅导出勾选项（未勾选则导出全部），通过系统分享发到微信 / 邮件（CSV 带 BOM，Excel 中文不乱码）。
- **辅助操作**：闪光灯开关、关键字筛选、清空（带确认）、单条删除、全选切换。
- **关于软件**：右上角「⋮」菜单 →「关于软件」可查看软件版本、作者署名、网站链接（可点击跳转浏览器）、软件简介与开源声明。

## 界面预览

> 手机端截图来自真机界面（高保真还原）；电脑端截图为 v1.04 实时采集界面。

**主界面（扫码 + 本地列表）**

![主界面](https://github.com/280814300/barcode-scanner/releases/download/v1.04/shot-main.png)

**点击条码编辑**

![编辑条码](https://github.com/280814300/barcode-scanner/releases/download/v1.04/shot-edit.png)

**批量勾选 + 导出菜单**

![批量操作](https://github.com/280814300/barcode-scanner/releases/download/v1.04/shot-batch.png)

**电脑端实时采集界面（v1.04 新增）**

![电脑端界面](https://github.com/280814300/barcode-scanner/releases/download/v1.04/shot-receiver-desktop.png)

**插件中心（导入 / 启用 / 卸载 / 分享接收）**

> 截图待补充。插件中心支持从本机导入 `.bsplugin` 插件包，或接收其他应用分享的插件；可单独启用/禁用、卸载，并在右上角菜单挂载插件的自定义入口。

## 技术选型

采用**原生 Android（Kotlin）+ zxing-android-embedded** 重写：**安装即用、无需联网下载任何模型**，完全离线运行。

## 隐私与离线

- 应用基础功能**仅使用摄像头权限**；为支持可选的「外接扫码枪」插件，安装包声明了 `INTERNET` 权限，但该权限**仅被声明了 `network` 权限的该插件通过宿主桥间接使用**，基础功能与另外两个官方插件（69码、盘点）依旧零网络、数据不出本机。
- 所有扫码数据仅存于本机存储，导出文件也只生成在本地，全程不出设备。
- 离线解码，不依赖云端识别服务，断网环境同样可用。

---

## 下载与安装

- 在 [Releases](../../releases) 页面下载 `barcodescanner-v1.05.apk`。
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
# 产出：app/build/outputs/apk/release/barcodescanner-v1.05.apk
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
│       │   ├── DataStore.kt           # 本地 JSON 持久化
│       │   ├── PluginsActivity.kt     # 插件中心：导入/启用/禁用/卸载/分享接收
│       │   └── plugin/                # 插件框架：Manifest/Manager/Sandbox/Host/Engine
│       ├── res/                        # 布局、图标、字符串、主题、菜单、FileProvider 路径
│       └── xml/file_paths.xml
├── plugins/                           # 示例插件源码（.bsplugin 由脚本打包）
├── plugins_dist/                      # 打包产物 + 静态分发页模板
├── build.gradle / settings.gradle
├── gradle.properties / .gitignore
└── README.md / DEVLOG.md
```

---

## 版本说明

- **v1.05（当前）**：修复外接扫码枪**连不上电脑、无法同步**的致命 bug——插件里 `local host, port, token = parse_uri(val)` 遮蔽了全局 `host` 表，导致扫电脑二维码时 `host.scangun.configure(...)` 实际是在索引一个字符串，必然抛「插件执行出错」，连接请求从未发出。同时修复：纯数字条码被序列化成科学计数法（`6901234567890` → `6.90123456789E12`，已改为按 Lua 真实类型判断）；旧式无令牌直连会吞掉第一条数据。体验改进：未连接/连接中/连接失败三种状态下扫码都有明确提示；电脑端把鉴权结果回传手机（令牌不对会提示「请重新扫描电脑上的最新二维码」），新增 PING/PONG 心跳；内置插件改为按版本比较自动升级——此前只要装过一次就永久跳过，修好的新版插件永远装不回。内置扫码枪版本 1.1.1。
- **v1.04**：修复右上角重复设置按钮——移除系统 options menu 路径，统一为单一的「⋮」按钮 + PopupMenu（此前系统 overflow 图标与自定义按钮并存，加载插件后尤为明显）。右上角菜单新增「插件面板」：弹出式直接查看、启用、关闭每个插件（含内置外接扫码枪，带「内置」标记），关闭外接扫码枪时自动断开网络长连接；导入/卸载仍走插件中心。电脑端 `scangun_receiver.py` 升级为「实时采集界面」：新增仅绑定 127.0.0.1 的本地网页界面（零外部依赖、标准库实现、自动打开浏览器），扫码数据实时上屏，交互与手机端对齐（单条复制、勾选批量复制、全选、导出 CSV/JSON、筛选、清空），启动时自动载入 CSV 最近 500 条历史；二维码、令牌握手、`scans.csv` 留档与旧式手动连接全部保留。
- **v1.03**：新增「外接扫码枪」插件——以独立可导入的插件形式封装，导入即启用；通过宿主提供的受 `network` 权限管控的 `host.scangun` 桥（声明式权限 + 宿主原生 TCP 客户端），将手机作为电脑外接扫码枪，扫码数据实时发往电脑端接收器（配套 `scangun_receiver.py`）。沙箱仍禁网，仅声明 `network` 的插件可间接联网，离线红线不破；安装包新增 `INTERNET` 权限但仅被该插件使用。宿主版本号 `HOST_VERSION` 升至 1.03。
- **v1.02**：新增插件扩展框架——Lua 脚本沙箱（白名单 API、禁联网/文件/反射，守住离线底线）+ 插件中心（导入/启用/禁用/卸载/分享接收）+ 两个官方示例插件（69码商品识别、批量盘点）；右上角菜单新增「关于软件」弹窗（版本/作者/网站链接/简介/开源声明）。界面延续 v1.01 黄金分割布局。
- v1.01：优化视觉比例——取景框与列表区按黄金分割分配，列表行整体缩小，删除按钮改为小号图标。
- v1.0：单码实时识别 + 本地列表 + 复制/编辑/批量复制/批量导出/筛选/清空，完全离线。
- 多码同框、按天归档、重复标注等增强能力曾在内部迭代过，本版本聚焦「稳定、准确、简单」的单码场景。
  若你的商品常带多个紧邻条码（69 码 / SN / IMEI）需要同框批量识别，可再评估升级方案。

## 作者与网站

- **作者**：毛栋梁
- **网站**：[https://wenxuejiupu.com](https://wenxuejiupu.com)

如果你觉得这个工具好用，欢迎到网站逛逛，或在本仓库提 Issue / PR。

## License

本项目仅供学习与交流使用，遵循 MIT License。
