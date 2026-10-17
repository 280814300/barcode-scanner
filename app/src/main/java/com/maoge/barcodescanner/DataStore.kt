package com.maoge.barcodescanner

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 本地 JSON 持久化（v1.0：单一 barcodes.json 扁平存储，完全离线）
 */
object DataStore {
    private const val FILE_NAME = "barcodes.json"

    fun load(ctx: Context): List<BarcodeRecord> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { BarcodeRecord.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<BarcodeRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        File(ctx.filesDir, FILE_NAME).writeText(arr.toString())
    }
}
