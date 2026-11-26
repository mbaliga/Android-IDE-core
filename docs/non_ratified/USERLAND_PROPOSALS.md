# Userland and git-engine proposals (PROPOSED - NOT IN FORCE)

> **License:** `LICENSE-PENDING` - see `docs/non_ratified/LICENSE_PENDING.md`.

**Status of everything in this file: PROPOSED. Nothing here is ratified, nothing is in force, no code
may rely on it.** The owner ratifies or rejects each proposal; until a proposal is ratified into
`docs/ratified/` by the owner, every rule it would amend stays exactly as written. Written by lane U0
(wave 2) to go with `docs/design/userland-proot.md`. Stable IDs are assigned by the owner on
ratification; the working labels below (`PROPOSAL-U-1`, `PROPOSAL-U-2`) are not register IDs.

Why proposals and not edits: `FB-RAT-DIST-003` (separately executed copyleft toolchains) is DEFERRED
pending "Real legal review, owner-only, cannot be done in-session". Nothing below pre-empts that
review; proposal U-1 is written so that it can only take effect after it.

---

## PROPOSAL-U-1 - A narrow, full-flavor-only exception for a separated, separately executed userland

**Amends (if ratified):** the copyleft-linking rule in `CONTRIBUTING.md`; the "never a binary fetched
at runtime" sentence of `docs/ratified/EXECUTION_CONTRACT.md` section 3.1 (only as stated in clause 4);
and resolves `FB-RAT-DIST-003` for this one case. **Precondition:** the owner's legal review of
`FB-RAT-DIST-003` has been completed and does not forbid the arrangement below. If it does, this
proposal is void.

**Proposed text:**

> **U-1.1 Scope.** This exception applies to the `full` (sideload) distribution flavor only. The
> `play` flavor MUST NOT contain, declare, download, or invoke any code covered by this exception,
> and its `CapabilityManifest` MUST NOT declare an operation that depends on it.
>
> **U-1.2 What is permitted.** A "separated userland" is a Linux user-space environment (a
> compatibility launcher such as proot, a shell, a root filesystem) whose components carry copyleft
> licences (GPL, LGPL or similar), provided ALL of the following hold: (a) it executes as its own
> process(es), never linked, loaded (`dlopen`/`System.load`) or compiled into any Fonebrew binary;
> (b) it is distributed either as a separately signed companion application installed by the user
> ("Fonebrew Shell"), or as unmodified upstream executables packaged in the APK as `jniLibs`
> libraries in a source set that exists only in the `full` flavor; (c) Fonebrew code interacts with
> it only through a documented, public, licence-neutral interface (process arguments, a pty, or a
> local socket protocol specified in this repository); (d) the corresponding source offers, licence
> texts and notices required by each component's licence are shipped or linked in-app (a "Third-party
> userland" screen) and in `NOTICE`; (e) core's own source remains Apache-2.0 and contains no copy of
> the copyleft code.
>
> **U-1.3 What stays forbidden.** Linking or vendoring copyleft code into core or any Fonebrew
> library; porting such code; any telemetry; any use on the `play` flavor; any `system_linker_exec`
> style linker trick (DISTRIBUTION_CAPABILITY_SPLIT mechanism (d) stands unchanged).
>
> **U-1.4 Data is not code.** A root filesystem archive is DATA and MAY be downloaded at runtime
> in the `full` flavor, with these conditions: it is fetched only after an explicit per-use user
> action that names the source host (a visible "watched object" style disclosure); the download is
> verified against a checksum pinned in the app build (SHA-256, or SHA-512 where the publisher only
> provides that) before extraction; extraction rejects absolute and `..` paths; nothing in the
> archive is executed by Fonebrew itself. The sentence "never a binary fetched at runtime and exec'd
> from app-writable storage" in EXECUTION_CONTRACT 3.1 is NOT relaxed for Fonebrew's own process:
> the only executables run are those packaged in the APK or in the installed companion; programs
> inside the rootfs run only under the separated userland's own mechanism, and the app MUST label
> such sessions as "separated userland" in the UI.
>
> **U-1.5 Legibility.** Any session running inside a separated userland is visibly marked as such
> (glyph and label, never colour alone), shows which component executes the commands, and is
> never started implicitly by an agent; an agent may only propose commands for the user to run there.
>
> **U-1.6 Evidence before use.** The exception takes effect for a given mechanism only after the
> owner has run the userland probe (`tools/userland-probe`) on the target device and the relevant
> checks passed; a mechanism whose probe checks fail is not shipped.

**Not decided here (owner questions):** whether the "separately executed" companion route (U-1.2(b),
first alternative) satisfies the legal review while the in-APK jniLibs route does not (the review
may split them); whether `NOTICE` plus an in-app screen satisfies the source-offer duty for a
mixed-licence rootfs; whether the user-facing label should be "Debian" or the neutral "Linux userland".

---

## PROPOSAL-U-2 - Supersede the "REST-only git" ruling with a full on-device git engine

**Amends (if ratified):** the statements that "the constellation has no JGit or any embedded git
library - all git integration is pure REST request-builders" (`DEVICE_STATE_AND_SAFETY_SPEC.md`
line 47; the same shape is described in `COMMON_CONVENTIONS.md` line 324 and the WP-0 survey
section 3). The owner now wants a full on-device git engine; a clean-room engine is being built in
another lane.

**Proposed text:**

> **U-2.1 Supersession.** The ruling "all git integration is pure REST" is superseded for the
> repository's own on-device engine only. Git hosts remain reachable through the existing REST
> builders (`GitContentsApi`, `GitTreeApi`) and through git transport (smart HTTP) implemented by the
> engine; both are valid, and neither is removed.
>
> **U-2.2 Engine provenance.** The engine MUST be implemented in Fonebrew's own source
> (Apache-2.0) from public format and protocol specifications (git object/pack/index formats,
> the smart-HTTP and pkt-line protocols) and its own tests. It MUST NOT link, vendor, copy or port
> JGit (EDL-1.0 is a BSD-style licence but its classpath and policy status are for the owner to
> decide), libgit2, or any GPL/LGPL implementation, and MUST NOT shell out to a `git` binary.
> Any third-party dependency must pass `config/allowed-licenses.json`.
>
> **U-2.3 Binding rules carry over.** No telemetry; credentials stay Keystore-encrypted and never
> appear in logs, URLs persisted to disk, or exception messages; network access to a host happens only
> by explicit user action or a user-configured sync; every remote is shown as a "watched object".
>
> **U-2.4 Truthfulness.** The engine MUST NOT claim a successful push, merge or fetch it did not
> perform; unsupported repository features (e.g. unfamiliar extensions, shallow oddities, signed
> push) fail visibly with the reason; partial results are labelled as such.
>
> **U-2.5 Relation to the shell.** The same engine serves the "no userland" shell command bridge
> (`docs/design/userland-proot.md` section 7), so `git` typed in the in-app shell calls the engine
> instead of a binary. This does not depend on PROPOSAL-U-1.

**Owner question:** whether EDL-1.0 (JGit's licence) is acceptable as a permissive dependency; this
note assumes the answer is "not needed", because the engine is clean-room.

---

## Ratification checklist (owner)

| Item | Ratify | Reject | Notes |
|---|---|---|---|
| PROPOSAL-U-1 (userland exception, full flavor) | | | Void unless the `FB-RAT-DIST-003` legal review permits it |
| PROPOSAL-U-2 (supersede REST-only git) | | | Independent of U-1 |
