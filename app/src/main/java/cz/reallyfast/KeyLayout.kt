package cz.reallyfast

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Rozlozeni klaves: znak na kazde klavese lze zmenit nebo prohodit s jinou.
 * Dole je pripnuty zivy nahled; klepnuti na klavesu v nahledu otevre volbu znaku.
 * Zmena se ulozi do nastaveni (`key_map`) a Rust ji pouzije v rozlozeni i v swipe decoderu.
 */
class LayoutActivity : BaseScreen() {

    private lateinit var container: LinearLayout
    private lateinit var preview: KeyboardView
    private lateinit var previewBox: LinearLayout
    private var statusView: TextView? = null
    private var cancelSwapView: TextView? = null
    private val pills = ArrayList<TextView>()
    private val hintPills = ArrayList<TextView>()
    private var shownMode = KeyboardView.MODE_LETTERS

    /** Cekajici prohozeni: puvodni kod pozice prvni klavesy a znak, ktery na ni ted je. */
    private var pendingSrc = -1
    private var pendingChar = ""
    private val listener: () -> Unit = {
        updatePreview()
        updateStatus()
        paintPills()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RustBridge.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.fitsSystemWindows = true

        val scroll = ScrollView(this)
        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16f), dp(20f), dp(16f), dp(28f))
        scroll.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Zivy nahled pripnuty dole; klepnuti na klavesu vybira, co se bude menit.
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val kv = KeyboardView(this)
        kv.preview = true
        kv.onKeyPick = { onPick(it) }
        box.addView(kv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        preview = kv
        previewBox = box
        updatePreview()

        setContentView(root)
        build()
        RustBridge.listeners.add(listener)
    }

    override fun onDestroy() {
        RustBridge.listeners.remove(listener)
        super.onDestroy()
    }

    private fun build() {
        title(container, getString(R.string.layout_open), back = true)
        container.addView(
            label(getString(R.string.layout_hint), 14f, dim).also { it.setPadding(dp(4f), dp(10f), dp(4f), 0) },
            rowParams(),
        )
        val box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }

        // Rezim nahledu: pismena / symboly / dalsi symboly
        val modes = listOf(
            KeyboardView.MODE_LETTERS to R.string.layout_mode_letters,
            KeyboardView.MODE_SYMBOLS to R.string.layout_mode_symbols,
            KeyboardView.MODE_SYMBOLS2 to R.string.layout_mode_symbols2,
        )
        val row = LinearLayout(this)
        for ((i, m) in modes.withIndex()) {
            val p = label(getString(m.second), 14f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            p.setOnClickListener {
                shownMode = m.first
                preview.setMode(m.first)
                paintPills()
            }
            pills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            row.addView(p, lp)
        }
        val rlp = rowParams()
        rlp.topMargin = dp(10f)
        rlp.bottomMargin = dp(6f)
        box.addView(row, rlp)
        paintPills()

        // Poloha male napovedy: dva radky po trech.
        box.addView(
            label(getString(R.string.layout_hint_pos), 14f, dim).also { it.setPadding(0, dp(8f), 0, dp(2f)) },
            rowParams(),
        )
        for (r in 0 until 2) {
            val hr = LinearLayout(this)
            for (c in 0 until 3) {
                val idx = r * 3 + c
                val p = label(getString(HINT_LABELS[idx]), 14f)
                p.gravity = Gravity.CENTER
                p.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
                p.setOnClickListener { setHintPos(HINT_POS[idx]) }
                hintPills.add(p)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (c > 0) lp.leftMargin = dp(6f)
                hr.addView(p, lp)
            }
            val hlp = rowParams()
            hlp.topMargin = dp(6f)
            box.addView(hr, hlp)
        }
        paintPills()

        statusView = label("", 14f, dim).also {
            it.setPadding(0, dp(10f), 0, dp(6f))
            box.addView(it, rowParams())
        }
        cancelSwapView = buttonRow(box, getString(R.string.layout_swap_cancel), accent) { cancelSwap() }
        cancelSwapView?.visibility = android.view.View.GONE
        buttonRow(box, getString(R.string.layout_reset), danger) {
            AlertDialog.Builder(this)
                .setMessage(R.string.layout_reset_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    cancelSwap()
                    writeMaps(LinkedHashMap(), LinkedHashMap())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        updateStatus()
    }

    private fun paintPills() {
        val a = accent
        val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
        val order = listOf(KeyboardView.MODE_LETTERS, KeyboardView.MODE_SYMBOLS, KeyboardView.MODE_SYMBOLS2)
        for ((i, p) in pills.withIndex()) {
            val on = order[i] == shownMode
            p.background = rounded(if (on) a else field, dp(12f).toFloat())
            p.setTextColor(if (on) onA else text)
        }
        val cur = RustBridge.settings.keyHintPos
        for ((i, p) in hintPills.withIndex()) {
            val on = HINT_POS[i] == cur
            p.background = rounded(if (on) a else field, dp(12f).toFloat())
            p.setTextColor(if (on) onA else text)
        }
    }

    private fun setHintPos(pos: String) {
        val j = RustBridge.settings.edit()
        j.put("key_hint_pos", pos)
        RustBridge.setSettings(j)
    }

    /** Nahled ukazuje presne to, co klavesnice (tema, vyska, premapovane znaky). */
    private fun updatePreview() {
        val s = RustBridge.settings
        val theme = Theme.from(s)
        preview.theme = theme
        val real = keyboardKeysHeight(resources, s, dp(44f))
        preview.desiredHeight = minOf(real, (resources.displayMetrics.heightPixels * 0.5f).toInt())
        preview.reloadLayout()
        previewBox.setBackgroundColor(theme.background)
    }

    private fun updateStatus() {
        val swapping = pendingSrc >= 0
        statusView?.text = if (swapping) {
            getString(R.string.layout_status_swap, pendingChar)
        } else {
            val n = (readMap().keys + readAlts().keys).size
            if (n == 0) getString(R.string.layout_status_none) else getString(R.string.layout_status_changed, n)
        }
        cancelSwapView?.visibility = if (swapping) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun cancelSwap() {
        pendingSrc = -1
        pendingChar = ""
        preview.markedSrc = Int.MIN_VALUE
        updateStatus()
    }

    // ------------------------------------------------------------------ mapa klaves

    private fun readMap(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val o = RustBridge.settings.json.optJSONObject("key_map") ?: return out
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    private fun readAlts(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val o = RustBridge.settings.json.optJSONObject("key_alts") ?: return out
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }

    /** Obe mapy se zapisuji jednim volanim, aby se rozlozeni prepocitalo jen jednou. */
    private fun writeMaps(map: Map<String, String>, alts: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in map) if (k != v) obj.put(k, v)
        val altsObj = JSONObject()
        for ((k, v) in alts) altsObj.put(k, v)
        val j = RustBridge.settings.edit()
        j.put("key_map", obj)
        j.put("key_alts", altsObj)
        RustBridge.setSettings(j)
    }

    private fun srcString(key: KeyboardView.Key): String = String(Character.toChars(key.src))

    // ------------------------------------------------------------------ vyber klavesy

    private fun onPick(key: KeyboardView.Key) {
        if (key.kind != KeyboardView.KIND_CHAR) {
            Toast.makeText(this, R.string.layout_not_editable, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingSrc >= 0) {
            if (key.src != pendingSrc) {
                val map = readMap()
                map[String(Character.toChars(pendingSrc))] = key.label
                map[srcString(key)] = pendingChar
                cancelSwap()
                writeMaps(map, readAlts())
            } else {
                cancelSwap() // klepnuti na tu samou klavesu = zrusit
            }
            return
        }
        askChar(key)
    }

    private fun askChar(key: KeyboardView.Key) {
        val et = EditText(this)
        et.setTextColor(text)
        et.setHintTextColor(dim)
        et.hint = getString(R.string.layout_char_hint)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.setSingleLine(true)
        et.setText(key.label)
        et.selectAll()
        val alts = EditText(this)
        alts.setTextColor(text)
        alts.setHintTextColor(dim)
        alts.hint = getString(R.string.layout_alts_hint)
        alts.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        alts.setSingleLine(true)
        alts.setText(key.alts)
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(20f), dp(12f), dp(20f), 0)
        form.addView(et, rowParams())
        form.addView(
            label(getString(R.string.layout_alts_label), 13f, dim).also { it.setPadding(0, dp(12f), 0, 0) },
            rowParams(),
        )
        form.addView(alts, rowParams())
        AlertDialog.Builder(this)
            .setTitle(key.label)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ -> saveChar(key, et.text.toString().trim(), alts.text.toString(), key.alts) }
            .setNeutralButton(R.string.layout_swap) { _, _ ->
                pendingSrc = key.src
                pendingChar = key.label
                preview.markedSrc = key.src
                updateStatus()
            }
            .setNegativeButton(R.string.layout_original) { _, _ ->
                val map = readMap()
                val altsMap = readAlts()
                map.remove(srcString(key))
                altsMap.remove(srcString(key))
                writeMaps(map, altsMap)
            }
            .show()
    }

    private fun saveChar(key: KeyboardView.Key, typed: String, typedAlts: String, oldAlts: String) {
        // Podporuje se jen jeden znak z zakladni roviny (jedna Rust `char` = jeden UTF-16 znak).
        val cp = if (typed.isEmpty()) -1 else typed.codePointAt(0)
        if (cp < 0 || cp > 0xFFFF || Character.isWhitespace(cp) || Character.isISOControl(cp)) {
            Toast.makeText(this, R.string.layout_char_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val map = readMap()
        map[srcString(key)] = String(Character.toChars(cp))
        val altsMap = readAlts()
        val cleanAlts = typedAlts.filter { !it.isWhitespace() && !Character.isISOControl(it) }
        if (cleanAlts != oldAlts) altsMap[srcString(key)] = cleanAlts
        writeMaps(map, altsMap)
    }

    private companion object {
        val HINT_POS = listOf("top_left", "top_center", "top_right", "bottom_left", "bottom_center", "bottom_right")
        val HINT_LABELS = listOf(
            R.string.hint_top_left, R.string.hint_top_center, R.string.hint_top_right,
            R.string.hint_bottom_left, R.string.hint_bottom_center, R.string.hint_bottom_right,
        )
    }
}
