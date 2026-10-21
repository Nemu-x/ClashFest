package com.github.kr328.clash.design

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.google.android.material.textfield.TextInputEditText

/**
 * Bottom sheet that lists one subscription's servers with a search field. Picking a row closes
 * the sheet and reports the server name; the current choice is checked.
 */
internal class ChainNodeSheet(
    private val context: Context,
    private val title: String,
    private val subtitle: String,
    private val names: List<String>,
    private val selected: String,
    private val onPicked: (String) -> Unit,
) {
    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_chain_nodes, null)
        val dialog = AppBottomSheetDialog(context)
        val list: RecyclerView = root.findViewById(R.id.chain_sheet_list)
        val empty: View = root.findViewById(R.id.chain_sheet_empty)
        val search: TextInputEditText = root.findViewById(R.id.chain_sheet_search)
        root.findViewById<TextView>(R.id.chain_sheet_title).text = title
        root.findViewById<TextView>(R.id.chain_sheet_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        // A fixed share of the screen keeps the sheet from jumping while the list is filtered.
        root.minimumHeight = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()

        val adapter = Adapter(selected) { name ->
            dialog.dismiss()
            onPicked(name)
        }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        fun filter(query: String) {
            val q = query.trim()
            val shown = if (q.isEmpty()) names else names.filter { it.contains(q, ignoreCase = true) }
            adapter.submit(shown)
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        filter("")
        search.doAfterTextChanged { filter(it?.toString().orEmpty()) }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search.clearFocus()
                true
            } else false
        }
        dialog.setContentView(root)
        dialog.show()
        // Bring the current choice into view when the sheet opens.
        val index = names.indexOf(selected)
        if (index > 0) list.post { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 0) }
    }

    private class Adapter(
        private val selected: String,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<String> = emptyList()

        fun submit(value: List<String>) {
            items = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chain_node, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = items[position]
            val checked = name == selected
            holder.name.text = name
            TooltipCompat.setTooltipText(holder.name, name)
            val code = FlagParser.parse(name)?.code
            val size = (32 * holder.itemView.resources.displayMetrics.density).toInt()
            val bitmap = FlagDrawableLoader.loadBitmap(holder.itemView.context, code, size)
            if (bitmap != null) holder.flag.setImageBitmap(bitmap)
            else holder.flag.setImageResource(R.drawable.ic_baseline_language)
            holder.check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            holder.itemView.isSelected = checked
            ViewCompat.setStateDescription(holder.itemView, if (checked) holder.itemView.context.getString(R.string.chain_selected) else null)
            holder.itemView.setOnClickListener { onClick(name) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val flag: ImageView = view.findViewById(R.id.chain_node_flag)
            val name: TextView = view.findViewById(R.id.chain_node_name)
            val check: ImageView = view.findViewById(R.id.chain_node_check)
        }
    }
}
