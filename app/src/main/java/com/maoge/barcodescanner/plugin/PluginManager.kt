package com.maoge.barcodescanner.plugin

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件注册与生命周期管理（纯文件级，离线）。
 *
 * 存储布局：
 *   filesDir/plugins/            —— 已安装插件根目录
 *   filesDir/plugins/<id>/       —— 单个插件解压目录（含 plugin.bsmanifest.json + entry + data 子目录）
 *   filesDir/plugins.json        —— 注册表（已安装插件元信息 + 启用状态）
 */
object PluginManager {

    private const val REG_FILE = "plugins.json"
    private const val PLUGIN_DIR = "plugins"
    private const val MANIFEST_NAME = "plugin.bsmanifest.json"

    /** 随 APK 内置的外接扫码枪插件 id（宿主内唯一会发起网络通信的插件） */
    private const val SCANGUN_PLUGIN_ID = "scangun"

    private lateinit var ctx: Context

    fun init(c: Context) {
        ctx = c.applicationContext
        // 首次启动（或升级后）把随 APK 内置的插件自动安装进注册表，
        // 之后用户可像普通插件一样启用 / 禁用 / 卸载。
        installBundledFromAssets()
    }

    /** 注册表中的插件元信息（持久化用，不含运行态） */
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val enabled: Boolean,
        val bundled: Boolean,
        val entry: String,
        val triggers: List<String>,
        val permissions: List<String>,
        val description: String
    )

    /** 安装结果 */
    sealed class InstallResult {
        data class Ok(val info: PluginInfo) : InstallResult()
        data class Err(val msg: String) : InstallResult()
    }

    private fun regFile() = File(ctx.filesDir, REG_FILE)
    private fun pluginRoot() = File(ctx.filesDir, PLUGIN_DIR).apply { if (!exists()) mkdirs() }

    fun pluginDir(id: String) = File(pluginRoot(), id)

    /** 读取注册表 */
    fun list(): List<PluginInfo> {
        val f = regFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getEnabled(): List<PluginInfo> = list().filter { it.enabled }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(enabled = enabled)
        save(all)
        // 关闭外接扫码枪时顺手断开已建立的网络长连接，避免插件已停用但 Socket 仍在挂着。
        // 宿主里只有这一个插件会联网，因此此处按 id 判断即可。
        if (!enabled && id == SCANGUN_PLUGIN_ID) {
            try { ScanGunBridge.disconnect() } catch (_: Exception) { }
        }
        return true
    }

    fun uninstall(id: String): Boolean {
        val dir = pluginDir(id)
        if (dir.exists()) dir.deleteRecursively()
        val all = list().filter { it.id != id }
        save(all)
        return true
    }

    /** 标记插件是否随 APK 内置（出厂自带，用户仍可卸载/禁用） */
    fun setBundled(id: String, bundled: Boolean): Boolean {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(bundled = bundled)
        save(all)
        return true
    }

    // 首次启动自动安装随 APK 内置的插件（assets/bundled_plugins 下的 .bsplugin 包）。
    // 用 bundled_seen.json 记录已处理过的插件 id：用户若已自行导入同名插件则仅记录不再覆盖；
    // 用户若卸载了内置插件，也不会被重新塞回（尊重用户选择）。
    private fun installBundledFromAssets() {
        try {
            val dir = "bundled_plugins"
            val names = ctx.assets.list(dir) ?: return
            val seen = loadBundledSeen().toMutableSet()
            for (name in names) {
                if (!name.endsWith(".bsplugin", true) && !name.endsWith(".zip", true)) continue
                val path = dir + "/" + name
                try {
                    val id = ctx.assets.open(path).use { peekManifestId(it) } ?: continue
                    if (seen.contains(id)) continue
                    // 用户已自行导入过该插件（或上版本已内置），不再覆盖
                    if (list().any { it.id == id }) {
                        seen.add(id)
                        continue
                    }
                    // 单独开一条流做真正安装（peek 已消耗另一条流）
                    var installed = false
                    ctx.assets.open(path).use { stream ->
                        val res = installFromStream(stream)
                        when (res) {
                            is InstallResult.Ok -> { setBundled(res.info.id, true); installed = true }
                            is InstallResult.Err -> { }
                        }
                    }
                    if (installed) seen.add(id)
                } catch (e: Exception) {
                    // 单个插件失败不影响其余；保留 seen 不写该 id 以便重试
                }
            }
            saveBundledSeen(seen)
        } catch (e: Exception) {
            // 无 bundled_plugins 目录等：静默跳过
        }
    }

    // 从 zip 流里只读 plugin.bsmanifest.json，取插件 id（不消耗安装用的流）
    private fun peekManifestId(stream: java.io.InputStream): String? {
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == MANIFEST_NAME) {
                    val text = zis.bufferedReader().readText()
                    return try { PluginManifest.parse(text).id } catch (e: Exception) { null }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun bundledSeenFile() = File(ctx.filesDir, "bundled_seen.json")
    private fun loadBundledSeen(): Set<String> {
        val f = bundledSeenFile()
        if (!f.exists()) return emptySet()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }
    private fun saveBundledSeen(set: Set<String>) {
        val a = JSONArray()
        set.forEach { a.put(it) }
        bundledSeenFile().writeText(a.toString())
    }

    /** 从系统文件选择器拿到的 Uri 安装 .bsplugin（zip） */
    fun install(uri: Uri): InstallResult {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { instream ->
                installFromStream(instream)
            } ?: InstallResult.Err("无法读取插件文件")
        } catch (e: Exception) {
            InstallResult.Err("安装失败：${e.localizedMessage}")
        }
    }

    /** 从原始 zip 字节流安装（便于单测/内部调用） */
    fun installFromStream(stream: java.io.InputStream): InstallResult {
        val tmp = File(ctx.cacheDir, "bsplugin_tmp_${System.currentTimeMillis()}")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(tmp, entry.name)
                        out.parentFile?.mkdirs()
                        // 防目录穿越
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath)) {
                            return InstallResult.Err("插件包含非法路径")
                        }
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val manifestFile = File(tmp, MANIFEST_NAME)
            if (!manifestFile.exists()) return InstallResult.Err("缺少 $MANIFEST_NAME")
            val manifest = PluginManifest.parse(manifestFile.readText())
            if (!versionAtLeast(PluginManifest.HOST_VERSION, manifest.minHostVersion)) {
                return InstallResult.Err("插件要求宿主版本 ${manifest.minHostVersion}，当前 ${PluginManifest.HOST_VERSION}")
            }
            val entryFile = File(tmp, manifest.entry)
            if (!entryFile.exists()) return InstallResult.Err("入口脚本 ${manifest.entry} 不存在")

            // 覆盖安装：先卸旧
            val target = pluginDir(manifest.id)
            if (target.exists()) target.deleteRecursively()
            tmp.copyRecursively(target, overwrite = true)

            val info = PluginInfo(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                author = manifest.author,
                enabled = true,
                bundled = false,
                entry = manifest.entry,
                triggers = manifest.triggers.toList(),
                permissions = manifest.permissions.toList(),
                description = manifest.description
            )
            val all = list().toMutableList()
            all.removeIf { it.id == info.id }
            all.add(info)
            save(all)
            return InstallResult.Ok(info)
        } catch (e: Exception) {
            return InstallResult.Err("安装失败：${e.localizedMessage}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 读取插件入口脚本内容 */
    fun readEntry(info: PluginInfo): String? {
        val f = File(pluginDir(info.id), info.entry)
        return if (f.exists()) f.readText() else null
    }

    /** 读取插件私有数据文件（data 目录下），供插件运行时访问 */
    fun readPluginData(info: PluginInfo, relPath: String): String? {
        val f = File(pluginDir(info.id), "data/$relPath")
        return if (f.exists()) f.readText() else null
    }

    private fun save(list: List<PluginInfo>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        regFile().writeText(arr.toString(2))
    }

    private fun toJson(i: PluginInfo) = JSONObject().apply {
        put("id", i.id); put("name", i.name); put("version", i.version)
        put("author", i.author); put("enabled", i.enabled); put("bundled", i.bundled); put("entry", i.entry)
        put("triggers", JSONArray(i.triggers)); put("permissions", JSONArray(i.permissions))
        put("description", i.description)
    }

    private fun fromJson(o: JSONObject) = PluginInfo(
        id = o.getString("id"),
        name = o.optString("name", o.getString("id")),
        version = o.optString("version", "0.0.0"),
        author = o.optString("author", "unknown"),
        enabled = o.optBoolean("enabled", true),
        bundled = o.optBoolean("bundled", false),
        entry = o.optString("entry", "main.lua"),
        triggers = jsonArr(o, "triggers"),
        permissions = jsonArr(o, "permissions"),
        description = o.optString("description", "")
    )

    private fun jsonArr(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }
}
