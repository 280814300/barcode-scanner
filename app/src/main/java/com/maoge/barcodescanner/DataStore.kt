package com.maoge.barcodescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地数据层：按天归档到 filesDir/barcodes_YYYY-MM-DD.json。
 * 所有数据仅存本机，不上传任何云端。
 */
object DataStore {

    private fun dayFile(context: Context, day: String): File =
        File(context.filesDir, "barcodes_$day.json")

    fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun loadProducts(context: Context, day: String): MutableList<ProductRecord> {
        val f = dayFile(context, day)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<ProductRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val barcodes = mutableListOf<BarcodeEntry>()
                val ba = o.getJSONArray("barcodes")
                for (j in 0 until ba.length()) {
                    val b = ba.getJSONObject(j)
                    barcodes.add(
                        BarcodeEntry(
                            b.getString("value"),
                            b.getString("format"),
                            b.getLong("timestamp"),
                            b.optBoolean("duplicate")
                        )
                    )
                }
                list.add(
                    ProductRecord(
                        o.getString("id"),
                        barcodes,
                        o.getLong("createdAt"),
                        o.optString("note", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveProducts(context: Context, day: String, products: List<ProductRecord>) {
        val arr = JSONArray()
        for (p in products) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("createdAt", p.createdAt)
            o.put("note", p.note)
            val ba = JSONArray()
            for (b in p.barcodes) {
                val bo = JSONObject()
                bo.put("value", b.value)
                bo.put("format", b.format)
                bo.put("timestamp", b.timestamp)
                bo.put("duplicate", b.duplicate)
                ba.put(bo)
            }
            o.put("barcodes", ba)
            arr.put(o)
        }
        dayFile(context, day).writeText(arr.toString())
    }

    /** 加载全部历史日归档中的条码值，用于"重复"判定（跨天全局比对）。 */
    fun loadAllValues(context: Context): MutableSet<String> {
        val set = mutableSetOf<String>()
        context.filesDir.listFiles { _, name ->
            name.startsWith("barcodes_") && name.endsWith(".json")
        }?.forEach { f ->
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val ba = arr.getJSONObject(i).getJSONArray("barcodes")
                    for (j in 0 until ba.length()) {
                        set.add(ba.getJSONObject(j).getString("value"))
                    }
                }
            } catch (e: Exception) {
                // 忽略损坏文件
            }
        }
        return set
    }
}
