package dev.fonebrew.domain.gitcore.merge

import dev.fonebrew.domain.gitcore.*
import java.io.File

/** Convenience over [TestRepo] for merge tests: commit files on branches. */
internal fun TestRepo.commitFiles(msg: String, vararg files: Pair<String, String?>): ObjectId {
    for ((p, c) in files) {
        if (c == null) { file(p).delete() } else write(p, c)
    }
    repo.add(listOf("."))
    return repo.commit(CommitOptions(msg, allowEmpty = true))
}

internal fun TestRepo.branchFrom(name: String, start: String = "HEAD") { repo.createBranch(name, start) }

internal fun TestRepo.on(branch: String, block: () -> Unit) { repo.checkout(branch); block() }

internal fun TestRepo.headTree(): ObjectId = repo.headTreeId()!!

internal fun TestRepo.flat(rev: String = "HEAD") = repo.db.flattenTree(repo.treeOf(repo.resolve(rev)))

/** Contents of every tracked file in the work tree, for state-equality assertions. */
internal fun TestRepo.snapshot(): Map<String, String> {
    val out = sortedMapOf<String, String>()
    dir.walkTopDown().onEnter { it.name != ".git" }.forEach { f ->
        if (f.isFile) out[f.relativeTo(dir).path] = f.readText()
    }
    return out
}

internal fun File.exec(on: Boolean) { setExecutable(on) }
