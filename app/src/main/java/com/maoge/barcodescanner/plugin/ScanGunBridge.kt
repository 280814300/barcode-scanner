package com.maoge.barcodescanner.plugin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
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
 */
object ScanGunBridge {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "scangun-io") }
    private val lock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var reader: BufferedReader? = null

    /** 对外暴露的连接状态：disconnected / connecting / connected / error */
    @Volatile var status: String = "disconnected"
        private set

    @Volatile var lastError: String = ""
        private set

    var host: String = ""
    var port: Int = 17533
    /** 配对令牌：非空前，连接建立后会先发送 `AUTH <token>` 完成鉴权 */
    var authToken: String = ""

    fun configure(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun connect(token: String = "") {
        this.authToken = token
        executor.execute {
            synchronized(lock) {
                if (status == "connected" || status == "connecting") return@execute
                try {
                    status = "connecting"
                    lastError = ""
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 5000)
                    writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                    reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                    socket = s
                    status = "connected"
                    // 携带令牌时，首行发送 AUTH 完成配对鉴权
                    if (authToken.isNotEmpty()) {
                        writer?.write("AUTH " + authToken + "\n")
                        writer?.flush()
                    }
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: "连接失败"
                    closeLocked()
                    status = "error"
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
        executor.execute {
            synchronized(lock) {
                try {
                    writer?.write(text + "\n")
                    writer?.flush()
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: "发送失败"
                    closeLocked()
                    status = "error"
                }
            }
        }
        return true
    }

    private fun closeLocked() {
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            writer = null
            reader = null
            socket = null
            if (status != "error") status = "disconnected"
        }
    }
}
