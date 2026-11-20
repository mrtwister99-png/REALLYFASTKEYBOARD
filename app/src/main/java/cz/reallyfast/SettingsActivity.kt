package cz.reallyfast

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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

        /** Po doleteni se logo1 a logo2 stridaji nad zakladem po 1 s; po 10 prepnutich (10 s) zustane vzdy videt logo1. */
        const val LOGO_FLIP_MS = 1000L
        const val LOGO_FLIPS = 10

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
    private var statusCard: LinearLayout? = null
    private var statusBadge: TextView? = null
    private var swipeLogButton: TextView? = null
    private var logoTop: ImageView? = null
    private var logoAlt: ImageView? = null
    private var logoSlide: ValueAnimator? = null
    private var logoFlips = 0
    private val logoFlip = object : Runnable {
        override fun run() {
            val a = logoTop ?: return
            val b = logoAlt ?: return
            logoFlips++
            val second = logoFlips % 2 == 1
            a.visibility = if (second) View.INVISIBLE else View.VISIBLE
            b.visibility = if (second) View.VISIBLE else View.INVISIBLE
            if (logoFlips < LOGO_FLIPS) a.postDelayed(this, LOGO_FLIP_MS)
        }
    }

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
        // Po navratu na obrazovku se stridani loga znovu rozbehne (pokud uz neprobiha vjezd).
        if (logoSlide == null) logoTop?.let {
            logoFlips = 0
            it.removeCallbacks(logoFlip)
            it.postDelayed(logoFlip, LOGO_FLIP_MS)
        }
        updateStatus()
        updateSwipeLogButton()
    }

    override fun onPause() {
        stopLogo()
        super.onPause()
    }

    override fun onDestroy() {
        stopLogo()
        logoTop = null
        logoAlt = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    // ------------------------------------------------------------------ obsah

    private fun build(container: LinearLayout) {
        // Nadpis: zaklad stoji porad; logo1 a logo2 (stejne platno 1700x650) lezi presne nad nim ve stejnem
        // FrameLayoutu (stejna velikost, FIT_XY, stejne zmenseni pri dekodovani).
        val logoW = dp(220f)
        val sample = logoSample(R.drawable.zaklad, logoW)
        val base = logoBitmap(R.drawable.zaklad, sample)
        val logoH = if (base != null && base.width > 0) logoW * base.height / base.width else logoW * 650 / 1700
        val frame = FrameLayout(this)
        frame.contentDescription = getString(R.string.app_name)
        fun layer(bmp: Bitmap?): ImageView {
            val v = ImageView(this)
            v.scaleType = ImageView.ScaleType.FIT_XY
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (bmp != null) v.setImageBitmap(bmp)
            frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return v
        }
        layer(base)
        val top = layer(logoBitmap(R.drawable.logo1, sample))
        val alt = layer(logoBitmap(R.drawable.logo2, sample))
        alt.visibility = View.INVISIBLE
        logoTop = top
        logoAlt = alt
        val hlp = LinearLayout.LayoutParams(logoW, logoH)
        hlp.bottomMargin = dp(4f)
        container.addView(frame, hlp)
        if (animateTitle) {
            // logo1 a logo2 vyjedou zprava za okrajem obrazovky a dolete nad zaklad; pak se stridaji (jen pri vytvoreni obrazovky).
            animateTitle = false
            val from = resources.displayMetrics.widthPixels.toFloat()
            top.translationX = from
            alt.translationX = from
            val a = ValueAnimator.ofFloat(from, 0f)
            a.duration = TITLE_MS
            a.interpolator = TITLE_SLIDE
            a.startDelay = 60L
            a.addUpdateListener {
                val x = it.animatedValue as Float
                top.translationX = x
                alt.translationX = x
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (logoSlide !== animation) return
                    logoSlide = null
                    logoFlips = 0
                    top.postDelayed(logoFlip, LOGO_FLIP_MS)
                }
            })
            logoSlide = a
            a.start()
        }
        container.addView(label(getString(R.string.settings_subtitle), 14f, dim))

        if (!RustBridge.isLoaded) {
            val warn = label(getString(R.string.core_missing), 14f, danger)
            warn.setPadding(0, dp(12f), 0, 0)
            container.addView(warn)
        }

        // Aktivace
        var box = section(container, getString(R.string.sec_setup))
        // Stav klavesnice: velke barevne policko se symbolem (zelena = aktivni, oranzova = zapnuta, cervena = vypnuta).
        val card = LinearLayout(this)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        val badge = TextView(this)
        badge.gravity = Gravity.CENTER
        badge.setTextColor(0xFFFFFFFF.toInt())
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        badge.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(badge, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        val st = label("", 17f, text)
        st.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        st.setPadding(dp(14f), 0, 0, 0)
        card.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clp = rowParams()
        clp.topMargin = dp(12f)
        clp.bottomMargin = dp(8f)
        box.addView(card, clp)
        statusCard = card
        statusBadge = badge
        statusView = st
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
        navRow(box, getString(R.string.macros_open), R.drawable.maskot) { startActivity(Intent(this, MacrosActivity::class.java)) }
        navRow(box, getString(R.string.dictionary_open)) { startActivity(Intent(this, DictionaryActivity::class.java)) }
        navRow(box, getString(R.string.voice_open)) { startActivity(Intent(this, VoiceActivity::class.java)) }
        navRow(box, getString(R.string.typing_open)) { startActivity(Intent(this, TypingActivity::class.java)) }
        navRow(box, getString(R.string.sec_about)) { startActivity(Intent(this, AboutActivity::class.java)) }

        // Schranka
        box = section(container, getString(R.string.sec_clipboard))
        switchRow(box, R.string.clipboard_enabled, "clipboard_enabled", R.string.clipboard_help, 5000)
        sliderRow(box, R.string.clipboard_ttl, "clipboard_ttl_minutes", 5, 240, 1) { getString(R.string.unit_minutes, it) }
        buttonRow(box, getString(R.string.clipboard_clear), danger) {
            confirmDanger(getString(R.string.clipboard_clear_confirm), getString(R.string.delete)) {
                RustBridge.clipClear()
                Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
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

    /** Zmenseni pri dekodovani (mocnina 2), aby obrazek nebyl zbytecne vetsi nez `targetW` px; stejne pro vsechny vrstvy. */
    private fun logoSample(id: Int, targetW: Int): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, id, o)
        var sample = 1
        while (o.outWidth > 0 && o.outWidth / (sample * 2) >= targetW) sample *= 2
        return sample
    }

    private fun logoBitmap(id: Int, sample: Int): Bitmap? = try {
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    /** Konec animace nadpisu bez uniku: animator i odlozene prepnuti pryc, logo1 zustane na miste nad zakladem. */
    private fun stopLogo() {
        val a = logoSlide
        logoSlide = null
        a?.removeAllListeners()
        a?.removeAllUpdateListeners()
        a?.cancel()
        logoTop?.removeCallbacks(logoFlip)
        logoTop?.let {
            it.translationX = 0f
            it.visibility = View.VISIBLE
        }
        logoAlt?.let {
            it.translationX = 0f
            it.visibility = View.INVISIBLE
        }
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
        // Barva a symbol podle stavu; policko ma pruhledne pozadi v barve stavu a obrys.
        val tone = when {
            active -> 0xFF10B981.toInt()
            enabled -> 0xFFF59E0B.toInt()
            else -> 0xFFEF4444.toInt()
        }
        statusBadge?.text = if (active) "✓" else if (enabled) "!" else "✕"
        statusBadge?.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(tone)
        }
        statusCard?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor((tone and 0x00FFFFFF) or 0x26000000)
            setStroke(dp(2f), tone)
        }
    }
}
