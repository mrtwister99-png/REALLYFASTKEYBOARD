package cz.reallyfast

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast

/** Zaloha a obnoveni: export a import nastaveni klavesnice do souboru, ktery si uzivatel sam ulozi, kam chce. */
class BackupActivity : SubScreen() {
    private companion object {
        const val REQ_EXPORT = 10
        const val REQ_IMPORT = 11
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(R.string.backup_open)
        val box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        buttonRow(box, getString(R.string.backup_export), accent) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "fazt-zaloha.json")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_EXPORT)
        }
        buttonRow(box, getString(R.string.backup_import), accent) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT)
        }
        val info = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f) }
        for (id in intArrayOf(R.string.backup_how, R.string.backup_privacy)) {
            val p = label(getString(id), 14f)
            p.setLineSpacing(0f, 1.15f)
            p.setPadding(0, dp(10f), 0, dp(10f))
            info.addView(p, rowParams())
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
                        it.write(RustBridge.exportBackup().toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    confirmRestore(json)
                }
            }
        } catch (_: Throwable) {
            val msg = if (requestCode == REQ_IMPORT) R.string.backup_restore_failed else R.string.backup_export_failed
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** Obnova prepise nastaveni, proto se pred ni zeptame. */
    private fun confirmRestore(json: String) {
        AlertDialog.Builder(this)
            .setMessage(R.string.backup_restore_confirm)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                val n = RustBridge.importBackup(json)
                if (n >= 0) {
                    Toast.makeText(this, getString(R.string.backup_restored, n), Toast.LENGTH_SHORT).show()
                    askPendingStyles()
                } else {
                    Toast.makeText(this, R.string.backup_restore_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Po obnove se vrati uvodni obrazovka postavena znovu (aby ukazovala obnovene nastaveni). */
    private fun finishRestore() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /**
     * Styly ze zalohy, pro ktere nezbylo misto (vsech 5 slotu je obsazeno): zepta se, ktery slot smazat a nahradit.
     * Kazdy cekajici styl se vyridi zvlast.
     */
    private fun askPendingStyles() {
        if (RustBridge.pendingStyleCount() == 0) {
            finishRestore()
            return
        }
        var picked = 0
        val names = Array(5) { getString(R.string.style_slot_n, it + 1) }
        AlertDialog.Builder(this)
            .setMessage(R.string.style_import_full)
            .setSingleChoiceItems(names, 0) { _, which -> picked = which }
            .setPositiveButton(R.string.style_import_replace) { _, _ ->
                RustBridge.placePendingStyle(picked)
                askPendingStyles()
            }
            .setNegativeButton(R.string.style_import_skip) { _, _ ->
                RustBridge.discardPendingStyles()
                finishRestore()
            }
            .setOnCancelListener {
                RustBridge.discardPendingStyles()
                finishRestore()
            }
            .show()
    }
}
