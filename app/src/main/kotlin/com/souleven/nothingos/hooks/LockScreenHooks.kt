package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.Locale

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"
    private var keyguardRootViewRef: WeakReference<ViewGroup>? = null
    private var chargingInfoView: TextView? = null
    private var batteryUpdateRunnable: Runnable? = null
    private var powerManager: PowerManager? = null
    private var isBouncerVisible: Boolean = false
    private var lastDozeTapTime: Long = 0L

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
                            keyguardRootViewRef = WeakReference(root)
                            powerManager = root.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            setupHideClock(root, prefs)
                            setupChargingInfoView(root)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [LockScreen] Error in setup KeyguardRootView: ${t.message}")
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook KeyguardRootView: ${t.message}")
            }
        }

        val controllerClass = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.KeyguardIndicationController", lpparam.classLoader)
        if (controllerClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "setIndicationArea",
                    ViewGroup::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            updateChargingInfo(param.thisObject, prefs, lpparam.classLoader)
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook setIndicationArea: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "setIndicationTextColor",
                    ColorStateList::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val colors = param.args[0] as? ColorStateList ?: return
                            chargingInfoView?.setTextColor(colors)
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "setVisible",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val visible = param.args[0] as? Boolean ?: return
                            if (visible) {
                                updateChargingInfo(param.thisObject, prefs, lpparam.classLoader)
                            } else {
                                chargingInfoView?.visibility = View.GONE
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "updateLockScreenBatteryMsg",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            updateChargingInfo(param.thisObject, prefs, lpparam.classLoader)
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook updateLockScreenBatteryMsg: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "updateDeviceEntryIndication",
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            updateChargingInfo(param.thisObject, prefs, lpparam.classLoader)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val securityContainerClass = XposedHelpers.findClassIfExists("com.android.keyguard.KeyguardSecurityContainer", lpparam.classLoader)
        if (securityContainerClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    securityContainerClass,
                    "setVisibility",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val vis = param.args[0] as? Int ?: return
                            isBouncerVisible = (vis == View.VISIBLE)
                        }
                    }
                )
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
                            if (!isDozing && state == 1 && !isBouncerVisible && prefs.getBoolean("pref_lockscreen_double_tap_to_sleep", false)) {
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

    private fun isPluggedIn(controller: Any, context: Context): Boolean {
        try {
            if (XposedHelpers.getBooleanField(controller, "mPowerPluggedIn")) return true
        } catch (_: Throwable) {}
        try {
            if (XposedHelpers.getBooleanField(controller, "mIsReallyPluggedIn")) return true
        } catch (_: Throwable) {}
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null && bm.isCharging) return true
        } catch (_: Throwable) {}
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (plugged > 0) return true
        } catch (_: Throwable) {}
        return false
    }

    private fun getRootView(controller: Any?): ViewGroup? {
        keyguardRootViewRef?.get()?.let { return it }
        if (controller != null) {
            val area = try { XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup } catch (_: Throwable) { null }
            var p = area?.parent
            while (p != null) {
                if (p.javaClass.name.endsWith("KeyguardRootView")) {
                    val kr = p as ViewGroup
                    keyguardRootViewRef = WeakReference(kr)
                    return kr
                }
                p = p.parent
            }
            val root = area?.rootView as? ViewGroup
            if (root != null) {
                val resId = root.context.resources.getIdentifier("keyguard_root_view", "id", "com.android.systemui")
                if (resId != 0) {
                    val kr = root.findViewById<ViewGroup>(resId)
                    if (kr != null) {
                        keyguardRootViewRef = WeakReference(kr)
                        return kr
                    }
                }
                if (root.javaClass.name.endsWith("KeyguardRootView")) {
                    keyguardRootViewRef = WeakReference(root)
                    return root
                }
            }
        }
        return null
    }

    private fun setupChargingInfoView(root: ViewGroup) {
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                try {
                    val tv = chargingInfoView
                    if (tv != null && tv.visibility == View.VISIBLE && tv.height > 0) {
                        updateChargingViewPosition(root, tv)
                    }
                } catch (_: Throwable) {}
                return true
            }
        })
    }

    private fun updateChargingViewPosition(root: ViewGroup, tv: TextView) {
        if (tv.visibility != View.VISIBLE || tv.height == 0) return

        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            root.resources.displayMetrics
        )

        // if fingerprint icon (or device entry lock icon) is visible on screen, position 10dp above it
        val resIdFp = root.context.resources.getIdentifier("device_entry_icon_view", "id", "com.android.systemui")
        val fpView = if (resIdFp != 0) root.findViewById<View>(resIdFp) else null

        val targetY: Float = if (fpView != null && fpView.visibility == View.VISIBLE && fpView.top > 0) {
            fpView.y - tv.height - paddingPx
        } else {
            // position 10dp above the indication area so it never collides with the gesture bar
            val resIdIndication = root.context.resources.getIdentifier("keyguard_indication_area", "id", "com.android.systemui")
            val indicationArea = if (resIdIndication != 0) root.findViewById<View>(resIdIndication) else null
            if (indicationArea != null && indicationArea.visibility == View.VISIBLE && indicationArea.top > 0) {
                indicationArea.y - tv.height - paddingPx
            } else {
                // fallback: safe distance above the bottom of the screen (well above gesture bar)
                val baseBottomOffset = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    72f,
                    root.resources.displayMetrics
                )
                root.height - tv.height - baseBottomOffset
            }
        }

        if (kotlin.math.abs(tv.y - targetY) > 1f) {
            tv.y = targetY
        }
    }

    private fun updateChargingInfo(controller: Any, prefs: Prefs, classLoader: ClassLoader) {
        try {
            if (!prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                chargingInfoView?.visibility = View.GONE
                return
            }

            val context = XposedHelpers.getObjectField(controller, "mContext") as? Context ?: return
            val visible = try { XposedHelpers.getBooleanField(controller, "mVisible") } catch (_: Throwable) { true }
            val dozing = try { XposedHelpers.getBooleanField(controller, "mDozing") } catch (_: Throwable) { false }
            val plugged = isPluggedIn(controller, context)

            val root = getRootView(controller)
            if (plugged && visible && !dozing && !isBouncerVisible && root != null) {
                val view = ensureChargingView(root, prefs)
                if (view != null) {
                    val lockView = try { XposedHelpers.getObjectField(controller, "mLockScreenIndicationView") as? TextView } catch (_: Throwable) { null }
                    if (lockView != null) {
                        view.setTextColor(lockView.textColors)
                        view.typeface = lockView.typeface
                    } else {
                        val colors = try { XposedHelpers.getObjectField(controller, "mInitialTextColorState") as? ColorStateList } catch (_: Throwable) { null }
                        if (colors != null) view.setTextColor(colors)
                        else view.setTextColor(Color.WHITE)
                    }
                    val extra = getChargingExtraInfo(context, classLoader)
                    if (extra.isNotEmpty()) {
                        view.text = extra
                        view.visibility = View.VISIBLE
                        updateChargingViewPosition(root, view)
                    } else {
                        view.visibility = View.GONE
                    }
                }
                val handler = XposedHelpers.getObjectField(controller, "mHandler") as? Handler
                if (handler != null) scheduleBatteryMsgUpdate(controller, handler, prefs, classLoader)
            } else {
                chargingInfoView?.visibility = View.GONE
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] Error in updateChargingInfo: ${t.message}")
        }
    }

    private fun ensureChargingView(root: ViewGroup, prefs: Prefs): TextView? {
        if (!prefs.getBoolean("pref_lockscreen_charging_info", false)) {
            chargingInfoView?.visibility = View.GONE
            return null
        }
        val existing = root.findViewWithTag<TextView>("nt_lockscreen_charging_info_view")
        if (existing != null) {
            chargingInfoView = existing
            return existing
        }

        val context = root.context
        val tv = TextView(context).apply {
            tag = "nt_lockscreen_charging_info_view"
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            alpha = 0.9f
            visibility = View.GONE
            setTextColor(Color.WHITE)
            isClickable = false
            isFocusable = false
            isLongClickable = false

            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateChargingViewPosition(root, this)
            }
        }

        val lp = try {
            val lpClass = root.javaClass.classLoader?.loadClass("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams")
            val ctor = lpClass?.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val p = ctor?.newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (p != null) {
                try {
                    XposedHelpers.setIntField(p, "startToStart", 0)
                    XposedHelpers.setIntField(p, "endToEnd", 0)
                } catch (_: Throwable) {}
            }
            p as? ViewGroup.LayoutParams ?: ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (_: Throwable) {
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        try {
            root.addView(tv, lp)
            chargingInfoView = tv
        } catch (_: Throwable) {
            return null
        }
        return tv
    }

    private fun scheduleBatteryMsgUpdate(controller: Any, handler: Handler, prefs: Prefs, classLoader: ClassLoader) {
        if (batteryUpdateRunnable != null) return
        batteryUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    if (!prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                        chargingInfoView?.visibility = View.GONE
                        batteryUpdateRunnable = null
                        return
                    }
                    val context = XposedHelpers.getObjectField(controller, "mContext") as? Context
                    val visible = try { XposedHelpers.getBooleanField(controller, "mVisible") } catch (_: Throwable) { true }
                    val dozing = try { XposedHelpers.getBooleanField(controller, "mDozing") } catch (_: Throwable) { false }
                    val plugged = if (context != null) isPluggedIn(controller, context) else XposedHelpers.getBooleanField(controller, "mPowerPluggedIn")

                    val root = getRootView(controller)
                    if (plugged && visible && !dozing && !isBouncerVisible && root != null) {
                        val view = ensureChargingView(root, prefs)
                        if (view != null && context != null) {
                            val lockView = try { XposedHelpers.getObjectField(controller, "mLockScreenIndicationView") as? TextView } catch (_: Throwable) { null }
                            if (lockView != null) {
                                view.setTextColor(lockView.textColors)
                                view.typeface = lockView.typeface
                            }
                            val extra = getChargingExtraInfo(context, classLoader)
                            if (extra.isNotEmpty()) {
                                view.text = extra
                                view.visibility = View.VISIBLE
                                updateChargingViewPosition(root, view)
                            } else {
                                view.visibility = View.GONE
                            }
                        }
                        handler.postDelayed(this, 2000L)
                    } else {
                        chargingInfoView?.visibility = View.GONE
                        batteryUpdateRunnable = null
                    }
                } catch (_: Throwable) {
                    batteryUpdateRunnable = null
                }
            }
        }
        handler.postDelayed(batteryUpdateRunnable!!, 2000L)
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
