package com.souleven.nothingos.hooks

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AIClipboardHooks : HookModule {

    companion object {
        const val KEY_AI_CLIPBOARD = "ai_clipboard_gemini"
        private const val PACKAGE_GEMINI = "com.google.android.apps.bard"
        private const val PACKAGE_CHATGPT = "com.openai.chatgpt"

        private const val TEXT_TO_FILE_THRESHOLD = 3000
        private const val MAX_DIRECT_TEXT_LENGTH = 5000
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val classLoader = lpparam.classLoader

        fun hasGemini(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(PACKAGE_GEMINI, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun hasChatGPT(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo(PACKAGE_CHATGPT, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun getContext(obj: Any): Context? {
            return (obj as? View)?.context
                ?: try { XposedHelpers.getObjectField(obj, "mContext") as? Context } catch (_: Throwable) { null }
                ?: try { XposedHelpers.getObjectField(obj, "context") as? Context } catch (_: Throwable) { null }
                ?: run {
                    try {
                        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                        XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context
                    } catch (_: Throwable) { null }
                }
        }

        fun createTextFileUri(context: Context, text: String): Uri? {
            return try {
                val bundle = context.contentResolver.call(
                    RemotePreferenceProvider.CLIPBOARD_URI,
                    RemotePreferenceProvider.METHOD_SET_CLIPBOARD_TEXT,
                    text,
                    null
                )
                if (bundle?.getBoolean("success") == true) {
                    RemotePreferenceProvider.CLIPBOARD_URI
                } else {
                    null
                }
            } catch (e: Throwable) {
                XposedBridge.log("$TAG Clipboard: Failed to prepare text file: ${e.message}")
                null
            }
        }

        fun applyTextOrFileToIntent(context: Context, intent: Intent, text: String) {
            if (text.length > TEXT_TO_FILE_THRESHOLD) {
                val fileUri = createTextFileUri(context, text)
                if (fileUri != null) {
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    intent.type = "text/plain"
                    intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = android.content.ClipData.newUri(context.contentResolver, "clipboard_snippet.txt", fileUri)
                    try {
                        context.grantUriPermission(PACKAGE_GEMINI, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                    return
                }
            }

            // direct text mode (normal or fallback if file creation failed)
            val safeText = if (text.length > MAX_DIRECT_TEXT_LENGTH) {
                text.substring(0, MAX_DIRECT_TEXT_LENGTH) + "\n\n[Text truncated due to size]"
            } else {
                text
            }
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, safeText)
            intent.clipData = android.content.ClipData.newPlainText("text", safeText)
        }

        fun buildGeminiIntent(
            context: Context,
            clipData: android.content.ClipData?,
            fallbackText: String? = null
        ): Intent {
            val intent = Intent(Intent.ACTION_SEND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                `package` = PACKAGE_GEMINI
            }

            var text: String? = null
            var streamUri: Uri? = null

            if (clipData != null && clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                text = item.text?.toString()
                    ?: try { item.coerceToText(context)?.toString() } catch (_: Throwable) { null }
                streamUri = item.uri
            }

            if (text.isNullOrBlank() && !fallbackText.isNullOrBlank()) {
                text = fallbackText
            }

            if (streamUri != null) {
                val mimeType = try { context.contentResolver.getType(streamUri) } catch (_: Throwable) { null } ?: "image/*"
                intent.type = mimeType
                intent.putExtra(Intent.EXTRA_STREAM, streamUri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = android.content.ClipData.newUri(context.contentResolver, "image", streamUri)
                if (!text.isNullOrBlank()) {
                    val safeText = if (text.length > MAX_DIRECT_TEXT_LENGTH) text.substring(0, MAX_DIRECT_TEXT_LENGTH) else text
                    intent.putExtra(Intent.EXTRA_TEXT, safeText)
                }
            } else if (!text.isNullOrBlank()) {
                applyTextOrFileToIntent(context, intent, text)
            }

            return intent
        }

        fun extractClipData(controller: Any, context: Context): Pair<android.content.ClipData?, String?> {
            var clipData: android.content.ClipData? = null
            var fallbackText: String? = null

            val model = try {
                XposedHelpers.getObjectField(controller, "mClipboardModel")
            } catch (_: Throwable) { null }

            if (model != null) {
                clipData = try {
                    XposedHelpers.getObjectField(model, "clipData") as? android.content.ClipData
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.callMethod(model, "getClipData") as? android.content.ClipData
                    } catch (_: Throwable) { null }
                }

                if (clipData == null || clipData.itemCount == 0) {
                    fallbackText = try {
                        (XposedHelpers.getObjectField(model, "text") as? CharSequence)?.toString()
                    } catch (_: Throwable) {
                        try {
                            (XposedHelpers.callMethod(model, "getText") as? CharSequence)?.toString()
                        } catch (_: Throwable) { null }
                    }
                }
            }

            if ((clipData == null || clipData.itemCount == 0) && fallbackText.isNullOrBlank()) {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipData = cm?.primaryClip
                } catch (_: Throwable) {}
            }

            return Pair(clipData, fallbackText)
        }

        fun launchGeminiAndDismiss(controller: Any, clipDataArg: android.content.ClipData?): Boolean {
            val context = getContext(controller) ?: return false
            if (!hasGemini(context)) return false

            val (clipData, fallbackText) = if (clipDataArg != null) {
                Pair(clipDataArg, null)
            } else {
                extractClipData(controller, context)
            }

            val intent = buildGeminiIntent(context, clipData, fallbackText)

            try {
                context.startActivity(intent)
                val mode = if (intent.hasExtra(Intent.EXTRA_STREAM)) "file_stream" else "text"
                XposedBridge.log("$TAG Clipboard: Started Gemini ($mode)")
            } catch (e: Exception) {
                XposedBridge.log("$TAG Clipboard: Failed to start Gemini: ${e.message}")
            }

            // dismiss the clipboard overlay cleanly
            try {
                XposedHelpers.callMethod(controller, "hideImmediate")
            } catch (_: Throwable) {
                try {
                    val eventClass = XposedHelpers.findClass("com.android.systemui.clipboardoverlay.ClipboardOverlayEvent", classLoader)
                    val dismissedEvent = XposedHelpers.getStaticObjectField(eventClass, "CLIPBOARD_OVERLAY_DISMISSED_OTHER")
                    XposedHelpers.callMethod(controller, "finish", dismissedEvent)
                } catch (_: Throwable) {}
            }
            return true
        }

        // 1. force the chatgpt (eye) icon to appear for Clipboard
        val clipboardViewClass = XposedHelpers.findClassIfExists("com.nothing.systemui.clipboardoverlay.NTClipboardOverlayView", classLoader)
        if (clipboardViewClass != null) {
            try {
                XposedBridge.hookAllMethods(clipboardViewClass, "isChatGPTInstalled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val context = getContext(param.thisObject) ?: return
                        param.result = hasGemini(context) || hasChatGPT(context)
                    }
                })
            } catch (_: Throwable) {}
        }

        // 2. force the chatgpt (Eye) icon to appear for Screenshots
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
                            val context = getContext(param.thisObject) ?: return
                            param.result = hasGemini(context) || hasChatGPT(context)
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // 3. hijack clipboard intent creation (AOSP DefaultIntentCreator & ActionIntentCreator)
        val hookClipboardIntent = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                val clipData = param.args[0] as? android.content.ClipData ?: return
                val context = param.args[1] as? Context ?: return

                if (hasGemini(context)) {
                    param.result = buildGeminiIntent(context, clipData)
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
                } catch (_: Throwable) {}
            }
        }

        // 3.5. hook ClipboardOverlayController to prevent freezing and bypass ChatGPT package hardcoding
        val clipboardControllerClass = XposedHelpers.findClassIfExists("com.android.systemui.clipboardoverlay.ClipboardOverlayController", classLoader)
        if (clipboardControllerClass != null) {
            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "shareToAI", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val clipData = param.args.getOrNull(0) as? android.content.ClipData
                        if (launchGeminiAndDismiss(param.thisObject, clipData)) {
                            param.result = null // skip stock shareToAI which hardcodes com.openai.chatgpt
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "onShareAIClicked", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        if (launchGeminiAndDismiss(param.thisObject, null)) {
                            param.result = null // skip stock onShareAIClicked to avoid finishWithSharedTransition
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                XposedBridge.hookAllMethods(clipboardControllerClass, "finishWithSharedTransition", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val intent = (if (param.args.size > 1) param.args[1] else param.args.getOrNull(0)) as? Intent ?: return
                        val isGemini = intent.`package` == PACKAGE_GEMINI ||
                                intent.component?.packageName == PACKAGE_GEMINI
                        if (isGemini) {
                            val context = getContext(param.thisObject)
                            if (context != null) {
                                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                                if (extraText != null) {
                                    applyTextOrFileToIntent(context, intent, extraText)
                                }
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
            } catch (_: Throwable) {}
        }

        // 4. hijack screenshot intent creation and bypass shared transition
        val screenshotIntentCreatorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionIntentCreator", classLoader)
        if (screenshotIntentCreatorClass != null) {
            try {
                XposedBridge.hookAllMethods(screenshotIntentCreatorClass, "createShareAI", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val originalIntent = param.result as? Intent ?: return

                        val context = getContext(param.thisObject)
                        if (context != null && hasGemini(context)) {
                            originalIntent.component = null
                            originalIntent.setPackage(PACKAGE_GEMINI)
                            originalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            param.result = originalIntent
                        }
                    }
                })
            } catch (_: Throwable) {}
        }

        // 5. prevent freezing by bypassing startSharedTransition for Gemini
        val actionExecutorClass = XposedHelpers.findClassIfExists("com.android.systemui.screenshot.ActionExecutor", classLoader)
        if (actionExecutorClass != null) {
            try {
                XposedBridge.hookAllMethods(actionExecutorClass, "startSharedTransition", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!prefs.getBoolean(KEY_AI_CLIPBOARD, false)) return
                        val intent = param.args[0] as? Intent ?: return
                        val isGemini = intent.`package` == PACKAGE_GEMINI ||
                                intent.component?.packageName == PACKAGE_GEMINI
                        if (isGemini) {
                            val context = getContext(param.thisObject)
                            if (context != null) {
                                try {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }

                            // dismiss the screenshot overlay properly
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
            } catch (_: Throwable) {}
        }
    }
}
