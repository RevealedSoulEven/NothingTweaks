package com.souleven.nothingos.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.souleven.nothingos.R

internal enum class MonochromeState { ENABLED, DISABLED, BUILT_IN }

internal data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val monochrome: MonochromeState? = null
)

internal class AppPickerAdapter(
    apps: List<AppItem>,
    private val onAppSelected: (AppItem) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var allApps = apps
    private var filteredApps = apps
    private var query = ""

    fun updateApps(apps: List<AppItem>) {
        allApps = apps
        filter(query)
    }

    fun filter(query: String) {
        this.query = query
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
        private val nativeIconLabel = itemView.findViewById<TextView>(R.id.app_native_monochrome)
        private val checkbox = itemView.findViewById<MaterialCheckBox>(R.id.app_selected)

        fun bind(app: AppItem) {
            iconView.setImageDrawable(app.icon)
            labelView.text = app.label
            packageView.text = app.packageName

            val builtIn = app.monochrome == MonochromeState.BUILT_IN
            nativeIconLabel.visibility = if (builtIn) View.VISIBLE else View.GONE
            checkbox.visibility = if (app.monochrome == null) View.GONE else View.VISIBLE
            checkbox.isEnabled = !builtIn
            checkbox.isChecked = app.monochrome != MonochromeState.DISABLED
            checkbox.contentDescription = app.label
            if (builtIn) {
                itemView.setOnClickListener(null)
                checkbox.setOnClickListener(null)
            } else {
                itemView.setOnClickListener { onAppSelected(app) }
                checkbox.setOnClickListener { onAppSelected(app) }
            }
            itemView.isClickable = !builtIn
        }
    }
}
