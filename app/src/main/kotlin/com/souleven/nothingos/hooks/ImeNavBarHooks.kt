package com.souleven.nothingos.hooks

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.souleven.nothingos.MainHook.Companion.TAG

class ImeNavBarHooks : HookModule {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var modulePrefs: Prefs
    
    companion object {
        private const val PREF_HIDE_IME_BAR = "pref_hide_ime_bar"
        private const val IME_LAYOUT_WATCHER_KEY = "nothingxpert_ime_layout_watcher"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        modulePrefs = prefs
        if (lpparam.packageName == "com.google.android.inputmethod.latin") {
            installHideImeBarHook(lpparam)
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG [ImeNavBarHooks] $msg")
    }

    private inline fun safeHook(description: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$description failed: $t")
        }
    }

    private fun isHideImeBarEnabled(): Boolean {
        modulePrefs.forceReload()
        return modulePrefs.getBoolean(PREF_HIDE_IME_BAR, false)
    }

    private fun installHideImeBarHook(lpparam: LoadPackageParam) {
        safeHook("InputMethodService universal IME bar hooks") {
            val imsClass = InputMethodService::class.java

            XposedHelpers.findAndHookMethod(
                imsClass,
                "onWindowShown",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideImeBarEnabled()) return
                        val ims = param.thisObject
                        handler.post {
                            hideImeSwitcher(ims)
                            ensureImeLayoutWatcher(ims)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                imsClass,
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isHideImeBarEnabled()) return
                        val ims = param.thisObject
                        handler.post {
                            hideImeSwitcher(ims)
                            ensureImeLayoutWatcher(ims)
                        }
                    }
                }
            )

            try {
                XposedHelpers.findAndHookMethod(
                    imsClass,
                    "onComputeInsets",
                    InputMethodService.Insets::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isHideImeBarEnabled()) return
                            try {
                                val insets = param.args[0]
                                XposedHelpers.setIntField(
                                    insets,
                                    "contentTopInsets",
                                    XposedHelpers.getIntField(insets, "visibleTopInsets")
                                )
                                XposedHelpers.setIntField(insets, "touchableInsets", 0)
                            } catch (t: Throwable) {
                                log("Failed to adjust IME insets: $t")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                log("onComputeInsets hook failed: ${t.message}")
            }
            log("IME bar hider installed using InputMethodService base hooks")
        }

        safeHook("Gboard InputView padding override") {
            val inputViewClass = XposedHelpers.findClassIfExists(
                "com.google.android.libraries.inputmethod.inputview.InputView",
                lpparam.classLoader
            )
            if (inputViewClass != null) {
                XposedHelpers.findAndHookMethod(
                    View::class.java,
                    "setPadding",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isHideImeBarEnabled()) return
                            if (!inputViewClass.isInstance(param.thisObject)) return
                            val bottom = param.args[3] as? Int ?: return
                            if (bottom == 0) return
                            param.args[3] = 0
                        }
                    }
                )
                log("Gboard InputView padding override installed")
            }
        }
    }

    private fun ensureImeLayoutWatcher(ims: Any) {
        try {
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: return
            val decor = dialog.window?.decorView ?: return
            val existing = XposedHelpers.getAdditionalInstanceField(decor, IME_LAYOUT_WATCHER_KEY)
            if (existing != null) return

            var isHandlingLayout = false
            var lastLayoutCheck = 0L
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!isHideImeBarEnabled()) return@OnGlobalLayoutListener
                if (isHandlingLayout) return@OnGlobalLayoutListener
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLayoutCheck < 100) return@OnGlobalLayoutListener
                lastLayoutCheck = now
                isHandlingLayout = true
                try {
                    hideImeSwitcher(ims)
                } finally {
                    isHandlingLayout = false
                }
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            XposedHelpers.setAdditionalInstanceField(decor, IME_LAYOUT_WATCHER_KEY, listener)
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    val stored = XposedHelpers.getAdditionalInstanceField(v, IME_LAYOUT_WATCHER_KEY)
                        as? ViewTreeObserver.OnGlobalLayoutListener
                    if (stored != null && v.viewTreeObserver.isAlive) {
                        v.viewTreeObserver.removeOnGlobalLayoutListener(stored)
                    }
                    XposedHelpers.removeAdditionalInstanceField(v, IME_LAYOUT_WATCHER_KEY)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        } catch (t: Throwable) {
            log("Failed to install IME layout watcher: $t")
        }
    }

    private fun hideImeSwitcher(ims: Any) {
        try {
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: return
            val window = dialog.window ?: return
            val decor = window.decorView
            var anyChanged = false
            val ids = listOf(
                "input_method_nav_bar",
                "input_method_nav_back",
                "input_method_nav_ime_switcher",
                "input_method_nav_home_handle",
                "input_method_nav_gesture"
            )
            for (name in ids) {
                val id = decor.resources.getIdentifier(name, "id", "android")
                if (id == 0) continue
                val v = decor.findViewById<View>(id) ?: continue
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    anyChanged = true
                }
                if (v.alpha != 0f) {
                    v.alpha = 0f
                    anyChanged = true
                }
                val lp = v.layoutParams
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    v.layoutParams = lp
                    anyChanged = true
                }
                if (zeroBottomPaddingUp(v)) {
                    anyChanged = true
                }
            }
            if (hideImeNavBarClasses(decor)) anyChanged = true
            if (zeroBottomPaddingUp(decor, 6)) anyChanged = true
            if (adjustImeInputViewPadding(decor)) anyChanged = true
            if (stripImeBottomInset(decor)) anyChanged = true

            if (anyChanged) {
                decor.requestLayout()
            }
        } catch (t: Throwable) {
            log("Failed to hide IME nav bar: $t")
        }
    }

    private fun hideImeNavBarClasses(root: View): Boolean {
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isImeNavBar = className.contains("inputmethodservice.navigationbar.NavigationBarFrame") ||
                className.endsWith(".NavigationBarFrame") ||
                className.contains("inputmethodservice.navigationbar.NavigationBarView") ||
                className.endsWith(".NavigationBarView")
            if (!isImeNavBar) return

            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                changed = true
            }
            if (v.alpha != 0f) {
                v.alpha = 0f
                changed = true
            }
            val lp = v.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                v.layoutParams = lp
                changed = true
            }
            if (v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                changed = true
            }
        }
        visit(root)
        return changed
    }

    private fun zeroBottomPaddingUp(view: View?, depth: Int = 3): Boolean {
        var changed = false
        var current: Any? = view
        repeat(depth) {
            val v = current as? View ?: return changed
            if (v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                changed = true
            }
            current = v.parent
        }
        return changed
    }

    private fun stripImeBottomInset(root: View): Boolean {
        val navBarHeight = getNavBarHeight(root) ?: return false
        if (root.height <= 0) return false
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val resId = v.id
            val pkgId = (resId ushr 24) and 0xFF
            val name = if (resId > 0 && (pkgId == 0x01 || pkgId == 0x7F)) {
                runCatching { v.resources.getResourceEntryName(resId) }.getOrNull()?.lowercase()
            } else null
            val lp = v.layoutParams
            val heightMatches = v.height == navBarHeight || lp?.height == navBarHeight
            val nearBottom = v.bottom >= (root.height - navBarHeight - 4)
            val nameMatches = name?.contains("nav") == true ||
                name?.contains("gesture") == true ||
                name?.contains("ime") == true ||
                name?.contains("inset") == true ||
                name?.contains("bar") == true
            val isSpacer = v.javaClass.name.endsWith("Space")
            val isInputView = v.javaClass.name.contains("inputview.InputView")
            if (isInputView && v.paddingBottom != 0) {
                if (adjustImeInputViewPadding(v)) {
                    changed = true
                }
                return
            }
            if (nearBottom && (heightMatches || nameMatches || isSpacer)) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    changed = true
                }
                if (v.alpha != 0f) {
                    v.alpha = 0f
                    changed = true
                }
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    v.layoutParams = lp
                    changed = true
                }
                if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    v.layoutParams = lp
                    changed = true
                }
                if (v.paddingBottom != 0) {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    changed = true
                }
            }
        }
        visit(root)
        if (root.paddingBottom != 0) {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            changed = true
        }
        return changed
    }

    private fun adjustImeInputViewPadding(root: View): Boolean {
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isInputView = className.contains("inputview.InputView")
            if (isInputView && v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                changed = true
            }
        }
        visit(root)
        return changed
    }
    
    private fun getNavBarHeight(view: View): Int? {
        val res = view.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) return null
        return runCatching { res.getDimensionPixelSize(id) }.getOrNull()
    }
}
