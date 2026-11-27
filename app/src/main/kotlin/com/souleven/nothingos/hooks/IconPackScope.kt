package com.souleven.nothingos.hooks

internal data class IconPackScope(val excludedPackages: Set<String> = emptySet()) {
    fun excludes(packageName: String): Boolean = packageName in excludedPackages

    companion object {
        const val KEY_APPS = "pref_nothing_icon_pack_apps"
        const val KEY_EXCLUDED_PACKAGES = "pref_nothing_icon_pack_excluded_packages"

        fun read(getString: (String, String) -> String): IconPackScope = IconPackScope(
            getString(KEY_EXCLUDED_PACKAGES, "").splitToSequence('\n').filter { it.isNotEmpty() }.toSet()
        )
    }
}
