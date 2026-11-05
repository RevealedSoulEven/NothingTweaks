package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LauncherHooks : HookModule {

    companion object {
        const val TAG = "NothingTweaks:Launcher"
        const val PREF_SKIP_HIDDEN_APPS_AUTH = "pref_skip_hidden_apps_auth"
        private const val CLASS_SMART_FULL_SHEET = "com.nothing.launcher.allapps.smart.SmartFullSheet"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.nothing.launcher") return
        val classLoader = lpparam.classLoader

        val sheetClass = XposedHelpers.findClassIfExists(CLASS_SMART_FULL_SHEET, classLoader) ?: return

        try {
            XposedBridge.hookAllMethods(sheetClass, "isNeedAuthenticate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_SKIP_HIDDEN_APPS_AUTH, false)) {
                        param.result = false
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook isNeedAuthenticate: ${t.message}")
        }
    }
}
