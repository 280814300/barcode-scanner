# 条码扫描 App 插件开发指南（v1.03）

本应用从 v1.02 起支持**插件扩展**。任何人都可以用 Lua 脚本编写插件，扩展扫码后的处理逻辑（如商品识别、批量盘点、外接扫码枪），并以 `.bsplugin` 包的形式分发给其他人导入使用。

插件运行在**受限沙箱**中：无法访问网络、文件系统、Java 反射或动态加载代码，从根上保证应用"完全离线、数据不出本机"的隐私底线。唯一的例外是：声明了 `network` 权限的插件，可通过宿主提供的 `host.scangun` 桥**间接**发起局域网通信（真正的 Socket 由宿主原生代码持有，插件拿不到原始网络能力）。这是目前唯一会启用网络的能力，且必须用户主动安装并启用了对应插件才会生效。

---

## 1. 插件包结构

一个插件就是一个 zip 压缩包，扩展名建议为 `.bsplugin`，内部至少包含：

```
your_plugin.bsplugin
├── plugin.bsmanifest.json   # 必须：插件清单（身份 + 入口 + 触发点 + 权限声明）
├── main.lua                # 入口脚本（文件名与 manifest.entry 一致）
└── data/                   # 可选：插件自带数据（如产品库 CSV）
```

打包示例（项目内已提供）：

```bash
# 在 plugins/<插件目录>/ 下包含 manifest + lua，运行：
python plugins/package_plugins.py
# 产物输出到 plugins_dist/<目录名>.bsplugin
```

---

## 2. plugin.bsmanifest.json 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 全局唯一标识，建议使用反向域名，如 `com.yourname.plugin` |
| `name` | 是 | 展示名称 |
| `version` | 否 | 语义化版本，默认 `0.0.0` |
| `author` | 否 | 作者，默认 `unknown` |
| `minHostVersion` | 否 | 要求的最低宿主版本，默认 `1.02` |
| `entry` | 否 | 入口脚本文件名，默认 `main.lua` |
| `description` | 否 | 功能描述 |
| `triggers` | 否 | 触发点数组，见下表 |
| `permissions` | 否 | 权限声明数组（透明告知用，见 §5） |
| `dependencies` | 否 | 依赖的其他插件 id 列表（预留） |

### triggers（触发点）

| 值 | 触发时机 | 宿主调用签名 |
|---|---|---|
| `on_scan` | 每次扫码识别一条新码后 | `on_scan(rec)` |
| `on_menu` | 用户在顶部菜单点插件项时 | `on_menu()` |
| `on_record_longclick` | 用户在列表中长按某条记录时 | `on_record_longclick(rec)` |

`rec` 是一个 Lua table：`{ value, format, timestamp, extras }`，其中 `extras` 是该记录的扩展字段（JSONObject）。

---

## 3. host API 速查表

插件通过全局表 `host` 调用宿主能力（**这是插件唯一可用的能力入口**）：

### 基础
- `host.version()` → 字符串，宿主版本号
- `host.time()` → 数字，当前 epoch 毫秒
- `host.toast(msg)` → 弹 toast 提示

### 记录读写（read_records / write_records）
- `host.records()` → 数组，全部记录
- `host.get(value)` → 单条记录或 nil
- `host.add(value, format?, extras?)` → 新增记录，已存在返回 false
- `host.update(value, extras)` → 更新某记录的 extras
- `host.remove(value)` → 删除某记录

### 产品库（product_db）—— 离线 EAN-13 商品库
- `host.product.lookup(barcode)` → 商品信息 table 或 nil
- `host.product.add(barcode, info)` → 写入/覆盖商品（info 为 table）

### 界面（ui_form）
- `host.ui.toast(msg)`
- `host.ui.openForm(title, fields, cb)` → 异步弹表单；`fields` 为数组 `[{key,label,type,default}]`，用户确认后回调 `cb(resultMap)`

### 导出（export_csv / export_json）
- `host.export.csv(name, rows)` → `rows` 为二维数组，调系统分享选择器导出
- `host.export.json(name, obj)` → 导出 JSON

### 私有存储（storage）
- `host.storage.get(key)` → 字符串或 nil（按当前插件隔离）
- `host.storage.set(key, val)` → 写入，val 为 nil 则删除

### JSON 工具
- `host.json.parse(str)` → table 或 nil
- `host.json.encode(tbl)` → JSON 字符串

### 外接扫码枪（scangun）—— 需 `network` 权限
将手机作为电脑的外接扫码枪，把每次扫码实时发到电脑端接收器。**仅当插件 manifest 声明了 `network` 权限时才可用**，否则所有方法返回"权限不足"。
- `host.scangun.configure(ip, port)` → 设置电脑接收端地址（port 默认 17533）
- `host.scangun.connect(token?)` → 异步建立 TCP 连接，返回当前状态字符串；可选传入配对令牌 `token`，连接建立后手机会先发送 `AUTH <token>` 完成鉴权（电脑端 `scangun_receiver.py` 的二维码已携带该令牌）
- `host.scangun.disconnect()` → 断开
- `host.scangun.status()` → 状态：`disconnected` / `connecting` / `connected` / `error` / `denied`
- `host.scangun.lastError()` → 最近一次错误描述
- `host.scangun.send(text)` → 发送一行文本（JSON 等），返回布尔（是否成功入队）

**二维码一键连接（免手动输入）**：电脑端 `scangun_receiver.py` 启动后会自动生成含 `scangun://电脑IP:端口?t=令牌&v=1` 的二维码（终端 ASCII + 自动保存 `scangun_qr.png` 并打开）。手机端「外接扫码枪」插件的 `on_scan` 识别到 `scangun://` 前缀时，会自动解析地址与令牌、调用 `configure` + `connect(token)` 完成连接，并把该连接串从主列表清除——**无需在菜单里手动输入任何地址或参数**。同时保留菜单手动输入 IP 的备用入口，以及旧的"无令牌直连"向后兼容。

---

## 4. 安全沙箱

宿主在运行插件前会**移除** Lua 环境中的所有危险能力：`os`、`io`、`package`、`require`、`load`、`loadfile`、`dofile`、`loadstring`、`luajava`、`debug`。因此插件：

- ❌ 不能访问文件系统、不能联网、不能启动进程
- ❌ 不能调用任意 Java/Android API 或反射
- ✅ 只能通过上面的 `host` 白名单与用户交互、读写本地数据

这保证了第三方插件不会泄露数据或破坏系统，符合本应用"零网络、数据本地化"的硬约束。

---

## 5. 权限声明

`permissions` 当前为**声明式**（透明告知用户插件会用到哪些能力）。除 `network` 外，沙箱已限制全部危险能力，未声明也不会获得额外权限。`network` 是特殊权限：**声明后才会解锁 `host.scangun` 桥**，使插件能间接发起局域网通信。建议如实填写，便于用户判断。可用值：`read_records`、`write_records`、`ui_form`、`product_db`、`storage`、`export_csv`、`export_json`、`network`（启用 `host.scangun` 局域网桥）。

---

## 6. 示例插件解读

仓库 `plugins/` 下提供了两个官方示例（已打包在 `plugins_dist/`）：

### 6.1 69码商品识别（triggers: on_scan）
识别 690–699 开头的 EAN-13 商品码，查本地产品库；未收录则弹窗录入名称/规格/单位/单价并写入产品库。录入后的商品名会写回该记录的 `extras.product`，在列表长按/查看时可见。

### 6.2 批量盘点（triggers: on_menu / on_scan / on_record_longclick）
点菜单"批量盘点"开始 → 逐件扫描自动计数(+1) → 长按记录可减1纠错 → 再次点菜单结束并导出实盘 CSV。计数状态保存在插件私有 storage，不影响原始扫码记录。

### 6.3 外接扫码枪（triggers: on_menu / on_scan，permissions: network）
将手机作为电脑的外接扫码枪。两种连接方式：
1. **二维码一键连接（推荐）**：电脑运行 `scangun_receiver.py` 自动生成二维码，手机直接扫描即完成 `configure` + `connect(token)`；`on_scan` 识别到 `scangun://` 前缀自动连接，并把该连接串从主列表清除。
2. **菜单手动连接（备用）**：点菜单"外接扫码枪" → 输入电脑 IP 与端口并连接（无令牌直连，向后兼容）。

连接建立后，每次扫码实时把 `{v,f,t}` 的 JSON 单行发到电脑端接收器（配套 `scangun_receiver.py`）。连接由宿主桥持有，扫码监听与上报逻辑在插件内，未连接时不拦截宿主原有扫码流程。`main.lua` 即完整实现，可作参考模板。

**电脑端实时采集界面（v1.04 起）**：`scangun_receiver.py` 启动后会在本机 `127.0.0.1`（默认端口 17534）起一个仅本机可访问的网页界面并自动打开浏览器——扫码数据实时上屏，支持单条复制、勾选批量复制、全选、导出 CSV / JSON、关键字筛选与清空，交互与手机端一致；数据同时追加写入 `scans.csv`。界面服务不对外网/局域网开放，数据不出本机。插件面板提示：该插件已随 APK 内置，可在右上角「设置-插件」面板或插件中心随时启用 / 关闭（关闭即断开连接）。

---

## 7. 编写你自己的插件

1. 新建目录 `plugins/<你的插件id>/`
2. 写 `plugin.bsmanifest.json`（参考示例）
3. 写 `main.lua`，实现需要的 `on_scan` / `on_menu` / `on_record_longclick` 全局函数
4. 运行 `python plugins/package_plugins.py` 生成 `.bsplugin`
5. 在 App「插件中心」点「导入插件」选择该文件，或从文件管理器/浏览器分享 `.bsplugin` 到本应用

---

## 8. 分发

`.bsplugin` 是一个普通文件，可以通过以下方式分发：

- **文件分享**：把 `.bsplugin` 发给他人，对方用本应用「导入插件」或直接"打开/分享"该文件即可安装。
- **分发页**：作者可自建一个静态网页（参考仓库 `plugins_dist/index.html` 模板）列出插件下载链接，用户下载后分享到 App 安装。应用本身不强制联网，联网动作只发生在用户主动下载插件时。
