package com.maoge.barcodescanner

import org.json.JSONObject

/**
 * 单条条码记录（v1.02：在 v1.0 扁平结构基础上增加 extras，
 * 供插件写入扩展信息，如 69 码商品名、盘点数量等。向后兼容旧数据）
 * value 使用 var：支持在列表内直接编辑并保存。
 */
data class BarcodeRecord(
    var value: String,
    val format: String,
    val timestamp: Long,
    var extras: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("value", value)
        put("format", format)
        put("timestamp", timestamp)
        if (extras != null) put("extras", extras)
    }

    companion object {
        fun fromJson(o: JSONObject): BarcodeRecord = BarcodeRecord(
            value = o.getString("value"),
            format = o.optString("format", "UNKNOWN"),
            timestamp = o.optLong("timestamp", 0L),
            extras = if (o.has("extras")) o.optJSONObject("extras") else null
        )
    }
}
