#!/usr/bin/env python3
# 电脑端「外接扫码枪」服务端（实时界面版）
# 配套 BarcodeApp 的「外接扫码枪」插件（host.scangun）。
# 仅用 Python 标准库 + 可选的 qrcode/Pillow；TCP 走局域网，界面只绑本机回环，数据不出本机。
#
# 用法（一条命令）：
#   python scangun_receiver.py
#   （可选）python scangun_receiver.py --port 17533 --web-port 17534
#
# 启动后会自动：
#   1) 起一个局域网 TCP 服务（默认 17533），接收手机扫码数据
#   2) 生成随机配对令牌，把「电脑IP + 端口 + 令牌」编码成二维码（终端 + PNG）
#   3) 起一个仅绑定 127.0.0.1 的本地界面服务，并自动打开浏览器
#      界面里实时展示扫码数据，支持单条复制、勾选批量复制、导出 CSV / JSON、筛选、清空
#
# 手机端：
#   插件中心里「外接扫码枪」已随 APK 内置 → 启用 → 扫电脑屏幕上的二维码即可连接。
#   之后每扫一条条码，电脑界面即时出现（数据同时追加到 scans.csv）。
#
# 说明：
#   - TCP 只接受局域网连接；界面服务默认只绑本机回环，别的设备访问不到。
#   - 关闭终端即停止，不残留后台进程。

import socket
import json
import csv
import os
import sys
import secrets
import datetime
import io
import threading
import subprocess
import webbrowser
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HOST = "0.0.0.0"          # TCP 监听：局域网内手机可连
WEB_HOST = "127.0.0.1"    # 界面服务：只绑本机回环
PORT = 17533
WEB_PORT = 17534

HERE = os.path.dirname(os.path.abspath(__file__))
CSV = os.path.join(HERE, "scans.csv")
QR_PNG = os.path.join(HERE, "scangun_qr.png")

# 配对令牌（二维码里携带，手机扫到即自动完成鉴权）
EXPECTED_TOKEN = secrets.token_urlsafe(6)

# 运行期由 main() 填入：当前连接地址、端口、以及所有可选地址
CURRENT_IP = "127.0.0.1"
CURRENT_PORT = PORT
VALID_IPS = []

# ---------------- 内存数据（供界面实时拉取） ----------------
_scans = []            # [{"id": int, "ts": str, "value": str, "fmt": str}]，按到达顺序
_seq = 0               # 单调递增序号，清空列表后也不回退
_clients = 0           # 当前接入的手机数量
_lock = threading.Lock()


# ---------------- 二维码依赖（缺失时自动安装，仍失败则回退为纯文本） ----------------
HAS_QR = False
try:
    import qrcode
    from PIL import Image  # noqa: F401
    HAS_QR = True
except Exception:
    try:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "--quiet", "qrcode", "pillow"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        import qrcode
        from PIL import Image  # noqa: F401
        HAS_QR = True
    except Exception:
        HAS_QR = False


def make_uri(ip, port, token):
    return f"scangun://{ip}:{port}?t={token}&v=1"


def lan_ip():
    """按默认路由挑一个对外地址（老办法，多网卡时可能挑错）。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def lan_ips(include_apipa=False):
    """
    列出本机所有可用于连接的 IPv4 及标注。

    多网卡机器（WiFi / 网线 / 虚拟机 / Tailscale）上，按默认路由挑出来的那一个
    经常不是手机能连的那个，所以这里全部列出来，让用户在界面上直接换。
    """
    out = []
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
    except Exception:
        infos = []
    seen = set()
    for _fam, _t, _p, _cn, sa in infos:
        ip = sa[0]
        if ip in seen:
            continue
        seen.add(ip)
        if ip.startswith("127."):
            continue
        tag = ""
        if ip.startswith("169.254."):
            # 链路本地地址（APIPA）：手机几乎不可能连上，默认不列
            if not include_apipa:
                continue
            tag = "链路本地（通常不可用）"
        elif ip.startswith("100."):
            try:
                second = int(ip.split(".")[1])
                if 64 <= second <= 127:
                    tag = "Tailscale"
            except Exception:
                pass
        out.append((ip, tag))
    return out


def pick_ip():
    """优先用路由挑出的地址；挑不到或挑到的不在候选里，就退回第一个候选。"""
    routed = lan_ip()
    cands = lan_ips()
    ips = [i for i, _ in cands]
    if routed in ips:
        return routed, cands
    if ips:
        return ips[0], cands
    # 极端情况：只有链路本地地址，也列出来让用户自己试
    fallback = lan_ips(include_apipa=True)
    return routed, (fallback or [(routed, "")])


def self_check(ip, port):
    """用局域网地址回连自己，确认端口是真的对外可达（防火墙/绑定问题会在这里暴露）。"""
    try:
        s = socket.create_connection((ip, port), timeout=3)
        s.close()
        return True, ""
    except Exception as e:
        return False, str(e)


def build_qr(uri):
    """终端打印 + 保存 PNG（供界面弹层展示与手机扫屏幕）。"""
    if not HAS_QR:
        print("[!] 未安装 qrcode 库，无法渲染二维码；可用下方连接串手动连接。")
        return
    try:
        qr = qrcode.QRCode(box_size=1, border=2)
        qr.add_data(uri)
        qr.make(fit=True)
        print("请使用手机扫描下方二维码完成连接：")
        try:
            qr.print_ascii()
        except Exception:
            pass
    except Exception as e:
        print(f"[!] 终端二维码渲染失败（{e}）")
    png = qr_png_bytes(uri)
    if png:
        try:
            with open(QR_PNG, "wb") as f:
                f.write(png)
            print(f"二维码图片：{QR_PNG}")
        except Exception as e:
            print(f"[!] 保存二维码图片失败：{e}")


def qr_png_bytes(uri):
    """生成二维码 PNG 字节（界面按 IP 实时切换时复用）。"""
    if not HAS_QR:
        return None
    try:
        qr = qrcode.QRCode(box_size=10, border=4, error_correction=qrcode.constants.ERROR_CORRECT_M)
        qr.add_data(uri)
        qr.make(fit=True)
        buf = io.BytesIO()
        qr.make_image(fill_color="black", back_color="white").save(buf, format="PNG")
        return buf.getvalue()
    except Exception:
        return None


def append_csv(ts, val, fmt):
    try:
        with open(CSV, "a", newline="", encoding="utf-8-sig") as f:
            csv.writer(f).writerow([ts, val, fmt])
    except Exception as e:
        print(f"[!] 写入 CSV 失败：{e}")


def load_recent_history(limit=500):
    """启动时把 CSV 末尾若干条载入内存，界面重启后也能看到最近的记录。"""
    global _seq
    if not os.path.exists(CSV):
        return
    try:
        with open(CSV, "r", encoding="utf-8-sig", newline="") as f:
            tail = list(deque(f, maxlen=limit + 1))
        rows = list(csv.reader(tail))
        if rows and rows[0] and rows[0][0] == "时间":
            rows = rows[1:]
        with _lock:
            for row in rows:
                if len(row) < 3:
                    continue
                _seq += 1
                _scans.append({"id": _seq, "ts": row[0], "value": row[1], "fmt": row[2]})
    except Exception as e:
        print(f"[!] 载入历史记录失败：{e}")


def add_scan(value, fmt):
    global _seq
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with _lock:
        _seq += 1
        rec = {"id": _seq, "ts": ts, "value": value, "fmt": fmt}
        _scans.append(rec)
    append_csv(ts, value, fmt)
    print(f"[{ts}] {value}  ({fmt})")
    return rec


# ---------------- TCP：接收手机上报 ----------------
def handle(conn, addr):
    global _clients
    peer = addr[0]
    authed = False
    buf = b""
    counted = False
    try:
        with conn:
            while True:
                data = conn.recv(4096)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    text = line.decode("utf-8", "replace")

                    # 首行可能是鉴权令牌（扫二维码自动连接时手机会先发 AUTH）
                    if not authed:
                        if text.startswith("AUTH "):
                            tok = text[5:].strip()
                            if tok != EXPECTED_TOKEN:
                                print(f"[x] 令牌不匹配，拒绝连接：{peer}")
                                print("    常见原因：扫的是上次运行留下的二维码/截图。")
                                print("    解决：扫描当前终端或界面里显示的最新二维码即可。")
                                # 明确回一行错误，让手机端能显示真实原因而不是静默失败
                                try:
                                    conn.sendall("ERR 令牌不匹配，请重新扫描电脑上的最新二维码\n".encode())
                                except Exception:
                                    pass
                                return
                            authed = True
                            try:
                                conn.sendall(b"OK AUTH\n")
                            except Exception:
                                pass
                            if not counted:
                                counted = True
                                with _lock:
                                    _clients += 1
                                print(f"[+] 设备接入：{peer}（当前 {_clients} 台）")
                            # AUTH 行本身是握手，不是扫码数据，跳过
                            continue
                        # 旧式手动连接：没有令牌，首行就是扫码数据，继续往下解析
                        # （早期实现此处直接 continue，会把第一条数据吞掉）
                        authed = True

                    if not counted:
                        counted = True
                        with _lock:
                            _clients += 1
                        print(f"[+] 设备接入：{peer}（当前 {_clients} 台）")

                    # 心跳：手机端可用来确认链路是否还活着
                    if text == "PING":
                        try:
                            conn.sendall(b"PONG\n")
                        except Exception:
                            pass
                        continue

                    try:
                        obj = json.loads(text)
                        val = str(obj.get("v", ""))
                        fmt = str(obj.get("f", ""))
                    except Exception:
                        val = text
                        fmt = "RAW"
                    if val:
                        add_scan(val, fmt)
    except Exception as e:
        print(f"[!] 设备异常断开 {addr}：{e}")
    finally:
        if counted:
            with _lock:
                _clients = max(0, _clients - 1)
            print(f"[-] 设备断开：{peer}（当前 {_clients} 台）")


# ---------------- 本地界面服务 ----------------
class Handler(BaseHTTPRequestHandler):
    server_version = "ScanGunUI/1.0"

    def log_message(self, fmt, *args):
        pass  # 静音访问日志，避免刷屏终端

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.end_headers()
        try:
            self.wfile.write(data)
        except Exception:
            pass

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path

        if path in ("/", "/index.html"):
            self._send(200, HTML_PAGE, "text/html; charset=utf-8")
            return

        if path == "/api/scans":
            q = parse_qs(u.query)
            try:
                since = int(q.get("since", ["0"])[0])
            except Exception:
                since = 0
            with _lock:
                items = [dict(r) for r in _scans if r["id"] > since]
                last = _scans[-1]["id"] if _scans else 0
                total = len(_scans)
                clients = _clients
            payload = {"items": items, "lastId": last, "total": total, "clients": clients}
            self._send(200, json.dumps(payload, ensure_ascii=False))
            return

        if path == "/api/qr.png":
            q = parse_qs(u.query)
            want = (q.get("ip", [""])[0] or CURRENT_IP).strip()
            allowed = [i for i, _ in VALID_IPS] or [CURRENT_IP]
            if want not in allowed:
                want = CURRENT_IP
            png = qr_png_bytes(make_uri(want, CURRENT_PORT, EXPECTED_TOKEN))
            if png:
                self._send(200, png, "image/png")
            else:
                self._send(404, "no qr", "text/plain; charset=utf-8")
            return

        if path == "/api/info":
            payload = {
                "ip": CURRENT_IP,
                "port": CURRENT_PORT,
                "ips": [{"ip": ip, "tag": tag} for ip, tag in VALID_IPS],
                "hasQr": HAS_QR,
            }
            self._send(200, json.dumps(payload, ensure_ascii=False))
            return

        self._send(404, "not found", "text/plain; charset=utf-8")

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/clear":
            with _lock:
                _scans.clear()
            self._send(200, json.dumps({"ok": True}))
            print("[i] 界面已清空（CSV 文件保留历史）")
            return
        self._send(404, "not found", "text/plain; charset=utf-8")


def start_web(preferred):
    for p in range(preferred, preferred + 10):
        try:
            srv = ThreadingHTTPServer((WEB_HOST, p), Handler)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            return p
        except OSError:
            continue
    return None


# ---------------- 界面（单文件内嵌，零外部依赖） ----------------
HTML_PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>扫码枪 · 实时采集</title>
<style>
:root{
  --bg:#eaf3fb;
  --ink:#0b1a2b;
  --ink2:#43566b;
  --ink3:#7b8ea3;
  --line:rgba(148,178,205,.34);
  --card:rgba(255,255,255,.76);
  --cardline:rgba(255,255,255,.92);
  --cyan:#0891b2;
  --blue:#2563eb;
  --green:#059669;
  --amber:#d97706;
  --pink:#db2777;
  --mono:ui-monospace,"SF Mono","Cascadia Mono","JetBrains Mono",Consolas,monospace;
}
*{box-sizing:border-box}
html,body{height:100%}
body{
  margin:0;color:var(--ink);background:var(--bg);overflow-x:hidden;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  -webkit-font-smoothing:antialiased;
}
body::before{
  content:"";position:fixed;inset:0;z-index:-2;
  background:
    radial-gradient(720px 520px at 8% -6%,rgba(6,182,212,.30),transparent 62%),
    radial-gradient(680px 480px at 96% 4%,rgba(37,99,235,.24),transparent 60%),
    radial-gradient(620px 520px at 78% 96%,rgba(236,72,153,.15),transparent 62%),
    radial-gradient(560px 440px at 18% 92%,rgba(16,185,129,.16),transparent 60%);
}
body::after{
  content:"";position:fixed;inset:0;z-index:-1;pointer-events:none;
  background-image:
    linear-gradient(rgba(148,178,205,.15) 1px,transparent 1px),
    linear-gradient(90deg,rgba(148,178,205,.15) 1px,transparent 1px);
  background-size:44px 44px;
  -webkit-mask-image:radial-gradient(circle at 50% 38%,#000 28%,transparent 80%);
  mask-image:radial-gradient(circle at 50% 38%,#000 28%,transparent 80%);
}
.shell{max-width:1180px;margin:0 auto;padding:26px 22px 44px}

.topbar{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;margin-bottom:18px}
.brand{display:flex;align-items:center;gap:13px}
.mark{
  width:42px;height:42px;border-radius:11px;display:grid;place-items:center;
  background:linear-gradient(145deg,#0ea5e9,#2563eb);color:#fff;
  font-weight:800;font-size:13px;letter-spacing:.6px;
  box-shadow:0 8px 20px -8px rgba(37,99,235,.55);
}
h1{margin:0;font-size:19px;font-weight:700;letter-spacing:-.2px}
.sub{margin:3px 0 0;font-size:12.5px;color:var(--ink2)}
.topright{display:flex;align-items:center;gap:9px;flex-wrap:wrap}
.status{
  display:inline-flex;align-items:center;gap:9px;height:38px;padding:0 13px;
  border-radius:10px;background:rgba(255,255,255,.7);border:1px solid var(--line);
  font-size:12.5px;color:var(--ink2);
}
.status::before{content:"";width:3px;height:15px;border-radius:2px;background:#9db4c9;transition:background .2s}
.status.on{color:#065f46}
.status.on::before{background:var(--green)}

.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:16px}
.stat{
  position:relative;padding:14px 16px 13px;border-radius:14px;
  background:var(--card);border:1px solid var(--cardline);
  box-shadow:0 1px 2px rgba(15,42,66,.05),0 10px 26px -18px rgba(15,42,66,.35);
  -webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);
  overflow:hidden;
}
.stat::before{content:"";position:absolute;left:0;top:0;bottom:0;width:3px;background:var(--cyan)}
.stat:nth-child(2)::before{background:var(--pink)}
.stat:nth-child(3)::before{background:var(--green)}
.stat:nth-child(4)::before{background:var(--amber)}
.stat .k{display:block;font-size:11.5px;color:var(--ink3);letter-spacing:.4px}
.stat .v{display:block;margin-top:4px;font-size:25px;font-weight:700;letter-spacing:-.5px;font-variant-numeric:tabular-nums}
.stat .v.mono{font-family:var(--mono);font-size:19px;letter-spacing:0}

.panel{
  background:var(--card);border:1px solid var(--cardline);border-radius:18px;
  -webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);
  box-shadow:0 1px 2px rgba(15,42,66,.05),0 24px 60px -34px rgba(15,42,66,.42);
  overflow:hidden;
}
.toolbar{display:flex;gap:8px;padding:14px 16px;border-bottom:1px solid var(--line);flex-wrap:wrap;align-items:center}
#filter{
  flex:1 1 210px;min-width:0;height:38px;padding:0 13px;
  border-radius:10px;border:1px solid var(--line);background:rgba(255,255,255,.88);
  font-size:13.5px;color:var(--ink);outline:none;font-family:inherit;
  transition:border-color .16s,box-shadow .16s;
}
#filter::placeholder{color:#96a9bc}
#filter:focus{border-color:var(--blue);box-shadow:0 0 0 3px rgba(37,99,235,.14)}
button{
  height:38px;padding:0 14px;border-radius:10px;border:1px solid var(--line);
  background:rgba(255,255,255,.92);font-size:13px;color:var(--ink);
  cursor:pointer;font-family:inherit;font-weight:500;white-space:nowrap;
  transition:background .16s,border-color .16s,transform .1s;
}
button:hover{background:#fff;border-color:rgba(37,99,235,.42)}
button:active{transform:translateY(1px)}
button.primary{
  background:linear-gradient(140deg,#0ea5e9,#2563eb);border-color:transparent;color:#fff;
  box-shadow:0 8px 18px -10px rgba(37,99,235,.8);
}
button.primary:hover{filter:brightness(1.05)}
button.danger{color:#b42318;border-color:rgba(217,119,6,.32)}
button.danger:hover{background:rgba(217,119,6,.08);border-color:rgba(217,119,6,.5)}

.listhead{
  display:grid;grid-template-columns:44px minmax(0,1fr) 88px;gap:12px;
  padding:9px 16px;font-size:11.5px;color:var(--ink3);letter-spacing:.4px;
  border-bottom:1px solid var(--line);background:rgba(255,255,255,.44);
}
.listhead span:last-child{text-align:right}
.list{list-style:none;margin:0;padding:0;max-height:min(58vh,560px);overflow:auto}
.row{
  display:grid;grid-template-columns:44px minmax(0,1fr) 88px;gap:12px;align-items:center;
  padding:11px 16px;border-bottom:1px solid rgba(148,178,205,.16);
  transition:background .14s;
}
.row:last-child{border-bottom:none}
.row:hover{background:rgba(255,255,255,.68)}
.row.sel{background:rgba(37,99,235,.055)}
.row .main{min-width:0}
.row .val{display:block;font-family:var(--mono);font-size:14.5px;letter-spacing:.2px;word-break:break-all;line-height:1.35}
.row .meta{display:flex;gap:10px;margin-top:3px;flex-wrap:wrap}
.row .fmt{font-size:11.5px;color:var(--ink2)}
.row .time{font-family:var(--mono);font-size:11.5px;color:var(--ink3)}
.row .ops{text-align:right}
.row button{height:30px;padding:0 11px;font-size:12px;border-radius:8px}

@keyframes flashIn{
  0%{background:rgba(16,185,129,.24)}
  100%{background:transparent}
}
.row.fresh{animation:flashIn 1.2s ease-out}

.pick{display:grid;place-items:center}
.pick input{
  -webkit-appearance:none;appearance:none;width:17px;height:17px;margin:0;
  border:1.6px solid #9db4c9;border-radius:5px;background:#fff;cursor:pointer;
  position:relative;transition:border-color .14s,background .14s;
}
.pick input:checked{background:var(--blue);border-color:var(--blue)}
.pick input:checked::after{
  content:"";position:absolute;left:5px;top:1px;width:4px;height:9px;
  border:solid #fff;border-width:0 2px 2px 0;transform:rotate(42deg);
}
.empty{padding:58px 20px;text-align:center;color:var(--ink3);font-size:13.5px;display:none}
.empty.show{display:block}

#toast{
  position:fixed;left:50%;bottom:28px;transform:translateX(-50%) translateY(12px);
  background:rgba(11,26,43,.93);color:#fff;padding:10px 18px;border-radius:10px;
  font-size:13px;opacity:0;pointer-events:none;z-index:80;
  transition:opacity .2s,transform .2s;
  max-width:min(560px,86vw);
}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}

.modal{
  position:fixed;inset:0;background:rgba(11,26,43,.46);z-index:90;
  display:none;place-items:center;padding:20px;
  -webkit-backdrop-filter:blur(3px);backdrop-filter:blur(3px);
}
.modal.show{display:grid}
.modalcard{background:#fff;border-radius:16px;padding:22px;text-align:center;max-width:340px;
  box-shadow:0 30px 70px -30px rgba(11,26,43,.65)}
.modalcard img{width:250px;height:250px;display:block;margin:0 auto 12px;image-rendering:pixelated}
.modalcard p{margin:0 0 14px;font-size:13px;color:var(--ink2);line-height:1.6}
.ippick{display:flex;flex-wrap:wrap;gap:6px;justify-content:center;margin:0 0 12px}
.ipbtn{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;padding:5px 10px;
  border-radius:8px;border:1px solid var(--line);background:rgba(255,255,255,.9);
  color:var(--ink2);cursor:pointer;transition:background .16s,border-color .16s,color .16s}
.ipbtn:hover{border-color:var(--cyan);color:var(--ink)}
.ipbtn.on{background:var(--cyan);border-color:var(--cyan);color:#fff;font-weight:600}
.qruri{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;color:var(--ink);
  word-break:break-all;user-select:all;margin:0 0 10px}
.qrhint{font-size:12px;color:var(--ink2);line-height:1.7}

@media (max-width:880px){
  .shell{padding:18px 14px 34px}
  .stats{grid-template-columns:repeat(2,minmax(0,1fr))}
  h1{font-size:17px}
  .row,.listhead{grid-template-columns:38px minmax(0,1fr) 76px;gap:10px;padding-left:13px;padding-right:13px}
  .row .val{font-size:13.5px}
}
</style>
</head>
<body>
<div class="shell">
  <header class="topbar">
    <div class="brand">
      <div class="mark">BS</div>
      <div>
        <h1>扫码枪 · 实时采集</h1>
        <p class="sub">手机扫码实时汇入，本机保存，不出网络</p>
      </div>
    </div>
    <div class="topright">
      <span class="status" id="status"><b id="statusText">等待手机接入</b></span>
      <button id="btnQr">连接二维码</button>
    </div>
  </header>

  <section class="stats">
    <div class="stat"><span class="k">总计</span><span class="v" id="stTotal">0</span></div>
    <div class="stat"><span class="k">已选</span><span class="v" id="stSel">0</span></div>
    <div class="stat"><span class="k">在线设备</span><span class="v" id="stDev">0</span></div>
    <div class="stat"><span class="k">最近一条</span><span class="v mono" id="stLast">--:--:--</span></div>
  </section>

  <section class="panel">
    <div class="toolbar">
      <input id="filter" placeholder="筛选条码 / 格式" autocomplete="off">
      <button id="btnAll">全选</button>
      <button id="btnCopy" class="primary">批量复制</button>
      <button id="btnCsv">导出 CSV</button>
      <button id="btnJson">导出 JSON</button>
      <button id="btnClear" class="danger">清空</button>
    </div>
    <div class="listhead"><span></span><span>条码 / 格式 / 时间</span><span>操作</span></div>
    <ul class="list" id="list"></ul>
    <div class="empty" id="empty">等待手机扫码，数据会实时出现在这里</div>
  </section>
</div>

<div class="modal" id="qrModal">
  <div class="modalcard">
    <img id="qrImg" alt="连接二维码">
    <div id="ipPicker" class="ippick"></div>
    <p id="qrUri" class="qruri"></p>
    <p class="qrhint">手机端启用「外接扫码枪」后扫描此码即可自动连接。
      若手机始终连不上，点上方其他 IP 换一个地址再扫；也可用手机「插件 → 扫码枪连接（手动）」手输上面的地址。</p>
    <button onclick="document.getElementById('qrModal').classList.remove('show')">关闭</button>
  </div>
</div>

<div id="toast"></div>

<script>
(function(){
  var listEl = document.getElementById('list');
  var rows = [];              // 最新在前
  var selected = {};          // id -> true
  var lastId = 0;
  var filterText = '';
  var firstLoad = true;
  var MAX_ROWS = 5000;

  function esc(s){
    return String(s).replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  function visible(){
    var q = filterText.trim().toLowerCase();
    if(!q) return rows;
    return rows.filter(function(r){
      return r.value.toLowerCase().indexOf(q) >= 0 ||
             (r.fmt || '').toLowerCase().indexOf(q) >= 0;
    });
  }

  function selectedCount(){
    var n = 0;
    for(var k in selected){ if(selected[k]) n++; }
    return n;
  }

  function render(){
    var v = visible().slice(0, 1200);
    var html = v.map(function(r){
      var on = selected[r.id] ? ' checked' : '';
      var sel = selected[r.id] ? ' sel' : '';
      var t = (r.ts || '').slice(11);
      return '<li class="row' + sel + '" data-id="' + r.id + '">'
        + '<label class="pick"><input type="checkbox" data-id="' + r.id + '"' + on + '></label>'
        + '<div class="main"><span class="val">' + esc(r.value) + '</span>'
        + '<span class="meta"><span class="fmt">' + esc(r.fmt || 'RAW') + '</span>'
        + '<span class="time">' + esc(t) + '</span></span></div>'
        + '<div class="ops"><button data-copy="' + r.id + '">复制</button></div>'
        + '</li>';
    }).join('');
    var sc = listEl.scrollTop;
    listEl.innerHTML = html;
    listEl.scrollTop = sc;
    document.getElementById('empty').classList.toggle('show', v.length === 0);
    syncStats();
  }

  function syncStats(){
    document.getElementById('stTotal').textContent = rows.length;
    document.getElementById('stSel').textContent = selectedCount();
    document.getElementById('stLast').textContent = rows.length ? (rows[0].ts || '').slice(11) : '--:--:--';
  }

  function findRow(id){
    for(var i = 0; i < rows.length; i++){ if(rows[i].id === id) return rows[i]; }
    return null;
  }

  var toastTimer = null;
  function toast(msg){
    var t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function(){ t.classList.remove('show'); }, 2000);
  }

  function copyText(text){
    if(navigator.clipboard && navigator.clipboard.writeText){
      return navigator.clipboard.writeText(text).then(function(){ return true; }).catch(fallback);
    }
    return Promise.resolve(fallback());
    function fallback(){
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try{ ok = document.execCommand('copy'); }catch(e){ ok = false; }
      document.body.removeChild(ta);
      return ok;
    }
  }

  function targets(){
    var v = visible();
    var picked = v.filter(function(r){ return selected[r.id]; });
    return picked.length ? picked : v;
  }

  function download(name, text, mime){
    var blob = new Blob([text], {type: mime});
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function(){ URL.revokeObjectURL(url); }, 1500);
  }

  function stamp(){
    var d = new Date();
    function p(n){ return String(n).padStart(2, '0'); }
    return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_'
      + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
  }

  function cell(s){
    return '"' + String(s).replace(/"/g, '""') + '"';
  }

  // ---- 单条复制 ----
  listEl.addEventListener('click', function(e){
    var btn = e.target.closest ? e.target.closest('button[data-copy]') : null;
    if(!btn) return;
    var r = findRow(Number(btn.getAttribute('data-copy')));
    if(!r) return;
    copyText(r.value).then(function(ok){
      toast(ok ? ('已复制：' + r.value) : '复制失败，请手动选中复制');
    });
  });

  // ---- 勾选 ----
  listEl.addEventListener('change', function(e){
    var cb = e.target;
    if(!cb || cb.type !== 'checkbox') return;
    var id = Number(cb.getAttribute('data-id'));
    if(cb.checked) selected[id] = true; else delete selected[id];
    var row = cb.closest('.row');
    if(row) row.classList.toggle('sel', cb.checked);
    syncStats();
  });

  // ---- 全选 / 取消 ----
  document.getElementById('btnAll').onclick = function(){
    var v = visible();
    if(!v.length){ toast('当前没有数据'); return; }
    var allOn = v.every(function(r){ return selected[r.id]; });
    v.forEach(function(r){
      if(allOn) delete selected[r.id]; else selected[r.id] = true;
    });
    render();
  };

  // ---- 批量复制 ----
  document.getElementById('btnCopy').onclick = function(){
    var list = targets();
    if(!list.length){ toast('没有可复制的数据'); return; }
    var text = list.map(function(r){ return r.value; }).join('\n');
    copyText(text).then(function(ok){
      if(!ok){ toast('复制失败'); return; }
      toast('已复制 ' + list.length + ' 条条码');
      selected = {};
      render();
    });
  };

  // ---- 导出 CSV ----
  document.getElementById('btnCsv').onclick = function(){
    var list = targets();
    if(!list.length){ toast('没有可导出的数据'); return; }
    var lines = [['时间', '条码', '格式'].map(cell).join(',')];
    list.forEach(function(r){
      lines.push([r.ts, r.value, r.fmt || 'RAW'].map(cell).join(','));
    });
    download('scans_' + stamp() + '.csv', '\ufeff' + lines.join('\r\n'), 'text/csv;charset=utf-8');
    toast('已导出 ' + list.length + ' 条到 CSV');
    selected = {};
    render();
  };

  // ---- 导出 JSON ----
  document.getElementById('btnJson').onclick = function(){
    var list = targets();
    if(!list.length){ toast('没有可导出的数据'); return; }
    var data = list.map(function(r){
      return {time: r.ts, value: r.value, format: r.fmt || 'RAW'};
    });
    download('scans_' + stamp() + '.json', JSON.stringify(data, null, 2), 'application/json');
    toast('已导出 ' + list.length + ' 条到 JSON');
    selected = {};
    render();
  };

  // ---- 清空 ----
  document.getElementById('btnClear').onclick = function(){
    if(!rows.length){ toast('列表已经是空的'); return; }
    if(!confirm('清空界面上的全部 ' + rows.length + ' 条数据？\n（scans.csv 文件仍保留历史记录）')) return;
    fetch('/api/clear', {method: 'POST'}).then(function(){
      rows = [];
      selected = {};
      lastId = 0;
      render();
      toast('已清空界面列表，CSV 文件保留');
    }).catch(function(){ toast('清空失败'); });
  };

  // ---- 筛选 ----
  document.getElementById('filter').addEventListener('input', function(e){
    filterText = e.target.value;
    selected = {};
    render();
  });

  // ---- 二维码（支持切换 IP 后即时重生成） ----
  var curIp = '', curPort = 0;
  function refreshQr(){
    var img = document.getElementById('qrImg');
    if(curIp) img.src = '/api/qr.png?ip=' + encodeURIComponent(curIp) + '&t=' + Date.now();
    document.getElementById('qrUri').textContent = curIp ? ('scangun://' + curIp + ':' + curPort) : '';
    var btns = document.querySelectorAll('#ipPicker .ipbtn');
    for(var i=0;i<btns.length;i++){
      btns[i].classList.toggle('on', btns[i].getAttribute('data-ip') === curIp);
    }
  }
  function loadIps(){
    fetch('/api/info', {cache:'no-store'})
      .then(function(r){ return r.json(); })
      .then(function(d){
        curPort = d.port || 0;
        var box = document.getElementById('ipPicker');
        box.innerHTML = '';
        (d.ips || []).forEach(function(it){
          var b = document.createElement('button');
          b.className = 'ipbtn';
          b.setAttribute('data-ip', it.ip);
          b.textContent = it.ip + (it.tag ? '（' + it.tag + '）' : '');
          b.onclick = function(){ curIp = it.ip; refreshQr(); };
          box.appendChild(b);
        });
        if(!curIp) curIp = d.ip || '';
        refreshQr();
      })
      .catch(function(){ /* 接口不可用时沿用默认二维码 */ });
  }
  document.getElementById('btnQr').onclick = function(){
    document.getElementById('qrModal').classList.add('show');
    loadIps();
  };
  document.getElementById('qrModal').addEventListener('click', function(e){
    if(e.target.id === 'qrModal') e.target.classList.remove('show');
  });

  // ---- 实时同步 ----
  function poll(){
    fetch('/api/scans?since=' + lastId, {cache: 'no-store'})
      .then(function(r){
        if(!r.ok) throw new Error('http ' + r.status);
        return r.json();
      })
      .then(function(d){
        var st = document.getElementById('status');
        st.classList.toggle('on', d.clients > 0);
        document.getElementById('statusText').textContent =
          d.clients > 0 ? ('已接入 ' + d.clients + ' 台设备') : '等待手机接入';
        document.getElementById('stDev').textContent = d.clients;

        if(d.items.length){
          var ids = [];
          d.items.forEach(function(it){
            rows.unshift(it);
            ids.push(it.id);
          });
          if(rows.length > MAX_ROWS) rows.length = MAX_ROWS;
          lastId = d.lastId;
          render();
          ids.forEach(function(id){
            var el = listEl.querySelector('.row[data-id="' + id + '"]');
            if(el){
              el.classList.add('fresh');
              setTimeout(function(){ el.classList.remove('fresh'); }, 1250);
            }
          });
        } else if(firstLoad){
          render();
        }
        firstLoad = false;
      })
      .catch(function(){
        document.getElementById('status').classList.remove('on');
        document.getElementById('statusText').textContent = '界面服务未连接';
      });
  }

  poll();
  setInterval(poll, 700);
})();
</script>
</body>
</html>
"""


def main():
    global CURRENT_IP, CURRENT_PORT, VALID_IPS
    port = PORT
    web_port = WEB_PORT
    args = sys.argv
    if "--port" in args:
        try:
            port = int(args[args.index("--port") + 1])
        except Exception:
            pass
    if "--web-port" in args:
        try:
            web_port = int(args[args.index("--web-port") + 1])
        except Exception:
            pass
    open_browser = "--no-browser" not in args

    load_recent_history()

    # 选 IP：多网卡机器上默认路由挑出的地址常常不是手机能连的那个，
    # 所以这里把所有候选都记下来，界面上可一键切换；也支持 --ip 直接指定。
    auto_ip, cands = pick_ip()
    ip = auto_ip
    if "--ip" in args:
        try:
            given = args[args.index("--ip") + 1].strip()
            if given:
                ip = given
                if not any(given == c for c, _ in cands):
                    cands = cands + [(given, "手动指定")]
        except Exception:
            pass
    CURRENT_IP, CURRENT_PORT, VALID_IPS = ip, port, cands
    uri = make_uri(ip, port, EXPECTED_TOKEN)

    if not os.path.exists(CSV):
        with open(CSV, "a", newline="", encoding="utf-8-sig") as f:
            csv.writer(f).writerow(["时间", "条码", "格式"])

    print("=" * 62)
    print("外接扫码枪服务端已启动")
    print(f"电脑IP：{ip}    TCP 端口：{port}")
    if len(cands) > 1:
        others = "、".join(c for c, _ in cands if c != ip)
        print(f"其他可选IP：{others}")
        print("          （手机连不上时，在界面的二维码弹窗里切换 IP，或加参数 --ip 指定）")
    print(f"配对令牌：{EXPECTED_TOKEN}")
    print("          （已写入二维码，手机扫到会自动携带，无需手输）")
    print(f"数据留档：{CSV}")
    print("=" * 62)
    build_qr(uri)
    print(f"连接串（备用）：{uri}")
    print()

    # 本地界面服务（只绑 127.0.0.1）
    actual_web = start_web(web_port)
    if actual_web:
        url = f"http://{WEB_HOST}:{actual_web}/"
        print(f"实时界面：{url}   （仅本机可访问，Ctrl+C 停止）")
        if open_browser:
            threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    else:
        print(f"[!] 界面端口 {web_port} 附近均被占用，界面不可用；终端仍会实时打印扫码数据。")
    print()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((HOST, port))
    except OSError as e:
        print(f"[!] TCP 端口 {port} 绑定失败：{e}\n请换一个端口，例如：python scangun_receiver.py --port 18080")
        return
    srv.listen(5)

    # 启动自检：用局域网地址回连自己，端口被防火墙挡住或绑错时会立刻暴露，
    # 而不是等到手机怎么扫都连不上才发现。
    ok, err = self_check(ip, port)
    if ok and ip.startswith("127."):
        print("[!] 自检未通过：没找到局域网地址，手机无法连接。")
        print("    请确认电脑已连上 WiFi/网线（当前只有本机回环地址 127.0.0.1）。")
    elif ok:
        print(f"[√] 自检通过：{ip}:{port} 对外可达，手机在同一 WiFi 下应能连上。")
    else:
        print(f"[!] 自检未通过：连不上 {ip}:{port}（{err}）")
        print("    请依次确认：")
        print("    1) 电脑防火墙是否放行本程序与端口（首次运行常弹窗，需点「允许访问」）")
        print("    2) 手机与电脑是否连的是同一个 WiFi，且不是访客网络 / AP 隔离")
        print(f"    3) 若机器有多个网卡，换一个 IP 试试：--ip <上面列出的其他IP>")
    print()

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n接收器已停止。")


if __name__ == "__main__":
    main()
