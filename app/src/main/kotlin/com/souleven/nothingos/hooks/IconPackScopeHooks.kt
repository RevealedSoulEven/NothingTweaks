package com.souleven.nothingos.hooks

import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.souleven.nothingos.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class IconPackScopeHooks : HookModule {
    @Volatile
    private var scope = IconPackScope()

    @Volatile
    private var modelInitializer: Any? = null

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.processName != MainHook.PKG_LAUNCHER) return

        val loader = lpparam.classLoader
        val createDrawable = XposedHelpers.findMethodExact(
            "com.android.launcher3.icons.BitmapInfo", loader, "newIcon",
            Context::class.java, Int::class.javaPrimitiveType, Path::class.java, String::class.java
        )
        val initializer = XposedHelpers.findClass("com.android.launcher3.model.ModelInitializer", loader)
        val launcherModel = XposedHelpers.findClass("com.android.launcher3.LauncherModel", loader)
        val initializeModel = XposedHelpers.findMethodExact(initializer, "initialize", launcherModel)
        val refreshModel = initializer.getDeclaredMethod("refreshAndReloadLauncher")

        // BitmapInfo can draw the original bitmap even when it has a cached monochrome layer.
        XposedBridge.hookMethod(createDrawable, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val flags = param.args[1] as Int
                if (flags and FLAG_THEMED == 0) return
                val packageName = param.args[3] as? String ?: return
                if (scope.excludes(packageName)) {
                    param.args[1] = flags and FLAG_THEMED.inv()
                }
            }
        })

        XposedBridge.hookMethod(initializeModel, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val firstModel = modelInitializer == null
                modelInitializer = param.thisObject
                if (firstModel) {
                    runCatching { LauncherIconPreviews(loader, param.thisObject).register() }
                        .onFailure { XposedBridge.log("NothingTweaks: Failed to register launcher icon previews: ${it.message}") }
                }
            }
        })

        val mainHandler = Handler(Looper.getMainLooper())
        val refresh = Runnable {
            val model = modelInitializer ?: return@Runnable
            try {
                refreshModel.invoke(model)
            } catch (t: Throwable) {
                XposedBridge.log("NothingTweaks: Failed to refresh launcher icons: ${t.message}")
            }
        }
        prefs.addListener {
            val updatedScope = IconPackScope.read(prefs::getString)
            if (updatedScope != scope) {
                scope = updatedScope
                mainHandler.removeCallbacks(refresh)
                mainHandler.post(refresh)
            }
        }
    }

    private companion object {
        const val FLAG_THEMED = 1
    }
}
