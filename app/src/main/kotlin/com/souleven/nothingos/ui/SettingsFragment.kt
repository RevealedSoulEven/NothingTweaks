package com.souleven.nothingos.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.souleven.nothingos.MainHook
import com.souleven.nothingos.R
import com.souleven.nothingos.hooks.BatteryCapSeekBarPreference
import com.souleven.nothingos.hooks.RemotePreferenceProvider

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        RemotePreferenceProvider.getDeviceProtectedPrefs(requireContext())
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = MainHook.PREFS_NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        setPreferencesFromResource(R.xml.preferences, rootKey)

        wireFingerprintDependencies()
        wireNetworkTrafficDependencies()
        wireBatteryCapDependencies()

        requireRebootOnEnable("allow_180_rotation")
        requireRebootOnEnable("pref_advanced_power_menu")
        requireRebootOnEnable("pref_back_gesture_kill")
        requireRebootOnEnable("pref_disable_flag_secure")
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        wireNetworkTrafficDependencies()
        wireBatteryCapDependencies()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        try {
            val uri = if (key != null) {
                Uri.withAppendedPath(RemotePreferenceProvider.CONTENT_URI, key)
            } else {
                RemotePreferenceProvider.CONTENT_URI
            }
            ctx.contentResolver.notifyChange(uri, null)
        } catch (_: Throwable) {}

        try {
            val intent = Intent("com.souleven.nothingos.PREFERENCES_CHANGED").apply {
                setPackage("android")
                putExtra("key", key)
                if (key == "pref_battery_cap_level" || key == "pref_battery_cap_enabled" || key == "pref_battery_cap_notify") {
                    action = "com.souleven.nothingos.BATTERY_CAP_CHANGED"
                    val p = sharedPreferences
                    if (p != null) {
                        putExtra("pref_battery_cap_enabled", p.getBoolean("pref_battery_cap_enabled", false))
                        putExtra("pref_battery_cap_level", p.getInt("pref_battery_cap_level", 80))
                        putExtra("pref_battery_cap_notify", p.getBoolean("pref_battery_cap_notify", true))
                    }
                }
            }
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun requireRebootOnEnable(preferenceKey: String) {
        findPreference<SwitchPreferenceCompat>(preferenceKey)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Please reboot your phone to enable this feature.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.isNestedScrollingEnabled = false
        listView.clipToPadding = false
    }

    private fun wireFingerprintDependencies() {
        val master = findPreference<SwitchPreferenceCompat>("fp_color_enabled") ?: return
        val random = findPreference<SwitchPreferenceCompat>("fp_random") ?: return
        val color = findPreference<ColorPreference>("fp_color") ?: return

        fun apply(masterOn: Boolean, randomOn: Boolean) {
            random.isEnabled = masterOn
            color.isEnabled = masterOn && !randomOn
        }

        master.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val on = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            if (!on) {
                random.isChecked = true
            }
            apply(on, random.isChecked)
            true
        }

        random.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val randomOn = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            apply(master.isChecked, randomOn)
            true
        }

        apply(master.isChecked, random.isChecked)
    }

    private fun wireNetworkTrafficDependencies() {
        val master = findPreference<SwitchPreferenceCompat>("pref_network_traffic_enabled") ?: return
        val mode = findPreference<ListPreference>("pref_network_traffic_mode") ?: return
        val arrows = findPreference<SwitchPreferenceCompat>("pref_network_traffic_show_arrows") ?: return

        fun apply(masterOn: Boolean, currentMode: String) {
            val isSeparate = currentMode == "separate"
            arrows.isEnabled = masterOn && isSeparate
        }

        master.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val on = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            apply(on, mode.value ?: "combined")
            true
        }

        mode.setOnPreferenceChangeListener { _: Preference, newValue: Any ->
            val selectedMode = newValue as? String ?: "combined"
            apply(master.isChecked, selectedMode)
            true
        }

        apply(master.isChecked, mode.value ?: "combined")
    }

    private fun wireBatteryCapDependencies() {
        val master = findPreference<SwitchPreferenceCompat>("pref_battery_cap_enabled") ?: return
        val slider = findPreference<BatteryCapSeekBarPreference>("pref_battery_cap_level")

        fun resetCapToDefault() {
            slider?.value = 80
            preferenceManager.sharedPreferences?.edit()?.putInt("pref_battery_cap_level", 80)?.apply()
        }

        if (!master.isChecked) {
            resetCapToDefault()
        }

        master.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
            if (enabled) {
                val ctx = context
                if (ctx != null) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Please reboot your phone to enable this feature.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                resetCapToDefault()
            }
            true
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference) {
            is EditTextPreference -> showCustomEditTextDialog(preference)
            is ListPreference -> showCustomListDialog(preference)
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun showCustomEditTextDialog(preference: EditTextPreference) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_preference_edittext, null)
        val textInputLayout = dialogView.findViewById<TextInputLayout>(R.id.text_input_layout)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.edit_text)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)

        val message = preference.dialogMessage
        if (!message.isNullOrBlank()) {
            messageView.text = message
            messageView.visibility = View.VISIBLE
        }

        textInputLayout.hint = preference.dialogTitle ?: preference.title
        editText.setText(preference.text)
        if (editText.text != null) {
            editText.setSelection(editText.text!!.length)
        }

        if (preference.key == "pref_max_notif_icons" || preference.key == "volume_panel_timeout") {
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        } else {
            editText.inputType = InputType.TYPE_CLASS_TEXT
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(preference.dialogTitle ?: preference.title)
            .setBackground(ContextCompat.getDrawable(ctx, R.drawable.dialog_background))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newValue = editText.text?.toString().orEmpty()
                if (preference.callChangeListener(newValue)) {
                    preference.text = newValue
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()

        editText.requestFocus()
        editText.post {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showCustomListDialog(preference: ListPreference) {
        val ctx = context ?: return
        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return
        val currentIndex = preference.findIndexOfValue(preference.value)

        val adapter = ArrayAdapter(
            ctx,
            R.layout.dialog_list_item_singlechoice,
            android.R.id.text1,
            entries
        )

        MaterialAlertDialogBuilder(ctx)
            .setTitle(preference.dialogTitle ?: preference.title)
            .setBackground(ContextCompat.getDrawable(ctx, R.drawable.dialog_background))
            .setSingleChoiceItems(adapter, currentIndex) { dialog, which ->
                if (which in entryValues.indices) {
                    val newValue = entryValues[which].toString()
                    if (preference.callChangeListener(newValue)) {
                        preference.value = newValue
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
