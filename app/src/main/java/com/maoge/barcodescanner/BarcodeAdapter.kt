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
    private val onDelete: (BarcodeRecord) -> Unit
) : RecyclerView.Adapter<BarcodeAdapter.VH>() {

    // view = 当前筛选后的展示列表；records 为全量列表（与 MainActivity 同一引用）
    private val view = mutableListOf<BarcodeRecord>()
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
        notifyDataSetChanged()
    }
}
