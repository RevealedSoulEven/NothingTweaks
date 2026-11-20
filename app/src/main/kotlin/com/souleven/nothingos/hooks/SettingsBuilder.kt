package com.souleven.nothingos.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.xmlpull.v1.XmlPullParser

/*
    The settings app is very heavily obfuscated with proguard due to minifying
    so finding hooks in it is possible but making it survive multiple devices/versions
    and updates is tough as we need to rely on pattern-recognition at runtime.
    So I did the research on the methods but rest of the hooks are written by AI (yes)
    used deepseek 4.1 flash and gemini 3.8 flash, both corrected each other for over 7 hours
    and generated SettingsBuilder.kt and SettingsHooks.kt, so ya the code below is AI-generated
*/

object SettingsBuilder {

    private const val TAG = "NothingTweaks"

    @Volatile
    private var cachedRemoveAllMethod: java.lang.reflect.Method? = null

    @Volatile
    private var cachedOrderingMethod: java.lang.reflect.Method? = null

    @Volatile
    private var cachedOrderField: java.lang.reflect.Field? = null

    @Volatile
    private var cachedKeyField: java.lang.reflect.Field? = null

    @Volatile
    private var cachedSetCheckedMethod: java.lang.reflect.Method? = null

    // structural type discovery for Preference
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

    // structural type discovery for PreferenceGroup
    private fun declaresGroupStructure(c: Class<*>): Boolean {
        if (c.declaredMethods.any {
                it.name == "getKey" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }) {
            return false
        }

        val hasList = c.declaredFields.any { List::class.java.isAssignableFrom(it.type) }
        if (!hasList) return false
        return c.declaredMethods.any { m ->
            if (!Modifier.isPublic(m.modifiers)) return@any false
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

    private fun hasSameSignature(m1: java.lang.reflect.Method, m2: java.lang.reflect.Method): Boolean {
        if (m1.name != m2.name) return false
        if (m1.returnType != m2.returnType) return false
        val p1 = m1.parameterTypes
        val p2 = m2.parameterTypes
        if (p1.size != p2.size) return false
        for (i in p1.indices) {
            if (p1[i] != p2[i]) return false
        }
        return true
    }

    private fun getPreferenceList(group: Any): List<*>? {
        var c: Class<*>? = group.javaClass
        while (c != null && c != Any::class.java) {
            if (declaresGroupStructure(c) || c.name.contains("PreferenceGroup")) {
                for (f in c.declaredFields) {
                    if (List::class.java.isAssignableFrom(f.type)) {
                        try {
                            f.isAccessible = true
                            val list = f.get(group) as? List<*>
                            if (list != null) return list
                        } catch (_: Throwable) {}
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun removePref(group: Any, child: Any): Boolean {
        val list = getPreferenceList(group)
        if (list != null && !list.contains(child)) return true

        try {
            val res = XposedHelpers.callMethod(group, "removePreference", child)
            if (list != null && !list.contains(child)) return true
            if (res is Boolean && res) return true
        } catch (_: Throwable) {}

        var c: Class<*>? = group.javaClass
        while (c != null && c != Any::class.java) {
            if (declaresGroupStructure(c) || c.name.contains("PreferenceGroup")) {
                for (m in c.declaredMethods) {
                    if (Modifier.isPublic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.returnType == java.lang.Boolean.TYPE &&
                        isPreferenceType(m.parameterTypes[0], preferenceClassOf(child))
                    ) {
                        try {
                            m.isAccessible = true
                            m.invoke(group, child)
                            if (list != null && !list.contains(child)) {
                                return true
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            c = c.superclass
        }
        return false
    }

    private fun clearPreferenceGroup(group: Any) {
        cachedRemoveAllMethod?.let {
            try {
                it.invoke(group)
                return
            } catch (_: Throwable) {}
        }

        try {
            val m = group.javaClass.getMethod("removeAll")
            m.invoke(group)
            cachedRemoveAllMethod = m
            return
        } catch (_: Throwable) {}

        val list = getPreferenceList(group)
        var c: Class<*>? = group.javaClass
        while (c != null && c != Any::class.java) {
            if (declaresGroupStructure(c) || c.name.contains("PreferenceGroup")) {
                for (m in c.declaredMethods) {
                    if (Modifier.isPublic(m.modifiers) &&
                        m.parameterTypes.isEmpty() &&
                        m.returnType == java.lang.Void.TYPE
                    ) {
                        try {
                            m.isAccessible = true
                            val sizeBefore = list?.size ?: -1
                            m.invoke(group)
                            val sizeAfter = list?.size ?: -1
                            if (sizeBefore > 0 && sizeAfter == 0) {
                                cachedRemoveAllMethod = m
                                return
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            c = c.superclass
        }

        // fallback: remove one by one
        try {
            val copy = list?.toList() ?: emptyList()
            for (item in copy) {
                if (item != null) {
                    removePref(group, item)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun createPreferenceScreen(pm: Any, context: Context): Any? {
        try {
            val m = pm.javaClass.getMethod("createPreferenceScreen", Context::class.java)
            return m.invoke(pm, context)
        } catch (_: Throwable) {}

        for (m in pm.javaClass.methods) {
            if (m.parameterTypes.size == 1 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                (isGroupType(m.returnType) || m.returnType.name.contains("PreferenceScreen"))
            ) {
                try {
                    return m.invoke(pm, context)
                } catch (_: Throwable) {}
            }
        }

        try {
            val screenClass = XposedHelpers.findClass("androidx.preference.PreferenceScreen", pm.javaClass.classLoader)
            return screenClass.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
                .newInstance(context, null)
        } catch (_: Throwable) {}

        return null
    }

    // clean header to show only "Nothing Tweaks" in authentic headline font without duplicate titles
    fun cleanActivityHeader(activity: Any) {
        val title = "Nothing Tweaks"
        try {
            val ab = XposedHelpers.callMethod(activity, "getActionBar")
            if (ab != null) {
                try { XposedHelpers.callMethod(ab, "setDisplayShowTitleEnabled", false) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(ab, "setDisplayShowHomeEnabled", false) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(ab, "setDisplayUseLogoEnabled", false) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        try {
            var cursor: Class<*>? = activity.javaClass
            while (cursor != null && cursor != Any::class.java) {
                for (f in cursor.declaredFields) {
                    val n = f.name
                    if (n == "k0" || n == "mInitialTitleResId" || n == "mTitleResId") {
                        try {
                            f.isAccessible = true
                            f.setInt(activity, -1)
                        } catch (_: Throwable) {}
                    } else if (n == "j0" || n == "mInitialTitle" || n == "mTitle") {
                        try {
                            f.isAccessible = true
                            f.set(activity, title)
                        } catch (_: Throwable) {}
                    } else if (n == "mToolbarTitle") {
                        try {
                            f.isAccessible = true
                            f.set(activity, null)
                        } catch (_: Throwable) {}
                    }
                }
                cursor = cursor.superclass
            }
        } catch (_: Throwable) {}

        try {
            val decor = XposedHelpers.callMethod(activity, "getWindow")?.let {
                XposedHelpers.callMethod(it, "getDecorView") as? View
            }
            if (decor != null) {
                val tb = (try { XposedHelpers.getObjectField(activity, "e0") as? View } catch (_: Throwable) { null })
                    ?: findToolbar(decor)
                if (tb != null) {
                    try { tb.javaClass.getMethod("setTitle", CharSequence::class.java).invoke(tb, null) } catch (_: Throwable) {}
                    try { tb.javaClass.getMethod("setSubtitle", CharSequence::class.java).invoke(tb, null) } catch (_: Throwable) {}
                    try { tb.javaClass.getMethod("setLogo", Drawable::class.java).invoke(tb, null) } catch (_: Throwable) {}
                }

                val ctl = (try { XposedHelpers.getObjectField(activity, "b0") as? View } catch (_: Throwable) { null })
                    ?: findCollapsingToolbar(decor)
                if (ctl != null) {
                    setCollapsingToolbarTitle(ctl, title)
                    clearCollapsingToolbarSubtitle(ctl)
                    val cl = (activity as? Activity)?.classLoader ?: ctl.context.classLoader
                    applyHeadlineTypeface(ctl, cl)
                }
            }
        } catch (_: Throwable) {}
    }

    fun updateActivityTitle(activity: Any) {
        cleanActivityHeader(activity)
    }

    private fun findToolbar(view: View?): View? {
        if (view == null) return null
        if (view.javaClass.name.contains("Toolbar")) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findToolbar(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findCollapsingToolbar(view: View?): View? {
        if (view == null) return null
        if (view.javaClass.name.contains("CollapsingToolbarLayout")) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findCollapsingToolbar(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun setCollapsingToolbarTitle(ctl: View, title: CharSequence) {
        for (name in arrayOf("setTitle", "F")) {
            try {
                ctl.javaClass.getMethod(name, CharSequence::class.java).invoke(ctl, title)
                return
            } catch (_: Throwable) {}
        }
        for (m in ctl.javaClass.methods) {
            if (m.name != "E" && m.name != "setSubtitle" &&
                m.parameterTypes.size == 1 &&
                CharSequence::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.returnType == java.lang.Void.TYPE
            ) {
                try {
                    m.invoke(ctl, title)
                    return
                } catch (_: Throwable) {}
            }
        }
    }

    private fun clearCollapsingToolbarSubtitle(ctl: View) {
        for (name in arrayOf("setSubtitle", "E")) {
            try {
                ctl.javaClass.getMethod(name, CharSequence::class.java).invoke(ctl, null)
                return
            } catch (_: Throwable) {}
        }
    }

    private fun applyHeadlineTypeface(ctl: View, classLoader: ClassLoader) {
        try {
            val pjaClass = XposedHelpers.findClassIfExists("pj.a", classLoader)
            if (pjaClass != null) {
                XposedHelpers.callStaticMethod(pjaClass, "a", ctl)
                return
            }
        } catch (_: Throwable) {}

        try {
            val xcClass = XposedHelpers.findClassIfExists("xc.e", classLoader)
            val tf = if (xcClass != null) XposedHelpers.callStaticMethod(xcClass, "a") as? Typeface else null
            if (tf != null) {
                for (m in arrayOf("w", "t", "setCollapsedTitleTypeface", "setExpandedTitleTypeface")) {
                    try {
                        ctl.javaClass.getMethod(m, Typeface::class.java).invoke(ctl, tf)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    fun buildPreferenceScreen(fragment: Any, classLoader: ClassLoader) {
        // XposedBridge.log("$TAG [SettingsBuilder] Building Nothing Tweaks PreferenceScreen...")

        val context = XposedHelpers.callMethod(fragment, "requireContext") as Context
        val pm = XposedHelpers.callMethod(fragment, "getPreferenceManager")
            ?: run {
                return
            }

        var screen: Any? = null
        try {
            screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen")
        } catch (_: Throwable) {}

        if (screen == null) {
            screen = createPreferenceScreen(pm, context)
            if (screen != null) {
                try {
                    XposedHelpers.callMethod(fragment, "setPreferenceScreen", screen)
                } catch (_: Throwable) {}
            }
        }

        if (screen == null) return

        clearPreferenceGroup(screen)

        val prefClass = preferenceClassOf(screen)
            ?: XposedHelpers.findClass("androidx.preference.Preference", classLoader)
        val catClass = XposedHelpers.findClassIfExists("androidx.preference.PreferenceCategory", classLoader)
            ?: prefClass
        val plainPrefClass = prefClass

        val allPrefs = try {
            context.contentResolver.call(
                RemotePreferenceProvider.CONTENT_URI,
                RemotePreferenceProvider.METHOD_GET_ALL,
                null,
                null
            )
        } catch (_: Throwable) { null }

        fun setPrefOrder(pref: Any, order: Int) {
            try {
                XposedHelpers.callMethod(pref, "setOrder", order)
                return
            } catch (_: Throwable) {}

            cachedOrderField?.let {
                try {
                    it.setInt(pref, order)
                    return
                } catch (_: Throwable) {}
            }

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c.name == "androidx.preference.Preference") {
                    val orderField = c.declaredFields.firstOrNull {
                        !Modifier.isStatic(it.modifiers) && it.type == java.lang.Integer.TYPE
                    }
                    if (orderField != null) {
                        try {
                            orderField.isAccessible = true
                            orderField.setInt(pref, order)
                            cachedOrderField = orderField
                            return
                        } catch (_: Throwable) {}
                    }
                }
                c = c.superclass
            }

            try {
                XposedHelpers.setIntField(pref, "mOrder", order)
            } catch (_: Throwable) {}
        }

        fun addPref(group: Any, child: Any): Boolean {
            val list = getPreferenceList(group)
            if (list != null && list.contains(child)) return true

            try {
                val res = XposedHelpers.callMethod(group, "addPreference", child)
                if (list != null && list.contains(child)) return true
                if (res is Boolean && res) return true
            } catch (_: Throwable) {}

            var c: Class<*>? = group.javaClass
            while (c != null && c != Any::class.java) {
                if (declaresGroupStructure(c) || c.name.contains("PreferenceGroup")) {
                    for (m in c.declaredMethods) {
                        if (Modifier.isPublic(m.modifiers) &&
                            m.parameterTypes.size == 1 &&
                            isPreferenceType(m.parameterTypes[0], prefClass)
                        ) {
                            try {
                                m.isAccessible = true
                                m.invoke(group, child)
                                if (list != null && list.contains(child)) {
                                    return true
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }
            return false
        }

        fun setPrefKey(pref: Any, key: String) {
            try {
                XposedHelpers.callMethod(pref, "setKey", key)
                val k = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null }
                if (k == key) return
            } catch (_: Throwable) {}

            cachedKeyField?.let {
                try {
                    it.set(pref, key)
                    val k = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null }
                    if (k == key) return
                } catch (_: Throwable) {}
            }

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c == prefClass || c.name == "androidx.preference.Preference") {
                    for (f in c.declaredFields) {
                        if (!Modifier.isStatic(f.modifiers) && f.type == String::class.java) {
                            try {
                                f.isAccessible = true
                                val oldVal = f.get(pref)
                                f.set(pref, key)
                                val k = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null }
                                if (k == key) {
                                    cachedKeyField = f
                                    return
                                }
                                f.set(pref, oldVal)
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }

            try {
                XposedHelpers.setObjectField(pref, "mKey", key)
            } catch (_: Throwable) {}
        }

        fun setPrefTitle(pref: Any, title: CharSequence) {
            try {
                XposedHelpers.callMethod(pref, "setTitle", title)
                return
            } catch (_: Throwable) {}

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c == prefClass || c.name == "androidx.preference.Preference") {
                    val csFields = c.declaredFields.filter { it.type == CharSequence::class.java }
                    val titleField = csFields.firstOrNull()
                    if (titleField != null) {
                        try {
                            titleField.isAccessible = true
                            titleField.set(pref, title)
                            return
                        } catch (_: Throwable) {}
                    }
                }
                c = c.superclass
            }

            try {
                XposedHelpers.setObjectField(pref, "mTitle", title)
            } catch (_: Throwable) {}
        }

        fun setPrefSummary(pref: Any, summary: CharSequence) {
            try {
                XposedHelpers.callMethod(pref, "setSummary", summary)
                return
            } catch (_: Throwable) {}

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c == prefClass || c.name == "androidx.preference.Preference") {
                    val csFields = c.declaredFields.filter { it.type == CharSequence::class.java }
                    val summaryField = csFields.getOrNull(1)
                    if (summaryField != null) {
                        try {
                            summaryField.isAccessible = true
                            summaryField.set(pref, summary)
                            return
                        } catch (_: Throwable) {}
                    }
                }
                c = c.superclass
            }

            try {
                XposedHelpers.setObjectField(pref, "mSummary", summary)
            } catch (_: Throwable) {}
        }

        fun setPrefSelectable(pref: Any, selectable: Boolean) {
            try {
                XposedHelpers.callMethod(pref, "setSelectable", selectable)
                return
            } catch (_: Throwable) {}
            try {
                XposedHelpers.setBooleanField(pref, "mSelectable", selectable)
                return
            } catch (_: Throwable) {}
        }

        fun setOrderingAsAdded(group: Any, ordering: Boolean) {
            cachedOrderingMethod?.let {
                try {
                    it.invoke(group, ordering)
                    return
                } catch (_: Throwable) {}
            }

            try {
                XposedHelpers.callMethod(group, "setOrderingAsAdded", ordering)
                return
            } catch (_: Throwable) {}

            val prefBaseMethods = prefClass.declaredMethods
            var c: Class<*>? = group.javaClass
            while (c != null && c != Any::class.java) {
                if (declaresGroupStructure(c) || c.name.contains("PreferenceGroup")) {
                    for (m in c.declaredMethods) {
                        if (Modifier.isPublic(m.modifiers) &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == java.lang.Boolean.TYPE &&
                            m.returnType == java.lang.Void.TYPE
                        ) {
                            if (prefBaseMethods.any { hasSameSignature(it, m) }) {
                                continue
                            }
                            try {
                                m.isAccessible = true
                                m.invoke(group, ordering)
                                cachedOrderingMethod = m
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                    for (f in c.declaredFields) {
                        if (f.type == java.lang.Boolean.TYPE) {
                            try {
                                f.isAccessible = true
                                f.setBoolean(group, ordering)
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }

            try {
                XposedHelpers.setBooleanField(group, "mOrderingAsAdded", ordering)
            } catch (_: Throwable) {}
        }

        fun isCheckedValue(pref: Any): Boolean? {
            try {
                val res = XposedHelpers.callMethod(pref, "isChecked")
                if (res is Boolean) return res
            } catch (_: Throwable) {}
            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c.name.contains("TwoStatePreference")) {
                    for (m in c.declaredMethods) {
                        if (Modifier.isPublic(m.modifiers) &&
                            m.parameterTypes.isEmpty() &&
                            m.returnType == java.lang.Boolean.TYPE
                        ) {
                            try {
                                m.isAccessible = true
                                val res = m.invoke(pref)
                                if (res is Boolean) return res
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }
            return null
        }

        fun findTwoStateClass(pref: Any): Class<*>? {
            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c.name.contains("TwoStatePreference")) return c
                c = c.superclass
            }
            return null
        }

        fun setSwitchChecked(pref: Any, checked: Boolean) {
            try {
                XposedHelpers.callMethod(pref, "setChecked", checked)
                return
            } catch (_: Throwable) {}

            cachedSetCheckedMethod?.let {
                try {
                    it.invoke(pref, checked)
                    return
                } catch (_: Throwable) {}
            }

            val twoStateClass = findTwoStateClass(pref)
            if (twoStateClass != null) {
                val checkedBefore = isCheckedValue(pref)
                for (m in twoStateClass.declaredMethods) {
                    if (Modifier.isPublic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == java.lang.Boolean.TYPE &&
                        m.returnType == java.lang.Void.TYPE
                    ) {
                        try {
                            m.isAccessible = true
                            if (checkedBefore != null) {
                                m.invoke(pref, !checkedBefore)
                                val checkedAfter = isCheckedValue(pref)
                                if (checkedAfter == !checkedBefore) {
                                    cachedSetCheckedMethod = m
                                    m.invoke(pref, checked)
                                    return
                                } else {
                                    m.invoke(pref, checkedBefore)
                                }
                            } else {
                                m.invoke(pref, checked)
                                cachedSetCheckedMethod = m
                                return
                            }
                        } catch (_: Throwable) {}
                    }
                }

                val checkedField = twoStateClass.declaredFields.firstOrNull {
                    !Modifier.isStatic(it.modifiers) && it.type == java.lang.Boolean.TYPE
                }
                if (checkedField != null) {
                    try {
                        checkedField.isAccessible = true
                        checkedField.setBoolean(pref, checked)
                        return
                    } catch (_: Throwable) {}
                }
            }

            try {
                XposedHelpers.setBooleanField(pref, "mChecked", checked)
            } catch (_: Throwable) {}
        }

        // dynamically discover obfuscated listener interfaces
        val changeListenerIface: Class<*>? = run {
            try {
                val std = Class.forName("androidx.preference.Preference\$OnPreferenceChangeListener", false, classLoader)
                if (std != null) return@run std
            } catch (_: Throwable) {}

            var c: Class<*>? = prefClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (Modifier.isPublic(m.modifiers) && m.parameterTypes.size == 1) {
                        val p = m.parameterTypes[0]
                        if (p.isInterface) {
                            for (im in p.methods) {
                                if (im.parameterTypes.size == 2 &&
                                    im.returnType == java.lang.Boolean.TYPE &&
                                    isPreferenceType(im.parameterTypes[0], prefClass)
                                ) {
                                    return@run p
                                }
                            }
                        }
                    }
                }
                c = c.superclass
            }
            null
        }

        val clickListenerIface: Class<*>? = run {
            try {
                val std = Class.forName("androidx.preference.Preference\$OnPreferenceClickListener", false, classLoader)
                if (std != null) return@run std
            } catch (_: Throwable) {}

            var c: Class<*>? = prefClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (Modifier.isPublic(m.modifiers) && m.parameterTypes.size == 1) {
                        val p = m.parameterTypes[0]
                        if (p.isInterface) {
                            for (im in p.methods) {
                                if (im.parameterTypes.size == 1 &&
                                    im.returnType == java.lang.Boolean.TYPE &&
                                    isPreferenceType(im.parameterTypes[0], prefClass)
                                ) {
                                    return@run p
                                }
                            }
                        }
                    }
                }
                c = c.superclass
            }
            null
        }

        fun setPrefChangeListener(pref: Any, onPreferenceChange: (pref: Any, value: Any) -> Boolean) {
            val iface = changeListenerIface ?: return
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(iface)) { proxyObj, method, args ->
                if (method.returnType == java.lang.Boolean.TYPE && args?.size == 2) {
                    val p = args[0]
                    val v = args[1]
                    return@newProxyInstance onPreferenceChange(p, v)
                }
                when (method.name) {
                    "equals" -> return@newProxyInstance args?.getOrNull(0) === proxyObj
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxyObj)
                    "toString" -> return@newProxyInstance "OnPreferenceChangeListenerProxy"
                }
                null
            }

            try {
                XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", proxy)
                return
            } catch (_: Throwable) {}

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c == prefClass || c.name.contains("Preference")) {
                    for (m in c.declaredMethods) {
                        if (Modifier.isPublic(m.modifiers) &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == iface &&
                            m.returnType == java.lang.Void.TYPE
                        ) {
                            try {
                                m.isAccessible = true
                                m.invoke(pref, proxy)
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                    for (f in c.declaredFields) {
                        if (f.type == iface) {
                            try {
                                f.isAccessible = true
                                f.set(pref, proxy)
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }
        }

        fun setPrefClickListener(pref: Any, onClick: () -> Unit) {
            val iface = clickListenerIface ?: return
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(iface)) { proxyObj, method, args ->
                if (method.returnType == java.lang.Boolean.TYPE && args?.size == 1) {
                    onClick()
                    return@newProxyInstance true
                }
                when (method.name) {
                    "equals" -> return@newProxyInstance args?.getOrNull(0) === proxyObj
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxyObj)
                    "toString" -> return@newProxyInstance "OnPreferenceClickListenerProxy"
                }
                null
            }

            try {
                XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", proxy)
                return
            } catch (_: Throwable) {}

            var c: Class<*>? = pref.javaClass
            while (c != null && c != Any::class.java) {
                if (c == prefClass || c.name.contains("Preference")) {
                    for (m in c.declaredMethods) {
                        if (Modifier.isPublic(m.modifiers) &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == iface &&
                            m.returnType == java.lang.Void.TYPE
                        ) {
                            try {
                                m.isAccessible = true
                                m.invoke(pref, proxy)
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                    for (f in c.declaredFields) {
                        if (f.type == iface) {
                            try {
                                f.isAccessible = true
                                f.set(pref, proxy)
                                return
                            } catch (_: Throwable) {}
                        }
                    }
                }
                c = c.superclass
            }
        }

        fun newPref(clazz: Class<*>): Any {
            return try {
                clazz.getConstructor(Context::class.java).newInstance(context)
            } catch (_: Throwable) {
                clazz.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
                    .newInstance(context, null)
            }
        }

        fun newSwitch(): Any {
            val names = arrayOf(
                "com.nothing.settings.widget.NtSwitchPreference",
                "com.nothing.settings.widget.NothingSwitchPreference",
                "androidx.preference.SwitchPreferenceCompat",
                "androidx.preference.SwitchPreference",
                "androidx.preference.TwoStatePreference"
            )
            for (name in names) {
                val clazz = XposedHelpers.findClassIfExists(name, classLoader)
                if (clazz != null) {
                    try {
                        return newPref(clazz)
                    } catch (_: Throwable) {}
                }
            }
            return newPref(plainPrefClass)
        }

        fun updateActivityTitle() {
            try {
                val act = XposedHelpers.callMethod(fragment, "getActivity") ?: return
                updateActivityTitle(act)
            } catch (_: Throwable) {}
        }

        updateActivityTitle()
        setPrefTitle(screen, "Nothing Tweaks")

        // category
        fun addCategory(title: String, order: Int): Any {
            val cat = newPref(catClass)
            setPrefTitle(cat, title)
            setPrefOrder(cat, order)
            setOrderingAsAdded(cat, true)
            addPref(screen, cat)
            return cat
        }

        // switch
        fun addSwitch(
            group: Any,
            key: String,
            title: String,
            summary: String,
            defaultVal: Boolean,
            requiresReboot: Boolean = false,
            order: Int = 0
        ): Any {
            val pref = newSwitch()
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            if (summary.isNotEmpty()) setPrefSummary(pref, summary)
            setPrefOrder(pref, order)

            val currentVal = allPrefs?.getBoolean(key, defaultVal) ?: defaultVal
            setSwitchChecked(pref, currentVal)

            setPrefChangeListener(pref) { _, newValObj ->
                val newVal = (newValObj as? Boolean)
                    ?: (newValObj as? String)?.toBooleanStrictOrNull()
                    ?: (newValObj as? Number)?.let { it.toInt() != 0 }
                    ?: false
                RemotePreferenceProvider.putPrefBoolean(context, key, newVal)
                setSwitchChecked(pref, newVal)
                if (newVal && requiresReboot) {
                    Toast.makeText(context, "Please reboot your phone to apply this change.", Toast.LENGTH_LONG).show()
                }
                true
            }
            addPref(group, pref)
            return pref
        }

        // slider dialog
        fun addSeekBar(
            group: Any,
            key: String,
            title: String,
            summary: String,
            min: Int,
            max: Int,
            defaultVal: Int,
            unit: String = "",
            requiresReboot: Boolean = false,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            var currentVal = allPrefs?.getInt(key, defaultVal) ?: defaultVal
            fun updateSummary(v: Int) {
                val formattedVal = if (v == -1) "System Default" else if (unit.isNotEmpty()) "$v $unit" else "$v"
                val s = if (summary.isNotEmpty()) "$summary: $formattedVal" else formattedVal
                setPrefSummary(pref, s)
            }
            updateSummary(currentVal)

            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                val layout = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (24 * act.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, (12 * act.resources.displayMetrics.density).toInt())
                }
                val tv = TextView(act).apply {
                    text = if (currentVal == -1) "System Default" else if (unit.isNotEmpty()) "$currentVal $unit" else "$currentVal"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, (12 * act.resources.displayMetrics.density).toInt())
                }
                val sb = SeekBar(act).apply {
                    this.max = max - min
                    this.progress = currentVal - min
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            val actual = p + min
                            tv.text = if (actual == -1) "System Default" else if (unit.isNotEmpty()) "$actual $unit" else "$actual"
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                layout.addView(tv)
                layout.addView(sb)

                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val chosenVal = sb.progress + min
                        currentVal = chosenVal
                        RemotePreferenceProvider.putPrefInt(context, key, chosenVal)
                        updateSummary(chosenVal)
                        if (requiresReboot) {
                            Toast.makeText(context, "Please reboot your phone to apply this change.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // list options dialog
        fun addListPref(
            group: Any,
            key: String,
            title: String,
            entries: Array<String>,
            values: Array<String>,
            defaultVal: String,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            var currentVal = allPrefs?.getString(key, defaultVal) ?: defaultVal
            fun updateSummary(v: String) {
                val idx = values.indexOf(v)
                val entry = if (idx >= 0 && idx < entries.size) entries[idx] else v
                setPrefSummary(pref, entry)
            }
            updateSummary(currentVal)

            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                var selectedIdx = values.indexOf(currentVal)
                if (selectedIdx < 0) selectedIdx = 0

                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setSingleChoiceItems(entries, selectedIdx) { dialog, which ->
                        val newVal = values[which]
                        currentVal = newVal
                        RemotePreferenceProvider.putPrefString(context, key, newVal)
                        updateSummary(newVal)
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // text input dialog
        fun addEditPref(
            group: Any,
            key: String,
            title: String,
            defaultVal: String,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            var currentVal = allPrefs?.getString(key, defaultVal) ?: defaultVal
            setPrefSummary(pref, if (currentVal.isNotEmpty()) currentVal else "(Not set)")

            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                val editText = EditText(act).apply {
                    this.inputType = inputType
                    this.setText(currentVal)
                    this.setSelection(this.text.length)
                }
                val container = FrameLayout(act).apply {
                    val pad = (24 * act.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                    addView(editText)
                }

                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val newVal = editText.text.toString().trim()
                        currentVal = newVal
                        RemotePreferenceProvider.putPrefString(context, key, newVal)
                        setPrefSummary(pref, if (newVal.isNotEmpty()) newVal else "(Not set)")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // app picker dialog
        fun addAppPickerPref(
            group: Any,
            key: String,
            title: String,
            defaultSummary: String,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            val currentPkg = allPrefs?.getString(key, "") ?: ""
            fun updateAppSummary(pkg: String) {
                if (pkg.isNotEmpty()) {
                    try {
                        val pm = context.packageManager
                        val info = pm.getApplicationInfo(pkg, 0)
                        setPrefSummary(pref, pm.getApplicationLabel(info).toString())
                    } catch (_: Throwable) {
                        setPrefSummary(pref, pkg)
                    }
                } else {
                    setPrefSummary(pref, if (defaultSummary.isNotEmpty()) defaultSummary else "(Not set)")
                }
            }
            updateAppSummary(currentPkg)

            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                val pm = act.packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val apps = pm.queryIntentActivities(mainIntent, 0)
                    .map { it.activityInfo }
                    .sortedBy { pm.getApplicationLabel(it.applicationInfo).toString().lowercase() }

                val appLabels = apps.map { pm.getApplicationLabel(it.applicationInfo).toString() }.toTypedArray()
                val appPackages = apps.map { it.packageName }.toTypedArray()

                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setItems(appLabels) { _, which ->
                        val chosenPkg = appPackages[which]
                        RemotePreferenceProvider.putPrefString(context, key, chosenPkg)
                        updateAppSummary(chosenPkg)
                    }
                    .setNeutralButton("Clear") { _, _ ->
                        RemotePreferenceProvider.putPrefString(context, key, "")
                        updateAppSummary("")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // color picker dialog
        fun addColorPickerPref(
            group: Any,
            key: String,
            title: String,
            defaultVal: String,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            var curFpColor = allPrefs?.getString(key, defaultVal) ?: defaultVal
            setPrefSummary(pref, curFpColor)
            val colorNames = arrayOf(
                "Nothing Red (#FFD71921)",
                "Electric Blue (#FF007AFF)",
                "Cyber Green (#FF00E676)",
                "Pure White (#FFFFFFFF)",
                "Amber Orange (#FFFF9100)",
                "Neon Purple (#FFB388FF)",
                "Custom Hex Value..."
            )
            val colorHexes = arrayOf(
                "#FFD71921",
                "#FF007AFF",
                "#FF00E676",
                "#FFFFFFFF",
                "#FFFF9100",
                "#FFB388FF",
                "custom"
            )
            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setItems(colorNames) { _, which ->
                        val chosen = colorHexes[which]
                        if (chosen == "custom") {
                            val input = EditText(act).apply { setText(curFpColor) }
                            AlertDialog.Builder(act)
                                .setTitle("Enter Hex Color (e.g. #FFD71921)")
                                .setView(input)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val hex = input.text.toString().trim()
                                    curFpColor = hex
                                    RemotePreferenceProvider.putPrefString(context, key, hex)
                                    setPrefSummary(pref, hex)
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            curFpColor = chosen
                            RemotePreferenceProvider.putPrefString(context, key, chosen)
                            setPrefSummary(pref, chosen)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // hide status bar icons dialog
        fun addHideStatusBarIconsPref(
            group: Any,
            key: String,
            title: String,
            summary: String,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            if (summary.isNotEmpty()) setPrefSummary(pref, summary)
            setPrefOrder(pref, order)

            val iconKeys = arrayOf(
                StatusBarIconHooks.PREF_HIDE_STATUSBAR_CLOCK,
                StatusBarIconHooks.PREF_HIDE_SILENT_ICON,
                StatusBarIconHooks.PREF_HIDE_NETWORK_TYPE,
                StatusBarIconHooks.PREF_HIDE_SIM1,
                StatusBarIconHooks.PREF_HIDE_SIM2,
                StatusBarIconHooks.PREF_HIDE_DUAL_SIM
            )
            val iconLabels = arrayOf(
                "Clock",
                "Mute / Silent Icon",
                "Network Type (4G/5G)",
                "SIM 1 Indicator",
                "SIM 2 Indicator",
                "Dual SIM Slot"
            )
            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                val checkedItems = BooleanArray(iconKeys.size) { i ->
                    RemotePreferenceProvider.getPrefBoolean(context, iconKeys[i], false)
                }
                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setMultiChoiceItems(iconLabels, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                        RemotePreferenceProvider.putPrefBoolean(context, iconKeys[which], isChecked)
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("Reset All") { _, _ ->
                        for (k in iconKeys) {
                            RemotePreferenceProvider.putPrefBoolean(context, k, false)
                        }
                    }
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // volume steps dialog
        fun addVolumeStepsPref(
            group: Any,
            key: String,
            title: String,
            order: Int = 0
        ): Any {
            val pref = newPref(plainPrefClass)
            setPrefKey(pref, key)
            setPrefTitle(pref, title)
            setPrefOrder(pref, order)

            fun updateVolStepsSummary() {
                val enabled = RemotePreferenceProvider.getPrefBoolean(context, VolumeStepsHooks.KEY_ENABLED, false)
                if (!enabled) {
                    setPrefSummary(pref, "Default system steps (Disabled)")
                } else {
                    val media = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_MEDIA, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
                    val ring = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_RING, VolumeStepsHooks.DEFAULT_RING_STEPS)
                    val alarm = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_ALARM, VolumeStepsHooks.DEFAULT_ALARM_STEPS)
                    setPrefSummary(pref, "Media: $media · Ring: $ring · Alarm: $alarm")
                }
            }
            updateVolStepsSummary()
            setPrefClickListener(pref) {
                val act = XposedHelpers.callMethod(fragment, "getActivity") as? Activity ?: return@setPrefClickListener
                val isEnabled = RemotePreferenceProvider.getPrefBoolean(context, VolumeStepsHooks.KEY_ENABLED, false)
                val mediaVal = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_MEDIA, VolumeStepsHooks.DEFAULT_MEDIA_STEPS)
                val ringVal = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_RING, VolumeStepsHooks.DEFAULT_RING_STEPS)
                val alarmVal = RemotePreferenceProvider.getPrefInt(context, VolumeStepsHooks.KEY_ALARM, VolumeStepsHooks.DEFAULT_ALARM_STEPS)

                val layout = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (20 * act.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                val checkEnabled = CheckBox(act).apply {
                    text = "Enable Custom Volume Steps"
                    isChecked = isEnabled
                }
                layout.addView(checkEnabled)

                fun createSlider(label: String, cur: Int, min: Int, max: Int): SeekBar {
                    val tv = TextView(act).apply { text = "$label: $cur" }
                    val sb = SeekBar(act).apply {
                        this.max = max - min
                        progress = cur - min
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                                tv.text = "$label: ${p + min}"
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }
                    layout.addView(tv)
                    layout.addView(sb)
                    return sb
                }
                val sbMedia = createSlider("Media Steps (15 - 100)", mediaVal, 15, 100)
                val sbRing = createSlider("Ring Steps (7 - 50)", ringVal, 7, 50)
                val sbAlarm = createSlider("Alarm Steps (7 - 50)", alarmVal, 7, 50)

                AlertDialog.Builder(act)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        RemotePreferenceProvider.putPrefBoolean(context, VolumeStepsHooks.KEY_ENABLED, checkEnabled.isChecked)
                        RemotePreferenceProvider.putPrefInt(context, VolumeStepsHooks.KEY_MEDIA, sbMedia.progress + 15)
                        RemotePreferenceProvider.putPrefInt(context, VolumeStepsHooks.KEY_RING, sbRing.progress + 7)
                        RemotePreferenceProvider.putPrefInt(context, VolumeStepsHooks.KEY_ALARM, sbAlarm.progress + 7)
                        updateVolStepsSummary()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPref(group, pref)
            return pref
        }

        // load all categories and preferences dynamically from preferences.xml
        val modContext = try {
            context.createPackageContext("com.souleven.nothingos", Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE)
        } catch (_: Throwable) {
            context
        }
        var itemOrder = 0
        try {
            val parser = modContext.resources.getXml(com.souleven.nothingos.R.xml.preferences)
            var currentCategory: Any? = null
            var categoryIndex = 0

            fun getAttr(name: String): String? {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == name) {
                        val resId = parser.getAttributeResourceValue(i, 0)
                        if (resId != 0) {
                            try {
                                return modContext.getString(resId)
                            } catch (_: Throwable) {}
                        }
                        return parser.getAttributeValue(i)
                    }
                }
                return null
            }

            fun getAttrResId(name: String): Int {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == name) {
                        return parser.getAttributeResourceValue(i, 0)
                    }
                }
                return 0
            }

            fun getAttrStringArray(name: String): Array<String>? {
                val resId = getAttrResId(name)
                if (resId != 0) {
                    try {
                        return modContext.resources.getStringArray(resId)
                    } catch (_: Throwable) {}
                }
                return null
            }

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (tag == "PreferenceCategory") {
                        val catTitle = getAttr("title") ?: "Category ${++categoryIndex}"
                        currentCategory = addCategory(catTitle, itemOrder++)
                    } else if (tag != "PreferenceScreen" && currentCategory != null) {
                        val key = getAttr("key")
                        if (key != null) {
                            val title = getAttr("title") ?: key
                            val summary = getAttr("summary") ?: ""
                            val defaultVal = getAttr("defaultValue")
                            val min = getAttr("min")?.toIntOrNull()
                            val max = getAttr("max")?.toIntOrNull()
                            val explicitOrder = getAttr("order")?.toIntOrNull() ?: itemOrder++
                            val requiresReboot = summary.contains("reboot", ignoreCase = true)

                            when {
                                tag.endsWith("SeekBarPreference") || tag.contains("SeekBar") -> {
                                    val minVal = min ?: 0
                                    val maxVal = max ?: 100
                                    val defInt = defaultVal?.toIntOrNull() ?: minVal
                                    val unit = if (key.contains("padding")) "dp" else if (key.contains("cap") || key.contains("battery")) "%" else ""
                                    addSeekBar(currentCategory, key, title, summary, minVal, maxVal, defInt, unit, requiresReboot, explicitOrder)
                                }
                                tag == "SwitchPreferenceCompat" || tag.endsWith("SwitchPreference") -> {
                                    val defBool = defaultVal?.toBooleanStrictOrNull() ?: false
                                    addSwitch(currentCategory, key, title, summary, defBool, requiresReboot, explicitOrder)
                                }
                                tag == "ListPreference" -> {
                                    val entries = getAttrStringArray("entries") ?: emptyArray()
                                    val values = getAttrStringArray("entryValues") ?: emptyArray()
                                    val defStr = defaultVal ?: ""
                                    addListPref(currentCategory, key, title, entries, values, defStr, explicitOrder)
                                }
                                tag == "EditTextPreference" -> {
                                    val inputType = if (key.contains("timeout") || key.contains("max_notif")) {
                                        InputType.TYPE_CLASS_NUMBER
                                    } else {
                                        InputType.TYPE_CLASS_TEXT
                                    }
                                    val defStr = defaultVal ?: ""
                                    addEditPref(currentCategory, key, title, defStr, inputType, explicitOrder)
                                }
                                tag.endsWith("ColorPreference") || key == "fp_color" -> {
                                    addColorPickerPref(currentCategory, key, title, defaultVal ?: "#FFD71921", explicitOrder)
                                }
                                key == "pref_hide_statusbar_icons" -> {
                                    addHideStatusBarIconsPref(currentCategory, key, title, summary, explicitOrder)
                                }
                                key == "pref_volume_steps" -> {
                                    addVolumeStepsPref(currentCategory, key, title, explicitOrder)
                                }
                                key.endsWith("_app") -> {
                                    addAppPickerPref(currentCategory, key, title, summary, explicitOrder)
                                }
                                tag.endsWith("ExpandableInfoNotePreference") -> {
                                    val notePref = newPref(plainPrefClass)
                                    setPrefKey(notePref, key)
                                    setPrefTitle(notePref, title)
                                    if (summary.isNotEmpty()) setPrefSummary(notePref, summary)
                                    setPrefOrder(notePref, explicitOrder)
                                    setPrefSelectable(notePref, false)
                                    addPref(currentCategory, notePref)
                                }
                                else -> {
                                    val plainPref = newPref(plainPrefClass)
                                    setPrefKey(plainPref, key)
                                    setPrefTitle(plainPref, title)
                                    if (summary.isNotEmpty()) setPrefSummary(plainPref, summary)
                                    setPrefOrder(plainPref, explicitOrder)
                                    addPref(currentCategory, plainPref)
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (t: Throwable) {
            // XposedBridge.log("$TAG [SettingsBuilder] XML parse error: ${t.message}")
        }

        // developer credits and about section
        try {
            fun getModString(resId: Int, fallback: String): String {
                return try {
                    modContext.getString(resId)
                } catch (_: Throwable) {
                    fallback
                }
            }

            val catTitle = getModString(com.souleven.nothingos.R.string.about_category_title, "About & Credits")
            val catAbout = addCategory(catTitle, itemOrder++)

            val devName = getModString(com.souleven.nothingos.R.string.about_developer_name, "Ayush Maurya")
            val devHandle = getModString(com.souleven.nothingos.R.string.about_developer_handle, "Revealed SoulEven (@RevealedSoulEven)")
            val devUrl = getModString(com.souleven.nothingos.R.string.about_developer_url, "https://github.com/RevealedSoulEven")

            val devPref = newPref(plainPrefClass)
            setPrefKey(devPref, "pref_about_developer")
            setPrefTitle(devPref, devName)
            setPrefSummary(devPref, devHandle)
            setPrefOrder(devPref, itemOrder++)
            setPrefClickListener(devPref) {
                openUrl(context, devUrl)
            }
            addPref(catAbout, devPref)

            val srcTitle = getModString(com.souleven.nothingos.R.string.about_source_title, "Source Code")
            val srcSummary = getModString(com.souleven.nothingos.R.string.about_source_summary, "GitHub repository")
            val srcUrl = getModString(com.souleven.nothingos.R.string.about_source_url, "https://github.com/RevealedSoulEven/NothingTweaks")

            val sourcePref = newPref(plainPrefClass)
            setPrefKey(sourcePref, "pref_about_source")
            setPrefTitle(sourcePref, srcTitle)
            setPrefSummary(sourcePref, srcSummary)
            setPrefOrder(sourcePref, itemOrder++)
            setPrefClickListener(sourcePref) {
                openUrl(context, srcUrl)
            }
            addPref(catAbout, sourcePref)

            val issueTitle = getModString(com.souleven.nothingos.R.string.about_issue_title, "Report an Issue")
            val issueSummary = getModString(com.souleven.nothingos.R.string.about_issue_summary, "Submit a bug report on GitHub")
            val issueUrl = getModString(com.souleven.nothingos.R.string.about_issue_url, "https://github.com/RevealedSoulEven/NothingTweaks/issues/new?labels=bug&title=%28bug%29+")

            val bugPref = newPref(plainPrefClass)
            setPrefKey(bugPref, "pref_about_bug")
            setPrefTitle(bugPref, issueTitle)
            setPrefSummary(bugPref, issueSummary)
            setPrefOrder(bugPref, itemOrder++)
            setPrefClickListener(bugPref) {
                openUrl(context, issueUrl)
            }
            addPref(catAbout, bugPref)

            val featTitle = getModString(com.souleven.nothingos.R.string.about_feature_title, "Request a Feature")
            val featSummary = getModString(com.souleven.nothingos.R.string.about_feature_summary, "Suggest new ideas or feature requests")
            val featUrl = getModString(com.souleven.nothingos.R.string.about_feature_url, "https://github.com/RevealedSoulEven/NothingTweaks/issues/new?labels=enhancement&title=%28feat%29+")

            val featPref = newPref(plainPrefClass)
            setPrefKey(featPref, "pref_about_feature")
            setPrefTitle(featPref, featTitle)
            setPrefSummary(featPref, featSummary)
            setPrefOrder(featPref, itemOrder++)
            setPrefClickListener(featPref) {
                openUrl(context, featUrl)
            }
            addPref(catAbout, featPref)

            val tagline = getModString(com.souleven.nothingos.R.string.about_tagline, "Made with ❤ for Nothing OS")
            val verPref = newPref(plainPrefClass)
            setPrefKey(verPref, "pref_about_version")
            val versionName = try {
                context.packageManager.getPackageInfo("com.souleven.nothingos", 0).versionName ?: "1.0"
            } catch (_: Throwable) {
                "1.0"
            }
            setPrefTitle(verPref, "Nothing Tweaks v$versionName")
            setPrefSummary(verPref, tagline)
            setPrefOrder(verPref, itemOrder++)
            setPrefSelectable(verPref, false)
            addPref(catAbout, verPref)
        } catch (_: Throwable) {}

        // ensure bottom spacing matching Nothing OS Settings
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post { adjustRecyclerViewPadding(fragment) }
        mainHandler.postDelayed({ adjustRecyclerViewPadding(fragment) }, 150)
        mainHandler.postDelayed({ adjustRecyclerViewPadding(fragment) }, 500)

        // XposedBridge.log("$TAG [SettingsBuilder] Nothing Tweaks PreferenceScreen successfully built!")
    }

    fun adjustRecyclerViewPadding(fragment: Any) {
        try {
            val rv = (try { XposedHelpers.callMethod(fragment, "getListView") as? android.view.ViewGroup } catch (_: Throwable) { null })
                ?: (try {
                    val fView = XposedHelpers.callMethod(fragment, "getView") as? View
                    fView?.findViewById<View>(android.R.id.list) as? android.view.ViewGroup
                        ?: findRecyclerView(fView)
                } catch (_: Throwable) { null })

            if (rv != null) {
                rv.clipToPadding = false
                val res = rv.context.resources
                val bottomSpace = (30 * res.displayMetrics.density).toInt() // manually set footer bottom padding
                rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, bottomSpace)
            }
        } catch (_: Throwable) {}
    }

    private fun findRecyclerView(view: View?): android.view.ViewGroup? {
        if (view == null) return null
        if (view.javaClass.name.contains("RecyclerView") && view is android.view.ViewGroup) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findRecyclerView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }
}
