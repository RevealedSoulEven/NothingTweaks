package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        ) ?: return

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

    private fun hookSystemUI(classLoader: ClassLoader, prefs: Prefs) {
        // 1. hook CommandQueue - the AIDL entry point in SystemUI from system_server
        val commandQueueClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.CommandQueue", classLoader)
        if (commandQueueClass != null) {
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
                        XposedBridge.log("$TAG [PowerButton] Intercepted onCameraLaunchGestureDetected in CommandQueue")

                        val cmdQueueObj = param.thisObject
                        Handler(Looper.getMainLooper()).post {
                            launchAppFromSystemUI(cmdQueueObj, targetPackage, classLoader)
                        }
                    }
                })
                XposedBridge.log("$TAG [PowerButton] Hooked CommandQueue#onCameraLaunchGestureDetected")
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
                        XposedBridge.log("$TAG [PowerButton] Intercepted onCameraLaunchGestureDetected in $csName")

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

    private fun launchAppFromSystemUI(thisObject: Any, targetPackage: String, classLoader: ClassLoader) {
        val context = try {
            XposedHelpers.getObjectField(thisObject, "mContext") as? Context
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(thisObject, "context") as? Context
            } catch (_: Throwable) { null }
        } ?: AndroidAppHelper.currentApplication() ?: return

        vibrateClick(context)

        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(targetPackage)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        } ?: return

        // 1. try Dependency.get(ActivityStarter.class)
        val activityStarter = try {
            val depClass = XposedHelpers.findClassIfExists("com.android.systemui.Dependency", classLoader)
            val starterClass = XposedHelpers.findClassIfExists("com.android.systemui.plugins.ActivityStarter", classLoader)
            if (depClass != null && starterClass != null) {
                XposedHelpers.callStaticMethod(depClass, "get", starterClass)
            } else null
        } catch (_: Throwable) { null }
            // 2. try from thisObject fields
            ?: try {
                XposedHelpers.getObjectField(thisObject, "mActivityStarter")
            } catch (_: Throwable) {
                try {
                    XposedHelpers.getObjectField(thisObject, "activityStarter")
                } catch (_: Throwable) { null }
            }
            // 3. try searching CommandQueue callbacks (e.g. CentralSurfacesImpl)
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

        if (activityStarter != null) {
            try {
                XposedHelpers.callMethod(activityStarter, "postStartActivityDismissingKeyguard", intent, 0)
                XposedBridge.log("$TAG [PowerButton] Launched $targetPackage via postStartActivityDismissingKeyguard")
                return
            } catch (_: Throwable) {}

            try {
                XposedHelpers.callMethod(activityStarter, "startActivity", intent, true)
                XposedBridge.log("$TAG [PowerButton] Launched $targetPackage via ActivityStarter#startActivity")
                return
            } catch (_: Throwable) {}
        }

        context.startActivity(intent)
        XposedBridge.log("$TAG [PowerButton] Launched $targetPackage via context#startActivity")
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
