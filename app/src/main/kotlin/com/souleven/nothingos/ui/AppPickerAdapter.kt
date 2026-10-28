package com.souleven.nothingos.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.souleven.nothingos.R

data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?
)

class AppPickerAdapter(
    private val allApps: List<AppItem>,
    private val onAppSelected: (AppItem) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var filteredApps: List<AppItem> = allApps

    fun filter(query: String) {
        val q = query.trim().lowercase()
        filteredApps = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun isListEmpty(): Boolean = filteredApps.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.bind(app)
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView = itemView.findViewById<ImageView>(R.id.app_icon)
        private val labelView = itemView.findViewById<TextView>(R.id.app_label)
        private val packageView = itemView.findViewById<TextView>(R.id.app_package)

        fun bind(app: AppItem) {
            iconView.setImageDrawable(app.icon)
            labelView.text = app.label
            packageView.text = app.packageName

            itemView.setOnClickListener {
                onAppSelected(app)
            }
        }
    }
}
