package com.souleven.nothingos.hooks

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Volume Steps — changes the REAL per-stream step count of AudioService (system_server).
 *
 * AudioService keeps each stream's step count in the static array
 * AudioService.MAX_STREAM_VOLUME. VolumeStreamState's constructor turns it into mIndexMax
 * (x10), publishes the range to the native audio policy and VolumeGroupState / SystemUI
 * read it back. So the array is patched once, right before the first VolumeStreamState is
 * built; nothing else needs to change.
 *
 * Stepless Volume (Nothing, feature 148) keeps media at 160 = 16 steps x10 (hardware keys
 * move 10 units, the slider 1). That x10 factor is kept: media = N * 10 on such devices.
 *
 * Config: the module app (granted WRITE_SECURE_SETTINGS by this hook) and system_server
 * (whenever Prefs loads or changes) publish the settings to Settings.Global, the only
 * place readable that early in boot. A change therefore needs one reboot.
 *
 * Saved levels: Settings.System stores levels in step units, so a different step count
 * would change the loudness of every saved level. They are scaled by new/old max before
 * the streams read them; the max they currently refer to is kept in STATE_KEY.
 *
 * Every failure path leaves the stock behaviour untouched.
 */
class VolumeStepsHooks : HookModule {

    private companion object {
        const val AUDIO_SERVICE = "com.android.server.audio.AudioService"
        const val VSS = "com.android.server.audio.AudioService\$VolumeStreamState"
        const val MODULE_PACKAGE = "com.souleven.nothingos"
        const val SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        const val STATE_KEY = "nothingtweaks_volume_steps_applied"

        const val STREAM_SYSTEM = 1
        const val STREAM_RING = 2
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4
        const val STREAM_SYSTEM_ENFORCED = 7
        const val STREAM_DTMF = 8

        const val MODE_DEFAULT = "default"
        const val MODE_CUSTOM = "custom"
        const val MIN_STEPS = 4
        const val MAX_STEPS = 100

        // Stock media max is 160 when Nothing's Stepless Volume is active (16 steps x10).
        const val STEPLESS_DETECT_MIN = 100
        const val STEPLESS_FACTOR = 10

        const val DEVICE_OUT_DEFAULT = 0x40000000
    }

    /** Streams whose step count may change, with the base name of their saved-level rows. */
    private val managedSettings = mapOf(
        STREAM_MUSIC to "volume_music",
        STREAM_RING to "volume_ring",
        STREAM_SYSTEM to "volume_system",
        STREAM_SYSTEM_ENFORCED to "volume_system_enforced",
        STREAM_DTMF to "volume_dtmf",
        STREAM_ALARM to "volume_alarm"
    )

    private val applied = AtomicBoolean(false)
    private val publishing = AtomicBoolean(false)
    private val grantTried = AtomicBoolean(false)
    @Volatile private var resolver: ContentResolver? = null
    @Volatile private var systemContext: Context? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return

        val audioServiceClass = XposedHelpers.findClassIfExists(AUDIO_SERVICE, lpparam.classLoader)
        val vssClass = XposedHelpers.findClassIfExists(VSS, lpparam.classLoader)
        if (audioServiceClass == null || vssClass == null) {
            XposedBridge.log("$TAG   [VolumeSteps] AudioService classes not found — skipped")
            return
        }

        try {
            XposedHelpers.findAndHookConstructor(
                vssClass,
                audioServiceClass,
                String::class.java,
                Integer.TYPE,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val audioService = param.args[0] ?: return
                            applyOnce(audioService, audioServiceClass)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [VolumeSteps] apply failed, stock kept: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG   [VolumeSteps] hooked VolumeStreamState constructor")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] FAILED to hook VolumeStreamState ctor: ${t.message}")
            return
        }

        // Fires whenever Prefs loads or changes (after the system is ready).
        prefs.addListener { publishConfig(prefs) }
    }

    /** Runs once, before the first VolumeStreamState is constructed. */
    private fun applyOnce(audioService: Any, audioServiceClass: Class<*>) {
        if (!applied.compareAndSet(false, true)) return
        val arr = XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME") as? IntArray
        if (arr == null || arr.size <= STREAM_DTMF) return

        resolver = runCatching {
            XposedHelpers.getObjectField(audioService, "mContentResolver") as? ContentResolver
        }.getOrNull()
        systemContext = runCatching {
            XposedHelpers.getObjectField(audioService, "mContext") as? Context
        }.getOrNull()

        val raw = resolver?.let { Settings.Global.getString(it, VolumeStepsBridge.GLOBAL_KEY) }
        val values = if (raw.isNullOrBlank()) emptyMap<String, String>() else VolumeStepsBridge.decode(raw)
        XposedBridge.log(
            if (values.isEmpty()) {
                "$TAG   [VolumeSteps] no boot config yet — stock kept"
            } else {
                "$TAG   [VolumeSteps] boot config: media=${values[VolumeStepsBridge.KEY_MEDIA]} " +
                    "ring=${values[VolumeStepsBridge.KEY_RING]} alarm=${values[VolumeStepsBridge.KEY_ALARM]}"
            }
        )

        val stockMedia = arr[STREAM_MUSIC]
        target(values, VolumeStepsBridge.KEY_MEDIA, VolumeStepsBridge.KEY_MEDIA_CUSTOM)?.let { steps ->
            val factor = if (stockMedia >= STEPLESS_DETECT_MIN) STEPLESS_FACTOR else 1
            set(arr, STREAM_MUSIC, steps * factor)
        }
        target(values, VolumeStepsBridge.KEY_RING, VolumeStepsBridge.KEY_RING_CUSTOM)?.let { steps ->
            // System / system-enforced / DTMF are aliased to ring: keep their range equal.
            for (stream in intArrayOf(STREAM_RING, STREAM_SYSTEM, STREAM_SYSTEM_ENFORCED, STREAM_DTMF)) {
                set(arr, stream, steps)
            }
        }
        target(values, VolumeStepsBridge.KEY_ALARM, VolumeStepsBridge.KEY_ALARM_CUSTOM)?.let { steps ->
            set(arr, STREAM_ALARM, steps)
        }

        rescaleSavedLevels(arr)
    }

    private fun set(arr: IntArray, stream: Int, value: Int) {
        if (arr[stream] == value) return
        XposedBridge.log("$TAG   [VolumeSteps] stream $stream max ${arr[stream]} -> $value")
        arr[stream] = value
    }

    /** Returns the wanted step count, or null to leave the stream untouched. */
    private fun target(values: Map<String, String>, modeKey: String, customKey: String): Int? {
        val steps = when (val mode = values[modeKey]) {
            null, "", MODE_DEFAULT -> return null
            MODE_CUSTOM -> values[customKey]?.toIntOrNull()
            else -> mode.toIntOrNull()
        } ?: return null
        return if (steps in MIN_STEPS..MAX_STEPS) steps else null
    }

    private fun rescaleSavedLevels(arr: IntArray) {
        val resolver = this.resolver ?: return
        try {
            val state = HashMap<Int, Int>()
            Settings.Global.getString(resolver, STATE_KEY)?.let { raw ->
                for ((stream, max) in VolumeStepsBridge.decode(raw)) {
                    val s = stream.toIntOrNull()
                    val m = max.toIntOrNull()
                    if (s != null && m != null) state[s] = m
                }
            }

            val suffixes = deviceSuffixes()
            for ((stream, base) in managedSettings) {
                val newMax = arr[stream]
                // No record yet: the saved rows already refer to the current range.
                val oldMax = state[stream] ?: newMax
                if (oldMax != newMax && oldMax > 0 && newMax > 0) {
                    val rows = rescaleRows(resolver, base, suffixes, oldMax, newMax)
                    XposedBridge.log("$TAG   [VolumeSteps] stream $stream saved levels $oldMax -> $newMax ($rows rows)")
                }
                state[stream] = newMax
            }

            val text = state.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            if (Settings.Global.getString(resolver, STATE_KEY) != text) {
                Settings.Global.putString(resolver, STATE_KEY, text)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] saved-level rescale failed: ${t.message}")
        }
    }

    private fun rescaleRows(
        resolver: ContentResolver,
        base: String,
        suffixes: Set<String>,
        oldMax: Int,
        newMax: Int
    ): Int {
        var rows = 0
        for (key in listOf(base) + suffixes.map { "${base}_$it" }) {
            try {
                val saved = Settings.System.getInt(resolver, key, Int.MIN_VALUE)
                if (saved == Int.MIN_VALUE || saved < 0) continue
                var scaled = (saved.toDouble() * newMax / oldMax).roundToInt().coerceIn(0, newMax)
                if (saved > 0 && scaled == 0) scaled = 1 // never turn a quiet level into silence
                if (scaled != saved) {
                    Settings.System.putInt(resolver, key, scaled)
                    rows++
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [VolumeSteps] could not rescale $key: ${t.message}")
            }
        }
        return rows
    }

    /** Device names AudioService appends to the setting names ("speaker", "bt_a2dp", ...). */
    private fun deviceSuffixes(): Set<String> {
        val names = HashSet<String>()
        try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val devices = HashSet<Int>()
            (XposedHelpers.getStaticObjectField(audioSystem, "DEVICE_OUT_ALL_SET") as? Set<*>)
                ?.forEach { if (it is Int) devices.add(it) }
            devices.add(DEVICE_OUT_DEFAULT)
            for (device in devices) {
                val name = XposedHelpers.callStaticMethod(audioSystem, "getOutputDeviceName", device) as? String
                if (!name.isNullOrEmpty()) names.add(name)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] could not list output devices: ${t.message}")
        }
        return names
    }

    private fun publishConfig(prefs: Prefs) {
        if (!publishing.compareAndSet(false, true)) return
        try {
            grantSecureSettingsToModule()
            val resolver = this.resolver ?: return
            val text = VolumeStepsBridge.encode { prefs.getString(it, "") }
            if (Settings.Global.getString(resolver, VolumeStepsBridge.GLOBAL_KEY) != text) {
                Settings.Global.putString(resolver, VolumeStepsBridge.GLOBAL_KEY, text)
                XposedBridge.log("$TAG   [VolumeSteps] config published (applies after reboot): $text")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] could not publish config: ${t.message}")
        } finally {
            publishing.set(false)
        }
    }

    /** Lets the module app write Settings.Global itself; system_server may grant this permission. */
    private fun grantSecureSettingsToModule() {
        val context = systemContext ?: return
        if (!grantTried.compareAndSet(false, true)) return
        try {
            val packageManager = context.packageManager
            if (packageManager.checkPermission(SECURE_SETTINGS_PERMISSION, MODULE_PACKAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) return
            val user = XposedHelpers.getStaticObjectField(UserHandle::class.java, "SYSTEM")
            XposedHelpers.callMethod(
                packageManager, "grantRuntimePermission", MODULE_PACKAGE, SECURE_SETTINGS_PERMISSION, user
            )
            XposedBridge.log("$TAG   [VolumeSteps] granted WRITE_SECURE_SETTINGS to the module app")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] could not grant WRITE_SECURE_SETTINGS: ${t.message}")
        }
    }
}
