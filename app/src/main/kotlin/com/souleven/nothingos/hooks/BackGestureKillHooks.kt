package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent

import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG

class BackGestureKillHooks : HookModule {

    companion object {
        private val PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.nothing.launcher",
            "android"
        )
        private const val LONG_PRESS_TIMEOUT_MS = 300
    }

    private var isCallbackHooked = false

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.android.systemui") return

        try {
            val handlerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                lpparam.classLoader
            )

            if (handlerClass == null) {
                XposedBridge.log("$TAG BackGestureKillHooks: EdgeBackGestureHandler not found")
                return
            }

            XposedBridge.hookAllConstructors(
                handlerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean("pref_back_gesture_kill", false)) return
                        try {
                            XposedHelpers.setIntField(param.thisObject, "mLongPressTimeout", Int.MAX_VALUE)
                        } catch (e: Exception) {
                            XposedBridge.log("$TAG BackGestureKillHooks: Failed to set mLongPressTimeout: ${e.message}")
                        }
                    }
                }
            )

            val gestureState = java.util.WeakHashMap<Any, GestureState>()

            XposedHelpers.findAndHookMethod(
                handlerClass,
                "onMotionEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean("pref_back_gesture_kill", false)) return
                        val handler = param.thisObject
                        val event = param.args[0] as MotionEvent
                        val action = event.actionMasked

                        val state = gestureState.getOrPut(handler) { GestureState() }

                        if (!isCallbackHooked) {
                            val callback = try {
                                XposedHelpers.getObjectField(handler, "mBackCallback")
                            } catch (e: Exception) { null }
                            
                            if (callback != null) {
                                isCallbackHooked = true
                                XposedBridge.hookAllMethods(
                                    callback.javaClass,
                                    "triggerBack",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(callbackParam: MethodHookParam) {
                                            val h = try {
                                                XposedHelpers.getObjectField(callbackParam.thisObject, "this\$0")
                                            } catch (e: Exception) { null }
                                            
                                            if (h != null) {
                                                val s = gestureState[h]
                                                if (s != null && s.longPressTriggered) {
                                                    val packageName = try {
                                                        XposedHelpers.getObjectField(h, "mPackageName") as? String
                                                    } catch (e: Exception) { null }

                                                    if (packageName != null && packageName !in PROTECTED_PACKAGES) {
                                                        try {
                                                            val context = XposedHelpers.getObjectField(h, "mContext") as Context
                                                            val intent = Intent("com.souleven.nothingos.FORCE_STOP_APP")
                                                            intent.setPackage("android")
                                                            intent.putExtra("package_name", packageName)
                                                            context.sendBroadcast(intent)
                                                        } catch (e: Exception) {
                                                            XposedBridge.log("$TAG BackGestureKill: Failed to send kill broadcast: ${e.message}")
                                                        }
                                                    }

                                                    callbackParam.result = null
                                                    s.longPressTriggered = false
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        when (action) {
                            MotionEvent.ACTION_DOWN -> {
                                state.longPressTriggered = false
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val allowGesture = try {
                                    XposedHelpers.getBooleanField(handler, "mAllowGesture")
                                } catch (e: Exception) { false }

                                if (allowGesture) {
                                    val elapsed = event.eventTime - event.downTime
                                    if (elapsed > LONG_PRESS_TIMEOUT_MS && !state.longPressTriggered) {
                                        state.longPressTriggered = true
                                    }
                                }
                            }
                        }
                    }
                }
            )

        } catch (t: Throwable) {
            XposedBridge.log("$TAG BackGestureKillHooks: Failed to hook: ${t.message}")
            XposedBridge.log(t)
        }
    }

    private class GestureState {
        var longPressTriggered = false
    }
}
