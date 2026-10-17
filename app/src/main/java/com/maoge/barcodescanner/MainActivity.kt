package com.maoge.barcodescanner

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.maoge.barcodescanner.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: BarcodeAdapter
    private val records = mutableListOf<BarcodeRecord>()
    private val seen = mutableSetOf<String>()   // 本次会话内已识别的值，避免连续重复
    private var flashOn = false
    private var scanning = false
    private val CAMERA_PERM = Manifest.permission.CAMERA
    private val REQ_CAMERA = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 载入历史
        records.addAll(DataStore.load(this))
        rebuildSeen()

        adapter = BarcodeAdapter(
            records,
            onCopy = ::copyRecord,
            onDelete = ::deleteRecord,
            onEdit = ::editRecord
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 扫码回调（连续解码）
        binding.barcodeView.decodeContinuous(callback)

        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnClear.setOnClickListener { clearAll() }
        binding.btnScan.setOnClickListener { onScanToggle() }

        // 相机权限：Android 6+ 必须运行时申请，否则预览黑屏、无法扫码
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERM) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERM), REQ_CAMERA)
        }
        binding.etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, p: Int, b: Int, c: Int) {
                adapter.applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            val v = result?.text ?: return
            if (seen.add(v)) {  // 新码才入列
                val rec = BarcodeRecord(
                    value = v,
                    format = result.barcodeFormat?.name ?: "UNKNOWN",
                    timestamp = System.currentTimeMillis()
                )
                records.add(0, rec)
                DataStore.save(this@MainActivity, records)
                adapter.applyFilter(binding.etFilter.text.toString())
                Toast.makeText(this@MainActivity, "已识别：$v", Toast.LENGTH_SHORT).show()
            }
        }

        override fun possibleResultPoints(points: List<ResultPoint?>?) {}
    }

    override fun onResume() {
        super.onResume()
        if (scanning) binding.barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeView.pause()
    }

    // 开始 / 暂停 扫描
    private fun onScanToggle() {
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERM) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERM), REQ_CAMERA)
            return
        }
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!scanning) {
            binding.barcodeView.resume()
            scanning = true
        }
        binding.btnScan.text = getString(R.string.pause_scan)
    }

    private fun stopScan() {
        binding.barcodeView.pause()
        scanning = false
        binding.btnScan.text = getString(R.string.start_scan)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                Toast.makeText(this, R.string.need_camera, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFlash() {
        if (!scanning) {
            Toast.makeText(this, R.string.need_camera, Toast.LENGTH_SHORT).show()
            return
        }
        flashOn = !flashOn
        binding.barcodeView.barcodeView.setTorch(flashOn)
        binding.btnFlash.text = if (flashOn) getString(R.string.flashlight_off) else getString(R.string.flashlight)
    }

    private fun copyRecord(rec: BarcodeRecord) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("barcode", rec.value))
        Toast.makeText(this, "已复制：${rec.value}", Toast.LENGTH_SHORT).show()
    }

    private fun deleteRecord(rec: BarcodeRecord) {
        records.remove(rec)
        rebuildSeen()
        DataStore.save(this, records)
        adapter.applyFilter(binding.etFilter.text.toString())
    }

    // 点击条码进入编辑：修改后自动保存
    private fun editRecord(rec: BarcodeRecord) {
        val et = EditText(this)
        et.setText(rec.value)
        et.selectAll()
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) {
                    rec.value = v
                    rebuildSeen()
                    DataStore.save(this, records)
                    adapter.applyFilter(binding.etFilter.text.toString())
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rebuildSeen() {
        seen.clear()
        seen.addAll(records.map { it.value })
    }

    private fun clearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                records.clear()
                seen.clear()
                DataStore.save(this, records)
                adapter.applyFilter("")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // 批量复制：优先复制勾选项，未勾选则复制全部
    private fun batchCopy() {
        val list = adapter.getSelected().ifEmpty { records }
        val text = list.joinToString("\n") { it.value }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("barcodes", text))
        Toast.makeText(this, getString(R.string.copied_count, list.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    // 导出：优先导出勾选项，未勾选则导出全部
    private fun exportCsv() {
        val list = adapter.getSelected().ifEmpty { records }
        exportFile("barcodes_${ts()}.csv", buildCsv(list), "text/csv")
        Toast.makeText(this, getString(R.string.exported_count, list.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    private fun exportJson() {
        val list = adapter.getSelected().ifEmpty { records }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        exportFile("barcodes_${ts()}.json", arr.toString(2), "application/json")
        Toast.makeText(this, getString(R.string.exported_count, list.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    private fun buildCsv(list: List<BarcodeRecord>): String {
        val sb = StringBuilder("﻿value,format,time\n")  // BOM 保证 Excel 中文不乱码
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        list.forEach { r ->
            val t = if (r.timestamp > 0) sdf.format(r.timestamp) else ""
            sb.append("\"${r.value.replace("\"", "\"\"")}\",${r.format},\"$t\"\n")
        }
        return sb.toString()
    }

    private fun ts(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

    private fun exportFile(name: String, content: String, mime: String) {
        try {
            val file = File(cacheDir, name)
            file.writeText(content)
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Android 11+ 必须显式向所有可接收的应用授予 URI 读取权限，否则目标应用读取时崩溃
            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (r in resInfoList) {
                grantUriPermission(r.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_to)))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.top_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_csv -> { exportCsv(); true }
            R.id.action_json -> { exportJson(); true }
            R.id.action_batch_copy -> { batchCopy(); true }
            R.id.action_select_all -> { adapter.toggleSelectAll(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
