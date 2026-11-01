package com.maoge.barcodescanner.plugin

import org.json.JSONObject

/**
 * 插件清单（plugin.bsmanifest.json）的数据模型。
 * 由第三方插件作者提供，描述插件身份、入口、触发点与权限声明。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val minHostVersion: String,
    val entry: String,
    val description: String,
    val triggers: Set<String>,
    val permissions: Set<String>,
    val dependencies: List<String>
) {
    companion object {
        /** 宿主版本号，用于 minHostVersion 校验 */
        const val HOST_VERSION = "1.03"

        /** 权限：允许插件通过宿主桥进行局域网通信（仅此一项会间接启用网络） */
        const val PERM_NETWORK = "network"

        fun parse(json: String): PluginManifest {
            val o = JSONObject(json)
            fun arr(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            val id = o.getString("id").trim()
            require(id.isNotEmpty()) { "manifest 缺少 id" }
            return PluginManifest(
                id = id,
                name = o.optString("name", id),
                version = o.optString("version", "0.0.0"),
                author = o.optString("author", "unknown"),
                minHostVersion = o.optString("minHostVersion", "1.02"),
                entry = o.optString("entry", "main.lua").trim().ifEmpty { "main.lua" },
                description = o.optString("description", ""),
                triggers = arr("triggers").toSet(),
                permissions = arr("permissions").toSet(),
                dependencies = arr("dependencies")
            )
        }
    }
}

/** 语义化版本比较：a >= b 返回 true（仅支持 x.y.z） */
fun versionAtLeast(a: String, b: String): Boolean {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}
