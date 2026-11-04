package dev.fonebrew.watch.core

/**
 * The shape vocabulary of the wrist. **Every state WatchBrew shows is carried by one of these shapes
 * plus a word — never by hue** (the owner is red-green colour-blind; on a 1-inch screen the rule
 * matters more, not less). The presenters in this package choose a [Glyph]; the Compose layer draws
 * each as a Canvas path (`ui/GlyphIcon.kt`). Because the choice is made here, in pure Kotlin, the
 * JVM tests can pin that two different states of one family never share a shape
 * (`GlyphDistinctnessTest`).
 *
 * Deliberately NOT font characters: watch system fonts do not reliably carry symbol blocks like
 * U+2601 / U+2302, and a missing glyph renders as tofu — a state that vanishes. The phone UI's
 * register (house = on-device, cloud = watched, half-ring = mixed) is mirrored as shapes instead.
 */
enum class Glyph {
    // Task states (WatchTaskState).
    TASK_TODO,      // empty ring
    TASK_DOING,     // half-filled ring
    TASK_BLOCKED,   // square with a bar through it
    TASK_DONE,      // check mark

    // Loop-run states (StateGlyphKeys + an open-set fallback).
    LOOP_RUNNING,   // play triangle
    LOOP_COMPLETE,  // check inside a ring
    LOOP_STOPPED,   // filled square
    LOOP_FAILED,    // cross
    LOOP_UNKNOWN,   // diamond (a key this build does not know)

    // Who answered (the watched-object rule, binding rule 2, reaching the wrist).
    ON_DEVICE,      // house
    CLOUD_WATCHED,  // cloud

    // The phone link.
    LINK_CHECKING,  // dotted ring
    LINK_OK,        // two linked rings
    LINK_BROKEN,    // two rings with a slash
    LINK_MISMATCH,  // two rings with an inequality bar

    // A reply the phone refused / a reply that arrived damaged.
    NOTICE,         // triangle with a bar
}
