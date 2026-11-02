package com.maoge.barcodescanner.plugin

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 外接扫码枪桥（宿主原生实现）。
 *
 * 设计要点：
 * - 这是整个 App 中**唯一**会发起网络通信的地方；由声明了 `network` 权限的插件通过
 *   `host.scangun` 间接调用，沙箱内的 Lua 插件本身无法直接接触 Socket，离线红线不破。
 * - 所有 Socket 操作都在单线程 executor 上执行，Lua 侧的 `on_scan` 在主线程调用
 *   [send] 时只是把写入任务入队，绝不阻塞也不触发 NetworkOnMainThreadException。
 * - 连接状态为单例，跨插件调用保持（菜单里 connect，扫码时 send 复用同一条连接）。
 * - v1.06：连接结果通过 [notifier] 在主线程回调给界面。此前无论成败都只弹「连接中」，
 *   用户看不到失败原因，是「一直连接中却连不上」却无从排查的直接原因。
 */
object ScanGunBridge {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "scangun-io") }
    private val lock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var connectStartedAt: Long = 0L

    /** 对外暴露的连接状态：disconnected / connecting / connected / error */
    @Volatile var status: String = "disconnected"
        private set

    @Volatile var lastError: String = ""
        private set

    /**
     * 状态变更通知（已切到主线程）。参数：(状态, 错误原因)。
     * 由 MainActivity 注册后弹 Toast；未注册时静默，不影响功能。
     */
    @Volatile var notifier: ((String, String) -> Unit)? = null

    var host: String = ""
    var port: Int = 17533
    /** 配对令牌：非空前，连接建立后会先发送 `AUTH <token>` 完成鉴权 */
    var authToken: String = ""

    private fun notifyUi(st: String, err: String) {
        val cb = notifier ?: return
        mainHandler.post { cb.invoke(st, err) }
    }

    fun configure(h: String, p: Int) {
        host = h
        port = p
    }

    fun connect(token: String = "") {
        authToken = token
        executor.execute {
            synchronized(lock) {
                // 已连上则忽略；正在连接时，只有明显卡死（超过 8 秒）才允许重来，
                // 否则用户连扫两次二维码会被静默忽略，看不到任何反馈。
                val pending = status == "connecting" &&
                        (System.currentTimeMillis() - connectStartedAt) < 8000
                if (status == "connected" || pending) return@execute
                try {
                    status = "connecting"
                    connectStartedAt = System.currentTimeMillis()
                    lastError = ""
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.tcpNoDelay = true
                    writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                    reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                    socket = s
                    status = "connected"
                    // 携带令牌时，首行发送 AUTH 完成配对鉴权
                    if (authToken.isNotEmpty()) {
                        writer?.write("AUTH " + authToken + "\n")
                        writer?.flush()
                    }
                    // 起读线程接收电脑端回执：鉴权成功为 OK AUTH，失败为 ERR <原因>
                    startReader()
                    // v1.06：连接成功必须让用户看见，而不是停在「连接中」
                    notifyUi("connected", "")
                } catch (e: Exception) {
                    val reason = humanize(e)
                    lastError = reason
                    closeLocked()
                    status = "error"
                    notifyUi("error", reason)
                }
            }
        }
    }

    fun disconnect() {
        executor.execute { closeLocked() }
    }

    /** 发送一行文本（立即返回是否成功入队；实际写入在后台线程完成） */
    fun send(text: String): Boolean {
        if (status != "connected") return false
        // status 是 connected 但写入器已空（对端刚断开）时，必须报错而不是静默丢数据
        if (writer == null) {
            status = "error"
            lastError = "与电脑端的连接已关闭，请重新扫码连接"
            return false
        }
        executor.execute {
            synchronized(lock) {
                try {
                    writer?.write(text + "\n")
                    writer?.flush()
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: "发送失败"
                    closeLocked()
                    status = "error"
                    notifyUi("error", lastError)
                }
            }
        }
        return true
    }

    /**
     * 把 Socket 异常翻译成能照着做的中文提示。
     * 以前直接抛 `connect timed out` 之类的英文，用户拿到也无从下手。
     */
    private fun humanize(e: Throwable): String {
        val m = e.localizedMessage ?: ""
        return when {
            e is SocketTimeoutException || m.contains("timed out", true) ||
                    m.contains("ETIMEDOUT", true) ->
                "连接超时：$host:$port 连不上。请确认 ①电脑上接收器正在运行 ②手机与电脑连的是同一个 WiFi（不是手机流量）③不是路由器访客网络/AP 隔离"
            e is ConnectException || m.contains("refused", true) ||
                    m.contains("ECONNREFUSED", true) ->
                "连接被拒绝：电脑在线但端口 $port 没人接。请确认接收器已启动，且端口与电脑上显示的一致"
            e is NoRouteToHostException || m.contains("EHOSTUNREACH", true) ||
                    m.contains("ENETUNREACH", true) ->
                "网络不可达：手机与电脑不在同一局域网。请让手机连上电脑所在的同一个 WiFi"
            m.contains("EACCES", true) || m.contains("EPERM", true) ->
                "网络权限被拒绝：请在系统设置里允许本 App 使用网络"
            m.contains("unresolved", true) || e is java.net.UnknownHostException ->
                "地址无法解析：$host 不是有效的 IP"
            else -> "连接失败：${if (m.isBlank()) e.javaClass.simpleName else m}"
        }
    }

    private fun closeLocked() {
        synchronized(lock) {
            stopReader()
            try { writer?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            writer = null
            reader = null
            socket = null
            if (status != "error") status = "disconnected"
        }
    }

    /**
     * 读取电脑端回执。
     *
     * 关键价值：令牌不匹配时电脑端会回 `ERR <原因>` 并断开，以前手机端完全不读，
     * 用户只看到"连上了但发不出去"；现在能拿到真实原因并在扫码时提示出来。
     */
    private fun startReader() {
        stopReader()
        val t = Thread({
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    when {
                        line.startsWith("ERR") -> {
                            val reason = line.removePrefix("ERR").trim()
                            lastError = reason
                            status = "error"
                            notifyUi("error", reason)
                            break
                        }
                        line.startsWith("OK") -> {
                            lastError = ""
                        }
                    }
                }
            } catch (_: Exception) {
                // 主动关闭 socket 时 readLine 抛异常属正常路径
            }
            // 对端断开且不是我们主动关的 → 标记为断开，避免"假连接"状态
            if (status == "connected") {
                status = "disconnected"
                if (lastError.isEmpty()) lastError = "与电脑端的连接已断开"
                notifyUi("disconnected", lastError)
            }
        }, "scangun-reader")
        t.isDaemon = true
        readerThread = t
        t.start()
    }

    private fun stopReader() {
        try { readerThread?.interrupt() } catch (_: Exception) {}
        readerThread = null
    }
}
