package com.souleven.nothingos.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.CollapsingToolbarLayout
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.souleven.nothingos.ModuleStatus
import com.souleven.nothingos.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.app_name)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.menu.findItem(R.id.action_restart)?.icon?.setTint(getColor(R.color.restart_icon_color))

        CoroutineScope(Dispatchers.Main).launch {
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    process.waitFor() == 0
                } catch (e: Exception) {
                    false
                }
            }
            if (hasRoot) {
                toolbar.setOnMenuItemClickListener { item ->
                    if (item.itemId == R.id.action_restart) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill systemui; pkill launcher; pkill -f com.google.android.inputmethod.latin"))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        true
                    } else false
                }
            } else {
                toolbar.setOnMenuItemClickListener { item ->
                    if (item.itemId == R.id.action_restart) {
                        Toast.makeText(this@MainActivity, "Root permission not granted", Toast.LENGTH_SHORT).show()
                        true
                    } else false
                }
            }
        }

        applyInsets()
        bindStatusBanner()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume so toggling the module in LSPosed is reflected without a
        // full app restart being strictly necessary.
        bindStatusBanner()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val appBar = findViewById<View>(R.id.app_bar)
        val scroll = findViewById<View>(R.id.content_scroll)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            appBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            scroll.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * ModuleStatus.isModuleActive() is compiled to return false; MainHook overrides it to
     * true when the module is genuinely loaded. Anything else would be guesswork.
     */
    private fun bindStatusBanner() {
        val active = try {
            ModuleStatus.isModuleActive()
        } catch (t: Throwable) {
            false
        }

        val card = findViewById<MaterialCardView>(R.id.status_card)
        val title = findViewById<TextView>(R.id.status_title)
        val desc = findViewById<TextView>(R.id.status_desc)

        if (active) {
            title.setText(R.string.status_active)
            desc.setText(R.string.status_active_desc)
            card.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSecondaryContainer))
            title.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            desc.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
        } else {
            title.setText(R.string.status_inactive)
            desc.setText(R.string.status_inactive_desc)
            card.setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorErrorContainer))
            title.setTextColor(themeColor(com.google.android.material.R.attr.colorOnErrorContainer))
            desc.setTextColor(themeColor(com.google.android.material.R.attr.colorOnErrorContainer))
        }
    }

    private fun themeColor(attr: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            return typed.getColor(0, 0)
        } finally {
            typed.recycle()
        }
    }
}
