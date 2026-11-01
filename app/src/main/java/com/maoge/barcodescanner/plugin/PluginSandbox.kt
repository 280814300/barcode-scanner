package com.maoge.barcodescanner.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * LuaJ 沙箱：构建受限运行环境，仅保留纯计算能力 + 自定义 host API。
 * 刻意移除 os / io / package / require / load* / luajava / debug，
 * 使第三方插件代码无法访问文件系统、网络、Java 反射或动态加载任意代码，
 * 从而守住"完全离线、零网络"的隐私红线。
 */
object PluginSandbox {
    fun buildGlobals(): Globals {
        val g = JsePlatform.standardGlobals()
        val removed = listOf(
            "os", "io", "package", "require", "load", "loadfile",
            "dofile", "loadstring", "luajava", "debug"
        )
        removed.forEach { g.set(it, LuaValue.NIL) }
        return g
    }
}

/** JSONObject / JSONArray / 基础类型 → LuaValue（递归） */
fun jsonToLua(v: Any?): LuaValue = when (v) {
    null -> LuaValue.NIL
    is JSONObject -> LuaTable().also { t ->
        val it = v.keys()
        while (it.hasNext()) {
            val k = it.next()
            t.set(k, jsonToLua(v.get(k)))
        }
    }
    is JSONArray -> LuaTable().also { t ->
        for (i in 0 until v.length()) t.set(i + 1, jsonToLua(v.get(i)))
    }
    is String -> LuaValue.valueOf(v)
    is Boolean -> LuaValue.valueOf(v)
    is Int -> LuaValue.valueOf(v)
    is Long -> LuaValue.valueOf(v.toDouble())
    is Double -> LuaValue.valueOf(v)
    is Float -> LuaValue.valueOf(v.toDouble())
    else -> LuaValue.valueOf(v.toString())
}

/** LuaValue → JSONObject / JSONArray / 基础类型（递归） */
fun luaToJson(v: LuaValue): Any? = when {
    v.isnil() -> null
    v.isboolean() -> v.toboolean()
    v.isnumber() -> v.todouble()
    v.isstring() -> v.tojstring()
    v.istable() -> {
        val t = v.checktable()
        val n = t.length()
        if (n > 0) {
            val arr = JSONArray()
            for (i in 1..n) arr.put(luaToJson(t.get(i)))
            arr
        } else {
            val o = JSONObject()
            val keys = t.keys()
            for (i in 0 until keys.size) {
                val key = keys[i]
                o.put(key.tojstring(), luaToJson(t.get(key)))
            }
            o
        }
    }
    else -> v.tojstring()
}
