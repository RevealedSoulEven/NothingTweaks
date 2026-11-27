package com.souleven.nothingos.hooks

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import de.robv.android.xposed.XposedHelpers

internal class LauncherIconPreviews(private val loader: ClassLoader, initializer: Any) {
    private val context = XposedHelpers.getObjectField(initializer, "context") as Context
    private val iconCache = XposedHelpers.getObjectField(initializer, "iconCache")
    private val iconProvider by lazy { XposedHelpers.getObjectField(initializer, "iconProvider") }
    private val deviceProfile by lazy { XposedHelpers.getObjectField(initializer, "idp") }
    private val previewFactoryCompanion by lazy {
        XposedHelpers.getStaticObjectField(XposedHelpers.findClass("p7.h", loader), "b")
    }
    private val iconOptionsClass by lazy { XposedHelpers.findClass("k7.e", loader) }
    private val appInfo = XposedHelpers.findClass("com.android.launcher3.model.data.AppInfo", loader)
    private val lookupFlag = XposedHelpers.getStaticObjectField(
        XposedHelpers.findClass("com.android.launcher3.icons.cache.CacheLookupFlag", loader), "DEFAULT_LOOKUP_FLAG"
    )
    private val handler = XposedHelpers.callMethod(
        XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass("com.android.launcher3.util.Executors", loader), "MODEL_EXECUTOR"
        ), "getHandler"
    ) as Handler
    private var generation = 0

    fun register() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handleRequest()
            }
        }
        context.contentResolver.registerContentObserver(IconPreviewRequests.uri, false, observer)
        handler.post { handleRequest() }
    }

    private fun handleRequest() {
        val request = runCatching {
            context.contentResolver.call(IconPreviewRequests.uri, IconPreviewRequests.METHOD_TAKE, null, null)
        }.getOrNull() ?: return
        val reply = request.getParcelable(IconPreviewRequests.KEY_REPLY, Messenger::class.java) ?: return
        val components = request.getParcelableArrayList(IconPreviewRequests.KEY_COMPONENTS, ComponentName::class.java)
            ?: return
        val activities = runCatching {
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .associateBy { it.componentName }
        }.getOrNull() ?: return
        val canGenerate = IconPackScope.isNothingIconInstalled(context.packageManager)
        sendBatch(components.mapNotNull(activities::get).iterator(), reply, ++generation, canGenerate)
    }

    private fun sendBatch(
        activities: Iterator<LauncherActivityInfo>,
        reply: Messenger,
        requestGeneration: Int,
        canGenerate: Boolean
    ) {
        if (requestGeneration != generation) return
        val previews = Bundle()
        var count = 0
        while (count < BATCH_SIZE && activities.hasNext()) {
            val activity = activities.next()
            val preview = runCatching {
                val drawable = loadThemedDrawable(activity)
                    ?: (if (canGenerate) generateThemedDrawable(activity) else null)
                    ?: return@runCatching null
                // Launcher drawables can contain hardware-backed layers.
                val picture = Picture()
                drawable.setBounds(0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
                val canvas = picture.beginRecording(PREVIEW_SIZE, PREVIEW_SIZE)
                try {
                    drawable.draw(canvas)
                } finally {
                    picture.endRecording()
                }
                Bitmap.createBitmap(picture, PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            if (preview != null) {
                previews.putParcelable(activity.componentName.packageName, preview)
            }
            count++
        }
        try {
            if (!previews.isEmpty) reply.send(Message.obtain().apply { data = previews })
        } catch (_: RemoteException) {
            return
        }
        if (activities.hasNext()) {
            handler.postDelayed({ sendBatch(activities, reply, requestGeneration, canGenerate) }, 16L)
        }
    }

    private fun loadThemedDrawable(activity: LauncherActivityInfo): Drawable? = runCatching {
        val item = XposedHelpers.newInstance(appInfo, context, activity, activity.user)
        XposedHelpers.callMethod(iconCache, "getTitleAndIcon", item, activity, lookupFlag)
        themedDrawable(XposedHelpers.getObjectField(item, "bitmap"), activity.componentName.packageName)
    }.getOrNull()

    private fun generateThemedDrawable(activity: LauncherActivityInfo): Drawable? {
        // Nothing Launcher 4.0.21 uses this factory to build monochrome even outside the current icon theme.
        val packageName = activity.componentName.packageName
        val size = XposedHelpers.getIntField(deviceProfile, "iconBitmapSize")
        val dpi = XposedHelpers.getIntField(deviceProfile, "fillResIconDpi")
        val icon = runCatching {
            XposedHelpers.callMethod(iconProvider, "getIcon", false, activity, dpi) as? Drawable
        }.getOrNull() ?: activity.getIcon(dpi)
        val options = XposedHelpers.newInstance(iconOptionsClass, context)
        XposedHelpers.callMethod(options, "r", packageName)
        val factory = XposedHelpers.callMethod(previewFactoryCompanion, "b", context, size) as AutoCloseable
        return factory.use {
            val bitmapInfo = XposedHelpers.callMethod(it, "createBadgedIconBitmap", icon, options)
            themedDrawable(bitmapInfo, packageName)
        }
    }

    private fun themedDrawable(bitmapInfo: Any, packageName: String): Drawable? {
        val themed = XposedHelpers.callMethod(bitmapInfo, "getThemedBitmap") ?: return null
        return XposedHelpers.callMethod(themed, "newDrawable", bitmapInfo, context, packageName) as? Drawable
    }

    private companion object {
        const val PREVIEW_SIZE = 80
        const val BATCH_SIZE = 8
    }
}
