package com.souleven.nothingos.hooks

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Carries the Volume Steps settings to system_server through Settings.Global.
 *
 * AudioService builds its streams before the module's content provider is reachable and
 * SELinux keeps system_server out of the module's own settings.xml, but Settings can be
 * read at that point. Both the module app (once the hook has granted it
 * WRITE_SECURE_SETTINGS) and system_server publish the same value:
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

    private val KEYS = arrayOf(
        KEY_MEDIA, KEY_MEDIA_CUSTOM, KEY_RING, KEY_RING_CUSTOM, KEY_ALARM, KEY_ALARM_CUSTOM
    )

    fun isVolumeStepsKey(key: String?): Boolean = key != null && key in KEYS

    /** "key=value" pairs joined with ';' (separators never appear in the values). */
    fun encode(valueOf: (String) -> String?): String =
        KEYS.joinToString(";") { key -> "$key=${clean(valueOf(key))}" }

    fun decode(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in text.split(';')) {
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        return map
    }

    private fun clean(value: String?): String =
        (value ?: "").trim().filter { it != ';' && it != '=' }

    /**
     * Module app side. Silently does nothing until the hook has granted the permission
     * (very first run); system_server publishes the same value itself in that case.
     */
    fun syncToSettings(context: Context, prefs: SharedPreferences) {
        try {
            if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return
            val text = encode { prefs.getString(it, "") }
            val resolver = context.contentResolver
            if (Settings.Global.getString(resolver, GLOBAL_KEY) != text) {
                Settings.Global.putString(resolver, GLOBAL_KEY, text)
            }
        } catch (_: Throwable) {
        }
    }
}
