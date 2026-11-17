package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class PowerButtonHooks : HookModule {

    companion object {
        const val KEY_CUSTOM_APP = "pref_power_double_tap_custom_app"
        const val KEY_APP = "pref_power_double_tap_app"
        private const val DEBOUNCE_MS = 600L

        private var lastTriggerTime = 0L

        @Volatile var sActivityStarter: Any? = null
        @Volatile var sContext: Context? = null

        fun launchAppFromSystemUI(thisObject: Any, targetPackage: String, classLoader: ClassLoader) {
            val context = try {
                XposedHelpers.getObjectField(thisObject, "mContext") as? Context
            } catch (_: Throwable) {
                try {
                    XposedHelpers.getObjectField(thisObject, "context") as? Context
                } catch (_: Throwable) { null }
            } ?: (thisObject as? Context) ?: sContext ?: AndroidAppHelper.currentApplication() ?: return

            vibrateClick(context)

            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            } ?: return

            val activityStarter = sActivityStarter
                ?: try {
                    val depClass = XposedHelpers.findClassIfExists("com.android.systemui.Dependency", classLoader)
                    val starterClass = XposedHelpers.findClassIfExists("com.android.systemui.plugins.ActivityStarter", classLoader)
                    if (depClass != null && starterClass != null) {
                        XposedHelpers.callStaticMethod(depClass, "get", starterClass)
                    } else null
                } catch (_: Throwable) { null }
                ?: try {
                    XposedHelpers.getObjectField(thisObject, "mActivityStarter")
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.getObjectField(thisObject, "activityStarter")
                    } catch (_: Throwable) { null }
                }
                ?: try {
                    val callbacks = XposedHelpers.getObjectField(thisObject, "mCallbacks") as? java.util.ArrayList<*>
                    var found: Any? = null
                    if (callbacks != null) {
                        for (cb in callbacks) {
                            if (cb == null) continue
                            found = try {
                                XposedHelpers.getObjectField(cb, "mActivityStarter")
                            } catch (_: Throwable) {
                                try {
                                    XposedHelpers.getObjectField(cb, "activityStarter")
                                } catch (_: Throwable) { null }
                            }
                            if (found != null) break
                        }
                    }
                    found
                } catch (_: Throwable) { null }

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isInteractive) {
                try {
                    XposedHelpers.callMethod(powerManager, "wakeUp", SystemClock.uptimeMillis(), 4, "AppLaunch")
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.callMethod(powerManager, "wakeUp", SystemClock.uptimeMillis())
                    } catch (_: Throwable) {}
                }
            }

            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isLocked = km?.isKeyguardLocked ?: false

            if (activityStarter != null) {
                if (!isLocked) {
                    if (callActivityStarterStartActivity(activityStarter, intent)) {
                        return
                    }
                }

                if (callActivityStarterDismissKeyguard(activityStarter, intent)) {
                    return
                }

                if (callActivityStarterStartActivity(activityStarter, intent)) {
                    return
                }
            }

            // fallback: try context.startActivity with Background Activity Launch exemption options
            val options = android.app.ActivityOptions.makeBasic()
            try {
                XposedHelpers.callMethod(options, "setPendingIntentBackgroundActivityStartMode", 1)
            } catch (_: Throwable) {}
            try {
                context.startActivity(intent, options.toBundle())
            } catch (_: Throwable) {
                try {
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG [PowerButton] Launch failed: ${t.message}")
                }
            }
        }

        private fun callActivityStarterStartActivity(starter: Any, intent: Intent): Boolean {
            try {
                XposedHelpers.callMethod(starter, "startActivity", intent, true)
                return true
            } catch (_: Throwable) {}
            try {
                val method = starter.javaClass.methods.firstOrNull {
                    it.name == "startActivity" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Intent::class.java
                }
                if (method != null) {
                    val args = arrayOfNulls<Any>(method.parameterCount)
                    args[0] = intent
                    for (i in 1 until method.parameterCount) {
                        val pt = method.parameterTypes[i]
                        args[i] = when (pt) {
                            java.lang.Boolean.TYPE -> true
                            java.lang.Integer.TYPE -> 0
                            else -> null
                        }
                    }
                    method.isAccessible = true
                    method.invoke(starter, *args)
                    return true
                }
            } catch (_: Throwable) {}
            return false
        }

        private fun callActivityStarterDismissKeyguard(starter: Any, intent: Intent): Boolean {
            try {
                XposedHelpers.callMethod(starter, "postStartActivityDismissingKeyguard", intent, 0)
                return true
            } catch (_: Throwable) {}
            try {
                val method = starter.javaClass.methods.firstOrNull {
                    it.name == "postStartActivityDismissingKeyguard" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == Intent::class.java
                }
                if (method != null) {
                    val args = arrayOfNulls<Any>(method.parameterCount)
                    args[0] = intent
                    for (i in 1 until method.parameterCount) {
                        val pt = method.parameterTypes[i]
                        args[i] = when (pt) {
                            java.lang.Integer.TYPE -> 0
                            java.lang.Boolean.TYPE -> false
                            else -> null
                        }
                    }
                    method.isAccessible = true
                    method.invoke(starter, *args)
                    return true
                }
            } catch (_: Throwable) {}
            return false
        }

        private fun vibrateClick(context: Context) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(35L)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName == "android") {
            hookSystemServer(lpparam.classLoader, prefs)
        } else if (lpparam.packageName == "com.android.systemui") {
            hookSystemUI(lpparam.classLoader, prefs)
        }
    }

    private fun hookSystemServer(classLoader: ClassLoader, prefs: Prefs) {
        val glsClass = XposedHelpers.findClassIfExists(
            "com.android.server.GestureLauncherService",
            classLoader
        )
        if (glsClass != null) {
            // ensure system gesture detection remains active even if user turned off camera gesture in stock settings
            try {
                XposedBridge.hookAllMethods(glsClass, "isCameraDoubleTapPowerEnabled", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            param.result = true
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(glsClass, "interceptPowerKeyDown", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mCameraDoubleTapPowerEnabled", true)
                            } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val pwmClass = XposedHelpers.findClassIfExists(
            "com.android.server.policy.PhoneWindowManager",
            classLoader
        )
        if (pwmClass != null) {
            // NOS 5 enables early short press on power key which immediately turns off the screen on the first press
            // disable it when custom double-tap app is active so double-tap can be detected without prematurely locking
            try {
                XposedBridge.hookAllMethods(pwmClass, "initSingleKeyGestureRules", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mShouldEarlyShortPressOnPower", false)
                            } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(pwmClass, "updateSettings", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mShouldEarlyShortPressOnPower", false)
                            } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(pwmClass, "interceptPowerKeyDown", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "mShouldEarlyShortPressOnPower", false)
                            } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(pwmClass, "getMaxMultiPressPowerCount", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            val count = (param.result as? Int) ?: 1
                            if (count < 2) {
                                param.result = 2
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val powerRuleClass = XposedHelpers.findClassIfExists(
            "com.android.server.policy.PhoneWindowManager\$PowerKeyRule",
            classLoader
        )
        if (powerRuleClass != null) {
            try {
                XposedBridge.hookAllMethods(powerRuleClass, "onKeyUp", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (enabled && targetPackage.isNotEmpty()) {
                            val count = param.args.getOrNull(0) as? Int ?: 1
                            if (count == 1) {
                                param.result = null
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun hookSystemUI(classLoader: ClassLoader, prefs: Prefs) {
        // cache ActivityStarter from ActivityStarterImpl
        val starterClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.ActivityStarterImpl", classLoader)
        if (starterClass != null) {
            try {
                XposedBridge.hookAllConstructors(starterClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sActivityStarter = param.thisObject
                    }
                })
            } catch (_: Throwable) {}
        }

        // cache ActivityStarter from CentralSurfacesImpl
        val csImplClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.CentralSurfacesImpl", classLoader)
        if (csImplClass != null) {
            try {
                XposedBridge.hookAllConstructors(csImplClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val starter = XposedHelpers.getObjectField(param.thisObject, "mActivityStarter")
                            if (starter != null) {
                                sActivityStarter = starter
                            }
                        } catch (_: Throwable) {}
                    }
                })
                XposedBridge.hookAllMethods(csImplClass, "start", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val starter = XposedHelpers.getObjectField(param.thisObject, "mActivityStarter")
                            if (starter != null) {
                                sActivityStarter = starter
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}
        }

        // 1. hook CommandQueue - the AIDL entry point in SystemUI from system_server
        val commandQueueClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.CommandQueue", classLoader)
        if (commandQueueClass != null) {
            try {
                XposedBridge.hookAllMethods(commandQueueClass, "addCallback", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cb = param.args.getOrNull(0) ?: return
                        try {
                            val starter = XposedHelpers.getObjectField(cb, "mActivityStarter")
                            if (starter != null) {
                                sActivityStarter = starter
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(commandQueueClass, "onCameraLaunchGestureDetected", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        if (!enabled) return
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (targetPackage.isEmpty()) return

                        val source = param.args.getOrNull(0) as? Int ?: 1
                        if (source != 1) return

                        val now = SystemClock.uptimeMillis()
                        if (now - lastTriggerTime < DEBOUNCE_MS) {
                            param.result = null
                            return
                        }
                        lastTriggerTime = now

                        param.result = null // Block camera launch in CommandQueue

                        val cmdQueueObj = param.thisObject
                        Handler(Looper.getMainLooper()).post {
                            launchAppFromSystemUI(cmdQueueObj, targetPackage, classLoader)
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [PowerButton] Failed to hook CommandQueue: ${t.message}")
            }
        }

        // 2. also hook CentralSurfacesImpl as fallback
        val csClasses = listOf(
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.statusbar.phone.CentralSurfaces",
            "com.android.systemui.statusbar.phone.StatusBar"
        )
        for (csName in csClasses) {
            val csClass = XposedHelpers.findClassIfExists(csName, classLoader) ?: continue
            try {
                XposedBridge.hookAllMethods(csClass, "onCameraLaunchGestureDetected", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        if (!enabled) return
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (targetPackage.isEmpty()) return

                        val source = param.args.getOrNull(0) as? Int ?: 1
                        if (source != 1) return

                        val now = SystemClock.uptimeMillis()
                        if (now - lastTriggerTime < DEBOUNCE_MS) {
                            param.result = null
                            return
                        }
                        lastTriggerTime = now

                        param.result = null

                        val csObj = param.thisObject
                        Handler(Looper.getMainLooper()).post {
                            launchAppFromSystemUI(csObj, targetPackage, classLoader)
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        // 3. hook CameraGestureHelper and CameraLauncher as additional safety
        val cameraLauncherClasses = listOf(
            "com.android.systemui.camera.CameraGestureHelper",
            "com.android.systemui.shade.CameraLauncher",
            "com.android.systemui.statusbar.phone.CameraLauncher"
        )
        for (clsName in cameraLauncherClasses) {
            val launcherClass = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
            try {
                XposedBridge.hookAllMethods(launcherClass, "launchCamera", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val enabled = prefs.getBoolean(KEY_CUSTOM_APP, false)
                        if (!enabled) return
                        val targetPackage = prefs.getString(KEY_APP, "")
                        if (targetPackage.isEmpty()) return

                        val now = SystemClock.uptimeMillis()
                        if (now - lastTriggerTime < DEBOUNCE_MS) {
                            consumeCameraMethod(param)
                            return
                        }
                        lastTriggerTime = now

                        consumeCameraMethod(param)
                        val thisObj = param.thisObject
                        Handler(Looper.getMainLooper()).post {
                            launchAppFromSystemUI(thisObj, targetPackage, classLoader)
                        }
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun consumeCameraMethod(param: XC_MethodHook.MethodHookParam) {
        val method = param.method as? java.lang.reflect.Method
        if (method != null && method.returnType == java.lang.Boolean.TYPE) {
            param.result = true
        } else {
            param.result = null
        }
    }
}
