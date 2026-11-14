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
import android.view.ViewGroup
import android.widget.SeekBar
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.souleven.nothingos.MainHook
import com.souleven.nothingos.R
import com.souleven.nothingos.hooks.BatteryCapSeekBarPreference
import com.souleven.nothingos.hooks.EssentialKeyHooks
import com.souleven.nothingos.hooks.PowerButtonHooks
import com.souleven.nothingos.hooks.RemotePreferenceProvider
import com.souleven.nothingos.hooks.StatusBarIconHooks
import com.souleven.nothingos.hooks.VolumeStepsHooks

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
        wireEssentialKey()

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
        wireEssentialKey()
        updateVolumeStepsSummary()
        updateHideStatusBarIconsSummary()
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

        if (key == VolumeStepsHooks.KEY_ENABLED || key == VolumeStepsHooks.KEY_MEDIA ||
            key == VolumeStepsHooks.KEY_RING || key == VolumeStepsHooks.KEY_ALARM) {
            updateVolumeStepsSummary()
        }

        if (key in StatusBarIconHooks.ALL_KEYS) {
            updateHideStatusBarIconsSummary()
        }

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

    private fun wireEssentialKey() {
        for (gesture in EssentialKeyHooks.GESTURES) {
            val actionPref = findPreference<ListPreference>(EssentialKeyHooks.keyAction(gesture)) ?: continue
            val appPref = findPreference<Preference>(EssentialKeyHooks.keyApp(gesture)) ?: continue
            val appKey = EssentialKeyHooks.keyApp(gesture)

            fun refreshApp() {
                val savedPkg = preferenceManager.sharedPreferences?.getString(appKey, null)
                if (!savedPkg.isNullOrEmpty()) {
                    try {
                        val pm = requireContext().packageManager
                        val info = pm.getApplicationInfo(savedPkg, 0)
                        appPref.summary = pm.getApplicationLabel(info)
                        appPref.icon = pm.getApplicationIcon(info)
                    } catch (_: Throwable) {
                        appPref.summary = savedPkg
                        appPref.icon = null
                    }
                } else {
                    appPref.summary = getString(R.string.pref_essential_app_summary)
                    appPref.icon = null
                }
            }

            actionPref.setOnPreferenceChangeListener { _, newValue ->
                val isApp = newValue == EssentialKeyHooks.ACTION_APP
                appPref.isVisible = isApp
                if (isApp && preferenceManager.sharedPreferences?.getString(appKey, null).isNullOrEmpty()) {
                    showAppPickerDialog(appPref, appKey)
                }
                true
            }
            appPref.setOnPreferenceClickListener {
                showAppPickerDialog(appPref, appKey)
                true
            }

            appPref.isVisible = actionPref.value == EssentialKeyHooks.ACTION_APP
            refreshApp()
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

    private fun showAppPickerDialog(appPref: Preference, prefKey: String = PowerButtonHooks.KEY_APP) {
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
                    preferenceManager.sharedPreferences?.edit()?.putString(prefKey, selectedApp.packageName)?.apply()
                    appPref.summary = selectedApp.label
                    appPref.icon = selectedApp.icon
                    onSharedPreferenceChanged(preferenceManager.sharedPreferences, prefKey)
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

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "pref_volume_steps") {
            showVolumeStepsDialog()
            return true
        }
        if (preference.key == "pref_hide_statusbar_icons") {
            showHideStatusBarIconsDialog()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun updateVolumeStepsSummary() {
        val pref = findPreference<Preference>("pref_volume_steps") ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val enabled = prefs.getBoolean(VolumeStepsHooks.KEY_ENABLED, false)
        if (!enabled) {
            pref.summary = getString(R.string.pref_volume_steps_desc)
        } else {
            val media = prefs.getInt(VolumeStepsHooks.KEY_MEDIA, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
            val ring = prefs.getInt(VolumeStepsHooks.KEY_RING, VolumeStepsHooks.DEFAULT_RING_STEPS)
            val alarm = prefs.getInt(VolumeStepsHooks.KEY_ALARM, VolumeStepsHooks.DEFAULT_ALARM_STEPS)
            pref.summary = "${getString(R.string.pref_volume_steps_media)}: $media · ${getString(R.string.pref_volume_steps_ring)}: $ring · ${getString(R.string.pref_volume_steps_alarm)}: $alarm"
        }
    }

    private fun showVolumeStepsDialog() {
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_volume_steps, null)
        val switchEnabled = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_enabled)
        val slidersContainer = dialogView.findViewById<ViewGroup>(R.id.sliders_container)

        val seekbarMedia = dialogView.findViewById<SeekBar>(R.id.seekbar_media)
        val textMediaValue = dialogView.findViewById<TextView>(R.id.text_media_value)

        val seekbarRing = dialogView.findViewById<SeekBar>(R.id.seekbar_ring)
        val textRingValue = dialogView.findViewById<TextView>(R.id.text_ring_value)

        val seekbarAlarm = dialogView.findViewById<SeekBar>(R.id.seekbar_alarm)
        val textAlarmValue = dialogView.findViewById<TextView>(R.id.text_alarm_value)

        val initialEnabled = prefs.getBoolean(VolumeStepsHooks.KEY_ENABLED, false)
        val initialMedia = prefs.getInt(VolumeStepsHooks.KEY_MEDIA, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
        val initialRing = prefs.getInt(VolumeStepsHooks.KEY_RING, VolumeStepsHooks.DEFAULT_RING_STEPS)
        val initialAlarm = prefs.getInt(VolumeStepsHooks.KEY_ALARM, VolumeStepsHooks.DEFAULT_ALARM_STEPS)

        switchEnabled.isChecked = initialEnabled

        fun updateValueText(textView: TextView, value: Int, defaultVal: Int) {
            textView.text = if (value == defaultVal) {
                "$value (${getString(R.string.volume_steps_default)})"
            } else {
                getString(R.string.volume_steps_count, value)
            }
        }

        fun setContainerState(enabled: Boolean) {
            slidersContainer.alpha = if (enabled) 1.0f else 0.4f
            seekbarMedia.isEnabled = enabled
            seekbarRing.isEnabled = enabled
            seekbarAlarm.isEnabled = enabled
        }

        seekbarMedia.progress = initialMedia
        seekbarRing.progress = initialRing
        seekbarAlarm.progress = initialAlarm

        updateValueText(textMediaValue, initialMedia, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
        updateValueText(textRingValue, initialRing, VolumeStepsHooks.DEFAULT_RING_STEPS)
        updateValueText(textAlarmValue, initialAlarm, VolumeStepsHooks.DEFAULT_ALARM_STEPS)
        setContainerState(initialEnabled)

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            setContainerState(isChecked)
        }

        seekbarMedia.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText(textMediaValue, progress, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekbarRing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText(textRingValue, progress, VolumeStepsHooks.DEFAULT_RING_STEPS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekbarAlarm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateValueText(textAlarmValue, progress, VolumeStepsHooks.DEFAULT_ALARM_STEPS)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pref_volume_steps)
            .setBackground(ContextCompat.getDrawable(ctx, R.drawable.dialog_background))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putBoolean(VolumeStepsHooks.KEY_ENABLED, switchEnabled.isChecked)
                    .putInt(VolumeStepsHooks.KEY_MEDIA, seekbarMedia.progress)
                    .putInt(VolumeStepsHooks.KEY_RING, seekbarRing.progress)
                    .putInt(VolumeStepsHooks.KEY_ALARM, seekbarAlarm.progress)
                    .apply()
                updateVolumeStepsSummary()
            }
            .setNeutralButton(R.string.reset_txt, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            switchEnabled.isChecked = false
            seekbarMedia.progress = VolumeStepsHooks.DEFAULT_MEDIA_STEPS
            seekbarRing.progress = VolumeStepsHooks.DEFAULT_RING_STEPS
            seekbarAlarm.progress = VolumeStepsHooks.DEFAULT_ALARM_STEPS
            updateValueText(textMediaValue, VolumeStepsHooks.DEFAULT_MEDIA_STEPS, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
            updateValueText(textRingValue, VolumeStepsHooks.DEFAULT_RING_STEPS, VolumeStepsHooks.DEFAULT_RING_STEPS)
            updateValueText(textAlarmValue, VolumeStepsHooks.DEFAULT_ALARM_STEPS, VolumeStepsHooks.DEFAULT_ALARM_STEPS)
            setContainerState(false)
        }
    }

    private fun updateHideStatusBarIconsSummary() {
        val pref = findPreference<Preference>("pref_hide_statusbar_icons") ?: return
        val prefs = preferenceManager.sharedPreferences ?: return
        val hiddenList = mutableListOf<String>()
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_STATUSBAR_CLOCK, false)) {
            hiddenList.add(getString(R.string.hide_icon_clock))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_SILENT_ICON, false)) {
            hiddenList.add(getString(R.string.hide_icon_silent))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_NETWORK_TYPE, false)) {
            hiddenList.add(getString(R.string.hide_icon_network_type))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_SIM1, false)) {
            hiddenList.add(getString(R.string.hide_icon_sim1))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_SIM2, false)) {
            hiddenList.add(getString(R.string.hide_icon_sim2))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_DUAL_SIM, false)) {
            hiddenList.add(getString(R.string.hide_icon_dual_sim))
        }
        if (prefs.getBoolean(StatusBarIconHooks.PREF_HIDE_ALARM_ICON, false)) {
            hiddenList.add(getString(R.string.hide_icon_alarm))
        }

        if (hiddenList.isEmpty()) {
            pref.summary = getString(R.string.pref_hide_statusbar_icons_desc)
        } else {
            pref.summary = hiddenList.joinToString(" · ")
        }
    }

    private fun showHideStatusBarIconsDialog() {
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_hide_statusbar_icons, null)

        val switchClock = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_clock)
        val switchMute = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_mute)
        val switchNetType = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_network_type)
        val switchSim1 = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_sim1)
        val switchSim2 = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_sim2)
        val switchDualSim = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_dual_sim)
        val switchAlarm = dialogView.findViewById<MaterialSwitch>(R.id.switch_hide_alarm)

        val switches = listOf(
            switchClock to StatusBarIconHooks.PREF_HIDE_STATUSBAR_CLOCK,
            switchMute to StatusBarIconHooks.PREF_HIDE_SILENT_ICON,
            switchNetType to StatusBarIconHooks.PREF_HIDE_NETWORK_TYPE,
            switchSim1 to StatusBarIconHooks.PREF_HIDE_SIM1,
            switchSim2 to StatusBarIconHooks.PREF_HIDE_SIM2,
            switchDualSim to StatusBarIconHooks.PREF_HIDE_DUAL_SIM,
            switchAlarm to StatusBarIconHooks.PREF_HIDE_ALARM_ICON,
        )

        for ((switch, key) in switches) {
            switch.isChecked = prefs.getBoolean(key, false)
            switch.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pref_hide_statusbar_icons)
            .setBackground(ContextCompat.getDrawable(ctx, R.drawable.dialog_background))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.reset_txt, null)
            .create()

        dialog.show()

        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            val editor = prefs.edit()
            for ((switch, key) in switches) {
                switch.isChecked = false
                editor.putBoolean(key, false)
            }
            editor.apply()
        }
    }
}
