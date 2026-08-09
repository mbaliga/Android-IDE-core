# Product Direction and Benchmark Baseline

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document's own scope) with explicit DEFERRED/EXPERIMENTAL/REJECTED
sub-items cross-referenced, not restated. Citation target for `FB-RAT-STR-001`…`FB-RAT-STR-010`
and `FB-RAT-PORT-001`…`003, 006, 007, 011`. Two corrections from independent validation are
applied inline (§4, §5) rather than silently — see each section's "Correction applied" note for
the source.

**What this document is not.** It is not `EXECUTION_CONTRACT.md` (owns `STR-004`'s full
mechanism, §2), not `docs/non_ratified/EXPERIMENTAL_DECISIONS.md` (owns `STR-009`'s scenario
weights and `PORT-004/005/008/009/010`, §6), and not `DISTRIBUTION_CAPABILITY_SPLIT.md` (owns the
full capability-manifest consequences of the Android W^X fact recorded in §4 — a different WP-1
agent's output). Where this document touches those areas it cross-references rather than
restates, per the task brief's instruction not to author another domain's contract doc from
inside this one.

---

## 1. Category statement (FB-RAT-STR-001)

**FB-RAT-STR-001 — ACCEPTED.** The product is a **sovereign phone-native developer operating
environment** — not a desktop editor with an AI panel bolted on. This is a category claim, not a
feature claim: the phone is the primary computing surface the developer works *in*, not a remote
control for a desktop-shaped tool that happens to run on a phone screen. Every other STR/PORT
decision in this document sits underneath this one; a design that would make sense for "a mobile
AI coding assistant" but not for "the primary place you do the work" is out of scope for this
product, independent of how well-executed it might be as the former.

## 2. Laptop-replacement = workflow replacement (FB-RAT-STR-002)

**FB-RAT-STR-002 — ACCEPTED.** Replacing a laptop means replacing the *workflow*, not
reproducing a laptop's UI inside a smaller frame. A feature earns its place by closing a real gap
in the on-phone development workflow (edit → run → debug → ship), not by visually resembling
what a desktop IDE panel would show. This is the standard `PRODUCT_DIRECTION_AND_BENCHMARK_
BASELINE.md` applies when a later work package proposes a UI shape borrowed from desktop
tooling without first asking what workflow step it serves on-device.

## 3. Solo-shipper persona (FB-RAT-STR-003)

**FB-RAT-STR-003 — ACCEPTED.** The design persona is a **solo shipper** — one person taking a
project from idea to shipped artifact without a team, a desktop, or a second device to lean on.
This is the persona `FB-RAT-STR-007`'s benchmark target (§7) and `FB-RAT-PORT-003`'s P1 scope
(§9 — Device Broker, tests/profiling, offline resilience) are calibrated against: features that
assume a team's division of labor (e.g. a dedicated reviewer role, a separate CI operator) are
evaluated against how a solo shipper actually uses them alone, not against a team workflow this
product does not target.

## 4. Visible compute provenance rule (FB-RAT-STR-004)

**FB-RAT-STR-004 — ACCEPTED**, cross-reference only. The rule that compute provenance (what ran
where — on-device vs. a "watched object" cloud provider, per `CLAUDE.md` binding rule 2) must be
visible to the user at the point of use lives mainly in `EXECUTION_CONTRACT.md` (a different
work package's output; not yet written as of this document per the WP-0 survey — see
`docs/WP0_SURVEY.md` §1(c), Execution Contract + Authority engine is create-new). This document
does not restate that mechanism; it only asserts that `FB-RAT-STR-004` is binding and that any
benchmark scenario touching cloud compute (§8) must exercise the provenance-visibility path, not
route around it.

## 5. Public claim boundary (FB-RAT-STR-005)

**FB-RAT-STR-005 — ACCEPTED.** The public-facing claim boundary is exactly: **"the phone is the
sovereign primary development device."** Marketing, docs, and in-app copy MUST NOT overstate this
into "replaces every desktop workflow" or understate it into "an AI coding assistant app" — both
misrepresent `FB-RAT-STR-001`'s category statement. This is the sentence a later Studio-layer
launch/store-listing generator (`studio-launch/StoreListing.kt` per the WP-0 survey §5) should be
checked against, not a different phrasing invented per surface.

## 6. Competitive learning (FB-RAT-STR-006)

**FB-RAT-STR-006 — ACCEPTED.** The product roadmap incorporates competitive learning from
existing mobile-dev and AI-coding tools as an input, not a target to match feature-for-feature —
consistent with `FB-RAT-STR-001`: a competitor feature is adopted only if it serves the
sovereign-phone-native category this product occupies, not because a competitor shipped it.

## 7. Benchmark target environment (FB-RAT-STR-007)

**FB-RAT-STR-007 — ACCEPTED.** The benchmark target is a **16GB ARM64 flagship handheld,
docked and undocked.** This matches the constellation's actual build convention today —
`CLAUDE.md`'s "Target device & conventions": a high-end `arm64-v8a` Android phone, `minSdk 31`,
`targetSdk/compileSdk 36`, single ABI `arm64-v8a` — this document ratifies that convention as the
**benchmark** baseline specifically (not just a build-flavor default), so a future benchmark
scenario (§8) that assumes a lower-RAM device or a non-ARM64 target is out of baseline scope
unless a separate decision widens it.

**Benchmark/target-environment fact (recorded here per WP-1 task instruction, full
capability-manifest consequences owned elsewhere):** Android has enforced a **W^X execution
restriction since API 29+** — SELinux blocks `execve()`/exec-mmap of files in app-writable
storage. This is why Termux was pulled from the Play Store in November 2020, and it is an
**OS-level constraint independent of distribution channel** — it applies identically whether an
app ships via Play, sideload, or any other channel, because it is enforced by the OS's SELinux
policy against the *storage location* a binary executes from, not by a store's review policy.
Any benchmark scenario (§8) or capability manifest (`schemas/common/
capability-manifest.schema.json`, `targetRequirements`) describing an on-device compiled-toolchain
or interpreter execution path MUST account for this constraint rather than assume Play-policy
avoidance (e.g. sideload-only distribution) is sufficient to route around it. The full
capability-manifest consequences of this fact — which `subjectKind: "EXECUTION"` manifests can
legally claim `device`-local execution and under what `targetRequirements` — are **not** decided
in this document; they land in `DISTRIBUTION_CAPABILITY_SPLIT.md`, a separate WP-1 agent's
output, cross-referenced only.

## 8. Shipped-vs-designed scoring rule (FB-RAT-STR-008)

**FB-RAT-STR-008 — ACCEPTED.** A benchmark scenario scores what has actually **shipped** and is
**owner-verified working**, never what is merely designed, coded-but-untested-on-device, or
planned. This is the same discipline `CLAUDE.md`'s "Environment honesty" section already applies
constellation-wide (never report on-device behavior as confirmed when the build container has no
device/emulator/board/SSH host) — `FB-RAT-STR-008` makes it a formal scoring rule for the
benchmark specifically, not just a reporting convention. A conformance-suite `PERFORMANCE` row
(`docs/ratified/COMMON_CONVENTIONS.md` §11) that has never run on a real device scores as
**not shipped**, regardless of how complete the code looks.

## 9. Benchmark scenario suite (FB-RAT-STR-009) — EXPERIMENTAL, cross-reference only

**FB-RAT-STR-009 — EXPERIMENTAL.** The B01–B10 scenario suite and its scoring weights are not
ratified by this document. They are tracked in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`
(a different agent's output within this same handoff-pack build-out). This document records only
that the suite exists and is EXPERIMENTAL — it does not enumerate B01–B10 or their weights here,
to avoid this "foundations" domain accidentally self-ratifying content that belongs to the
non-ratified register.

## 10. Benchmark governance (FB-RAT-STR-010)

**FB-RAT-STR-010 — ACCEPTED.** Benchmark governance is: **fixed fixtures per release candidate**,
with **score deltas retained** across releases rather than discarded after each run. Concretely,
this means a benchmark run's fixtures (whatever scenario suite `FB-RAT-STR-009` eventually
stabilizes into) are pinned for the lifetime of a given release candidate — not regenerated
mid-cycle, which would make a score delta meaningless — and every run's score is kept (not just
the latest), so regressions and improvements are traceable release-over-release. This mirrors the
same append-only-facts discipline `FB-RAT-COM-006` requires of events/receipts
(`docs/ratified/COMMON_CONVENTIONS.md` §6): a benchmark score history is itself an append-only
log, not a single mutable "current score" field.

## 11. Roadmap center of gravity (FB-RAT-PORT-001)

**FB-RAT-PORT-001 — ACCEPTED.** The roadmap's center of gravity is **workspace, execution,
language, debugger, Git, and docked** — these come before adding more product "rooms" (in the
sense of `CLAUDE.md`'s spatial-IA room model: Chat/Conversations/Settings/Project/Tree/Develop).
A proposal to add a new top-level room is out of `FB-RAT-PORT-001`'s center of gravity unless it
is in direct service of workspace/execution/language/debugger/Git/docked maturity.

## 12. P0 scope (FB-RAT-PORT-002)

**FB-RAT-PORT-002 — ACCEPTED, scope-ratification-only for its sub-areas.**

> **Correction applied (from independent validation, stated per the task brief rather than
> silently):** `FB-RAT-PORT-002` ratifies *that* the following are in P0 scope. It does **not**
> itself define the debugger/Git/Android-lane/docked/tests/search sub-areas' own contracts —
> those are separate outputs a later work package adds. This document is a forward pointer to
> that future work, not an attempt to author it here.

P0 scope, as ratified:

- **Workspace/editor**
- **Terminal / process supervisor**
- **Local / SSH / CI targets** — per the WP-0 survey, the SSH lane and CI build spine already
  exist as real substrate in `core-engine` (survey §1(h), §1(i): `sshj`-backed
  `RemoteSessionDriver`/`SshjTransport`, and `domain/builds/{Build,BuildsApi,CiTrigger}.kt` +
  the real `.github/workflows/ci.yml` gate — see `docs/WP0_SURVEY.md` for the exact command,
  which both repos' `CLAUDE.md` files state incorrectly). P0 here ratifies these as in-scope
  *product surface*, not as greenfield build work.
- **Capsules**
- **Language services**
- **Debugger** — sub-area scoped only, contract deferred (see correction above).
- **Git** — sub-area scoped only, contract deferred. Per the WP-0 survey §3, git integration in
  this constellation is pure REST request-builders (`GitContentsApi.kt`/`GitTreeApi.kt` against
  GitHub/Gitea), no JGit or embedded git library — any future Git-domain contract should model
  against that real shape, not invent a libgit2-style abstraction.
- **Android lane** — sub-area scoped only, contract deferred.
- **Docked IDE** — sub-area scoped only, contract deferred.
- **Reliability**
- **Authority**

## 13. P1 scope (FB-RAT-PORT-003)

**FB-RAT-PORT-003 — ACCEPTED.** P1 scope: **Device Broker, Arduino/ESP, embedded debug,
Raspberry Pi, tests/profiling, typed agent tools, offline resilience.** Per the WP-0 survey §1(e),
"Device Broker" has no existing type or module by that literal name anywhere in the constellation
today, but real substrate exists to extend (`core-engine/src/main/java/dev/aarso/data/
DeviceRepo.kt` + the `domain/device` package + the SSH spine) — P1 work in this area is
extend-existing-substrate-under-new-naming, not greenfield, per that survey finding.

## 14. Language depth policy (FB-RAT-PORT-006)

**FB-RAT-PORT-006 — ACCEPTED.** Language support policy is **a small set deep, not many
shallow** — a handful of languages with real debugger/LSP/execution depth, rather than broad
shallow support across many languages. See §15 for how this interacts with the corrected
lane-symmetry finding.

## 15. Language sequence (FB-RAT-PORT-007)

**FB-RAT-PORT-007 — ACCEPTED, stands, with a corrected asymmetry.** Language sequence:
**TypeScript and Python first, then Git/docked, then Android, then embedded, then Raspberry
Pi.**

> **Correction applied (from independent validation):** `FB-RAT-PORT-007` stands as the sequence,
> but the four language lanes it and `FB-RAT-PORT-006` presuppose are **not symmetric**, and this
> document must not present them as though they were:
> - **TypeScript and Python are interpreter-lane languages** — both are locally viable *both
>   flavors* (on-device execution and remote SSH/CI execution) on the phone target today, subject
>   to the W^X constraint recorded in §7 for whatever concrete execution mechanism a lane uses.
> - **Rust and C++ are compiled-toolchain lanes** — these are **remote-first** (SSH/CI) until a
>   bundled-`jniLibs` or capsule-APK path is engineered to get a compiler itself running
>   on-device within the W^X constraint. Treating a compiled-toolchain lane as equivalent to an
>   interpreter lane for on-device viability would overstate what P0/P1 can actually deliver
>   on-phone before that engineering work exists.
>
> Any future language-lane contract or capability manifest MUST declare which of these two
> categories it belongs to and MUST NOT claim on-device-equivalent-to-remote viability for a
> compiled-toolchain lane without the bundled-`jniLibs`/capsule-APK work this correction names as
> the actual precondition.

## 16. Studio boundary (FB-RAT-PORT-011)

**FB-RAT-PORT-011 — ACCEPTED.** Studio **may consume** dev evidence (e.g. `ArtifactRef`s,
`ConformanceSuite` results, benchmark scores from this document's governance in §10) but **MUST
NOT own or block** core dev substrate. Concretely: a Studio-layer feature (per the WP-0 survey
§5 — `studio-pm`, `studio-launch`, the entitlement gate) reading a core `ArtifactRef` or
`ConformanceSuite` to power a PM dashboard is within bounds; a core dev-substrate feature gated
on Studio's entitlement check (`StudioEntitlement.kt` per the survey) would violate
`FB-RAT-PORT-011` — the entitlement gate the survey found is real and code-complete, but it MUST
stay scoped to Studio's own paid-layer features, never to core substrate this document's P0/P1
scope (§12–§13) covers.

---

## Deferred / Experimental / Rejected — cross-reference only (FB-RAT-PORT-004, 005, 008, 009, 010)

`FB-RAT-PORT-004`, `005`, `008`, `009`, and `010` exist and carry DEFERRED, EXPERIMENTAL, or
REJECTED status. Per the task brief for this document, they are **not** re-stated in full here —
their content lands in `docs/non_ratified/` (the non-ratified-registers agent's output, a
different work package within this same handoff-pack build-out). This document records only that
they exist and are not part of the ACCEPTED `PORT-001..003, 006, 007, 011` set ratified above.
Do not treat their absence from §11–§16 as an oversight; it is the documented scope boundary of
this file.

## Cross-references

- **`EXECUTION_CONTRACT.md`** — owns `FB-RAT-STR-004`'s full compute-provenance mechanism (§4).
- **`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`** — owns `FB-RAT-STR-009`'s B01–B10 scenario
  suite and weights (§9), and `FB-RAT-PORT-004/005/008/009/010`'s full text (above).
- **`DISTRIBUTION_CAPABILITY_SPLIT.md`** — owns the full capability-manifest consequences of the
  Android W^X execution-restriction fact recorded in §7.
- **`docs/ratified/COMMON_CONVENTIONS.md`** — the `foundations` domain this document's benchmark
  governance (§10) and shipped-vs-designed scoring rule (§8) both lean on for their
  append-only-facts (`FB-RAT-COM-006`) and environment-honesty framing.
- **`docs/WP0_SURVEY.md`** — source of every "already exists" / "extend-existing" / "create-new"
  finding this document cites in §12–§13; read it directly for the underlying evidence rather
  than trusting this document's paraphrase of it.
