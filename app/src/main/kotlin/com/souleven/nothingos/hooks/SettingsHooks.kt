package com.souleven.nothingos.hooks

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.ref.WeakReference


/*
    The settings app is very heavily obfuscated with proguard due to minifying
    so finding hooks in it is possible but making it survive multiple devices/versions
    and updates is tough as we need to rely on pattern-recognition at runtime.
    So I did the research on the methods but rest of the hooks are written by AI (yes)
    used deepseek 4.1 flash and gemini 3.8 flash, both corrected each other for over 7 hours
    and generated SettingsBuilder.kt and SettingsHooks.kt, so ya the code below is AI-generated
*/


/**
 * SettingsHooks
 *
 * Injects "Nothing Tweaks" at the top of Nothing OS Settings (TopLevelSettings)
 * with the exact same visual styling, rounded card appearance, and monochrome icon.
 *
 * Fully resilient against R8/ProGuard obfuscation by supporting both standard
 * AndroidX names and Nothing OS obfuscated names (K1, J1, T1, etc.), plus signature/field fallbacks.
 *
 * Click handling is handled with triple redundancy:
 * 1. Direct View.OnClickListener on the RecyclerView itemView during binding.
 * 2. TopLevelSettings.onPreferenceTreeClick hook.
 * 3. Preference OnPreferenceClickListener proxy & intent.
 */
class SettingsHooks : HookModule {

    companion object {
        const val PREF_KEY_CATEGORY = "top_level_nothing_tweaks_category"
        const val PREF_KEY_ITEM = "top_level_nothing_tweaks"
        const val FRAGMENT_NOTHING_TWEAKS = "com.souleven.nothingos.NothingTweaksSettingsFragment"
        private const val PKG_MOD = "com.souleven.nothingos"
        private const val TARGET_ACTIVITY = "com.souleven.nothingos.ui.MainActivity"

        @Volatile
        private var lastActiveFragment: WeakReference<Any>? = null

        @Volatile
        private var lastLoggedState: Boolean? = null

        @Volatile
        private var cachedOrderField: java.lang.reflect.Field? = null

        @Volatile
        private var cachedKeyField: java.lang.reflect.Field? = null

        @Volatile
        private var cachedOrderingMethod: java.lang.reflect.Method? = null
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "com.android.settings") return

        // XposedBridge.log("$TAG [SettingsHooks] handleLoadPackage started for process: ${lpparam.processName}")

        // catch crashes so settings failures still show up in the log
        try {
            val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                XposedBridge.log("$TAG [CRASH in Settings] Thread '${t.name}': ${e.javaClass.name}: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
                oldHandler?.uncaughtException(t, e)
            }
        } catch (_: Throwable) {}

        val topLevelSettingsClass = XposedHelpers.findClassIfExists(
            "com.android.settings.homepage.TopLevelSettings",
            lpparam.classLoader
        )

        if (topLevelSettingsClass == null) {
            // XposedBridge.log("$TAG [SettingsHooks] TopLevelSettings class NOT found in Settings.")
            return
        }

        // XposedBridge.log("$TAG [SettingsHooks] TopLevelSettings class found! Registering lifecycle hooks...")

        // hook the helper that hides homepage subtitles. we find it through the homepage
        // preference classes instead of relying on obfuscated class and method names.

        try {
            val candidateClasses = listOf(
                "com.nothing.settings.widget.NtHomepagePreference",
                "com.android.settings.widget.HomepagePreference"
            )
            val hookedMethods = HashSet<java.lang.reflect.Method>()

            for (prefClassName in candidateClasses) {
                val hpClass = XposedHelpers.findClassIfExists(prefClassName, lpparam.classLoader) ?: continue

                // fields can live on any class in the hierarchy, r8 often moves them
                val helperClasses = LinkedHashSet<Class<*>>()
                var hpCursor: Class<*>? = hpClass
                while (hpCursor != null && hpCursor != Any::class.java) {
                    for (f in hpCursor.declaredFields) {
                        val t = f.type
                        if (t.isPrimitive || t == View::class.java || hpClass.isAssignableFrom(t)) continue
                        // skip framework types so we don't hook unrelated (String) -> boolean methods
                        val pkg = t.`package`?.name ?: ""
                        if (pkg.startsWith("android.") || pkg.startsWith("androidx.") ||
                            pkg.startsWith("java.") || pkg.startsWith("kotlin.")
                        ) continue
                        helperClasses.add(t)
                    }
                    hpCursor = hpCursor.superclass
                }

                for (helperClass in helperClasses) {
                    // include inherited methods too, the helper is often declared on a parent
                    val candidates = LinkedHashSet<java.lang.reflect.Method>()
                    candidates.addAll(helperClass.methods)
                    var cursor: Class<*>? = helperClass
                    while (cursor != null && cursor != Any::class.java) {
                        candidates.addAll(cursor.declaredMethods)
                        cursor = cursor.superclass
                    }

                    for (method in candidates) {
                        if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) continue
                        if (method.returnType != java.lang.Boolean.TYPE ||
                            method.parameterTypes.size != 1 ||
                            method.parameterTypes[0] != String::class.java
                        ) continue
                        if (!hookedMethods.add(method)) continue

                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val key = param.args.getOrNull(0) as? String
                                if (PREF_KEY_ITEM == key) {
                                    param.result = true
                                }
                            }
                        })
                        // XposedBridge.log("$TAG [SettingsHooks] Pattern-hooked subtitle visibility on ${helperClass.simpleName}#${method.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Warning: Failed pattern-hooking subtitle visibility: ${t.message}")
        }

        // intercept clicks on our preference from the top level settings fragment
        try {
            XposedBridge.hookAllMethods(
                topLevelSettingsClass,
                "onPreferenceTreeClick",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject?.javaClass != topLevelSettingsClass) return
                        val pref = param.args.getOrNull(0) ?: return
                        val key = getPrefKey(pref)
                        if (PREF_KEY_ITEM == key) {
                            // XposedBridge.log("$TAG [SettingsHooks] onPreferenceTreeClick intercepted for $key!")
                            param.result = true // consume the click
                            val fragment = param.thisObject
                            val context = (XposedHelpers.callMethod(fragment, "getContext") as? Context)
                                ?: (XposedHelpers.callMethod(pref, "getContext") as? Context)
                            if (context != null) {
                                launchNothingTweaksSubSettings(context)
                            }
                        }
                    }
                }
            )
            // XposedBridge.log("$TAG [SettingsHooks] Hooked TopLevelSettings.onPreferenceTreeClick")
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Failed to hook onPreferenceTreeClick: ${t.message}")
        }

        // hook preference binding so we can intercept taps on the item view itself.
        // onBindViewHolder is either named normally or found by its (view holder) -> void shape.
        val prefClass = XposedHelpers.findClassIfExists("androidx.preference.Preference", lpparam.classLoader)
        if (prefClass != null) {
            try {
                val bindMethod = prefClass.methods.firstOrNull { it.name == "onBindViewHolder" }
                    ?: prefClass.declaredMethods.firstOrNull { m ->
                        m.returnType == java.lang.Void.TYPE &&
                        m.parameterTypes.size == 1 &&
                        isViewHolderClass(m.parameterTypes[0])
                    }

                if (bindMethod != null) {
                    XposedBridge.hookMethod(bindMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val pref = param.thisObject ?: return
                                val key = getPrefKey(pref)
                                if (PREF_KEY_ITEM == key) {
                                    val holder = param.args.getOrNull(0) ?: return
                                    val itemView = getItemViewFromHolder(holder)
                                    // XposedBridge.log("$TAG [SettingsHooks] Binding $key: holder=${holder.javaClass.simpleName}, itemView=${itemView?.javaClass?.simpleName}")
                                    if (itemView != null) {
                                        itemView.isClickable = true
                                        itemView.isFocusable = true
                                        itemView.setOnClickListener { v ->
                                            // XposedBridge.log("$TAG [SettingsHooks] Direct itemView click intercepted!")
                                            launchNothingTweaksSubSettings(v.context)
                                        }

                                        // make sure the subtitle is visible in every Nothing style
                                        val summaryView = itemView.findViewById<View>(android.R.id.summary)
                                        summaryView?.visibility = View.VISIBLE
                                    }
                                }
                            } catch (t: Throwable) {
                                // XposedBridge.log("$TAG [SettingsHooks] Error in onBindViewHolder afterHookedMethod: ${t.message}")
                            }
                        }
                    })
                    // XposedBridge.log("$TAG [SettingsHooks] Pattern-hooked Preference#${bindMethod.name} (${bindMethod.parameterTypes[0].simpleName}) for itemView clicks")
                } else {
                    // XposedBridge.log("$TAG [SettingsHooks] Warning: Could not find onBindViewHolder method on Preference")
                }
            } catch (t: Throwable) {
                // XposedBridge.log("$TAG [SettingsHooks] Warning: Failed pattern-hooking onBindViewHolder: ${t.message}")
            }
        }

        // route SubSettings to our fragment when the intent asks for it
        val utilsClass = XposedHelpers.findClassIfExists("com.android.settings.Utils", lpparam.classLoader)
        if (utilsClass != null) {
            try {
                XposedBridge.hookAllMethods(utilsClass, "getTargetFragment", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 3) return
                        if (param.args[0] !is Activity) return
                        val fragName = param.args.getOrNull(1) as? String
                        if (fragName == FRAGMENT_NOTHING_TWEAKS) {
                            val testingClass = XposedHelpers.findClassIfExists(
                                "com.android.settings.TestingSettings",
                                lpparam.classLoader
                            )
                            if (testingClass != null) {
                                val fragment = testingClass.getConstructor().newInstance()
                                val args = (param.args.getOrNull(2) as? Bundle) ?: Bundle()
                                args.putBoolean("is_nothing_tweaks", true)
                                XposedHelpers.callMethod(fragment, "setArguments", args)
                                param.result = fragment
                                // XposedBridge.log("$TAG [SettingsHooks] getTargetFragment returned NothingTweaks host fragment!")

                                val act = param.args.getOrNull(0) as? Activity
                                if (act != null) {
                                    updateActivityTitle(act)
                                }
                            }
                        }
                    }
                })
                // XposedBridge.log("$TAG [SettingsHooks] Hooked Utils.getTargetFragment")
            } catch (t: Throwable) {
                // XposedBridge.log("$TAG [SettingsHooks] Failed to hook getTargetFragment: ${t.message}")
            }
        }

        // build our preference tree inside the settings host fragment and keep the title
        val testingSettingsClass = XposedHelpers.findClassIfExists(
            "com.android.settings.TestingSettings",
            lpparam.classLoader
        )
        if (testingSettingsClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    testingSettingsClass,
                    "onCreate",
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fragment = param.thisObject
                            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
                            val isNothingTweaks = args?.getBoolean("is_nothing_tweaks", false) == true
                            if (isNothingTweaks) {
                                // XposedBridge.log("$TAG [SettingsHooks] TestingSettings.onCreate afterHookedMethod for Nothing Tweaks!")
                                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity
                                if (act != null) updateActivityTitle(act)
                                try {
                                    SettingsBuilder.buildPreferenceScreen(fragment, lpparam.classLoader)
                                    SettingsBuilder.adjustRecyclerViewPadding(fragment)
                                } catch (t: Throwable) {
                                    // XposedBridge.log("$TAG [SettingsHooks] buildPreferenceScreen error: ${t.message}")
                                }
                            }
                        }
                    }
                )
                // XposedBridge.log("$TAG [SettingsHooks] Hooked TestingSettings.onCreate")
            } catch (t: Throwable) {
                // XposedBridge.log("$TAG [SettingsHooks] Failed to hook TestingSettings.onCreate: ${t.message}")
            }

            // ensure space after the menu ends when view is created
            try {
                hookDeclaredOrAll(
                    testingSettingsClass,
                    "onViewCreated",
                    arrayOf(View::class.java, Bundle::class.java),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fragment = param.thisObject
                            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
                            if (args?.getBoolean("is_nothing_tweaks", false) == true) {
                                SettingsBuilder.adjustRecyclerViewPadding(fragment)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // keep top-left action bar clean and maintain bottom padding on resume
            try {
                hookDeclaredOrAll(
                    testingSettingsClass,
                    "onResume",
                    emptyArray(),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fragment = param.thisObject
                            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
                            val isNothingTweaks = args?.getBoolean("is_nothing_tweaks", false) == true
                            if (isNothingTweaks) {
                                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity
                                if (act != null) updateActivityTitle(act)
                                SettingsBuilder.adjustRecyclerViewPadding(fragment)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            // and on start
            try {
                hookDeclaredOrAll(
                    testingSettingsClass,
                    "onStart",
                    emptyArray(),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val fragment = param.thisObject
                            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
                            val isNothingTweaks = args?.getBoolean("is_nothing_tweaks", false) == true
                            if (isNothingTweaks) {
                                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity
                                if (act != null) updateActivityTitle(act)
                                SettingsBuilder.adjustRecyclerViewPadding(fragment)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // suppress action bar title when host activity resumes
        val settingsActivityClass = XposedHelpers.findClassIfExists(
            "com.android.settings.SettingsActivity",
            lpparam.classLoader
        )
        if (settingsActivityClass != null) {
            try {
                hookDeclaredOrAll(
                    settingsActivityClass,
                    "onResume",
                    emptyArray(),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? Activity ?: return
                            val intent = act.intent ?: return
                            if (intent.getBooleanExtra("is_nothing_tweaks", false)) {
                                updateActivityTitle(act)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // enforce Nothing Tweaks title and headline font on base activity
        val settingsBaseActivityClass = XposedHelpers.findClassIfExists(
            "com.android.settings.core.SettingsBaseActivity",
            lpparam.classLoader
        )
        if (settingsBaseActivityClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    settingsBaseActivityClass,
                    "setTitle",
                    java.lang.Integer.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? Activity ?: return
                            if (isNothingTweaksActivity(act)) {
                                param.result = null
                                XposedHelpers.callMethod(act, "setTitle", "Nothing Tweaks" as CharSequence)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(
                    settingsBaseActivityClass,
                    "setTitle",
                    CharSequence::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? Activity ?: return
                            if (isNothingTweaksActivity(act)) {
                                val currentTitle = param.args.getOrNull(0) as? CharSequence
                                if (currentTitle == null || currentTitle.contains("Testing") || currentTitle.toString() != "Nothing Tweaks") {
                                    param.args[0] = "Nothing Tweaks" as CharSequence
                                }
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? Activity ?: return
                            if (isNothingTweaksActivity(act)) {
                                updateActivityTitle(act)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // inject after the top level preference screen is created
        try {
            XposedHelpers.findAndHookMethod(
                topLevelSettingsClass,
                "onCreatePreferences",
                Bundle::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        lastActiveFragment = WeakReference(param.thisObject)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            injectNothingTweaksPreference(param.thisObject, lpparam.classLoader, prefs)
                        }
                    }
                }
            )
            // XposedBridge.log("$TAG [SettingsHooks] TopLevelSettings.onCreatePreferences hooked")
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Failed to hook onCreatePreferences: ${t.message}")
        }

        // keep the card present after resume
        try {
            val hooked = hookDeclaredOrAll(
                topLevelSettingsClass,
                "onResume",
                emptyArray(),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass != topLevelSettingsClass) return
                        lastActiveFragment = WeakReference(param.thisObject)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            injectNothingTweaksPreference(param.thisObject, lpparam.classLoader, prefs)
                        }
                    }
                }
            )
            // if (hooked) XposedBridge.log("$TAG [SettingsHooks] TopLevelSettings.onResume hooked")
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Failed to hook onResume: ${t.message}")
        }

        // and after start
        try {
            val hooked = hookDeclaredOrAll(
                topLevelSettingsClass,
                "onStart",
                emptyArray(),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass != topLevelSettingsClass) return
                        lastActiveFragment = WeakReference(param.thisObject)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            injectNothingTweaksPreference(param.thisObject, lpparam.classLoader, prefs)
                        }
                    }
                }
            )
            // if (hooked) XposedBridge.log("$TAG [SettingsHooks] TopLevelSettings.onStart hooked")
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Failed to hook onStart: ${t.message}")
        }

        // the dashboard can rebuild its tiles, so inject again afterwards
        try {
            hookDeclaredOrAll(
                topLevelSettingsClass,
                "refreshDashboardTiles",
                arrayOf(String::class.java),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass != topLevelSettingsClass) return
                        lastActiveFragment = WeakReference(param.thisObject)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            injectNothingTweaksPreference(param.thisObject, lpparam.classLoader, prefs)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        // last chance trigger when the homepage activity starts
        val homepageActivityClass = XposedHelpers.findClassIfExists(
            "com.android.settings.homepage.SettingsHomepageActivity",
            lpparam.classLoader
        )
        if (homepageActivityClass != null) {
            try {
                val hooked = hookDeclaredOrAll(
                    homepageActivityClass,
                    "onStart",
                    emptyArray(),
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.thisObject.javaClass != homepageActivityClass) return
                            try {
                                val fragment = getTopLevelFragment(param.thisObject)
                                if (fragment != null) {
                                    lastActiveFragment = WeakReference(fragment)
                                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                                    mainHandler.post {
                                        injectNothingTweaksPreference(fragment, lpparam.classLoader, prefs)
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                )
                // if (hooked) XposedBridge.log("$TAG [SettingsHooks] SettingsHomepageActivity.onStart hooked")
            } catch (t: Throwable) {
                // XposedBridge.log("$TAG [SettingsHooks] Failed to hook SettingsHomepageActivity.onStart: ${t.message}")
            }
        }

        // refresh the card when our prefs change
        prefs.addListener {
            val fragment = lastActiveFragment?.get() ?: return@addListener
            try {
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    try {
                        injectNothingTweaksPreference(fragment, lpparam.classLoader, prefs)
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Hook the method on [clazz] itself first, then fall back to hookAllMethods
     * when the rom inherits it from a parent.
     */
    private fun hookDeclaredOrAll(
        clazz: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>>,
        callback: XC_MethodHook
    ): Boolean {
        return try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *paramTypes, callback)
            true
        } catch (_: Throwable) {
            try {
                XposedBridge.hookAllMethods(clazz, methodName, callback)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun isNothingTweaksActivity(act: Activity): Boolean {
        val intent = act.intent ?: return false
        if (intent.getBooleanExtra("is_nothing_tweaks", false)) return true
        val frag = intent.getStringExtra(":settings:show_fragment")
        if (frag == FRAGMENT_NOTHING_TWEAKS) return true
        return false
    }

    private fun updateActivityTitle(act: Activity) {
        SettingsBuilder.updateActivityTitle(act)
    }

    private fun readIntegrationPref(context: Context, prefs: Prefs): Boolean {
        // ask the provider first, it stays fresh across processes
        try {
            val bundle = context.contentResolver.call(
                RemotePreferenceProvider.CONTENT_URI,
                RemotePreferenceProvider.METHOD_GET_ALL,
                null,
                null
            )
            if (bundle != null && bundle.containsKey("pref_integrate_settings")) {
                return bundle.getBoolean("pref_integrate_settings", true)
            }
        } catch (_: Throwable) {}

        // then try the shared prefs file directly
        val paths = listOf(
            "/data/user_de/0/com.souleven.nothingos/shared_prefs/settings.xml",
            "/data/data/com.souleven.nothingos/shared_prefs/settings.xml"
        )
        for (path in paths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val text = file.readText()
                    val hasTrue = text.contains("\"pref_integrate_settings\" value=\"true\"") ||
                            text.contains("name=\"pref_integrate_settings\">true<") ||
                            text.contains("\"pref_integrate_settings\">1<")
                    val hasFalse = text.contains("\"pref_integrate_settings\" value=\"false\"") ||
                            text.contains("name=\"pref_integrate_settings\">false<") ||
                            text.contains("\"pref_integrate_settings\">0<")
                    if (hasTrue) return true
                    if (hasFalse) return false
                } catch (t: Throwable) {
                    // XposedBridge.log("$TAG [SettingsHooks] Failed reading $path: ${t.message}")
                }
            }
        }

        // last resort: cached value, default on
        return prefs.getBoolean("pref_integrate_settings", true)
    }

    private fun injectNothingTweaksPreference(
        fragment: Any,
        classLoader: ClassLoader,
        prefs: Prefs
    ) {
        var context: Context? = null
        try {
            context = XposedHelpers.callMethod(fragment, "getContext") as? Context
            if (context == null) return

            val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen")
            if (screen == null) return

            val isIntegrationEnabled = readIntegrationPref(context, prefs)

            // only log when the toggle changes (log line kept for debugging)
            if (lastLoggedState != isIntegrationEnabled) {
                lastLoggedState = isIntegrationEnabled
                // XposedBridge.log("$TAG [SettingsHooks] isIntegrationEnabled = $isIntegrationEnabled (screen=${screen.javaClass.simpleName})")
            }

            if (!isIntegrationEnabled) {
                removeNothingTweaksPreference(fragment, screen)
                return
            }

            // already there
            val existingPref = findPref(fragment, screen, PREF_KEY_ITEM)
            if (existingPref != null) {
                return
            }

            // drop a category that lost its item
            val staleCategory = findPref(fragment, screen, PREF_KEY_CATEGORY)
            if (staleCategory != null) {
                removePref(screen, staleCategory)
            }

            // rounded card container
            val untitledCatClass = XposedHelpers.findClassIfExists(
                "com.android.settingslib.widget.UntitledPreferenceCategory",
                classLoader
            )
            val baseCatClass = XposedHelpers.findClass("androidx.preference.PreferenceCategory", classLoader)
            val catClass = untitledCatClass ?: baseCatClass
            val category = catClass.getConstructor(Context::class.java).newInstance(context)

            setPrefKey(category, PREF_KEY_CATEGORY)
            setOrderingAsAdded(category, false)
            setPrefOrder(category, -10000)

            // untitled category already sets its own layout
            if (untitledCatClass == null) {
                val topCategoryLayoutId = context.resources.getIdentifier(
                    "nt_homepage_top_preference_category_layout",
                    "layout",
                    "com.android.settings"
                )
                if (topCategoryLayoutId != 0) {
                    setPrefLayoutResource(category, topCategoryLayoutId)
                }
            }

            // the actual row
            val ntPrefClass = XposedHelpers.findClassIfExists(
                "com.nothing.settings.widget.NtHomepagePreference",
                classLoader
            )
            val homepagePrefClass = XposedHelpers.findClassIfExists(
                "com.android.settings.widget.HomepagePreference",
                classLoader
            )
            val basePrefClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
            val prefClass = ntPrefClass ?: homepagePrefClass ?: basePrefClass
            val pref = prefClass.getConstructor(Context::class.java).newInstance(context)

            setPrefKey(pref, PREF_KEY_ITEM)
            setPrefTitle(pref, "Nothing Tweaks")
            setPrefSummary(pref, "Customizations, gestures & interface")
            setPrefOrder(pref, -10000)

            // copy the layout from an existing homepage row so ours looks native
            val networkPref = findPref(fragment, screen, "top_level_network")
            var layoutRes = if (networkPref != null) getPrefLayoutResource(networkPref) else 0
            if (layoutRes == 0) {
                layoutRes = findAnyLayoutResource(screen) { item ->
                    (ntPrefClass?.isInstance(item) == true) ||
                        (homepagePrefClass?.isInstance(item) == true)
                }
            }
            if (layoutRes != 0) {
                setPrefLayoutResource(pref, layoutRes)
            }

            // tint the dot matrix icon to match the theme
            try {
                val modContext = context.createPackageContext(PKG_MOD, Context.CONTEXT_IGNORE_SECURITY)
                val icon = ContextCompat.getDrawable(
                    modContext,
                    com.souleven.nothingos.R.drawable.ic_settings_nothing_tweaks
                )
                if (icon != null) {
                    val mutatedIcon = icon.mutate()
                    val typedValue = android.util.TypedValue()
                    val iconColor = if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)) {
                        if (typedValue.resourceId != 0) ContextCompat.getColor(context, typedValue.resourceId)
                        else typedValue.data
                    } else {
                        android.graphics.Color.WHITE
                    }
                    mutatedIcon.setTint(iconColor)
                    setPrefIcon(pref, mutatedIcon)
                }
            } catch (e: Exception) {
                // XposedBridge.log("$TAG [SettingsHooks] Icon load warning: ${e.message}")
            }

            // open our fragment on click
            val launchIntent = Intent().apply {
                setClassName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", FRAGMENT_NOTHING_TWEAKS)
                putExtra(":settings:show_fragment_title", "Nothing Tweaks")
                putExtra("is_nothing_tweaks", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            setPrefIntent(pref, launchIntent)

            setPrefClickListener(pref, classLoader) {
                launchNothingTweaksSubSettings(context)
            }

            // attach the row to the category, then the category to the screen
            val addedItem = addPref(category, pref)
            setOrderingAsAdded(screen, false)
            val addedCategory = addPref(screen, category)

            if (addedItem && addedCategory) {
                // XposedBridge.log("$TAG [SettingsHooks] Successfully injected Nothing Tweaks at top of Settings!")
            } else {
                // XposedBridge.log(
                //     "$TAG [SettingsHooks] Injection incomplete: addItem=$addedItem " +
                //         "addCategory=$addedCategory group=${groupClassOf(screen)?.simpleName} " +
                //         "size=${getPreferenceList(screen)?.size} " +
                //         "keys=${getPrefKey(category)}/${getPrefKey(pref)}"
                // )
            }

            // redraw so the card shows up right away
            refreshAdapter(fragment)

        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Injection error: ${t.message}")
        }
    }

    private fun launchNothingTweaksSubSettings(context: Context) {
        // XposedBridge.log("$TAG [SettingsHooks] launchNothingTweaksSubSettings requested from context=${context.javaClass.simpleName}")
        try {
            val intent = Intent().apply {
                setClassName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", FRAGMENT_NOTHING_TWEAKS)
                putExtra(":settings:show_fragment_title", "Nothing Tweaks")
                putExtra("is_nothing_tweaks", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // XposedBridge.log("$TAG [SettingsHooks] Successfully launched SubSettings for Nothing Tweaks!")
            return
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Failed to launch SubSettings, falling back to MainActivity: ${t.message}")
        }
        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
        // XposedBridge.log("$TAG [SettingsHooks] launchMainActivity requested from context=${context.javaClass.simpleName}")

        // make sure the launcher activity is enabled
        try {
            val pm = context.packageManager
            val component = ComponentName(PKG_MOD, "com.souleven.nothingos.ui.MainActivityLauncher")
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Throwable) {}

        // try the explicit activity first
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(PKG_MOD, TARGET_ACTIVITY)
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            context.startActivity(intent)
            // XposedBridge.log("$TAG [SettingsHooks] Successfully launched MainActivity via explicit Intent!")
            return
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Explicit startActivity failed: ${t.message}")
        }

        // then the package launch intent
        try {
            val pkgIntent = context.packageManager.getLaunchIntentForPackage(PKG_MOD)
            if (pkgIntent != null) {
                pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(pkgIntent)
                // XposedBridge.log("$TAG [SettingsHooks] Successfully launched via getLaunchIntentForPackage!")
                return
            }
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] getLaunchIntentForPackage failed: ${t.message}")
        }

        // root shell as the last option
        try {
            val cmd = "am start -n $PKG_MOD/$TARGET_ACTIVITY -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            // XposedBridge.log("$TAG [SettingsHooks] Fallback: am start executed via root shell")
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsHooks] Root launch fallback failed: ${t.message}")
        }
    }

    private fun removeNothingTweaksPreference(fragment: Any, screen: Any) {
        try {
            val category = findPref(fragment, screen, PREF_KEY_CATEGORY)
            if (category != null) {
                val parent = getParent(category) ?: screen
                removePref(parent, category)
            }
            val pref = findPref(fragment, screen, PREF_KEY_ITEM)
            if (pref != null) {
                val parent = getParent(pref) ?: screen
                removePref(parent, pref)
            }
            refreshAdapter(fragment)
        } catch (_: Throwable) {}
    }

    private fun refreshAdapter(fragment: Any) {
        try {
            val recyclerView = XposedHelpers.callMethod(fragment, "getListView") as? androidx.recyclerview.widget.RecyclerView
            recyclerView?.post {
                try {
                    recyclerView.adapter?.notifyDataSetChanged()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    // reflection helpers, kept loose so r8/proguard renames don't break us

    private fun getTopLevelFragment(activity: Any): Any? {
        // look for a no-arg method that returns TopLevelSettings
        var c: Class<*>? = activity.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.returnType.name.contains("TopLevelSettings")) {
                    try {
                        m.isAccessible = true
                        val frag = m.invoke(activity)
                        if (frag != null) return frag
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }

        // or a field of that type
        c = activity.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type.name.contains("TopLevelSettings")) {
                    try {
                        f.isAccessible = true
                        val frag = f.get(activity)
                        if (frag != null) return frag
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }

        // or ask the fragment manager
        try {
            val fm = XposedHelpers.callMethod(activity, "getSupportFragmentManager")
            val fragments = XposedHelpers.callMethod(fm, "getFragments") as? List<*>
            if (fragments != null) {
                for (frag in fragments) {
                    if (frag != null && frag.javaClass.name.contains("TopLevelSettings")) {
                        return frag
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun isViewHolderClass(clazz: Class<*>): Boolean {
        if (clazz.isInterface || clazz.isPrimitive) return false
        val name = clazz.name
        if (name == "java.lang.Object" || name == "java.lang.String" ||
            name.startsWith("android.os.") || name.startsWith("android.graphics.") ||
            name.startsWith("android.content.") || name.startsWith("android.view.")
        ) {
            return false
        }
        if (name.contains("ViewHolder")) return true
        // recycler view holders usually have a constructor that takes a view
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (ctor in c.declaredConstructors) {
                if (ctor.parameterTypes.size == 1 && View::class.java.isAssignableFrom(ctor.parameterTypes[0])) {
                    return true
                }
            }
            c = c.superclass
        }
        return false
    }

    private fun isRecyclerView(view: View): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null && c != Any::class.java) {
            if (c.name.contains("RecyclerView")) return true
            c = c.superclass
        }
        return false
    }

    private fun getItemViewFromHolder(holder: Any?): View? {
        if (holder == null) return null
        if (holder is View) return holder

        // androidx calls it itemView
        try {
            val v = XposedHelpers.getObjectField(holder, "itemView") as? View
            if (v != null && !isRecyclerView(v)) return v
        } catch (_: Throwable) {}

        // otherwise look for an exact View field, not a RecyclerView subclass
        var clazz: Class<*>? = holder.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                if (f.type == View::class.java) {
                    try {
                        f.isAccessible = true
                        val v = f.get(holder) as? View
                        if (v != null && !isRecyclerView(v)) return v
                    } catch (_: Throwable) {}
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun findPref(fragment: Any, screen: Any, key: String): Any? {
        // walk the real preference list first, it does not depend on method names
        val inScreen = findPrefInGroup(screen, key)
        if (inScreen != null) return inScreen

        // then ask the fragment
        try {
            val pref = XposedHelpers.callMethod(fragment, "findPreference", key as CharSequence)
            if (pref != null && getPrefKey(pref) == key) return pref
        } catch (_: Throwable) {}

        return null
    }

    private fun findPrefInGroup(group: Any, key: String): Any? {
        val count = getPrefCount(group)
        for (i in 0 until count) {
            val item = getPrefAt(group, i) ?: continue
            val itemKey = getPrefKey(item)
            if (key == itemKey) return item
            val childCount = getPrefCount(item)
            if (childCount > 0) {
                val nested = findPrefInGroup(item, key)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun getPrefKey(pref: Any?): String? {
        if (pref == null) return null
        try {
            val key = XposedHelpers.callMethod(pref, "getKey") as? String
            if (key != null) return key
        } catch (_: Throwable) {}
        try {
            val key = XposedHelpers.getObjectField(pref, "mKey") as? String
            if (key != null) return key
        } catch (_: Throwable) {}
        return null
    }

    /**
     * find the androidx Preference base class without trusting its name.
     * getKey() is public and stays stable across androidx versions.
     */
    private fun preferenceClassOf(obj: Any): Class<*>? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "getKey" && m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                    return c
                }
            }
            c = c.superclass
        }
        return try {
            Class.forName("androidx.preference.Preference", false, obj.javaClass.classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPreferenceType(type: Class<*>, prefClass: Class<*>?): Boolean {
        if (prefClass != null && prefClass.isAssignableFrom(type)) return true
        return type.name.contains("Preference")
    }

    /**
     * a preference group owns a child list and has a boolean add/remove method
     * that takes a preference. this avoids depending on the class name.
     */
    private fun declaresGroupStructure(c: Class<*>): Boolean {
        // the base Preference class also has a list and boolean setters, skip it
        if (c.declaredMethods.any {
                it.name == "getKey" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }) {
            return false
        }

        val hasList = c.declaredFields.any { List::class.java.isAssignableFrom(it.type) }
        if (!hasList) return false
        return c.declaredMethods.any { m ->
            if (!java.lang.reflect.Modifier.isPublic(m.modifiers)) return@any false
            if (m.parameterTypes.size != 1 || m.returnType != java.lang.Boolean.TYPE) return@any false
            val p = m.parameterTypes[0]
            !p.isPrimitive && p != java.lang.String::class.java &&
                p != CharSequence::class.java && p != Any::class.java
        }
    }

    private fun groupClassOf(obj: Any): Class<*>? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            if (declaresGroupStructure(c)) return c
            c = c.superclass
        }
        return null
    }

    private fun isGroupType(type: Class<*>): Boolean {
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            if (declaresGroupStructure(c)) return true
            c = c.superclass
        }
        return false
    }

    private fun isPreferenceGroup(obj: Any?): Boolean {
        return obj != null && groupClassOf(obj) != null
    }

    private fun getPreferenceList(group: Any): List<*>? {
        val groupClass = groupClassOf(group) ?: return null

        for (f in groupClass.declaredFields) {
            if (List::class.java.isAssignableFrom(f.type)) {
                try {
                    f.isAccessible = true
                    val list = f.get(group) as? List<*>
                    if (list != null) return list
                } catch (_: Throwable) {}
            }
        }

        // sometimes the list lives on a subclass instead
        var c: Class<*>? = group.javaClass
        while (c != null && c != Any::class.java) {
            if (c == groupClass) break
            for (f in c.declaredFields) {
                if (List::class.java.isAssignableFrom(f.type)) {
                    try {
                        f.isAccessible = true
                        val list = f.get(group) as? List<*>
                        if (list != null) return list
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun getPrefCount(group: Any): Int {
        if (!isPreferenceGroup(group)) return 0

        val list = getPreferenceList(group)
        if (list != null) return list.size

        try {
            return XposedHelpers.callMethod(group, "getPreferenceCount") as Int
        } catch (_: Throwable) {}

        return 0
    }

    private fun getPrefAt(group: Any, index: Int): Any? {
        if (!isPreferenceGroup(group)) return null

        val list = getPreferenceList(group)
        if (list != null && index in 0 until list.size) {
            return list[index]
        }

        try {
            return XposedHelpers.callMethod(group, "getPreference", index)
        } catch (_: Throwable) {}

        // last resort: find an (int) -> preference method
        val groupClass = groupClassOf(group)
        val prefClass = preferenceClassOf(group)
        if (groupClass != null) {
            for (m in groupClass.declaredMethods) {
                if (m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == java.lang.Integer.TYPE &&
                    m.returnType != java.lang.Void.TYPE &&
                    !m.returnType.isPrimitive &&
                    isPreferenceType(m.returnType, prefClass)
                ) {
                    try {
                        m.isAccessible = true
                        val pref = m.invoke(group, index)
                        if (pref != null) return pref
                    } catch (_: Throwable) {}
                }
            }
        }

        return null
    }

    private fun addPref(group: Any, child: Any): Boolean {
        val list = getPreferenceList(group)
        if (list != null && list.contains(child)) return true

        // normal addPreference first
        try {
            val res = XposedHelpers.callMethod(group, "addPreference", child)
            if (list != null && list.contains(child)) return true
            if (list == null && res == true) return true
        } catch (_: Throwable) {}

        // then any public boolean method that takes a preference. we only accept it
        // if the child really ends up in the list, so removePreference can't fool us.
        val groupClass = groupClassOf(group) ?: return false
        val prefClass = preferenceClassOf(group)
        for (m in groupClass.declaredMethods) {
            if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.returnType == java.lang.Boolean.TYPE &&
                isPreferenceType(m.parameterTypes[0], prefClass)
            ) {
                try {
                    m.isAccessible = true
                    m.invoke(group, child)
                    if (list != null && list.contains(child)) return true
                } catch (_: Throwable) {}
            }
        }

        // some versions use a void add method instead. only accept it when we can
        // see the child in the list afterwards.
        if (list != null) {
            for (m in groupClass.declaredMethods) {
                if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.returnType == java.lang.Void.TYPE &&
                    isPreferenceType(m.parameterTypes[0], prefClass)
                ) {
                    try {
                        m.isAccessible = true
                        m.invoke(group, child)
                        if (list.contains(child)) return true
                    } catch (_: Throwable) {}
                }
            }
        }
        return false
    }

    private fun removePref(group: Any, child: Any): Boolean {
        val list = getPreferenceList(group)
        if (list != null && !list.contains(child)) return true

        // normal removePreference first
        try {
            val res = XposedHelpers.callMethod(group, "removePreference", child) as? Boolean
            if (list != null && !list.contains(child)) return true
            if (list == null && res == true) return true
        } catch (_: Throwable) {}

        // then any public boolean method that takes a preference
        val groupClass = groupClassOf(group) ?: return false
        val prefClass = preferenceClassOf(group)
        for (m in groupClass.declaredMethods) {
            if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.returnType == java.lang.Boolean.TYPE &&
                isPreferenceType(m.parameterTypes[0], prefClass)
            ) {
                try {
                    m.isAccessible = true
                    m.invoke(group, child)
                    if (list != null && !list.contains(child)) return true
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    private fun getParent(pref: Any): Any? {
        // androidx has getParent
        try {
            val parent = XposedHelpers.callMethod(pref, "getParent")
            if (parent != null && isPreferenceGroup(parent)) return parent
        } catch (_: Throwable) {}

        // otherwise find a no-arg method that returns a group
        var c: Class<*>? = pref.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.parameterTypes.isEmpty() && isGroupType(m.returnType)) {
                    try {
                        m.isAccessible = true
                        val parent = m.invoke(pref)
                        if (parent != null) return parent
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }

        // or a field of a group type
        c = pref.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (isGroupType(f.type)) {
                    try {
                        f.isAccessible = true
                        val parent = f.get(pref)
                        if (parent != null) return parent
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }

        // last try: the usual field name
        try {
            val parent = XposedHelpers.getObjectField(pref, "mParent")
            if (parent != null) return parent
        } catch (_: Throwable) {}

        return null
    }

    private fun setPrefKey(pref: Any, key: String) {
        // normal setKey first
        try {
            XposedHelpers.callMethod(pref, "setKey", key)
            if (getPrefKey(pref) == key) return
        } catch (_: Throwable) {}

        // then the field we already found
        cachedKeyField?.let {
            try {
                it.set(pref, key)
                if (getPrefKey(pref) == key) return
            } catch (_: Throwable) {}
        }

        // otherwise try each string field and check getKey() after setting it
        val fields = preferenceClassOf(pref)?.declaredFields ?: emptyArray()
        for (f in fields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == String::class.java) {
                try {
                    f.isAccessible = true
                    val oldVal = f.get(pref)
                    f.set(pref, key)
                    if (getPrefKey(pref) == key) {
                        cachedKeyField = f
                        return
                    }
                    f.set(pref, oldVal)
                } catch (_: Throwable) {}
            }
        }

        try {
            XposedHelpers.setObjectField(pref, "mKey", key)
        } catch (_: Throwable) {}
    }

    private fun setPrefTitle(pref: Any, title: CharSequence) {
        // normal setTitle first
        try {
            XposedHelpers.callMethod(pref, "setTitle", title)
            return
        } catch (_: Throwable) {}

        // title is the first CharSequence field on Preference
        val titleField = preferenceClassOf(pref)
            ?.declaredFields
            ?.firstOrNull { it.type == CharSequence::class.java }
        if (titleField != null) {
            try {
                titleField.isAccessible = true
                titleField.set(pref, title)
                return
            } catch (_: Throwable) {}
        }

        try {
            XposedHelpers.setObjectField(pref, "mTitle", title)
        } catch (_: Throwable) {}
    }

    private fun setPrefSummary(pref: Any, summary: CharSequence) {
        // normal setSummary first
        try {
            XposedHelpers.callMethod(pref, "setSummary", summary)
            return
        } catch (_: Throwable) {}

        // summary is the second CharSequence field
        val summaryField = preferenceClassOf(pref)
            ?.declaredFields
            ?.filter { it.type == CharSequence::class.java }
            ?.getOrNull(1)
        if (summaryField != null) {
            try {
                summaryField.isAccessible = true
                summaryField.set(pref, summary)
                return
            } catch (_: Throwable) {}
        }

        try {
            XposedHelpers.setObjectField(pref, "mSummary", summary)
        } catch (_: Throwable) {}
    }

    private fun setPrefOrder(pref: Any, order: Int) {
        // normal setOrder first
        try {
            XposedHelpers.callMethod(pref, "setOrder", order)
            return
        } catch (_: Throwable) {}

        // then the field we already found
        cachedOrderField?.let {
            try {
                it.isAccessible = true
                it.setInt(pref, order)
                return
            } catch (_: Throwable) {}
        }

        // otherwise probe which int field actually changes compareTo()
        val discovered = discoverOrderField(pref)
        if (discovered != null) {
            try {
                discovered.isAccessible = true
                discovered.setInt(pref, order)
                cachedOrderField = discovered
                return
            } catch (_: Throwable) {}
        }

        // last try: first non-static int field
        val orderField = preferenceClassOf(pref)
            ?.declaredFields
            ?.firstOrNull { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == java.lang.Integer.TYPE }
        if (orderField != null) {
            try {
                orderField.isAccessible = true
                orderField.setInt(pref, order)
                cachedOrderField = orderField
                return
            } catch (_: Throwable) {}
        }

        try {
            XposedHelpers.setIntField(pref, "mOrder", order)
        } catch (_: Throwable) {}
    }

    private fun discoverOrderField(pref: Any): java.lang.reflect.Field? {
        val prefClass = preferenceClassOf(pref) ?: return null
        val context = try {
            XposedHelpers.callMethod(pref, "getContext") as? android.content.Context
        } catch (_: Throwable) {
            null
        } ?: return null

        val ctor = try {
            prefClass.getConstructor(android.content.Context::class.java)
        } catch (_: Throwable) {
            return null
        }

        val a = try { ctor.newInstance(context) } catch (_: Throwable) { return null }
        val b = try { ctor.newInstance(context) } catch (_: Throwable) { return null }

        // make key and title equal so only the real order field can make compareTo non-zero
        setPrefKey(a, "__nt_order_probe")
        setPrefKey(b, "__nt_order_probe")
        setPrefTitle(a, "probe")
        setPrefTitle(b, "probe")

        val compareTo = try {
            java.lang.Comparable::class.java.getMethod("compareTo", Any::class.java)
        } catch (_: Throwable) {
            return null
        }

        for (f in prefClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type != java.lang.Integer.TYPE) continue
            val oldA = try { f.getInt(a) } catch (_: Throwable) { continue }
            val oldB = try { f.getInt(b) } catch (_: Throwable) { continue }
            try {
                f.isAccessible = true
                f.setInt(a, 1)
                f.setInt(b, 2)
                val result = try {
                    compareTo.invoke(a, b) as? Int ?: 0
                } catch (_: Throwable) {
                    0
                }
                if (result < 0) return f
            } finally {
                try { f.setInt(a, oldA) } catch (_: Throwable) {}
                try { f.setInt(b, oldB) } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun setPrefIcon(pref: Any, icon: Drawable) {
        // normal setIcon first
        try {
            XposedHelpers.callMethod(pref, "setIcon", icon)
            return
        } catch (_: Throwable) {}

        // then a public (Drawable) -> void method
        val prefClass = preferenceClassOf(pref)
        if (prefClass != null) {
            for (m in prefClass.declaredMethods) {
                if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    Drawable::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.returnType == java.lang.Void.TYPE
                ) {
                    try {
                        m.isAccessible = true
                        m.invoke(pref, icon)
                        return
                    } catch (_: Throwable) {}
                }
            }
            // or the first Drawable field
            for (f in prefClass.declaredFields) {
                if (Drawable::class.java.isAssignableFrom(f.type)) {
                    try {
                        f.isAccessible = true
                        f.set(pref, icon)
                        return
                    } catch (_: Throwable) {}
                }
            }
        }

        try {
            XposedHelpers.setObjectField(pref, "mIcon", icon)
        } catch (_: Throwable) {}
    }

    private fun setPrefIntent(pref: Any, intent: Intent) {
        // normal setIntent first
        try {
            XposedHelpers.callMethod(pref, "setIntent", intent)
            return
        } catch (_: Throwable) {}

        // then a public (Intent) -> void method
        val prefClass = preferenceClassOf(pref)
        if (prefClass != null) {
            for (m in prefClass.declaredMethods) {
                if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Intent::class.java &&
                    m.returnType == java.lang.Void.TYPE
                ) {
                    try {
                        m.isAccessible = true
                        m.invoke(pref, intent)
                        return
                    } catch (_: Throwable) {}
                }
            }
            // or the first Intent field
            for (f in prefClass.declaredFields) {
                if (f.type == Intent::class.java) {
                    try {
                        f.isAccessible = true
                        f.set(pref, intent)
                        return
                    } catch (_: Throwable) {}
                }
            }
        }

        try {
            XposedHelpers.setObjectField(pref, "mIntent", intent)
        } catch (_: Throwable) {}
    }

    private var cachedLayoutResField: java.lang.reflect.Field? = null

    private fun getPrefLayoutResource(pref: Any): Int {
        // normal getter first
        try {
            val res = XposedHelpers.callMethod(pref, "getLayoutResource") as? Int
            if (res != null && res != 0) return res
        } catch (_: Throwable) {}

        try {
            val res = XposedHelpers.getIntField(pref, "mLayoutResId")
            if (res != 0) return res
        } catch (_: Throwable) {}

        // then the field we already found
        try {
            val res = cachedLayoutResField?.getInt(pref) ?: 0
            if (res != 0) return res
        } catch (_: Throwable) {}

        // otherwise find the int field that currently holds a layout id
        val context = try {
            XposedHelpers.callMethod(pref, "getContext") as? android.content.Context
        } catch (_: Throwable) { null }
        val prefClass = preferenceClassOf(pref)
        if (prefClass != null) {
            for (f in prefClass.declaredFields) {
                if (f.type == java.lang.Integer.TYPE) {
                    try {
                        f.isAccessible = true
                        val v = f.getInt(pref)
                        if (v > 0 && context != null) {
                            try {
                                if (context.resources.getResourceTypeName(v) == "layout") {
                                    cachedLayoutResField = f
                                    return v
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
        return 0
    }

    /**
     * walk the screen for a filled-in preference that [accept] likes and use its
     * layout. only needed when the network row has no layout set.
     */
    private fun findAnyLayoutResource(group: Any, accept: (Any) -> Boolean): Int {
        val count = getPrefCount(group)
        for (i in 0 until count) {
            val item = getPrefAt(group, i) ?: continue
            if (accept(item)) {
                val res = getPrefLayoutResource(item)
                if (res != 0) return res
            }
            if (isPreferenceGroup(item)) {
                val nested = findAnyLayoutResource(item, accept)
                if (nested != 0) return nested
            }
        }
        return 0
    }

    private fun setPrefLayoutResource(pref: Any, resId: Int) {
        if (resId == 0) return
        // standard setLayoutResource
        try {
            XposedHelpers.callMethod(pref, "setLayoutResource", resId)
            return
        } catch (_: Throwable) {}

        // standard mLayoutResId field
        try {
            XposedHelpers.setIntField(pref, "mLayoutResId", resId)
            return
        } catch (_: Throwable) {}

        // cached layout field
        try {
            val f = cachedLayoutResField
            if (f != null) {
                f.isAccessible = true
                f.setInt(pref, resId)
                return
            }
        } catch (_: Throwable) {}

        // find int field holding a layout id
        val context = try {
            XposedHelpers.callMethod(pref, "getContext") as? android.content.Context
        } catch (_: Throwable) { null }
        val prefClass = preferenceClassOf(pref)
        if (prefClass != null) {
            for (f in prefClass.declaredFields) {
                if (f.type == java.lang.Integer.TYPE) {
                    try {
                        f.isAccessible = true
                        val v = f.getInt(pref)
                        if (v > 0 && context != null) {
                            try {
                                if (context.resources.getResourceTypeName(v) == "layout") {
                                    f.setInt(pref, resId)
                                    cachedLayoutResField = f
                                    return
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun setOrderingAsAdded(group: Any, ordering: Boolean) {
        // standard setOrderingAsAdded
        try {
            XposedHelpers.callMethod(group, "setOrderingAsAdded", ordering)
            return
        } catch (_: Throwable) {}

        val groupClass = groupClassOf(group)
        if (groupClass != null) {
            // cached ordering method
            cachedOrderingMethod?.let { m ->
                try {
                    m.isAccessible = true
                    m.invoke(group, ordering)
                    return
                } catch (_: Throwable) {}
            }

            // 3. PreferenceGroup declares both setOrderingAsAdded(Z) and an override of
            //    Preference.setEnabled(Z). The override shares its name/signature with a
            //    method declared on the Preference base class, so we can exclude it
            //    structurally instead of relying on declaration order.
            val prefClass = preferenceClassOf(group)
            for (m in groupClass.declaredMethods) {
                if (!java.lang.reflect.Modifier.isPublic(m.modifiers)) continue
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != java.lang.Boolean.TYPE) continue
                if (m.returnType != java.lang.Void.TYPE) continue
                if (prefClass != null && hasSameSignature(prefClass, m)) continue
                try {
                    m.isAccessible = true
                    m.invoke(group, ordering)
                    cachedOrderingMethod = m
                    return
                } catch (_: Throwable) {}
            }

            // 4. Last resort: the ordering flag is the first boolean declared directly on
            //    the group class (the inherited enabled flag lives on Preference).
            for (f in groupClass.declaredFields) {
                if (f.type == java.lang.Boolean.TYPE && !java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    try {
                        f.isAccessible = true
                        f.setBoolean(group, ordering)
                        return
                    } catch (_: Throwable) {}
                }
            }
        }

        try {
            XposedHelpers.setBooleanField(group, "mOrderingAsAdded", ordering)
        } catch (_: Throwable) {}
    }

    private fun hasSameSignature(owner: Class<*>, method: java.lang.reflect.Method): Boolean {
        var c: Class<*>? = owner
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == method.name &&
                    m.returnType == method.returnType &&
                    m.parameterTypes.contentEquals(method.parameterTypes)
                ) {
                    return true
                }
            }
            c = c.superclass
        }
        return false
    }

    private fun setPrefClickListener(pref: Any, classLoader: ClassLoader, onClick: () -> Unit) {
        var listenerClass = XposedHelpers.findClassIfExists(
            "androidx.preference.Preference\$OnPreferenceClickListener",
            classLoader
        )

        if (listenerClass == null) {
            // search from Preference base class
            val prefClass = preferenceClassOf(pref)
            if (prefClass != null) {
                for (m in prefClass.declaredMethods) {
                    if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0].isInterface &&
                        m.returnType == java.lang.Void.TYPE
                    ) {
                        val iface = m.parameterTypes[0]
                        for (im in iface.declaredMethods) {
                            if (im.returnType == java.lang.Boolean.TYPE && im.parameterTypes.size == 1) {
                                listenerClass = iface
                                break
                            }
                        }
                        if (listenerClass != null) break
                    }
                }
            }
        }

        if (listenerClass == null) return

        try {
            val listenerType = listenerClass
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerType)
            ) { _, method, _ ->
                if (method.declaringClass == listenerType &&
                    method.parameterTypes.size == 1 &&
                    method.returnType == java.lang.Boolean.TYPE
                ) {
                    onClick()
                    true
                } else {
                    null
                }
            }

            // standard setOnPreferenceChangeListener
            try {
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", proxy)
                return
            } catch (_: Throwable) {}

            // scan methods on Preference taking listener class
            val prefClass = preferenceClassOf(pref)
            if (prefClass != null) {
                for (m in prefClass.declaredMethods) {
                    if (java.lang.reflect.Modifier.isPublic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == listenerClass &&
                        m.returnType == java.lang.Void.TYPE
                    ) {
                        try {
                            m.isAccessible = true
                            m.invoke(pref, proxy)
                            return
                        } catch (_: Throwable) {}
                    }
                }
                // scan fields in Preference of listener type
                for (f in prefClass.declaredFields) {
                    if (f.type == listenerClass) {
                        try {
                            f.isAccessible = true
                            f.set(pref, proxy)
                            return
                        } catch (_: Throwable) {}
                    }
                }
            }

            try {
                XposedHelpers.setObjectField(pref, "mOnClickListener", proxy)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
