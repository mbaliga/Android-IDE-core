package dev.aarso.ui

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.aarso.service.ScreenCaptureService

/**
 * Transparent launcher that asks for the one-shot screen-capture consent (§7,
 * MediaProjection tier). On grant it hands the token to [ScreenCaptureService]
 * and finishes immediately, so the app behind it returns to the foreground to be
 * captured + OCR'd. Yields a screenshot's text, not the original file (§7 honesty).
 */
class ScreenCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
