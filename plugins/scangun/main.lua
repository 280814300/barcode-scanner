-- 外接扫码枪插件 v1.1.0
-- 将本机作为电脑的外接扫码枪：连接电脑后，每次扫码实时上报条码到电脑端接收器。
--
-- 本插件只做“连接管理 / 扫码监听 / 数据上报”的编排；
-- 真正的局域网通信由宿主 host.scangun 桥完成（沙箱禁网，所以由宿主代发）。
-- 使用前需在 manifest 声明 network 权限（已声明）。

local PORT_DEFAULT = 17533

-- 解析连接二维码串：scangun://host:port?t=token&v=1
-- 返回 host, port, token；失败返回 nil
function parse_uri(u)
  local s = u:match("^scangun://(.+)$")
  if not s then return nil end
  local auth, query = s:match("^([^?]*)(%?.*)$")
  if not auth then auth = s end
  local host, port = auth:match("^([^:]+):(%d+)$")
  if not host then return nil end
  local token = ""
  if query then
    token = query:match("t=([^&]+)") or ""
  end
  return host, tonumber(port), token
end

-- 扫码监听
function on_scan(rec)
  local val = rec.value

  -- 情形一：扫到电脑生成的“扫码枪连接二维码”（scangun://...）
  -- 自动完成连接，无需手动输入；该连接串本身不转发、也不留在主列表
  if val:sub(1, 10) == "scangun://" then
    local host, port, token = parse_uri(val)
    if host and port then
      host.scangun.configure(host, port)
      host.scangun.connect(token or "")
      host.toast("扫码枪连接中…（扫电脑二维码自动连接）")
    else
      host.toast("无法识别该连接二维码")
    end
    host.remove(val)  -- 清掉这条连接串记录，避免污染主列表
    return
  end

  -- 情形二：普通条码 —— 已连接则实时上报到电脑
  local st = host.scangun.status()
  if st ~= "connected" then
    return
  end
  local line = host.json.encode({ v = val, f = rec.format, t = rec.timestamp })
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

  -- 未连接：读取已保存配置，弹出表单让用户输入电脑 IP 与端口（备用入口）
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
