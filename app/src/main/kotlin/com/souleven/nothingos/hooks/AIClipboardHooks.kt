package com.souleven.nothingos.hooks

import android.content.Intent
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AIClipboardHooks : HookModule {

    companion object {
        const val KEY_AI_CLIPBOARD = "ai_clipboard_gemini"
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        val hasGeminiCache = mutableMapOf<String, Boolean>()
        
        fun hasGemini(context: android.content.Context): Boolean {
            return hasGeminiCache.getOrPut("gemini") {
                try { context.packageManager.getPackageInfo("com.google.android.apps.bard", 0); true } catch (e: Exception) { false }
            }
        }
        
        fun hasChatGPT(context: android.content.Context): Boolean {
            return try { context.packageManager.getPackageInfo("com.openai.chatgpt", 0); true } catch (e: Exception) { false }
        }

        // 1. Force the ChatGPT (Eye) icon to appear for Clipboard
        val clipboardViewClass = XposedHelpers.findClassIfExists("com.nothing.systemui.clipboardoverlay.NTClipboardOverlayView", classLoader)
        if (clipboardViewClass != null) {
            try {
                XposedBridge.hookAllMethods(clipboardViewClass, "isChatGPTInstalled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val context = try {
                            (param.thisObject as? android.view.View)?.context
                        } catch (_: Throwable) { null } ?: try {
                            XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context
                        } catch (_: Throwable) { null } ?: run {
                            try {
                                val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                                XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                            } catch (_: Throwable) { null }
                        } ?: return
                        param.result = hasGemini(context) || hasChatGPT(context)
                    }
                })
            } catch (t: Throwable) {}
        }
        
        // 2. Force the ChatGPT (Eye) icon to appear for Screenshots
        arrayOf(
            "com.android.systemui.screenshot.LegacyScreenshotController",
            "com.android.systemui.screenshot.ScreenshotController"
        ).forEach { className ->
            val screenshotControllerClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (screenshotControllerClass != null) {
                try {
                    XposedBridge.hookAllMethods(screenshotControllerClass, "isChatGPTInstalled", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                            val context = try {
                                XposedHelpers.getObjectField(param.thisObject, "context") as? android.content.Context
                            } catch (_: Throwable) {
                                try {
                                    XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context
                                } catch (_: Throwable) { null }
                            } ?: run {
                                try {
                                    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                                    XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                                } catch (_: Throwable) { null }
                            } ?: return
                            param.result = hasGemini(context) || hasChatGPT(context)
                        }
                    })
                } catch (t: Throwable) {}
            }
        }

        // 3. Hijack Clipboard Intent Creation (AOSP DefaultIntentCreator & ActionIntentCreator)
        val hookClipboardIntent = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                val clipData = param.args[0] as? android.content.ClipData ?: return
                val context = param.args[1] as? android.content.Context ?: return
                
                if (hasGemini(context)) {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    
                    if (clipData.itemCount > 0) {
                        val item = clipData.getItemAt(0)
                        val text = item.text?.toString()
                            ?: try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
                            ?: ""
                        if (text.isNotBlank()) {
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, text)
                            intent.clipData = android.content.ClipData.newPlainText("text", text)
                        } else if (item.uri != null) {
                            intent.type = "image/*"
                            intent.putExtra(Intent.EXTRA_STREAM, item.uri)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = android.content.ClipData.newUri(context.contentResolver, "image", item.uri)
                        }
                    }
                    intent.setPackage("com.google.android.apps.bard")
                    param.result = intent
                }
            }
        }
        
        arrayOf(
            "com.android.systemui.clipboardoverlay.DefaultIntentCreator",
            "com.android.systemui.clipboardoverlay.ActionIntentCreator"
        ).forEach { className ->
            val creatorClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (creatorClass != null) {
                try {
                    XposedBridge.hookAllMethods(creatorClass, "getGPTIntent", hookClipboardIntent)
                } catch (t: Throwable) {}
            }
        }

        // 3.5. Hook ClipboardOverlayController to prevent freezing and bypass ChatGPT package hardcoding
        val clipboardControllerClass = XposedHelpers.findClassIfExists("com.android.systemui.clipboardoverlay.ClipboardOverlayController", classLoader)
        if (clipboardControllerClass != null) {
            val getClipDataFromController = { controller: Any, context: android.content.Context ->
                val model = try {
                    XposedHelpers.getObjectField(controller, "mClipboardModel")
                } catch (_: Throwable) { null }
                
                val modelClip = model?.let { m ->
                    try {
                        XposedHelpers.getObjectField(m, "clipData") as? android.content.ClipData
                    } catch (_: Throwable) {
                        try {
                            XposedHelpers.callMethod(m, "getClipData") as? android.content.ClipData
                        } catch (_: Throwable) { null }
                    }
                }
                if (modelClip != null && modelClip.itemCount > 0) {
                    modelClip
                } else {
                    try {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.primaryClip
                    } catch (_: Throwable) { null }
                }
            }

            val launchGeminiAndDismiss = { controller: Any, clipDataArg: android.content.ClipData? ->
                val context = try {
                    XposedHelpers.getObjectField(controller, "mContext") as? android.content.Context
                } catch (_: Throwable) { null } ?: run {
                    try {
                        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                        XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                    } catch (_: Throwable) { null }
                }

                if (context != null && hasGemini(context)) {
                    val clipData = clipDataArg ?: getClipDataFromController(controller, context)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        `package` = "com.google.android.apps.bard"
                    }

                    var hasContent = false
                    if (clipData != null && clipData.itemCount > 0) {
                        val item = clipData.getItemAt(0)
                        val text = item.text?.toString()
                            ?: try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
                            ?: ""
                        if (text.isNotBlank()) {
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, text)
                            intent.clipData = android.content.ClipData.newPlainText("text", text)
                            hasContent = true
                        } else if (item.uri != null) {
                            intent.type = "image/*"
                            intent.putExtra(Intent.EXTRA_STREAM, item.uri)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = android.content.ClipData.newUri(context.contentResolver, "image", item.uri)
                            hasContent = true
                        }
                    }

                    if (!hasContent) {
                        val model = try { XposedHelpers.getObjectField(controller, "mClipboardModel") } catch (_: Throwable) { null }
                        val modelText = model?.let {
                            try { (XposedHelpers.getObjectField(it, "text") as? CharSequence)?.toString() } catch (_: Throwable) {
                                try { (XposedHelpers.callMethod(it, "getText") as? CharSequence)?.toString() } catch (_: Throwable) { null }
                            }
                        }
                        if (!modelText.isNullOrBlank()) {
                            intent.type = "text/plain"
                            intent.putExtra(Intent.EXTRA_TEXT, modelText)
                            intent.clipData = android.content.ClipData.newPlainText("text", modelText)
                            hasContent = true
                        }
                    }

                    try {
                        context.startActivity(intent)
                        XposedBridge.log("$TAG Clipboard: Started Gemini with text=${intent.getStringExtra(Intent.EXTRA_TEXT)}")
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG Clipboard: Failed to start Gemini: ${e.message}")
                    }

                    // Dismiss the clipboard overlay cleanly
                    try {
                        XposedHelpers.callMethod(controller, "hideImmediate")
                    } catch (_: Throwable) {
                        try {
                            val eventClass = XposedHelpers.findClass("com.android.systemui.clipboardoverlay.ClipboardOverlayEvent", classLoader)
                            val dismissedEvent = XposedHelpers.getStaticObjectField(eventClass, "CLIPBOARD_OVERLAY_DISMISSED_OTHER")
                            XposedHelpers.callMethod(controller, "finish", dismissedEvent)
                        } catch (_: Throwable) {}
                    }
                    true
                } else {
                    false
                }
            }

            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "shareToAI", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val clipData = param.args.getOrNull(0) as? android.content.ClipData
                        if (launchGeminiAndDismiss(param.thisObject, clipData)) {
                            param.result = null // Skip stock shareToAI which hardcodes com.openai.chatgpt
                        }
                    }
                })
            } catch (t: Throwable) {}

            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "onShareAIClicked", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        if (launchGeminiAndDismiss(param.thisObject, null)) {
                            param.result = null // Skip stock onShareAIClicked to avoid finishWithSharedTransition
                        }
                    }
                })
            } catch (t: Throwable) {}

            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "finishWithSharedTransition", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val intent = (if (param.args.size > 1) param.args[1] else param.args.getOrNull(0)) as? Intent ?: return
                        val isGemini = intent.`package` == "com.google.android.apps.bard" ||
                                intent.component?.packageName == "com.google.android.apps.bard"
                        if (isGemini) {
                            val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                            if (extraText != null) {
                                intent.putExtra(Intent.EXTRA_TEXT, extraText)
                                intent.clipData = android.content.ClipData.newPlainText("text", extraText)
                            }
                            val context = try {
                                XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context
                            } catch (_: Throwable) { null }
                            if (context != null) {
                                try {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            try {
                                XposedHelpers.callMethod(param.thisObject, "hideImmediate")
                            } catch (_: Throwable) {}
                            param.result = null // prevent freezing in shared transition
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
        
        // 4. Hijack Screenshot Intent Creation and Bypass Shared Transition
        val screenshotIntentCreatorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionIntentCreator", classLoader)
        if (screenshotIntentCreatorClass != null) {
            try {
                XposedBridge.hookAllMethods(screenshotIntentCreatorClass, "createShareAI", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val originalIntent = param.result as? Intent ?: return
                        
                        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                        val context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                        if (context != null && hasGemini(context)) {
                            originalIntent.component = null
                            originalIntent.setPackage("com.google.android.apps.bard")
                            originalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            param.result = originalIntent
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
        
        // 5. Prevent freezing by bypassing startSharedTransition for Gemini
        val actionExecutorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionExecutor", classLoader)
        if (actionExecutorClass != null) {
            try {
                XposedBridge.hookAllMethods(actionExecutorClass, "startSharedTransition", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val intent = param.args[0] as? Intent ?: return
                        val isGemini = intent.`package` == "com.google.android.apps.bard" ||
                                intent.component?.packageName == "com.google.android.apps.bard"
                        if (isGemini) {
                            // Bypass shared transition which freezes if the target app doesn't support it
                            val context = try {
                                val window = XposedHelpers.getObjectField(param.thisObject, "window") as? android.view.Window
                                window?.context
                            } catch (_: Throwable) { null } ?: run {
                                try {
                                    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
                                    XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                                } catch (_: Throwable) { null }
                            }

                            if (context != null) {
                                try {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            
                            // Dismiss the screenshot overlay properly
                            try {
                                val finishDismiss = XposedHelpers.getObjectField(param.thisObject, "finishDismiss")
                                if (finishDismiss != null) {
                                    XposedHelpers.callMethod(finishDismiss, "invoke")
                                }
                            } catch (_: Throwable) {}

                            try {
                                val viewProxy = XposedHelpers.getObjectField(param.thisObject, "viewProxy")
                                if (viewProxy != null) {
                                    try {
                                        XposedHelpers.callMethod(viewProxy, "requestDismissal", null as Any?)
                                    } catch (_: Throwable) {
                                        try {
                                            XposedHelpers.callMethod(viewProxy, "requestDismissal")
                                        } catch (_: Throwable) {}
                                    }
                                }
                            } catch (_: Throwable) {}

                            try {
                                XposedHelpers.setBooleanField(param.thisObject, "isPendingSharedTransition", false)
                            } catch (_: Throwable) {}

                            param.result = null // skip the original startSharedTransition
                        }
                    }
                })
            } catch (t: Throwable) {}
        }
    }
}
