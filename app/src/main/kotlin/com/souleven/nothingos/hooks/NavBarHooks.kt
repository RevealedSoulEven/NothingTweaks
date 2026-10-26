package com.souleven.nothingos.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class NavBarHooks : HookModule {

    companion object {
        const val KEY_TRANSPARENT_NAV_BAR = "transparent_nav_bar"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        val stashedHandleClass = XposedHelpers.findClassIfExists("com.android.launcher3.taskbar.StashedHandleView", classLoader)
        if (stashedHandleClass != null) {
            try {
                XposedBridge.hookAllConstructors(stashedHandleClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_TRANSPARENT_NAV_BAR, false)) return
                        
                        val view = param.thisObject as? android.view.View ?: return
                        view.background = null

                        try {
                            XposedHelpers.setIntField(param.thisObject, "mStashedHandleDarkColor", 0)
                            XposedHelpers.setIntField(param.thisObject, "mStashedHandleLightColor", 0)
                        } catch (t: Throwable) {}
                    }
                })
            } catch (t: Throwable) {}
        }

        val navBarViewClasses = listOf(
            "com.android.systemui.navigationbar.views.NavigationBarView",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.statusbar.phone.NavigationBarView"
        )
        val navBarViewClass = navBarViewClasses.firstNotNullOfOrNull { className ->
            try { XposedHelpers.findClass(className, lpparam.classLoader) }
            catch (_: Throwable) { null }
        }
        
        if (navBarViewClass != null) {
            val hideHomeHandle = { view: android.view.ViewGroup ->
                if (prefs.getBoolean("pref_hide_ime_bar", false)) {
                    val handle = view.findViewById<android.view.View?>(view.resources.getIdentifier("home_handle", "id", "com.android.systemui"))
                        ?: view.findViewById<android.view.View?>(view.resources.getIdentifier("home_handle", "id", "com.android.systemui.res"))
                    if (handle != null) {
                        var changed = false
                        if (handle.visibility != android.view.View.GONE) {
                            handle.visibility = android.view.View.GONE
                            changed = true
                        }
                        if (handle.alpha != 0f) {
                            handle.alpha = 0f
                            changed = true
                        }
                        val lp = handle.layoutParams
                        if (lp != null && lp.height != 0) {
                            lp.height = 0
                            handle.layoutParams = lp
                            changed = true
                        }
                        if (changed) {
                            (handle.parent as? android.view.ViewGroup)?.requestLayout()
                        }
                    }
                }
            }

            try {
                XposedHelpers.findAndHookMethod(navBarViewClass, "updateNavButtonIcons", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? android.view.ViewGroup)?.let { hideHomeHandle(it) }
                    }
                })
            } catch (t: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(navBarViewClass, "updateStates", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? android.view.ViewGroup)?.let { hideHomeHandle(it) }
                    }
                })
            } catch (t: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(navBarViewClass, "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? android.view.ViewGroup)?.let { hideHomeHandle(it) }
                    }
                })
            } catch (t: Throwable) {}
        }
    }
}
