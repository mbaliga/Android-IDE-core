package dev.aarso.ui.workdeck

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.aarso.service.WorkdeckService

/** System-owned projection consent surface; API 34+ lets the user choose one app or the display. */
class WorkdeckProjectionActivity : Activity() {
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WorkdeckService::class.java)
                    .setAction(WorkdeckService.ACTION_START_PROJECTION)
                    .putExtra(WorkdeckService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
                    .putExtra(WorkdeckService.EXTRA_PROJECTION_DATA, result.data),
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        projection.launch(intent)
    }
}
