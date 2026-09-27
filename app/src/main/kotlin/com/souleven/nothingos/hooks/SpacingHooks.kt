package com.souleven.nothingos.hooks

import android.content.Context
import android.graphics.Insets
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.souleven.nothingos.R
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class StatusBarPaddingSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBarPreference(context, attrs) {

    init {
        min = -1
        max = 20
        seekBarIncrement = 1
        showSeekBarValue = true
        updatesContinuously = true
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

        (valueText.parent as? ViewGroup)?.let { parent ->
            parent.setPaddingRelative(parent.paddingStart, parent.paddingTop, 0, parent.paddingBottom)
            parent.setPadding(parent.paddingLeft, parent.paddingTop, 0, parent.paddingBottom)
            (parent.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginEnd = 0
                lp.rightMargin = 0
                parent.layoutParams = lp
            }
        }

        (valueText.parent?.parent as? ViewGroup)?.let { grandParent ->
            if (grandParent !== holder.itemView) {
                grandParent.setPaddingRelative(grandParent.paddingStart, grandParent.paddingTop, 0, grandParent.paddingBottom)
                grandParent.setPadding(grandParent.paddingLeft, grandParent.paddingTop, 0, grandParent.paddingBottom)
                (grandParent.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginEnd = 0
                    lp.rightMargin = 0
                    grandParent.layoutParams = lp
                }
            }
        }

        (valueText.getTag(androidx.preference.R.id.seekbar_value) as? TextWatcher)?.let {
            valueText.removeTextChangedListener(it)
        }

        if (valueText.text?.toString() == "-1") {
            valueText.text = context.getString(R.string.status_bar_padding_default)
        }

        var updating = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                if (s?.toString() == "-1") {
                    updating = true
                    valueText.text = context.getString(R.string.status_bar_padding_default)
                    updating = false
                }
            }
        }
        valueText.setTag(androidx.preference.R.id.seekbar_value, watcher)
        valueText.addTextChangedListener(watcher)
    }
}

class SpacingHooks : HookModule {
    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        try {
            val insetsClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.layout.StatusBarContentInsetsProviderImpl",
                lpparam.classLoader
            )
            if (insetsClass != null) {
                val insetsHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val step = prefs.getInt("pref_status_bar_padding", -1)
                        if (step < 0) return
                        val padding = step * 5
                        val orig = param.result as? Insets
                        param.result = if (orig != null) {
                            Insets.of(padding, orig.top, padding, orig.bottom)
                        } else {
                            Insets.of(padding, 0, padding, 0)
                        }
                    }
                }
                XposedBridge.hookAllMethods(insetsClass, "getStatusBarContentInsetsForCurrentRotation", insetsHook)
                XposedBridge.hookAllMethods(insetsClass, "getStatusBarContentInsetsForRotation", insetsHook)
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [SpacingHooks] FAILED to hook StatusBarContentInsetsProviderImpl: ${t.message}")
        }

        try {
            val statusBarViewClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                lpparam.classLoader
            )
            if (statusBarViewClass != null) {
                val paddingHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val step = prefs.getInt("pref_status_bar_padding", -1)
                        if (step < 0) return
                        param.result = step * 5
                    }
                }
                XposedBridge.hookAllMethods(statusBarViewClass, "getPaddingStart", paddingHook)
                XposedBridge.hookAllMethods(statusBarViewClass, "getPaddingEnd", paddingHook)
            }
        } catch (t: Throwable) {
            XposedBridge.log("NothingTweaks: [SpacingHooks] FAILED to hook PhoneStatusBarView: ${t.message}")
        }
    }
}
