package com.maoge.barcodescanner

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 载入历史
        records.addAll(DataStore.load(this))
        seen.addAll(records.map { it.value })

        adapter = BarcodeAdapter(records, onCopy = ::copyRecord, onDelete = ::deleteRecord)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 扫码回调（连续解码）
        binding.barcodeView.decodeContinuous(callback)

        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnManual.setOnClickListener { manualEntry() }
        binding.btnClear.setOnClickListener { clearAll() }
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
        binding.barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeView.pause()
    }

    private fun toggleFlash() {
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
        seen.remove(rec.value)
        DataStore.save(this, records)
        adapter.applyFilter(binding.etFilter.text.toString())
    }

    private fun manualEntry() {
        val et = EditText(this)
        et.hint = getString(R.string.manual_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.manual)
            .setView(et)
            .setPositiveButton(R.string.add) { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty() && seen.add(v)) {
                    records.add(0, BarcodeRecord(v, "MANUAL", System.currentTimeMillis()))
                    DataStore.save(this, records)
                    adapter.applyFilter(binding.etFilter.text.toString())
                    Toast.makeText(this, R.string.added, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun exportCsv() = exportFile("barcodes_${ts()}.csv", buildCsv(), "text/csv")
    private fun exportJson() {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        exportFile("barcodes_${ts()}.json", arr.toString(2), "application/json")
    }

    private fun buildCsv(): String {
        val sb = StringBuilder("﻿value,format,time\n")  // BOM 保证 Excel 中文不乱码
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        records.forEach { r ->
            val t = if (r.timestamp > 0) sdf.format(r.timestamp) else ""
            sb.append("\"${r.value.replace("\"", "\"\"")}\",${r.format},\"$t\"\n")
        }
        return sb.toString()
    }

    private fun ts(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())

    private fun exportFile(name: String, content: String, mime: String) {
        val file = File(cacheDir, name)
        file.writeText(content)
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_to)))
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.top_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_csv -> { exportCsv(); true }
            R.id.action_json -> { exportJson(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
