package com.maoge.barcodescanner

import org.json.JSONObject

/**
 * 单条条码记录（v1.0：扁平本地列表，无按天归档）
 */
data class BarcodeRecord(
    val value: String,
    val format: String,
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("value", value)
        put("format", format)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(o: JSONObject): BarcodeRecord = BarcodeRecord(
            value = o.getString("value"),
            format = o.optString("format", "UNKNOWN"),
            timestamp = o.optLong("timestamp", 0L)
        )
    }
}
