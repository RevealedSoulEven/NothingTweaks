package com.souleven.nothingos.hooks

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Carries the Volume Steps settings to system_server through Settings.Global.
 *
 * system_server may not open the module's own settings.xml (SELinux), and the module's
 * content provider is not reachable yet when AudioService builds its streams. Settings
 * are readable at that point, so the module app (when it holds WRITE_SECURE_SETTINGS)
 * and system_server itself both keep one Settings.Global value up to date:
 *
 *   nothingtweaks_volume_steps = "vs_media=32;vs_media_custom=;vs_ring=32;..."
 */
object VolumeStepsBridge {

    const val GLOBAL_KEY = "nothingtweaks_volume_steps"
    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    const val KEY_MEDIA = "vs_media"
    const val KEY_MEDIA_CUSTOM = "vs_media_custom"
    const val KEY_RING = "vs_ring"
    const val KEY_RING_CUSTOM = "vs_ring_custom"
    const val KEY_ALARM = "vs_alarm"
    const val KEY_ALARM_CUSTOM = "vs_alarm_custom"

    val KEYS = arrayOf(
        KEY_MEDIA, KEY_MEDIA_CUSTOM, KEY_RING, KEY_RING_CUSTOM, KEY_ALARM, KEY_ALARM_CUSTOM
    )

    fun isVolumeStepsKey(key: String?): Boolean = key != null && key in KEYS

    /** "key=value" pairs joined with [separator]; separators and '=' never appear in values. */
    fun encode(separator: String, valueOf: (String) -> String?): String =
        KEYS.joinToString(separator) { key -> "$key=${clean(valueOf(key))}" }

    /** Accepts both the ';' form (Settings.Global) and the newline form (boot file). */
    fun decode(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in text.split(';', '\n')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        return map
    }

    private fun clean(value: String?): String =
        (value ?: "").trim().filter { it != ';' && it != '=' && it != '\n' && it != '\r' }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Module app side: publish the current settings. The permission is granted to the app by
     * the module's system_server hook at boot; until then (very first run) this does nothing
     * and system_server falls back to its own copy.
     */
    fun syncToSettings(context: Context, prefs: SharedPreferences) {
        try {
            if (!hasPermission(context)) return
            val text = encode(";") { prefs.getString(it, "") }
            val resolver = context.contentResolver
            if (Settings.Global.getString(resolver, GLOBAL_KEY) == text) return
            Settings.Global.putString(resolver, GLOBAL_KEY, text)
        } catch (_: Throwable) {
        }
    }
}
