package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHooks : HookModule {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam, prefs: Prefs) {
        val containerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.NotificationIconContainer", lpparam.classLoader)
        if (containerClass != null) {
            
            try {
                XposedBridge.hookAllMethods(containerClass, "setMaxIconsAmount", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            param.args[0] = maxIcons
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook setMaxIconsAmount: ${t.message}")
            }

            // Hook initResources to override AOD and Lockscreen limit
            try {
                XposedBridge.hookAllMethods(containerClass, "initResources", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                        val maxIcons = maxIconsStr.toIntOrNull()
                        if (maxIcons != null) {
                            try { XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnAod", maxIcons) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(param.thisObject, "mMaxIconsOnLockscreen", maxIcons) } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook initResources: ${t.message}")
            }

            // Hook getIconLimit in NotificationIconsViewData (Android 14+ ViewModel flow)
            try {
                val dataClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData", lpparam.classLoader)
                if (dataClass != null) {
                    XposedBridge.hookAllMethods(dataClass, "getIconLimit", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val maxIconsStr = prefs.getString("pref_max_notif_icons", "")
                            val maxIcons = maxIconsStr.toIntOrNull()
                            if (maxIcons != null) {
                                param.result = maxIcons
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: [NotificationIconHooks] FAILED to hook getIconLimit: ${t.message}")
            }
        }
    }
}
