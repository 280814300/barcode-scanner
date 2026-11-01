package com.maoge.barcodescanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maoge.barcodescanner.databinding.ActivityPluginsBinding
import com.maoge.barcodescanner.databinding.ItemPluginBinding
import com.maoge.barcodescanner.plugin.PluginManager

class PluginsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPluginsBinding
    private lateinit var adapter: PluginListAdapter

    companion object {
        private const val REQ_PICK = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.plugins_title)

        PluginManager.init(applicationContext)

        adapter = PluginListAdapter(
            onToggle = { id, enabled -> PluginManager.setEnabled(id, enabled); refresh() },
            onUninstall = { id, name ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(getString(R.string.confirm_uninstall, name))
                    .setPositiveButton(R.string.uninstall) { _, _ ->
                        PluginManager.uninstall(id)
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnImport.setOnClickListener { pickPlugin() }

        // 从其他应用分享 .bsplugin 进来
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            installFromUri(intent.data!!)
        }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun pickPlugin() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { installFromUri(it) }
        }
    }

    private fun installFromUri(uri: Uri) {
        val name = nameFromUri(uri)
        if (name != null && !name.endsWith(".bsplugin", true) && !name.endsWith(".zip", true)) {
            Toast.makeText(this, "请选择 .bsplugin 或 .zip 插件包", Toast.LENGTH_SHORT).show()
            return
        }
        when (val res = PluginManager.install(uri)) {
            is PluginManager.InstallResult.Ok -> {
                Toast.makeText(this, getString(R.string.plugin_imported, res.info.name), Toast.LENGTH_LONG).show()
                refresh()
            }
            is PluginManager.InstallResult.Err -> {
                Toast.makeText(this, getString(R.string.plugin_import_fail, res.msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun nameFromUri(uri: Uri): String? {
        var n: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i)
            }
        }
        return n
    }

    private fun refresh() {
        val list = PluginManager.list()
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------------- 列表适配器 ----------------
    class PluginListAdapter(
        private val onToggle: (id: String, enabled: Boolean) -> Unit,
        private val onUninstall: (id: String, name: String) -> Unit
    ) : RecyclerView.Adapter<PluginListAdapter.VH>() {

        private val items = mutableListOf<PluginManager.PluginInfo>()

        fun submit(list: List<PluginManager.PluginInfo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val b: ItemPluginBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPluginBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = items[position]
            holder.b.tvName.text = "${info.name}  v${info.version}"
            val bundledTag = if (info.bundled) "  ·  内置" else ""
            holder.b.tvMeta.text = "${info.author}  ·  ${if (info.enabled) "已启用" else "已禁用"}$bundledTag"
            holder.b.tvDesc.text = info.description
            holder.b.swEnabled.isChecked = info.enabled
            holder.b.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(info.id, isChecked)
            }
            holder.b.btnUninstall.setOnClickListener { onUninstall(info.id, info.name) }
        }
    }
}
