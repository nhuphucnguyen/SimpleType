package dev.phucngu.simpletype.ui

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.phucngu.simpletype.R

/**
 * Entry-point screen. Android requires the user to (1) enable an IME in system settings
 * and (2) select it as the active keyboard — neither can be done programmatically — so this
 * screen links to both system flows, shows whether SimpleType is enabled, and offers a
 * field to try the keyboard out.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(
            if (isImeEnabled()) R.string.status_enabled else R.string.status_not_enabled
        )
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }
}
