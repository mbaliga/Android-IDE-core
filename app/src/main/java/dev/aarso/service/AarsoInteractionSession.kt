package dev.aarso.service

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.aarso.AarsoApp
import dev.aarso.data.Intake
import dev.aarso.ui.MainActivity

/**
 * The summoned assist surface (handoff §7). On the assist gesture we capture the
 * on-screen text (Assist API, tier 1) and route it into Aarso, then bring the app
 * forward — the "content → act on it" experience through a sanctioned door. We
 * can't lift the original file the way an OEM can; this is captured text.
 */
class AarsoInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val text = extractText(state.assistStructure)
        if (text.isNotBlank()) {
            (context.applicationContext as AarsoApp).container.sharedIntake
                .offer(Intake(text = text, source = "assist"))
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Bring Aarso forward; if assist text arrives it's already routed reactively.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        hide()
    }

    private fun extractText(structure: AssistStructure?): String {
        if (structure == null) return ""
        val sb = StringBuilder()
        for (i in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(i).rootViewNode, sb)
        }
        return sb.toString().trim().take(4000)
    }

    private fun collect(node: AssistStructure.ViewNode, sb: StringBuilder) {
        node.text?.let { t -> if (t.isNotBlank()) sb.append(t).append('\n') }
        for (j in 0 until node.childCount) collect(node.getChildAt(j), sb)
    }
}
