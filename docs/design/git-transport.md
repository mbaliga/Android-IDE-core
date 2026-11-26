# Git engine, part 3 (`domain/gitcore/transport/`, `domain/gitcore/client/`, `data/gitcore/`) — design note

Status: wave-2 lane G3, BUILT. The first commit of this file was the plan; the section "As built"
at the end records what changed between plan and code. Part 1 (`docs/design/git-engine.md`) is the
object model, packs, refs, index and work-tree operations; this part adds the network. Test counts are
in the lane report, not frozen here.

## Why
Part 1 can read, write and check out a repository but cannot get one. The owner's goal is "clone,
branch, commit, push ... against any git host, no desktop step". The REST layer (`domain/git/`) stays
the fast path for browse and agent commits and is NOT touched. This lane speaks the real wire
protocol, clean-room from the public protocol documentation (pkt-line, capabilities, protocol v0/v2,
side-band, pack format, receive-pack report-status) and our own tests. The installed git 2.43 is a
black-box oracle and the test SERVER only; no JGit/libgit2/other GPL-family code is read or ported.

## What gets built
1. `domain/gitcore/transport/` (pure Kotlin, JVM-tested, no `android.*`, no OkHttp, no sshj):
   pkt-line reader/writer with flush/delim/response-end and length caps; capability parsing;
   side-band / side-band-64k demultiplexer (pack bytes out, progress to a callback, band 3 -> typed
   error); v0/v1 advertisement parser; protocol v2 `ls-refs` and `fetch` (want/have/done, shallow,
   deepen, ofs-delta, thin-pack, include-tag); receive-pack request builder and report-status parser
   (delete refs, atomic when advertised, push is never forced by default).
2. Negotiation and pack handling: a bounded single-round `have` set (ref tips first, then recent
   commits by committer time, hard cap) sent together with `done`; the pack goes through the part-1
   `PackIndexer` (spool + trailer check + thin completion); a connectivity check runs BEFORE any ref
   moves; the push pack contains only what the remote lacks (`objectsToSend` against the remote's
   advertised tips we hold).
3. Transports behind one port (`GitTransport`): smart HTTP(S) on OkHttp and SSH on sshj, in
   `data/gitcore/`. Credentials come from ports (`CredentialProvider`, `SshKeyProvider`,
   `HostKeyPolicy`); the engine never stores them.
4. `domain/gitcore/client/GitClient.kt`: clone (Flow of typed progress events), fetch, pull
   (fast-forward only), push, remote add/remove/list, ls-remote; every failure a `GitFailure`.
5. A real-git test fixture (JDK `HttpServer` bridged to `git http-backend`) and the interop suite.

## What is NOT built (named follow-ups)
Partial-clone filters, bundle URIs, LFS, the `git://` protocol, HTTP proxies, resumable clone,
multi-round negotiation (one bounded round with `done`), v2 push (does not exist upstream), push
options, signed pushes, `want-ref`, SHA-256 object format, merge-on-pull (typed `NeedsMerge` unless
the part-2 merge engine is on the head), credential helpers / OAuth device flow, the SSH-key
management UI (the key store is only a PORT here).

## Seams and the credential model
- `CredentialProvider.credentialFor(CredentialRequest(scheme, host, port, usernameHint))` is
  consulted PER HOST and only after the server answered 401 with a Basic challenge (git's own
  order: anonymous first). Credentials are therefore never sent pre-emptively and a redirect to
  another host re-asks the provider for THAT host (which has no entry), so one host's token cannot
  reach another by construction. URLs carrying a password are refused; every message prints
  scheme://host/path only.
- `Credential` and `SshKeyMaterial` override `toString` to a redaction and are never put in
  exception messages; a test greps every exception, event and `toString` from a failing run for the
  secret.
- Tokens live in the existing Keystore-backed `GitHostStore`; `GitHostCredentialProvider` adapts it
  (by lambdas, so it is JVM-testable). `GitHostFlavor` (GitHub, GitLab, Gitea/Forgejo, Bitbucket,
  other) only maps a token to the right Basic username convention; the engine stays host-agnostic.
- SSH: `HostKeyPolicy` reuses `domain/remote` `KnownHosts`/`Trust`: only `Vetted` connects silently,
  `Unknown`/`Changed` go to the user-decision callback, default is refuse. Identity comes from
  `SshKeyProvider`. No password prompt and no agent.

## Safety model
- Hostile server: pkt-line lengths, side-band frames, advertisement size, ref count and sideband
  error text are capped; a pack that fails trailer checksum, is truncated, or lacks a base is
  refused by the indexer; a pack that indexes but leaves a wanted tip unreachable (missing tree,
  blob or parent) fails the connectivity check. In every case NO ref is created or moved. Objects
  from a refused pack may remain unreferenced in the object store (harmless; no gc yet).
- Refs move only in one place (`FetchApply`), after connectivity, each with compare-and-swap.
- Push: `ForceMode.None` is the default. A non-fast-forward is detected locally (and server-side
  rejections are surfaced verbatim as `Rejected`). `Force` and `WithLease(expectedOld)` are the only
  ways to send a non-FF update; lease compares the remote's advertised value with `expectedOld`
  before sending, and the old value rides in the command so the server also compare-and-swaps.
- Redirects: at most 5, handled by us (OkHttp auto-follow is off), https -> http refused, the new
  base must end in `/info/refs` (as git requires), credentials not carried.
- Cancellation: clone writes into a sibling staging directory and renames it onto the target only
  after checkout completes; cancel or failure deletes the staging directory. The target path never
  holds a repository that looks valid but is incomplete. Not resumable (named follow-up).
- Memory: the pack is streamed to disk by the part-1 indexer; the push pack streams from the object
  store into the request body; only pkt-lines and the ref advertisement are buffered (capped).

## Owner-verified-only list (nothing here ran against these)
Real GitHub / GitLab / Gitea / Bitbucket behaviour (their token username rules, rate limits,
`WWW-Authenticate` shapes, v2 support); SSH against a real sshd (no sshd in the build container, so
the SSH adapter is compile-verified and its pure helpers are unit-tested only); large repositories
on phone storage; cellular data cost (no metered-network guard exists); Android TLS trust store
behaviour; time-to-first-byte and pack indexing speed on the phone.

## As built (deviations from the plan, and facts the plan could not know)

Protocol versions and why
- **v2** (`ls-refs`, `fetch` with want/have/done, shallow, deepen, ofs-delta, thin-pack, include-tag) is
  requested first (`Git-Protocol: version=2` over HTTP, `GIT_PROTOCOL` over SSH where the server accepts
  the variable). It is the only version modern hosts optimise and it keeps ref listings small.
- **v0/v1** is the fallback when the server answers in v0 (older servers, some proxies, and the
  `v0Only` switch of the test server). Same code path above the codec.
- **Push is v0** `receive-pack` with `report-status` (v2 has no push). `side-band-64k` is used when offered.
- One negotiation **round with `done`**, no multi-ack. Measured against git 2.43: without `multi_ack`
  the server ACKs every common `have` (not just the first) before the pack, so the client skips any
  number of `ACK`/`NAK` lines and hands the first side-band frame on. Servers without side-band are
  refused as UNSUPPORTED (named follow-up) instead of guessing where a raw pack starts.
- `have` set: ref tips first (cap 128), then newest commits by committer time, 256 in total.

Safety, as implemented
- Order inside one fetch (`FetchEngine`): index the pack (trailer / base / malformed entry refused) ->
  `Connectivity.verify` from every wanted tip (stops at trusted `have` commits and shallow roots, checks
  every tree and blob of the new commits exists and parses; tag chains are followed) -> write
  `.git/shallow` -> move refs with compare-and-swap. Refs are never created or moved before the check.
  A refused pack can leave unreferenced objects in the store (no gc yet); they are never reachable.
- Hostile ref names in an advertisement (`..`, `.git` components, bad characters) are skipped, never written.
- Tags are never overwritten (a differing local tag is reported in `FetchOutcome.tagsNotUpdated`).
- Push: `ForceMode.None` is the default and the ONLY thing that sends is an old/new pair the caller
  allowed. A non-fast-forward without `Force`/`WithLease` is refused locally and nothing is sent (a test
  asserts no receive-pack POST reaches the wire). Tags are never updated without force.
  `WithLease(expectedOld)` is checked client-side against the advertised value, and the same old value
  rides in the command so the server also compare-and-swaps. Server refusals come back verbatim
  (sanitised, one line, 500 chars) as `Rejected` / `NonFastForward(remoteReason)`.
- Credentials: asked only after a 401 with a Basic challenge, per origin; attached only to requests for
  that origin; a 401 for another origin asks the provider again by that host name. A Bearer-only
  challenge never calls the provider. `Credential`, `SshKeyMaterial` print as redacted; URLs with a
  password are refused at parse time and addresses are stored/printed without userinfo.
- Redirects: followed by us for the discovery GET only (5 max); a redirected data POST, an https -> http
  step, or a target that does not end in `/info/refs` is `RedirectRefused`.
- Cancellation / failure of a clone: work happens in `<target>.fonebrew-clone-<hex>` next to the target and
  is renamed onto it only when checkout finished; everything else is deleted. NOT resumable (named
  follow-up). A process kill can leave the staging directory (its name says what it is); the target
  path is never a half repository.

Facade notes
- `pull` is fetch + fast-forward. The move uses the guarded `checkout` (dirty tree -> typed
  `LocalChanges`, nothing changed) and then moves the branch ref and re-attaches HEAD; a crash between
  those two steps leaves a detached HEAD at the fetched commit (recoverable, nothing lost). Diverged
  histories return `PullOutcome.NeedsMerge(ours, theirs, base)`: the merge lane's tree-level merge is not on
  the head yet (only the text merge is), so there is nothing to call.
- `GitClient` is wired in `AppContainer.gitClient` (lazy). SSH has no key store yet, so ssh:// remotes
  fail with a typed AuthRequired until the key-management package lands.
- `gitcore` seam (part-1 file touched): `Revisions.kt` now asks `Shallow.kt` for the shallow set so
  `log`, `ancestors`, `mergeBase` and `objectsToSend` treat a shallow commit as a root. Nothing else in
  part 1 changed; the REST layer is untouched.
- Progress events are measured values only: `Receiving(bytes, totalObjects)` where the object count is the
  one the pack header declares, `ResolvingDeltas` as a phase marker (the indexer has no per-object callback;
  adding one would change part 1), `CheckingOut` as start/end (no per-file counter).

Test harness
- `GitHttpFixture` serves bare repositories through `git http-backend` (CGI) behind a small socket-based
  HTTP server (`MiniHttpServer`; the Android unit-test classpath has no `com.sun.net.httpserver`), with
  switchable Basic auth, v0-only mode, redirect mode and an in-flight tamper hook. Every honest answer
  comes from git 2.43; the hostile cases rewrite real answers. Tests skip with a stated reason when git
  or `git http-backend` is missing. The SSH interop test is skipped when no sshd is present (none is in
  the build container).

Additional not-built items found while building
Servers without side-band, multi-round negotiation, `--prune`, fetching namespaces other than branches and
tags (notes, pull-request refs), `want-ref`, submodule recursion, reachable-SHA-1-in-want, per-file
checkout progress, resuming a partial pack, a metered-network guard, proxy authentication.
