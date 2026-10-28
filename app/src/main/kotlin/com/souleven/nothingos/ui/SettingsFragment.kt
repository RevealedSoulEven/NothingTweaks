package com.souleven.nothingos.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.souleven.nothingos.MainHook
import com.souleven.nothingos.R
import com.souleven.nothingos.hooks.BatteryCapSeekBarPreference
import com.souleven.nothingos.hooks.PowerButtonHooks
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
        wirePowerButtonDependencies()

        requireRebootOnEnable("allow_180_rotation")
        requireRebootOnEnable("pref_advanced_power_menu")
        requireRebootOnEnable("pref_back_gesture_kill")
        requireRebootOnEnable("pref_disable_flag_secure")
        requireRebootOnEnable(PowerButtonHooks.KEY_CUSTOM_APP)
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        wireNetworkTrafficDependencies()
        wireBatteryCapDependencies()
        wirePowerButtonDependencies()
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

    private fun wirePowerButtonDependencies() {
        val switchPref = findPreference<SwitchPreferenceCompat>(PowerButtonHooks.KEY_CUSTOM_APP) ?: return
        val appPref = findPreference<Preference>(PowerButtonHooks.KEY_APP) ?: return

        fun updateAppSummaryAndIcon() {
            val savedPkg = preferenceManager.sharedPreferences?.getString(PowerButtonHooks.KEY_APP, null)
            if (!savedPkg.isNullOrEmpty()) {
                try {
                    val pm = requireContext().packageManager
                    val appInfo = pm.getApplicationInfo(savedPkg, 0)
                    appPref.summary = pm.getApplicationLabel(appInfo)
                    appPref.icon = pm.getApplicationIcon(appInfo)
                } catch (_: Throwable) {
                    appPref.summary = savedPkg
                    appPref.icon = null
                }
            } else {
                appPref.summary = getString(R.string.pref_power_double_tap_app_summary)
                appPref.icon = null
            }
        }

        fun updateVisibility(enabled: Boolean) {
            appPref.isVisible = enabled
        }

        switchPref.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            updateVisibility(enabled)
            if (enabled) {
                val savedPkg = preferenceManager.sharedPreferences?.getString(PowerButtonHooks.KEY_APP, null)
                if (savedPkg.isNullOrEmpty()) {
                    showAppPickerDialog(appPref)
                }
            }
            true
        }

        appPref.setOnPreferenceClickListener {
            showAppPickerDialog(appPref)
            true
        }

        updateVisibility(switchPref.isChecked)
        updateAppSummaryAndIcon()
    }

    private fun showAppPickerDialog(appPref: Preference) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val searchEdit = dialogView.findViewById<TextInputEditText>(R.id.search_edit_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.apps_recycler_view)
        val emptyView = dialogView.findViewById<View>(R.id.no_apps_found)

        recyclerView.layoutManager = LinearLayoutManager(ctx)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_select_app)
            .setBackground(ContextCompat.getDrawable(ctx, R.drawable.dialog_background))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        Thread {
            val pm = ctx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            val appList = mutableListOf<AppItem>()
            val seenPackages = mutableSetOf<String>()

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName) continue
                if (seenPackages.add(pkg)) {
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    appList.add(AppItem(label, pkg, icon))
                }
            }

            try {
                val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
                for (appInfo in installedApps) {
                    val pkg = appInfo.packageName
                    if (pkg == ctx.packageName || seenPackages.contains(pkg)) continue
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        seenPackages.add(pkg)
                        val label = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        appList.add(AppItem(label, pkg, icon))
                    }
                }
            } catch (_: Throwable) {}

            appList.sortBy { it.label.lowercase() }

            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                val adapter = AppPickerAdapter(appList) { selectedApp ->
                    preferenceManager.sharedPreferences?.edit()?.putString(PowerButtonHooks.KEY_APP, selectedApp.packageName)?.apply()
                    appPref.summary = selectedApp.label
                    appPref.icon = selectedApp.icon
                    onSharedPreferenceChanged(preferenceManager.sharedPreferences, PowerButtonHooks.KEY_APP)
                    dialog.dismiss()
                }
                recyclerView.adapter = adapter

                searchEdit.addTextChangedListener(afterTextChanged = { s ->
                    val query = s?.toString().orEmpty()
                    adapter.filter(query)
                    emptyView.visibility = if (adapter.isListEmpty()) View.VISIBLE else View.GONE
                })
            }
        }.start()

        dialog.show()
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
