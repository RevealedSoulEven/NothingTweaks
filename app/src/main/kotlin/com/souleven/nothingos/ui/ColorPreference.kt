package com.souleven.nothingos.ui

import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.souleven.nothingos.R
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.Preference


class ColorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    companion object {
        const val VALUE_OFF = "off"

        private val PALETTE = listOf(
            "#FFFFFFFF" to "White",
            "#FFD71921" to "Nothing red",
            "#FFFF5722" to "Orange",
            "#FFFFC107" to "Amber",
            "#FF4CAF50" to "Green",
            "#FF00BCD4" to "Cyan",
            "#FF2196F3" to "Blue",
            "#FF7C4DFF" to "Violet",
            "#FFE91E63" to "Pink",
            "#FF9E9E9E" to "Grey",
            "#FF000000" to "Black"
        )

        private const val SWATCHES_PER_ROW = 6
        private const val SWATCH_DP = 44
        private const val SWATCH_MARGIN_DP = 6
    }

    private var value: String = VALUE_OFF

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index) ?: VALUE_OFF
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedString(defaultValue as? String ?: VALUE_OFF)
        updateSummary()
    }

    override fun onClick() {
        showPicker()
    }

    private fun setValue(newValue: String) {
        if (!callChangeListener(newValue)) return
        value = newValue
        persistString(newValue)
        updateSummary()
    }

    private fun updateSummary() {
        summary = if (value == VALUE_OFF) {
            "Off — keep the stock adaptive colour"
        } else {
            val label = PALETTE.firstOrNull { it.first.equals(value, ignoreCase = true) }?.second
            if (label != null) "$label · $value" else "Custom · $value"
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    private fun showPicker() {
        val pad = dp(20)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val hexField = EditText(context).apply {
            hint = "#AARRGGBB"
            setSingleLine()
            if (this@ColorPreference.value != VALUE_OFF) setText(this@ColorPreference.value)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val tv = TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
            setTextColor(tv.data)
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, tv, true)
            setHintTextColor(tv.data)
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)
            val strokeCol = tv.data
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), strokeCol)
            }
        }

        val preview = TextView(context).apply {
            text = "Preview"
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
        }

        fun applyPreview(color: Int?) {
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(color ?: Color.TRANSPARENT)
            }
            preview.setTextColor(
                if (color == null) Color.GRAY
                else if (isDark(color)) Color.WHITE else Color.BLACK
            )
            preview.text = if (color == null) "No colour selected" else "Preview"
        }

        applyPreview(parse(value))

        PALETTE.chunked(SWATCHES_PER_ROW).forEach { rowColors ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowColors.forEach { (hex, label) ->
                val color = parse(hex) ?: Color.TRANSPARENT
                val swatch = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(SWATCH_DP), dp(SWATCH_DP)).apply {
                        val m = dp(SWATCH_MARGIN_DP)
                        setMargins(m, m, m, m)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(1), Color.GRAY)
                    }
                    contentDescription = label
                    isClickable = true
                    setOnClickListener {
                        hexField.setText(hex)
                        applyPreview(color)
                    }
                }
                row.addView(swatch)
            }
            root.addView(row)
        }

        hexField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyPreview(parse(s?.toString() ?: ""))
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        root.addView(TextView(context).apply {
            text = "Or enter a hex value"
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(hexField)
        root.addView(preview)

        MaterialAlertDialogBuilder(context)
            .setBackground(ContextCompat.getDrawable(context, R.drawable.dialog_background))
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Apply") { _, _ ->
                val typed = hexField.text?.toString()?.trim().orEmpty()
                val parsed = parse(typed)
                if (parsed != null) {
                    setValue(normalise(typed))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parse(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("#") ?: return null
        if (hex.equals(VALUE_OFF, ignoreCase = true)) return null
        return try {
            when (hex.length) {
                8 -> hex.toLong(16).toInt()
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun normalise(raw: String): String {
        val color = parse(raw) ?: return VALUE_OFF
        return String.format("#%08X", color)
    }

    private fun isDark(color: Int): Boolean {
        val luminance =
            0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance < 140
    }
}
