package com.souleven.nothingos.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"
    private var lastIndicationController: Any? = null
    private var lastRotateController: Any? = null
    private var isCharging = false
    private var isUpdatingInPlace = false
    private var powerManager: PowerManager? = null
    private var lastDozeTapTime: Long = 0L
    private var lastWakeTime: Long = 0L
    private var batteryReceiver: BroadcastReceiver? = null

    private fun ensureBatteryReceiver(context: Context, prefs: Prefs, classLoader: ClassLoader) {
        if (batteryReceiver != null) return
        val appCtx = context.applicationContext ?: context
        val sticky = try { appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
        val initStatus = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        isCharging = initStatus == BatteryManager.BATTERY_STATUS_CHARGING
                || initStatus == BatteryManager.BATTERY_STATUS_FULL

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL
                if (nowCharging != isCharging) {
                    onChargingStateChanged(nowCharging, classLoader, prefs)
                }
            }
        }
        try {
            appCtx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        } catch (_: Throwable) {}

        try {
            appCtx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        reshuffleComposePin()
                    }
                }
            }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (_: Throwable) {}
    }

    private fun buildChargingIndication(classLoader: ClassLoader, message: String, ctrl: Any?): Any? {
        val builderClass = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.KeyguardIndication\$Builder", classLoader) ?: return null
        val builder = XposedHelpers.newInstance(builderClass)
        XposedHelpers.callMethod(builder, "setMessage", message)
        val color = if (ctrl != null) try { XposedHelpers.callMethod(ctrl, "getInitialTextColorState") } catch (_: Throwable) { null } else null
        if (color != null) XposedHelpers.callMethod(builder, "setTextColor", color)
        return XposedHelpers.callMethod(builder, "build")
    }

    private fun ensureChargingIndication(classLoader: ClassLoader) {
        val ctrl = lastIndicationController ?: return
        val rotateCtrl = lastRotateController
            ?: try { XposedHelpers.getObjectField(ctrl, "mRotateTextViewController") } catch (_: Throwable) { null }
            ?: return
        val context = try { XposedHelpers.getObjectField(ctrl, "mContext") as? Context } catch (_: Throwable) { null }
            ?: (XposedHelpers.getObjectField(rotateCtrl, "mView") as? View)?.context
            ?: return
        val extra = getChargingExtraInfo(context, classLoader)
        if (extra.isEmpty()) return

        val indication = buildChargingIndication(classLoader, extra, ctrl) ?: return
        try {
            XposedHelpers.callMethod(rotateCtrl, "updateIndication", 0, indication, false)
        } catch (_: Throwable) {}
    }

    private fun onChargingStateChanged(charging: Boolean, classLoader: ClassLoader, prefs: Prefs) {
        isCharging = charging
        val ctrl = lastIndicationController
        if (charging) {
            val isVisible = if (ctrl != null) try { XposedHelpers.getBooleanField(ctrl, "mVisible") } catch (_: Throwable) { false } else false
            if (isVisible && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                ensureChargingIndication(classLoader)
                if (ctrl != null) {
                    try {
                        XposedHelpers.callMethod(ctrl, "updateLockScreenOwnerInfo")
                        XposedHelpers.callMethod(ctrl, "updateLockScreenBatteryMsg", false)
                    } catch (_: Throwable) {}
                }
            }
        } else {
            if (ctrl != null) {
                try {
                    XposedHelpers.callMethod(ctrl, "updateLockScreenOwnerInfo")
                    XposedHelpers.callMethod(ctrl, "updateLockScreenBatteryMsg", false)
                } catch (_: Throwable) {}
            }
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader)
        if (clazz != null) {
            hookBool(clazz, "getShouldPowerOffVerify", "disable_power_off_verify", prefs, forceValue = false)
        } else {
            XposedBridge.log("$TAG   [LockScreen] class not found: $className")
        }

        val pinViewControllerClass = XposedHelpers.findClassIfExists("com.android.keyguard.KeyguardPinViewController", lpparam.classLoader)
        if (pinViewControllerClass != null) {
            try {
                XposedBridge.hookAllMethods(pinViewControllerClass, "onViewAttached", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean("scramble_pin", false)) return

                        val view = XposedHelpers.getObjectField(param.thisObject, "mView") as? View ?: return
                        val context = view.context

                        val buttons = mutableListOf<View>()
                        for (i in 0..9) {
                            val resId = context.resources.getIdentifier("key$i", "id", "com.android.systemui")
                            if (resId != 0) {
                                view.findViewById<View>(resId)?.let { buttons.add(it) }
                            }
                        }

                        if (buttons.size == 10) {
                            val shuffledDigits = (0..9).shuffled()
                            val sKlondike = XposedHelpers.getStaticObjectField(buttons[0].javaClass, "sKlondike") as? Array<String>

                            for (i in 0..9) {
                                val btn = buttons[i]
                                val newDigit = shuffledDigits[i]

                                XposedHelpers.setIntField(btn, "mDigit", newDigit)

                                val digitText = XposedHelpers.getObjectField(btn, "mDigitText") as? TextView
                                digitText?.text = newDigit.toString()

                                val klondikeText = XposedHelpers.getObjectField(btn, "mKlondikeText") as? TextView
                                if (sKlondike != null && sKlondike.size > newDigit && newDigit >= 0) {
                                    klondikeText?.text = sKlondike[newDigit]
                                } else {
                                    klondikeText?.text = ""
                                }
                            }
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook scramble pin: ${t.message}")
            }
        }

        // Nothing OS 5.0 / Flexiglass Scene Container
        hookComposeScramblePin(lpparam, prefs)

        // Nothing OS 5.0 / Compose & Scene Container
        hookComposeHideClock(lpparam, prefs)

        val keyguardRootViewClass = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.ui.view.KeyguardRootView", lpparam.classLoader)
        if (keyguardRootViewClass != null) {
            try {
                XposedBridge.hookAllConstructors(keyguardRootViewClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val root = param.thisObject as? ViewGroup ?: return
                            powerManager = root.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            ensureBatteryReceiver(root.context, prefs, lpparam.classLoader)
                            setupHideClock(root, prefs)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [LockScreen] Error in setup KeyguardRootView: ${t.message}")
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook KeyguardRootView: ${t.message}")
            }
        }

        val rotateControllerClass = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController", lpparam.classLoader)
        if (rotateControllerClass != null) {
            try {
                XposedBridge.hookAllMethods(rotateControllerClass, "updateIndication", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        lastRotateController = param.thisObject
                        val id = param.args.getOrNull(0) as? Int ?: return
                        if (id == 0 && isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            val ctrl = lastIndicationController
                            val context = (if (ctrl != null) try { XposedHelpers.getObjectField(ctrl, "mContext") as? Context } catch (_: Throwable) { null } else null)
                                ?: (XposedHelpers.getObjectField(param.thisObject, "mView") as? View)?.context
                                ?: return
                            val extra = getChargingExtraInfo(context, lpparam.classLoader)
                            if (extra.isNotEmpty()) {
                                val indication = param.args.getOrNull(1)
                                if (indication == null) {
                                    val newInd = buildChargingIndication(lpparam.classLoader, extra, ctrl)
                                    if (newInd != null) {
                                        param.args[1] = newInd
                                        param.args[2] = false
                                    }
                                } else {
                                    try {
                                        XposedHelpers.setObjectField(indication, "mMessage", extra)
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(rotateControllerClass, "showIndication", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        lastRotateController = param.thisObject
                        val newType = param.args.getOrNull(0) as? Int ?: -1
                        val currType = try { XposedHelpers.getIntField(param.thisObject, "mCurrIndicationType") } catch (_: Throwable) { -1 }
                        isUpdatingInPlace = (newType == currType && newType != -1)

                        if (newType == 0 && isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            val ctrl = lastIndicationController
                            val context = (if (ctrl != null) try { XposedHelpers.getObjectField(ctrl, "mContext") as? Context } catch (_: Throwable) { null } else null)
                                ?: (XposedHelpers.getObjectField(param.thisObject, "mView") as? View)?.context
                            if (context != null) {
                                val extra = getChargingExtraInfo(context, lpparam.classLoader)
                                if (extra.isNotEmpty()) {
                                    val map = try { XposedHelpers.getObjectField(param.thisObject, "mIndicationMessages") as? Map<Int, Any> } catch (_: Throwable) { null }
                                    val indication = map?.get(0)
                                    if (indication != null) {
                                        try {
                                            XposedHelpers.setObjectField(indication, "mMessage", extra)
                                        } catch (_: Throwable) {}
                                    } else {
                                        val newInd = buildChargingIndication(lpparam.classLoader, extra, ctrl)
                                        if (newInd != null) {
                                            (map as? MutableMap<Int, Any>)?.put(0, newInd)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        isUpdatingInPlace = false
                    }
                })
            } catch (_: Throwable) {}
        }

        val indicationTextViewClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.KeyguardIndicationTextView",
            lpparam.classLoader
        )
        if (indicationTextViewClass != null) {
            try {
                XposedBridge.hookAllMethods(indicationTextViewClass, "switchIndication", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size >= 3 && isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            if (isUpdatingInPlace) {
                                param.args[2] = false
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val rotateListenerClass = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController\$1", lpparam.classLoader)
        if (rotateListenerClass != null) {
            try {
                XposedBridge.hookAllMethods(rotateListenerClass, "onDozingChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val isDozing = param.args.getOrNull(0) as? Boolean ?: return
                        if (!isDozing && isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            val ctrl = lastIndicationController
                            if (ctrl != null) {
                                ensureChargingIndication(lpparam.classLoader)
                                try {
                                    XposedHelpers.callMethod(ctrl, "updateLockScreenOwnerInfo")
                                    XposedHelpers.callMethod(ctrl, "updateLockScreenBatteryMsg", false)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val controllerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.KeyguardIndicationController", lpparam.classLoader)
        if (controllerClass != null) {
            try {
                XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastIndicationController = param.thisObject
                        val context = try { XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context } catch (_: Throwable) { null }
                        if (context != null) {
                            ensureBatteryReceiver(context, prefs, lpparam.classLoader)
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(controllerClass, "computePowerIndication", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastIndicationController = param.thisObject
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(controllerClass, "setIndicationArea", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastIndicationController = param.thisObject
                        val rotateCtrl = try { XposedHelpers.getObjectField(param.thisObject, "mRotateTextViewController") } catch (_: Throwable) { null }
                        if (rotateCtrl != null) lastRotateController = rotateCtrl
                        if (isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            val isVisible = try { XposedHelpers.getBooleanField(param.thisObject, "mVisible") } catch (_: Throwable) { false }
                            if (isVisible) {
                                ensureChargingIndication(lpparam.classLoader)
                                try {
                                    XposedHelpers.callMethod(param.thisObject, "updateLockScreenOwnerInfo")
                                    XposedHelpers.callMethod(param.thisObject, "updateLockScreenBatteryMsg", false)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(controllerClass, "setVisible", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastIndicationController = param.thisObject
                        val visible = param.args.getOrNull(0) as? Boolean ?: return
                        if (visible && isCharging && prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                            ensureChargingIndication(lpparam.classLoader)
                            try {
                                XposedHelpers.callMethod(param.thisObject, "updateLockScreenOwnerInfo")
                                XposedHelpers.callMethod(param.thisObject, "updateLockScreenBatteryMsg", false)
                            } catch (_: Throwable) {}
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        val touchHandlingViewClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.common.ui.view.TouchHandlingView",
            lpparam.classLoader
        )
        if (touchHandlingViewClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    touchHandlingViewClass,
                    "setDoublePressHandlingEnabled",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)
                                || prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                param.args[0] = true
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    touchHandlingViewClass,
                    "onAttachedToWindow",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)
                                || prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                try {
                                    XposedHelpers.callMethod(param.thisObject, "setDoublePressHandlingEnabled", true)
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val interactorClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.keyguard.domain.interactor.KeyguardTouchHandlingInteractor",
            lpparam.classLoader
        )
        if (interactorClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    interactorClass,
                    "onDoubleClick",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            val pm = getPowerManager(param.thisObject)
                            val isInteractive = pm?.isInteractive ?: false

                            if (!isInteractive) {
                                // device is dozing / screen off / AOD -> double tap to WAKE
                                if (prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                    lastWakeTime = SystemClock.uptimeMillis()
                                    val pulsing = try { XposedHelpers.getObjectField(param.thisObject, "pulsingGestureListener") } catch (_: Throwable) { null }
                                    if (pulsing != null) {
                                        try {
                                            XposedHelpers.callMethod(pulsing, "onDoubleTapEvent")
                                        } catch (_: Throwable) {}
                                    }
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis(), 15, "DOUBLE_TAP_TO_WAKE")
                                        } catch (_: Throwable) {
                                            try {
                                                XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis())
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                    param.result = null
                                }
                            } else {
                                // device is awake on lockscreen -> double tap to SLEEP
                                if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
                                    if (SystemClock.uptimeMillis() - lastWakeTime < 800) {
                                        param.result = null
                                        return
                                    }
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "goToSleep", SystemClock.uptimeMillis(), 4, 0)
                                        } catch (_: Throwable) {
                                            try {
                                                XposedHelpers.callMethod(it, "goToSleep", SystemClock.uptimeMillis())
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                    param.result = null
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook onDoubleClick: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    interactorClass,
                    "isDoubleTapFeatureEnabled",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)
                                || prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val pulsingGestureListenerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shade.PulsingGestureListener",
            lpparam.classLoader
        )
        if (pulsingGestureListenerClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    pulsingGestureListenerClass,
                    "onDoubleTapEvent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            val ssc = try { XposedHelpers.getObjectField(param.thisObject, "statusBarStateController") } catch (_: Throwable) { null }
                            val isDozing = try {
                                XposedHelpers.callMethod(ssc, "isDozing") as? Boolean ?: false
                            } catch (_: Throwable) { false }
                            val state = try {
                                XposedHelpers.callMethod(ssc, "getState") as? Int ?: -1
                            } catch (_: Throwable) { -1 }

                            // Double tap to sleep on lockscreen
                            if (!isDozing && state == 1 && !isBouncerShowing(lastIndicationController) && prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
                                if (SystemClock.uptimeMillis() - lastWakeTime < 800) return
                                val falsingManager = try { XposedHelpers.getObjectField(param.thisObject, "falsingManager") } catch (_: Throwable) { null }
                                val isFalseTap = try {
                                    XposedHelpers.callMethod(falsingManager, "isFalseDoubleTap") as? Boolean ?: false
                                } catch (_: Throwable) { false }
                                if (!isFalseTap) {
                                    val pm = getPowerManager(param.thisObject)
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "goToSleep", SystemClock.uptimeMillis(), 4, 0)
                                        } catch (_: Throwable) {
                                            try {
                                                XposedHelpers.callMethod(it, "goToSleep", SystemClock.uptimeMillis())
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                    param.result = true
                                    return
                                }
                            }

                            // Double tap to wake while in AOD/dozing
                            if (isDozing && prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                lastWakeTime = SystemClock.uptimeMillis()
                                val powerInteractor = try { XposedHelpers.getObjectField(param.thisObject, "powerInteractor") } catch (_: Throwable) { null }
                                if (powerInteractor != null) {
                                    try {
                                        XposedHelpers.callMethod(powerInteractor, "wakeUpIfDozing", "PULSING_DOUBLE_TAP", 15)
                                    } catch (_: Throwable) {}
                                }
                                val pm = getPowerManager(param.thisObject)
                                pm?.let {
                                    try {
                                        XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis(), 15, "PULSING_DOUBLE_TAP")
                                    } catch (_: Throwable) {
                                        try {
                                            XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis())
                                        } catch (_: Throwable) {}
                                    }
                                }
                                param.result = true
                                return
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook PulsingGestureListener: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    pulsingGestureListenerClass,
                    "onSingleTapUp",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                // nerf single tap wake while dozing/AOD!
                                param.result = false
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    pulsingGestureListenerClass,
                    "onSingleTapUp",
                    MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            prefs.forceReload()
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                // Nerf single tap wake while dozing/AOD!
                                param.result = false
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val mediatorClass = XposedHelpers.findClassIfExists("com.nothing.systemui.keyguard.KeyguardViewMediatorEx", lpparam.classLoader)
        if (mediatorClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    mediatorClass,
                    "handleNotifyStartedWakingUp",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            lastDozeTapTime = 0L
                            lastWakeTime = SystemClock.uptimeMillis()
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val dozeTriggersExClass = XposedHelpers.findClassIfExists("com.nothing.systemui.doze.DozeTriggersEx", lpparam.classLoader)
        if (dozeTriggersExClass != null) {
            try {
                val dozeHostClass = XposedHelpers.findClassIfExists("com.android.systemui.doze.DozeHost", lpparam.classLoader)
                val dozeMachineStateClass = XposedHelpers.findClassIfExists("com.android.systemui.doze.DozeMachine\$State", lpparam.classLoader)
                val consumerClass = java.util.function.Consumer::class.java

                if (dozeHostClass != null && dozeMachineStateClass != null) {
                    XposedHelpers.findAndHookMethod(
                        dozeTriggersExClass,
                        "handleSingleTapEvent",
                        dozeHostClass,
                        dozeMachineStateClass,
                        consumerClass,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                prefs.forceReload()
                                if (!prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) return

                                val now = SystemClock.uptimeMillis()
                                val diff = now - lastDozeTapTime
                                if (diff in 40..800) {
                                    // double tap: wake device straight to lockscreen
                                    lastDozeTapTime = 0L
                                    lastWakeTime = now

                                    val dozeHost = param.args[0]
                                    val x = param.args[3] as? Float ?: -1.0f
                                    val y = param.args[4] as? Float ?: -1.0f
                                    val consumer = param.args[2]
                                    val reason = param.args[5]

                                    try {
                                        XposedHelpers.callMethod(dozeHost, "onSlpiTap", x, y)
                                    } catch (_: Throwable) {}

                                    if (consumer != null && reason != null) {
                                        try {
                                            XposedHelpers.callMethod(consumer, "accept", reason)
                                        } catch (_: Throwable) {}
                                    }

                                    val pm = getPowerManager(param.thisObject)
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis(), 15, "DOUBLE_TAP_TO_WAKE")
                                        } catch (_: Throwable) {
                                            try {
                                                XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis())
                                            } catch (_: Throwable) {}
                                        }
                                    }

                                    // cancel stock method so it NEVER requests DOZE_AOD (shows doze again)
                                    param.result = null
                                } else {
                                    // single tap: nerf completely! screen stays off/dozing, do not transition to AOD
                                    lastDozeTapTime = now
                                    param.result = null
                                }
                            }
                        }
                    )
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook handleSingleTapEvent: ${t.message}")
            }
        }


    }

    private fun isBouncerShowing(controller: Any?): Boolean {
        val vm = if (controller != null) try { XposedHelpers.getObjectField(controller, "mStatusBarKeyguardViewManager") } catch (_: Throwable) { null } else null
        if (vm != null) {
            try {
                return XposedHelpers.callMethod(vm, "isBouncerShowing") as? Boolean ?: false
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun getPowerManager(fallbackObj: Any? = null): PowerManager? {
        if (powerManager != null) return powerManager
        val ctx = try {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        } ?: run {
            if (fallbackObj != null) {
                val userTracker = try { XposedHelpers.getObjectField(fallbackObj, "userTracker") } catch (_: Throwable) { null }
                (try { XposedHelpers.callMethod(userTracker, "getUserContext") as? Context } catch (_: Throwable) { null })
                    ?: (try { XposedHelpers.getObjectField(fallbackObj, "mContext") as? Context } catch (_: Throwable) { null })
            } else null
        }
        return (ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.also { powerManager = it }
    }


    private fun getChargingExtraInfo(context: Context, classLoader: ClassLoader): String {
        val parts = mutableListOf<String>()
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            var currentMicro = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            if (currentMicro == 0 || currentMicro == Int.MIN_VALUE) {
                currentMicro = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
            }
            if (currentMicro == Int.MIN_VALUE) {
                currentMicro = 0
            }
            val currentAbs = kotlin.math.abs(currentMicro.toLong())
            val currentMa = (if (currentAbs > 10_000) currentAbs / 1000 else currentAbs).toInt()

            val batteryIntent = try {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (_: Throwable) {
                null
            }
            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

            val adaptorRate = try {
                val csmClass = XposedHelpers.findClassIfExists("com.nothing.systemui.power.ChargeStateMonitor", classLoader)
                if (csmClass != null) {
                    val instance = XposedHelpers.getStaticObjectField(csmClass, "INSTANCE")
                    XposedHelpers.callMethod(instance, "getAdaptorPowerRate") as? Int ?: 0
                } else 0
            } catch (_: Throwable) {
                0
            }

            val watts = if (currentMa > 0 && voltageMv > 0) {
                (currentMa.toLong() * voltageMv) / 1_000_000.0f
            } else if (adaptorRate > 0) {
                adaptorRate.toFloat()
            } else {
                0.0f
            }

            if (watts > 0.0f) {
                parts.add(String.format(Locale.US, "%.1fW", watts))
            }
            if (currentMa > 0) {
                parts.add("${currentMa}mA")
            }
            if (voltageMv > 0) {
                val voltageV = if (voltageMv > 100) voltageMv / 1000.0f else voltageMv.toFloat()
                parts.add(String.format(Locale.US, "%.2fV", voltageV))
            }
            if (tempTenths > 0) {
                parts.add(String.format(Locale.US, "%.1f°C", tempTenths / 10.0f))
            }
        } catch (_: Throwable) {}
        return parts.joinToString(" · ")
    }

    private fun setupHideClock(root: ViewGroup, prefs: Prefs) {
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                try {
                    if (prefs.getBoolean("pref_hide_lockscreen_clock", false)) {
                        var changed = false
                        val hideId = { idName: String ->
                            val resId = root.context.resources.getIdentifier(idName, "id", "com.android.systemui")
                            if (resId != 0) {
                                val v = root.findViewById<View>(resId)
                                if (v != null && v.visibility != View.INVISIBLE) {
                                    v.visibility = View.INVISIBLE
                                    changed = true
                                }
                            }
                        }

                        hideId("bc_smartspace_view")
                        hideId("keyguard_slice_view")
                        hideId("lockscreen_clock_view")
                        hideId("lockscreen_clock_view_large")
                        hideId("date_smartspace_view_large")
                        hideId("weather_smartspace_view_large")
                        hideId("weather_clock_view")

                        if (changed) return false
                    }
                } catch (_: Throwable) {}
                return true
            }
        })
    }

    private fun hookComposeHideClock(lpparam: LoadPackageParam, prefs: Prefs) {
        val hookedViews = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

        val hideClock = { view: View? ->
            if (view != null && hookedViews.add(view)) {
                var hiddenByUs = false
                view.viewTreeObserver.addOnPreDrawListener {
                    try {
                        if (prefs.getBoolean("pref_hide_lockscreen_clock", false)) {
                            if (view.visibility != View.INVISIBLE) {
                                view.visibility = View.INVISIBLE
                                hiddenByUs = true
                            }
                            if (view.alpha != 0f) {
                                view.alpha = 0f
                                hiddenByUs = true
                            }
                        } else if (hiddenByUs) {
                            view.visibility = View.VISIBLE
                            view.alpha = 1f
                            hiddenByUs = false
                        }
                    } catch (_: Throwable) {}
                    true
                }
            }
        }

        val clockViewCompanion = XposedHelpers.findClassIfExists(
            "com.android.systemui.customization.clocks.view.DefaultClockFaceLayout\$Companion",
            lpparam.classLoader
        )
        if (clockViewCompanion != null) {
            try {
                XposedBridge.hookAllMethods(clockViewCompanion, "ClockView", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        hideClock(param.args.getOrNull(0) as? View)
                    }
                })
            } catch (_: Throwable) {}
        }

        val clockEventController = XposedHelpers.findClassIfExists(
            "com.android.keyguard.ClockEventController",
            lpparam.classLoader
        )
        if (clockEventController != null) {
            try {
                XposedBridge.hookAllMethods(clockEventController, "setClock", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clock = param.args.getOrNull(0) ?: return
                        try {
                            val small = XposedHelpers.callMethod(clock, "getSmallClock")
                            val large = XposedHelpers.callMethod(clock, "getLargeClock")
                            hideClock(XposedHelpers.callMethod(small, "getView") as? View)
                            hideClock(XposedHelpers.callMethod(large, "getView") as? View)
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun hookBool(
        clazz: Class<*>,
        method: String,
        prefKey: String,
        prefs: Prefs,
        forceValue: Boolean
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(prefKey, false)) return
                    val orig = param.result as? Boolean ?: return
                    if (orig != forceValue) {
                        param.result = forceValue
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] FAILED to hook $method: ${t.message}")
            XposedBridge.log(t)
        }
    }

    @Volatile
    private var composeScrambleMap: IntArray = (0..9).shuffled().toIntArray()

    @Volatile
    private var lastPinBouncerViewModel: WeakReference<Any>? = null

    private fun reshuffleComposePin() {
        composeScrambleMap = (0..9).shuffled().toIntArray()
    }

    private fun resolvePinBouncerViewModel(origOnClicked: Any): Any? {
        lastPinBouncerViewModel?.get()?.let { return it }
        return try {
            origOnClicked.javaClass.declaredFields.firstOrNull { field ->
                field.type.name.contains("PinBouncerViewModel")
            }?.apply { isAccessible = true }?.get(origOnClicked)
        } catch (_: Throwable) {
            null
        }
    }

    private fun dispatchPinButtonClicked(vm: Any?, digit: Int) {
        if (vm == null) return
        try {
            XposedHelpers.callMethod(vm, "onPinButtonClicked", digit)
        } catch (_: Throwable) {
            try {
                val method = vm.javaClass.methods.firstOrNull { m ->
                    m.returnType == java.lang.Void.TYPE &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == java.lang.Integer.TYPE &&
                    (m.name.contains("PinButton", ignoreCase = true) || m.name.contains("Digit", ignoreCase = true))
                }
                method?.invoke(vm, digit)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] Compose PIN: error dispatching digit $digit: ${t.message}")
            }
        }
    }

    private class ComposePinProxyHandler(
        private val origOnClicked: Any,
        private val origDigit: Int,
        private val targetDigit: Int,
        private val vmResolver: (Any) -> Any?,
        private val clickDispatcher: (Any?, Int) -> Unit
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "invoke" && args != null && args.isNotEmpty()) {
                val digit = (args[0] as? Number)?.toInt() ?: targetDigit
                if (origDigit in 1..9) {
                    try {
                        val invokeMethod = origOnClicked.javaClass.methods.firstOrNull {
                            it.name == "invoke" && it.parameterTypes.size == 1
                        }
                        if (invokeMethod != null) {
                            return invokeMethod.invoke(origOnClicked, digit)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG   [LockScreen] Compose PIN: error invoking origOnClicked for digit $digit: ${t.message}")
                    }
                }
                val vm = vmResolver(origOnClicked)
                clickDispatcher(vm, digit)
                val unitInstance = try {
                    origOnClicked.javaClass.classLoader?.loadClass("kotlin.Unit")
                        ?.getField("INSTANCE")?.get(null) ?: Unit
                } catch (_: Throwable) {
                    Unit
                }
                return unitInstance
            }
            return when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ComposePinProxyHandler(orig=$origDigit, target=$targetDigit)"
                else -> null
            }
        }
    }

    private fun hookComposeScramblePin(lpparam: LoadPackageParam, prefs: Prefs) {
        val pinBouncerViewModelClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel",
            lpparam.classLoader
        )
        if (pinBouncerViewModelClass != null) {
            try {
                XposedBridge.hookAllConstructors(pinBouncerViewModelClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastPinBouncerViewModel = WeakReference(param.thisObject)
                        if (prefs.getBoolean("scramble_pin", false)) {
                            reshuffleComposePin()
                        }
                    }
                })
                val onActivatedMethods = pinBouncerViewModelClass.declaredMethods.filter { m ->
                    m.name == "onActivated" || (m.parameterTypes.size == 1 && m.parameterTypes[0].name.contains("Continuation"))
                }
                for (m in onActivatedMethods) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            lastPinBouncerViewModel = WeakReference(param.thisObject)
                            if (prefs.getBoolean("scramble_pin", false)) {
                                reshuffleComposePin()
                            }
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook PinBouncerViewModel: ${t.message}")
            }
        }

        val authBouncerViewModelClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel",
            lpparam.classLoader
        )
        if (authBouncerViewModelClass != null) {
            try {
                XposedBridge.hookAllMethods(authBouncerViewModelClass, "onFailureAnimationShown", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (prefs.getBoolean("scramble_pin", false)) {
                            reshuffleComposePin()
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook onFailureAnimationShown: ${t.message}")
            }
        }

        val pinBouncerKtClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.bouncer.ui.composable.PinBouncerKt",
            lpparam.classLoader
        )
        if (pinBouncerKtClass != null) {
            try {
                val digitButtonMethods = pinBouncerKtClass.declaredMethods.filter { m ->
                    Modifier.isStatic(m.modifiers) &&
                    m.name.startsWith("DigitButton") &&
                    !m.name.contains("$") &&
                    m.returnType == java.lang.Void.TYPE &&
                    m.parameterTypes.size >= 8 &&
                    m.parameterTypes[0] == java.lang.Integer.TYPE &&
                    m.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[2].name.endsWith("Function1")
                }
                for (m in digitButtonMethods) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean("scramble_pin", false)) return
                            val onClicked = param.args.getOrNull(2) ?: return
                            if (Proxy.isProxyClass(onClicked.javaClass)) {
                                val handler = Proxy.getInvocationHandler(onClicked)
                                if (handler is ComposePinProxyHandler) {
                                    return
                                }
                            }

                            val origDigit = param.args[0] as? Int ?: return
                            if (origDigit !in 0..9) return

                            val slotIndex = if (origDigit == 0) 9 else origDigit - 1
                            val newDigit = composeScrambleMap.getOrNull(slotIndex) ?: origDigit

                            param.args[0] = newDigit

                            val function1Class = try {
                                onClicked.javaClass.classLoader?.loadClass("kotlin.jvm.functions.Function1")
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val proxy = Proxy.newProxyInstance(
                                onClicked.javaClass.classLoader,
                                arrayOf(function1Class),
                                ComposePinProxyHandler(
                                    origOnClicked = onClicked,
                                    origDigit = origDigit,
                                    targetDigit = newDigit,
                                    vmResolver = ::resolvePinBouncerViewModel,
                                    clickDispatcher = ::dispatchPinButtonClicked
                                )
                            )
                            param.args[2] = proxy
                        }
                    })
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook PinBouncerKt DigitButton: ${t.message}")
            }
        }
    }
}
