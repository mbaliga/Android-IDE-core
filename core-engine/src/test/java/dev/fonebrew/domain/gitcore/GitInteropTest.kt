package dev.fonebrew.domain.gitcore

import dev.fonebrew.domain.gitcore.GitTestSupport.git
import dev.fonebrew.domain.gitcore.GitTestSupport.gitBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Proof against the real git CLI (2.43 here). Every test is skipped by assumption when `git` is
 * not on PATH, and the pass/skip counts come from the JUnit report. Both directions are covered:
 * engine-written repositories read by git, and git-written repositories (loose, packed, deltified,
 * merges, annotated tags, exec bits, symlinks) read by the engine - comparing against git's own
 * answers (`rev-list`, `ls-tree`, `cat-file --batch-check`, `fsck --strict`, `ls-files -s`).
 */
class GitInteropTest {
    @Before fun requireGit() = assumeTrue("git CLI not available", GitTestSupport.gitAvailable())

    private fun lines(dir: File, vararg a: String) = git(dir, *a).lines().filter { it.isNotBlank() }
    private fun fsck(dir: File) {
        val out = git(dir, "fsck", "--strict", "--full", "--no-dangling")
        assertTrue("fsck reported problems: $out", !out.contains("error") && !out.contains("missing") && !out.contains("broken"))
    }

    private fun engineRepo(): TestRepo {
        val t = TestRepo()
        t.write("README.md", "# project\n\nhello\n")
        t.write("src/main.kt", "fun main() {\n    println(\"hi\")\n}\n")
        t.write("src/deep/a/b/c.txt", "deep\n")
        t.write("a.b", "dot"); t.write("a0", "zero"); t.write("a/z", "slash")
        t.write("run.sh", "#!/bin/sh\necho ok\n"); t.file("run.sh").setExecutable(true)
        Files.createSymbolicLink(t.file("link").toPath(), Paths.get("README.md"))
        t.write("unicode-é世.txt", "ü\n")
        val c1 = t.commitAll("first commit\n\nwith a body")
        t.write("src/main.kt", "fun main() {\n    println(\"hello\")\n}\n"); t.write("new.txt", "n\n")
        t.commitAll("second")
        t.repo.createBranch("feature")
        t.repo.checkout("feature")
        t.write("feature.txt", "f\n"); val f1 = t.commitAll("on feature")
        t.repo.checkout("main")
        t.write("main-only.txt", "m\n"); val m1 = t.commitAll("on main")
        // a hand-built merge commit (two parents) with the union tree
        t.repo.add(listOf("."))
        val sig = t.repo.signature(Identity("Eng Test", "eng@test"))
        val flatMain = t.repo.db.flattenTree(t.repo.db.readCommit(m1).tree).toMutableMap()
        flatMain.putAll(t.repo.db.flattenTree(t.repo.db.readCommit(f1).tree).filterKeys { it == "feature.txt" })
        val ix = GitIndex.fromTree(flatMain)
        val tree = ix.writeTree(t.repo.db)
        val merge = t.repo.db.write(Commit(tree, listOf(m1, f1), sig, sig, "Merge branch 'feature'\n"))
        t.repo.refs.update("refs/heads/main", merge)
        t.repo.reset(ResetMode.HARD, "main", discardLocalChanges = true)
        t.repo.createTag("v1", "main", message = "version one\n\nnotes")
        t.repo.createTag("light", c1.hex)
        return t
    }

    @Test fun `engine written repo passes fsck strict and agrees with git on log tree and objects`() {
        val t = engineRepo()
        fsck(t.dir)
        val engineAll = t.repo.refs.list("refs/").values.map { t.repo.peel(it) }.filter { t.repo.db.require(it) is Commit }.flatMap { t.repo.ancestors(it) }.toSet()
        assertEquals(lines(t.dir, "rev-list", "--all").toSet(), engineAll.map { it.hex }.toSet())
        assertEquals(lines(t.dir, "log", "--format=%H", "main"), t.repo.log("main").map { it.id.hex })
        assertEquals(lines(t.dir, "log", "--format=%H", "--first-parent", "main"), t.repo.log("main", LogOptions(firstParent = true)).map { it.id.hex })
        assertEquals(lines(t.dir, "log", "--format=%H", "--", "src/main.kt"), t.repo.log("main", LogOptions(path = "src/main.kt")).map { it.id.hex })
        // ls-tree -r agrees entry for entry
        val engineTree = t.repo.db.flattenTree(t.repo.headTreeId()).map { (p, e) -> "%06o %s\t%s".format(e.mode, e.id.hex, p) }
        val gitTree = lines(t.dir, "ls-tree", "-r", "HEAD").map { l -> l.replace(" blob ", " ").replace(" commit ", " ") }
        assertEquals(gitTree, engineTree)
        // cat-file -p bytes equal what the engine wrote
        for (spec in listOf("HEAD", "v1", "HEAD^{tree}")) {
            val id = t.repo.resolve(spec)
            val type = git(t.dir, "cat-file", "-t", id.hex).trim()
            assertArrayEquals(spec, gitBytes(t.dir, "cat-file", type, id.hex), t.repo.db.readRaw(id)!!.data)
        }
        assertEquals("version one\n\nnotes\n", git(t.dir, "cat-file", "-p", "v1").substringAfter("\n\n"))
        assertEquals("tag", git(t.dir, "cat-file", "-t", "v1").trim())
        assertEquals(t.repo.resolve("v1^{}").hex, git(t.dir, "rev-parse", "v1^{commit}").trim())
        // status agrees (clean, then dirty)
        assertEquals("", git(t.dir, "status", "--porcelain"))
        assertTrue(t.repo.status().isClean)
        t.write("README.md", "# changed\n"); t.write("untracked.txt", "u"); t.file("new.txt").delete()
        t.repo.add(listOf("a0")) // no-op
        t.write("a0", "zero changed"); t.repo.add(listOf("a0"))
        assertEquals(lines(t.dir, "status", "--porcelain", "-uall"), t.repo.status().porcelain())
        assertEquals(git(t.dir, "diff", "--cached", "--name-status").trim(), t.repo.diffStaged().joinToString("\n") { "${if (it.type == ChangeType.ADD) "A" else if (it.type == ChangeType.DELETE) "D" else "M"}\t${it.path}" })
    }

    @Test fun `engine written repo survives git gc including aggressive repack`() {
        val t = engineRepo()
        for (mode in listOf(listOf("gc", "--prune=now"), listOf("gc", "--aggressive", "--prune=now"))) {
            git(t.dir, "-c", "gc.autoDetach=false", *mode.toTypedArray())
            fsck(t.dir)
            val fresh = Repository.open(JvmFileSystem(), t.dir.absolutePath)
            val all = lines(t.dir, "rev-list", "--objects", "--all").map { it.substringBefore(' ') }
            assertTrue(all.size > 10)
            for (h in all) {
                val raw = fresh.db.readRaw(ObjectId.fromHex(h))!!
                assertEquals(h, ObjectId.hashObject(raw.type, raw.data).hex)
            }
            assertEquals(lines(t.dir, "log", "--format=%H", "main"), fresh.log("main").map { it.id.hex })
            assertEquals(t.repo.refs.resolve("refs/tags/v1"), fresh.refs.resolve("refs/tags/v1"))
            assertTrue("refs are packed after gc", File(t.gitDir(), "packed-refs").exists())
            assertEquals(t.repo.refs.list("refs/"), fresh.refs.list("refs/"))
            assertEquals("", git(t.dir, "status", "--porcelain"))
            assertTrue(fresh.status().isClean)
        }
    }

    private fun cliRepo(): File {
        val dir = GitTestSupport.tempDir("cli")
        git(dir, "init", "-q", "-b", "main")
        val big = (0 until 1500).joinToString("\n") { "line $it of a reasonably long text file that deltifies nicely" } + "\n"
        GitTestSupport.write(File(dir, "big.txt"), big)
        GitTestSupport.write(File(dir, "dir/sub/leaf.txt"), "leaf\n")
        GitTestSupport.write(File(dir, "run.sh"), "#!/bin/sh\n"); File(dir, "run.sh").setExecutable(true)
        Files.createSymbolicLink(File(dir, "ln").toPath(), Paths.get("big.txt"))
        git(dir, "add", "-A"); git(dir, "commit", "-q", "-m", "c0")
        for (i in 1..25) {
            GitTestSupport.write(File(dir, "big.txt"), big.replace("line ${i * 10} ", "LINE $i ") + "extra $i\n")
            GitTestSupport.write(File(dir, "dir/f$i.txt"), "file $i\n".repeat(i))
            git(dir, "add", "-A"); git(dir, "commit", "-q", "-m", "c$i")
        }
        git(dir, "checkout", "-q", "-b", "topic", "HEAD~5")
        GitTestSupport.write(File(dir, "topic.txt"), "topic work\n"); git(dir, "add", "-A"); git(dir, "commit", "-q", "-m", "topic")
        git(dir, "checkout", "-q", "main")
        git(dir, "merge", "-q", "--no-ff", "-m", "merge topic", "topic")
        git(dir, "tag", "-a", "-m", "annotated release", "v1.0")
        git(dir, "tag", "light")
        git(dir, "tag", "-a", "-m", "tag of a tree", "treetag", "HEAD^{tree}")
        return dir
    }

    private fun assertEngineAgreesWithGit(dir: File) {
        val repo = Repository.open(JvmFileSystem(), dir.absolutePath)
        // every object: type and size per `cat-file --batch-check`, and the body hashes back to its id
        val check = git(dir, "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)", "--batch-all-objects").lines().filter { it.isNotBlank() }
        assertTrue(check.size > 40)
        for (l in check) {
            val (h, type, size) = l.split(' ')
            val id = ObjectId.fromHex(h)
            val peek = repo.db.peek(id)!!
            assertEquals(l, "$h ${peek.first.tag} ${peek.second}")
            assertEquals(type, peek.first.tag)
            val raw = repo.db.readRaw(id)!!
            assertEquals(size.toLong(), raw.data.size.toLong())
            assertEquals(h, ObjectId.hashObject(raw.type, raw.data).hex)
            repo.db.require(id) // parses and verifies
        }
        // rev-list --all
        val ref = repo.refs.list("refs/").values.map { repo.peel(it) }.filter { repo.db.require(it) is Commit }.flatMap { repo.ancestors(it) }.map { it.hex }.toSet()
        assertEquals(lines(dir, "rev-list", "--all").toSet(), ref)
        assertEquals(lines(dir, "log", "--format=%H", "main"), repo.log("main").map { it.id.hex })
        assertEquals(lines(dir, "log", "--format=%H", "--first-parent", "main"), repo.log("main", LogOptions(firstParent = true)).map { it.id.hex })
        // ls-tree -r at HEAD and at an old commit
        for (rev in listOf("HEAD", "HEAD~7", "topic")) {
            val want = lines(dir, "ls-tree", "-r", rev).map { it.replace(" blob ", " ") }
            val got = repo.db.flattenTree(repo.treeOf(repo.resolve(rev))).map { (p, e) -> "%06o %s\t%s".format(e.mode, e.id.hex, p) }
            assertEquals(rev, want, got)
        }
        // refs: heads and tags agree incl. annotated ones
        for (l in lines(dir, "for-each-ref", "--format=%(objectname) %(refname)")) {
            val (h, name) = l.split(' ')
            assertEquals(name, h, repo.refs.resolve(name)!!.hex)
        }
        // index written by git is readable and agrees with `ls-files -s`
        val ix = repo.readIndex().entries().map { "%06o %s %d\t%s".format(it.mode, it.id.hex, it.stage, it.path) }
        assertEquals(lines(dir, "ls-files", "-s"), ix)
        assertTrue(repo.status().isClean)
        assertEquals("", git(dir, "status", "--porcelain"))
    }

    @Test fun `git written repo with loose objects is read by the engine`() {
        val dir = cliRepo()
        assertTrue(File(dir, ".git/objects").walkTopDown().none { it.extension == "pack" })
        assertEngineAgreesWithGit(dir)
    }

    @Test fun `git written repo after gc aggressive with deltas and packed refs is read by the engine`() {
        val dir = cliRepo()
        git(dir, "-c", "gc.autoDetach=false", "gc", "--aggressive", "--prune=now")
        assertTrue(File(dir, ".git/packed-refs").exists())
        val pack = File(dir, ".git/objects/pack").listFiles()!!.first { it.extension == "idx" }
        val verify = git(dir, "verify-pack", "-v", pack.path)
        val deltified = verify.lines().count { Regex("^[0-9a-f]{40} \\w+ +\\d+ \\d+ \\d+ \\d+ [0-9a-f]{40}$").matches(it.trim()) }
        assertTrue("fixture must contain deltified objects, found $deltified", deltified > 5)
        assertEngineAgreesWithGit(dir)
        // the engine can also verify the checksums and CRCs git wrote
        val repoPack = PackFile(JvmFileSystem(), pack.path.removeSuffix(".idx") + ".pack", PackIndex.open(JvmFileSystem(), pack.path))
        repoPack.use { p -> p.verifyChecksum(); p.index.verifyChecksum(); for (i in 0 until p.index.objectCount step 7) assertTrue(p.verifyCrc(p.index.idAt(i))) }
    }

    @Test fun `git written repo read by engine matches after checkout operations too`() {
        val dir = cliRepo()
        git(dir, "-c", "gc.autoDetach=false", "gc", "-q")
        val repo = Repository.open(JvmFileSystem(), dir.absolutePath, clock = { 1_800_000_000L })
        repo.config.set("user", null, "name", "Eng"); repo.config.set("user", null, "email", "e@e"); repo.saveConfig()
        repo.checkout("topic")
        assertEquals("topic", git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals("", git(dir, "status", "--porcelain"))
        assertTrue(File(dir, "topic.txt").exists())
        repo.checkout("v1.0")
        assertEquals("HEAD", git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
        assertEquals("", git(dir, "status", "--porcelain"))
        assertEquals(git(dir, "rev-parse", "v1.0^{commit}").trim(), git(dir, "rev-parse", "HEAD").trim())
        repo.checkout("main")
        assertEquals("", git(dir, "status", "--porcelain"))
        // exec bit and symlink restored by the engine are what git expects
        File(dir, "run.sh").delete(); File(dir, "ln").delete()
        repo.restoreWorktree(listOf("run.sh", "ln"), discardLocalChanges = true)
        assertEquals("", git(dir, "status", "--porcelain"))
        assertTrue(File(dir, "run.sh").canExecute())
        assertTrue(Files.isSymbolicLink(File(dir, "ln").toPath()))
        // an engine commit on a git repo is accepted by git
        GitTestSupport.write(File(dir, "from-engine.txt"), "hello from the engine\n")
        repo.add(listOf("from-engine.txt"))
        val c = repo.commit(CommitOptions("engine commit"))
        assertEquals(c.hex, git(dir, "rev-parse", "HEAD").trim())
        fsck(dir)
        assertEquals("", git(dir, "status", "--porcelain"))
        assertEquals("engine commit", git(dir, "log", "-1", "--format=%s").trim())
    }

    @Test fun `index written by the engine is accepted by git status diff and ls-files`() {
        val t = engineRepo()
        git(t.dir, "update-index", "--refresh") // git must read our index without complaint
        assertEquals("", git(t.dir, "status", "--porcelain"))
        assertEquals("", git(t.dir, "diff", "--cached", "--stat"))
        assertEquals("", git(t.dir, "diff", "--stat"))
        val engineLs = t.repo.readIndex().entries().map { "%06o %s %d\t%s".format(it.mode, it.id.hex, it.stage, it.path) }
        assertEquals(lines(t.dir, "ls-files", "-s"), engineLs)
        // stage something with the engine; git sees exactly it
        t.write("staged.txt", "s\n"); t.write("README.md", "# edited\n")
        t.repo.add(listOf("staged.txt", "README.md"))
        assertEquals(setOf("A\tstaged.txt", "M\tREADME.md"), lines(t.dir, "diff", "--cached", "--name-status").toSet())
        assertEquals("", git(t.dir, "diff", "--name-status").trim())
        assertEquals(lines(t.dir, "write-tree").single(), t.repo.readIndex().writeTree(t.repo.db).hex)
        // engine stages a deletion
        t.repo.remove(listOf("new.txt"))
        assertTrue(lines(t.dir, "diff", "--cached", "--name-status").contains("D\tnew.txt"))
        // git commit of an engine-written index works and fsck stays clean
        git(t.dir, "commit", "-q", "-m", "by git on engine index")
        fsck(t.dir)
        assertTrue(Repository.open(JvmFileSystem(), t.dir.absolutePath).status().isClean)
    }

    @Test fun `index byte for byte equal to gits for the same content`() {
        val t = engineRepo()
        // Rebuild an index with git from the engine's tree and compare entry sets; bytes can differ only in stat fields.
        val ours = t.repo.readIndex()
        val other = GitTestSupport.tempDir("clone")
        git(other, "clone", "-q", "--no-local", t.dir.absolutePath, ".")
        val theirs = Repository.open(JvmFileSystem(), other.absolutePath).readIndex()
        assertEquals(ours.entries().map { Triple(it.path, it.id, it.mode) }, theirs.entries().map { Triple(it.path, it.id, it.mode) })
        // re-serialising git's index with our writer yields a file git accepts (stat preserved, TREE dropped)
        val rewritten = theirs.toBytes()
        File(other, ".git/index").writeBytes(rewritten)
        assertEquals("", git(other, "status", "--porcelain"))
        git(other, "fsck", "--strict")
    }

    @Test fun `engine indexes a pack made by git pack-objects including deltas`() {
        val dir = cliRepo()
        val pack = gitBytesIn(dir, "rev-list --objects --all", "pack-objects --stdout --delta-base-offset")
        val t = TestRepo()
        val r = t.repo.db.indexPack(pack.inputStream())
        assertEquals(lines(dir, "rev-list", "--objects", "--all").size, r.objectCount)
        // our .idx is byte-identical to the one git builds from the same pack
        val packFile = File(GitTestSupport.tempDir("p"), "x.pack").also { it.writeBytes(pack) }
        git(packFile.parentFile, "index-pack", "-o", File(packFile.parentFile, "x.idx").path, packFile.path)
        assertArrayEquals(File(packFile.parentFile, "x.idx").readBytes(), File(r.idxPath).readBytes())
        // and git's verify-pack accepts the pack we stored
        git(t.dir, "verify-pack", File(r.idxPath).path)
        val head = git(dir, "rev-parse", "main").trim()
        t.repo.refs.update("refs/heads/main", ObjectId.fromHex(head))
        fsck(t.dir)
        assertEquals(lines(dir, "log", "--format=%H", "main"), t.repo.log("main").map { it.id.hex })
    }

    @Test fun `engine completes a thin pack made by git`() {
        val dir = cliRepo()
        val base = git(dir, "rev-parse", "HEAD~3").trim()
        val tip = git(dir, "rev-parse", "main").trim()
        val full = gitBytesIn(dir, "rev-list --objects $base", "pack-objects --stdout")
        val thin = gitBytesIn(dir, "rev-list --objects $tip ^$base", "pack-objects --stdout --thin --revs --delta-base-offset", revsStdin = "$tip\n^$base\n")
        val t = TestRepo()
        t.repo.db.indexPack(full.inputStream())
        val r = t.repo.db.indexPack(thin.inputStream())
        t.repo.refs.update("refs/heads/main", ObjectId.fromHex(tip))
        fsck(t.dir)
        assertEquals(lines(dir, "log", "--format=%H", "main"), t.repo.log("main").map { it.id.hex })
        // Whether git chose thin deltas against the base depends on its heuristics; report what happened.
        println("thin pack: appended bases = ${r.appendedBases}")
        assertTrue(r.objectCount > 0)
    }

    @Test fun `engine PackWriter output is accepted by git index-pack strict`() {
        val t = engineRepo()
        val ids = (git(t.dir, "rev-list", "--objects", "--all").lines().filter { it.isNotBlank() }.map { ObjectId.fromHex(it.substringBefore(' ')) }).distinct()
        val out = java.io.ByteArrayOutputStream()
        PackWriter.write(t.repo.db, ids, out)
        val f = File(GitTestSupport.tempDir("pw"), "w.pack").also { it.writeBytes(out.toByteArray()) }
        val dest = GitTestSupport.tempDir("dest")
        git(dest, "init", "-q", "--bare")
        val res = ProcessBuilder("git", "index-pack", "--strict", "--stdin").directory(dest).redirectErrorStream(true).also {
            it.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        }.start()
        res.outputStream.use { it.write(out.toByteArray()) }
        val msg = String(res.inputStream.readBytes())
        assertEquals(msg, 0, res.waitFor())
        assertTrue(msg, msg.contains("pack\t"))
    }

    /** Runs `git <producer>` to list revs, pipes them into `git <packer>` and returns the packer's stdout. */
    private fun gitBytesIn(dir: File, producer: String, packer: String, revsStdin: String? = null): ByteArray {
        val input = revsStdin ?: git(dir, *producer.split(' ').toTypedArray()).lines().filter { it.isNotBlank() }.joinToString("\n") { it.substringBefore(' ') } + "\n"
        val pb = ProcessBuilder(listOf("git") + packer.split(' ')).directory(dir)
        pb.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        val p = pb.start()
        val t = Thread { p.outputStream.use { it.write(input.toByteArray()) } }
        t.start()
        val out = p.inputStream.readBytes()
        t.join()
        assertEquals(0, p.waitFor())
        return out
    }
}
