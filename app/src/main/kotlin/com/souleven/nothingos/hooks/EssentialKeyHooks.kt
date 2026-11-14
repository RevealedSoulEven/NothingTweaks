package com.souleven.nothingos.hooks

import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Member
import java.util.function.Consumer

/**
 * Essential key (scan code 250), runs in system_server.
 *
 * NtEssentialKeyImpl.onRecord is hooked: the gesture (single / double / triple / long) is classified here, a gesture
 * with a custom action is swallowed, the others are replayed to the original method with their original timing.
 * The Essential Space switches follow the Recorder state published by EssentialRecorderHooks, the Essential Voice
 * switches veto NtEssentialVoiceImpl.startVoiceInput per trigger, and "screenshot with keyboard open" replays a
 * single press with isImeVisible() answering false (Nothing otherwise never forwards the key to the Recorder while a
 * keyboard is shown). Presses that Nothing's own guards would drop are left to it. With nothing customised the hook
 * is a pure pass-through. Work off the input thread runs on one private thread and must never throw.
 */
class EssentialKeyHooks : HookModule {

    companion object {
        const val ACTION_DEFAULT = "default"
        const val ACTION_NONE = "none"
        const val ACTION_FLASHLIGHT = "flashlight"
        const val ACTION_RINGER = "ringer"
        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_APP = "app"

        /** Internal only (never stored): replay the press as a quick click to Nothing's pipeline. */
        private const val ACTION_CLICK = "click"

        /** Internal only: replay a single press so that Nothing's code forwards it to the Recorder although a keyboard is open. */
        private const val ACTION_CAPTURE_IME = "capture_ime"

        val GESTURES = listOf("single", "double", "triple", "long")

        const val ACTION_LAUNCH_APP = "com.souleven.nothingos.action.ESSENTIAL_LAUNCH_APP"
        const val EXTRA_PACKAGE = "package"

        // Switches for Essential Voice (dictation), per trigger
        const val KEY_BLOCK_VOICE_HOLD = "pref_essential_block_voice_hold"
        const val KEY_BLOCK_VOICE_DOUBLE = "pref_essential_block_voice_double"

        // Switches for the Essential Space / Recorder functions (they follow the Recorder state)
        const val KEY_BLOCK_CAPTURE = "pref_essential_block_capture"
        const val KEY_BLOCK_VOICE_RECORD = "pref_essential_block_voice_record"
        const val KEY_BLOCK_OPEN_SPACE = "pref_essential_block_open_space"
        const val KEY_CAPTURE_WITH_KEYBOARD = "pref_essential_capture_with_keyboard"

        /**
         * Settings.Secure key that carries the state of Essential Recorder's capture flow
         * (written by EssentialRecorderHooks, read here). Values are the Recorder's own state names.
         */
        const val KEY_ES_STATE = "nt_tweaks_es_state"

        fun keyAction(gesture: String) = "pref_essential_${gesture}_action"
        fun keyApp(gesture: String) = "pref_essential_${gesture}_app"

        fun anyGestureUses(prefs: Prefs, action: String): Boolean =
            GESTURES.any { prefs.getString(keyAction(it), ACTION_DEFAULT) == action }

        fun isAppAssigned(prefs: Prefs, pkg: String): Boolean =
            pkg.isNotEmpty() && GESTURES.any {
                prefs.getString(keyAction(it), ACTION_DEFAULT) == ACTION_APP &&
                    prefs.getString(keyApp(it), "") == pkg
            }

        private const val IMPL_CLASS = "com.android.server.wm.NtEssentialKeyImpl"
        private const val VOICE_CLASS = "com.android.server.wm.NtEssentialVoiceImpl"

        // Settings.Secure keys read by NtEssentialKeyImpl (verified in nt-services.jar)
        private const val SETTING_BLOCK_WHEN_OFF = "nt_block_essential_key"
        private const val SETTING_MISTOUCH = "nt_essential_key_mistouch_prevention"
        private const val USER_CURRENT = -2
        private const val MISTOUCH_WINDOW_MS = 2000L

        // NtEssentialVoiceImpl$TriggerType: 0 DEFAULT, 1 EK_LONG_CLICK, 2 EK_DOUBLE_CLICK
        private const val TRIGGER_LONG_CLICK = 1
        private const val TRIGGER_DOUBLE_CLICK = 2
        private const val VOICE_ACTIVE_MAX_MS = 120_000L

        private const val LONG_PRESS_MS = 500L // Essential Recorder fires onLongClick ~505-520 ms after DOWN
        private const val MULTI_PRESS_WINDOW_MS = 300L
        private const val MAX_PRESSES = 3
        private const val CLICK_HOLD_MS = 80L
    }

    private class Press(val down: KeyEvent) {
        var up: KeyEvent? = null
    }

    // --- state touched only from the hook (input) thread ---
    @Volatile private var ownedPress = false
    private var pendingActivation = false
    private var stateObserverRegistered = false

    /** Policy computed at the DOWN of the current press, handed to the handler thread. */
    @Volatile private var latestPolicy: Map<String, String> = emptyMap()
    @Volatile private var latestIme = false

    // --- state touched only from [handler] ---
    private val presses = ArrayList<Press>()
    private var longFired = false
    private var longReplayedDown = false
    private var seqPolicy: Map<String, String> = emptyMap()
    private var seqIme = false

    // --- shared ---
    private val imeBypass = ThreadLocal<Boolean>() // true only while a replay must see "no keyboard"
    @Volatile private var origMethod: Member? = null
    @Volatile private var origTarget: Any? = null
    @Volatile private var cachedContext: Context? = null
    @Volatile private var voiceActiveSince = 0L
    @Volatile private var activatedAtWall = 0L
    @Volatile private var esState = "IDLE"
    private var screenshotHelper: Any? = null // only touched on [handler]

    private lateinit var prefs: Prefs
    private lateinit var classLoader: ClassLoader
    private val thread = HandlerThread("NtTweaksEssentialKey").apply { start() }
    private val handler = Handler(thread.looper)

    private val longRunnable = Runnable { guarded { onLongTimeout() } }
    private val windowRunnable = Runnable { guarded { classifyAndFinish() } }

    /** Everything that runs on our own thread goes through here: an exception that escapes would kill system_server. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] task failed: $t")
        }
    }

    private fun post(delayMs: Long = 0L, block: () -> Unit) {
        handler.postDelayed({ guarded(block) }, delayMs)
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return
        this.prefs = prefs
        this.classLoader = lpparam.classLoader

        hookVoiceImpl(lpparam.classLoader)

        val cls = findClass(IMPL_CLASS, lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey] $IMPL_CLASS not found, Essential key features disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "onRecord", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        onRecordHook(param)
                    } catch (t: Throwable) {
                        // never break the stock path because of us: fail open (the event goes to Nothing's code)
                        ownedPress = false
                        pendingActivation = false
                        XposedBridge.log("$TAG [EssentialKey] onRecord hook failed: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] failed to hook onRecord: $t")
        }
    }

    private fun findClass(name: String, cl: ClassLoader): Class<*>? {
        XposedHelpers.findClassIfExists(name, cl)?.let { return it }
        for (alt in listOf(ClassLoader.getSystemClassLoader(), Thread.currentThread().contextClassLoader)) {
            if (alt == null) continue
            try {
                return Class.forName(name, false, alt)
            } catch (_: Throwable) {}
        }
        return null
    }

    // ---------------------------------------------------------------- Essential Voice (dictation)

    /**
     * NtEssentialKeyImpl starts Essential Voice through NtEssentialVoiceImpl.startVoiceInput(boolean, boolean, int)
     * where the int is the trigger type. Returning false there vetoes the start for that trigger only,
     * regardless of the Transcribe / Translate choice made in Nothing's settings.
     * isImeVisible() is overridden only for the replay of a "screenshot with keyboard open" press (see [imeBypass]).
     */
    private fun hookVoiceImpl(cl: ClassLoader) {
        val cls = findClass(VOICE_CLASS, cl)
        if (cls == null) {
            XposedBridge.log("$TAG [EssentialKey] $VOICE_CLASS not found, Essential Voice switches disabled")
            return
        }
        try {
            XposedBridge.hookAllMethods(cls, "startVoiceInput", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = param.args.getOrNull(2) as? Int ?: return
                    val block = (type == TRIGGER_LONG_CLICK && prefs.getBoolean(KEY_BLOCK_VOICE_HOLD, false)) ||
                        (type == TRIGGER_DOUBLE_CLICK && prefs.getBoolean(KEY_BLOCK_VOICE_DOUBLE, false))
                    if (block) {
                        param.setObjectExtra("ntBlocked", true)
                        param.result = false
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("ntBlocked") == true || param.hasThrowable()) return
                    if (param.result == true) voiceActiveSince = SystemClock.uptimeMillis()
                }
            })
            XposedBridge.hookAllMethods(cls, "stopVoiceInput", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    voiceActiveSince = 0L
                }
            })
            XposedBridge.hookAllMethods(cls, "isImeVisible", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (imeBypass.get() == true) param.result = false
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] voice hook failed: $t")
        }
    }

    /** True while a keyboard is shown, as NtEssentialVoiceImpl sees it (false when it cannot be asked). */
    private fun imeVisible(): Boolean = try {
        val factory = XposedHelpers.findClass("com.nothing.server.NtExtServiceFactory", classLoader)
        val type = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass("com.nothing.server.INtExtServiceFactory\$ExtType", classLoader), "NT_ESSENTIAL_VOICE"
        )
        XposedHelpers.callMethod(XposedHelpers.callStaticMethod(factory, "getOrCreate", type), "isImeVisible") as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    /** True while an Essential Voice session started by the key runs: its stop-press must reach Nothing's code. */
    private fun voiceActive(): Boolean {
        val since = voiceActiveSince
        return since != 0L && SystemClock.uptimeMillis() - since < VOICE_ACTIVE_MAX_MS
    }

    // ---------------------------------------------------------------- hook thread

    private fun onRecordHook(param: XC_MethodHook.MethodHookParam) {
        val event = param.args.getOrNull(0) as? KeyEvent ?: return

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            decidePress()
        } else if (event.action == KeyEvent.ACTION_UP && !ownedPress && pendingActivation) {
            // mirrors NtEssentialKeyImpl: the UP of an un-activated press arms the key for 2 seconds
            pendingActivation = false
            activatedAtWall = System.currentTimeMillis()
        }

        if (!ownedPress) return // pass-through to Nothing's pipeline

        origMethod = param.method
        origTarget = param.thisObject
        param.result = null // swallow: Nothing's pipeline does not see this event

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return
        val copy = KeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val policy = latestPolicy
                val ime = latestIme
                post { onDown(copy, policy, ime) }
            }
            KeyEvent.ACTION_UP -> post { onUp(copy) }
        }
    }

    /** Decides once per press whether we take ownership. The UP of a press follows the decision of its DOWN. */
    private fun decidePress() {
        pendingActivation = false
        val state = if (needsEssentialState()) {
            ensureStateObserver()
            esState
        } else {
            "IDLE"
        }
        val ime = prefs.getBoolean(KEY_CAPTURE_WITH_KEYBOARD, false) && imeVisible()
        val policy = GESTURES.associateWith { effectiveAction(it, state, ime) }
        latestPolicy = policy
        latestIme = ime
        ownedPress = policy.values.any { it != ACTION_DEFAULT } && !voiceActive() && !nativeGuardHit()
    }

    private fun needsEssentialState() =
        prefs.getBoolean(KEY_BLOCK_CAPTURE, false) || prefs.getBoolean(KEY_BLOCK_VOICE_RECORD, false)

    /**
     * What should happen for [gesture] given the Recorder [state]?
     * A custom action wins; otherwise the per-function switches decide; otherwise the system default.
     */
    private fun effectiveAction(gesture: String, state: String, keyboardOpen: Boolean): String {
        val custom = prefs.getString(keyAction(gesture), ACTION_DEFAULT)
        // "open app" without a chosen app must not swallow the stock behaviour for nothing
        if (custom == ACTION_APP && prefs.getString(keyApp(gesture), "").isEmpty()) return ACTION_DEFAULT
        if (custom != ACTION_DEFAULT) return custom
        val idle = state == "IDLE"
        val focus = state == "FOCUS" || state == "FOCUS_SHARE"
        val blockCapture = prefs.getBoolean(KEY_BLOCK_CAPTURE, false)
        val blockRecord = prefs.getBoolean(KEY_BLOCK_VOICE_RECORD, false)
        val blockSpace = prefs.getBoolean(KEY_BLOCK_OPEN_SPACE, false)
        return when (gesture) {
            "single" -> when {
                blockCapture && idle -> ACTION_NONE
                keyboardOpen -> ACTION_CAPTURE_IME // with a keyboard open Nothing would only use it for dictation
                else -> ACTION_DEFAULT
            }
            "double" -> if (blockSpace) ACTION_NONE else ACTION_DEFAULT
            "long" -> when {
                blockRecord -> if (focus) ACTION_CLICK else ACTION_NONE // in the capture bar: just save, no voice note
                blockCapture && idle -> ACTION_NONE // a hold in IDLE would take the screenshot too
                else -> ACTION_DEFAULT
            }
            else -> ACTION_DEFAULT
        }
    }

    /**
     * Keeps [esState] in sync with the value EssentialRecorderHooks publishes, without any settings read on the
     * input thread: the first call (on our own thread) clears a value left over from the previous boot and
     * registers an observer.
     */
    private fun ensureStateObserver() {
        if (stateObserverRegistered) return
        val ctx = systemContext() ?: return
        stateObserverRegistered = true
        post {
            val resolver = ctx.contentResolver
            Settings.Secure.putString(resolver, KEY_ES_STATE, "IDLE")
            esState = "IDLE"
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_ES_STATE), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        guarded { esState = Settings.Secure.getString(resolver, KEY_ES_STATE) ?: "IDLE" }
                    }
                }
            )
        }
    }

    /** True when NtEssentialKeyImpl would ignore the press; those presses are left to it. */
    private fun nativeGuardHit(): Boolean {
        val ctx = systemContext() ?: return false
        return try {
            val resolver = ctx.contentResolver
            val blockOn = secureInt(resolver, SETTING_BLOCK_WHEN_OFF) != 0
            val interactive = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
            val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: false
            when {
                blockOn && (!interactive || locked) -> true // "disabled when screen off / locked"
                secureInt(resolver, SETTING_MISTOUCH) != 0 &&
                    System.currentTimeMillis() - activatedAtWall > MISTOUCH_WINDOW_MS -> {
                    pendingActivation = true // "activate with a single tap first"
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [EssentialKey] guard check failed: $t")
            false
        }
    }

    private fun secureInt(resolver: ContentResolver, key: String): Int =
        try {
            XposedHelpers.callStaticMethod(Settings.Secure::class.java, "getIntForUser", resolver, key, 0, USER_CURRENT) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }

    // ---------------------------------------------------------------- handler thread

    private fun onDown(ev: KeyEvent, policy: Map<String, String>, ime: Boolean) {
        handler.removeCallbacks(windowRunnable)

        // stale state (e.g. a cancelled press whose UP never arrived)
        if (presses.isNotEmpty() && (presses.last().up == null || longFired)) resetState()

        if (presses.isEmpty()) {
            seqPolicy = policy
            seqIme = ime
        }
        presses.add(Press(ev))
        if (presses.size == 1) {
            longFired = false
            handler.removeCallbacks(longRunnable)
            handler.postDelayed(longRunnable, LONG_PRESS_MS)
        }
    }

    private fun onUp(ev: KeyEvent) {
        val last = presses.lastOrNull() ?: return
        last.up = ev

        if (longFired) {
            if (longReplayedDown) replay(ev, 0L) // a long press left on the system default: finish it
            resetState()
            return
        }

        handler.removeCallbacks(longRunnable)
        if (presses.size >= MAX_PRESSES) {
            classifyAndFinish()
        } else {
            handler.removeCallbacks(windowRunnable)
            handler.postDelayed(windowRunnable, MULTI_PRESS_WINDOW_MS)
        }
    }

    private fun onLongTimeout() {
        // only the first press of a sequence can become a long press
        if (presses.size != 1 || presses[0].up != null) return
        longFired = true
        when (val action = seqPolicy["long"] ?: ACTION_DEFAULT) {
            ACTION_DEFAULT -> {
                longReplayedDown = true
                replay(presses[0].down, 0L)
            }
            ACTION_CLICK -> {
                // keep the hold away from the Recorder (no voice note) and give it a quick click instead
                longReplayedDown = false
                replayClick(presses[0].down)
            }
            else -> {
                longReplayedDown = false
                execute(action, "long")
            }
        }
    }

    private fun classifyAndFinish() {
        handler.removeCallbacks(longRunnable)
        val snapshot = ArrayList(presses)
        presses.clear()
        longFired = false
        longReplayedDown = false
        if (snapshot.isEmpty()) return

        val gesture = when (snapshot.size) {
            1 -> "single"
            2 -> "double"
            else -> "triple"
        }
        val action = seqPolicy[gesture] ?: ACTION_DEFAULT
        if (action == ACTION_DEFAULT || action == ACTION_CAPTURE_IME) {
            // give the stock pipeline exactly what it would have seen, with the original gaps
            val bypass = action == ACTION_CAPTURE_IME
            val base = snapshot[0].down.eventTime
            for (p in snapshot) {
                replay(p.down, (p.down.eventTime - base).coerceAtLeast(0L), bypass)
                p.up?.let { replay(it, (it.eventTime - base).coerceAtLeast(0L), bypass) }
            }
        } else {
            execute(action, gesture)
        }
    }

    private fun resetState() {
        handler.removeCallbacks(longRunnable)
        handler.removeCallbacks(windowRunnable)
        presses.clear()
        longFired = false
        longReplayedDown = false
    }

    private fun replay(ev: KeyEvent, delayMs: Long, bypassIme: Boolean = false) {
        val method = origMethod ?: return
        val target = origTarget ?: return
        post(delayMs) {
            if (bypassIme) imeBypass.set(true)
            try {
                XposedBridge.invokeOriginalMethod(method, target, arrayOf<Any?>(KeyEvent(ev)))
            } finally {
                imeBypass.set(false)
            }
        }
    }

    /** DOWN now, UP shortly after: what a quick tap looks like to Nothing's pipeline. */
    private fun replayClick(down: KeyEvent) {
        replay(down, 0L, seqIme)
        val up = KeyEvent.changeTimeRepeat(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), down.eventTime + CLICK_HOLD_MS, 0)
        replay(up, CLICK_HOLD_MS, seqIme)
    }

    // ---------------------------------------------------------------- actions

    private fun systemContext(): Context? {
        cachedContext?.let { return it }
        return try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            (XposedHelpers.callMethod(at, "getSystemContext") as? Context)?.also { cachedContext = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun execute(action: String, gesture: String) {
        val ctx = systemContext() ?: return
        when (action) {
            // SystemUI's torch toggle already gives its own haptic, so none is added here
            ACTION_FLASHLIGHT ->
                sendToSystemUi(ctx, Intent(TorchHooks.ACTION_TOGGLE_TORCH).putExtra(TorchHooks.EXTRA_ESSENTIAL, true))
            ACTION_RINGER -> cycleRinger(ctx)
            ACTION_SCREENSHOT -> takeScreenshot(ctx)
            ACTION_APP -> {
                val pkg = prefs.getString(keyApp(gesture), "")
                if (pkg.isNotEmpty()) sendToSystemUi(ctx, Intent(ACTION_LAUNCH_APP).putExtra(EXTRA_PACKAGE, pkg))
            }
            // ACTION_NONE (and anything unknown): the press is simply swallowed
        }
    }

    private fun sendToSystemUi(ctx: Context, intent: Intent) {
        try {
            intent.setPackage("com.android.systemui")
            val all = XposedHelpers.getStaticObjectField(UserHandle::class.java, "ALL")
            XposedHelpers.callMethod(ctx, "sendBroadcastAsUser", intent, all)
        } catch (t: Throwable) {
            try {
                ctx.sendBroadcast(intent)
            } catch (_: Throwable) {
                XposedBridge.log("$TAG [EssentialKey] broadcast failed: ${t.message}")
            }
        }
    }

    /** The system screenshot, started the way PhoneWindowManager does it (SystemUI shows its own feedback). */
    private fun takeScreenshot(ctx: Context) {
        val helper = screenshotHelper ?: XposedHelpers.newInstance(
            XposedHelpers.findClass("com.android.internal.util.ScreenshotHelper", classLoader), ctx
        ).also { screenshotHelper = it }
        val source = XposedHelpers.getStaticIntField(
            XposedHelpers.findClass("android.view.WindowManager\$ScreenshotSource", classLoader), "SCREENSHOT_KEY_OTHER"
        )
        // the helper posts its timeouts to this handler: use the main one, not our private thread
        XposedHelpers.callMethod(helper, "takeScreenshot", source, Handler(Looper.getMainLooper()), Consumer<Uri?> { })
    }

    /** Normal -> Vibrate -> Silent -> Normal */
    private fun cycleRinger(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        // SystemUI gives its own haptic when the ringer changes to vibrate; for the other changes nothing
        // vibrates, so we do it. Result: always exactly one haptic.
        if (next != AudioManager.RINGER_MODE_VIBRATE) vibrate(ctx)
        try {
            XposedHelpers.callMethod(am, "setRingerModeInternal", next)
        } catch (_: Throwable) {
            am.ringerMode = next
        }
    }

    private fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }
}
