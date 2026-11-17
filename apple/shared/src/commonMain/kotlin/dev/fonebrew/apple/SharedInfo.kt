// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.diff.LineDiff
import dev.fonebrew.domain.tree.MessageTree

/** Facade the Swift shell calls; keeps Swift away from Kotlin generics where possible. */
object SharedInfo {
    const val APP_NAME = "Fonebrew"
    /** Apple bundle id lives in apple/ios/project.yml (one marked place); NOT duplicated here. */
    const val SHARED_CORE = "dev.fonebrew.domain (tree, diff) compiled from core-engine sources"

    /** A tiny deterministic demo conversation so the UI proves the shared tree runs on the device. */
    fun demoTreeLines(): List<String> {
        val root = MessageNode("n1", null, Role.SYSTEM, "You are Fonebrew.", createdAt = 1)
        val q = MessageNode("n2", "n1", Role.USER, "Hello", createdAt = 2)
        val a1 = MessageNode("n3", "n2", Role.ASSISTANT, "Hi.", modelId = "demo", createdAt = 3)
        val a2 = MessageNode("n4", "n2", Role.ASSISTANT, "Hello there.", modelId = "demo", createdAt = 4)
        val tree = MessageTree(listOf(root, q, a1, a2))
        val path = tree.pathToRoot("n4")
        return path.map { "${it.role.wire}: ${it.content}" } +
            "branches under n2: ${tree.childrenOf("n2").size}"
    }

    fun diffSummary(old: String, new: String): String {
        val stat = LineDiff.stat(old, new)
        return stat.toString()
    }
}
