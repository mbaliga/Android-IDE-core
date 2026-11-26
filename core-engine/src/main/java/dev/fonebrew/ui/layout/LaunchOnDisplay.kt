package dev.fonebrew.ui.layout

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import dev.fonebrew.domain.layout.DisplayLaunchPlanner
import dev.fonebrew.domain.layout.DisplayState
import dev.fonebrew.domain.layout.LaunchPlan
import dev.fonebrew.ui.MainActivity

/**
 * Thin adapter over [DisplayLaunchPlanner]: starts [MainActivity] on another display with
 * `ActivityOptions.setLaunchDisplayId`. **Nothing calls this yet and it has never run**: whether
 * the RedMagic's software honours it is owner-verified only (docs/design/docked-layout.md).
 */
object LaunchOnDisplay {
    /** `null` = unknown. The literal is the documented feature string; read via the public PackageManager API. */
    fun supportsSecondaryDisplayActivities(context: Context): Boolean? =
        runCatching { context.packageManager.hasSystemFeature(FEATURE_SECONDARY_DISPLAYS) }
            .getOrNull()
            ?.takeIf { it } // absence of the flag is not proof it cannot work; report unknown, not false

    fun plan(context: Context, state: DisplayState): LaunchPlan =
        DisplayLaunchPlanner.plan(state, supportsSecondaryDisplayActivities(context))

    fun start(context: Context, plan: LaunchPlan.Launch) {
        val intent = Intent(context, MainActivity::class.java)
        if (plan.needsNewTaskFlags) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(plan.targetDisplayId)
        context.startActivity(intent, options.toBundle())
    }

    private const val FEATURE_SECONDARY_DISPLAYS = "android.software.activities_on_secondary_displays"
}
