package com.souleven.nothingos.hooks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.provider.OpenableColumns
import com.souleven.nothingos.MainHook
import java.io.File

class RemotePreferenceProvider : ContentProvider(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val AUTHORITY = "com.souleven.nothingos.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/prefs")
        val CLIPBOARD_URI: Uri = Uri.parse("content://$AUTHORITY/clipboard_text.txt")
        const val METHOD_GET_ALL = "get_all"
        const val METHOD_SET_CLIPBOARD_TEXT = "set_clipboard_text"
        const val METHOD_PUT = "put"
        private const val CLIPBOARD_FILE_NAME = "clipboard_snippet.txt"
        private const val KEY_MIGRATED = "_migrated_from_ce"

        fun getPrefBoolean(context: Context, key: String, def: Boolean): Boolean {
            return try {
                val b = context.contentResolver.call(CONTENT_URI, METHOD_GET_ALL, null, null)
                if (b != null && b.containsKey(key)) b.getBoolean(key, def) else def
            } catch (_: Throwable) { def }
        }

        fun putPrefBoolean(context: Context, key: String, value: Boolean) {
            try {
                val extras = Bundle().apply {
                    putString("key", key)
                    putString("type", "boolean")
                    putBoolean("value", value)
                }
                context.contentResolver.call(CONTENT_URI, METHOD_PUT, null, extras)
            } catch (_: Throwable) {}
        }

        fun getPrefInt(context: Context, key: String, def: Int): Int {
            return try {
                val b = context.contentResolver.call(CONTENT_URI, METHOD_GET_ALL, null, null)
                if (b != null && b.containsKey(key)) b.getInt(key, def) else def
            } catch (_: Throwable) { def }
        }

        fun putPrefInt(context: Context, key: String, value: Int) {
            try {
                val extras = Bundle().apply {
                    putString("key", key)
                    putString("type", "int")
                    putInt("value", value)
                }
                context.contentResolver.call(CONTENT_URI, METHOD_PUT, null, extras)
            } catch (_: Throwable) {}
        }

        fun getPrefString(context: Context, key: String, def: String?): String? {
            return try {
                val b = context.contentResolver.call(CONTENT_URI, METHOD_GET_ALL, null, null)
                if (b != null && b.containsKey(key)) b.getString(key, def) else def
            } catch (_: Throwable) { def }
        }

        fun putPrefString(context: Context, key: String, value: String?) {
            try {
                val extras = Bundle().apply {
                    putString("key", key)
                    putString("type", "string")
                    putString("value", value)
                }
                context.contentResolver.call(CONTENT_URI, METHOD_PUT, null, extras)
            } catch (_: Throwable) {}
        }

        fun getDeviceProtectedPrefs(context: Context): SharedPreferences {
            val deContext = context.createDeviceProtectedStorageContext()
            val dePrefs = deContext.getSharedPreferences(MainHook.PREFS_NAME, Context.MODE_PRIVATE)

            try {
                val um = context.getSystemService(UserManager::class.java)
                if (um == null || um.isUserUnlocked) {
                    if (!dePrefs.getBoolean(KEY_MIGRATED, false)) {
                        val cePrefs = context.getSharedPreferences(MainHook.PREFS_NAME, Context.MODE_PRIVATE)
                        val all = cePrefs.all
                        if (all.isNotEmpty()) {
                            val editor = dePrefs.edit()
                            for ((k, v) in all) {
                                when (v) {
                                    is Boolean -> editor.putBoolean(k, v)
                                    is Int -> editor.putInt(k, v)
                                    is Long -> editor.putLong(k, v)
                                    is Float -> editor.putFloat(k, v)
                                    is String -> editor.putString(k, v)
                                    is Set<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        editor.putStringSet(k, v as Set<String>)
                                    }
                                }
                            }
                            editor.putBoolean(KEY_MIGRATED, true)
                            editor.commit()
                        } else {
                            dePrefs.edit().putBoolean(KEY_MIGRATED, true).commit()
                        }
                    }
                    cleanupLegacyWorldReadableFiles(context, deContext)
                } else {
                    fixFilePermissions(deContext.dataDir)
                }
            } catch (_: Throwable) {}

            return dePrefs
        }

        private fun cleanupLegacyWorldReadableFiles(ceContext: Context, deContext: Context) {
            try {
                // Remove old credential-encrypted settings.xml so no legacy 0664 file remains
                val cePrefsDir = File(ceContext.dataDir, "shared_prefs")
                val ceFile = File(cePrefsDir, "${MainHook.PREFS_NAME}.xml")
                if (ceFile.exists()) {
                    fixFilePermissions(cePrefsDir)
                    ceFile.delete()
                }
                val ceBak = File(cePrefsDir, "${MainHook.PREFS_NAME}.xml.bak")
                if (ceBak.exists()) {
                    ceBak.delete()
                }
                fixFilePermissions(deContext.dataDir)
            } catch (_: Throwable) {}
        }

        private fun fixFilePermissions(baseDir: File?) {
            if (baseDir == null || !baseDir.exists()) return
            try {
                val prefsDir = if (baseDir.name == "shared_prefs") baseDir else File(baseDir, "shared_prefs")
                if (prefsDir.exists()) {
                    prefsDir.setReadable(true, false)
                    prefsDir.setExecutable(true, false)
                    prefsDir.listFiles()?.forEach { file ->
                        file.setReadable(true, false)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private var prefs: SharedPreferences? = null

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        val sharedPrefs = getDeviceProtectedPrefs(ctx)
        if (!sharedPrefs.contains("pref_integrate_settings")) {
            sharedPrefs.edit().putBoolean("pref_integrate_settings", true).commit()
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(this)
        prefs = sharedPrefs
        return true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        try {
            val uri = if (key != null) Uri.withAppendedPath(CONTENT_URI, key) else CONTENT_URI
            ctx.contentResolver.notifyChange(uri, null)
        } catch (_: Throwable) {}
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_GET_ALL) {
            val ctx = context ?: return null
            val sharedPrefs = prefs ?: getDeviceProtectedPrefs(ctx).also { prefs = it }
            val bundle = Bundle()
            for ((k, v) in sharedPrefs.all) {
                if (k == KEY_MIGRATED) continue
                when (v) {
                    is Boolean -> bundle.putBoolean(k, v)
                    is Int -> bundle.putInt(k, v)
                    is Long -> bundle.putLong(k, v)
                    is Float -> bundle.putFloat(k, v)
                    is String -> bundle.putString(k, v)
                }
            }
            return bundle
        } else if (method == METHOD_SET_CLIPBOARD_TEXT) {
            val ctx = context ?: return null
            val text = arg ?: return null
            return try {
                val file = File(ctx.cacheDir, CLIPBOARD_FILE_NAME)
                file.writeText(text, Charsets.UTF_8)
                Bundle().apply { putBoolean("success", true) }
            } catch (_: Throwable) {
                null
            }
        } else if (method == METHOD_PUT) {
            val ctx = context ?: return null
            val bundle = extras ?: return null
            val key = bundle.getString("key") ?: return null
            val type = bundle.getString("type") ?: "boolean"
            val sharedPrefs = prefs ?: getDeviceProtectedPrefs(ctx).also { prefs = it }
            val editor = sharedPrefs.edit()
            when (type) {
                "boolean" -> editor.putBoolean(key, bundle.getBoolean("value"))
                "int" -> editor.putInt(key, bundle.getInt("value"))
                "long" -> editor.putLong(key, bundle.getLong("value"))
                "float" -> editor.putFloat(key, bundle.getFloat("value"))
                "string" -> editor.putString(key, bundle.getString("value"))
            }
            editor.commit()

            try {
                val uri = Uri.withAppendedPath(CONTENT_URI, key)
                ctx.contentResolver.notifyChange(uri, null)
            } catch (_: Throwable) {}

            try {
                val changeIntent = android.content.Intent("com.souleven.nothingos.PREFERENCES_CHANGED").apply {
                    setPackage("android")
                    putExtra("key", key)
                    if (key == "pref_battery_cap_level" || key == "pref_battery_cap_enabled" || key == "pref_battery_cap_notify") {
                        action = "com.souleven.nothingos.BATTERY_CAP_CHANGED"
                        putExtra("pref_battery_cap_enabled", sharedPrefs.getBoolean("pref_battery_cap_enabled", false))
                        putExtra("pref_battery_cap_level", sharedPrefs.getInt("pref_battery_cap_level", 80))
                        putExtra("pref_battery_cap_notify", sharedPrefs.getBoolean("pref_battery_cap_notify", true))
                    }
                }
                ctx.sendBroadcast(changeIntent)
            } catch (_: Throwable) {}

            return Bundle().apply { putBoolean("success", true) }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null

        if (uri.path?.contains("clipboard_text") == true) {
            val file = File(ctx.cacheDir, CLIPBOARD_FILE_NAME)
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            cursor.addRow(arrayOf<Any?>(CLIPBOARD_FILE_NAME, if (file.exists()) file.length() else 0L))
            return cursor
        }

        val sharedPrefs = prefs ?: getDeviceProtectedPrefs(ctx).also { prefs = it }
        val cursor = MatrixCursor(arrayOf("key", "type", "value"))
        for ((k, v) in sharedPrefs.all) {
            if (k == KEY_MIGRATED) continue
            val type = when (v) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is String -> "string"
                else -> continue
            }
            val storedValue = if (v is Boolean) (if (v) 1 else 0).toString() else v.toString()
            cursor.addRow(arrayOf<Any?>(k, type, storedValue))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        if (uri.path?.contains("clipboard_text") == true) {
            return "text/plain"
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (uri.path?.contains("clipboard_text") == true) {
            val file = File(ctx.cacheDir, CLIPBOARD_FILE_NAME)
            if (file.exists()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        return super.openFile(uri, mode)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
