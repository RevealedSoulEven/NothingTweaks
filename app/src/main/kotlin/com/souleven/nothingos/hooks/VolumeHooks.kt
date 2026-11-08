package com.souleven.nothingos.hooks

import android.app.Dialog
import android.content.Context
import android.media.AudioManager
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class VolumeHooks : HookModule {

    private companion object {
        const val PREF_KEY = "volume_panel_timeout"
        const val PREF_DISABLE_SAFE_VOLUME = "pref_disable_safe_volume"
        const val MIN_TIMEOUT_MS = 500
        const val MAX_TIMEOUT_MS = 600_000

        const val NOS4_DIALOG_CLASS = "com.android.systemui.volume.VolumeDialogImpl"
        const val NOS5_VISIBILITY_INTERACTOR_CLASS =
            "com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor"
        const val NOS5_PLUGIN_CLASS = "com.android.systemui.volume.dialog.VolumeDialogPlugin"
        const val NOS5_SAFETY_DELEGATE_CLASS = "com.android.systemui.volume.SafetyWarningDialogDelegate"
        const val NOS5_SETTINGS_REPO_CLASS =
            "com.android.systemui.util.settings.repository.UserAwareSettingsRepository"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val nos4Class = XposedHelpers.findClassIfExists(NOS4_DIALOG_CLASS, lpparam.classLoader)
        val nos5InteractorClass =
            XposedHelpers.findClassIfExists(NOS5_VISIBILITY_INTERACTOR_CLASS, lpparam.classLoader)

        if (nos4Class == null && nos5InteractorClass == null) {
            return
        }

        if (nos4Class != null) {
            initNos4Hooks(nos4Class, prefs)
        }

        if (nos5InteractorClass != null) {
            initNos5Hooks(nos5InteractorClass, lpparam, prefs)
        }
    }

    private fun initNos4Hooks(clazz: Class<*>, prefs: Prefs) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "computeTimeoutH", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wanted = readTimeoutPref(prefs)
                    if (wanted <= 0) return

                    val hovering = try {
                        XposedHelpers.getBooleanField(param.thisObject, "mHovering")
                    } catch (_: Throwable) {
                        false
                    }
                    if (hovering) return

                    val orig = param.result as? Int ?: return
                    if (orig != wanted) {
                        param.result = wanted
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [Volume] NOS4: failed to hook computeTimeoutH: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(clazz, "showSafetyWarningH", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                        param.result = null
                        disableSafeMediaVolumeFromDialog(param.thisObject)
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(clazz, "init", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                        disableSafeMediaVolumeFromDialog(param.thisObject)
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun initNos5Hooks(interactorClass: Class<*>, lpparam: LoadPackageParam, prefs: Prefs) {
        try {
            XposedBridge.hookAllConstructors(interactorClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyNos5Timeout(param.thisObject, prefs, lpparam.classLoader)
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(interactorClass, "resetDismissTimeout", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    applyNos5Timeout(param.thisObject, prefs, lpparam.classLoader)
                }
            })
        } catch (_: Throwable) {}

        for (method in interactorClass.declaredMethods) {
            if (method.name.startsWith("computeTimeout")) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            applyNos5Timeout(param.thisObject, prefs, lpparam.classLoader)
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        val settingsRepoClass = XposedHelpers.findClassIfExists(NOS5_SETTINGS_REPO_CLASS, lpparam.classLoader)
        if (settingsRepoClass != null) {
            try {
                for (m in settingsRepoClass.declaredMethods) {
                    if (m.name == "getInt" && m.parameterTypes.size == 3 && m.parameterTypes[0] == String::class.java) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.args[0] == "volume_dialog_dismiss_timeout") {
                                    val wanted = readTimeoutPref(prefs)
                                    if (wanted > 0) {
                                        param.result = wanted
                                    }
                                }
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        val pluginClass = XposedHelpers.findClassIfExists(NOS5_PLUGIN_CLASS, lpparam.classLoader)
        if (pluginClass != null) {
            try {
                XposedBridge.hookAllMethods(pluginClass, "init", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                            val audioManager = try {
                                XposedHelpers.getObjectField(param.thisObject, "audioManager") as? AudioManager
                            } catch (_: Throwable) { null }
                            disableSafeMediaVolume(audioManager)
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val safetyDelegateClass = XposedHelpers.findClassIfExists(NOS5_SAFETY_DELEGATE_CLASS, lpparam.classLoader)
        if (safetyDelegateClass != null) {
            try {
                XposedBridge.hookAllMethods(safetyDelegateClass, "onStart", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean(PREF_DISABLE_SAFE_VOLUME, false)) {
                            val audioManager = try {
                                XposedHelpers.getObjectField(param.thisObject, "mAudioManager") as? AudioManager
                            } catch (_: Throwable) { null }
                            disableSafeMediaVolume(audioManager)
                            (param.args.firstOrNull { it is Dialog } as? Dialog)?.dismiss()
                        }
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun applyNos5Timeout(interactor: Any, prefs: Prefs, classLoader: ClassLoader) {
        val wanted = readTimeoutPref(prefs)
        if (wanted <= 0) return
        try {
            val durationUnitClass = XposedHelpers.findClassIfExists("kotlin.time.DurationUnit", classLoader)
                ?: return
            val toDurationMethod = XposedHelpers.findClassIfExists("kotlin.time.DurationKt", classLoader)
                ?.getMethod("toDuration", Int::class.javaPrimitiveType, durationUnitClass)
                ?: return
            val millisUnit = durationUnitClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "MILLISECONDS" }
                ?: return
            val rawDuration = toDurationMethod.invoke(null, wanted, millisUnit)
            if (rawDuration is Long) {
                XposedHelpers.setLongField(interactor, "defaultTimeout", rawDuration)
            }
        } catch (_: Throwable) {}
    }

    private fun disableSafeMediaVolumeFromDialog(dialogImpl: Any) {
        try {
            val audioManager = try {
                val controller = XposedHelpers.getObjectField(dialogImpl, "mController")
                XposedHelpers.callMethod(controller, "getAudioManager") as? AudioManager
            } catch (_: Throwable) { null }
                ?: try {
                    val ctx = XposedHelpers.getObjectField(dialogImpl, "mContext") as? Context
                    ctx?.getSystemService(AudioManager::class.java)
                } catch (_: Throwable) { null }

            disableSafeMediaVolume(audioManager)
        } catch (_: Throwable) {}
    }

    private fun disableSafeMediaVolume(audioManager: AudioManager?) {
        try {
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
        val parsed = raw.toIntOrNull() ?: return -1
        if (parsed <= 0) return -1
        return parsed.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
