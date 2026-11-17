package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import java.util.WeakHashMap

class BackGestureKillHooks : HookModule {

    companion object {
        private val PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.nothing.launcher",
            "android"
        )

        private const val GESTURE_HOLD_TIMEOUT_MS = 300L
        private const val BUTTON_HOLD_TIMEOUT_MS = 400L
        private const val DEBOUNCE_MS = 600L

        private var lastKillTime = 0L

        @Volatile
        private var lastKnownForegroundPackage: String? = null

        /**
         * sends a kill broadcast to system_server. If [candidatePackage] is null,
         * system_server resolves the focused package via ActivityTaskManager
         */
        fun killForegroundApp(context: Context, candidatePackage: String? = null): String? {
            val now = SystemClock.uptimeMillis()
            if (now - lastKillTime < DEBOUNCE_MS) return null
            lastKillTime = now

            val pkg = when {
                !candidatePackage.isNullOrEmpty() && candidatePackage != "_UNKNOWN" -> candidatePackage
                !lastKnownForegroundPackage.isNullOrEmpty() && lastKnownForegroundPackage != "_UNKNOWN" -> lastKnownForegroundPackage
                else -> null
            }

            if (pkg != null && pkg in PROTECTED_PACKAGES) {
                XposedBridge.log("$TAG [BackGestureKill] Refusing to kill protected package: $pkg")
                return null
            }

            try {
                val intent = Intent("com.souleven.nothingos.FORCE_STOP_APP").apply {
                    setPackage("android")
                    if (!pkg.isNullOrEmpty() && pkg != "_UNKNOWN") {
                        putExtra("package_name", pkg)
                    }
                }
                context.sendBroadcast(intent)
                vibrateKill(context)
                return pkg
            } catch (e: Exception) {
                XposedBridge.log("$TAG [BackGestureKill] Failed to send kill broadcast: ${e.message}")
                return null
            }
        }

        fun vibrateKill(context: Context) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                } ?: return

                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(50L)
                    }
                }
            } catch (_: Throwable) {}
        }

        // clears pressed visual state and resets ripples to prevent the white button overlay freeze
        fun resetViewVisualState(view: View) {
            try {
                view.isPressed = false
                view.isHovered = false
                view.isSelected = false
                view.jumpDrawablesToCurrentState()

                val bg = view.background
                if (bg != null) {
                    bg.state = android.util.StateSet.NOTHING
                    bg.jumpToCurrentState()
                    try {
                        val abortMethod = bg.javaClass.getMethod("abortDelayedRipple")
                        abortMethod.invoke(bg)
                    } catch (_: Throwable) {}
                }
                view.invalidate()
            } catch (_: Throwable) {}
        }
    }

    private var isCallbackHooked = false
    private val attachedLauncherButtons = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        when (lpparam.packageName) {
            "com.android.systemui" -> {
                hookGestureNavigation(lpparam, prefs)
            }
            "com.nothing.launcher" -> {
                hookLauncherThreeButton(lpparam, prefs)
            }
        }
    }

    // ================================================================
    // 1. Gesture Navigation (Swipe Back in SystemUI)
    // ================================================================

    private fun hookGestureNavigation(lpparam: LoadPackageParam, prefs: Prefs) {
        try {
            val handlerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllConstructors(
                handlerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean("pref_back_gesture_kill", false)) return
                        try {
                            XposedHelpers.setIntField(param.thisObject, "mLongPressTimeout", Int.MAX_VALUE)
                        } catch (_: Throwable) {}
                    }
                }
            )

            XposedBridge.hookAllMethods(
                handlerClass,
                "updateTopActivityPackageName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkg = try {
                            XposedHelpers.getObjectField(param.thisObject, "mPackageName") as? String
                        } catch (_: Throwable) { null }
                        if (!pkg.isNullOrEmpty() && pkg != "_UNKNOWN") {
                            lastKnownForegroundPackage = pkg
                        }
                    }
                }
            )

            val gestureState = WeakHashMap<Any, GestureState>()

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
                            } catch (_: Throwable) { null }

                            if (callback != null) {
                                isCallbackHooked = true
                                XposedBridge.hookAllMethods(
                                    callback.javaClass,
                                    "triggerBack",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(callbackParam: MethodHookParam) {
                                            val h = try {
                                                XposedHelpers.getObjectField(callbackParam.thisObject, "this\$0")
                                            } catch (_: Throwable) { null }

                                            if (h != null) {
                                                val s = gestureState[h]
                                                if (s != null && s.longPressTriggered) {
                                                    val packageName = try {
                                                        XposedHelpers.getObjectField(h, "mPackageName") as? String
                                                    } catch (_: Throwable) { null }

                                                    if (!packageName.isNullOrEmpty() && packageName != "_UNKNOWN") {
                                                        lastKnownForegroundPackage = packageName
                                                    }

                                                    val context = try {
                                                        XposedHelpers.getObjectField(h, "mContext") as? Context
                                                    } catch (_: Throwable) { null }

                                                    if (context != null) {
                                                        killForegroundApp(context, candidatePackage = packageName)
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
                                } catch (_: Throwable) { false }

                                if (allowGesture) {
                                    val elapsed = event.eventTime - event.downTime
                                    if (elapsed > GESTURE_HOLD_TIMEOUT_MS && !state.longPressTriggered) {
                                        state.longPressTriggered = true
                                    }
                                }
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BackGestureKill] Failed to hook gesture back: ${t.message}")
        }
    }

    // ================================================================
    // 2. 3-Button Navigation (Nothing Launcher Taskbar)
    // ================================================================

    private fun hookLauncherThreeButton(lpparam: LoadPackageParam, prefs: Prefs) {
        val candidateClasses = listOf(
            "com.android.launcher3.taskbar.NavbarButtonsViewController",
            "com.nothing.launcher.taskbar.NavbarButtonsViewController"
        )

        var navbarClass: Class<*>? = null
        for (className in candidateClasses) {
            val cls = XposedHelpers.findClassIfExists(className, lpparam.classLoader)
            if (cls != null) {
                navbarClass = cls
                break
            }
        }

        if (navbarClass == null) {
            XposedBridge.log("$TAG [BackGestureKill] NavbarButtonsViewController not found in Launcher")
            return
        }

        // primary hook: setBackButtonTouchListener - standard Launcher3 method specifically for Back button
        try {
            XposedBridge.hookAllMethods(
                navbarClass,
                "setBackButtonTouchListener",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val backButton = param.args.getOrNull(0) as? View ?: return
                        attachLauncherBackButtonHold(backButton, prefs)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BackGestureKill] setBackButtonTouchListener hook error: ${t.message}")
        }

        // fallback: initButtons - ensures back button is hooked even if setBackButtonTouchListener changes
        try {
            XposedBridge.hookAllMethods(
                navbarClass,
                "initButtons",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val backButton = try {
                            XposedHelpers.getObjectField(param.thisObject, "mBackButton") as? View
                        } catch (_: Throwable) { null } ?: return
                        attachLauncherBackButtonHold(backButton, prefs)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BackGestureKill] initButtons hook error: ${t.message}")
        }
    }

    private fun attachLauncherBackButtonHold(view: View, prefs: Prefs) {
        if (!attachedLauncherButtons.add(view)) return

        // retrieve the stock OnTouchListener installed by Launcher (NavbarButtonsViewController's touch lambda)
        val originalListener = try {
            val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }
            val listenerInfo = getListenerInfo.invoke(view)
            val field = listenerInfo.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }
            field.get(listenerInfo) as? View.OnTouchListener
        } catch (_: Throwable) { null }

        var holdRunnable: Runnable? = null
        var isArmed = false
        var isCancelled = false

        fun isInside(v: View, x: Float, y: Float): Boolean {
            val density = v.resources.displayMetrics.density
            val slop = 50f * density
            return x >= -slop && x <= v.width + slop &&
                   y >= -slop && y <= v.height + slop
        }

        view.setOnTouchListener { v, event ->
            if (!prefs.getBoolean("pref_back_gesture_kill", false)) {
                return@setOnTouchListener originalListener?.onTouch(v, event) ?: false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isArmed = false
                    isCancelled = false
                    holdRunnable?.let { v.removeCallbacks(it) }

                    val runnable = Runnable {
                        if (!prefs.getBoolean("pref_back_gesture_kill", false)) return@Runnable
                        if (isCancelled) return@Runnable
                        isArmed = true
                        try {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (_: Throwable) {}
                    }
                    holdRunnable = runnable
                    v.postDelayed(runnable, BUTTON_HOLD_TIMEOUT_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isInside(v, event.x, event.y)) {
                        if (!isCancelled) {
                            isCancelled = true
                            holdRunnable?.let {
                                v.removeCallbacks(it)
                                holdRunnable = null
                            }
                            if (isArmed) {
                                isArmed = false
                            }
                            resetViewVisualState(v)
                            // notify launcher to cancel pending button press state
                            try {
                                val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                originalListener?.onTouch(v, cancelEvent)
                                cancelEvent.recycle()
                            } catch (_: Throwable) {}
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    holdRunnable?.let {
                        v.removeCallbacks(it)
                        holdRunnable = null
                    }

                    if (isArmed) {
                        isArmed = false
                        killForegroundApp(v.context)

                        // immediately clear the pressed/white overlay state
                        resetViewVisualState(v)

                        // cancel any pending key dispatch in Launcher
                        try {
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            originalListener?.onTouch(v, cancelEvent)
                            v.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        } catch (_: Throwable) {}

                        return@setOnTouchListener true
                    }

                    if (isCancelled) {
                        isCancelled = false
                        resetViewVisualState(v)
                        // cancel stock listener as well so releasing outside doesn't fire a back tap
                        try {
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            originalListener?.onTouch(v, cancelEvent)
                            cancelEvent.recycle()
                        } catch (_: Throwable) {}
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let {
                        v.removeCallbacks(it)
                        holdRunnable = null
                    }
                    isArmed = false
                    isCancelled = false
                    resetViewVisualState(v)
                }
            }

            originalListener?.onTouch(v, event) ?: false
        }

    }

    private class GestureState {
        var longPressTriggered = false
    }
}
