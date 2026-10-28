package com.souleven.nothingos.hooks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File

class BatteryCapHooks : HookModule {

    companion object {
        const val KEY_ENABLED = "pref_battery_cap_enabled"
        const val KEY_LEVEL = "pref_battery_cap_level"
        const val KEY_NOTIFY = "pref_battery_cap_notify"
        private const val DEFAULT_LEVEL = 80
        private const val HYSTERESIS_PCT = 2
        private const val CLASS_BATTERY_SERVICE = "com.android.server.BatteryService"
        private const val ICHARGE_SERVICE_NAME = "vendor.noth.hardware.charge.ICharge/default"
        private const val NOTIFICATION_CHANNEL_ID = "battery_cap_channel"
        private const val NOTIFICATION_ID = 8080
    }

    private lateinit var prefsRef: Prefs
    private var appContext: Context? = null
    private var classLoaderRef: ClassLoader? = null
    private var batteryServiceInstance: Any? = null

    private var iChargeInstance: Any? = null
    private var iChargeResolved = false

    @Volatile
    private var overrideEnabled: Boolean? = null

    @Volatile
    private var overrideLevel: Int? = null

    @Volatile
    private var overrideNotify: Boolean? = null

    @Volatile
    private var isCapped = false

    @Volatile
    private var lastCapLevel = -1

    @Volatile
    private var lastHardwareEnabled: Boolean? = null

    @Volatile
    private var lastPlugged: Boolean? = null

    @Volatile
    private var isNotificationShown = false

    @Volatile
    private var lastNotifiedLevel = -1

    private var receiverRegistered = false

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        if (lpparam.packageName != "android") return
        prefsRef = prefs
        classLoaderRef = lpparam.classLoader

        prefs.addListener {
            onPreferencesChanged()
        }

        hookBatteryService(lpparam.classLoader)
    }

    private fun hookBatteryService(classLoader: ClassLoader) {
        val batteryServiceClass = try {
            XposedHelpers.findClass(CLASS_BATTERY_SERVICE, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BatteryCap] BatteryService not found: ${t.message}")
            return
        }

        // hook BatteryService constructor to capture Context and instance early
        try {
            XposedHelpers.findAndHookConstructor(
                batteryServiceClass,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        batteryServiceInstance = param.thisObject
                        val ctx = param.args[0] as? Context
                        if (ctx != null) {
                            appContext = ctx
                            prefsRef.init(ctx)
                            registerCapReceiver(ctx, classLoader)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                batteryServiceClass,
                "processValuesLocked",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        batteryServiceInstance = param.thisObject
                        if (appContext == null) {
                            appContext = try {
                                XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                            } catch (_: Throwable) { null }
                            appContext?.let {
                                prefsRef.init(it)
                                registerCapReceiver(it, classLoader)
                            }
                        }

                        enforceBatteryCap(param.thisObject, classLoader)
                    }
                }
            )
            XposedBridge.log("$TAG [BatteryCap] Hooked BatteryService#processValuesLocked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BatteryCap] FAILED to hook processValuesLocked: ${t.message}")
        }

        // on system boot / service ready, ensure hardware charging is initially in safe, enabled state
        try {
            XposedHelpers.findAndHookMethod(
                batteryServiceClass,
                "onBootPhase",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val phase = param.args[0] as? Int ?: return
                        // PHASE_BOOT_COMPLETED = 1000
                        if (phase == 1000) {
                            if (!isCapped) {
                                setHardwareChargingEnabled(true, classLoader)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun registerCapReceiver(context: Context, classLoader: ClassLoader) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction("com.souleven.nothingos.BATTERY_CAP_CHANGED")
                    addAction("com.souleven.nothingos.PREFERENCES_CHANGED")
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent == null) return
                        XposedBridge.log("$TAG [BatteryCap] Live broadcast received: ${intent.action}")
                        if (intent.hasExtra(KEY_ENABLED)) {
                            overrideEnabled = intent.getBooleanExtra(KEY_ENABLED, false)
                        }
                        if (intent.hasExtra(KEY_LEVEL)) {
                            overrideLevel = intent.getIntExtra(KEY_LEVEL, DEFAULT_LEVEL)
                        }
                        if (intent.hasExtra(KEY_NOTIFY)) {
                            overrideNotify = intent.getBooleanExtra(KEY_NOTIFY, true)
                        }
                        prefsRef.forceReload()
                        onPreferencesChanged()
                    }
                }

                try {
                    // Context.RECEIVER_EXPORTED = 0x2
                    val method = context.javaClass.getMethod(
                        "registerReceiver",
                        BroadcastReceiver::class.java,
                        IntentFilter::class.java,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(context, receiver, filter, 0x2)
                } catch (_: Throwable) {
                    context.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                XposedBridge.log("$TAG [BatteryCap] Dynamic BATTERY_CAP_CHANGED receiver registered")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [BatteryCap] Failed to register receiver: ${t.message}")
            }
        }
    }

    private fun enforceBatteryCap(service: Any, classLoader: ClassLoader) {
        if (!::prefsRef.isInitialized) return

        val healthInfo = try {
            XposedHelpers.getObjectField(service, "mHealthInfo")
        } catch (_: Throwable) { null } ?: return

        val batteryLevel = try {
            XposedHelpers.getIntField(healthInfo, "batteryLevel")
        } catch (_: Throwable) { 0 }

        val plugType = try {
            XposedHelpers.callStaticMethod(service.javaClass, "plugType", healthInfo) as? Int
                ?: XposedHelpers.getIntField(service, "mPlugType")
        } catch (_: Throwable) {
            try { XposedHelpers.getIntField(service, "mPlugType") } catch (_: Throwable) { 0 }
        }

        val isPlugged = plugType != 0
        val plugChanged = (lastPlugged != null && lastPlugged != isPlugged)
        lastPlugged = isPlugged

        val enabled = overrideEnabled ?: prefsRef.getBoolean(KEY_ENABLED, false)
        val capLevel = overrideLevel ?: prefsRef.getInt(KEY_LEVEL, DEFAULT_LEVEL)
        val notify = overrideNotify ?: prefsRef.getBoolean(KEY_NOTIFY, true)

        if (!enabled || !isPlugged) {
            // unplugged or feature disabled: unconditionally restore hardware charging
            val wasCapped = isCapped
            isCapped = false
            setHardwareChargingEnabled(true, classLoader, force = (plugChanged || wasCapped))
            updateNotification(false, capLevel, notify)
            lastCapLevel = capLevel
            return
        }

        // detect if target cap was changed by the user in NothingTweaks
        if (lastCapLevel != -1 && capLevel != lastCapLevel) {
            XposedBridge.log("$TAG [BatteryCap] Target cap updated from $lastCapLevel% to $capLevel% (current: $batteryLevel%)")
            if (batteryLevel < capLevel) {
                // user increased cap above current battery level: force resume charging immediately
                isCapped = false
                setHardwareChargingEnabled(true, classLoader, force = true)
                updateNotification(false, capLevel, notify)
            }
        }
        lastCapLevel = capLevel

        if (batteryLevel >= capLevel) {
            // limit reached or exceeded: stop charging
            if (!isCapped) {
                isCapped = true
                XposedBridge.log("$TAG [BatteryCap] Battery level ($batteryLevel%) reached cap ($capLevel%). Pausing charging.")
                setHardwareChargingEnabled(false, classLoader)
                updateNotification(true, capLevel, notify)
            }
            // override batteryStatus to BATTERY_STATUS_NOT_CHARGING (4) so the lockscreen,
            // status bar, and settings reflect "plugged in, not charging"
            try {
                XposedHelpers.setIntField(healthInfo, "batteryStatus", 4)
            } catch (_: Throwable) {}
        } else if (batteryLevel <= (capLevel - HYSTERESIS_PCT)) {
            // dropped below hysteresis threshold : resume charging
            if (isCapped) {
                XposedBridge.log("$TAG [BatteryCap] Battery dropped to $batteryLevel%. Resuming charging up to $capLevel%.")
            }
            isCapped = false
            setHardwareChargingEnabled(true, classLoader)
            updateNotification(false, capLevel, notify)
        } else if (isCapped) {
            // inside the 2% hysteresis band while capped: maintain paused charging
            setHardwareChargingEnabled(false, classLoader)
            try {
                XposedHelpers.setIntField(healthInfo, "batteryStatus", 4)
            } catch (_: Throwable) {}
        } else {
            // below cap and not capped: actively ensure hardware charging is ENABLED!
            setHardwareChargingEnabled(true, classLoader, force = plugChanged)
        }
    }

    private fun onPreferencesChanged() {
        val service = batteryServiceInstance ?: return
        val classLoader = classLoaderRef ?: return

        try {
            val lock = XposedHelpers.getObjectField(service, "mLock")
            if (lock != null) {
                synchronized(lock) {
                    enforceBatteryCap(service, classLoader)
                    XposedHelpers.callMethod(service, "processValuesLocked", true)
                }
            } else {
                enforceBatteryCap(service, classLoader)
                XposedHelpers.callMethod(service, "processValuesLocked", true)
            }
        } catch (_: Throwable) {
            enforceBatteryCap(service, classLoader)
        }
    }

    private fun getICharge(classLoader: ClassLoader): Any? {
        if (iChargeResolved) return iChargeInstance
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, ICHARGE_SERVICE_NAME) as? IBinder
            if (binder != null) {
                val stubClass = XposedHelpers.findClassIfExists("vendor.noth.hardware.charge.ICharge\$Stub", classLoader)
                if (stubClass != null) {
                    iChargeInstance = XposedHelpers.callStaticMethod(stubClass, "asInterface", binder)
                    iChargeResolved = true
                    XposedBridge.log("$TAG [BatteryCap] Connected to Nothing ICharge HAL")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BatteryCap] Could not bind to ICharge HAL: ${t.message}")
        }
        return iChargeInstance
    }

    private fun setHardwareChargingEnabled(enabled: Boolean, classLoader: ClassLoader, force: Boolean = false) {
        if (!force && lastHardwareEnabled == enabled) {
            return
        }
        lastHardwareEnabled = enabled

        // 1. Hardware PMIC control via Nothing ICharge AIDL HAL
        // We solely use setUsb_charger_en(1 / 0) which is Nothing's direct hardware switch.
        // We intentionally do NOT touch setChargeFccInt because setting FCC to 0 mA clamps
        // current at 0 mA and passing -1 does not restore current in Qualcomm HAL voters.
        val iCharge = getICharge(classLoader)
        if (iCharge != null) {
            try {
                val usbVal = if (enabled) 1 else 0
                XposedHelpers.callMethod(iCharge, "setUsb_charger_en", usbVal)
                XposedBridge.log("$TAG [BatteryCap] Called ICharge#setUsb_charger_en($usbVal)")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG [BatteryCap] ICharge#setUsb_charger_en error: ${t.message}")
            }
        }

        // 2. Sysfs control fallback for Linux power supply drivers (safe charging_enabled only)
        val writeVal = if (enabled) "1" else "0"
        val nodes = listOf(
            "/sys/class/power_supply/battery/charging_enabled" to writeVal,
            "/sys/class/power_supply/battery/battery_charging_enabled" to writeVal,
            "/sys/class/qcom-battery/charging_enabled" to writeVal
        )

        for ((path, value) in nodes) {
            try {
                val file = File(path)
                if (file.exists() && file.canWrite()) {
                    file.writeText(value)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun updateNotification(show: Boolean, capLevel: Int, allowNotify: Boolean) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (!show || !allowNotify) {
            if (isNotificationShown) {
                try {
                    nm.cancel(NOTIFICATION_ID)
                } catch (_: Throwable) {}
                isNotificationShown = false
                lastNotifiedLevel = -1
            }
            return
        }

        if (isNotificationShown && lastNotifiedLevel == capLevel) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Battery Protection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notifies when battery charging is paused at the set limit"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val notification = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Charging paused at $capLevel%")
                .setContentText("Battery protection is active to preserve battery lifespan.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
            isNotificationShown = true
            lastNotifiedLevel = capLevel
        } catch (t: Throwable) {
            XposedBridge.log("$TAG [BatteryCap] Failed to post notification: ${t.message}")
        }
    }
}
