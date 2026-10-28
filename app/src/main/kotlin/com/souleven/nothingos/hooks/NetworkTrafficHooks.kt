package com.souleven.nothingos.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.ArrayList
import java.util.Locale

class NetworkTrafficHooks : HookModule {

    companion object {
        const val KEY_ENABLED = "pref_network_traffic_enabled"
        const val KEY_POSITION = "pref_network_traffic_position"
        const val KEY_MODE = "pref_network_traffic_mode"
        const val KEY_AUTOHIDE = "pref_network_traffic_autohide"
        const val KEY_SHOW_ARROWS = "pref_network_traffic_show_arrows"

        private const val CLASS_CONTROLLER = "com.android.systemui.statusbar.phone.PhoneStatusBarViewController"
        private const val CLASS_CLOCK = "com.android.systemui.statusbar.policy.Clock"
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private lateinit var prefsRef: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentController: Any? = null
    private var trafficView: TextView? = null
    private var clockView: TextView? = null

    private var screenOn = true
    private var isLoopRunning = false
    private var receiverRegistered = false

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastUpdateTime = 0L
    private var currentTotalSpeed = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    lastRxBytes = -1L
                    lastTxBytes = -1L
                    lastUpdateTime = 0L
                    if (::prefsRef.isInitialized && prefsRef.getBoolean(KEY_ENABLED, false)) {
                        startLoop()
                    }
                }
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!screenOn || !::prefsRef.isInitialized || !prefsRef.getBoolean(KEY_ENABLED, false) || trafficView == null) {
                isLoopRunning = false
                return
            }

            val speeds = calculateSpeed()
            if (speeds != null) {
                val (rxSpeed, txSpeed) = speeds
                currentTotalSpeed = rxSpeed + txSpeed
                updateTrafficText(rxSpeed, txSpeed)
            }

            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        prefsRef = prefs

        // Listen for preference changes so UI immediately updates without reboot
        prefs.addListener {
            mainHandler.post {
                updateFromPrefs()
            }
        }

        hookPhoneStatusBarViewController(lpparam.classLoader)
        hookClockTint(lpparam.classLoader)
    }

    private fun hookPhoneStatusBarViewController(classLoader: ClassLoader) {
        val controllerClass = try {
            XposedHelpers.findClass(CLASS_CONTROLLER, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] Class not found: $CLASS_CONTROLLER — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "onViewAttached",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        setupNetworkTraffic(param.thisObject)
                    }
                }
            )
            XposedBridge.log("$TAG [NetworkTraffic] Hooked PhoneStatusBarViewController#onViewAttached")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] FAILED to hook onViewAttached: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "onViewDetached",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject === currentController) {
                            stopLoop()
                            (trafficView?.parent as? ViewGroup)?.removeView(trafficView)
                            currentController = null
                        }
                    }
                }
            )
            XposedBridge.log("$TAG [NetworkTraffic] Hooked PhoneStatusBarViewController#onViewDetached")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] FAILED to hook onViewDetached: ${t.message}")
        }
    }

    private fun hookClockTint(classLoader: ClassLoader) {
        val clockClass = try {
            XposedHelpers.findClass(CLASS_CLOCK, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] Class not found: $CLASS_CLOCK — ${t.message}")
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                clockClass,
                "onDarkChanged",
                ArrayList::class.java,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clock = param.thisObject as? TextView ?: return
                        val color = clock.currentTextColor
                        mainHandler.post {
                            trafficView?.setTextColor(color)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] FAILED to hook Clock#onDarkChanged: ${t.message}")
        }

        try {
            XposedHelpers.findAndHookMethod(
                clockClass,
                "onColorsChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clock = param.thisObject as? TextView ?: return
                        val color = clock.currentTextColor
                        mainHandler.post {
                            trafficView?.setTextColor(color)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [NetworkTraffic] FAILED to hook Clock#onColorsChanged: ${t.message}")
        }
    }

    private fun setupNetworkTraffic(controller: Any) {
        currentController = controller

        val statusBarView = try {
            XposedHelpers.getObjectField(controller, "mView") as? ViewGroup
        } catch (_: Throwable) { null } ?: return

        val context = statusBarView.context

        clockView = try {
            XposedHelpers.getObjectField(controller, "clock") as? TextView
        } catch (_: Throwable) { null }

        if (trafficView == null || trafficView?.context !== context) {
            trafficView = TextView(context).apply {
                id = View.generateViewId()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                isSingleLine = true
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                val pad = (3 * context.resources.displayMetrics.density).toInt()
                setPaddingRelative(pad, 0, pad, 0)
            }
        }

        val tv = trafficView ?: return

        clockView?.let { clock ->
            tv.typeface = clock.typeface
            tv.setTextColor(clock.currentTextColor)
            tv.letterSpacing = clock.letterSpacing
        }

        ensureScreenReceiver(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = pm?.isInteractive ?: true

        applyPositionAndVisibility()

        if (::prefsRef.isInitialized && prefsRef.getBoolean(KEY_ENABLED, false) && screenOn) {
            startLoop()
        }
    }

    private fun ensureScreenReceiver(context: Context) {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Throwable) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                context.registerReceiver(screenReceiver, filter)
                receiverRegistered = true
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [NetworkTraffic] FAILED to register screen receiver: ${t.message}")
            }
        }
    }

    private fun updateFromPrefs() {
        applyPositionAndVisibility()
        val enabled = prefsRef.getBoolean(KEY_ENABLED, false)
        if (enabled && screenOn) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    private fun applyPositionAndVisibility() {
        val tv = trafficView ?: return
        val controller = currentController ?: return
        val enabled = if (::prefsRef.isInitialized) prefsRef.getBoolean(KEY_ENABLED, false) else false

        if (!enabled) {
            tv.visibility = View.GONE
            (tv.parent as? ViewGroup)?.removeView(tv)
            return
        }

        val position = prefsRef.getString(KEY_POSITION, "left")
        val clock = clockView ?: (try {
            XposedHelpers.getObjectField(controller, "clock") as? View
        } catch (_: Throwable) { null })

        val endSide = try {
            XposedHelpers.getObjectField(controller, "endSideContainer") as? View
        } catch (_: Throwable) { null }

        val statusBarView = try {
            XposedHelpers.getObjectField(controller, "mView") as? ViewGroup
        } catch (_: Throwable) { null }

        var targetParent: ViewGroup? = null
        var targetIndex = -1

        if (position == "right") {
            if (endSide != null && endSide.parent is ViewGroup) {
                targetParent = endSide.parent as ViewGroup
                targetIndex = targetParent.indexOfChild(endSide).coerceAtLeast(0)
            } else if (endSide is ViewGroup) {
                targetParent = endSide
                targetIndex = 0
            } else if (statusBarView != null) {
                val endId = statusBarView.resources.getIdentifier(
                    "status_bar_end_side_content",
                    "id",
                    statusBarView.context.packageName
                )
                val endContainer = if (endId != 0) statusBarView.findViewById<ViewGroup>(endId) else null
                if (endContainer != null) {
                    targetParent = endContainer
                    targetIndex = 0
                }
            }
        } else {
            // Default "left": next to clock
            if (clock != null && clock.parent is ViewGroup) {
                targetParent = clock.parent as ViewGroup
                val clockIdx = targetParent.indexOfChild(clock)
                targetIndex = if (clockIdx >= 0) clockIdx + 1 else targetParent.childCount
            } else if (statusBarView != null) {
                val startId = statusBarView.resources.getIdentifier(
                    "status_bar_start_side_content",
                    "id",
                    statusBarView.context.packageName
                )
                val startContainer = if (startId != 0) statusBarView.findViewById<ViewGroup>(startId) else null
                if (startContainer != null) {
                    targetParent = startContainer
                    targetIndex = 1.coerceAtMost(startContainer.childCount)
                }
            }
        }

        if (targetParent == null) return

        val currentParent = tv.parent as? ViewGroup
        if (currentParent !== targetParent || targetParent.indexOfChild(tv) != targetIndex) {
            currentParent?.removeView(tv)

            val lp = when (targetParent) {
                is LinearLayout -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER_VERTICAL }
                is FrameLayout -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER_VERTICAL }
                else -> ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val safeIndex = targetIndex.coerceIn(0, targetParent.childCount)
            targetParent.addView(tv, safeIndex, lp)
        }

        val autoHide = prefsRef.getBoolean(KEY_AUTOHIDE, true)
        if (autoHide && currentTotalSpeed < 1024L) {
            tv.visibility = View.GONE
        } else {
            tv.visibility = View.VISIBLE
        }
    }

    private fun startLoop() {
        if (isLoopRunning) return
        isLoopRunning = true
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastUpdateTime = 0L
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.post(updateRunnable)
    }

    private fun stopLoop() {
        isLoopRunning = false
        mainHandler.removeCallbacks(updateRunnable)
    }

    private fun calculateSpeed(): Pair<Long, Long>? {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()

        if (totalRx == TrafficStats.UNSUPPORTED.toLong() || totalTx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }

        if (lastRxBytes < 0L || lastTxBytes < 0L || lastUpdateTime == 0L) {
            lastRxBytes = totalRx
            lastTxBytes = totalTx
            lastUpdateTime = now
            return null
        }

        val timeDeltaMs = now - lastUpdateTime
        if (timeDeltaMs <= 0) return null

        val rxDiff = totalRx - lastRxBytes
        val txDiff = totalTx - lastTxBytes

        lastRxBytes = totalRx
        lastTxBytes = totalTx
        lastUpdateTime = now

        if (rxDiff < 0 || txDiff < 0) {
            return null
        }

        val rxSpeed = (rxDiff * 1000L) / timeDeltaMs
        val txSpeed = (txDiff * 1000L) / timeDeltaMs

        return Pair(rxSpeed, txSpeed)
    }

    private fun updateTrafficText(rxSpeed: Long, txSpeed: Long) {
        val tv = trafficView ?: return
        val totalSpeed = rxSpeed + txSpeed
        val autoHide = prefsRef.getBoolean(KEY_AUTOHIDE, true)

        if (autoHide && totalSpeed < 1024L) {
            if (tv.visibility != View.GONE) {
                tv.visibility = View.GONE
            }
            return
        }

        if (tv.visibility != View.VISIBLE) {
            tv.visibility = View.VISIBLE
        }

        val mode = prefsRef.getString(KEY_MODE, "combined")
        val showArrows = prefsRef.getBoolean(KEY_SHOW_ARROWS, true)

        val text = if (mode == "separate") {
            val upText = formatCompact(txSpeed)
            val downText = formatCompact(rxSpeed)
            if (showArrows) {
                "▲$upText ▼$downText"
            } else {
                "U:$upText D:$downText"
            }
        } else {
            formatSpeed(totalSpeed)
        }

        if (tv.text != text) {
            tv.text = text
        }
    }

    private fun formatSpeed(speedBps: Long): String {
        return when {
            speedBps < 1024L -> "0 KB/s"
            speedBps < 100L * 1024L -> {
                val kb = speedBps / 1024.0
                String.format(Locale.US, "%.1f KB/s", kb)
            }
            speedBps < 1024L * 1024L -> {
                val kb = speedBps / 1024L
                "$kb KB/s"
            }
            speedBps < 100L * 1024L * 1024L -> {
                val mb = speedBps / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB/s", mb)
            }
            speedBps < 1024L * 1024L * 1024L -> {
                val mb = speedBps / (1024L * 1024L)
                "$mb MB/s"
            }
            else -> {
                val gb = speedBps / (1024.0 * 1024.0 * 1024.0)
                String.format(Locale.US, "%.2f GB/s", gb)
            }
        }
    }

    private fun formatCompact(speedBps: Long): String {
        return when {
            speedBps < 1024L -> "0K"
            speedBps < 1000L * 1024L -> "${speedBps / 1024L}K"
            speedBps < 1000L * 1024L * 1024L -> {
                val mb = speedBps / (1024.0 * 1024.0)
                if (mb < 10.0) String.format(Locale.US, "%.1fM", mb)
                else String.format(Locale.US, "%.0fM", mb)
            }
            else -> {
                val gb = speedBps / (1024.0 * 1024.0 * 1024.0)
                String.format(Locale.US, "%.1fG", gb)
            }
        }
    }
}
