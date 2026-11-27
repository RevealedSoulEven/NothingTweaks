package com.souleven.nothingos.hooks

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

        // The obfuscated names below are from Nothing Launcher 4.0.21.
        val loader = lpparam.classLoader
        val iconOptions = XposedHelpers.findClass("com.android.launcher3.icons.BaseIconFactory\$IconOptions", loader)
        val requestOptions = XposedHelpers.findClass("k7.e", loader)
        val requestPackage = requestOptions.getDeclaredMethod("f")
        val sourceHint = XposedHelpers.findField(iconOptions, "mSourceHint")
        val sourceKey = XposedHelpers.findField(sourceHint.type, "key")
        val sourceComponent = XposedHelpers.findField(sourceKey.type, "componentName")
        val createBitmap = XposedHelpers.findMethodExact(
            "com.android.launcher3.icons.BaseIconFactory", loader,
            "createBadgedIconBitmap", Drawable::class.java, iconOptions
        )
        val forcedRenderer = XposedHelpers.findClass("m7.i", loader)
        val render = XposedHelpers.findMethodExact(forcedRenderer, "d", String::class.java)
        val rendererContext = forcedRenderer.superclass.getDeclaredMethod("b")
        val iconProvider = XposedHelpers.findClass("com.android.launcher3.icons.IconProvider", loader)
        val appState = XposedHelpers.findMethodExact(iconProvider, "getStateForApp", ApplicationInfo::class.java)
        val providerContext = XposedHelpers.findField(iconProvider, "mContext")
        val initializer = XposedHelpers.findClass("com.android.launcher3.model.ModelInitializer", loader)
        val launcherModel = XposedHelpers.findClass("com.android.launcher3.LauncherModel", loader)
        val initializeModel = XposedHelpers.findMethodExact(initializer, "initialize", launcherModel)
        val refreshModel = initializer.getDeclaredMethod("refreshAndReloadLauncher")
        require(
            iconOptions.isAssignableFrom(requestOptions) && requestPackage.returnType == String::class.java &&
                render.returnType == Drawable::class.java && rendererContext.returnType == Context::class.java
        ) { "Unexpected Nothing Launcher icon pipeline" }

        val packageBeingRendered = ThreadLocal<String>()
        XposedBridge.hookMethod(createBitmap, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (scope.excludedPackages.isEmpty()) return
                val options = param.args[1] ?: return
                val requestName = if (requestOptions.isInstance(options)) requestPackage.invoke(options) as? String else null
                val packageName = requestName ?: run {
                    val key = sourceHint.get(options)?.let(sourceKey::get)
                    (key?.let(sourceComponent::get) as? ComponentName)?.packageName
                } ?: return
                param.setObjectExtra("packageScopeEntered", true)
                param.setObjectExtra("previousPackage", packageBeingRendered.get())
                packageBeingRendered.set(packageName)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.getObjectExtra("packageScopeEntered") != true) return
                val previousPackage = param.getObjectExtra("previousPackage") as? String
                if (previousPackage == null) packageBeingRendered.remove()
                else packageBeingRendered.set(previousPackage)
            }
        })

        // This renderer handles generated monochrome layers. App-supplied layers use another renderer.
        XposedBridge.hookMethod(render, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val packageName = (param.args[0] as? String)?.takeIf { it.isNotEmpty() }
                    ?: packageBeingRendered.get() ?: return
                if (scope.excludes(packageName) && iconPackForcesMonochrome(rendererContext.invoke(param.thisObject) as Context)) {
                    param.result = null
                }
            }
        })

        // Mark disk entries so a launcher started after a settings change can detect stale icons.
        XposedBridge.hookMethod(appState, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.args[0] as? ApplicationInfo ?: return
                val packageName = app.packageName ?: return
                if (!scope.excludes(packageName)) return
                val state = param.result as? String ?: return
                if (iconPackForcesMonochrome(providerContext.get(param.thisObject) as Context)) {
                    param.result = "$state,nothingtweaks_no_forced_mono"
                }
            }
        })

        XposedBridge.hookMethod(initializeModel, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!param.hasThrowable()) modelInitializer = param.thisObject
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

    private fun iconPackForcesMonochrome(context: Context): Boolean {
        val resolver = context.contentResolver
        return Settings.System.getInt(resolver, "nothing_icon_pack_force_render_enable", 0) == 1 &&
            Settings.System.getInt(resolver, "theme_bauhaus_enable", 0) == 0
    }
}
