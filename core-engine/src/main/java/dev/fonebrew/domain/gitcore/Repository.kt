package dev.fonebrew.domain.gitcore

/** Identity used for commits/reflogs; never invented - callers or config must supply it. */
data class Identity(val name: String, val email: String)

/**
 * An opened repository: the one handle every operation takes. [workTree] is null for a bare repo.
 * [clock] returns epoch seconds and [tzMinutes] is the zone stamped on commits; both are injected
 * so tests are deterministic and the engine never reads a hidden global.
 */
class Repository private constructor(
    val fs: FileSystemPort,
    val gitDir: String,
    val workTree: String?,
    val limits: GitLimits,
    val ignore: IgnoreRules,
    val clock: () -> Long,
    val tzMinutes: Int,
    val algorithm: HashAlgorithm,
    val config: GitConfig,
) : AutoCloseable {
    val db = ObjectDatabase(fs, "$gitDir/objects", limits, algorithm)
    val refs = RefStore(fs, gitDir, algorithm)
    val isBare: Boolean get() = workTree == null
    val indexPath: String get() = "$gitDir/index"

    fun requireWorkTree(): String = workTree ?: throw GitException(GitErrorKind.INVALID_ARGUMENT, "this operation needs a work tree (repository is bare)")

    fun saveConfig() = config.save(fs, "$gitDir/config")

    /** Identity from explicit args or `user.name`/`user.email`; typed error rather than a made-up one. */
    fun identity(explicit: Identity? = null): Identity {
        if (explicit != null) return explicit
        val n = config.userName
        val e = config.userEmail
        if (n.isNullOrBlank() || e.isNullOrBlank()) throw GitException(GitErrorKind.INVALID_ARGUMENT, "author identity unknown: set user.name and user.email")
        return Identity(n, e)
    }

    fun signature(id: Identity, whenSeconds: Long = clock()): Signature = Signature(id.name.trim(), id.email.trim(), whenSeconds, tzMinutes)

    fun reflogIdentity(explicit: Identity? = null): ReflogIdentity {
        val id = try { identity(explicit) } catch (_: GitException) { Identity("unknown", "unknown@localhost") }
        return ReflogIdentity(id.name, id.email, clock(), tzMinutes)
    }

    override fun close() = db.close()

    companion object {
        /**
         * Creates a repository at [path]. Non-bare: [path]/.git plus the work tree [path]; bare: the
         * git dir is [path]. Refuses to initialise over an existing repository.
         */
        fun init(
            fs: FileSystemPort,
            path: String,
            bare: Boolean = false,
            initialBranch: String = "main",
            limits: GitLimits = GitLimits(),
            ignore: IgnoreRules = IgnoreRules.NONE,
            clock: () -> Long = { System.currentTimeMillis() / 1000 },
            tzMinutes: Int = 0,
        ): Repository {
            RefName.requireValidShort(initialBranch)
            val gitDir = if (bare) path.trimEnd('/') else path.trimEnd('/') + "/.git"
            if (fs.stat("$gitDir/HEAD") != null) throw GitException(GitErrorKind.INVALID_ARGUMENT, "a repository already exists here")
            for (d in listOf("objects/info", "objects/pack", "refs/heads", "refs/tags", "info", "hooks")) fs.mkdirs("$gitDir/$d")
            fs.writeAtomic("$gitDir/HEAD", "ref: refs/heads/$initialBranch\n".toByteArray())
            val cfg = GitConfig.empty()
            cfg.set("core", null, "repositoryformatversion", "0")
            cfg.set("core", null, "filemode", "true")
            cfg.set("core", null, "bare", if (bare) "true" else "false")
            if (!bare) cfg.set("core", null, "logallrefupdates", "true")
            cfg.save(fs, "$gitDir/config")
            return Repository(fs, gitDir, if (bare) null else path.trimEnd('/'), limits, ignore, clock, tzMinutes, HashAlgorithm.SHA1, cfg)
        }

        /** Opens the repository at [path] (a work tree containing `.git/`, or a git dir). */
        fun open(
            fs: FileSystemPort,
            path: String,
            limits: GitLimits = GitLimits(),
            ignore: IgnoreRules = IgnoreRules.NONE,
            clock: () -> Long = { System.currentTimeMillis() / 1000 },
            tzMinutes: Int = 0,
        ): Repository {
            val p = path.trimEnd('/')
            val dotGit = fs.stat("$p/.git")
            val gitDir: String
            var work: String? = null
            when {
                dotGit?.kind == FileKind.DIRECTORY -> { gitDir = "$p/.git"; work = p }
                dotGit?.kind == FileKind.FILE -> throw GitException(GitErrorKind.UNSUPPORTED, "'.git' is a file (linked worktree or submodule): not supported")
                fs.stat("$p/HEAD")?.kind == FileKind.FILE && fs.stat("$p/objects")?.kind == FileKind.DIRECTORY -> gitDir = p
                else -> throw GitException(GitErrorKind.NOT_FOUND, "not a git repository")
            }
            if (fs.stat("$gitDir/HEAD") == null || fs.stat("$gitDir/objects") == null || fs.stat("$gitDir/refs") == null) {
                throw GitException(GitErrorKind.NOT_FOUND, "incomplete git directory")
            }
            val cfg = GitConfig.load(fs, "$gitDir/config")
            if (cfg.repositoryFormatVersion > 1) throw GitException(GitErrorKind.UNSUPPORTED, "repository format ${cfg.repositoryFormatVersion}")
            if (cfg.objectFormat != "sha1") throw GitException(GitErrorKind.UNSUPPORTED, "object format '${cfg.objectFormat}' (only sha1 is implemented)")
            if (cfg.get("extensions", null, "worktreeconfig") != null || cfg.get("extensions", null, "partialclone") != null) {
                throw GitException(GitErrorKind.UNSUPPORTED, "repository uses an unsupported extension")
            }
            if (cfg.isBare) work = null
            return Repository(fs, gitDir, work, limits, ignore, clock, tzMinutes, HashAlgorithm.SHA1, cfg)
        }
    }
}
