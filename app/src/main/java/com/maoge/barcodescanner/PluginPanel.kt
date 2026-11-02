package com.maoge.barcodescanner

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.maoge.barcodescanner.databinding.DialogPluginPanelBinding
import com.maoge.barcodescanner.plugin.PluginManager

/**
 * 右上角「设置 - 插件」面板。
 *
 * 直接在当前界面弹出（不再跳转整页），列出全部已安装插件，含随 APK 内置的
 * 「外接扫码枪」。每一项都能即时启用 / 关闭，关闭后该插件的 on_scan / on_menu
 * 触发器立即失效（外接扫码枪还会顺手断开已建立的长连接）。
 *
 * 导入 .bsplugin 与卸载仍由插件中心承担，面板底部提供入口。
 */
object PluginPanel {

    fun show(ctx: Context, onOpenCenter: () -> Unit) {
        // 确保注册表已就绪（内部带回填内置插件的逻辑，重复调用是幂等的）
        PluginManager.init(ctx.applicationContext)

        val b = DialogPluginPanelBinding.inflate(LayoutInflater.from(ctx))

        // 先声明再赋值：开关与卸载回调里需要回写列表，不能在初始化表达式内自引用
        lateinit var adapter: PluginsActivity.PluginListAdapter
        adapter = PluginsActivity.PluginListAdapter(
            onToggle = { id, enabled ->
                PluginManager.setEnabled(id, enabled)
                // 重新取一次注册表，让「已启用 / 已禁用」文案与开关保持一致
                adapter.submit(PluginManager.list())
                renderEmpty(b)
            },
            onUninstall = { id, name ->
                AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.confirm_uninstall, name))
                    .setPositiveButton(R.string.uninstall) { _, _ ->
                        PluginManager.uninstall(id)
                        adapter.submit(PluginManager.list())
                        renderEmpty(b)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        b.recyclerView.layoutManager = LinearLayoutManager(ctx)
        b.recyclerView.adapter = adapter
        adapter.submit(PluginManager.list())
        renderEmpty(b)

        b.btnOpenCenter.setOnClickListener { onOpenCenter() }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.plugins)
            .setView(b.root)
            .setNegativeButton(R.string.plugin_panel_close, null)
            .create()
            .show()
    }

    private fun renderEmpty(b: DialogPluginPanelBinding) {
        val empty = b.recyclerView.adapter?.itemCount == 0
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
