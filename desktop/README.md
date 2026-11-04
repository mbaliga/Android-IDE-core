# desktop/

Separate Gradle build (JDK 17 only, no Android SDK) for the Linux desktop host lane, porting plan
LX-1. `:core-host` compiles `core-engine`'s `domain/` and `contracts/kotlin/` by srcDir and runs
the matching domain tests. Run `cd desktop && ./gradlew :core-host:test`.

- Excluded for now, with their tests: `AuditedAuthorityEngine` and `CiActionsExecutionProvider`,
  which import `dev.aarso.data` (cut in LX-4).
- `core-host/src/hostStubs/` holds a compile-only `android.icu` stub so `Segmenter.kt` compiles
  unchanged. It throws, so the JDK `BreakIterator` fallback is used. It is not ICU.
- `org.json:json` is a runtime dependency here; its licence is not on `config/allowed-licenses.json`
  and needs an owner ruling before any distributable build.
- Nothing here runs a desktop UI. That is later plan steps.
