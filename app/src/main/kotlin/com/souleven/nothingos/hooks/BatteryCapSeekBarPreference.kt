package com.souleven.nothingos.hooks

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

class BatteryCapSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBarPreference(context, attrs) {

    init {
        min = 50
        max = 95
        seekBarIncrement = 5
        showSeekBarValue = true
        updatesContinuously = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val valueText = holder.findViewById(androidx.preference.R.id.seekbar_value) as? TextView ?: return

        valueText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL), Typeface.NORMAL)
        valueText.paint.isFakeBoldText = false
        valueText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        valueText.setPaddingRelative(valueText.paddingStart, valueText.paddingTop, 0, valueText.paddingBottom)
        valueText.setPadding(valueText.paddingLeft, valueText.paddingTop, 0, valueText.paddingBottom)

        (valueText.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.marginEnd = 0
            lp.rightMargin = 0
            valueText.layoutParams = lp
        }

        (valueText.getTag(androidx.preference.R.id.seekbar_value) as? TextWatcher)?.let {
            valueText.removeTextChangedListener(it)
        }

        fun formatPercent(raw: String?): String {
            if (raw.isNullOrBlank()) return "80%"
            val clean = raw.replace("%", "").trim()
            return if (clean.isNotEmpty()) "$clean%" else "80%"
        }

        valueText.text = formatPercent(valueText.text?.toString())

        var updating = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val str = s?.toString() ?: return
                if (!str.endsWith("%")) {
                    updating = true
                    valueText.text = formatPercent(str)
                    updating = false
                }
            }
        }
        valueText.setTag(androidx.preference.R.id.seekbar_value, watcher)
        valueText.addTextChangedListener(watcher)
    }
}
