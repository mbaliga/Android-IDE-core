package dev.fonebrew.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One screen: Run, Copy, and the plain-text report. Nothing leaves the device unless the owner pastes it. */
class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var run: Button
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        run = Button(this).apply { text = "Run"; setOnClickListener { start() } }
        val copy = Button(this).apply { text = "Copy"; setOnClickListener { copyReport() } }
        row.addView(run); row.addView(copy)
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
            text = "Userland probe ${ReportFormatter.FORMAT}\nNo network. Tap Run (about 15 seconds; press Home right after tapping to test background survival)." }
        col.addView(row)
        col.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        if (BuildConfig.STEP_TWO_ENABLED) {
            col.addView(Button(this).apply { text = "Step two (owner-enabled build)"
                setOnClickListener { startActivity(Intent(this@MainActivity, dev.fonebrew.probe.steptwo.StepTwoActivity::class.java)) } }, 1)
        }
    }

    private fun start() {
        run.isEnabled = false
        out.text = "Running... (about 15 seconds)"
        Thread {
            val c = Checks(applicationContext)
            val text = try { ReportFormatter.format(c.header(), c.all()) } catch (t: Throwable) { "probe crashed outside any check: $t" }
            runOnUiThread { report = text; out.text = text; run.isEnabled = true }
        }.start()
    }

    private fun copyReport() {
        if (report.isEmpty()) { Toast.makeText(this, "Run first", Toast.LENGTH_SHORT).show(); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("userland-probe report", report))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }
}
