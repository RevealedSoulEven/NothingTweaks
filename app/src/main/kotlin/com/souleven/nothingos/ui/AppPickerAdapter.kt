package com.souleven.nothingos.ui

import android.content.ComponentName
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.souleven.nothingos.R

internal enum class MonochromeState { ENABLED, DISABLED, REQUIRES_ICON_PACK }

internal data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val monochrome: MonochromeState? = null,
    val nativeMonochromeIcon: Drawable? = null,
    val launcherComponent: ComponentName? = null
)

internal class AppPickerAdapter(
    apps: List<AppItem>,
    private val onAppSelected: (AppItem) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    private var allApps = apps
    private var filteredApps = apps
    private var query = ""
    private var previewActive = false
    private val previewIcons = mutableMapOf<String, Drawable>()

    fun updateApps(apps: List<AppItem>) {
        allApps = apps
        filter(query)
    }

    fun filter(query: String) {
        this.query = query
        val q = query.trim().lowercase()
        val previous = filteredApps
        val matching = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        filteredApps = matching
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = matching.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition].packageName == matching[newItemPosition].packageName
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == matching[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    fun setMonochromePreview(active: Boolean) {
        if (previewActive == active) return
        previewActive = active
        notifyItemRangeChanged(0, itemCount, PREVIEW_PAYLOAD)
    }

    fun updateMonochromeIcons(icons: Map<String, Drawable>) {
        previewIcons.putAll(icons)
        if (previewActive) {
            filteredApps.forEachIndexed { index, app ->
                if (app.packageName in icons) notifyItemChanged(index, PREVIEW_PAYLOAD)
            }
        }
    }

    fun clearMonochromeIcons() {
        previewIcons.clear()
        if (previewActive) notifyItemRangeChanged(0, itemCount, PREVIEW_PAYLOAD)
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

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === PREVIEW_PAYLOAD }) {
            holder.bindIcon(filteredApps[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView = itemView.findViewById<ImageView>(R.id.app_icon)
        private val labelView = itemView.findViewById<TextView>(R.id.app_label)
        private val packageView = itemView.findViewById<TextView>(R.id.app_package)
        private val monochromeRequirement = itemView.findViewById<TextView>(R.id.app_monochrome_requirement)
        private val checkbox = itemView.findViewById<MaterialCheckBox>(R.id.app_selected)

        fun bindIcon(app: AppItem) {
            val showPreview = previewActive && app.monochrome != null &&
                app.monochrome != MonochromeState.REQUIRES_ICON_PACK
            val preview = if (showPreview) previewIcons[app.packageName] ?: app.nativeMonochromeIcon else null
            iconView.colorFilter = if (showPreview && preview == null) GRAYSCALE_PREVIEW else null
            iconView.setImageDrawable(preview ?: app.icon)
        }

        fun bind(app: AppItem) {
            bindIcon(app)
            labelView.text = app.label
            packageView.text = app.packageName

            val requiresPack = app.monochrome == MonochromeState.REQUIRES_ICON_PACK
            monochromeRequirement.visibility = if (requiresPack) View.VISIBLE else View.GONE
            checkbox.visibility = if (app.monochrome == null) View.GONE else View.VISIBLE
            checkbox.isEnabled = !requiresPack
            checkbox.isChecked = app.monochrome == MonochromeState.ENABLED
            checkbox.contentDescription = app.label
            if (requiresPack) {
                itemView.setOnClickListener(null)
                checkbox.setOnClickListener(null)
            } else {
                itemView.setOnClickListener { onAppSelected(app) }
                checkbox.setOnClickListener { onAppSelected(app) }
            }
            itemView.isClickable = !requiresPack
        }
    }

    private companion object {
        val PREVIEW_PAYLOAD = Any()
        val GRAYSCALE_PREVIEW = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}
