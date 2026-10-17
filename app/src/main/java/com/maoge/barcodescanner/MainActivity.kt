package com.maoge.barcodescanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.YuvImage
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.maoge.barcodescanner.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val MODE_MANUAL = 0
    private val MODE_BATCH = 1
    private var mode = MODE_MANUAL

    // 当前正在构建的"商品"（同一扫描区关联出的多个条码）
    private val currentProduct = mutableListOf<BarcodeEntry>()
    // 本批连续扫码（用于"复制本批"）
    private val sessionBatch = mutableListOf<BarcodeEntry>()
    // 今日已归档商品
    private val todayProducts = mutableListOf<ProductRecord>()
    // 全局已知条码值（跨天），用于重复标注
    private val knownValues = mutableSetOf<String>()

    private var latestDetections = emptyList<DetectedBarcode>()

    private var camera: Camera? = null
    private var flashOn = false
    private var isProcessing = false

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "需要摄像头权限才能扫描", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        knownValues.addAll(DataStore.loadAllValues(this))
        todayProducts.addAll(DataStore.loadProducts(this, DataStore.todayStr()))

        setupModeButtons()
        binding.flashBtn.setOnClickListener { toggleFlash() }
        binding.captureAllBtn.setOnClickListener { captureAll() }
        binding.addManualBtn.setOnClickListener { addManual() }
        binding.finishBtn.setOnClickListener { finishProduct() }
        binding.copyBatchBtn.setOnClickListener { copyBatch() }
        binding.clearCurrentBtn.setOnClickListener { clearCurrent() }
        binding.exportCsvBtn.setOnClickListener { exportFile("csv") }
        binding.exportJsonBtn.setOnClickListener { exportFile("json") }

        binding.overlay.onSelect = { value, format -> captureBarcode(value, format) }

        refreshCurrentList()
        refreshTodayList()
        setMode(MODE_MANUAL)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupModeButtons() {
        binding.modeManual.setOnClickListener { setMode(MODE_MANUAL) }
        binding.modeBatch.setOnClickListener { setMode(MODE_BATCH) }
    }

    private fun setMode(m: Int) {
        mode = m
        val manualSel = m == MODE_MANUAL
        binding.modeManual.alpha = if (manualSel) 1f else 0.45f
        binding.modeBatch.alpha = if (!manualSel) 1f else 0.45f
        binding.statusText.text = if (manualSel)
            "手动模式：点击画面中或下方列表里的某个条码即可选中"
        else
            "批量模式：点击「识别全部」把扫描区内所有条码归入当前商品"
    }

    // ---------------- 摄像头 ----------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)

                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, analysis)
                binding.statusText.text = "摄像头就绪，将商品条码移入取景框"
            } catch (e: Exception) {
                binding.statusText.text = "摄像头启动失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (isProcessing) { imageProxy.close(); return@Analyzer }
        isProcessing = true
        val bitmap = try { imageProxy.toBitmap() } catch (e: Exception) { null }
        val bw = bitmap?.width ?: 0
        val bh = bitmap?.height ?: 0
        imageProxy.close()
        if (bitmap == null) { isProcessing = false; return@Analyzer }
        val dets = ZxingMultiDecoder.decode(bitmap)
        bitmap.recycle()
        runOnUiThread {
            onDetections(dets, bw, bh)
            isProcessing = false
        }
    }

    private fun onDetections(dets: List<DetectedBarcode>, bw: Int, bh: Int) {
        latestDetections = dets
        binding.overlay.detections = dets
        binding.overlay.imageW = bw
        binding.overlay.imageH = bh
        binding.overlay.invalidate()

        // 去重（同一帧可能重复识别同一码）
        val unique = dets.distinctBy { it.value }
        binding.detectedLabel.text = if (unique.isEmpty()) "扫描区未检测到条码" else "扫描区检测到 ${unique.size} 个条码（点击选中 / 识别全部）"
        binding.chipContainer.removeAllViews()
        for (d in unique) {
            binding.chipContainer.addView(makeChip(d.value) { captureBarcode(d.value, d.format) })
        }
    }

    private fun makeChip(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setPadding(24, 10, 24, 10)
        tv.textSize = 13f
        tv.setTextColor(0xFF000000.toInt())
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(0xFFE0F7FA.toInt())
        bg.cornerRadius = 24f
        bg.setStroke(2, 0xFF00ACC1.toInt())
        tv.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 12, 0)
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        return tv
    }

    // ---------------- 采集逻辑 ----------------
    private fun captureBarcode(value: String, format: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        if (currentProduct.any { it.value == v }) {
            Toast.makeText(this, "该条码已在当前商品中", Toast.LENGTH_SHORT).show()
            return
        }
        val dup = knownValues.contains(v)
        val entry = BarcodeEntry(v, format, System.currentTimeMillis(), dup)
        currentProduct.add(entry)
        sessionBatch.add(entry)
        refreshCurrentList()
        binding.statusText.text = if (dup) "已添加（重复！库中已存在）：$v" else "已添加：$v"
    }

    private fun captureAll() {
        if (latestDetections.isEmpty()) {
            Toast.makeText(this, "当前未检测到条码", Toast.LENGTH_SHORT).show()
            return
        }
        var n = 0
        for (d in latestDetections.distinctBy { it.value }) {
            val before = currentProduct.size
            captureBarcode(d.value, d.format)
            if (currentProduct.size > before) n++
        }
        Toast.makeText(this, "已识别 $n 个条码到当前商品", Toast.LENGTH_SHORT).show()
    }

    private fun addManual() {
        val v = binding.manualInput.text.toString().trim()
        if (v.isEmpty()) return
        captureBarcode(v, "手动录入")
        binding.manualInput.text?.clear()
    }

    private fun finishProduct() {
        if (currentProduct.isEmpty()) {
            Toast.makeText(this, "当前商品没有条码", Toast.LENGTH_SHORT).show()
            return
        }
        val record = ProductRecord(
            id = UUID.randomUUID().toString(),
            barcodes = currentProduct.toList(),
            createdAt = System.currentTimeMillis()
        )
        todayProducts.add(record)
        DataStore.saveProducts(this, DataStore.todayStr(), todayProducts)
        for (b in record.barcodes) knownValues.add(b.value)
        currentProduct.clear()
        refreshCurrentList()
        refreshTodayList()
        binding.statusText.text = "已保存商品，含 ${record.barcodes.size} 个条码"
        Toast.makeText(this, "商品已归档（今日第 ${todayProducts.size} 个）", Toast.LENGTH_SHORT).show()
    }

    private fun copyBatch() {
        if (sessionBatch.isEmpty()) {
            Toast.makeText(this, "本批还没有条码", Toast.LENGTH_SHORT).show()
            return
        }
        val text = sessionBatch.joinToString("\n") { it.value }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("batch_barcodes", text))
        Toast.makeText(this, "已复制本批 ${sessionBatch.size} 个条码到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrent() {
        if (currentProduct.isEmpty()) {
            Toast.makeText(this, "当前商品已为空", Toast.LENGTH_SHORT).show()
            return
        }
        currentProduct.clear()
        refreshCurrentList()
        binding.statusText.text = "已清空当前商品（本批连续扫码记录保留）"
    }

    // ---------------- 列表渲染 ----------------
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private fun refreshCurrentList() {
        binding.currentList.removeAllViews()
        if (currentProduct.isEmpty()) {
            binding.currentList.addView(labelRow("（暂无，扫码或点选后在此显示）"))
            return
        }
        currentProduct.forEachIndexed { idx, e ->
            binding.currentList.addView(barcodeRow(e, idx + 1) {
                currentProduct.remove(e)
                sessionBatch.remove(e)
                refreshCurrentList()
            })
        }
    }

    private fun refreshTodayList() {
        binding.todayList.removeAllViews()
        if (todayProducts.isEmpty()) {
            binding.todayList.addView(labelRow("（今日暂无归档）"))
            return
        }
        val sdf = SimpleDateFormat("HH:mm", Locale.CHINA)
        todayProducts.asReversed().forEach { p ->
            val header = TextView(this)
            header.text = "商品 · ${sdf.format(Date(p.createdAt))} · ${p.barcodes.size} 个条码"
            header.textSize = 13f
            header.setTypeface(null, android.graphics.Typeface.BOLD)
            header.setPadding(0, 8, 0, 4)
            binding.todayList.addView(header)

            p.barcodes.forEach { e ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val tv = TextView(this)
                tv.text = if (e.duplicate) "🔁 ${e.value} ［重复］" else e.value
                tv.textSize = 13f
                tv.setPadding(8, 4, 8, 4)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tv.layoutParams = lp
                row.addView(tv)
                row.addView(miniButton("复制") {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("bc", e.value))
                    Toast.makeText(this, "已复制：${e.value}", Toast.LENGTH_SHORT).show()
                })
                binding.todayList.addView(row)
            }
        }
    }

    private fun labelRow(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setPadding(4, 6, 4, 6)
        return tv
    }

    private fun barcodeRow(e: BarcodeEntry, no: Int, onRemove: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val tv = TextView(this)
        tv.text = "$no. ${e.value} ［${e.format}］${if (e.duplicate) "  🔁重复" else ""}"
        tv.textSize = 13f
        tv.setPadding(4, 6, 4, 6)
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(tv)
        row.addView(miniButton("复制") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bc", e.value))
            Toast.makeText(this, "已复制：${e.value}", Toast.LENGTH_SHORT).show()
        })
        row.addView(miniButton("删") { onRemove() })
        return row
    }

    private fun miniButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(0xFF006064.toInt())
        tv.setPadding(14, 6, 14, 6)
        tv.setOnClickListener { onClick() }
        return tv
    }

    // ---------------- 闪光灯 ----------------
    private fun toggleFlash() {
        val ctrl = camera?.cameraControl ?: return
        flashOn = !flashOn
        ctrl.enableTorch(flashOn)
        binding.flashBtn.alpha = if (flashOn) 1f else 0.6f
    }

    // ---------------- 导出 ----------------
    private fun exportFile(kind: String) {
        if (todayProducts.isEmpty()) {
            Toast.makeText(this, "今日暂无归档可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val file = File(cacheDir, "barcodes_$stamp.$kind")
        val content = if (kind == "csv") buildCSV() else buildJSON()
        if (kind == "csv") {
            file.writeBytes(("\uFEFF" + content).toByteArray(Charsets.UTF_8))
        } else {
            file.writeText(content)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (kind == "csv") "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出今日条码"))
    }

    private fun buildCSV(): String {
        val sb = StringBuilder()
        sb.append("商品时间,序号,条码值,类型,扫描时间,是否重复\r\n")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        for (p in todayProducts) {
            val pTime = sdf.format(Date(p.createdAt))
            p.barcodes.forEachIndexed { i, b ->
                sb.append(csvCell(pTime)).append(",")
                    .append(csvCell((i + 1).toString())).append(",")
                    .append(csvCell(b.value)).append(",")
                    .append(csvCell(b.format)).append(",")
                    .append(csvCell(sdf.format(Date(b.timestamp)))).append(",")
                    .append(csvCell(if (b.duplicate) "是" else "否")).append("\r\n")
            }
        }
        return sb.toString()
    }

    private fun buildJSON(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val root = org.json.JSONArray()
        for (p in todayProducts) {
            val po = org.json.JSONObject()
            po.put("productTime", sdf.format(Date(p.createdAt)))
            val ba = org.json.JSONArray()
            for (b in p.barcodes) {
                val bo = org.json.JSONObject()
                bo.put("value", b.value)
                bo.put("format", b.format)
                bo.put("scanTime", sdf.format(Date(b.timestamp)))
                bo.put("duplicate", b.duplicate)
                ba.put(bo)
            }
            po.put("barcodes", ba)
            root.put(po)
        }
        return root.toString(2)
    }

    private fun csvCell(v: String): String =
        if (v.contains(",") || v.contains("\"") || v.contains("\r") || v.contains("\n"))
            "\"" + v.replace("\"", "\"\"") + "\"" else v

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}

// ---------------- ImageProxy -> Bitmap（含旋转修正） ----------------
private fun ImageProxy.toBitmap(): Bitmap {
    val w = width
    val h = height
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer

    val ySize = yPlane.remaining()
    val nv21 = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))

    val yRowStride = planes[0].rowStride
    val yPixelStride = planes[0].pixelStride
    var pos = 0
    if (yPixelStride == 1) {
        for (row in 0 until h) {
            yPlane.position(row * yRowStride)
            yPlane.get(nv21, row * w, w)
        }
    } else {
        for (row in 0 until h) {
            for (col in 0 until w) {
                nv21[row * w + col] = yPlane.get(row * yRowStride + col * yPixelStride)
            }
        }
    }
    pos = w * h
    val uvRowStride = planes[1].rowStride
    val uvPixelStride = planes[1].pixelStride
    val uvW = (w + 1) / 2
    val uvH = (h + 1) / 2
    val vRowStride = planes[2].rowStride
    val vPixelStride = planes[2].pixelStride
    for (row in 0 until uvH) {
        for (col in 0 until uvW) {
            val uIndex = row * uvRowStride + col * uvPixelStride
            val vIndex = row * vRowStride + col * vPixelStride
            nv21[pos++] = vPlane.get(vIndex)
            nv21[pos++] = uPlane.get(uIndex)
        }
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 90, out)
    val jpeg = out.toByteArray()
    val base = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IllegalStateException("decode fail")
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return base
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
}
