# Phonebrew — the open core (android-ide-core)

**Phonebrew** is a **sovereign, post-desktop computing environment on the phone** — an
open, on-device "Claude Code with loop engineering" that grows into a real **agentic IDE**:
chat with any model (on-device GGUF + cloud), a git-like message **tree**, a **Council**
of models, a free-form **Loop** graph engine (GraphRunner + BPMN 2.0), the agentic **repo
loop** (read → propose → per-hunk review → commit), **Devices** (Pi / Arduino / ESP / USB),
and a full spatial IA. **Open core**, package `dev.aarso.*` (rename deferred).

## Binding rules (do not relax)
1. **No telemetry, analytics, or phoning home. Ever.**
2. **On-device is the default**; cloud is opt-in per use and visibly a **watched object**.
3. The council is a **Council** (mixture-of-agents), never "MoE".
4. **API keys** are encrypted in the Android Keystore, never logged, never sent anywhere
   but the provider they belong to.
5. Plan before code; small legible commits; **never claim on-device behaviour works** —
   the build env has no device; the owner verifies on the phone.

## The constellation
This is the **open core** of a family. The **design system** is open in
[`Hyle-Design-System`](https://github.com/mbaliga/Hyle-Design-System) (consumed via the
token contract). The **self-reflection lens** is the private `Aarso` repo (a real lens
installs into the core's inert `MirrorSeam`). The paid **Studio** layer (project-management
UX, the store-publish pipeline) attaches above core through clean seams (`DevelopTabs`,
`ProjectRoomSlot`) — so the open core ships free and complete on its own.

> Extracted from the Aarso monorepo per its `docs/EXTRACTION_PLAN.md`. Public-core history
> begins fresh here (orphan-start), so no proprietary code or history is carried in.

## Build
```
./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test   # the JVM gate
```
JDK 17, Android SDK (`local.properties` → `sdk.dir`), NDK `28.2.13676358`, CMake `3.31.6`.
`git submodule update --init --recursive` for the native (llama.cpp / stable-diffusion.cpp)
assemble; the unit-test gate above does not require the native build.
