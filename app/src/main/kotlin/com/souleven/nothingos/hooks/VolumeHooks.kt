package com.souleven.nothingos.hooks

import android.content.Context
import android.media.AudioManager
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class VolumeHooks : HookModule {

    private val className = "com.android.systemui.volume.VolumeDialogImpl"

    private companion object {
        const val PREF_KEY = "volume_panel_timeout"
        const val PREF_DISABLE_SAFE_VOLUME = "pref_disable_safe_volume"
        const val MIN_TIMEOUT_MS = 500
        const val MAX_TIMEOUT_MS = 600_000
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = try {
            XposedHelpers.findClass(className, lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] class not found: $className — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "computeTimeoutH", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wanted = readTimeoutPref(prefs)
                    if (wanted <= 0) return

                    // Leave the special cases (hover, safety warning, captions tooltip) at stock.
                    val hovering = try {
                        XposedHelpers.getBooleanField(param.thisObject, "mHovering")
                    } catch (t: Throwable) {
                        false
                    }
                    if (hovering) {
                        return
                    }

                    val orig = param.result as? Int ?: return
                    if (orig != wanted) {
                        param.result = wanted
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] FAILED to hook computeTimeoutH: ${t.message}")
            XposedBridge.log(t)
        }

        try {
            XposedBridge.hookAllMethods(clazz, "showSafetyWarningH", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                        param.result = null
                        disableSafeMediaVolume(param.thisObject)
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] FAILED to hook showSafetyWarningH: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(clazz, "init", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                        disableSafeMediaVolume(param.thisObject)
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] FAILED to hook init: ${t.message}")
        }
    }

    private fun disableSafeMediaVolume(dialogImpl: Any) {
        try {
            val audioManager = try {
                val controller = XposedHelpers.getObjectField(dialogImpl, "mController")
                XposedHelpers.callMethod(controller, "getAudioManager") as? AudioManager
            } catch (_: Throwable) { null }
                ?: try {
                    val ctx = XposedHelpers.getObjectField(dialogImpl, "mContext") as? Context
                    ctx?.getSystemService(AudioManager::class.java)
                } catch (_: Throwable) { null }

            if (audioManager != null) {
                XposedHelpers.callMethod(audioManager, "disableSafeMediaVolume")
            } else {
                val audioService = XposedHelpers.callStaticMethod(AudioManager::class.java, "getService")
                if (audioService != null) {
                    XposedHelpers.callMethod(audioService, "disableSafeMediaVolume", "com.android.systemui")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun readTimeoutPref(prefs: Prefs): Int {
        val raw = prefs.getString(PREF_KEY, "").trim()
        if (raw.isBlank()) return -1
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            XposedBridge.log("$TAG   [Volume] bad timeout pref value: '$raw' — ignoring")
            return -1
        }
        if (parsed <= 0) return -1
        val clamped = parsed.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        if (clamped != parsed) {
            XposedBridge.log("$TAG   [Volume] timeout ${parsed}ms clamped to ${clamped}ms")
        }
        return clamped
    }
}
