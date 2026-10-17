package com.maoge.barcodescanner

/**
 * 单条条码记录。
 * @param duplicate 该条码值是否在历史库中已存在（重复标注）
 */
data class BarcodeEntry(
    val value: String,
    val format: String,
    val timestamp: Long,
    val duplicate: Boolean
)

/**
 * 一个商品：同一扫描区内识别出的多个条码（69码 / SN / IMEI 等）归并到同一商品下。
 */
data class ProductRecord(
    val id: String,
    val barcodes: List<BarcodeEntry>,
    val createdAt: Long,
    val note: String = ""
)
