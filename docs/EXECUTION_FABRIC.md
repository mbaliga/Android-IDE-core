# Execution Fabric

Fonebrew owns execution orchestration, not files and not test truth.

## Ownership

- **Fylz** owns file browsing, storage providers, mounts/remotes, history and file operations. Fonebrew consumes opaque workspace handles (`content://` tree URIs) and must not reproduce Fylz provider logic.
- **Fonebrew** owns runtime selection and execution orchestration. `RuntimeBroker` routes capability requests to truthful runtime profiles and then existing `ExecutionProvider` implementations do the actual work.
- **Assay** owns test/proof semantics. Fonebrew may execute an Assay-requested workload, but only Assay may decide that the resulting artifacts constitute proof/evidence.

## Runtime families

The broker can model Android native, local Linux userspace, JVM, headless browser/Selenium, Windows compatibility, isolated VM, SSH/user-owned remote and ephemeral CI.

A runtime is selectable only when its profile is `READY`. Planned providers remain `NEEDS_SETUP`; the broker fails closed rather than pretending they work.

## Current reality

Ready today: Android-native orchestration substrate, SSH execution when a trusted host is configured, and CI dispatch when a Git/CI host is configured.

Concrete local runtime: Termux RUN_COMMAND supplies the first Linux userspace provider. Fonebrew probes installed tools before advertising JVM/Gradle/Python/Node/native/browser/Selenium capabilities, exposes explicit provisioning actions, and mirrors user-granted SAF/Fylz workspaces into a bounded Termux execution workspace.

Still not implemented: Windows compatibility pack and isolated VM/AVF provider.

## Next provider work

1. Linux capsule provider: arm64 shell + Git + JDK + Gradle + Python/Node + Chromium/WebDriver, with signed/pinned runtime assets and bounded storage.
2. Browser session provider: headless Chromium, WebDriver/Selenium, screenshots/traces/artifact export.
3. Windows compatibility pack: separately installable/licensed Wine + translation runtime.
4. Isolated-VM provider: probe Android virtualization support and expose only when actually permitted.
5. Runtime capability probing: SSH/CI hosts gain JVM/browser/etc. capabilities only after an explicit probe proves them.

## Security invariants

- no downloaded binary is executed merely because it exists;
- capabilities are explicit and authority-gated;
- secrets remain opaque handles;
- remote/cloud location and data boundary stay visible;
- a clean process exit is not equivalent to verified test evidence;
- runtime/provider receipts remain append-only and attributable.

## Workspace handoff

Fylz can hand the currently open SAF tree to Fonebrew with `dev.aarso.action.OPEN_WORKSPACE`. Fonebrew persists the grant when the provider permits it and stores only the opaque `content://` capability. For local execution the tree is copied into a bounded Termux staging workspace; only declared artifacts are copied back.

## Isolation and compatibility status

- Termux Linux is the first concrete on-device execution provider.
- Selenium browser capture is concrete once Chromium, Chromedriver and the Selenium client pass the probe.
- Wine/translation support is probe-only: it becomes READY only when a working Wine executable is present; x64 translation is reported separately.
- AVF support is detected through the Android virtualization-framework system feature, but Fonebrew does not advertise `VM_ISOLATION` until a permitted VM provider actually exists.
