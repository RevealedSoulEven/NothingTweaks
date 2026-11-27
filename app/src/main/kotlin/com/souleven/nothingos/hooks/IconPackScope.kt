package com.souleven.nothingos.hooks

import android.content.pm.PackageManager

internal data class IconPackScope(val excludedPackages: Set<String> = emptySet()) {
    fun excludes(packageName: String): Boolean = packageName in excludedPackages

    companion object {
        private const val NOTHING_ICON_PACKAGE = "com.nothing.icon"
        const val KEY_APPS = "pref_nothing_icon_pack_apps"
        const val KEY_EXCLUDED_PACKAGES = "pref_nothing_icon_pack_excluded_packages"

        fun read(getString: (String, String) -> String): IconPackScope = IconPackScope(
            getString(KEY_EXCLUDED_PACKAGES, "").splitToSequence('\n').filter { it.isNotEmpty() }.toSet()
        )

        fun isNothingIconInstalled(packageManager: PackageManager): Boolean = try {
            packageManager.getApplicationInfo(NOTHING_ICON_PACKAGE, PackageManager.ApplicationInfoFlags.of(0L))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
