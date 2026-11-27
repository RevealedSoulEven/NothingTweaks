package com.souleven.nothingos.ui

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.souleven.nothingos.R
import com.souleven.nothingos.hooks.IconPackScope
import com.souleven.nothingos.hooks.IconPreviewRequests
import com.souleven.nothingos.hooks.RemotePreferenceProvider
import java.text.Collator
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class IconPackAppsDialog : DialogFragment() {
    private var pendingExclusions: MutableSet<String>? = null
    private var refreshPackAvailability: (() -> Unit)? = null
    private var stopPreview: (() -> Unit)? = null
    private var previewReply: Messenger? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        pendingExclusions = savedInstanceState?.getStringArrayList(STATE_EXCLUSIONS)?.toMutableSet()

        val context = requireContext()
        val listPrefs = context.getSharedPreferences(PREFS_LIST_UI, Context.MODE_PRIVATE)
        var enabledFirst = listPrefs.getBoolean(KEY_ENABLED_FIRST, false)
        var filterNative = listPrefs.getBoolean(KEY_FILTER_NATIVE, false)
        var iconPackInstalled = IconPackScope.isNothingIconInstalled(context.packageManager)
        val prefs = RemotePreferenceProvider.getDeviceProtectedPrefs(context)
        val currentScope = IconPackScope.read { key, default -> prefs.getString(key, default) ?: default }
        val view = layoutInflater.inflate(R.layout.dialog_icon_pack_apps, null)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.icon_pack_apps_toolbar)
        val previewButton = toolbar.menu.findItem(R.id.icon_pack_preview).actionView!!
        val search = view.findViewById<TextInputEditText>(R.id.search_edit_text)
        val recycler = view.findViewById<RecyclerView>(R.id.apps_recycler_view)
        val empty = view.findViewById<TextView>(R.id.no_apps_found)
        val progress = view.findViewById<View>(R.id.apps_loading)
        val collator = Collator.getInstance(resources.configuration.locales[0])
        val nameOrder = compareBy<AppItem, String>(collator) { it.label }.thenBy { it.packageName }
        var apps = emptyList<AppItem>()
        var editablePackages = emptySet<String>()
        var loadFailed = false

        lateinit var adapter: AppPickerAdapter
        fun updateEmptyState() {
            empty.visibility = if (progress.visibility != View.VISIBLE && adapter.isListEmpty()) View.VISIBLE else View.GONE
            empty.setText(if (loadFailed) R.string.icon_pack_load_error else R.string.no_apps_found)
        }

        fun updateApps(resort: Boolean = false) {
            apps = buildIconPackRows(
                apps, pendingExclusions.orEmpty(), iconPackInstalled, enabledFirst,
                nameOrder.takeIf { resort }
            )
            adapter.updateApps(if (filterNative) apps.filter { it.nativeMonochromeIcon == null } else apps)
            updateEmptyState()
        }

        fun updateAvailableApps(resort: Boolean = false) {
            editablePackages = apps.asSequence()
                .filter { it.canUseMonochrome(iconPackInstalled) }
                .map { it.packageName }
                .toSet()
            toolbar.menu.setGroupEnabled(R.id.icon_pack_bulk_actions, editablePackages.isNotEmpty())
            updateApps(resort)
        }

        adapter = AppPickerAdapter(emptyList()) { app ->
            val exclusions = pendingExclusions ?: return@AppPickerAdapter
            if (app.packageName !in editablePackages) return@AppPickerAdapter
            if (!exclusions.add(app.packageName)) exclusions.remove(app.packageName)
            updateApps()
        }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.itemAnimator = null
        recycler.adapter = adapter
        search.addTextChangedListener { value ->
            adapter.filter(value?.toString().orEmpty())
            updateEmptyState()
        }

        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (view.isAttachedToWindow) {
                val data = message.data
                val received = data.keySet().mapNotNull { packageName ->
                    data.getParcelable(packageName, Bitmap::class.java)?.let { packageName to BitmapDrawable(view.resources, it) }
                }.toMap()
                adapter.updateMonochromeIcons(received)
            }
            true
        })
        previewReply = reply

        fun requestPreviews() {
            val components = apps.asSequence()
                .filter { it.canUseMonochrome(iconPackInstalled) }
                .sortedBy { filterNative && it.nativeMonochromeIcon != null }
                .mapNotNull { it.launcherComponent }
                .toList()
            if (components.isNotEmpty()) {
                runCatching { IconPreviewRequests.request(context, components, reply) }
            }
        }

        var tip: PopupWindow? = null
        fun showPreviewTip() {
            tip?.dismiss()
            tip = showPreviewHelp(previewButton)
        }
        val restoreIcons = Runnable { adapter.setMonochromePreview(false) }
        stopPreview = {
            previewButton.removeCallbacks(restoreIcons)
            restoreIcons.run()
            tip?.dismiss()
        }
        previewButton.setOnClickListener { showPreviewTip() }
        previewButton.setOnLongClickListener { button ->
            tip?.dismiss()
            button.removeCallbacks(restoreIcons)
            adapter.setMonochromePreview(true)
            if (!button.isPressed) button.postDelayed(restoreIcons, 1800)
            true
        }
        previewButton.setOnTouchListener { button, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                button.removeCallbacks(restoreIcons)
                restoreIcons.run()
            }
            false
        }
        previewButton.setOnKeyListener { button, _, event ->
            if (event.action == KeyEvent.ACTION_UP) {
                button.removeCallbacks(restoreIcons)
                restoreIcons.run()
            }
            false
        }

        toolbar.menu.findItem(R.id.icon_pack_filter_native).isChecked = filterNative
        toolbar.menu.findItem(if (enabledFirst) R.id.icon_pack_sort_enabled else R.id.icon_pack_sort_name).isChecked = true
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.icon_pack_select_all -> pendingExclusions?.removeAll(editablePackages)
                R.id.icon_pack_deselect_all -> pendingExclusions?.addAll(editablePackages)
                R.id.icon_pack_filter_native -> {
                    filterNative = !filterNative
                    item.isChecked = filterNative
                    listPrefs.edit().putBoolean(KEY_FILTER_NATIVE, filterNative).apply()
                }
                R.id.icon_pack_sort_name, R.id.icon_pack_sort_enabled -> {
                    enabledFirst = item.itemId == R.id.icon_pack_sort_enabled
                    item.isChecked = true
                    listPrefs.edit().putBoolean(KEY_ENABLED_FIRST, enabledFirst).apply()
                    updateApps(resort = true)
                    return@setOnMenuItemClickListener true
                }
                else -> return@setOnMenuItemClickListener false
            }
            updateApps()
            true
        }

        refreshPackAvailability = {
            val installed = IconPackScope.isNothingIconInstalled(context.packageManager)
            if (iconPackInstalled != installed) {
                iconPackInstalled = installed
                updateAvailableApps()
                adapter.clearMonochromeIcons()
                requestPreviews()
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setBackground(ContextCompat.getDrawable(context, R.drawable.dialog_background))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val exclusions = pendingExclusions ?: return@setPositiveButton
                prefs.edit().putString(IconPackScope.KEY_EXCLUDED_PACKAGES, exclusions.sorted().joinToString("\n")).apply()
            }
            .create()
        progress.visibility = View.VISIBLE
        dialog.setOnShowListener {
            dialog.setOnShowListener(null)
            previewButton.post {
                if (dialog.isShowing && !listPrefs.getBoolean(KEY_PREVIEW_HELP_SEEN, false)) {
                    showPreviewTip()
                    if (tip?.isShowing == true) listPrefs.edit().putBoolean(KEY_PREVIEW_HELP_SEEN, true).apply()
                }
            }
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { loadApps(context) } }
                progress.visibility = View.GONE
                loadFailed = result.isFailure
                apps = result.getOrDefault(emptyList())
                if (!loadFailed) {
                    pendingExclusions = pendingExclusions ?: currentScope.excludedPackages.toMutableSet()
                    save.isEnabled = true
                }
                iconPackInstalled = IconPackScope.isNothingIconInstalled(context.packageManager)
                updateAvailableApps(resort = true)
                requestPreviews()
            }
        }
        return dialog
    }

    override fun onResume() {
        super.onResume()
        refreshPackAvailability?.invoke()
    }

    override fun onPause() {
        stopPreview?.invoke()
        super.onPause()
    }

    override fun onDestroyView() {
        refreshPackAvailability = null
        stopPreview?.invoke()
        stopPreview = null
        previewReply?.let(IconPreviewRequests::cancel)
        previewReply = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExclusions?.let { outState.putStringArrayList(STATE_EXCLUSIONS, ArrayList(it)) }
    }

    private fun loadApps(context: Context): List<AppItem> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val darkMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val backgroundColor = if (darkMode) Color.BLACK else Color.WHITE
        val foregroundColor = if (darkMode) Color.WHITE else Color.BLACK
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .groupBy { it.componentName.packageName }
            .map { (packageName, activities) ->
                val first = activities.first()
                val firstIcon = first.getIcon(0)
                var nativeLayer = (firstIcon as? AdaptiveIconDrawable)?.monochrome
                var previewComponent = first.componentName
                if (nativeLayer == null) {
                    for (activity in activities.drop(1)) {
                        nativeLayer = (activity.getIcon(0) as? AdaptiveIconDrawable)?.monochrome
                        if (nativeLayer != null) {
                            previewComponent = activity.componentName
                            break
                        }
                    }
                }
                val nativePreview = nativeLayer?.let { layer ->
                    val foreground = (layer.constantState?.newDrawable(context.resources) ?: layer).mutate()
                    foreground.setTint(foregroundColor)
                    AdaptiveIconDrawable(ColorDrawable(backgroundColor), foreground)
                }
                AppItem(
                    first.label.toString(), packageName, firstIcon,
                    nativeMonochromeIcon = nativePreview, launcherComponent = previewComponent
                )
            }
    }

    private fun showPreviewHelp(anchor: View): PopupWindow {
        val content = layoutInflater.inflate(R.layout.popup_icon_pack_preview_help, null)
        val width = (232 * resources.displayMetrics.density).roundToInt()
        return PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setTouchModal(false)
            elevation = 6 * resources.displayMetrics.density
            content.setOnClickListener { dismiss() }
            showAsDropDown(anchor, 0, 0, Gravity.END)
        }
    }

    companion object {
        private const val STATE_EXCLUSIONS = "exclusions"
        private const val PREFS_LIST_UI = "icon_pack_list_ui"
        private const val KEY_ENABLED_FIRST = "enabled_first"
        private const val KEY_FILTER_NATIVE = "filter_native"
        private const val KEY_PREVIEW_HELP_SEEN = "preview_help_seen"
    }
}

private fun AppItem.canUseMonochrome(iconPackInstalled: Boolean): Boolean =
    iconPackInstalled || nativeMonochromeIcon != null

internal fun buildIconPackRows(
    apps: List<AppItem>,
    exclusions: Set<String>,
    iconPackInstalled: Boolean,
    enabledFirst: Boolean,
    nameOrder: Comparator<AppItem>?
): List<AppItem> {
    val rows = apps.map { app ->
        val state = when {
            !app.canUseMonochrome(iconPackInstalled) -> MonochromeState.REQUIRES_ICON_PACK
            app.packageName in exclusions -> MonochromeState.DISABLED
            else -> MonochromeState.ENABLED
        }
        app.copy(monochrome = state)
    }
    if (nameOrder == null) return rows
    val order = if (enabledFirst) compareBy<AppItem> { it.monochrome }.then(nameOrder) else nameOrder
    return rows.sortedWith(order)
}
