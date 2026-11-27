package cz.reallyfast

import android.app.AlertDialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.animation.ValueAnimator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Diktovani: zapnuti, napoveda k ovladani a vlastni hlasove povely
 * (slovo nebo fraze, ktera pri diktovani misto zapsani neco udela).
 */
class VoiceActivity : SubScreen() {
    private companion object {
        const val REQ_MIC = 3
        val ACTIONS = listOf("delete_word", "delete_last", "newline", "enter", "stop", "text")
        val ACTION_LABELS = listOf(
            R.string.voice_action_delete_word,
            R.string.voice_action_delete_last,
            R.string.voice_action_newline,
            R.string.voice_action_enter,
            R.string.voice_action_stop,
            R.string.voice_action_text,
        )
    }

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(R.string.voice_open)

        // Zapnuti diktovani + napoveda
        val box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(52f)
        row.addView(label(getString(R.string.voice_input)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = ToggleView(this)
        sw.accent = accent
        sw.contentDescription = getString(R.string.voice_input)
        sw.setChecked(RustBridge.settings.voiceInput, false)
        sw.onChange = { checked ->
            if (RustBridge.settings.voiceInput != checked) {
                val j = RustBridge.settings.edit()
                j.put("voice_input", checked)
                RustBridge.setSettings(j)
                if (checked) ensureMicrophone()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        box.addView(row, rowParams())

        // Jak se diktovani spousti: podrzenim Enteru, tlacitkem v liste vedle schranky, nebo obojim.
        box.addView(label(getString(R.string.voice_trigger_label), 14f, dim).also { it.setPadding(0, dp(6f), 0, dp(6f)) }, rowParams())
        val triggerValues = listOf("enter", "button", "both")
        val triggerLabels = listOf(R.string.voice_trigger_enter, R.string.voice_trigger_button, R.string.voice_trigger_both)
        val triggerPills = ArrayList<TextView>()
        val triggerRow = LinearLayout(this)
        fun paintTrigger() {
            val cur = RustBridge.settings.voiceTrigger
            val a = accent
            val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
            for ((i, p) in triggerPills.withIndex()) {
                val on = triggerValues[i] == cur
                p.background = rounded(if (on) a else field, dp(12f).toFloat())
                p.setTextColor(if (on) onA else text)
            }
        }
        for ((i, v) in triggerValues.withIndex()) {
            val p = label(getString(triggerLabels[i]), 13f)
            p.gravity = Gravity.CENTER
            p.setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
            p.setOnClickListener {
                val j = RustBridge.settings.edit()
                j.put("voice_trigger", v)
                RustBridge.setSettings(j)
                paintTrigger()
            }
            triggerPills.add(p)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6f)
            triggerRow.addView(p, lp)
        }
        paintTrigger()
        val tlp = rowParams()
        tlp.bottomMargin = dp(8f)
        box.addView(triggerRow, tlp)
        hintText(box, getString(R.string.voice_hint))

        // Hlasove povely
        val commands = section(container, getString(R.string.sec_voice_commands))
        hintText(commands, getString(R.string.voice_commands_hint)).setPadding(0, dp(10f), 0, dp(10f))
        addNewCommandButton(commands)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        container.addView(list, rowParams())
        refresh()
    }

    /**
     * Tlacitko "+ Novy povel": po klepnuti se krizek protoci o 446 stupnu a zvetsi na 300 %, zelene par krat zapulzuje
     * a vyleti zlute jiskry; potom se vrati do zakladu a otevre se editor povelu.
     */
    private fun addNewCommandButton(commands: LinearLayout) {
        commands.clipChildren = false
        val holder = FrameLayout(this)
        holder.clipChildren = false
        val btn = LinearLayout(this)
        btn.clipChildren = false
        btn.gravity = Gravity.CENTER_VERTICAL
        btn.minimumHeight = dp(46f)
        btn.setPadding(dp(16f), 0, dp(16f), 0)
        btn.background = rounded(field, dp(12f).toFloat())
        val plus = IconView(this, IconView.PLUS)
        plus.color = accent
        btn.addView(plus, LinearLayout.LayoutParams(dp(26f), dp(26f)))
        val txt = label(getString(R.string.voice_command_new), 15f, accent)
        txt.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        txt.setPadding(dp(14f), 0, 0, 0)
        btn.addView(txt)
        holder.addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val burst = BurstView(this)
        val blp = FrameLayout.LayoutParams(dp(120f), dp(120f), Gravity.START or Gravity.CENTER_VERTICAL)
        blp.leftMargin = dp(16f) + dp(13f) - dp(60f)
        holder.addView(burst, blp)
        val lp = rowParams()
        lp.topMargin = dp(5f)
        lp.bottomMargin = dp(5f)
        commands.addView(holder, lp)
        btn.setOnClickListener {
            if (!btn.isEnabled) return@setOnClickListener
            btn.isEnabled = false
            val base = accent
            val green = 0xFF22C55E.toInt()
            val yellow = 0xFFFFD60A.toInt()
            // 1. Krizek se protoci o 446 stupnu a zvetsi na 300 %.
            plus.animate().rotation(446f).scaleX(3f).scaleY(3f).setDuration(650L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction {
                    // 2. Zelene zapulzuje nekolikrat a vyleti zlute jiskry.
                    burst.burst(yellow, 14, 62f, 800L)
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 840L
                        addUpdateListener {
                            val f = it.animatedFraction * 3f
                            val k = kotlin.math.sin((f - f.toInt()) * Math.PI).toFloat()
                            plus.color = Theme.blend(base, green, k)
                            plus.scaleX = 3f + 0.35f * k
                            plus.scaleY = 3f + 0.35f * k
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                // 3. Zpet do zakladu a otevre se editor.
                                plus.color = base
                                plus.animate().rotation(450f).scaleX(1f).scaleY(1f).setDuration(260L).withEndAction {
                                    plus.rotation = 0f
                                    btn.isEnabled = true
                                    edit(null)
                                }.start()
                            }
                        })
                        start()
                    }
                }.start()
        }
    }

    private fun ensureMicrophone() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    private fun actionLabel(action: String): String {
        val i = ACTIONS.indexOf(action)
        return if (i >= 0) getString(ACTION_LABELS[i]) else action
    }

    private fun refresh() {
        list.removeAllViews()
        val commands = RustBridge.voiceCommands()
        if (commands.isEmpty()) {
            list.addView(label(getString(R.string.voice_commands_empty), 14f, dim).also { it.setPadding(dp(6f), dp(18f), 0, 0) }, rowParams())
            return
        }
        for (c in commands) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.background = rounded(card, dp(16f).toFloat())
            item.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            item.addView(label(c.phrase, 16f), rowParams())
            val detail = if (c.action == "text") actionLabel(c.action) + ": " + c.text else actionLabel(c.action)
            val body = label(detail, 14f, dim)
            body.maxLines = 2
            body.ellipsize = TextUtils.TruncateAt.END
            body.setPadding(0, dp(4f), 0, 0)
            item.addView(body, rowParams())
            item.setOnClickListener { edit(c) }
            val lp = rowParams()
            lp.topMargin = dp(10f)
            list.addView(item, lp)
        }
    }

    private fun edit(existing: RustBridge.VoiceCommand?) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(20f), dp(12f), dp(20f), 0)
        val phrase = input(getString(R.string.voice_command_phrase))
        val body = input(getString(R.string.voice_command_text))
        var action = existing?.action ?: ACTIONS[0]
        existing?.let {
            phrase.setText(it.phrase)
            body.setText(it.text)
        }
        form.addView(phrase, rowParams())

        // Vyber akce: svisly seznam, vybrana polozka v accent barve (bez Spinneru).
        val pills = ArrayList<TextView>()
        fun paint() {
            val a = accent
            val onA = if (Theme.luminance(a) > 0.55f) 0xFF111113.toInt() else 0xFFFFFFFF.toInt()
            for ((i, p) in pills.withIndex()) {
                val on = ACTIONS[i] == action
                p.background = rounded(if (on) a else field, dp(12f).toFloat())
                p.setTextColor(if (on) onA else text)
            }
            body.visibility = if (action == "text") View.VISIBLE else View.GONE
        }
        for (i in ACTIONS.indices) {
            val p = label(getString(ACTION_LABELS[i]), 15f)
            p.setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            p.setOnClickListener {
                action = ACTIONS[i]
                paint()
            }
            pills.add(p)
            val lp = rowParams()
            lp.topMargin = dp(6f)
            form.addView(p, lp)
        }
        val blp = rowParams()
        blp.topMargin = dp(10f)
        form.addView(body, blp)
        paint()

        val scroll = ScrollView(this)
        scroll.addView(form)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.voice_command_new else R.string.voice_command_edit)
            .setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                val ok = RustBridge.saveVoiceCommand(
                    existing?.id ?: 0, phrase.text.toString(), action, body.text.toString(),
                )
                if (!ok) Toast.makeText(this, R.string.voice_command_save_failed, Toast.LENGTH_LONG).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) {
            dialog.setNeutralButton(R.string.delete) { _, _ ->
                RustBridge.deleteVoiceCommand(existing.id)
                refresh()
            }
        }
        dialog.show()
    }
}
