package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG

class PowerMenuHooks : HookModule {

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val dialogLiteClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.globalactions.GlobalActionsDialogLite",
                lpparam.classLoader
            )
            val restartActionClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.globalactions.GlobalActionsDialogLite\$RestartAction",
                lpparam.classLoader
            )

            if (dialogLiteClass != null && restartActionClass != null) {
                // Hook createActionItems to inject our custom buttons into the grid
                XposedHelpers.findAndHookMethod(
                    dialogLiteClass,
                    "createActionItems",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("pref_advanced_power_menu", false)) return

                            val dialogLite = param.thisObject
                            val mItems = XposedHelpers.getObjectField(dialogLite, "mItems") as java.util.ArrayList<Any>

                            // Recovery Button
                            val recoveryAction = XposedHelpers.newInstance(restartActionClass, dialogLite)
                            XposedHelpers.setIntField(recoveryAction, "mMessageResId", 0)
                            XposedHelpers.setObjectField(recoveryAction, "mMessage", "Recovery")
                            mItems.add(recoveryAction)

                            // Bootloader Button
                            val bootloaderAction = XposedHelpers.newInstance(restartActionClass, dialogLite)
                            XposedHelpers.setIntField(bootloaderAction, "mMessageResId", 0)
                            XposedHelpers.setObjectField(bootloaderAction, "mMessage", "Bootloader")
                            mItems.add(bootloaderAction)
                        }
                    }
                )

                // Hook onPress to intercept custom button clicks
                XposedHelpers.findAndHookMethod(
                    restartActionClass,
                    "onPress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("pref_advanced_power_menu", false)) return

                            val mMessageStr = (XposedHelpers.getObjectField(param.thisObject, "mMessage") as? CharSequence)?.toString()
                            
                            if (mMessageStr == "Recovery" || mMessageStr == "Bootloader") {
                                // cancel default restart behavior
                                param.result = null

                                val dialogLite = XposedHelpers.getObjectField(param.thisObject, "this\$0")
                                val context = XposedHelpers.getObjectField(dialogLite, "mContext") as? Context

                                val rebootRunnable = Runnable {
                                    try {
                                        val intent = Intent()
                                        if (mMessageStr == "Recovery") {
                                            intent.action = "com.souleven.nothingos.REBOOT_RECOVERY"
                                        } else {
                                            intent.action = "com.souleven.nothingos.REBOOT_BOOTLOADER"
                                        }
                                        intent.setPackage("android") // make it an explicit broadcast to system_server
                                        context?.sendBroadcast(intent)
                                    } catch (e: Exception) {
                                        XposedBridge.log("$TAG Failed to send reboot broadcast: ${e.message}")
                                    }
                                }

                                val shouldVerify = try {
                                    XposedHelpers.callMethod(dialogLite, "checkShouldPowerOffVerify") as? Boolean ?: false
                                } catch (_: Throwable) {
                                    false
                                }

                                if (shouldVerify) {
                                    try {
                                        val ntDepClass = XposedHelpers.findClassIfExists("com.nothing.systemui.NTDependencyEx", lpparam.classLoader)
                                        val secExClass = XposedHelpers.findClassIfExists("com.nothing.keyguard.KeyguardSecurityContainerControllerEx", lpparam.classLoader)
                                        if (ntDepClass != null && secExClass != null) {
                                            val secEx = XposedHelpers.callStaticMethod(ntDepClass, "get", secExClass)
                                            if (secEx != null) {
                                                XposedHelpers.callMethod(secEx, "setSecurityFinishAction", rebootRunnable)
                                            }
                                        }
                                        showBouncer(dialogLite, "PowerOffVerify-$mMessageStr-onPress")
                                    } catch (t: Throwable) {
                                        XposedBridge.log("$TAG Failed to show bouncer for power off verify: ${t.message}")
                                        rebootRunnable.run()
                                    }
                                } else {
                                    try {
                                        val ntDepClass = XposedHelpers.findClassIfExists("com.nothing.systemui.NTDependencyEx", lpparam.classLoader)
                                        val mediatorExClass = XposedHelpers.findClassIfExists("com.nothing.systemui.keyguard.KeyguardViewMediatorEx", lpparam.classLoader)
                                        if (ntDepClass != null && mediatorExClass != null) {
                                            val mediatorEx = XposedHelpers.callStaticMethod(ntDepClass, "get", mediatorExClass)
                                            if (mediatorEx != null) {
                                                XposedHelpers.callMethod(mediatorEx, "setRebootOrShutdown", true)
                                            }
                                        }
                                    } catch (_: Throwable) {}

                                    try {
                                        XposedHelpers.callMethod(dialogLite, "dismissDialog")
                                    } catch (_: Throwable) {}

                                    rebootRunnable.run()
                                }
                            }
                        }
                    }
                )

                // hook onLongPress to consume long press on custom buttons so safe mode reboot isn't triggered
                XposedHelpers.findAndHookMethod(
                    restartActionClass,
                    "onLongPress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("pref_advanced_power_menu", false)) return

                            val mMessageStr = (XposedHelpers.getObjectField(param.thisObject, "mMessage") as? CharSequence)?.toString()
                            if (mMessageStr == "Recovery" || mMessageStr == "Bootloader") {
                                param.result = true
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Power Menu: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private fun showBouncer(dialogLite: Any, reason: String) {
        try {
            XposedHelpers.callMethod(dialogLite, "showBouncerForPowerOffVerify", reason)
        } catch (_: Throwable) {
            try {
                val bouncerInteractor = XposedHelpers.getObjectField(dialogLite, "mPrimaryBouncerInteractor")
                XposedHelpers.callMethod(bouncerInteractor, "show", true, reason)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to show bouncer: ${t.message}")
            }
        }
    }
}
