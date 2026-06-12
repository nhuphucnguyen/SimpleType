package dev.phucngu.simpletype.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.KeyboardMetrics
import dev.phucngu.simpletype.ime.LatinKeyboardView
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

        setupSizeControls()
    }

    // ---- Keyboard sizing ----

    private lateinit var sizePreview: LatinKeyboardView
    private lateinit var lblRow: TextView
    private lateinit var lblGapH: TextView
    private lateinit var lblGapV: TextView
    private lateinit var seekRow: SeekBar
    private lateinit var seekGapH: SeekBar
    private lateinit var seekGapV: SeekBar

    private fun prefs() = getSharedPreferences("simpletype_prefs", MODE_PRIVATE)

    /** Three sliders (key height, horizontal gap, vertical gap) with a live keyboard preview. */
    private fun setupSizeControls() {
        sizePreview = findViewById(R.id.size_preview)
        lblRow = findViewById(R.id.lbl_row_height)
        lblGapH = findViewById(R.id.lbl_gap_h)
        lblGapV = findViewById(R.id.lbl_gap_v)
        seekRow = findViewById(R.id.seek_row_height)
        seekGapH = findViewById(R.id.seek_gap_h)
        seekGapV = findViewById(R.id.seek_gap_v)

        seekRow.max = (KeyboardMetrics.ROW_HEIGHT_MAX - KeyboardMetrics.ROW_HEIGHT_MIN).toInt()
        seekGapH.max = (KeyboardMetrics.GAP_MAX - KeyboardMetrics.GAP_MIN).toInt()
        seekGapV.max = (KeyboardMetrics.GAP_MAX - KeyboardMetrics.GAP_MIN).toInt()

        seekToMetrics(KeyboardMetrics.load(prefs()))
        applySizeFromSeekBars()

        val onChange = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) = applySizeFromSeekBars()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        seekRow.setOnSeekBarChangeListener(onChange)
        seekGapH.setOnSeekBarChangeListener(onChange)
        seekGapV.setOnSeekBarChangeListener(onChange)

        findViewById<Button>(R.id.btn_size_reset).setOnClickListener {
            seekToMetrics(KeyboardMetrics.DEFAULT)
            applySizeFromSeekBars()
        }
    }

    private fun seekToMetrics(m: KeyboardMetrics) {
        seekRow.progress = (m.rowHeightDp - KeyboardMetrics.ROW_HEIGHT_MIN).toInt()
        seekGapH.progress = (m.gapHorizontalDp - KeyboardMetrics.GAP_MIN).toInt()
        seekGapV.progress = (m.gapVerticalDp - KeyboardMetrics.GAP_MIN).toInt()
    }

    private fun applySizeFromSeekBars() {
        val m = KeyboardMetrics.of(
            KeyboardMetrics.ROW_HEIGHT_MIN + seekRow.progress,
            KeyboardMetrics.GAP_MIN + seekGapH.progress,
            KeyboardMetrics.GAP_MIN + seekGapV.progress,
        )
        lblRow.text = getString(R.string.size_row_height, m.rowHeightDp.toInt())
        lblGapH.text = getString(R.string.size_gap_h, m.gapHorizontalDp.toInt())
        lblGapV.text = getString(R.string.size_gap_v, m.gapVerticalDp.toInt())
        sizePreview.applyMetrics(m)
        KeyboardMetrics.save(prefs(), m)
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
