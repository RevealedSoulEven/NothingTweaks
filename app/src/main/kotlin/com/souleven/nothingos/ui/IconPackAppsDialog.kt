package com.souleven.nothingos.ui

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
import com.souleven.nothingos.hooks.RemotePreferenceProvider
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class IconPackAppsDialog : DialogFragment() {
    private var pendingExclusions: MutableSet<String>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        pendingExclusions = savedInstanceState?.getStringArrayList(STATE_EXCLUSIONS)?.toMutableSet()

        val context = requireContext()
        val listPrefs = context.getSharedPreferences(PREFS_LIST_UI, Context.MODE_PRIVATE)
        var editableOnly = listPrefs.getBoolean(KEY_EDITABLE_ONLY, false)
        var enabledFirst = listPrefs.getBoolean(KEY_ENABLED_FIRST, false)
        val prefs = RemotePreferenceProvider.getDeviceProtectedPrefs(context)
        val currentScope = IconPackScope.read { key, default -> prefs.getString(key, default) ?: default }
        val view = layoutInflater.inflate(R.layout.dialog_icon_pack_apps, null)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.icon_pack_apps_toolbar)
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

        fun updateApps() {
            val rows = buildIconPackRows(apps, pendingExclusions.orEmpty(), editableOnly, enabledFirst, nameOrder)
            adapter.updateApps(rows)
            updateEmptyState()
        }

        adapter = AppPickerAdapter(emptyList()) { app ->
            val exclusions = pendingExclusions ?: return@AppPickerAdapter
            if (app.packageName !in editablePackages) return@AppPickerAdapter
            if (!exclusions.add(app.packageName)) exclusions.remove(app.packageName)
            updateApps()
        }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter
        search.addTextChangedListener { value ->
            adapter.filter(value?.toString().orEmpty())
            updateEmptyState()
        }

        toolbar.menu.findItem(R.id.icon_pack_editable_only).isChecked = editableOnly
        toolbar.menu.findItem(if (enabledFirst) R.id.icon_pack_sort_enabled else R.id.icon_pack_sort_name).isChecked = true
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.icon_pack_select_all -> pendingExclusions?.removeAll(editablePackages)
                R.id.icon_pack_deselect_all -> pendingExclusions?.addAll(editablePackages)
                R.id.icon_pack_editable_only -> {
                    editableOnly = !editableOnly
                    item.isChecked = editableOnly
                    listPrefs.edit().putBoolean(KEY_EDITABLE_ONLY, editableOnly).apply()
                }
                R.id.icon_pack_sort_name, R.id.icon_pack_sort_enabled -> {
                    enabledFirst = item.itemId == R.id.icon_pack_sort_enabled
                    item.isChecked = true
                    listPrefs.edit().putBoolean(KEY_ENABLED_FIRST, enabledFirst).apply()
                }
                else -> return@setOnMenuItemClickListener false
            }
            updateApps()
            true
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setBackground(ContextCompat.getDrawable(context, R.drawable.dialog_background))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val exclusions = pendingExclusions ?: return@setPositiveButton
                if (isPackInstalled(context.packageManager)) {
                    prefs.edit().putString(IconPackScope.KEY_EXCLUDED_PACKAGES, exclusions.sorted().joinToString("\n")).apply()
                } else {
                    Toast.makeText(context, R.string.icon_pack_not_installed, Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        progress.visibility = View.VISIBLE
        dialog.setOnShowListener {
            dialog.setOnShowListener(null)
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { loadApps(context) } }
                progress.visibility = View.GONE
                loadFailed = result.isFailure
                apps = result.getOrDefault(emptyList())
                val (editable, builtIn) = apps.partition { it.monochrome != MonochromeState.BUILT_IN }
                editablePackages = editable.mapTo(mutableSetOf()) { it.packageName }
                if (!loadFailed) {
                    val exclusions = pendingExclusions ?: currentScope.excludedPackages.toMutableSet()
                    builtIn.forEach { exclusions.remove(it.packageName) }
                    pendingExclusions = exclusions
                    save.isEnabled = true
                    toolbar.menu.setGroupEnabled(R.id.icon_pack_bulk_actions, editablePackages.isNotEmpty())
                }
                updateApps()
            }
        }
        return dialog
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        if (!isPackInstalled(context.packageManager)) {
            Toast.makeText(context, R.string.icon_pack_not_installed, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExclusions?.let { outState.putStringArrayList(STATE_EXCLUSIONS, ArrayList(it)) }
    }

    private fun loadApps(context: Context): List<AppItem> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .groupBy { it.componentName.packageName }
            .map { (packageName, activities) ->
                val first = activities.first()
                val firstIcon = first.getIcon(0)
                val builtIn = (firstIcon as? AdaptiveIconDrawable)?.monochrome != null ||
                    activities.asSequence().drop(1).any { (it.getIcon(0) as? AdaptiveIconDrawable)?.monochrome != null }
                val state = if (builtIn) MonochromeState.BUILT_IN else MonochromeState.ENABLED
                AppItem(first.label.toString(), packageName, firstIcon, state)
            }
    }

    companion object {
        private const val PACKAGE_NAME = "com.nothing.icon"
        private const val STATE_EXCLUSIONS = "exclusions"
        private const val PREFS_LIST_UI = "icon_pack_list_ui"
        private const val KEY_EDITABLE_ONLY = "editable_only"
        private const val KEY_ENABLED_FIRST = "enabled_first"

        fun isPackInstalled(packageManager: PackageManager): Boolean = try {
            packageManager.getApplicationInfo(PACKAGE_NAME, PackageManager.ApplicationInfoFlags.of(0L))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

internal fun buildIconPackRows(
    apps: List<AppItem>,
    exclusions: Set<String>,
    editableOnly: Boolean,
    enabledFirst: Boolean,
    nameOrder: Comparator<AppItem>
): List<AppItem> {
    val order = if (enabledFirst) compareBy<AppItem> { it.monochrome }.then(nameOrder) else nameOrder
    return apps.asSequence()
        .filterNot { editableOnly && it.monochrome == MonochromeState.BUILT_IN }
        .map {
            if (it.monochrome != MonochromeState.BUILT_IN && it.packageName in exclusions) {
                it.copy(monochrome = MonochromeState.DISABLED)
            } else {
                it
            }
        }
        .sortedWith(order)
        .toList()
}
