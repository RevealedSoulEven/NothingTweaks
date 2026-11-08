package com.souleven.nothingos.hooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections
import java.util.WeakHashMap

class StatusBarIconHooks : HookModule {

    companion object {
        const val TAG = "NothingTweaks:StatusBarIcons"

        const val PREF_HIDE_STATUSBAR_CLOCK = "pref_hide_statusbar_clock"
        const val PREF_HIDE_SILENT_ICON = "pref_hide_silent_icon"
        const val PREF_HIDE_NETWORK_TYPE = "pref_hide_network_type"
        const val PREF_HIDE_SIM1 = "pref_hide_sim1"
        const val PREF_HIDE_SIM2 = "pref_hide_sim2"
        const val PREF_HIDE_DUAL_SIM = "pref_hide_dual_sim"
        const val PREF_HIDE_ALARM_ICON = "pref_hide_alarm_icon"

        val ALL_KEYS = setOf(
            PREF_HIDE_STATUSBAR_CLOCK,
            PREF_HIDE_SILENT_ICON,
            PREF_HIDE_NETWORK_TYPE,
            PREF_HIDE_SIM1,
            PREF_HIDE_SIM2,
            PREF_HIDE_DUAL_SIM,
            PREF_HIDE_ALARM_ICON
        )

        private val SILENT_SLOTS = setOf(
            "volume",
            "mute",
            "silent",
            "ringer_silent",
            "zen_muted",
            "status_bar_mute",
            "status_bar_volume"
        )

        private val ALARM_SLOTS = setOf(
            "alarm_clock",
            "alarm",
            "status_bar_alarm_clock"
        )

        private const val CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock"
        private const val CLASS_STATUS_BAR_ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView"
        private const val CLASS_STATUS_ICON_CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer"
        private const val CLASS_MODERN_MOBILE_VIEW = "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"
        private const val CLASS_MODERN_VIEW = "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
        private const val CLASS_MODERN_SHADE_MOBILE_VIEW = "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView"
        private const val CLASS_SHADE_CARRIER_GROUP = "com.android.systemui.shade.carrier.ShadeCarrierGroup"
        private const val CLASS_STACKED_MOBILE_SUPPRESSOR =
            "com.nothing.systemui.statusbar.pipeline.mobile.ui.NTStackedMobileTypeSuppressor"
        private const val CLASS_MOBILE_TYPE_ANIMATOR =
            "com.nothing.systemui.statusbar.pipeline.mobile.ui.binder.NTMobileTypeAnimator"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val clockViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val clockHiddenByUs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    private val iconViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    private val mobileViews: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val mobileViewsHiddenByUs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val mobileTypeHiddenByUs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var suppressorClassRef: Class<*>? = null
    @Volatile
    private var animatorClassRef: Class<*>? = null

    private val carrierGroups: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())
    private val carrierGroupsModified: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    private val subIdMap: MutableMap<View, Int> = Collections.synchronizedMap(WeakHashMap())
    private val listenersAttached: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.android.systemui") return
        val classLoader = lpparam.classLoader

        hookClock(classLoader, prefs)
        hookStatusBarIconViews(classLoader, prefs)
        hookStatusIconContainer(classLoader)
        hookModernMobileView(classLoader, prefs)
        hookShadeCarrierGroup(classLoader, prefs)

        // realtime update listener when user toggles any switch
        prefs.addListener {
            mainHandler.post {
                applyAll(prefs)
            }
        }
    }

    private fun hookClock(classLoader: ClassLoader, prefs: Prefs) {
        val clockClass = XposedHelpers.findClassIfExists(CLASS_CLOCK, classLoader) ?: return

        try {
            XposedHelpers.findAndHookMethod(clockClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    clockViews.add(view)
                    applyClock(view, prefs)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Clock.onAttachedToWindow: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(clockClass, "updateClockVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_HIDE_STATUSBAR_CLOCK, false)) {
                        (param.thisObject as? View)?.visibility = View.GONE
                        param.result = null
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Clock.updateClockVisibility: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(clockClass, "shouldBeVisible", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_HIDE_STATUSBAR_CLOCK, false)) {
                        param.result = false
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Clock.shouldBeVisible: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(clockClass, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(PREF_HIDE_STATUSBAR_CLOCK, false)) {
                        param.args[0] = View.GONE
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook Clock.setVisibility: ${t.message}")
        }
    }

    private fun isSilentSlot(slot: String?): Boolean {
        if (slot == null) return false
        val lower = slot.lowercase()
        if (lower in SILENT_SLOTS) return true
        return lower.contains("mute") || lower.contains("silent")
    }

    private fun isAlarmSlot(slot: String?): Boolean {
        if (slot == null) return false
        val lower = slot.lowercase()
        if (lower in ALARM_SLOTS) return true
        return lower.contains("alarm")
    }

    private fun getSlotFromIconView(iconView: Any): String? {
        return try {
            XposedHelpers.getObjectField(iconView, "mSlot") as? String
                ?: XposedHelpers.callMethod(iconView, "getSlot") as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun isViewNativelyBlocked(view: View): Boolean {
        return try {
            XposedHelpers.getBooleanField(view, "mBlocked")
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCollapsedStatusBarView(view: View): Boolean {
        var p = view.parent
        while (p != null) {
            if (p is View && p.javaClass.name.contains("PhoneStatusBarView")) {
                return true
            }
            p = p.parent
        }
        return false
    }

    private fun isViewIconVisible(view: View): Boolean {
        return try {
            val icon = XposedHelpers.getObjectField(view, "mIcon")
                ?: XposedHelpers.getObjectField(view, "mStatusBarIcon")
            if (icon != null) {
                XposedHelpers.getBooleanField(icon, "visible")
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun hookStatusBarIconViews(classLoader: ClassLoader, prefs: Prefs) {
        val iconViewClass = XposedHelpers.findClassIfExists(CLASS_STATUS_BAR_ICON_VIEW, classLoader) ?: return

        // hook constructors to register views
        try {
            XposedBridge.hookAllConstructors(iconViewClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    iconViews.add(view)
                    if (listenersAttached.add(view)) {
                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                applyIconView(v, prefs)
                            }
                            override fun onViewDetachedFromWindow(v: View) {}
                        })
                    }
                    applyIconView(view, prefs)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusBarIconView constructors: ${t.message}")
        }

        // hook set(StatusBarIcon)
        try {
            XposedBridge.hookAllMethods(iconViewClass, "set", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    applyIconView(view, prefs)
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusBarIconView.set: ${t.message}")
        }

        // hook setVisibility - ONLY intercept when the respective feature is active
        try {
            XposedBridge.hookAllMethods(iconViewClass, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val slot = getSlotFromIconView(view) ?: return
                    val hideSilent = prefs.getBoolean(PREF_HIDE_SILENT_ICON, false)
                    val hideAlarm = prefs.getBoolean(PREF_HIDE_ALARM_ICON, false)

                    if (hideAlarm && isAlarmSlot(slot)) {
                        param.args[0] = View.GONE
                    } else if (hideSilent && isSilentSlot(slot)) {
                        param.args[0] = View.GONE
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusBarIconView.setVisibility: ${t.message}")
        }

        // hook isIconBlocked - ONLY intercept when the respective feature is active
        try {
            XposedBridge.hookAllMethods(iconViewClass, "isIconBlocked", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val slot = getSlotFromIconView(view) ?: return
                    val hideSilent = prefs.getBoolean(PREF_HIDE_SILENT_ICON, false)
                    val hideAlarm = prefs.getBoolean(PREF_HIDE_ALARM_ICON, false)

                    if (hideAlarm && isAlarmSlot(slot)) {
                        param.result = true
                    } else if (hideSilent && isSilentSlot(slot)) {
                        param.result = true
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusBarIconView.isIconBlocked: ${t.message}")
        }

        // hook isIconVisible - ONLY intercept when the respective feature is active
        try {
            XposedBridge.hookAllMethods(iconViewClass, "isIconVisible", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val slot = getSlotFromIconView(view) ?: return
                    val hideSilent = prefs.getBoolean(PREF_HIDE_SILENT_ICON, false)
                    val hideAlarm = prefs.getBoolean(PREF_HIDE_ALARM_ICON, false)

                    if (hideAlarm && isAlarmSlot(slot)) {
                        param.result = false
                    } else if (hideSilent && isSilentSlot(slot)) {
                        param.result = false
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusBarIconView.isIconVisible: ${t.message}")
        }
    }

    /**
     * prevents StatusIconContainer from reserving layout width or spacing for views that are GONE
     * this eliminates large empty gaps in the expanded QS panel when network indicators or other icons are hidden
     */
    private fun hookStatusIconContainer(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClassIfExists(CLASS_STATUS_ICON_CONTAINER, classLoader) ?: return

        try {
            XposedHelpers.findAndHookMethod(containerClass, "getViewTotalWidth", View::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args[0] as? View ?: return
                    if (view.visibility == View.GONE) {
                        param.result = 0
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook StatusIconContainer.getViewTotalWidth: ${t.message}")
        }
    }

    private fun hookModernMobileView(classLoader: ClassLoader, prefs: Prefs) {
        val mobileViewClass = XposedHelpers.findClassIfExists(CLASS_MODERN_MOBILE_VIEW, classLoader)
        val modernViewClass = XposedHelpers.findClassIfExists(CLASS_MODERN_VIEW, classLoader)
        val shadeMobileClass = XposedHelpers.findClassIfExists(CLASS_MODERN_SHADE_MOBILE_VIEW, classLoader)

        suppressorClassRef = XposedHelpers.findClassIfExists(CLASS_STACKED_MOBILE_SUPPRESSOR, classLoader)
        animatorClassRef = XposedHelpers.findClassIfExists(CLASS_MOBILE_TYPE_ANIMATOR, classLoader)

        // hook isIconVisible on ModernStatusBarView so StatusIconContainer skips hidden mobile views
        if (modernViewClass != null) {
            try {
                XposedBridge.hookAllMethods(modernViewClass, "isIconVisible", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        if (shouldHideMobileView(view, prefs)) {
                            param.result = false
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to hook ModernStatusBarView.isIconVisible: ${t.message}")
            }

            try {
                XposedBridge.hookAllMethods(modernViewClass, "initView", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        val slot = (param.args.getOrNull(0) as? String) ?: ""
                        if (!slot.lowercase().contains("satellite")) {
                            registerMobileView(view, prefs)
                        }
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to hook ModernStatusBarView.initView: ${t.message}")
            }
        }

        if (mobileViewClass != null) {
            try {
                XposedHelpers.findAndHookMethod(mobileViewClass, "setSubId", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        val subId = param.args[0] as? Int ?: -1
                        if (subId >= 0) {
                            subIdMap[view] = subId
                        }
                        registerMobileView(view, prefs)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to hook ModernStatusBarMobileView.setSubId: ${t.message}")
            }
        }

        if (shadeMobileClass != null) {
            try {
                XposedHelpers.findAndHookMethod(shadeMobileClass, "setSubId", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        val subId = param.args[0] as? Int ?: -1
                        if (subId >= 0) {
                            subIdMap[view] = subId
                        }
                        registerMobileView(view, prefs)
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun hookShadeCarrierGroup(classLoader: ClassLoader, prefs: Prefs) {
        val carrierGroupClass = XposedHelpers.findClassIfExists(CLASS_SHADE_CARRIER_GROUP, classLoader) ?: return

        try {
            XposedBridge.hookAllConstructors(carrierGroupClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val group = param.thisObject as? ViewGroup ?: return
                    carrierGroups.add(group)
                    applyCarrierGroup(group, prefs)
                }
            })
        } catch (_: Throwable) {}
    }

    private fun registerMobileView(view: ViewGroup, prefs: Prefs) {
        mobileViews.add(view)
        if (listenersAttached.add(view)) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val vg = v as? ViewGroup ?: return
                    mobileViews.add(vg)
                    applyMobileView(vg, prefs)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
        applyMobileView(view, prefs)
    }

    private fun getSimSlotIndex(view: View): Int {
        val subId = subIdMap[view] ?: try {
            XposedHelpers.callMethod(view, "getSubId") as? Int
        } catch (_: Throwable) { null } ?: -1

        if (subId < 0) return -1

        return try {
            val sm = view.context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            var slot = -1
            if (sm != null) {
                val info = sm.getActiveSubscriptionInfo(subId)
                if (info != null) {
                    slot = info.simSlotIndex
                }
                if (slot < 0) {
                    slot = SubscriptionManager.getSlotIndex(subId)
                }
                if (slot < 0) {
                    val list = sm.activeSubscriptionInfoList
                    if (list != null) {
                        for (si in list) {
                            if (si.subscriptionId == subId) {
                                slot = si.simSlotIndex
                                break
                            }
                        }
                    }
                }
            }
            slot
        } catch (_: Throwable) {
            -1
        }
    }

    private fun shouldHideMobileView(view: ViewGroup, prefs: Prefs): Boolean {
        // NOS 5 Compose dual SIM indicator
        if (view.javaClass.name.contains("Compose") || view.javaClass.name.contains("Stacked")) {
            val hideDualSim = prefs.getBoolean(PREF_HIDE_DUAL_SIM, false)
            val hideSim1 = prefs.getBoolean(PREF_HIDE_SIM1, false)
            val hideSim2 = prefs.getBoolean(PREF_HIDE_SIM2, false)
            return hideDualSim || (hideSim1 && hideSim2)
        }

        val hideSim1 = prefs.getBoolean(PREF_HIDE_SIM1, false)
        val hideSim2 = prefs.getBoolean(PREF_HIDE_SIM2, false)
        if (!hideSim1 && !hideSim2) return false

        val slot = getSimSlotIndex(view)
        if (slot == 0) return hideSim1
        if (slot == 1) return hideSim2

        // fallback: order of subIds / views among all registered mobile views
        val subId = subIdMap[view] ?: try {
            XposedHelpers.callMethod(view, "getSubId") as? Int
        } catch (_: Throwable) { null } ?: -1

        val allSubIds = mobileViews.mapNotNull { v ->
            subIdMap[v] ?: try { XposedHelpers.callMethod(v, "getSubId") as? Int } catch (_: Throwable) { null }
        }.filter { it >= 0 }.distinct().sorted()

        val subIndex = allSubIds.indexOf(subId)
        if (subIndex == 0) return hideSim1
        if (subIndex == 1) return hideSim2

        val attachedList = mobileViews.toList().filter { it.isAttachedToWindow }
        val viewList = if (attachedList.isNotEmpty()) attachedList else mobileViews.toList()
        val index = viewList.indexOf(view)
        if (index == 0) return hideSim1
        if (index == 1) return hideSim2

        return false
    }

    private fun applyClock(view: View, prefs: Prefs) {
        val hideClock = prefs.getBoolean(PREF_HIDE_STATUSBAR_CLOCK, false)
        if (hideClock) {
            clockHiddenByUs.add(view)
            view.visibility = View.GONE
        } else {
            if (clockHiddenByUs.remove(view)) {
                try {
                    XposedHelpers.callMethod(view, "updateClockVisibility")
                } catch (_: Throwable) {
                    view.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun applyIconView(view: View, prefs: Prefs) {
        val slot = getSlotFromIconView(view) ?: return

        if (isAlarmSlot(slot)) {
            val hideAlarm = prefs.getBoolean(PREF_HIDE_ALARM_ICON, false)
            if (hideAlarm) {
                if (view.visibility != View.GONE) {
                    view.visibility = View.GONE
                    view.requestLayout()
                }
            } else {
                // if it was blocked natively by SystemUI (e.g. collapsed status bar alarm), keep it GONE
                val blocked = isViewNativelyBlocked(view) || isCollapsedStatusBarView(view)
                val targetVis = if (!blocked && isViewIconVisible(view)) View.VISIBLE else View.GONE
                if (view.visibility != targetVis) {
                    view.visibility = targetVis
                    view.requestLayout()
                }
            }
        } else if (isSilentSlot(slot)) {
            val hideSilent = prefs.getBoolean(PREF_HIDE_SILENT_ICON, false)
            if (hideSilent) {
                if (view.visibility != View.GONE) {
                    view.visibility = View.GONE
                    view.requestLayout()
                }
            } else {
                val blocked = isViewNativelyBlocked(view)
                val targetVis = if (!blocked && isViewIconVisible(view)) View.VISIBLE else View.GONE
                if (view.visibility != targetVis) {
                    view.visibility = targetVis
                    view.requestLayout()
                }
            }
        }
    }

    private fun applyCarrierGroup(group: ViewGroup, prefs: Prefs) {
        val hideSim1 = prefs.getBoolean(PREF_HIDE_SIM1, false)
        val hideSim2 = prefs.getBoolean(PREF_HIDE_SIM2, false)
        val hideDualSim = prefs.getBoolean(PREF_HIDE_DUAL_SIM, false)

        if (!hideSim1 && !hideSim2 && !hideDualSim) {
            if (carrierGroupsModified.remove(group)) {
                try {
                    val carrier1 = XposedHelpers.callMethod(group, "getCarrier1View") as? View
                    val carrier2 = XposedHelpers.callMethod(group, "getCarrier2View") as? View
                    val divider1 = XposedHelpers.callMethod(group, "getCarrierDivider1") as? View
                    group.visibility = View.VISIBLE
                    carrier1?.visibility = View.VISIBLE
                    carrier2?.visibility = View.VISIBLE
                    divider1?.visibility = View.VISIBLE
                    group.requestLayout()
                } catch (_: Throwable) {}
            }
            return
        }

        carrierGroupsModified.add(group)
        try {
            val carrier1 = XposedHelpers.callMethod(group, "getCarrier1View") as? View
            val carrier2 = XposedHelpers.callMethod(group, "getCarrier2View") as? View
            val divider1 = XposedHelpers.callMethod(group, "getCarrierDivider1") as? View

            if (hideDualSim || (hideSim1 && hideSim2)) {
                group.visibility = View.GONE
            } else {
                if (group.visibility != View.VISIBLE) group.visibility = View.VISIBLE
                if (hideSim1) {
                    carrier1?.visibility = View.GONE
                    divider1?.visibility = View.GONE
                    carrier2?.visibility = View.VISIBLE
                } else if (hideSim2) {
                    carrier2?.visibility = View.GONE
                    divider1?.visibility = View.GONE
                    carrier1?.visibility = View.VISIBLE
                }
            }
            group.requestLayout()
        } catch (_: Throwable) {}
    }

    private fun applyMobileView(view: ViewGroup, prefs: Prefs) {
        val hideSim1 = prefs.getBoolean(PREF_HIDE_SIM1, false)
        val hideSim2 = prefs.getBoolean(PREF_HIDE_SIM2, false)
        val hideDualSim = prefs.getBoolean(PREF_HIDE_DUAL_SIM, false)
        val hideType = prefs.getBoolean(PREF_HIDE_NETWORK_TYPE, false)

        val wasHidden = mobileViewsHiddenByUs.contains(view)
        val wasTypeHidden = mobileTypeHiddenByUs.isNotEmpty()

        // if all mobile preferences are OFF and we haven't touched this view, do nothing
        if (!hideSim1 && !hideSim2 && !hideDualSim && !hideType && !wasHidden && !wasTypeHidden) {
            return
        }

        // SIM 1 / SIM 2 indicator hiding
        val shouldHide = shouldHideMobileView(view, prefs)
        if (shouldHide) {
            mobileViewsHiddenByUs.add(view)
            var changed = false
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
                changed = true
            }

            // zero out dimensions so StatusIconContainer and parent layouts leave NO empty space
            val lp = view.layoutParams
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0
                lp.height = 0
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.marginStart = 0
                    lp.marginEnd = 0
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                }
                view.layoutParams = lp
                changed = true
            }
            if (view.paddingStart != 0 || view.paddingEnd != 0) {
                view.setPadding(0, 0, 0, 0)
                changed = true
            }

            if (changed) {
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
            return
        } else {
            if (mobileViewsHiddenByUs.remove(view)) {
                view.visibility = View.VISIBLE
                val lp = view.layoutParams
                if (lp != null && lp.width == 0) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = lp
                }
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
        }

        // hide/show 5G / 4G network type badge
        val res = view.context.resources
        val pkg = view.context.packageName
        val typeContainerId = res.getIdentifier("mobile_type_container", "id", pkg)
        val typeId = res.getIdentifier("mobile_type", "id", pkg)
        val typeContainer = if (typeContainerId != 0) view.findViewById<View>(typeContainerId) else null
        val typeImageView = if (typeId != 0) view.findViewById<View>(typeId) as? android.widget.ImageView else null

        val animatorClass = animatorClassRef
        if (animatorClass != null && typeImageView != null) {
            try {
                XposedHelpers.callStaticMethod(animatorClass, "setSuppressed", typeImageView, hideType)
            } catch (_: Throwable) {}
        }

        if (typeImageView != null || typeContainer != null) {
            val mainView = typeContainer ?: typeImageView!!
            if (hideType) {
                mobileTypeHiddenByUs.add(mainView)
                var changed = false
                if (typeContainer != null && typeContainer.visibility != View.GONE) {
                    typeContainer.visibility = View.GONE
                    changed = true
                }
                if (typeImageView != null && typeImageView.visibility != View.GONE) {
                    typeImageView.visibility = View.GONE
                    changed = true
                }
                val lp = (typeContainer ?: typeImageView)?.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && (lp.width != 0 || lp.marginEnd != 0)) {
                    lp.width = 0
                    lp.marginEnd = 0
                    (typeContainer ?: typeImageView)?.layoutParams = lp
                    changed = true
                }
                if (changed) {
                    view.requestLayout()
                    (view.parent as? View)?.requestLayout()
                }
            } else if (mobileTypeHiddenByUs.remove(mainView)) {
                val hasDrawable = (typeImageView?.drawable != null) || (typeContainer?.background != null)
                val targetVis = if (hasDrawable) View.VISIBLE else View.GONE
                typeContainer?.visibility = targetVis
                typeImageView?.visibility = targetVis
                val lp = (typeContainer ?: typeImageView)?.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.width == 0) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    (typeContainer ?: typeImageView)?.layoutParams = lp
                }
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            }
        }
    }

    private fun applyAll(prefs: Prefs) {
        // NOS 5 Compose dual-SIM network type suppressor
        val suppressorClass = suppressorClassRef
        if (suppressorClass != null) {
            try {
                val hideType = prefs.getBoolean(PREF_HIDE_NETWORK_TYPE, false)
                val instance = XposedHelpers.getStaticObjectField(suppressorClass, "INSTANCE") ?: return
                val state = XposedHelpers.callMethod(instance, "getSuppressed") ?: return
                XposedHelpers.callMethod(state, "setValue", hideType)
            } catch (_: Throwable) {}
        }

        // clock
        for (view in clockViews) {
            try {
                applyClock(view, prefs)
            } catch (_: Throwable) {}
        }

        // status bar icon views (Alarm, Silent)
        for (view in iconViews) {
            try {
                applyIconView(view, prefs)
            } catch (_: Throwable) {}
        }

        // mobile views (SIM 1, SIM 2, Dual SIM, 5G/4G)
        for (view in mobileViews) {
            try {
                applyMobileView(view, prefs)
            } catch (_: Throwable) {}
        }

        // shade carrier groups (expanded QS header carrier text/icons)
        for (group in carrierGroups) {
            try {
                applyCarrierGroup(group, prefs)
            } catch (_: Throwable) {}
        }
    }
}
