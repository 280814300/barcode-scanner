package com.maoge.barcodescanner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.maoge.barcodescanner.databinding.ItemBarcodeBinding
import java.text.SimpleDateFormat
import java.util.Locale

class BarcodeAdapter(
    private val records: MutableList<BarcodeRecord>,
    private val onCopy: (BarcodeRecord) -> Unit,
    private val onDelete: (BarcodeRecord) -> Unit,
    private val onEdit: (BarcodeRecord) -> Unit
) : RecyclerView.Adapter<BarcodeAdapter.VH>() {

    // view = 当前筛选后的展示列表；records 为全量列表（与 MainActivity 同一引用）
    private val view = mutableListOf<BarcodeRecord>()
    private val selected = mutableSetOf<BarcodeRecord>() // 批量勾选
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    init {
        applyFilter("")
    }

    class VH(val b: ItemBarcodeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBarcodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = view.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = view[position]
        holder.b.tvValue.text = r.value
        holder.b.tvMeta.text = buildMeta(r)
        holder.b.cbSelect.isChecked = selected.contains(r)
        holder.b.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(r) else selected.remove(r)
        }
        // 点击内容区进入编辑
        holder.b.rowContent.setOnClickListener { onEdit(r) }
        holder.b.btnCopy.setOnClickListener { onCopy(r) }
        holder.b.btnDelete.setOnClickListener { onDelete(r) }
    }

    private fun buildMeta(r: BarcodeRecord): String {
        val t = if (r.timestamp > 0) sdf.format(r.timestamp) else ""
        return "${r.format}  ·  $t"
    }

    fun applyFilter(query: String) {
        val q = query.lowercase().trim()
        view.clear()
        view.addAll(
            if (q.isBlank()) records
            else records.filter {
                it.value.lowercase().contains(q) || it.format.lowercase().contains(q)
            }
        )
        selected.clear() // 筛选变更后清空选择，避免误操作隐藏项
        notifyDataSetChanged()
    }

    // ---- 批量选择 ----
    fun getSelected(): List<BarcodeRecord> = selected.toList()

    fun hasSelection(): Boolean = selected.isNotEmpty()

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun isAllSelected(): Boolean = view.isNotEmpty() && selected.size == view.size

    fun toggleSelectAll(): Boolean {
        return if (isAllSelected()) {
            selected.clear()
            notifyDataSetChanged()
            false
        } else {
            selected.clear()
            selected.addAll(view)
            notifyDataSetChanged()
            true
        }
    }
}
