package dev.aarso.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state matrix is the contract Doc 00 §3.8 leans on, so it earns thorough JVM tests:
 * every case constructs and carries its fields, every combinator behaves, and the pass-through
 * cases are genuinely identity (no payload mangling).
 */
class UiStateTest {

    // --- construction: all seven states ---------------------------------------------------

    @Test fun loading_constructs() {
        val s: UiState<Int> = UiState.Loading
        assertTrue(s is UiState.Loading)
    }

    @Test fun empty_constructs_and_is_distinct_from_ready_empty_list() {
        val empty: UiState<List<String>> = UiState.Empty
        val readyEmptyList: UiState<List<String>> = UiState.Ready(emptyList())
        assertTrue(empty is UiState.Empty)
        assertTrue(readyEmptyList is UiState.Ready)
        // The useful empty and "ready over an empty list" are different states.
        assertFalse(empty == readyEmptyList)
    }

    @Test fun partial_constructs_and_carries_value_and_failed_slice() {
        val s = UiState.Partial(listOf("a", "b"), failedSlice = "cloud history")
        assertEquals(listOf("a", "b"), s.value)
        assertEquals("cloud history", s.failedSlice)
    }

    @Test fun partial_failed_slice_defaults_to_null() {
        val s = UiState.Partial(42)
        assertEquals(42, s.value)
        assertNull(s.failedSlice)
    }

    @Test fun ready_constructs_and_carries_value() {
        val s = UiState.Ready("done")
        assertEquals("done", s.value)
    }

    @Test fun error_constructs_with_cause_watched_object_and_retryable() {
        val s = UiState.Error("timeout", watchedObject = "Anthropic", retryable = true)
        assertEquals("timeout", s.cause)
        assertEquals("Anthropic", s.watchedObject)
        assertTrue(s.retryable)
    }

    @Test fun error_defaults_no_watched_object_and_retryable_true() {
        val s = UiState.Error("bad input")
        assertNull(s.watchedObject)
        assertTrue(s.retryable)
    }

    @Test fun error_can_be_non_retryable() {
        val s = UiState.Error("malformed request", retryable = false)
        assertFalse(s.retryable)
    }

    @Test fun offline_constructs_and_carries_on_device_alternative() {
        val s = UiState.Offline(onDeviceAlternative = "Use the on-device model")
        assertEquals("Use the on-device model", s.onDeviceAlternative)
    }

    @Test fun offline_alternative_defaults_to_null() {
        val s = UiState.Offline()
        assertNull(s.onDeviceAlternative)
    }

    @Test fun permission_blocked_constructs_and_carries_what_and_unblock_hint() {
        val s = UiState.PermissionBlocked(
            what = "Anthropic API key",
            unblockHint = "Add a key in Settings → Text",
        )
        assertEquals("Anthropic API key", s.what)
        assertEquals("Add a key in Settings → Text", s.unblockHint)
    }

    // --- valueOrNull ----------------------------------------------------------------------

    @Test fun valueOrNull_returns_value_for_ready() {
        assertEquals("v", UiState.Ready("v").valueOrNull())
    }

    @Test fun valueOrNull_returns_value_for_partial() {
        assertEquals("p", UiState.Partial("p", "slice").valueOrNull())
    }

    @Test fun valueOrNull_is_null_for_non_value_states() {
        assertNull((UiState.Loading as UiState<String>).valueOrNull())
        assertNull((UiState.Empty as UiState<String>).valueOrNull())
        assertNull((UiState.Error("x") as UiState<String>).valueOrNull())
        assertNull((UiState.Offline() as UiState<String>).valueOrNull())
        assertNull((UiState.PermissionBlocked("k", "h") as UiState<String>).valueOrNull())
    }

    // --- isTerminalSuccess ----------------------------------------------------------------

    @Test fun isTerminalSuccess_true_only_for_ready() {
        assertTrue(UiState.Ready(1).isTerminalSuccess)
        assertFalse(UiState.Partial(1, "s").isTerminalSuccess)
        assertFalse(UiState.Loading.isTerminalSuccess)
        assertFalse(UiState.Empty.isTerminalSuccess)
        assertFalse(UiState.Error("e").isTerminalSuccess)
        assertFalse(UiState.Offline().isTerminalSuccess)
        assertFalse(UiState.PermissionBlocked("k", "h").isTerminalSuccess)
    }

    // --- map ------------------------------------------------------------------------------

    @Test fun map_transforms_ready() {
        val s = UiState.Ready(2).map { it * 10 }
        assertEquals(UiState.Ready(20), s)
    }

    @Test fun map_transforms_partial_and_preserves_failed_slice() {
        val s = UiState.Partial(2, "cloud").map { it * 10 }
        assertTrue(s is UiState.Partial)
        s as UiState.Partial
        assertEquals(20, s.value)
        assertEquals("cloud", s.failedSlice)
    }

    @Test fun map_passes_loading_through_unchanged() {
        val src: UiState<Int> = UiState.Loading
        val out = src.map { it + 1 }
        assertSame(UiState.Loading, out)
    }

    @Test fun map_passes_empty_through_unchanged() {
        val src: UiState<Int> = UiState.Empty
        val out = src.map { it + 1 }
        assertSame(UiState.Empty, out)
    }

    @Test fun map_passes_error_through_identical() {
        val src: UiState<Int> = UiState.Error("boom", "Gemini", retryable = false)
        val out = src.map { it + 1 }
        assertSame(src, out)
    }

    @Test fun map_passes_offline_through_identical() {
        val src: UiState<Int> = UiState.Offline("on-device model")
        val out = src.map { it + 1 }
        assertSame(src, out)
    }

    @Test fun map_passes_permission_blocked_through_identical() {
        val src: UiState<Int> = UiState.PermissionBlocked("key", "add it")
        val out = src.map { it + 1 }
        assertSame(src, out)
    }

    // --- fromResult -----------------------------------------------------------------------

    @Test fun fromResult_success_becomes_ready() {
        val s = fromResult(Result.success(listOf(1, 2, 3)))
        assertEquals(UiState.Ready(listOf(1, 2, 3)), s)
    }

    @Test fun fromResult_success_with_emptyWhen_becomes_empty() {
        val s = fromResult(Result.success(emptyList<Int>())) { it.isEmpty() }
        assertTrue(s is UiState.Empty)
    }

    @Test fun fromResult_success_non_empty_stays_ready_under_emptyWhen() {
        val s = fromResult(Result.success(listOf(1))) { it.isEmpty() }
        assertEquals(UiState.Ready(listOf(1)), s)
    }

    @Test fun fromResult_failure_becomes_error_with_message_and_retryable() {
        val s = fromResult(Result.failure<Int>(IllegalStateException("nope")))
        assertTrue(s is UiState.Error)
        s as UiState.Error
        assertEquals("nope", s.cause)
        assertTrue(s.retryable)
    }

    @Test fun fromResult_failure_blank_message_falls_back_to_class_name() {
        val s = fromResult(Result.failure<Int>(RuntimeException()))
        assertTrue(s is UiState.Error)
        s as UiState.Error
        assertEquals("RuntimeException", s.cause)
    }

    // --- fold -----------------------------------------------------------------------------

    private fun <T> tag(s: UiState<T>): String = s.fold(
        onLoading = { "loading" },
        onEmpty = { "empty" },
        onPartial = { value, slice -> "partial:$value:$slice" },
        onReady = { value -> "ready:$value" },
        onError = { cause, watched, retry -> "error:$cause:$watched:$retry" },
        onOffline = { alt -> "offline:$alt" },
        onPermissionBlocked = { what, hint -> "blocked:$what:$hint" },
    )

    @Test fun fold_dispatches_loading() {
        assertEquals("loading", tag(UiState.Loading as UiState<Int>))
    }

    @Test fun fold_dispatches_empty() {
        assertEquals("empty", tag(UiState.Empty as UiState<Int>))
    }

    @Test fun fold_dispatches_partial_with_fields() {
        assertEquals("partial:7:slc", tag(UiState.Partial(7, "slc")))
    }

    @Test fun fold_dispatches_ready_with_value() {
        assertEquals("ready:9", tag(UiState.Ready(9)))
    }

    @Test fun fold_dispatches_error_with_fields() {
        assertEquals(
            "error:down:OpenAI:false",
            tag(UiState.Error("down", "OpenAI", retryable = false) as UiState<Int>),
        )
    }

    @Test fun fold_dispatches_offline_with_alternative() {
        assertEquals("offline:local", tag(UiState.Offline("local") as UiState<Int>))
    }

    @Test fun fold_dispatches_permission_blocked_with_fields() {
        assertEquals(
            "blocked:key:connect it",
            tag(UiState.PermissionBlocked("key", "connect it") as UiState<Int>),
        )
    }
}
