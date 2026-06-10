package dev.phucngu.simpletype.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.voice.ModelManager
import dev.phucngu.simpletype.voice.VoiceLanguage
import kotlin.concurrent.thread

/**
 * Entry-point screen. Android requires the user to (1) enable an IME in system settings
 * and (2) select it as the active keyboard — neither can be done programmatically — so this
 * screen links to both system flows, shows whether SimpleType is enabled, and offers a
 * field to try the keyboard out.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var btnEn: Button
    private lateinit var btnVi: Button
    private val models by lazy { ModelManager(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        status = findViewById(R.id.status)
        btnEn = findViewById(R.id.btn_model_en)
        btnVi = findViewById(R.id.btn_model_vi)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        btnEn.setOnClickListener { downloadModel(VoiceLanguage.ENGLISH, btnEn) }
        btnVi.setOnClickListener { downloadModel(VoiceLanguage.VIETNAMESE, btnVi) }
    }

    /** Download + unpack a voice model on a background thread, reporting progress on the button. */
    private fun downloadModel(language: VoiceLanguage, button: Button) {
        if (models.isInstalled(language)) {
            button.text = getString(R.string.model_installed)
            return
        }
        button.isEnabled = false
        thread {
            try {
                models.download(language) { percent ->
                    mainHandler.post { button.text = getString(R.string.model_downloading, percent) }
                }
                mainHandler.post {
                    button.text = getString(R.string.model_installed)
                    button.isEnabled = false
                }
            } catch (t: Throwable) {
                mainHandler.post {
                    button.text = getString(R.string.model_download_failed, t.message ?: "error")
                    button.isEnabled = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(
            if (isImeEnabled()) R.string.status_enabled else R.string.status_not_enabled
        )
        if (models.isInstalled(VoiceLanguage.ENGLISH)) {
            btnEn.text = getString(R.string.model_installed)
            btnEn.isEnabled = false
        }
        if (models.isInstalled(VoiceLanguage.VIETNAMESE)) {
            btnVi.text = getString(R.string.model_installed)
            btnVi.isEnabled = false
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }
}
