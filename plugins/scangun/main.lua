-- 外接扫码枪插件 v1.1.1
-- 将本机作为电脑的外接扫码枪：连接电脑后，每次扫码实时上报条码到电脑端接收器。
--
-- 本插件只做“连接管理 / 扫码监听 / 数据上报”的编排；
-- 真正的局域网通信由宿主 host.scangun 桥完成（沙箱禁网，所以由宿主代发）。
-- 使用前需在 manifest 声明 network 权限（已声明）。
--
-- v1.1.1 修复：
--   1) 致命 bug —— 局部变量 `local host` 遮蔽了全局 host 表，扫连接二维码时
--      `host.scangun` 其实是在索引一个字符串，必然抛 LuaError，连接从未发出。
--      现改名为 ip，并在函数内所有分支确保引用的是全局 host 表。
--   2) 连接二维码解析失败不再静默，给出明确提示。
--   3) 普通条码在“未连接 / 连接中 / 连接失败”三种状态下分别提示，不再无声丢弃。
--   4) 上报数据编码失败有兜底，不会因为单条异常影响整个插件。

local PORT_DEFAULT = 17533

-- 解析连接二维码串：scangun://host:port?t=token&v=1
-- 返回 ip, port, token；失败返回 nil
function parse_uri(u)
  local s = u:match("^scangun://(.+)$")
  if not s then return nil end
  local auth, query = s:match("^([^?]*)(%?.*)$")
  if not auth then auth = s end
  local ip, port = auth:match("^([^:]+):(%d+)$")
  if not ip then return nil end
  local token = ""
  if query then
    token = query:match("t=([^&]+)") or query:match("token=([^&]+)") or ""
  end
  return ip, tonumber(port), token
end

-- 扫码监听
function on_scan(rec)
  local val = rec.value
  if type(val) ~= "string" or val == "" then return end

  -- 情形一：扫到电脑生成的“扫码枪连接二维码”（scangun://...）
  -- 自动完成连接，无需手动输入；该连接串本身不转发、也不留在主列表
  if val:sub(1, 10) == "scangun://" then
    local ip, port, token = parse_uri(val)
    -- 先把这条连接串从主列表清掉，避免污染记录；失败也不影响后续连接
    pcall(host.remove, val)
    if ip and port then
      host.scangun.configure(ip, port)
      host.scangun.connect(token or "")
      host.toast("扫码枪连接中…（" .. ip .. ":" .. tostring(port) .. "）")
    else
      host.toast("无法识别该连接二维码，请重新扫描电脑上显示的最新二维码")
    end
    return
  end

  -- 情形二：普通条码 —— 已连接则实时上报到电脑
  local st = host.scangun.status()
  if st ~= "connected" then
    -- 三种未就绪状态分别提示，避免“扫了没反应”的困惑
    if st == "connecting" then
      host.toast("扫码枪正在连接，请稍候再扫")
    elseif st == "error" then
      host.toast("扫码枪连接失败：" .. host.scangun.lastError())
    else
      host.toast("扫码枪未连接：请先扫描电脑上的连接二维码")
    end
    return
  end

  local ok, line = pcall(host.json.encode, {
    v = val,
    f = rec.format or "UNKNOWN",
    t = rec.timestamp or 0
  })
  if not ok or type(line) ~= "string" then
    host.toast("数据编码失败，本条未发送")
    return
  end

  if not host.scangun.send(line) then
    host.toast("发送失败：" .. host.scangun.lastError())
  end
end

-- 菜单入口：连接 / 断开（手动输入 IP 的备用方式）
function on_menu()
  local st = host.scangun.status()
  if st == "connected" or st == "connecting" then
    host.scangun.disconnect()
    host.toast("扫码枪已断开")
    return
  end

  -- 未连接：先告知上一次失败原因，再弹出表单让用户输入电脑 IP 与端口
  if st == "error" then
    host.toast("上次连接失败：" .. host.scangun.lastError())
  end

  local ip = host.storage.get("sg_ip") or ""
  local port = host.storage.get("sg_port") or tostring(PORT_DEFAULT)
  host.ui.openForm("扫码枪连接（手动）", {
    { key = "ip",   label = "电脑IP",   type = "text",   default = ip },
    { key = "port", label = "端口",     type = "number", default = port }
  }, function(f)
    host.storage.set("sg_ip", f.ip)
    host.storage.set("sg_port", f.port)
    host.scangun.configure(f.ip, tonumber(f.port) or PORT_DEFAULT)
    host.scangun.connect("")
    host.toast("扫码枪连接中…")
  end)
end
