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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Volume Steps — changes the REAL per-stream step count of AudioService.
 *
 * AudioService keeps the step count of each stream in the static array
 * AudioService.MAX_STREAM_VOLUME. VolumeStreamState's constructor turns it into
 * mIndexMax (x10), publishes the range to the native audio policy
 * (AudioSystem.initStreamVolume) and VolumeGroupState / SystemUI read it back.
 * So we only patch that array right before the first VolumeStreamState is built.
 *
 * Stepless Volume (Nothing, feature 148) stores media as 160 = 16 steps x10.
 * Hardware keys move media by 10 steps-units, the slider moves in single units.
 * To stay compatible we keep that x10 factor: media = N * 10 on such devices.
 *
 * Config channel: AudioService is created before the module's content provider is
 * reachable, and SELinux forbids system_server from opening the module's own
 * settings.xml. The settings therefore travel through Settings.Global (written by the
 * module app, which the hook grants WRITE_SECURE_SETTINGS at boot, and by system_server
 * itself whenever Prefs loads or changes), with a copy in /data/system (CACHE_FILE) as a
 * fallback. Both are read at the next boot, so a change needs one reboot. Only the very
 * first boot after installing the module has nothing to read yet.
 *
 * Saved volume levels: Settings.System stores levels in step units (index / 10), so a
 * different step count would change the loudness of every saved level. Before the first
 * VolumeStreamState reads them, rows of the affected streams are scaled by new/old max.
 * The max that the saved rows currently refer to is kept in STATE_FILE.
 */
class VolumeStepsHooks : HookModule {

    private companion object {
        const val AUDIO_SERVICE = "com.android.server.audio.AudioService"
        const val VSS = "com.android.server.audio.AudioService\$VolumeStreamState"
        const val CACHE_FILE = "/data/system/nothingtweaks_volume_steps.conf"
        const val MODULE_PACKAGE = "com.souleven.nothingos"
        const val SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
        const val STATE_FILE = "/data/system/nothingtweaks_volume_steps.state"

        const val STREAM_SYSTEM = 1
        const val STREAM_RING = 2
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4
        const val STREAM_SYSTEM_ENFORCED = 7
        const val STREAM_DTMF = 8

        const val KEY_MEDIA = VolumeStepsBridge.KEY_MEDIA
        const val KEY_MEDIA_CUSTOM = VolumeStepsBridge.KEY_MEDIA_CUSTOM
        const val KEY_RING = VolumeStepsBridge.KEY_RING
        const val KEY_RING_CUSTOM = VolumeStepsBridge.KEY_RING_CUSTOM
        const val KEY_ALARM = VolumeStepsBridge.KEY_ALARM
        const val KEY_ALARM_CUSTOM = VolumeStepsBridge.KEY_ALARM_CUSTOM

        const val MODE_DEFAULT = "default"
        const val MODE_CUSTOM = "custom"
        const val MIN_STEPS = 4
        const val MAX_STEPS = 100

        // Stock media max is 160 when Nothing's Stepless Volume is active (16 steps x10).
        const val STEPLESS_DETECT_MIN = 100
        const val STEPLESS_FACTOR = 10

        const val DEVICE_OUT_DEFAULT = 0x40000000

    }

    private val originals = ConcurrentHashMap<Int, Int>()
    private val loggedConfig = AtomicBoolean(false)
    private val syncing = AtomicBoolean(false)
    private val rescaled = AtomicBoolean(false)
    @Volatile private var resolver: ContentResolver? = null
    @Volatile private var systemContext: Context? = null
    private val grantTried = AtomicBoolean(false)

    /** Streams whose step count we may change, with the base name of their saved-level rows. */
    private val managedSettings = mapOf(
        STREAM_MUSIC to "volume_music",
        STREAM_RING to "volume_ring",
        STREAM_SYSTEM to "volume_system",
        STREAM_SYSTEM_ENFORCED to "volume_system_enforced",
        STREAM_DTMF to "volume_dtmf",
        STREAM_ALARM to "volume_alarm"
    )

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
                            applyAll(audioService, audioServiceClass)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [VolumeSteps] apply failed, stock kept: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG   [VolumeSteps] hooked VolumeStreamState constructor")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] FAILED to hook VolumeStreamState ctor: ${t.message}")
        }

        // Keep the boot-time copy in sync with the real settings (fires on every prefs load/change).
        prefs.addListener { syncCache(prefs) }
    }

    private fun syncCache(prefs: Prefs) {
        if (!syncing.compareAndSet(false, true)) return
        try {
            grantSecureSettingsToModule()

            val read = { key: String -> prefs.getString(key, "") }
            val fileText = VolumeStepsBridge.encode("\n", read) + "\n"
            val settingsText = VolumeStepsBridge.encode(";", read)

            val file = File(CACHE_FILE)
            if (!file.exists() || file.readText() != fileText) {
                val tmp = File("$CACHE_FILE.tmp")
                tmp.writeText(fileText)
                if (!tmp.renameTo(file)) {
                    file.writeText(fileText)
                    tmp.delete()
                }
                XposedBridge.log("$TAG   [VolumeSteps] boot config updated (applies after reboot): $settingsText")
            }

            resolver?.let {
                if (Settings.Global.getString(it, VolumeStepsBridge.GLOBAL_KEY) != settingsText) {
                    Settings.Global.putString(it, VolumeStepsBridge.GLOBAL_KEY, settingsText)
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] could not write boot config: ${t.message}")
        } finally {
            syncing.set(false)
        }
    }

    /**
     * Lets the module app write Settings.Global itself, so the settings reach the next boot
     * without any manual `pm grant`. system_server is allowed to grant this permission.
     */
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

    private fun applyAll(audioService: Any, audioServiceClass: Class<*>) {
        val arr = XposedHelpers.getStaticObjectField(audioServiceClass, "MAX_STREAM_VOLUME") as? IntArray
            ?: return
        resolver = XposedHelpers.getObjectField(audioService, "mContentResolver") as? ContentResolver
        systemContext = XposedHelpers.getObjectField(audioService, "mContext") as? Context
        val values = readConfig()

        // Remember the stock value of every managed stream before anything is changed.
        for (stream in managedSettings.keys) {
            if (stream in arr.indices) original(arr, stream)
        }

        target(values, KEY_MEDIA, KEY_MEDIA_CUSTOM)?.let { n ->
            val orig = original(arr, STREAM_MUSIC)
            val factor = if (orig >= STEPLESS_DETECT_MIN) STEPLESS_FACTOR else 1
            set(arr, STREAM_MUSIC, n * factor)
        }
        target(values, KEY_RING, KEY_RING_CUSTOM)?.let { n ->
            // System / system-enforced / DTMF are aliased to ring: keep their range equal.
            for (s in intArrayOf(STREAM_RING, STREAM_SYSTEM, STREAM_SYSTEM_ENFORCED, STREAM_DTMF)) {
                set(arr, s, n)
            }
        }
        target(values, KEY_ALARM, KEY_ALARM_CUSTOM)?.let { n ->
            set(arr, STREAM_ALARM, n)
        }

        // Once per boot, before the first VolumeStreamState reads the saved levels.
        if (rescaled.compareAndSet(false, true)) rescaleSavedLevels(audioService, arr)
    }

    private fun rescaleSavedLevels(audioService: Any, arr: IntArray) {
        try {
            val resolver = this.resolver
            if (resolver == null) {
                XposedBridge.log("$TAG   [VolumeSteps] no content resolver — saved levels left as they are")
                return
            }
            val state = readState()
            val suffixes = deviceSuffixes()
            var changed = false
            for ((stream, base) in managedSettings) {
                if (stream !in arr.indices) continue
                val newMax = arr[stream]
                // No record yet: the saved rows already refer to the current range.
                val oldMax = state[stream] ?: newMax
                if (oldMax != newMax && oldMax > 0 && newMax > 0) {
                    val rows = rescaleRows(resolver, base, suffixes, oldMax, newMax)
                    XposedBridge.log("$TAG   [VolumeSteps] stream $stream saved levels $oldMax -> $newMax ($rows rows)")
                }
                if (state[stream] != newMax) {
                    state[stream] = newMax
                    changed = true
                }
            }
            if (changed) writeState(state)
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
        val keys = ArrayList<String>(suffixes.size + 1)
        keys.add(base)
        for (suffix in suffixes) keys.add("${base}_$suffix")
        var rows = 0
        for (key in keys) {
            val saved = Settings.System.getInt(resolver, key, Int.MIN_VALUE)
            if (saved == Int.MIN_VALUE || saved < 0) continue
            var scaled = (saved.toDouble() * newMax / oldMax).roundToInt().coerceIn(0, newMax)
            if (saved > 0 && scaled == 0) scaled = 1 // never turn a quiet level into silence
            if (scaled != saved) {
                Settings.System.putInt(resolver, key, scaled)
                rows++
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
            val all = XposedHelpers.getStaticObjectField(audioSystem, "DEVICE_OUT_ALL_SET") as? Set<*>
            all?.forEach { if (it is Int) devices.add(it) }
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

    private fun readState(): MutableMap<Int, Int> {
        val map = HashMap<Int, Int>()
        try {
            val file = File(STATE_FILE)
            if (file.exists()) {
                for (line in file.readLines()) {
                    val i = line.indexOf('=')
                    if (i <= 0) continue
                    val key = line.substring(0, i).trim().toIntOrNull() ?: continue
                    val value = line.substring(i + 1).trim().toIntOrNull() ?: continue
                    map[key] = value
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] state read failed: ${t.message}")
        }
        return map
    }

    private fun writeState(map: Map<Int, Int>) {
        try {
            val text = map.entries.sortedBy { it.key }
                .joinToString(separator = "\n", postfix = "\n") { "${it.key}=${it.value}" }
            val file = File(STATE_FILE)
            val tmp = File("$STATE_FILE.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [VolumeSteps] state write failed: ${t.message}")
        }
    }

    private fun original(arr: IntArray, stream: Int): Int =
        originals.getOrPut(stream) { arr[stream] }

    private fun set(arr: IntArray, stream: Int, value: Int) {
        if (stream !in arr.indices || arr[stream] == value) return
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

    private fun readConfig(): Map<String, String> {
        val first = loggedConfig.compareAndSet(false, true)
        return try {
            var source = "settings"
            var map: Map<String, String> = emptyMap()

            val fromSettings = resolver?.let { Settings.Global.getString(it, VolumeStepsBridge.GLOBAL_KEY) }
            if (!fromSettings.isNullOrBlank()) {
                map = VolumeStepsBridge.decode(fromSettings)
            } else {
                source = "file"
                val file = File(CACHE_FILE)
                if (file.exists()) map = VolumeStepsBridge.decode(file.readText())
            }

            if (first) {
                if (map.isEmpty()) {
                    XposedBridge.log("$TAG   [VolumeSteps] no boot config yet (first boot after install) — stock kept")
                } else {
                    XposedBridge.log(
                        "$TAG   [VolumeSteps] boot config ($source): media=${map[KEY_MEDIA]} ring=${map[KEY_RING]} alarm=${map[KEY_ALARM]}"
                    )
                }
            }
            map
        } catch (t: Throwable) {
            if (first) XposedBridge.log("$TAG   [VolumeSteps] boot config read failed: ${t.message}")
            emptyMap()
        }
    }
}
