package dev.fonebrew.watchbridge

/**
 * # `:watch-bridge` — the WatchBrew wire contract (v1)
 *
 * The single source of truth for what the phone (Fonebrew, via the relay in `:core-engine`) and
 * the wrist (WatchBrew, the Wear OS app) say to each other. Both sides consume this module; nothing
 * else does. The shape of the conversation is `docs/design/watchbrew.md` §3 (the v1 surfaces:
 * Ask, Tasks, Loops, plus the handshake); the module's place in the three-piece architecture is §2.
 *
 * ## Pure Kotlin, BY DESIGN — for a later Kotlin Multiplatform (`watchosArm64`) conversion
 *
 * This module has **no Android dependency** and must never gain one: no `android.*`, no `androidx.*`,
 * no Play Services (the Wearable Data Layer lives in the two *consumers*, never here), no Compose,
 * no coroutines, no `dev.fonebrew.*` import from any other module. That is not tidiness; it is the
 * Apple Watch plan. `docs/design/watchbrew.md` §5 keeps `:watch-bridge` pure Kotlin "precisely so a
 * Kotlin Multiplatform conversion (`watchosArm64` target, the porting program's KMP pins:
 * Kotlin 2.1.20 / 2.2.21) can carry the contract to Swift later without a rewrite." A purity test
 * in this module's test source set (`ModulePurityTest`) enforces the import and classpath rule.
 *
 * Honest scope of that claim: the *types, invariants and wire shapes* are already
 * multiplatform-clean. Two JVM-only pieces remain and are a named follow-up, not a hidden gap:
 * the JSON layer is `org.json` (a JVM/Android platform class, `compileOnly` here), and the strict
 * UTF-8 byte decoder in [WatchBridgeCodec] uses `java.nio.charset`. The KMP conversion swaps both
 * behind the same [WatchBridgeCodec] facade; nothing in the public message model changes. That
 * conversion is **not** done in v1 and has not been attempted.
 *
 * ## The wire format
 *
 * Every message travels as one UTF-8 JSON object in the `domain/contracts` `EnvelopeCodec` idiom — a
 * versioned envelope around a typed payload:
 *
 * ```
 * {"contract":"watchbrew","v":1,"type":"AskChunk","payload":{ ... }}
 * ```
 *
 * - `contract` — the literal [CONTRACT_NAME]; anything else is not ours ([DecodeResult.Malformed]).
 * - `v` — the contract version the *sender speaks in this message*; see [CONTRACT_VERSION].
 * - `type` — the message, hard-coded per class (never derived from a class name, so R8 on a
 *   consumer cannot change the wire).
 * - `payload` — the message's own fields. Present only for a version this build understands; a
 *   payload from another version is never interpreted (see below).
 *
 * ## Decode contract (the part both sides rely on)
 *
 * [WatchBridgeCodec.decode] **never throws** and returns exactly one of three typed outcomes:
 *
 * - [DecodeResult.Decoded] — the whole message parsed, every required field present with exactly
 *   the right JSON type, every cross-field invariant holding.
 * - [DecodeResult.Incompatible] — well-formed envelope, but `v` is not [CONTRACT_VERSION]. The
 *   payload is deliberately **not** parsed (its shape is another version's business). The result
 *   says which side is newer so the UI can say "update the watch app" or "update Fonebrew" instead
 *   of failing mysteriously.
 * - [DecodeResult.Malformed] — anything else. **Decode is all-or-nothing:** there is no partial
 *   message, no defaulted field, no best-effort salvage. A missing `estimated` flag does not
 *   default to `false` (that would claim a number is real); a truncated message does not yield its
 *   readable prefix.
 *
 * Strictness is deliberately independent of the platform's `org.json`: Android's parser is lenient
 * (single quotes, unquoted keys, comments, trailing data) and its accessors coerce (`getString` on
 * a number), while the reference parser used in JVM tests is stricter. The codec therefore runs its
 * own RFC 8259 validator first and reads fields only through type-exact accessors, so a message that
 * is rejected in the JVM tests is rejected on the phone and the watch too. Unknown extra fields are
 * ignored (additive, same-version forward compatibility; messages are one-hop and never re-encoded,
 * so unlike `EnvelopeCodec` there is nothing to preserve them *for*).
 *
 * ## Honesty rules this contract encodes
 *
 * - **No invented numbers.** [Usage] and [Spend] carry an explicit `estimated` flag with *no*
 *   default; token counts are nullable so "the engine reported nothing" is representable as
 *   *unknown*, never as a defaulted `0`. [AskChunk.usage] exists on the terminal chunk and only
 *   there.
 * - **The wrist never hides who answered.** Every [AskChunk] carries `engineId` and
 *   `watchedObject`; `true` means a cloud provider answered and the watch must show the
 *   watched-object glyph exactly like the phone UI.
 * - **No credentials.** There is no field anywhere in this contract that could carry an API key,
 *   and none may be added: keys live in the phone's Keystore only (the project's binding rule 5, extended to the
 *   wrist by the design doc §1).
 * - **No color on the wire.** Loop state travels as a glyph *key* ([StateGlyphKeys]); the watch maps
 *   it to a shape. The contract carries no hue of any kind (the owner is red-green colorblind;
 *   glyph/label/shape is primary).
 * - **Single writer.** The watch asks; the phone decides. [TaskToggleRequest] states a desired
 *   `done` value (set, not flip, so a retry is harmless) and [TaskToggleResult] reports what the
 *   phone's store actually holds.
 *
 * ## Verification honesty
 *
 * Everything here is machine-verified on the JVM only (round-trips, malformed-input rejection,
 * version handshake, invariants). Nothing about it has been run between a real phone and a real
 * watch — the build container has neither, nor an emulator. Pairing, Data Layer delivery, and
 * streaming feel are owner-verified (`docs/design/watchbrew.md` §7).
 */
public object WatchBridge {
    /** The literal value of the envelope's `contract` field. */
    public const val CONTRACT_NAME: String = "watchbrew"

    /**
     * The contract version this build speaks. Strict equality in v1: there is no min/max range and
     * no negotiation (a range would be scope the design does not ask for). Any change that an old
     * reader could misread bumps this; purely additive optional fields do not (readers ignore
     * unknown fields).
     */
    public const val CONTRACT_VERSION: Int = 1
}
