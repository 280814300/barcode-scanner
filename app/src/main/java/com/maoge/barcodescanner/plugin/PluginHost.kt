package com.maoge.barcodescanner.plugin

import com.maoge.barcodescanner.BarcodeRecord
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction

/** 单条记录 → Lua table（value/format/timestamp/extras） */
fun recordToLua(r: BarcodeRecord): LuaTable {
    val t = LuaTable()
    t.set("value", LuaValue.valueOf(r.value))
    t.set("format", LuaValue.valueOf(r.format))
    t.set("timestamp", LuaValue.valueOf(r.timestamp.toDouble()))
    t.set("extras", r.extras?.let { jsonToLua(it) } ?: LuaTable())
    return t
}

/** Lua 二维表 → List<List<String>>（用于 CSV 导出） */
fun luaTableToRows(v: LuaValue): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    if (!v.istable()) return rows
    val t = v.checktable()
    val n = t.length()
    for (i in 1..n) {
        val row = t.get(i)
        if (row.istable()) {
            val rt = row.checktable()
            val m = rt.length()
            val r = mutableListOf<String>()
            for (j in 1..m) r.add(rt.get(j).optjstring(""))
            rows.add(r)
        } else {
            rows.add(listOf(row.optjstring("")))
        }
    }
    return rows
}

/**
 * 构建 host 全局表：插件唯一可用的能力入口（白名单）。
 * 任何网络、文件、反射能力都不在此暴露，从根上保证离线安全。
 */
fun buildHostTable(engine: PluginEngine): LuaTable {
    val host = LuaTable()

    // host.version() —— 供插件自报兼容
    host.set("version", object : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.valueOf(PluginManifest.HOST_VERSION)
    })

    // host.time() —— 当前 epoch 毫秒（用于导出文件名等）
    host.set("time", object : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.valueOf(System.currentTimeMillis().toDouble())
    })

    // host.toast(msg)
    host.set("toast", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            engine.hostUi.toast(arg.optjstring(""))
            return LuaValue.NIL
        }
    })

    // host.records() —— 返回全部记录（数组）
    host.set("records", object : ZeroArgFunction() {
        override fun call(): LuaValue {
            val t = LuaTable()
            engine.records.forEachIndexed { i, r -> t.set(i + 1, recordToLua(r)) }
            return t
        }
    })

    // host.get(value) —— 按条码查找单条，没有返回 nil
    host.set("get", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val v = arg.checkjstring()
            val r = engine.records.firstOrNull { it.value == v }
            return if (r != null) recordToLua(r) else LuaValue.NIL
        }
    })

    // host.add(value, format?, extras?) —— 新增记录，已存在返回 false
    host.set("add", object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val value = args.arg(1).checkjstring()
            val format = if (args.narg() >= 2) args.arg(2).optjstring("UNKNOWN") else "UNKNOWN"
            if (engine.records.any { it.value == value }) return LuaValue.FALSE
            val rec = BarcodeRecord(value, format, System.currentTimeMillis())
            if (args.narg() >= 3 && args.arg(3).istable()) {
                rec.extras = luaToJson(args.arg(3)) as? JSONObject
            }
            engine.records.add(0, rec)
            engine.onDataChanged()
            return LuaValue.TRUE
        }
    })

    // host.update(value, extras) —— 更新某条记录的 extras
    host.set("update", object : TwoArgFunction() {
        override fun call(a: LuaValue, b: LuaValue): LuaValue {
            val value = a.checkjstring()
            val r = engine.records.firstOrNull { it.value == value } ?: return LuaValue.FALSE
            if (b.istable()) r.extras = luaToJson(b) as? JSONObject
            engine.onDataChanged()
            return LuaValue.TRUE
        }
    })

    // host.remove(value) —— 删除某条
    host.set("remove", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val value = arg.checkjstring()
            val removed = engine.records.removeIf { it.value == value }
            if (removed) engine.onDataChanged()
            return LuaValue.valueOf(removed)
        }
    })

    // host.product.* —— 离线产品库（69码识别等插件使用）
    val product = LuaTable()
    product.set("lookup", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val bc = arg.checkjstring()
            val p = engine.productLookup(bc)
            return if (p != null) jsonToLua(p) else LuaValue.NIL
        }
    })
    product.set("add", object : TwoArgFunction() {
        override fun call(a: LuaValue, b: LuaValue): LuaValue {
            val bc = a.checkjstring()
            val info = if (b.istable()) (luaToJson(b) as? JSONObject) ?: JSONObject() else JSONObject()
            info.put("barcode", bc)
            engine.productAdd(bc, info)
            return LuaValue.TRUE
        }
    })
    host.set("product", product)

    // host.ui.* —— 界面交互（toast / 表单）
    val ui = LuaTable()
    ui.set("toast", host.get("toast"))
    ui.set("openForm", object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val title = args.arg(1).optjstring("输入")
            val fieldsLua = args.arg(2)
            val cb = args.arg(3)
            val fields = mutableListOf<PluginEngine.FormField>()
            if (fieldsLua.istable()) {
                val t = fieldsLua.checktable()
                val n = t.length()
                for (i in 1..n) {
                    val f = t.get(i)
                    if (f.istable()) {
                        val ft = f.checktable()
                        fields.add(
                            PluginEngine.FormField(
                                key = ft.get("key").optjstring(""),
                                label = ft.get("label").optjstring(""),
                                type = ft.get("type").optjstring("text"),
                                default = ft.get("default").optjstring("")
                            )
                        )
                    }
                }
            }
            engine.hostUi.runOnUi {
                engine.hostUi.showForm(title, fields) { result ->
                    if (cb.isfunction()) {
                        val rt = LuaTable()
                        result.forEach { (k, v) -> rt.set(k, LuaValue.valueOf(v)) }
                        try {
                            cb.call(rt)
                        } catch (e: Exception) {
                            engine.hostUi.toast("表单回调出错：${e.localizedMessage}")
                        }
                    }
                }
            }
            return LuaValue.NIL
        }
    })
    host.set("ui", ui)

    // host.export.* —— 导出 CSV / JSON（复用系统分享选择器）
    val exp = LuaTable()
    exp.set("csv", object : TwoArgFunction() {
        override fun call(a: LuaValue, b: LuaValue): LuaValue {
            val name = a.checkjstring()
            engine.exportCsv(name, luaTableToRows(b))
            return LuaValue.NIL
        }
    })
    exp.set("json", object : TwoArgFunction() {
        override fun call(a: LuaValue, b: LuaValue): LuaValue {
            val name = a.checkjstring()
            engine.exportJson(name, luaToJson(b) ?: JSONObject())
            return LuaValue.NIL
        }
    })
    host.set("export", exp)

    // host.storage.* —— 插件私有键值存储（按当前插件 id 隔离）
    val storage = LuaTable()
    storage.set("get", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val key = arg.checkjstring()
            val v = engine.pluginStorageGet(key)
            return v?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
        }
    })
    storage.set("set", object : TwoArgFunction() {
        override fun call(a: LuaValue, b: LuaValue): LuaValue {
            val key = a.checkjstring()
            val v = if (b.isnil()) null else b.tojstring()
            engine.pluginStorageSet(key, v)
            return LuaValue.NIL
        }
    })
    host.set("storage", storage)

    // host.json.* —— JSON 解析/序列化（供插件处理复杂本地数据）
    val json = LuaTable()
    json.set("parse", object : OneArgFunction() {
        override fun call(a: LuaValue): LuaValue {
            val s = a.checkjstring()
            return try {
                jsonToLua(JSONObject(s))
            } catch (e: Exception) {
                try {
                    jsonToLua(JSONArray(s))
                } catch (e2: Exception) {
                    LuaValue.NIL
                }
            }
        }
    })
    json.set("encode", object : OneArgFunction() {
        override fun call(a: LuaValue): LuaValue {
            val o = luaToJson(a)
            return LuaValue.valueOf(o?.toString() ?: "null")
        }
    })
    host.set("json", json)

    // host.scangun.* —— 外接扫码枪桥（受 network 权限管控）
    // 只有声明了 network 权限的插件才能使用；其余插件调用一律返回"权限不足"，绝不联网。
    val scangun = LuaTable()
    val canNetwork = engine.activePermissions.contains(PluginManifest.PERM_NETWORK)
    if (canNetwork) {
        scangun.set("configure", object : TwoArgFunction() {
            override fun call(a: LuaValue, b: LuaValue): LuaValue {
                ScanGunBridge.configure(a.checkjstring(), b.optint(17533))
                return LuaValue.NIL
            }
        })
        scangun.set("connect", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val token = arg.optjstring("")
                ScanGunBridge.connect(token)
                return LuaValue.valueOf(ScanGunBridge.status)
            }
        })
        scangun.set("disconnect", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                ScanGunBridge.disconnect()
                return LuaValue.NIL
            }
        })
        scangun.set("status", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(ScanGunBridge.status)
        })
        scangun.set("lastError", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(ScanGunBridge.lastError)
        })
        scangun.set("send", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(ScanGunBridge.send(arg.checkjstring()))
        })
    } else {
        val denied = object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("权限不足：需在 manifest 声明 network 权限")
        }
        scangun.set("configure", denied)
        scangun.set("connect", denied)
        scangun.set("disconnect", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("权限不足")
        })
        scangun.set("status", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("denied")
        })
        scangun.set("lastError", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("需要 network 权限")
        })
        scangun.set("send", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.FALSE
        })
    }
    host.set("scangun", scangun)

    return host
}
