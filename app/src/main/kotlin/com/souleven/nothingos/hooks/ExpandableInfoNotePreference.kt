package com.souleven.nothingos.hooks

import android.content.Context
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.souleven.nothingos.R

class ExpandableInfoNotePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var isExpanded: Boolean = false

    init {
        layoutResource = R.layout.preference_info_note
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView.findViewById<View>(R.id.info_note_card) ?: holder.itemView
        val summaryView = holder.findViewById(android.R.id.summary) as? TextView
        val arrow = holder.findViewById(R.id.expand_arrow) as? ImageView

        summaryView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        arrow?.rotation = if (isExpanded) 180f else 0f

        card.isClickable = true
        card.setOnClickListener {
            isExpanded = !isExpanded
            val parent = holder.itemView.parent as? ViewGroup
            if (parent != null) {
                TransitionManager.beginDelayedTransition(parent)
            }
            summaryView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
            arrow?.animate()?.rotation(if (isExpanded) 180f else 0f)?.setDuration(200)?.start()
        }
    }
}
