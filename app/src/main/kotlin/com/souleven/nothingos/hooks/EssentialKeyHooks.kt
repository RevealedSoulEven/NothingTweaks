package com.souleven.nothingos.hooks

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Member
import java.util.function.Consumer

class EssentialKeyHooks : HookModule {

    companion object {
        const val ACTION_DEFAULT = "default"
        const val ACTION_NONE = "none"
        const val ACTION_FLASHLIGHT = "flashlight"
        const val ACTION_RINGER = "ringer"
        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_APP = "app"

        val GESTURES = listOf("single", "double", "triple", "long")

        private const val LONG_PRESS_TIMEOUT_MS = 500L
        private const val MULTI_PRESS_TIMEOUT_MS = 280L

        fun keyAction(gesture: String) = "pref_essential_${gesture}_action"
        fun keyApp(gesture: String) = "pref_essential_${gesture}_app"

        fun anyCustomGesture(prefs: Prefs): Boolean {
            return GESTURES.any { prefs.getString(keyAction(it), ACTION_DEFAULT) != ACTION_DEFAULT }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var classLoader: ClassLoader

    // state on the background handler thread
    private val thread = HandlerThread("NothingTweaks-EssentialKey").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var origMethod: Member? = null
    @Volatile private var origTarget: Any? = null
    @Volatile private var cachedContext: Context? = null

    private var pressCount = 0
    private var longPressFired = false
    private var longPressReplayActive = false
    private var lastDownEvent: KeyEvent? = null

    private val longPressRunnable = Runnable { onLongPressTimeout() }
    private val multiPressRunnable = Runnable { onMultiPressTimeout() }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return
        this.prefs = prefs
        this.classLoader = lpparam.classLoader
        hookKeyImpl(lpparam.classLoader)
    }

    private fun hookKeyImpl(cl: ClassLoader) {
        val keyClass = XposedHelpers.findClassIfExists("com.android.server.wm.NtEssentialKeyImpl", cl)
        if (keyClass == null) {
            XposedBridge.log("$TAG [EssentialKey] NtEssentialKeyImpl not found, skipping hook")
            return
        }

        try {
            XposedBridge.hookAllMethods(keyClass, "onRecord", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return

                    if (!anyCustomGesture(prefs)) return

                    origMethod = param.method
                    origTarget = param.thisObject
                    param.result = null // intercept and swallow from stock handler

                    val eventCopy = KeyEvent(event)
                    handler.post { handleKeyEventOnThread(eventCopy) }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Failed to hook onRecord: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- Gesture Handling on Handler Thread

    private fun handleKeyEventOnThread(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    handler.removeCallbacks(multiPressRunnable)
                    lastDownEvent = event

                    if (pressCount == 0) {
                        longPressFired = false
                        longPressReplayActive = false
                        handler.removeCallbacks(longPressRunnable)
                        handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)

                if (longPressFired) {
                    // if long press was set to system default, send the UP event to complete stock voice note
                    if (longPressReplayActive) {
                        replayLongPressUp(event)
                    }
                    resetGestureState()
                    return
                }

                pressCount++
                if (pressCount >= 3) {
                    val proto = lastDownEvent
                    resetGestureState()
                    executeGesture("triple", proto)
                } else {
                    handler.removeCallbacks(multiPressRunnable)
                    handler.postDelayed(multiPressRunnable, MULTI_PRESS_TIMEOUT_MS)
                }
            }
        }
    }

    private fun onLongPressTimeout() {
        if (pressCount == 0 && lastDownEvent != null) {
            longPressFired = true
            val action = prefs.getString(keyAction("long"), ACTION_DEFAULT)
            if (action == ACTION_DEFAULT) {
                longPressReplayActive = true
                replayLongPressDown(lastDownEvent!!)
            } else {
                longPressReplayActive = false
                executeCustomAction(action, "long")
            }
        }
    }

    private fun onMultiPressTimeout() {
        val count = pressCount
        val proto = lastDownEvent
        resetGestureState()
        when (count) {
            1 -> executeGesture("single", proto)
            2 -> executeGesture("double", proto)
        }
    }

    private fun resetGestureState() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(multiPressRunnable)
        pressCount = 0
        longPressFired = false
        longPressReplayActive = false
        lastDownEvent = null
    }

    // ---------------------------------------------------------------- Action Execution

    private fun executeGesture(gesture: String, proto: KeyEvent?) {
        val action = prefs.getString(keyAction(gesture), ACTION_DEFAULT)
        if (action == ACTION_DEFAULT) {
            val count = when (gesture) {
                "single" -> 1
                "double" -> 2
                "triple" -> 3
                else -> 1
            }
            replayClicks(count, proto)
        } else {
            executeCustomAction(action, gesture)
        }
    }

    private fun executeCustomAction(action: String, gesture: String) {
        val context = getSystemContext() ?: return

        when (action) {
            ACTION_NONE -> { /* swallow */ }
            ACTION_FLASHLIGHT -> toggleFlashlight(context)
            ACTION_RINGER -> cycleRinger(context)
            ACTION_SCREENSHOT -> takeScreenshot(context)
            ACTION_APP -> {
                val pkg = prefs.getString(keyApp(gesture), "")
                if (pkg.isNotEmpty()) {
                    launchApp(context, pkg)
                }
            }
        }
    }

    private fun replayClicks(count: Int, proto: KeyEvent?) {
        val method = origMethod ?: return
        val target = origTarget ?: return
        val keyCode = proto?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN
        val scanCode = proto?.scanCode ?: 250
        val deviceId = proto?.deviceId ?: 0

        handler.post {
            try {
                var time = SystemClock.uptimeMillis()
                for (i in 0 until count) {
                    val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, deviceId, scanCode)
                    XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any>(down))
                    time += 30
                    val up = KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0, deviceId, scanCode)
                    XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any>(up))
                    time += 40
                    if (i < count - 1) {
                        try { Thread.sleep(40) } catch (_: InterruptedException) {}
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [EssentialKey] Replay clicks failed: ${t.message}")
            }
        }
    }

    private fun replayLongPressDown(proto: KeyEvent) {
        val method = origMethod ?: return
        val target = origTarget ?: return
        val keyCode = proto.keyCode
        val scanCode = proto.scanCode
        val deviceId = proto.deviceId
        val time = SystemClock.uptimeMillis()

        try {
            val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, 0, deviceId, scanCode)
            XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any>(down))
            val downLong = KeyEvent(time, time + 400, KeyEvent.ACTION_DOWN, keyCode, 1, 0, deviceId, scanCode, KeyEvent.FLAG_LONG_PRESS)
            XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any>(downLong))
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Replay long down failed: ${t.message}")
        }
    }

    private fun replayLongPressUp(proto: KeyEvent) {
        val method = origMethod ?: return
        val target = origTarget ?: return
        val keyCode = proto.keyCode
        val scanCode = proto.scanCode
        val deviceId = proto.deviceId
        val time = SystemClock.uptimeMillis()

        try {
            val up = KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0, deviceId, scanCode)
            XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any>(up))
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Replay long up failed: ${t.message}")
        }
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val intent = Intent(TorchHooks.ACTION_TOGGLE_TORCH).apply {
                setPackage("com.android.systemui")
                putExtra("from_essential", true)
            }
            sendBroadcastSafe(context, intent)
        } catch (_: Throwable) {}
    }

    private fun cycleRinger(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (next != AudioManager.RINGER_MODE_VIBRATE) {
            vibrateClick(context)
        }
        try {
            XposedHelpers.callMethod(am, "setRingerModeInternal", next)
        } catch (_: Throwable) {
            am.ringerMode = next
        }
    }

    private fun takeScreenshot(context: Context) {
        try {
            val helperClass = XposedHelpers.findClassIfExists("com.android.internal.util.ScreenshotHelper", classLoader)
            if (helperClass != null) {
                val helper = XposedHelpers.newInstance(helperClass, context)
                val source = 6 // SCREENSHOT_KEY_OTHER
                XposedHelpers.callMethod(
                    helper,
                    "takeScreenshot",
                    source,
                    Handler(Looper.getMainLooper()),
                    Consumer<Uri?> {}
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Screenshot failed: ${t.message}")
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            } ?: return

            val userHandleClass = XposedHelpers.findClassIfExists("android.os.UserHandle", classLoader)
            val currentUser = XposedHelpers.getStaticObjectField(userHandleClass, "CURRENT")
            val options = android.app.ActivityOptions.makeBasic()
            try {
                XposedHelpers.callMethod(options, "setPendingIntentBackgroundActivityStartMode", 1)
            } catch (_: Throwable) {}
            XposedHelpers.callMethod(context, "startActivityAsUser", intent, options.toBundle(), currentUser)
            vibrateClick(context)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] Launch app failed: ${t.message}")
        }
    }

    private fun sendBroadcastSafe(context: Context, intent: Intent) {
        try {
            val allUser = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL")
            XposedHelpers.callMethod(context, "sendBroadcastAsUser", intent, allUser)
        } catch (_: Throwable) {
            try {
                context.sendBroadcast(intent)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [EssentialKey] Broadcast failed: ${t.message}")
            }
        }
    }

    private fun vibrateClick(context: Context) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(35L)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun getSystemContext(): Context? {
        cachedContext?.let { return it }
        return try {
            val atClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", classLoader)
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            (XposedHelpers.callMethod(at, "getSystemContext") as? Context)?.also { cachedContext = it }
        } catch (_: Throwable) {
            null
        }
    }
}
