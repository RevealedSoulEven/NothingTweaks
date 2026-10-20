package com.souleven.nothingos.hooks

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class StatusBarGestureHooks : HookModule {

    companion object {
        const val PREF_KEY = "pref_status_bar_brightness_slider"
        const val PREF_DT2S_KEY = "pref_status_bar_double_tap_to_sleep"
    }

    private var initialX = 0f
    private var initialY = 0f
    private var downTime = 0L
    private var firstTapUpTime = 0L
    private var firstTapUpX = 0f
    private var firstTapUpY = 0f
    private var isDoubleTapSleeping = false
    private var initialBrightness = 0.5f
    private var currentBrightness = 0.5f
    private var minBrightness = 0.0f
    private var maxBrightness = 1.0f
    private var isSliding = false
    private var isInStatusBar = false
    private var isDispatchingCancel = false
    private var lastSlideEndTime = 0L
    private var displayId = 0
    private val hookedListenerClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        // Lockscreen root window touch interception
        val nswvClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shade.NotificationShadeWindowView",
            lpparam.classLoader
        ) ?: XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
            lpparam.classLoader
        )
        if (nswvClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    nswvClass,
                    "dispatchTouchEvent",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isDispatchingCancel) return
                            if (isDoubleTapSleeping) {
                                val ev = param.args[0] as? MotionEvent
                                if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) {
                                    isDoubleTapSleeping = false
                                }
                                param.result = true
                                return
                            }
                            val ev = param.args[0] as? MotionEvent ?: return
                            val view = param.thisObject as? View ?: return
                            if (handleTouch(view, ev, checkBounds = true, prefs = prefs)) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [StatusBarGesture] FAILED to hook NotificationShadeWindowView: ${t.message}")
            }
        }

        // Normal screen (unlocked status bar) touch interception
        val statusBarViewClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            lpparam.classLoader
        )
        if (statusBarViewClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    statusBarViewClass,
                    "onInterceptTouchEvent",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isDispatchingCancel) return
                            if (isDoubleTapSleeping) {
                                val ev = param.args[0] as? MotionEvent
                                if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) {
                                    isDoubleTapSleeping = false
                                }
                                param.result = true
                                return
                            }
                            if (isSliding) {
                                param.result = true
                                return
                            }
                            val ev = param.args[0] as? MotionEvent ?: return
                            val view = param.thisObject as? View ?: return
                            if (handleTouch(view, ev, checkBounds = false, prefs = prefs)) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [StatusBarGesture] FAILED to hook onInterceptTouchEvent: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    statusBarViewClass,
                    "onTouchEvent",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isDispatchingCancel) return
                            if (isDoubleTapSleeping) {
                                val ev = param.args[0] as? MotionEvent
                                if (ev?.actionMasked == MotionEvent.ACTION_UP || ev?.actionMasked == MotionEvent.ACTION_CANCEL) {
                                    isDoubleTapSleeping = false
                                }
                                param.result = true
                                return
                            }
                            val ev = param.args[0] as? MotionEvent ?: return
                            val view = param.thisObject as? View ?: return
                            if (handleTouch(view, ev, checkBounds = false, prefs = prefs)) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [StatusBarGesture] FAILED to hook onTouchEvent: ${t.message}")
            }
        }

        // Suppress long press while or right after sliding/double-tapping
        val detectorClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shade.StatusBarLongPressGestureDetector",
            lpparam.classLoader
        )
        if (detectorClass != null) {
            try {
                for (inner in detectorClass.declaredClasses) {
                    if (GestureDetector.OnGestureListener::class.java.isAssignableFrom(inner)) {
                        hookOnLongPress(inner, prefs)
                    }
                }
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllConstructors(detectorClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val gd = XposedHelpers.getObjectField(param.thisObject, "gestureDetector") ?: return
                            val listener = XposedHelpers.getObjectField(gd, "mListener") ?: return
                            hookOnLongPress(listener.javaClass, prefs)
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    detectorClass,
                    "handleTouch",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean(PREF_KEY, false) && !prefs.getBoolean(PREF_DT2S_KEY, false)) return
                            val ev = param.args[0] as? MotionEvent ?: return
                            if (ev.actionMasked != MotionEvent.ACTION_CANCEL && (isSliding || isDoubleTapSleeping || SystemClock.uptimeMillis() - lastSlideEndTime < 500L)) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val npvcClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shade.NotificationPanelViewController",
            lpparam.classLoader
        )
        if (npvcClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    npvcClass,
                    "onStatusBarLongPress",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean(PREF_KEY, false) && !prefs.getBoolean(PREF_DT2S_KEY, false)) return
                            if (isSliding || isDoubleTapSleeping || SystemClock.uptimeMillis() - lastSlideEndTime < 500L) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [StatusBarGesture] FAILED to hook npvc onStatusBarLongPress: ${t.message}")
            }
        }
    }

    private fun hookOnLongPress(clazz: Class<*>, prefs: Prefs) {
        if (!hookedListenerClasses.add(clazz)) return
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onLongPress",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(PREF_KEY, false) && !prefs.getBoolean(PREF_DT2S_KEY, false)) return
                        if (isSliding || isDoubleTapSleeping || SystemClock.uptimeMillis() - lastSlideEndTime < 500L) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive != false
    }

    private fun handleTouch(view: View, ev: MotionEvent, checkBounds: Boolean, prefs: Prefs): Boolean {
        val brightnessEnabled = prefs.getBoolean(PREF_KEY, false)
        val dt2sEnabled = prefs.getBoolean(PREF_DT2S_KEY, false)
        if (!brightnessEnabled && !dt2sEnabled) return false

        if (!isInteractive(view.context)) {
            isInStatusBar = false
            firstTapUpTime = 0L
            isDoubleTapSleeping = false
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isDoubleTapSleeping) {
                    isDoubleTapSleeping = false
                }
                val inBounds = if (checkBounds) {
                    val sbHeight = getStatusBarHeight(view.context)
                    ev.rawY <= (sbHeight * 1.35f)
                } else {
                    true
                }
                if (inBounds) {
                    val now = SystemClock.uptimeMillis()
                    val timeSinceFirst = now - firstTapUpTime
                    val doubleTapSlop = try {
                        ViewConfiguration.get(view.context).scaledDoubleTapSlop
                    } catch (_: Throwable) {
                        (100 * view.resources.displayMetrics.density).toInt()
                    }
                    val dist = hypot(ev.rawX - firstTapUpX, ev.rawY - firstTapUpY)

                    // Check for double tap on the second ACTION_DOWN
                    if (dt2sEnabled && firstTapUpTime > 0L && timeSinceFirst in 40L..400L && dist <= doubleTapSlop) {
                        firstTapUpTime = 0L
                        isDoubleTapSleeping = true
                        lastSlideEndTime = now
                        collapseAndCancelShade(view, ev)
                        goToSleep(view.context)
                        return true
                    }

                    val isSameDown = isInStatusBar && !isSliding && initialX == ev.rawX && initialY == ev.rawY
                    isInStatusBar = true
                    initialX = ev.rawX
                    initialY = ev.rawY
                    downTime = now
                    isSliding = false
                    if (!isSameDown && brightnessEnabled) {
                        initBrightness(view)
                    }
                } else {
                    isInStatusBar = false
                    firstTapUpTime = 0L
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDoubleTapSleeping) return true
                if (!isInStatusBar) return false
                val dx = ev.rawX - initialX
                val dy = ev.rawY - initialY
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop

                if (!isSliding) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        // Vertical pulldown (opening notification shade / quick settings)
                        isInStatusBar = false
                        firstTapUpTime = 0L
                        return false
                    } else if (brightnessEnabled && abs(dx) > slop && abs(dx) > abs(dy)) {
                        // Horizontal slide to adjust screen brightness
                        isSliding = true
                        firstTapUpTime = 0L
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        cancelTouches(view, ev)
                    } else if (abs(dx) > slop || abs(dy) > slop) {
                        firstTapUpTime = 0L
                    }
                }

                if (isSliding) {
                    val width = view.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                    val delta = dx / width
                    val newBrightness = (initialBrightness + delta).coerceIn(minBrightness, maxBrightness)
                    currentBrightness = newBrightness
                    applyTemporaryBrightness(view, newBrightness)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isInStatusBar = false
                if (isDoubleTapSleeping) {
                    isDoubleTapSleeping = false
                    return true
                }
                if (isSliding) {
                    isSliding = false
                    firstTapUpTime = 0L
                    applyFinalBrightness(view, currentBrightness)
                    lastSlideEndTime = SystemClock.uptimeMillis()
                    return true
                }

                if (ev.actionMasked == MotionEvent.ACTION_UP && dt2sEnabled) {
                    val upTime = SystemClock.uptimeMillis()
                    val tapDuration = upTime - downTime
                    val dx = abs(ev.rawX - initialX)
                    val dy = abs(ev.rawY - initialY)
                    val slop = ViewConfiguration.get(view.context).scaledTouchSlop

                    if (tapDuration <= 300L && dx <= slop && dy <= slop) {
                        firstTapUpTime = upTime
                        firstTapUpX = ev.rawX
                        firstTapUpY = ev.rawY
                    } else {
                        firstTapUpTime = 0L
                    }
                } else {
                    firstTapUpTime = 0L
                }
            }
        }
        return false
    }

    private fun goToSleep(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val now = SystemClock.uptimeMillis()
            try {
                // Reason 4 = GO_TO_SLEEP_REASON_POWER_BUTTON
                XposedHelpers.callMethod(pm, "goToSleep", now, 4, 0)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(pm, "goToSleep", now)
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [StatusBarGesture] Failed to goToSleep: ${t.message}")
        }
    }

    private fun collapseAndCancelShade(view: View, ev: MotionEvent) {
        cancelTouches(view, ev)
        try {
            val handler = try { XposedHelpers.getObjectField(view, "mTouchEventHandler") } catch (_: Throwable) { null }
            if (handler != null) {
                val controller = try { XposedHelpers.getObjectField(handler, "this$0") } catch (_: Throwable) { null }
                if (controller != null) {
                    val shadeController = try { XposedHelpers.getObjectField(controller, "shadeController") } catch (_: Throwable) { null }
                    if (shadeController != null) {
                        try {
                            XposedHelpers.callMethod(shadeController, "cancelExpansionAndCollapseShade")
                        } catch (_: Throwable) {
                            try {
                                XposedHelpers.callMethod(shadeController, "instantCollapseShade")
                            } catch (_: Throwable) {}
                        }
                    }
                    val centralSurfaces = try { XposedHelpers.getObjectField(controller, "centralSurfaces") } catch (_: Throwable) { null }
                    if (centralSurfaces != null) {
                        try {
                            XposedHelpers.callMethod(centralSurfaces, "setInteracting", 1, false)
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun getStatusBarHeight(context: Context): Int {
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val resName = if (isLandscape) "status_bar_height_landscape" else "status_bar_height"
        var resId = context.resources.getIdentifier(resName, "dimen", "android")
        if (resId == 0) {
            resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        }
        val height = if (resId > 0) {
            try { context.resources.getDimensionPixelSize(resId) } catch (_: Throwable) { 0 }
        } else 0
        return if (height > 0) height else (32 * context.resources.displayMetrics.density).toInt()
    }

    private fun cancelTouches(view: View, ev: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(
            ev.downTime,
            ev.eventTime,
            MotionEvent.ACTION_CANCEL,
            ev.x,
            ev.y,
            0
        )
        isDispatchingCancel = true
        try {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.dispatchTouchEvent(cancelEvent)
                }
            }
            try {
                val detector = XposedHelpers.getObjectField(view, "mStatusBarLongPressGestureDetector")
                if (detector != null) {
                    XposedHelpers.callMethod(detector, "handleTouch", cancelEvent)
                }
            } catch (_: Throwable) {}

            try {
                val handler = XposedHelpers.getObjectField(view, "mTouchEventHandler")
                if (handler != null) {
                    XposedHelpers.callMethod(handler, "onTouchEvent", cancelEvent)
                }
            } catch (_: Throwable) {}
        } finally {
            isDispatchingCancel = false
            cancelEvent.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun initBrightness(view: View) {
        try {
            val context = view.context
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.display ?: context.display
            } else {
                null
            } ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay

            displayId = display?.displayId ?: Display.DEFAULT_DISPLAY

            val info = try {
                if (display != null) XposedHelpers.callMethod(display, "getBrightnessInfo") else null
            } catch (_: Throwable) {
                null
            }

            if (info != null) {
                currentBrightness = XposedHelpers.getFloatField(info, "brightness")
                minBrightness = XposedHelpers.getFloatField(info, "brightnessMinimum")
                maxBrightness = XposedHelpers.getFloatField(info, "brightnessMaximum")
            } else {
                val intVal = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                currentBrightness = (intVal / 255.0f).coerceIn(0.0f, 1.0f)
                minBrightness = 0.01f
                maxBrightness = 1.0f
            }
            initialBrightness = currentBrightness
        } catch (_: Throwable) {
            currentBrightness = 0.5f
            initialBrightness = 0.5f
            minBrightness = 0.01f
            maxBrightness = 1.0f
        }
    }

    private fun applyTemporaryBrightness(view: View, brightness: Float) {
        try {
            val context = view.context
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            XposedHelpers.callMethod(displayManager, "setTemporaryBrightness", displayId, brightness)
        } catch (_: Throwable) {
            try {
                val intVal = (brightness * 255f).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(view.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, intVal)
            } catch (_: Throwable) {}
        }
    }

    private fun applyFinalBrightness(view: View, brightness: Float) {
        try {
            val context = view.context
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (displayManager != null) {
                XposedHelpers.callMethod(displayManager, "setBrightness", displayId, brightness)
            }
            val intVal = (brightness * 255f).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, intVal)
        } catch (_: Throwable) {}
    }
}
