package com.souleven.nothingos

import com.souleven.nothingos.hooks.ClockHooks
import com.souleven.nothingos.hooks.EarphoneIconHooks
import com.souleven.nothingos.hooks.FeatureFlagHooks
import com.souleven.nothingos.hooks.FingerprintHooks
import com.souleven.nothingos.hooks.GlimpseHooks
import com.souleven.nothingos.hooks.HookModule
import com.souleven.nothingos.hooks.LockScreenHooks
import com.souleven.nothingos.hooks.Prefs
import com.souleven.nothingos.hooks.VolumeHooks
import com.souleven.nothingos.hooks.NavBarHooks
import com.souleven.nothingos.hooks.ImeNavBarHooks
import com.souleven.nothingos.hooks.AIClipboardHooks
import com.souleven.nothingos.hooks.MiscHooks
import com.souleven.nothingos.hooks.PowerMenuHooks
import com.souleven.nothingos.hooks.BackGestureKillHooks
import com.souleven.nothingos.hooks.SystemFrameworkHooks
import com.souleven.nothingos.hooks.NotificationIconHooks
import com.souleven.nothingos.hooks.SpacingHooks
import com.souleven.nothingos.hooks.StatusBarBrightnessHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "NothingTweaks:"
        const val PREFS_NAME = "settings"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_SELF = "com.souleven.nothingos"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == PKG_SELF) {
            installSelfDetection(lpparam)
            return
        }

        val allowedPackages = listOf(
            PKG_SYSTEMUI,
            "com.nothing.launcher",
            "android",
            "com.google.android.inputmethod.latin"
        )
        if (lpparam.packageName !in allowedPackages) return

        val raw = try {
            XSharedPreferences(PKG_SELF, PREFS_NAME).apply {
                makeWorldReadable()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG FATAL: could not load prefs: ${t.message}")
            XposedBridge.log(t)
            null
        }

        if (raw == null) {
            XposedBridge.log("$TAG Aborting hook install — no prefs available")
            return
        }

        val prefs = Prefs(raw)
        prefs.forceReload()

        val readable = try {
            prefs.file.canRead()
        } catch (t: Throwable) {
            false
        }
        if (!readable) {
            XposedBridge.log(
                "$TAG WARNING: prefs file not readable yet. Open the Nothing Tweaks app once " +
                        "and toggle a setting so the file is created, then restart SystemUI."
            )
        }

        if (lpparam.packageName == "android") {
            com.souleven.nothingos.hooks.SystemHooks.init(lpparam.classLoader, prefs)
        }

        val hooks = mutableListOf<Pair<String, HookModule>>()
        if (lpparam.packageName == PKG_SYSTEMUI) {
            hooks.add("FeatureFlagHooks" to FeatureFlagHooks())
            hooks.add("VolumeHooks" to VolumeHooks())
            hooks.add("GlimpseHooks" to GlimpseHooks())
            hooks.add("LockScreenHooks" to LockScreenHooks())
            hooks.add("EarphoneIconHooks" to EarphoneIconHooks())
            hooks.add("ClockHooks" to ClockHooks())
            hooks.add("FingerprintHooks" to FingerprintHooks())
            hooks.add("AIClipboardHooks" to AIClipboardHooks())
            hooks.add("MiscHooks" to MiscHooks())
            hooks.add("PowerMenuHooks" to PowerMenuHooks())
            hooks.add("BackGestureKillHooks" to BackGestureKillHooks())
            hooks.add("NotificationIconHooks" to NotificationIconHooks())
            hooks.add("SpacingHooks" to SpacingHooks())
            hooks.add("StatusBarBrightnessHooks" to StatusBarBrightnessHooks())
        }
        hooks.add("NavBarHooks" to NavBarHooks())
        if (lpparam.packageName == "com.google.android.inputmethod.latin") {
            hooks.add("ImeNavBarHooks" to ImeNavBarHooks())
        }

        if (lpparam.packageName == "android") {
            hooks.add("SystemFrameworkHooks" to SystemFrameworkHooks())
        }
        
        for ((name, hook) in hooks) {
            try {
                hook.handleLoadPackage(lpparam, prefs)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG ERROR in $name: ${t.message}")
                XposedBridge.log(t)
            }
        }
    }

    private fun installSelfDetection(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "$PKG_SELF.ModuleStatus",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Self-detection failed: ${t.message}")
            XposedBridge.log(t)
        }
    }
}
