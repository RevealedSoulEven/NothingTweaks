package com.souleven.nothingos.hooks

import android.graphics.Rect
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ScreenshotHooks : HookModule {
    companion object {
        const val KEY_DISABLE_FLASH = "screenshot_disable_flash"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        // Verified against Nothing OS C5.0-260924-2115 (Android 17).
        // Use the existing no-flash branch so preview animations and callbacks still run.
        val controller = XposedHelpers.findClassIfExists(
            "com.android.systemui.screenshot.ui.ScreenshotAnimationController",
            lpparam.classLoader
        )
        if (controller == null) {
            XposedBridge.log("$TAG Screenshot flash controller unavailable")
            return
        }

        XposedHelpers.findAndHookMethod(
            controller,
            "getEntranceAnimation",
            Rect::class.java,
            Boolean::class.javaPrimitiveType,
            "kotlin.jvm.functions.Function0",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (prefs.getBoolean(KEY_DISABLE_FLASH, false)) {
                        param.args[1] = false
                    }
                }
            }
        )
    }
}
