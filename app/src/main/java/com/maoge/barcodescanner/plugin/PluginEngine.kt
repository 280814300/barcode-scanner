package com.maoge.barcodescanner.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.maoge.barcodescanner.BarcodeRecord
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.Globals
import java.io.File

/**
 * 插件引擎：协调注册表（PluginManager）、沙箱（PluginSandbox）、API 桥（buildHostTable）。
 * 由 MainActivity 在 onCreate 中 bind()，并提供 records 引用与 UI 回调。
 */
object PluginEngine {

    lateinit var ctx: Context
    lateinit var records: MutableList<BarcodeRecord>
    lateinit var onDataChanged: () -> Unit
    lateinit var hostUi: HostUi

    /** 当前正在运行的插件 id（供 storage 隔离使用） */
    var currentPluginId: String = ""

    /** 当前正在运行的插件声明的权限（供 host 桥做能力管控，如 network） */
    var activePermissions: Set<String> = emptySet()

    /** 宿主需实现的交互能力（主线程 UI 操作） */
    interface HostUi {
        fun toast(msg: String)
        fun showForm(title: String, fields: List<FormField>, onResult: (Map<String, String>) -> Unit)
        fun runOnUi(r: Runnable)
    }

    data class FormField(
        val key: String,
        val label: String,
        val type: String,    // text / number / checkbox
        val default: String
    )

    fun bind(
        ctx: Context,
        records: MutableList<BarcodeRecord>,
        onDataChanged: () -> Unit,
        hostUi: HostUi
    ) {
        this.ctx = ctx.applicationContext
        this.records = records
        this.onDataChanged = onDataChanged
        this.hostUi = hostUi
        PluginManager.init(this.ctx)
    }

    // ---------------- 离线产品库（filesDir/products.json） ----------------
    private fun productsFile() = File(ctx.filesDir, "products.json")

    fun productLookup(barcode: String): JSONObject? {
        val arr = loadProducts()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("barcode") == barcode) return o
        }
        return null
    }

    fun productAdd(barcode: String, info: JSONObject) {
        val arr = loadProducts()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("barcode") == barcode) {
                arr.put(i, info)
                saveProducts(arr)
                return
            }
        }
        arr.put(info)
        saveProducts(arr)
    }

    private fun loadProducts(): JSONArray {
        val f = productsFile()
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveProducts(arr: JSONArray) {
        productsFile().writeText(arr.toString(2))
    }

    // ---------------- 导出（复用 FileProvider + 系统分享选择器） ----------------
    fun exportCsv(name: String, rows: List<List<String>>) {
        val sb = StringBuilder("﻿")
        rows.forEach { row ->
            sb.append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n")
        }
        exportFile(name, sb.toString(), "text/csv")
    }

    fun exportJson(name: String, obj: Any) {
        val s = when (obj) {
            is JSONObject -> obj.toString(2)
            is JSONArray -> obj.toString(2)
            else -> obj.toString()
        }
        exportFile(name, s, "application/json")
    }

    private fun exportFile(name: String, content: String, mime: String) {
        val file = File(ctx.cacheDir, name)
        file.writeText(content)
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val res = ctx.packageManager.queryIntentActivities(intent, 0)
        for (r in res) {
            ctx.grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        hostUi.runOnUi {
            ctx.startActivity(
                Intent.createChooser(intent, "导出到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------- 插件私有存储（SharedPreferences 隔离） ----------------
    fun pluginStorageGet(key: String): String? {
        if (currentPluginId.isEmpty()) return null
        return ctx.getSharedPreferences("plugin_$currentPluginId", Context.MODE_PRIVATE)
            .getString(key, null)
    }

    fun pluginStorageSet(key: String, value: String?) {
        if (currentPluginId.isEmpty()) return
        ctx.getSharedPreferences("plugin_$currentPluginId", Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    // ---------------- 调度 ----------------
    fun dispatchScan(rec: BarcodeRecord) {
        PluginManager.getEnabled()
            .filter { it.triggers.contains("on_scan") }
            .forEach { runPlugin(it, "on_scan", rec) }
    }

    fun dispatchLongClick(rec: BarcodeRecord) {
        PluginManager.getEnabled()
            .filter { it.triggers.contains("on_record_longclick") }
            .forEach { runPlugin(it, "on_record_longclick", rec) }
    }

    fun menuPlugins(): List<PluginManager.PluginInfo> =
        PluginManager.getEnabled().filter { it.triggers.contains("on_menu") }

    fun runMenuPlugin(info: PluginManager.PluginInfo) {
        runPlugin(info, "on_menu")
    }

    private fun runPlugin(
        info: PluginManager.PluginInfo,
        fn: String,
        rec: BarcodeRecord? = null
    ) {
        val code = PluginManager.readEntry(info) ?: return
        try {
            currentPluginId = info.id
            activePermissions = info.permissions.toSet()
            val g: Globals = PluginSandbox.buildGlobals()
            g.set("host", buildHostTable(this))
            g.load(code, "${info.id}/${info.entry}").call()
            val f = g.get(fn)
            if (f.isfunction()) {
                if (rec != null) f.call(recordToLua(rec)) else f.call()
            }
        } catch (e: Exception) {
            hostUi.toast("插件「${info.name}」执行出错：${e.localizedMessage}")
        } finally {
            currentPluginId = ""
            activePermissions = emptySet()
        }
    }
}
