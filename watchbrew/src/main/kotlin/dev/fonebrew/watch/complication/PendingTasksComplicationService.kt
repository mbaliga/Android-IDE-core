package dev.fonebrew.watch.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.aarso.watch.R
import dev.fonebrew.watch.MainActivity
import dev.fonebrew.watch.core.ComplicationContent
import dev.fonebrew.watch.core.ComplicationText
import dev.fonebrew.watch.watchRuntime

/**
 * The one complication: the phone's true pending-task count, as SHORT_TEXT, with the monochrome
 * Fonebrew glyph. The text rules (unknown is `--`, never 0; a real 0 is `0`; an old count carries a
 * trailing `?`; above 99 reads `99+`) live in the pure [ComplicationText]; this service only maps the
 * result onto the platform's data classes. Updates are push-driven: the controller asks for a refresh
 * whenever a task snapshot arrives, and the manifest sets no update period, so nothing polls.
 *
 * How it renders on a given watch face is device-verified only (watchbrew/OWNER_VERIFY.md).
 */
internal class PendingTasksComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val now = System.currentTimeMillis()
        return build(ComplicationText.derive(pendingCount = 3, receivedAtMs = now, nowMs = now))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val tasks = watchRuntime.controller.state.value.tasks
        return build(ComplicationText.derive(tasks.snapshot?.pendingCount, tasks.receivedAtMs, System.currentTimeMillis()))
    }

    private fun build(content: ComplicationContent): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(content.text).build(),
            contentDescription = PlainComplicationText.Builder(content.contentDescription).build(),
        )
            .setTitle(PlainComplicationText.Builder(content.title).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_stat_fonebrew)).build())
            .setTapAction(openTasks())
            .build()

    private fun openTasks(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_TASKS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
