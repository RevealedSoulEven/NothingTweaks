package com.souleven.nothingos.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.souleven.nothingos.MainHook
import com.souleven.nothingos.R
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

        requireRebootOnEnable("allow_180_rotation")
        requireRebootOnEnable("pref_advanced_power_menu")
        requireRebootOnEnable("pref_back_gesture_kill")
        requireRebootOnEnable("pref_disable_flag_secure")
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        wireNetworkTrafficDependencies()
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
}
