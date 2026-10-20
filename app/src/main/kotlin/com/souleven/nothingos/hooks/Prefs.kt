package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Prefs(private val isSystemServer: Boolean = false) {

    private companion object {
        const val RELOAD_INTERVAL_MS = 5_000L
    }

    private val cache = ConcurrentHashMap<String, Any>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var observerRegistered = false

    @Volatile
    private var lastReload = 0L

    private val reloading = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NothingTweaks-RemotePrefs").apply { isDaemon = true }
    }

    private val observerHandler by lazy {
        val thread = HandlerThread("NothingTweaks-PrefObserver").apply {
            isDaemon = true
            start()
        }
        Handler(thread.looper)
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
        }
        ensureObserver(appContext)
        triggerReload(waitIfFirstLoad = !isSystemServer)
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        try {
            val app = AndroidAppHelper.currentApplication()
            if (app != null) {
                appContext = app
                return app
            }
        } catch (_: Throwable) {}

        if (isSystemServer) {
            try {
                val atClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", null)
                val currentAt = atClass?.let { XposedHelpers.callStaticMethod(it, "currentActivityThread") }
                val sysCtx = currentAt?.let { XposedHelpers.callMethod(it, "getSystemContext") as? Context }
                if (sysCtx != null) {
                    appContext = sysCtx
                    return sysCtx
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun ensureObserver(ctx: Context?) {
        if (observerRegistered || ctx == null) return
        synchronized(this) {
            if (observerRegistered) return
            try {
                val observer = object : ContentObserver(observerHandler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        reloadSync(ctx)
                    }
                }
                ctx.contentResolver.registerContentObserver(
                    RemotePreferenceProvider.CONTENT_URI,
                    true,
                    observer
                )
                observerRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun triggerReload(waitIfFirstLoad: Boolean) {
        val ctx = resolveContext() ?: return
        ensureObserver(ctx)

        if (!reloading.compareAndSet(false, true)) return
        lastReload = System.currentTimeMillis()

        val future = executor.submit {
            try {
                reloadSync(ctx)
            } finally {
                reloading.set(false)
            }
        }

        if (waitIfFirstLoad && !initialized) {
            try {
                future.get(150, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun reloadSync(ctx: Context) {
        try {
            val resolver = ctx.contentResolver
            val bundle = try {
                resolver.call(
                    RemotePreferenceProvider.CONTENT_URI,
                    RemotePreferenceProvider.METHOD_GET_ALL,
                    null,
                    null
                )
            } catch (_: Throwable) {
                null
            }

            if (bundle != null) {
                val newKeys = HashSet<String>()
                for (key in bundle.keySet()) {
                    val value = bundle.get(key)
                    if (value != null) {
                        cache[key] = value
                        newKeys.add(key)
                    }
                }
                val iter = cache.keys.iterator()
                while (iter.hasNext()) {
                    if (iter.next() !in newKeys) {
                        iter.remove()
                    }
                }
                initialized = true
                lastReload = System.currentTimeMillis()
                return
            }

            resolver.query(RemotePreferenceProvider.CONTENT_URI, null, null, null, null)?.use { cursor ->
                val newKeys = HashSet<String>()
                val keyIdx = cursor.getColumnIndex("key")
                val typeIdx = cursor.getColumnIndex("type")
                val valIdx = cursor.getColumnIndex("value")
                if (keyIdx >= 0 && typeIdx >= 0 && valIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(keyIdx) ?: continue
                        val type = cursor.getString(typeIdx) ?: continue
                        val rawVal = cursor.getString(valIdx) ?: continue
                        val parsed: Any? = when (type) {
                            "boolean" -> rawVal == "1" || rawVal.equals("true", ignoreCase = true)
                            "int" -> rawVal.toIntOrNull()
                            "long" -> rawVal.toLongOrNull()
                            "float" -> rawVal.toFloatOrNull()
                            "string" -> rawVal
                            else -> null
                        }
                        if (parsed != null) {
                            cache[key] = parsed
                            newKeys.add(key)
                        }
                    }
                    val iter = cache.keys.iterator()
                    while (iter.hasNext()) {
                        if (iter.next() !in newKeys) {
                            iter.remove()
                        }
                    }
                    initialized = true
                    lastReload = System.currentTimeMillis()
                    return
                }
            }

            reloadFromFile()
        } catch (_: Throwable) {
            reloadFromFile()
        }
    }

    private fun reloadFromFile(): Boolean {
        try {
            val file = java.io.File("/data/user_de/0/com.souleven.nothingos/shared_prefs/settings.xml")
            if (!file.exists() || !file.canRead()) return false

            val parser = android.util.Xml.newPullParser()
            file.inputStream().use { inputStream ->
                parser.setInput(inputStream, "utf-8")
                var event = parser.eventType
                val newKeys = HashSet<String>()
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val tag = parser.name
                        val name = parser.getAttributeValue(null, "name")
                        if (name != null) {
                            val parsed: Any? = when (tag) {
                                "boolean" -> parser.getAttributeValue(null, "value")?.toBooleanStrictOrNull()
                                "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()
                                "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()
                                "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                "string" -> parser.nextText()
                                else -> null
                            }
                            if (parsed != null) {
                                cache[name] = parsed
                                newKeys.add(name)
                            }
                        }
                    }
                    event = parser.next()
                }
                if (newKeys.isNotEmpty()) {
                    val iter = cache.keys.iterator()
                    while (iter.hasNext()) {
                        if (iter.next() !in newKeys) {
                            iter.remove()
                        }
                    }
                    initialized = true
                    lastReload = System.currentTimeMillis()
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun maybeReload() {
        if (!initialized) {
            val now = System.currentTimeMillis()
            if (now - lastReload >= 1_000L) {
                triggerReload(waitIfFirstLoad = true)
            }
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        maybeReload()
        return when (val v = cache[key]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: default
            is Number -> v.toInt() != 0
            else -> default
        }
    }

    fun getString(key: String, default: String): String {
        maybeReload()
        return cache[key]?.toString() ?: default
    }

    fun getInt(key: String, default: Int): Int {
        maybeReload()
        return when (val v = cache[key]) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    fun forceReload() {
        val ctx = resolveContext()
        if (ctx != null) {
            reloadSync(ctx)
        } else {
            reloadFromFile()
        }
    }
}
