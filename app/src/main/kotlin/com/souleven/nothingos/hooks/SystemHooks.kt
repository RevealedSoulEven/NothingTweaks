package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SystemHooks {
    private const val KEY_ALLOW_180_ROTATION = "allow_180_rotation"
    private const val KEY_DISABLE_SAFE_VOLUME = "pref_disable_safe_volume"

    fun init(classLoader: ClassLoader, prefs: Prefs) {
        val displayRotationClass = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayRotation", classLoader)
        if (displayRotationClass != null) {
            try {
                XposedBridge.hookAllMethods(displayRotationClass, "getAllowAllRotations", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        prefs.forceReload()
                        if (prefs.getBoolean(KEY_ALLOW_180_ROTATION, false)) {
                            param.result = 1 // ALLOW_ALL_ROTATIONS_ENABLED
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [SystemHooks] FAILED to hook getAllowAllRotations: ${t.message}")
            }
        } else {
            XposedBridge.log("NothingTweaks: [SystemHooks] FAILED - DisplayRotation class not found!")
        }

        hookSafeMediaVolume(classLoader, prefs)
    }

    private fun hookSafeMediaVolume(classLoader: ClassLoader, prefs: Prefs) {
        val soundDoseHelperClass = XposedHelpers.findClassIfExists("com.android.server.audio.SoundDoseHelper", classLoader) ?: return

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "safeDevicesContains", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.forceReload()
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "checkSafeMediaVolume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.forceReload()
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "raiseVolumeDisplaySafeMediaVolume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.forceReload()
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "enforceSafeMediaVolumeIfActive", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.forceReload()
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = null
                    }
                }
            })
        } catch (_: Throwable) {}
    }
}
