package dev.fonebrew.domain.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopEmptyStatePresenterTest {

    @Test
    fun `shows when there are no saved loops and the canvas has no nodes`() {
        assertTrue(LoopEmptyStatePresenter.shouldShow(savedLoopCount = 0, canvasNodeCount = 0))
    }

    @Test
    fun `hides when a loop is saved even if the canvas is momentarily empty`() {
        assertFalse(LoopEmptyStatePresenter.shouldShow(savedLoopCount = 1, canvasNodeCount = 0))
    }

    @Test
    fun `hides when the canvas has nodes even with zero saved loops`() {
        assertFalse(LoopEmptyStatePresenter.shouldShow(savedLoopCount = 0, canvasNodeCount = 5))
    }

    @Test
    fun `hides once both a loop is saved and the canvas is populated`() {
        assertFalse(LoopEmptyStatePresenter.shouldShow(savedLoopCount = 3, canvasNodeCount = 5))
    }
}
