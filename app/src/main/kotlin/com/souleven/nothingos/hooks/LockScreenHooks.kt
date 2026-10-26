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
import java.util.Locale

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"
    private var lastIndicationController: Any? = null
    private var lastRotateController: Any? = null
    private var isCharging = false
    private var isUpdatingInPlace = false
    private var powerManager: PowerManager? = null
    private var lastDozeTapTime: Long = 0L
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
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
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
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
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
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
                                val pm = (XposedHelpers.getObjectField(param.thisObject, "powerManager") as? PowerManager)
                                    ?: powerManager
                                if (pm != null) {
                                    try {
                                        XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 4, 0)
                                    } catch (_: Throwable) {
                                        try {
                                            XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis())
                                        } catch (_: Throwable) {}
                                    }
                                }
                                param.result = null
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
                            if (prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
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
                            val ssc = XposedHelpers.getObjectField(param.thisObject, "statusBarStateController")
                            val isDozing = try {
                                XposedHelpers.callMethod(ssc, "isDozing") as? Boolean ?: false
                            } catch (_: Throwable) { false }
                            val state = try {
                                XposedHelpers.callMethod(ssc, "getState") as? Int ?: -1
                            } catch (_: Throwable) { -1 }

                            // Double tap to sleep on lockscreen
                            if (!isDozing && state == 1 && !isBouncerShowing(lastIndicationController) && prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
                                val falsingManager = XposedHelpers.getObjectField(param.thisObject, "falsingManager")
                                val isFalseTap = try {
                                    XposedHelpers.callMethod(falsingManager, "isFalseDoubleTap") as? Boolean ?: false
                                } catch (_: Throwable) { false }
                                if (!isFalseTap) {
                                    val pm = powerManager ?: run {
                                        val ac = try { XposedHelpers.getObjectField(param.thisObject, "ambientDisplayConfiguration") } catch (_: Throwable) { null }
                                        val ctx = try { XposedHelpers.getObjectField(ac, "mContext") as? Context } catch (_: Throwable) { null }
                                        ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
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
                                    param.result = true
                                    return
                                }
                            }

                            // Double tap to wake while in AOD/dozing
                            if (isDozing && prefs.getBoolean("pref_lockscreen_double_tap_to_wake", false)) {
                                val falsingManager = XposedHelpers.getObjectField(param.thisObject, "falsingManager")
                                val isProxNear = try {
                                    XposedHelpers.callMethod(falsingManager, "isProximityNear") as? Boolean ?: false
                                } catch (_: Throwable) { false }
                                if (!isProxNear) {
                                    val powerInteractor = try { XposedHelpers.getObjectField(param.thisObject, "powerInteractor") } catch (_: Throwable) { null }
                                    if (powerInteractor != null) {
                                        try {
                                            XposedHelpers.callMethod(powerInteractor, "wakeUpIfDozing", "PULSING_DOUBLE_TAP", 15)
                                        } catch (_: Throwable) {}
                                    }
                                    val pm = powerManager ?: run {
                                        val ac = try { XposedHelpers.getObjectField(param.thisObject, "ambientDisplayConfiguration") } catch (_: Throwable) { null }
                                        val ctx = try { XposedHelpers.getObjectField(ac, "mContext") as? Context } catch (_: Throwable) { null }
                                        ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                    }
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis(), 15, "PULSING_DOUBLE_TAP")
                                        } catch (_: Throwable) {}
                                    }
                                    param.result = true
                                    return
                                }
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
                                if (diff in 80..800) {
                                    // double tap: wake device straight to lockscreen
                                    lastDozeTapTime = 0L

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

                                    val pm = powerManager ?: run {
                                        val ctx = try { XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context } catch (_: Throwable) { null }
                                        ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                    }
                                    pm?.let {
                                        try {
                                            XposedHelpers.callMethod(it, "wakeUp", SystemClock.uptimeMillis(), 15, "DOUBLE_TAP_TO_WAKE")
                                        } catch (_: Throwable) {}
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
}
