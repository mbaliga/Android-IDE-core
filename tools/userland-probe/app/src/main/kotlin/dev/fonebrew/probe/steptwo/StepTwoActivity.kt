package dev.fonebrew.probe.steptwo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.fonebrew.probe.BuildConfig
import java.io.File

/**
 * Disabled in the default build (manifest android:enabled="false"; the stepTwo build type enables it).
 * Even when enabled it fetches nothing until the owner taps through an explicit confirmation, and
 * [StepTwoConfig.UNSET] is empty, so it refuses until the owner edits the config in source.
 */
class StepTwoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gate = StepTwoGate(BuildConfig.STEP_TWO_ENABLED)
        val config = StepTwoConfig.UNSET
        val out = TextView(this).apply { textSize = 12f; text = "Step two (owner-enabled).\nConfig problems: ${config.problems().ifEmpty { listOf("none") }}" }
        val btn = Button(this).apply { text = "Download and run (needs explicit confirmation)" }
        btn.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Fetch from the network?")
                .setMessage("This downloads the configured archive(s), verifies sha256 and runs them locally. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Fetch") { _, _ ->
                    Thread {
                        val text = try { StepTwoRunner(gate, config, HttpFetcher(), File(filesDir, "steptwo"), File(applicationInfo.nativeLibraryDir)).run() } catch (t: Throwable) { "failed: $t" }
                        runOnUiThread { out.text = text }
                    }.start()
                }.show()
        }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
            addView(btn); addView(ScrollView(this@StepTwoActivity).apply { addView(out) }) })
    }
}
