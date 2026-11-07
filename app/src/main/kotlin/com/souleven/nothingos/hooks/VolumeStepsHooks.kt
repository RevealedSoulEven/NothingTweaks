package com.souleven.nothingos.hooks

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SparseArray
import android.util.SparseIntArray
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class VolumeStepsHooks : HookModule {

    companion object {
        const val KEY_ENABLED = "pref_volume_steps_enabled"
        const val KEY_MEDIA = "pref_volume_steps_media"
        const val KEY_RING = "pref_volume_steps_ring"
        const val KEY_ALARM = "pref_volume_steps_alarm"

        const val GLOBAL_SETTINGS_KEY = "nothingtweaks_volume_steps"

        const val MIN_STEPS = 4
        const val MAX_STEPS = 32

        const val DEFAULT_MEDIA_STEPS = 16
        const val DEFAULT_RING_STEPS = 16
        const val DEFAULT_ALARM_STEPS = 16

        const val STREAM_VOICE_CALL = 0
        const val STREAM_SYSTEM = 1
        const val STREAM_RING = 2
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4
        const val STREAM_NOTIFICATION = 5
        const val STREAM_SYSTEM_ENFORCED = 7
        const val STREAM_DTMF = 8

        private const val AUDIO_SERVICE_CLASS = "com.android.server.audio.AudioService"
    }

    private var audioServiceRef: WeakReference<Any>? = null
    private var stockMaxStreamVolume: IntArray? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        val audioServiceClass = XposedHelpers.findClassIfExists(AUDIO_SERVICE_CLASS, lpparam.classLoader)
        if (audioServiceClass == null) {
            XposedBridge.log("$TAG [VolumeSteps] AudioService class not found")
            return
        }

        try {
            // hook createStreamStates on AudioService to capture stock defaults and apply boot config
            XposedBridge.hookAllMethods(audioServiceClass, "createStreamStates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val audioService = param.thisObject
                    audioServiceRef = WeakReference(audioService)
                    captureStockMaxVolume(audioServiceClass)
                    applyBootConfig(audioService, audioServiceClass)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    audioServiceRef = WeakReference(param.thisObject)
                }
            })

            // also capture reference on constructor
            XposedBridge.hookAllConstructors(audioServiceClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    audioServiceRef = WeakReference(param.thisObject)
                    captureStockMaxVolume(audioServiceClass)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [VolumeSteps] failed to hook AudioService: ${t.message}")
        }

        // real-time listener: triggers whenever preferences change in NothingTweaks app
        prefs.addListener {
            mainHandler.post {
                onPrefsUpdated(prefs)
            }
        }
    }

    private fun captureStockMaxVolume(audioServiceClass: Class<*>) {
        if (stockMaxStreamVolume != null) return
        try {
            val arr = XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME") as? IntArray
            if (arr != null && arr.size > STREAM_DTMF) {
                stockMaxStreamVolume = arr.clone()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [VolumeSteps] error capturing stock volumes: ${t.message}")
        }
    }

    private fun isSteplessMediaSupported(audioServiceClass: Class<*>): Boolean {
        val stockMedia = stockMaxStreamVolume?.getOrNull(STREAM_MUSIC) ?: 0
        if (stockMedia >= 100) return true
        try {
            val ntFeaturesClass = XposedHelpers.findClassIfExists("com.nothing.NtFeaturesUtils", audioServiceClass.classLoader)
            if (ntFeaturesClass != null) {
                val isSupport = XposedHelpers.callStaticMethod(ntFeaturesClass, "isSupport", intArrayOf(148)) as? Boolean
                if (isSupport == true) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun applyBootConfig(audioService: Any, audioServiceClass: Class<*>) {
        try {
            val resolver = XposedHelpers.getObjectField(audioService, "mContentResolver") as? ContentResolver ?: return
            val raw = Settings.Global.getString(resolver, GLOBAL_SETTINGS_KEY)
            if (raw.isNullOrBlank()) return

            val map = decodeConfig(raw)
            val enabled = map["enabled"]?.toBooleanStrictOrNull() ?: false
            if (!enabled) return

            val media = map["media"]?.toIntOrNull()?.coerceIn(MIN_STEPS, MAX_STEPS)
            val ring = map["ring"]?.toIntOrNull()?.coerceIn(MIN_STEPS, MAX_STEPS)
            val alarm = map["alarm"]?.toIntOrNull()?.coerceIn(MIN_STEPS, MAX_STEPS)

            val arr = XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME") as? IntArray ?: return
            val mediaFactor = if (isSteplessMediaSupported(audioServiceClass)) 10 else 1
            if (media != null) arr[STREAM_MUSIC] = media * mediaFactor
            if (ring != null) {
                arr[STREAM_RING] = ring
                arr[STREAM_SYSTEM] = ring
                arr[STREAM_NOTIFICATION] = ring
                arr[STREAM_SYSTEM_ENFORCED] = ring
                arr[STREAM_DTMF] = ring
            }
            if (alarm != null) arr[STREAM_ALARM] = alarm
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [VolumeSteps] boot config failed: ${t.message}")
        }
    }

    private fun onPrefsUpdated(prefs: Prefs) {
        val audioService = audioServiceRef?.get() ?: return
        try {
            val enabled = prefs.getBoolean(KEY_ENABLED, false)
            val media = prefs.getInt(KEY_MEDIA, DEFAULT_MEDIA_STEPS).coerceIn(MIN_STEPS, MAX_STEPS)
            val ring = prefs.getInt(KEY_RING, DEFAULT_RING_STEPS).coerceIn(MIN_STEPS, MAX_STEPS)
            val alarm = prefs.getInt(KEY_ALARM, DEFAULT_ALARM_STEPS).coerceIn(MIN_STEPS, MAX_STEPS)

            // 1. persist to Settings.Global for the next boot
            val resolver = XposedHelpers.getObjectField(audioService, "mContentResolver") as? ContentResolver
            if (resolver != null) {
                val encoded = encodeConfig(enabled, media, ring, alarm)
                if (Settings.Global.getString(resolver, GLOBAL_SETTINGS_KEY) != encoded) {
                    Settings.Global.putString(resolver, GLOBAL_SETTINGS_KEY, encoded)
                }
            }

            // 2. apply in real-time
            applyRealtime(audioService, enabled, media, ring, alarm)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [VolumeSteps] error updating prefs: ${t.message}")
        }
    }

    private fun applyRealtime(audioService: Any, enabled: Boolean, media: Int, ring: Int, alarm: Int) {
        val audioServiceClass = audioService.javaClass
        if (stockMaxStreamVolume == null) {
            captureStockMaxVolume(audioServiceClass)
        }
        val stock = stockMaxStreamVolume
        val streamStates = XposedHelpers.getObjectField(audioService, "mStreamStates") as? SparseArray<*> ?: return
        val maxStreamVolume = XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME") as? IntArray ?: return

        if (!enabled) {
            // revert back to stock defaults
            if (stock != null) {
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_MUSIC, stock[STREAM_MUSIC])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_RING, stock[STREAM_RING])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_SYSTEM, stock[STREAM_SYSTEM])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_NOTIFICATION, stock[STREAM_NOTIFICATION])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_SYSTEM_ENFORCED, stock[STREAM_SYSTEM_ENFORCED])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_DTMF, stock[STREAM_DTMF])
                restoreStreamToStock(streamStates, maxStreamVolume, STREAM_ALARM, stock[STREAM_ALARM])
                broadcastVolumeChanged(audioService, STREAM_MUSIC)
            }
            return
        }

        // apply custom steps in real-time
        val mediaFactor = if (isSteplessMediaSupported(audioServiceClass)) 10 else 1
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_MUSIC, media * mediaFactor)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_RING, ring)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_SYSTEM, ring)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_NOTIFICATION, ring)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_SYSTEM_ENFORCED, ring)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_DTMF, ring)
        updateStreamSteps(streamStates, maxStreamVolume, STREAM_ALARM, alarm)

        broadcastVolumeChanged(audioService, STREAM_MUSIC)
    }

    private fun updateStreamSteps(
        streamStates: SparseArray<*>,
        maxStreamVolume: IntArray,
        streamType: Int,
        newSteps: Int
    ) {
        try {
            if (streamType in maxStreamVolume.indices) {
                maxStreamVolume[streamType] = newSteps
            }
            val vss = streamStates.get(streamType) ?: return
            val oldIndexMax = XposedHelpers.getIntField(vss, "mIndexMax")
            val newIndexMax = newSteps * 10

            if (oldIndexMax == newIndexMax) return

            // rescale current indices in mIndexMap proportionally so loudness ratio is preserved
            val indexMap = XposedHelpers.getObjectField(vss, "mIndexMap") as? SparseIntArray
            val indexMin = try { XposedHelpers.getIntField(vss, "mIndexMin") } catch (_: Throwable) { 0 }
            if (indexMap != null && oldIndexMax > 0) {
                for (i in 0 until indexMap.size()) {
                    val oldVal = indexMap.valueAt(i)
                    val scaledVal = ((oldVal.toDouble() * newIndexMax) / oldIndexMax).roundToInt().coerceIn(indexMin, newIndexMax)
                    indexMap.setValueAt(i, scaledVal)
                }
            }

            XposedHelpers.setIntField(vss, "mIndexMax", newIndexMax)
            XposedHelpers.callMethod(vss, "updateIndexFactors")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [VolumeSteps] failed to update stream $streamType: ${t.message}")
        }
    }

    private fun restoreStreamToStock(
        streamStates: SparseArray<*>,
        maxStreamVolume: IntArray,
        streamType: Int,
        stockSteps: Int
    ) {
        updateStreamSteps(streamStates, maxStreamVolume, streamType, stockSteps)
    }

    private fun broadcastVolumeChanged(audioService: Any, streamType: Int) {
        try {
            val ctx = XposedHelpers.getObjectField(audioService, "mContext") as? Context ?: return
            val intent = Intent("android.media.VOLUME_CHANGED_ACTION").apply {
                putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", streamType)
            }
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun encodeConfig(enabled: Boolean, media: Int, ring: Int, alarm: Int): String {
        return "enabled=$enabled;media=$media;ring=$ring;alarm=$alarm"
    }

    private fun decodeConfig(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                map[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
        }
        return map
    }
}
