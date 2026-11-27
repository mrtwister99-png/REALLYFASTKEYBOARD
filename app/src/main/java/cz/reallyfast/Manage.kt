package cz.reallyfast

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Zaklad podobrazovky: nadpis se sipkou zpet + rolovaci obsah. */
abstract class SubScreen : BaseScreen() {
    protected lateinit var container: LinearLayout

    protected fun setUp(titleRes: Int) {
        RustBridge.init(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.fitsSystemWindows = true
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16f), dp(20f), dp(16f), dp(40f))
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        title(container, getString(titleRes))
    }

    protected fun input(hint: String, multiLine: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(dim)
        setTextColor(this@SubScreen.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(field, dp(12f).toFloat())
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        inputType = if (multiLine) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (multiLine) minLines = 3
    }
}

/**
 * Osobni slovnik: prehled naucenych slov, rucni pridani, hledani, mazani, export / import.
 */
class DictionaryActivity : SubScreen() {
    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
        const val MAX_SHOWN = 300
    }

    private lateinit var list: LinearLayout
    private lateinit var search: EditText

    /** Zabrneni po pridani slova (stejny vzor jako pri pridani slova tlacitkem + v liste). */
    private fun vibrateSaved() {
        try {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return
            val pattern = longArrayOf(0, 70, 90, 120)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (_: Throwable) {
            // bez vibrace
        }
    }

    private var adding = false

    /**
     * Pismena tlacitka Pridat po pridani slova odletí kazde jinam (nahoru na okraj, dolu za obrazovku, doprava) a pak se tlacitko vrati.
     * Pismena letí v preklopne vrstve okna, aby je nic neorezavalo. Smery se opakuji podle poradi pismene.
     */
    private fun playAddedAnimation(add: android.widget.TextView) {
        if (adding) return
        adding = true
        val decor = window.decorView as ViewGroup
        val dloc = IntArray(2)
        decor.getLocationOnScreen(dloc)
        val aloc = IntArray(2)
        add.getLocationOnScreen(aloc)
        val text = add.text.toString()
        val baseX = (aloc[0] - dloc[0] + add.totalPaddingLeft).toFloat()
        val baseY = (aloc[1] - dloc[1] + add.totalPaddingTop).toFloat()
        val dirs = intArrayOf(0, 1, 1, 0, 1, 2) // 0 nahoru, 1 dolu, 2 doprava
        val letters = ArrayList<android.widget.TextView>()
        add.alpha = 0f
        for (i in text.indices) {
            val ch = text.substring(i, i + 1)
            if (ch.isBlank()) continue
            val tv = android.widget.TextView(this)
            tv.text = ch
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, add.textSize)
            tv.typeface = add.typeface
            tv.setTextColor(accent)
            tv.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            val x = (baseX + add.paint.measureText(text, 0, i)).toInt()
            val y = baseY.toInt()
            tv.layout(x, y, x + tv.measuredWidth, y + tv.measuredHeight)
            decor.overlay.add(tv)
            letters.add(tv)
            var dx = 0f
            var dy = 0f
            when (dirs[i % dirs.size]) {
                0 -> dy = -y.toFloat()
                1 -> dy = (decor.height - y + tv.measuredHeight).toFloat()
                else -> dx = (decor.width - x + tv.measuredWidth).toFloat()
            }
            tv.animate().translationX(dx).translationY(dy).setStartDelay(i * 40L).setDuration(560L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
        }
        add.postDelayed({
            for (tv in letters) {
                tv.animate().cancel()
                decor.overlay.remove(tv)
            }
            add.scaleX = 0.6f
            add.scaleY = 0.6f
            add.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                .withEndAction { adding = false }.start()
        }, 900L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(R.string.sec_dictionary)

        // Pridani slova
        var box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        val addRow = LinearLayout(this)
        addRow.gravity = Gravity.CENTER_VERTICAL
        addRow.setPadding(0, dp(8f), 0, dp(8f))
        val word = input(getString(R.string.dictionary_add_hint))
        word.imeOptions = EditorInfo.IME_ACTION_DONE
        addRow.addView(word, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = label(getString(R.string.dictionary_add), 15f, accent)
        add.setPadding(dp(16f), dp(12f), dp(4f), dp(12f))
        val doAdd = {
            val w = word.text.toString().trim()
            if (w.isNotEmpty()) {
                if (RustBridge.addUserWord(w)) {
                    word.setText("")
                    refresh()
                    vibrateSaved()
                    playAddedAnimation(add)
                } else {
                    Toast.makeText(this, R.string.dictionary_add_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        add.setOnClickListener { doAdd() }
        word.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doAdd()
                true
            } else false
        }
        // Tlacitko Pridat v obalu, aby jiskry a zvetseni mohly prekrocit jeho okraj.
        val addHolder = FrameLayout(this)
        addHolder.clipChildren = false
        addHolder.addView(add, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addRow.clipChildren = false
        box.clipChildren = false
        addRow.addView(addHolder)
        box.addView(addRow, rowParams())

        // Seznam
        box = section(container, getString(R.string.sec_dictionary))
        search = input(getString(R.string.dictionary_search))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        val slp = rowParams()
        slp.topMargin = dp(10f)
        box.addView(search, slp)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        box.addView(list, rowParams())

        // Export / import / smazani
        box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        buttonRow(box, getString(R.string.dictionary_export), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "fazt-slovnik.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_EXPORT)
        }
        buttonRow(box, getString(R.string.dictionary_import), accent) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT)
        }
        buttonRow(box, getString(R.string.dictionary_clear), danger) {
            AlertDialog.Builder(this)
                .setMessage(R.string.dictionary_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    RustBridge.clearUserDictionary()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val all = RustBridge.userWords()
        val q = search.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) all else all.filter { it.word.lowercase().contains(q) }
        val head = label(
            if (all.isEmpty()) getString(R.string.dictionary_empty) else getString(R.string.dictionary_count, all.size),
            14f, dim,
        )
        head.setPadding(0, dp(12f), 0, dp(6f))
        list.addView(head, rowParams())
        for (w in shown.take(MAX_SHOWN)) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label(w.word, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("${w.count}×", 13f, dim))
            val del = IconView(this, IconView.CLOSE)
            del.color = dim
            del.contentDescription = getString(R.string.delete)
            del.setOnClickListener {
                RustBridge.deleteUserWord(w.word)
                refresh()
            }
            row.addView(del, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            list.addView(row, rowParams())
        }
        if (shown.size > MAX_SHOWN) {
            list.addView(label(getString(R.string.dictionary_show_more, MAX_SHOWN), 13f, dim).also { it.setPadding(0, dp(6f), 0, dp(10f)) }, rowParams())
        }
    }

    @Suppress("DEPRECATION") // bez AndroidX neni jina cesta k vysledku aktivity
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(RustBridge.exportUserDictionary().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.dictionary_exported, Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val n = RustBridge.importUserDictionary(json)
                    val msg = if (n >= 0) getString(R.string.dictionary_imported, n) else getString(R.string.dictionary_import_failed)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.dictionary_import_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Obrazky makra na vyber (poradi = index v `macro_faces` i v KeyboardView.faceBitmaps). */
object MacroFaces {
    val IMAGES = intArrayOf(R.drawable.trol, R.drawable.chocho, R.drawable.dollo, R.drawable.maskot)

    /** Pocet obrazku; musi sedet s MACRO_FACES v settings.rs. */
    val COUNT = IMAGES.size
}

/**
 * Makra: vlastni textove bloky. Vkladaji se tlacitkem maker (s maskotem) na klavesnici,
 * nebo napsanim zkratky a mezery.
 */
class MacrosActivity : SubScreen() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(R.string.macros)
        container.addView(label(getString(R.string.macros_hint), 14f, dim).also { it.setPadding(dp(4f), dp(10f), dp(4f), 0) }, rowParams())

        val box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        // Nove makro: text probehne vlnou zleva doprava a pak se otevre editor.
        val newMacro = WaveTextView(this, "+  " + getString(R.string.macro_new), accent, 15f)
        newMacro.background = rounded(field, dp(12f).toFloat())
        newMacro.setOnClickListener { newMacro.play { edit(null) } }
        val nlp = rowParams()
        nlp.topMargin = dp(5f)
        nlp.bottomMargin = dp(5f)
        box.addView(newMacro, nlp)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        container.addView(list, rowParams())
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val macros = RustBridge.macros()
        if (macros.isEmpty()) {
            list.addView(label(getString(R.string.macros_empty), 14f, dim).also { it.setPadding(dp(6f), dp(18f), 0, 0) }, rowParams())
            return
        }
        for (m in macros) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.background = rounded(card, dp(16f).toFloat())
            item.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            val head = LinearLayout(this)
            head.gravity = Gravity.CENTER_VERTICAL
            // Obrazek makra pred nazvem (stejny, ktery se ukaze na klavese).
            val face = RustBridge.settings.macroFaces.get(m.id.toInt(), 0).coerceIn(0, MacroFaces.COUNT - 1)
            val img = android.widget.ImageView(this)
            img.setImageResource(MacroFaces.IMAGES[face])
            img.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            head.addView(img, LinearLayout.LayoutParams(dp(34f), dp(34f)).also { it.rightMargin = dp(10f) })
            head.addView(label(m.name, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (m.shortcut.isNotEmpty()) {
                val chip = label(m.shortcut, 13f, accent)
                chip.background = rounded(field, dp(8f).toFloat())
                chip.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                head.addView(chip)
            }
            item.addView(head, rowParams())
            val body = label(m.text, 14f, dim)
            body.maxLines = 2
            body.ellipsize = TextUtils.TruncateAt.END
            body.setPadding(0, dp(4f), 0, 0)
            item.addView(body, rowParams())
            item.setOnClickListener { edit(m) }
            val lp = rowParams()
            lp.topMargin = dp(10f)
            list.addView(item, lp)
        }
    }

    private fun edit(existing: RustBridge.Macro?) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(20f), dp(12f), dp(20f), 0)
        val name = input(getString(R.string.macro_name))
        val body = input(getString(R.string.macro_text), multiLine = true)
        val shortcut = input(getString(R.string.macro_shortcut))
        existing?.let {
            name.setText(it.name)
            body.setText(it.text)
            shortcut.setText(it.shortcut)
        }
        for ((i, v) in listOf(name, body, shortcut).withIndex()) {
            val lp = rowParams()
            if (i > 0) lp.topMargin = dp(10f)
            form.addView(v, lp)
        }
        // Obrazek makra (pod zkratkou): ukaze se na klavese, kam makro prirazime; u noveho makra je predvybrany nahodny.
        form.addView(label(getString(R.string.key_macro_face_title), 13f, dim).also { it.setPadding(0, dp(14f), 0, dp(6f)) }, rowParams())
        var face = if (existing != null) RustBridge.settings.macroFaces.get(existing.id.toInt(), 0).coerceIn(0, MacroFaces.COUNT - 1)
        else kotlin.random.Random.nextInt(MacroFaces.COUNT)
        val faceRow = LinearLayout(this)
        val faceViews = ArrayList<android.widget.ImageView>()
        fun paintFaces() {
            for ((i, v) in faceViews.withIndex()) {
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(0)
                    setStroke(dp(if (i == face) 3f else 1f), if (i == face) accent else 0x33FFFFFF)
                }
            }
        }
        for ((i, res) in MacroFaces.IMAGES.withIndex()) {
            val img = android.widget.ImageView(this)
            img.setImageResource(res)
            img.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            img.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            img.contentDescription = getString(R.string.key_macro_face_n, i + 1)
            img.setOnClickListener {
                face = i
                paintFaces()
            }
            faceViews.add(img)
            val lp = LinearLayout.LayoutParams(0, dp(60f), 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            faceRow.addView(img, lp)
        }
        paintFaces()
        form.addView(faceRow, rowParams())
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.macro_new else R.string.macro_edit)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                // Jadro vrati id ulozeneho makra (0 = neulozeno), obrazek se priradi primo jemu.
                val id = RustBridge.saveMacro(
                    existing?.id ?: 0, name.text.toString(), body.text.toString(), shortcut.text.toString(),
                )
                if (id <= 0) {
                    Toast.makeText(this, R.string.macro_save_failed, Toast.LENGTH_LONG).show()
                } else {
                    RustBridge.setMacroFace(id, face)
                }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            dialog.setNeutralButton(R.string.delete) { _, _ ->
                RustBridge.deleteMacro(existing.id)
                refresh()
            }
        }
        dialog.show()
    }
}
