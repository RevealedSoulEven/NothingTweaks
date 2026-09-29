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
import java.util.Locale

class LockScreenHooks : HookModule {

    private val className = "com.nothing.keyguard.KeyguardSecurityContainerControllerEx"
    private var chargingInfoView: TextView? = null
    private var batteryUpdateRunnable: Runnable? = null
    private var powerManager: PowerManager? = null
    private var isBouncerVisible: Boolean = false

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
                            setupHideClock(root, prefs)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG   [LockScreen] Error in setupHideClock: ${t.message}")
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
                            val area = param.args[0] as? ViewGroup ?: return
                            ensureChargingView(param.thisObject, area, prefs)
                            updateChargingInfo(param.thisObject, area, prefs, lpparam.classLoader)
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
                                updateChargingInfo(param.thisObject, null, prefs, lpparam.classLoader)
                            } else {
                                chargingInfoView?.visibility = View.GONE
                                val area = XposedHelpers.getObjectField(param.thisObject, "mIndicationArea") as? ViewGroup
                                area?.translationY = 0f
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
                            updateChargingInfo(param.thisObject, null, prefs, lpparam.classLoader)
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
                            updateChargingInfo(param.thisObject, null, prefs, lpparam.classLoader)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        val indicationAreaClass = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.ui.view.KeyguardIndicationArea", lpparam.classLoader)
        if (indicationAreaClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    indicationAreaClass,
                    "setTranslationY",
                    Float::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val tv = chargingInfoView
                            if (tv != null && tv.visibility == View.VISIBLE && tv.height > 0) {
                                val orig = param.args[0] as Float
                                param.args[0] = orig + (tv.height * 0.35f)
                            }
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
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG   [LockScreen] FAILED to hook PulsingGestureListener: ${t.message}")
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

    private fun updateChargingInfo(controller: Any, indicationArea: ViewGroup?, prefs: Prefs, classLoader: ClassLoader) {
        try {
            if (!prefs.getBoolean("pref_lockscreen_charging_info", false)) {
                chargingInfoView?.visibility = View.GONE
                val area = indicationArea ?: (XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup)
                area?.translationY = 0f
                return
            }

            val context = XposedHelpers.getObjectField(controller, "mContext") as? Context ?: return
            val visible = try { XposedHelpers.getBooleanField(controller, "mVisible") } catch (_: Throwable) { true }
            val dozing = try { XposedHelpers.getBooleanField(controller, "mDozing") } catch (_: Throwable) { false }
            val plugged = isPluggedIn(controller, context)

            val area = indicationArea ?: (XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup)
            if (plugged && visible && !dozing) {
                val view = ensureChargingView(controller, area, prefs)
                if (view != null) {
                    val lockView = XposedHelpers.getObjectField(controller, "mLockScreenIndicationView") as? TextView
                    if (lockView != null) {
                        view.setTextColor(lockView.textColors)
                        view.typeface = lockView.typeface
                    }
                    val extra = getChargingExtraInfo(context, classLoader)
                    if (extra.isNotEmpty()) {
                        view.text = extra
                        view.visibility = View.VISIBLE
                        if (view.height > 0) {
                            area?.translationY = (view.height * 0.35f)
                        }
                    } else {
                        view.visibility = View.GONE
                        area?.translationY = 0f
                    }
                }
                val handler = XposedHelpers.getObjectField(controller, "mHandler") as? Handler
                if (handler != null) scheduleBatteryMsgUpdate(controller, handler, prefs, classLoader)
            } else {
                chargingInfoView?.visibility = View.GONE
                area?.translationY = 0f
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG   [LockScreen] Error in updateChargingInfo: ${t.message}")
        }
    }

    private fun ensureChargingView(controller: Any, indicationArea: ViewGroup?, prefs: Prefs): TextView? {
        if (!prefs.getBoolean("pref_lockscreen_charging_info", false)) {
            chargingInfoView?.visibility = View.GONE
            val area = indicationArea ?: (XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup)
            area?.translationY = 0f
            return null
        }
        val area = indicationArea ?: (XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup) ?: return null
        val existing = area.findViewWithTag<TextView>("nt_lockscreen_charging_info_view")
        if (existing != null) {
            chargingInfoView = existing
            return existing
        }

        val context = area.context
        val lockView = XposedHelpers.getObjectField(controller, "mLockScreenIndicationView") as? TextView

        val tv = TextView(context).apply {
            tag = "nt_lockscreen_charging_info_view"
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            alpha = 0.85f
            visibility = View.GONE

            if (lockView != null) {
                typeface = lockView.typeface
                setTextColor(lockView.textColors)
            } else {
                val colors = XposedHelpers.getObjectField(controller, "mInitialTextColorState") as? ColorStateList
                if (colors != null) setTextColor(colors)
                else setTextColor(Color.WHITE)
            }

            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (visibility == View.VISIBLE && h > 0) {
                    area.translationY = (h * 0.35f)
                } else if (visibility != View.VISIBLE) {
                    area.translationY = 0f
                }
            }
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val targetIndex = if (lockView != null) {
            val idx = area.indexOfChild(lockView)
            if (idx >= 0) idx + 1 else area.childCount
        } else {
            area.childCount
        }

        try {
            area.addView(tv, targetIndex, lp)
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
                        val area = XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup
                        area?.translationY = 0f
                        batteryUpdateRunnable = null
                        return
                    }
                    val context = XposedHelpers.getObjectField(controller, "mContext") as? Context
                    val visible = try { XposedHelpers.getBooleanField(controller, "mVisible") } catch (_: Throwable) { true }
                    val dozing = try { XposedHelpers.getBooleanField(controller, "mDozing") } catch (_: Throwable) { false }
                    val plugged = if (context != null) isPluggedIn(controller, context) else XposedHelpers.getBooleanField(controller, "mPowerPluggedIn")

                    if (plugged && visible && !dozing) {
                        val view = ensureChargingView(controller, null, prefs)
                        val area = XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup
                        if (view != null && context != null) {
                            val lockView = XposedHelpers.getObjectField(controller, "mLockScreenIndicationView") as? TextView
                            if (lockView != null) {
                                view.setTextColor(lockView.textColors)
                                view.typeface = lockView.typeface
                            }
                            val extra = getChargingExtraInfo(context, classLoader)
                            if (extra.isNotEmpty()) {
                                view.text = extra
                                view.visibility = View.VISIBLE
                                if (view.height > 0) {
                                    area?.translationY = (view.height * 0.35f)
                                }
                            } else {
                                view.visibility = View.GONE
                                area?.translationY = 0f
                            }
                        }
                        handler.postDelayed(this, 2000L)
                    } else {
                        chargingInfoView?.visibility = View.GONE
                        val area = XposedHelpers.getObjectField(controller, "mIndicationArea") as? ViewGroup
                        area?.translationY = 0f
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
