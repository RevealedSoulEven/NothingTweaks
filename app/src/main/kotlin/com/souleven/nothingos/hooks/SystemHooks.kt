package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SystemHooks {
    private const val KEY_ALLOW_180_ROTATION = "allow_180_rotation"
    private const val KEY_DISABLE_SAFE_VOLUME = "pref_disable_safe_volume"
    private const val KEY_VOLUME_BOTH_FOR_TORCH = "pref_volume_both_for_torch"
    private const val ACTION_TOGGLE_TORCH = "com.souleven.nothingos.action.TOGGLE_TORCH"
    private const val CHORD_WINDOW_MS = 500L

    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var volUpPressTime = 0L
    private var volDownPressTime = 0L
    private var torchHandledThisChord = false

    fun init(classLoader: ClassLoader, prefs: Prefs) {
        val displayRotationClass = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayRotation", classLoader)
        if (displayRotationClass != null) {
            try {
                XposedBridge.hookAllMethods(displayRotationClass, "getAllowAllRotations", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean(KEY_ALLOW_180_ROTATION, false)) {
                            param.result = 1 // ALLOW_ALL_ROTATIONS_ENABLED
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        hookSafeMediaVolume(classLoader, prefs)
        hookVolumeBothForTorch(classLoader, prefs)
    }

    private fun hookVolumeBothForTorch(classLoader: ClassLoader, prefs: Prefs) {
        val pwmClass = XposedHelpers.findClassIfExists("com.android.server.policy.PhoneWindowManager", classLoader) ?: return

        try {
            XposedBridge.hookAllMethods(pwmClass, "interceptKeyBeforeQueueing", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return
                    val keyCode = event.keyCode
                    if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                        return
                    }

                    if (!prefs.getBoolean(KEY_VOLUME_BOTH_FOR_TORCH, false)) {
                        return
                    }

                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                    val pm = context?.getSystemService(PowerManager::class.java)
                    val isScreenOn = try {
                        XposedHelpers.callMethod(param.thisObject, "isScreenOn") as? Boolean ?: false
                    } catch (_: Throwable) {
                        pm?.isInteractive ?: false
                    }

                    if (isScreenOn || pm?.isInteractive == true) {
                        isVolUpPressed = false
                        isVolDownPressed = false
                        torchHandledThisChord = false
                        return
                    }

                    val action = event.action
                    val now = SystemClock.uptimeMillis()

                    if (action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            isVolUpPressed = true
                            volUpPressTime = now
                        } else {
                            isVolDownPressed = true
                            volDownPressTime = now
                        }

                        if (torchHandledThisChord) {
                            param.result = 0
                            return
                        }

                        val timeDiff = kotlin.math.abs(volUpPressTime - volDownPressTime)
                        val bothDown = isVolUpPressed && isVolDownPressed
                        val chordWindow = timeDiff < CHORD_WINDOW_MS

                        if (bothDown && chordWindow && volUpPressTime > 0L && volDownPressTime > 0L) {
                            torchHandledThisChord = true
                            volUpPressTime = 0L
                            volDownPressTime = 0L
                            if (context != null) {
                                sendToggleTorchBroadcast(context, classLoader)
                            }
                            param.result = 0
                        }
                    } else if (action == KeyEvent.ACTION_UP) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            isVolUpPressed = false
                        } else {
                            isVolDownPressed = false
                        }

                        if (torchHandledThisChord) {
                            param.result = 0
                            if (!isVolUpPressed && !isVolDownPressed) {
                                torchHandledThisChord = false
                                volUpPressTime = 0L
                                volDownPressTime = 0L
                            }
                        }
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    private fun sendToggleTorchBroadcast(context: Context, classLoader: ClassLoader) {
        try {
            val intent = Intent(ACTION_TOGGLE_TORCH)
            intent.setPackage("com.android.systemui")
            val userHandleClass = XposedHelpers.findClassIfExists("android.os.UserHandle", classLoader)
            val allUser = if (userHandleClass != null) {
                XposedHelpers.getStaticObjectField(userHandleClass, "ALL")
            } else null

            if (allUser != null) {
                XposedHelpers.callMethod(context, "sendBroadcastAsUser", intent, allUser)
            } else {
                context.sendBroadcast(intent)
            }
        } catch (_: Throwable) {}
    }

    private fun hookSafeMediaVolume(classLoader: ClassLoader, prefs: Prefs) {
        val soundDoseHelperClass = XposedHelpers.findClassIfExists("com.android.server.audio.SoundDoseHelper", classLoader) ?: return

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "safeDevicesContains", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "checkSafeMediaVolume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "raiseVolumeDisplaySafeMediaVolume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = false
                    }
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(soundDoseHelperClass, "enforceSafeMediaVolumeIfActive", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(KEY_DISABLE_SAFE_VOLUME, false)) {
                        param.result = null
                    }
                }
            })
        } catch (_: Throwable) {}
    }
}
