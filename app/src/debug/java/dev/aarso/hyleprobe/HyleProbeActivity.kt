package dev.aarso.hyleprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Debug-only launcher for the Hyle rendering probes (a separate "Hyle Probe" icon in
 * debug builds). Throwaway: it exists to run shader effects on the real device, the
 * only place they can be validated. Never shipped — lives in src/debug, so it's absent
 * from every release/Play build.
 */
class HyleProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RadiantGlowProbe() }
    }
}
