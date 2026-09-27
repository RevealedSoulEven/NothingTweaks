package com.souleven.nothingos.hooks

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

class StatusBarBrightnessHooks : HookModule {

    companion object {
        const val PREF_KEY = "pref_status_bar_brightness_slider"
    }

    private var initialX = 0f
    private var initialY = 0f
    private var initialBrightness = 0.5f
    private var currentBrightness = 0.5f
    private var minBrightness = 0.0f
    private var maxBrightness = 1.0f
    private var isSliding = false
    private var longPressCancelled = false
    private var lastSlideEndTime = 0L
    private var displayId = 0
    private val hookedListenerClasses = Collections.synchronizedSet(mutableSetOf<Class<*>>())

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val statusBarViewClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            lpparam.classLoader
        ) ?: return

        try {
            XposedHelpers.findAndHookMethod(
                statusBarViewClass,
                "onInterceptTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(PREF_KEY, false)) return
                        val ev = param.args[0] as? MotionEvent ?: return
                        val view = param.thisObject as? View ?: return

                        val km = view.context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        if (km?.isKeyguardLocked == true) return

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = ev.rawX
                                initialY = ev.rawY
                                isSliding = false
                                longPressCancelled = false
                                initBrightness(view)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = ev.rawX - initialX
                                val dy = ev.rawY - initialY
                                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                                if (!isSliding && abs(dx) > slop && abs(dx) > abs(dy)) {
                                    isSliding = true
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                    if (!longPressCancelled) {
                                        longPressCancelled = true
                                        cancelStatusBarTouches(view, ev)
                                    }
                                }
                                if (isSliding) {
                                    param.result = true
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (isSliding) {
                                    applyFinalBrightness(view, currentBrightness)
                                    isSliding = false
                                    lastSlideEndTime = SystemClock.uptimeMillis()
                                    param.result = true
                                }
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [StatusBarBrightness] FAILED to hook onInterceptTouchEvent: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                statusBarViewClass,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(PREF_KEY, false)) return
                        val ev = param.args[0] as? MotionEvent ?: return
                        val view = param.thisObject as? View ?: return

                        val km = view.context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        if (km?.isKeyguardLocked == true) return

                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = ev.rawX
                                initialY = ev.rawY
                                isSliding = false
                                longPressCancelled = false
                                initBrightness(view)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = ev.rawX - initialX
                                val dy = ev.rawY - initialY
                                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                                if (!isSliding && abs(dx) > slop && abs(dx) > abs(dy)) {
                                    isSliding = true
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                    if (!longPressCancelled) {
                                        longPressCancelled = true
                                        cancelStatusBarTouches(view, ev)
                                    }
                                }
                                if (isSliding) {
                                    val width = view.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                                    val delta = dx / width
                                    val newBrightness = (initialBrightness + delta).coerceIn(minBrightness, maxBrightness)
                                    currentBrightness = newBrightness
                                    applyTemporaryBrightness(view, newBrightness)
                                    param.result = true
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (isSliding) {
                                    applyFinalBrightness(view, currentBrightness)
                                    isSliding = false
                                    lastSlideEndTime = SystemClock.uptimeMillis()
                                    param.result = true
                                }
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [StatusBarBrightness] FAILED to hook onTouchEvent: ${t.message}")
        }

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
                            if (!prefs.getBoolean(PREF_KEY, false)) return
                            val ev = param.args[0] as? MotionEvent ?: return
                            if (ev.actionMasked != MotionEvent.ACTION_CANCEL && (isSliding || SystemClock.uptimeMillis() - lastSlideEndTime < 300L)) {
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
                            if (!prefs.getBoolean(PREF_KEY, false)) return
                            if (isSliding || SystemClock.uptimeMillis() - lastSlideEndTime < 300L) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [StatusBarBrightness] FAILED to hook npvc onStatusBarLongPress: ${t.message}")
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
                        if (!prefs.getBoolean(PREF_KEY, false)) return
                        if (isSliding || SystemClock.uptimeMillis() - lastSlideEndTime < 300L) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun cancelStatusBarTouches(view: View, ev: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(
            ev.downTime,
            ev.eventTime,
            MotionEvent.ACTION_CANCEL,
            ev.x,
            ev.y,
            0
        )
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

        cancelEvent.recycle()
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
