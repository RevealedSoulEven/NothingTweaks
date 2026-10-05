// ALL CREDITS GOES TO LSPOSED/DISABLEFLAGSECURE
// THIS CODE IS TAKEN FROM LSPOSED
// https://github.com/LSPosed/DisableFlagSecure

// https://github.com/LSPosed/DisableFlagSecure/blob/main/app/src/main/java/io/github/lsposed/disableflagsecure/DisableFlagSecure.java

package com.souleven.nothingos.hooks

import android.hardware.display.DisplayManager
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG
import java.lang.reflect.Field

class FlagSecureHooks : HookModule {

    companion object {
        const val PREF_DISABLE_FLAG_SECURE = "pref_disable_flag_secure"
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG [FlagSecureHooks] $msg")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName == "android") {
            hookWindowState(lpparam.classLoader, prefs)
            hookScreenCapture(lpparam.classLoader, prefs)
            hookScreenshotHardwareBuffer(lpparam.classLoader, prefs)
            hookActivityTaskManagerService(lpparam.classLoader, prefs)
            hookWindowManagerService(lpparam.classLoader, prefs)
            hookDisplayControl(lpparam.classLoader, prefs)
            hookVirtualDisplayAdapter(lpparam.classLoader, prefs)
        } else if (lpparam.packageName == "com.android.systemui") {
            hookScreenCapture(lpparam.classLoader, prefs)
            hookScreenshotHardwareBuffer(lpparam.classLoader, prefs)
        }
    }

    /**
     * WindowState.isSecureLocked():
     * Return false for screenshot / task snapshot / capture callers so Android permits the capture.
     * When called during surface creation (setInitialSurfaceControlProperties / createSurfaceLocked),
     * let the original method run so SurfaceFlinger retains secure flags and apps don't detect tampering.
     */
    private fun hookWindowState(classLoader: ClassLoader, prefs: Prefs) {
        val windowStateClass = XposedHelpers.findClassIfExists("com.android.server.wm.WindowState", classLoader) ?: return
        val systemServerCl = windowStateClass.classLoader
        try {
            XposedHelpers.findAndHookMethod(
                windowStateClass,
                "isSecureLocked",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) return

                        val match = try {
                            val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                            walker.walk { frames ->
                                frames.anyMatch { frame ->
                                    val declClass = frame.declaringClass
                                    declClass != null && declClass.classLoader == systemServerCl &&
                                        (frame.methodName == "setInitialSurfaceControlProperties" ||
                                         frame.methodName == "createSurfaceLocked")
                                }
                            }
                        } catch (_: Throwable) {
                            Thread.currentThread().stackTrace.any { frame ->
                                frame.methodName == "setInitialSurfaceControlProperties" ||
                                    frame.methodName == "createSurfaceLocked"
                            }
                        }

                        if (!match) {
                            param.result = false
                        }
                    }
                }
            )
            log("Hooked WindowState.isSecureLocked")
        } catch (t: Throwable) {
            log("Failed to hook WindowState.isSecureLocked: ${t.message}")
        }
    }

    /**
     * ScreenCapture (Android 14+) / SurfaceControl:
     * Force mCaptureSecureLayers = true (or mSecureContentPolicy = 1) in CaptureArgs
     * so SurfaceFlinger captures secure layers rather than blacking them out.
     */
    private fun hookScreenCapture(classLoader: ClassLoader, prefs: Prefs) {
        val screenCaptureClasses = listOf(
            "android.window.ScreenCaptureInternal",
            "android.window.ScreenCapture",
            "android.view.SurfaceControl"
        )
        val screenCaptureClass = screenCaptureClasses.firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name, classLoader)
        } ?: return

        val captureArgsClasses = listOf(
            "android.window.ScreenCaptureInternal\$CaptureArgs",
            "android.window.ScreenCapture\$CaptureArgs",
            "android.view.SurfaceControl\$CaptureArgs"
        )
        val captureArgsClass = captureArgsClasses.firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name, classLoader)
        }

        var secureField: Field? = null
        if (captureArgsClass != null) {
            for (fieldName in listOf("mSecureContentPolicy", "mCaptureSecureLayers")) {
                try {
                    val field = captureArgsClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    secureField = field
                    break
                } catch (_: Throwable) {}
            }
        }

        val hooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) return
                val field = secureField ?: return
                val captureArgs = param.args.firstOrNull { it != null && captureArgsClass?.isInstance(it) == true }
                    ?: param.args.getOrNull(0) ?: return
                try {
                    if (field.type == Int::class.javaPrimitiveType) {
                        field.set(captureArgs, 1)
                    } else {
                        field.set(captureArgs, true)
                    }
                } catch (_: Throwable) {}
            }
        }

        for (methodName in listOf("nativeCaptureDisplay", "nativeCaptureLayers", "captureDisplay", "captureLayers")) {
            try {
                XposedBridge.hookAllMethods(screenCaptureClass, methodName, hooker)
            } catch (_: Throwable) {}
        }
        log("Hooked ScreenCapture methods on ${screenCaptureClass.name}")
    }

    /**
     * ScreenshotHardwareBuffer.containsSecureLayers():
     * Always return false so consumers (like SystemUI) don't discard or black out the screenshot buffer.
     */
    private fun hookScreenshotHardwareBuffer(classLoader: ClassLoader, prefs: Prefs) {
        val bufferClasses = listOf(
            "android.window.ScreenCapture\$ScreenshotHardwareBuffer",
            "android.view.SurfaceControl\$ScreenshotHardwareBuffer"
        )
        val bufferClass = bufferClasses.firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name, classLoader)
        } ?: return

        try {
            XposedHelpers.findAndHookMethod(
                bufferClass,
                "containsSecureLayers",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) {
                            param.result = false
                        }
                    }
                }
            )
            log("Hooked ScreenshotHardwareBuffer.containsSecureLayers")
        } catch (t: Throwable) {
            log("Failed to hook containsSecureLayers: ${t.message}")
        }
    }

    /**
     * Screenshot detection avoidance (Android 14+):
     * Neutralize ActivityTaskManagerService.registerScreenCaptureObserver so apps cannot detect when a screenshot is taken.
     */
    private fun hookActivityTaskManagerService(classLoader: ClassLoader, prefs: Prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val atmsClass = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityTaskManagerService", classLoader) ?: return
        try {
            XposedBridge.hookAllMethods(atmsClass, "registerScreenCaptureObserver", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) {
                        param.result = null
                    }
                }
            })
            log("Hooked ActivityTaskManagerService.registerScreenCaptureObserver")
        } catch (t: Throwable) {
            log("Failed to hook registerScreenCaptureObserver: ${t.message}")
        }
    }

    /**
     * Screen recording detection avoidance (Android 15+):
     * Neutralize WindowManagerService.registerScreenRecordingCallback so apps cannot detect active screen recording.
     */
    private fun hookWindowManagerService(classLoader: ClassLoader, prefs: Prefs) {
        if (Build.VERSION.SDK_INT < 35 /* Build.VERSION_CODES.VANILLA_ICE_CREAM */) return

        val wmsClass = XposedHelpers.findClassIfExists("com.android.server.wm.WindowManagerService", classLoader) ?: return
        try {
            XposedBridge.hookAllMethods(wmsClass, "registerScreenRecordingCallback", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) {
                        param.result = false
                    }
                }
            })
            log("Hooked WindowManagerService.registerScreenRecordingCallback")
        } catch (t: Throwable) {
            log("Failed to hook registerScreenRecordingCallback: ${t.message}")
        }
    }

    /**
     * DisplayControl (Android 14+):
     * Force virtual display creation to have isSecure = true so casting, scrcpy, and screen recording see secure content.
     */
    private fun hookDisplayControl(classLoader: ClassLoader, prefs: Prefs) {
        val displayControlClasses = listOf(
            "com.android.server.display.DisplayControl",
            "android.view.SurfaceControl"
        )
        val displayControlClass = displayControlClasses.firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name, classLoader)
        } ?: return

        val hooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) return
                if (param.args.size >= 2 && param.args[1] is Boolean) {
                    param.args[1] = true
                }
            }
        }

        for (methodName in listOf("createVirtualDisplay", "createDisplay")) {
            try {
                XposedBridge.hookAllMethods(displayControlClass, methodName, hooker)
            } catch (_: Throwable) {}
        }
        log("Hooked DisplayControl virtual display creation")
    }

    /**
     * VirtualDisplayAdapter:
     * Add VIRTUAL_DISPLAY_FLAG_SECURE to flags for OS or MediaProjection callers so screen recorder can capture secure screens.
     */
    private fun hookVirtualDisplayAdapter(classLoader: ClassLoader, prefs: Prefs) {
        val adapterClass = XposedHelpers.findClassIfExists("com.android.server.display.VirtualDisplayAdapter", classLoader) ?: return
        try {
            XposedBridge.hookAllMethods(adapterClass, "createVirtualDisplayLocked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(PREF_DISABLE_FLAG_SECURE, false)) return
                    val caller = param.args.getOrNull(2) as? Int ?: 0
                    val projection = param.args.getOrNull(1)
                    if (caller >= 10000 && projection == null) {
                        return
                    }
                    for (i in 3 until param.args.size) {
                        val flags = param.args[i] as? Int
                        if (flags != null) {
                            param.args[i] = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                            break
                        }
                    }
                }
            })
            log("Hooked VirtualDisplayAdapter.createVirtualDisplayLocked")
        } catch (t: Throwable) {
            log("Failed to hook VirtualDisplayAdapter: ${t.message}")
        }
    }
}
