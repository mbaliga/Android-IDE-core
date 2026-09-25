# Execution Fabric — RedMagic device certification

This checklist certifies what the phone actually supports. A step is not considered supported until it passes here.

## 1. Termux bridge

1. Install a current Termux build from an official Termux distribution channel.
2. In `~/.termux/termux.properties`, set:
   `allow-external-apps=true`
3. Open Fonebrew → Develop → Terminal → This phone.
4. Grant **Run commands in Termux environment** when prompted.
5. Tap **Probe toolchain**.

Pass:
- runtime changes to `ready`;
- `shell` is listed;
- a local `uname -a` returns output and exit 0.

Failure evidence to record:
- Android version/build;
- Fonebrew APK commit;
- Termux version;
- exact Fonebrew output pane text.

## 2. Core development toolchain

1. Tap **Install core dev**.
2. Re-run **Probe toolchain**.
3. Confirm capabilities for Git, JVM, Gradle, Python, Node and native toolchain.
4. Run:
   - `java -version`
   - `gradle --version`
   - `python --version`
   - `node --version`
   - `git --version`

Pass:
- each command exits 0;
- probe advertises only tools that actually execute.

Do not mark `ANDROID_SDK` supported from this test. The current provider intentionally keeps that capability absent until a complete ARM-native Android SDK path is certified.

## 3. Fylz workspace handoff

1. Open a small test repository folder in Fylz.
2. Folder overflow → **Open workspace in Fonebrew**.
3. Confirm Fonebrew opens and Develop → Terminal shows the workspace.
4. Tap **Materialize locally**.

Pass:
- workspace name appears;
- read-only/read-write grant is accurate;
- materialization reports file/byte counts;
- nested files and binary files arrive intact in the Termux staging directory;
- original Fylz files are unchanged.

Restart Fonebrew and confirm the workspace still appears. If it does not, record the provider authority: the provider did not permit the delegated grant to become persistent.

## 4. JVM/Gradle execution

Use a small JVM-only Gradle fixture inside the connected workspace.

Run the test/build through the local execution coordinator.

Pass:
- RuntimeBroker selects `termux-linux`;
- execution emits a terminal receipt;
- exit 0 is recorded as `SUCCEEDED_UNVERIFIED`, never as verified proof;
- declared test artifacts are copied back to SAF/Fylz;
- Assay can separately validate the returned artifacts.

## 5. Browser/Selenium

1. Tap **Install browser test**.
2. Probe again.
3. Confirm `browser_headless` and `selenium` appear.
4. Run the browser capture harness against a simple HTTPS page.

Pass:
- Chromium + Chromedriver launch headlessly;
- DOM HTML and PNG screenshot are written to the execution workspace;
- declared browser artifacts can be pulled back to SAF;
- no browser launch alone is treated as a passed test.

## 6. Large output

Run a command that emits more than Termux's PendingIntent result limit.

Pass:
- Fonebrew completes the command;
- receipt/output explicitly says the result transport truncated output;
- original stdout/stderr lengths are preserved;
- test artifacts remain usable independently of log truncation.

## 7. Windows compatibility

Tap **Probe Windows**.

Possible valid states:
- `NEEDS_SETUP`: no working Wine runtime — expected on a stock setup.
- Wine detected, no x64 translator: report partial compatibility only.
- Wine + Box64/FEX detected: x64 Windows compatibility may be offered for device testing.

No state is promoted to supported merely because a package exists; a real executable must be launched successfully before certification.

## 8. AVF isolation

Check the AVF line in Develop → Terminal.

- `not exposed`: device/firmware does not advertise Android Virtualization Framework.
- `device support detected`: AVF exists, but Fonebrew still must NOT advertise `VM_ISOLATION` until an allowed VM provider can actually create/run a VM.

## 9. Thermal/background resilience

Repeat a Gradle test and Selenium run with:
- screen off/on;
- Fonebrew backgrounded and restored;
- battery saver off/on;
- device warm after sustained load.

Record only failures where execution state or receipt becomes incorrect. Performance tuning is secondary to trustworthy state.

## Certification outcome

A provider is production-usable only when:
- capability probe matches reality;
- workspace bytes round-trip safely;
- cancellation/reconnect limitations are explicit;
- logs disclose truncation;
- execution location is visible;
- no exit code is confused with Assay proof;
- repeated runs behave consistently on the physical RedMagic.
