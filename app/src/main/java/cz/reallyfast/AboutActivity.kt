package cz.reallyfast

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout

/** O aplikaci: nazev, strucny popis, soukromi a verze jadra. */
class AboutActivity : SubScreen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp(R.string.sec_about)

        val name = label(getString(R.string.about_title), 34f, accent)
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        name.setPadding(dp(6f), dp(18f), 0, 0)
        container.addView(name, rowParams())
        val tag = label(getString(R.string.about_tagline), 14f, dim)
        tag.setPadding(dp(6f), 0, 0, 0)
        container.addView(tag, rowParams())

        val box = cardBox(container).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(22f) }
        for (id in intArrayOf(R.string.about_p1, R.string.about_p2, R.string.about_p3, R.string.about_p4)) {
            val p = label(getString(id), 15f)
            p.setLineSpacing(0f, 1.15f)
            p.setPadding(0, dp(10f), 0, dp(6f))
            box.addView(p, rowParams())
        }
        val privacy = label(getString(R.string.about_privacy), 14f)
        privacy.setPadding(0, dp(10f), 0, dp(10f))
        box.addView(privacy, rowParams())

        val info = label(RustBridge.info(), 12f, dim)
        info.setPadding(dp(6f), dp(16f), dp(6f), 0)
        container.addView(info, rowParams())
    }
}
