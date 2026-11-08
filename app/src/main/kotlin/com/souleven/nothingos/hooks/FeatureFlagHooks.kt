package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class FeatureFlagHooks : HookModule {

    private val className = "com.nothing.NTFeaturesSystemUIUtils"

    companion object {
        const val STATE_SKIP = "skip"
        const val STATE_TRUE = "true"
        const val STATE_FALSE = "false"

        fun prefKeyFor(method: String) = "flag_$method"

        const val KEY_WATERMARK_FORCE_ON = "watermark_force_on"

        const val KEY_RECORD_LIMIT = "record_limit"
        const val RECORD_LIMIT_OFF = "off"
        const val RECORD_LIMIT_720P = "720p"
        const val RECORD_LIMIT_1080P_60FPS = "1080p60fps"
        const val RECORD_LIMIT_60FPS_1080P_LEGACY = "60fps1080p"

        private fun is1080p60(limit: String) =
            limit == RECORD_LIMIT_1080P_60FPS || limit == RECORD_LIMIT_60FPS_1080P_LEGACY

        private fun is720p(limit: String) =
            limit == RECORD_LIMIT_720P
    }

    private val methods = listOf(
        "isEnableWatermark",
        "isScreenRecordBy720P",
        "isScreenRecordByLimit60FpsAnd1080P",
        "isSupportSteplessVolume",
        "isSupportSupperVolume",
        "isSupportPalmTouchSleep",
        "isSupportFlipToRecord"
    )

    private fun stateFor(method: String, prefs: Prefs): String = when (method) {
        "isEnableWatermark" ->
            if (prefs.getBoolean(KEY_WATERMARK_FORCE_ON, false)) STATE_TRUE else STATE_SKIP

        "isScreenRecordBy720P" -> {
            val limit = prefs.getString(KEY_RECORD_LIMIT, RECORD_LIMIT_OFF)
            when {
                is720p(limit) -> STATE_TRUE
                is1080p60(limit) -> STATE_FALSE
                else -> STATE_SKIP
            }
        }

        "isScreenRecordByLimit60FpsAnd1080P" -> {
            val limit = prefs.getString(KEY_RECORD_LIMIT, RECORD_LIMIT_OFF)
            when {
                is1080p60(limit) -> STATE_TRUE
                is720p(limit) -> STATE_FALSE
                else -> STATE_SKIP
            }
        }

        else -> if (prefs.getBoolean(prefKeyFor(method), false)) STATE_TRUE else STATE_SKIP
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return

        for (method in methods) {
            try {
                if (clazz.declaredMethods.any { it.name == method }) {
                    XposedHelpers.findAndHookMethod(clazz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = stateFor(method, prefs)
                            if (state == STATE_SKIP) return
                            param.result = (state == STATE_TRUE)
                        }
                    })
                }
            } catch (_: Throwable) {}
        }
    }
}
