package cz.reallyfast

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Klavesnice zada o povoleni mikrofonu (sluzba o nej pozadat nemuze). */
const val EXTRA_REQUEST_MIC = "request_mic"

/**
 * Hlavni obrazovka: aktivace klavesnice, rozcestnik na podobrazovky nastaveni,
 * schranka, zaloha a informace o aplikaci. Samotne volby jsou v podobrazovkach
 * (Vzhled a rozlozeni, Psani, Hlas, ...), aby tahle zustala prehledna.
 */
class SettingsActivity : SettingsScreen() {

    private companion object {
        const val REQ_MIC = 3
        const val REQ_BACKUP_EXPORT = 10
        const val REQ_BACKUP_IMPORT = 11
        const val REQ_SWIPE_LOG_EXPORT = 12
        const val TITLE_MS = 900L
        const val TITLE_BRAKE_AT = 0.7f

        /**
         * Nadpis: prvnich 70 % drahy rovnomerne, zbytek zpomaluje (kvadraticky) az do klidu; casy jsou zvolene tak,
         * aby na styku rychlost navazovala (rovnomerna cast zabere ~54 % casu).
         */
        val TITLE_SLIDE = android.animation.TimeInterpolator { f ->
            val linearTime = 1f / (1f + (1f - TITLE_BRAKE_AT) * 2f / TITLE_BRAKE_AT)
            if (f < linearTime) {
                TITLE_BRAKE_AT * f / linearTime
            } else {
                val u = (f - linearTime) / (1f - linearTime)
                TITLE_BRAKE_AT + (1f - TITLE_BRAKE_AT) * (1f - (1f - u) * (1f - u))
            }
        }
    }

    private var animateTitle = false
    private var statusView: TextView? = null
    private var swipeLogButton: TextView? = null

    // ------------------------------------------------------------------ zivotni cyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animateTitle = true
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.fitsSystemWindows = true
        val container = contentColumn()
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        build(container)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) ensureMicrophone()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) ensureMicrophone()
    }

    private fun ensureMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateSwipeLogButton()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    // ------------------------------------------------------------------ obsah

    private fun build(container: LinearLayout) {
        // Misto textoveho nadpisu obrazek loga (pomer stran 10:3), zarovnany vlevo.
        val heading = android.widget.ImageView(this)
        heading.setImageResource(R.drawable.logo)
        heading.scaleType = android.widget.ImageView.ScaleType.FIT_START
        heading.adjustViewBounds = true
        heading.contentDescription = getString(R.string.app_name)
        val hlp = LinearLayout.LayoutParams(dp(220f), dp(66f))
        hlp.bottomMargin = dp(4f)
        container.addView(heading, hlp)
        if (animateTitle) {
            // Nadpis vyjede zprava za okrajem obrazovky a doleti na sve misto (jen pri vytvoreni obrazovky).
            animateTitle = false
            val from = resources.displayMetrics.widthPixels.toFloat()
            heading.translationX = from
            android.animation.ObjectAnimator.ofFloat(heading, android.view.View.TRANSLATION_X, from, 0f).apply {
                duration = TITLE_MS
                interpolator = TITLE_SLIDE
                startDelay = 60L
                start()
            }
        }
        container.addView(label(getString(R.string.settings_subtitle), 14f, dim))

        if (!RustBridge.isLoaded) {
            val warn = label(getString(R.string.core_missing), 14f, danger)
            warn.setPadding(0, dp(12f), 0, 0)
            container.addView(warn)
        }

        // Aktivace
        var box = section(container, getString(R.string.sec_setup))
        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(12f), 0, dp(4f))
            box.addView(it, rowParams())
        }
        buttonRow(box, getString(R.string.setup_enable), accent) {
            startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        buttonRow(box, getString(R.string.setup_select), accent) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val test = EditText(this)
        test.hint = getString(R.string.test_hint)
        test.setHintTextColor(dim)
        test.setTextColor(text)
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.background = rounded(field, dp(12f).toFloat())
        test.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        val tlp = rowParams()
        tlp.topMargin = dp(6f)
        tlp.bottomMargin = dp(10f)
        box.addView(test, tlp)

        // Podobrazovky nastaveni
        box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(26f) }
        navRow(box, getString(R.string.appearance_open)) { startActivity(Intent(this, AppearanceActivity::class.java)) }
        navRow(box, getString(R.string.macros_open)) { startActivity(Intent(this, MacrosActivity::class.java)) }
        navRow(box, getString(R.string.dictionary_open)) { startActivity(Intent(this, DictionaryActivity::class.java)) }
        navRow(box, getString(R.string.voice_open)) { startActivity(Intent(this, VoiceActivity::class.java)) }
        navRow(box, getString(R.string.typing_open)) { startActivity(Intent(this, TypingActivity::class.java)) }
        navRow(box, getString(R.string.sec_about)) { startActivity(Intent(this, AboutActivity::class.java)) }

        // Schranka
        box = section(container, getString(R.string.sec_clipboard))
        switchRow(box, R.string.clipboard_enabled, "clipboard_enabled", R.string.clipboard_help, 5000)
        sliderRow(box, R.string.clipboard_ttl, "clipboard_ttl_minutes", 5, 240, 1) { getString(R.string.unit_minutes, it) }
        buttonRow(box, getString(R.string.clipboard_clear), danger) {
            if (Lantern.gleam(this)) {
                confirmDialog(getString(R.string.downgrade_done), getString(R.string.notice_ok), null, false, 0xFF22C55E.toInt()) {
                    startActivity(Intent(this, AppearanceActivity::class.java).putExtra(Lantern.MOTH, true))
                }
            } else {
                confirmDanger(getString(R.string.clipboard_clear_confirm), getString(R.string.delete)) {
                    RustBridge.clipClear()
                    Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Zaloha a ladeni
        box = section(container, getString(R.string.sec_backup))
        buttonRow(box, getString(R.string.backup_export), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "fazt-zaloha.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP_EXPORT)
        }
        buttonRow(box, getString(R.string.backup_import), accent) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP_IMPORT)
        }
        hintText(box, getString(R.string.backup_hint))
        switchRow(box, R.string.swipe_log, "swipe_log", R.string.swipe_log_hint, 10000)
        swipeLogButton = buttonRow(box, getString(R.string.swipe_log_export, RustBridge.swipeLogCount()), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "fazt-tahy.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_SWIPE_LOG_EXPORT)
        }
        buttonRow(box, getString(R.string.swipe_log_clear), danger) {
            confirmDanger(getString(R.string.swipe_log_clear_confirm), getString(R.string.delete)) {
                RustBridge.clearSwipeLog()
                updateSwipeLogButton()
                Toast.makeText(this, R.string.swipe_log_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        updateStatus()
    }

    /** Systemove zpet na hlavni obrazovce se nejdriv zepta (bublina se zavira driv, v BaseScreen). */
    override fun onBackRequested(): Boolean {
        confirmDialog(getString(R.string.leave_settings_confirm), getString(R.string.leave), getString(R.string.stay), true) { finish() }
        return true
    }

    override fun onSettingUpdated(key: String, value: Any) {
        if (key == "clipboard_enabled") Lantern.flicker(this)
    }

    private fun updateSwipeLogButton() {
        swipeLogButton?.text = getString(R.string.swipe_log_export, RustBridge.swipeLogCount())
    }

    @Suppress("DEPRECATION") // bez AndroidX neni jina cesta k vysledku aktivity
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_BACKUP_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportBackup().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                }
                REQ_BACKUP_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    confirmRestore(json)
                }
                REQ_SWIPE_LOG_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportSwipeLog().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.swipe_log_exported, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Throwable) {
            val msg = if (requestCode == REQ_BACKUP_IMPORT) R.string.backup_restore_failed else R.string.backup_export_failed
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Obnova prepise nastaveni, proto se pred ni zeptame. Po uspechu se obrazovka postavi znovu. */
    private fun confirmRestore(json: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                val n = RustBridge.importBackup(json)
                if (n >= 0) {
                    Toast.makeText(this, getString(R.string.backup_restored, n), Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, R.string.backup_restore_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = try {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
        val current = SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val active = current.startsWith("$packageName/")
        statusView?.setText(
            when {
                active -> R.string.setup_status_active
                enabled -> R.string.setup_status_enabled
                else -> R.string.setup_status_disabled
            }
        )
        statusView?.setTextColor(if (active) 0xFF10B981.toInt() else dim)
    }
}
