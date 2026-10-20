package com.souleven.nothingos.hooks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import com.souleven.nothingos.MainHook
import java.io.File

class RemotePreferenceProvider : ContentProvider(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val AUTHORITY = "com.souleven.nothingos.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/prefs")
        const val METHOD_GET_ALL = "get_all"
        private const val KEY_MIGRATED = "_migrated_from_ce"

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

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
