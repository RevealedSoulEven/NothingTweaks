package com.souleven.nothingos.hooks

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class TorchHooks : HookModule {

    companion object {
        const val PREF_KEY = "pref_volume_both_for_torch"
        const val ACTION_TOGGLE_TORCH = "com.souleven.nothingos.action.TOGGLE_TORCH"
        private const val CHORD_WINDOW_MS = 500L
        private const val DEBOUNCE_MS = 1200L

        @Volatile
        private var sFlashlightController: Any? = null

        @Volatile
        private var sContext: Context? = null

        @Volatile
        private var receiverRegistered = false

        private var volUpPressTime = 0L
        private var volDownPressTime = 0L
        private var lastToggleTime = 0L

        private var fallbackTorchOn = false
        private var fallbackCameraId: String? = null
        private var fallbackTorchCallbackRegistered = false
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        hookSystemUIApplication(lpparam, prefs)
        hookFlashlightController(lpparam, prefs)
        hookFlashlightTile(lpparam)
        hookCommandQueue(lpparam, prefs)
    }

    private fun hookSystemUIApplication(lpparam: LoadPackageParam, prefs: Prefs) {
        val appClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.SystemUIApplication",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    sContext = app
                    registerReceiver(app, prefs)
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookFlashlightController(lpparam: LoadPackageParam, prefs: Prefs) {
        val fcClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.policy.FlashlightControllerImpl",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllConstructors(fcClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    sFlashlightController = param.thisObject
                    val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    if (ctx != null) {
                        sContext = ctx
                        registerReceiver(ctx, prefs)
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookFlashlightTile(lpparam: LoadPackageParam) {
        val tileClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.qs.tiles.FlashlightTile",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllConstructors(tileClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val fc = XposedHelpers.getObjectField(param.thisObject, "mFlashlightController")
                        if (fc != null) {
                            sFlashlightController = fc
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookCommandQueue(lpparam: LoadPackageParam, prefs: Prefs) {
        val cqClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.CommandQueue",
            lpparam.classLoader
        ) ?: return

        try {
            XposedBridge.hookAllMethods(cqClass, "handleSystemKey", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return
                    val keyCode = event.keyCode
                    if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                        return
                    }
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
                        return
                    }

                    prefs.forceReload()
                    if (!prefs.getBoolean(PREF_KEY, false)) {
                        return
                    }

                    val context = (XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context)
                        ?: sContext
                        ?: AndroidAppHelper.currentApplication()
                        ?: return

                    if (sContext == null) {
                        sContext = context
                    }
                    registerReceiver(context, prefs)

                    val pm = context.getSystemService(PowerManager::class.java)
                    if (pm?.isInteractive == true) {
                        volUpPressTime = 0L
                        volDownPressTime = 0L
                        return
                    }

                    val now = SystemClock.uptimeMillis()
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        volUpPressTime = now
                    } else {
                        volDownPressTime = now
                    }

                    val diff = kotlin.math.abs(volUpPressTime - volDownPressTime)
                    val isChord = (volUpPressTime > 0L && volDownPressTime > 0L) && (diff < CHORD_WINDOW_MS)

                    if (isChord && (now - lastToggleTime > DEBOUNCE_MS)) {
                        lastToggleTime = now
                        volUpPressTime = 0L
                        volDownPressTime = 0L
                        toggleTorch(context)
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun registerReceiver(context: Context, prefs: Prefs) {
        if (receiverRegistered) return
        synchronized(TorchHooks::class.java) {
            if (receiverRegistered) return
            try {
                val filter = IntentFilter(ACTION_TOGGLE_TORCH)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_TOGGLE_TORCH) return
                        prefs.forceReload()
                        if (!prefs.getBoolean(PREF_KEY, false)) return

                        val targetContext = c ?: sContext ?: AndroidAppHelper.currentApplication() ?: return
                        val now = SystemClock.uptimeMillis()
                        if (now - lastToggleTime > DEBOUNCE_MS) {
                            lastToggleTime = now
                            volUpPressTime = 0L
                            volDownPressTime = 0L
                            toggleTorch(targetContext)
                        }
                    }
                }

                val appCtx = context.applicationContext ?: context
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun toggleTorch(context: Context) {
        try {
            val fc = sFlashlightController
            if (fc != null) {
                val isEnabled = XposedHelpers.callMethod(fc, "isEnabled") as? Boolean ?: false
                XposedHelpers.callMethod(fc, "setFlashlight", !isEnabled)
                vibrate(context)
                return
            }
        } catch (_: Throwable) {}

        try {
            val cm = context.getSystemService(CameraManager::class.java) ?: return
            ensureFallbackTorchCallback(cm)
            val cameraId = getCameraId(cm) ?: return
            val newMode = !fallbackTorchOn
            cm.setTorchMode(cameraId, newMode)
            fallbackTorchOn = newMode
            vibrate(context)
        } catch (_: Throwable) {}
    }

    private fun ensureFallbackTorchCallback(cameraManager: CameraManager) {
        if (fallbackTorchCallbackRegistered) return
        try {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (fallbackCameraId == null || cameraId == fallbackCameraId) {
                        fallbackCameraId = cameraId
                        fallbackTorchOn = enabled
                    }
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == fallbackCameraId) {
                        fallbackTorchOn = false
                    }
                }
            }, null)
            fallbackTorchCallbackRegistered = true
        } catch (_: Throwable) {}
    }

    private fun getCameraId(cameraManager: CameraManager): String? {
        fallbackCameraId?.let { return it }
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    fallbackCameraId = id
                    return id
                }
            }
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    fallbackCameraId = id
                    return id
                }
            }
            if (cameraManager.cameraIdList.isNotEmpty()) {
                fallbackCameraId = cameraManager.cameraIdList[0]
                return fallbackCameraId
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (_: Throwable) {}
    }
}
