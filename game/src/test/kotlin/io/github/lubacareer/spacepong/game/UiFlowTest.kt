package io.github.lubacareer.spacepong.game

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UiFlowTest {
    @Test fun `interruption pauses only active match and settings returns to pause`() {
        val flow = UiFlow()
        flow.show(Page.MATCH)
        flow.interrupt()
        assertEquals(Page.PAUSE, flow.page)
        flow.openSettings()
        assertEquals(Page.SETTINGS, flow.page)
        flow.closeSettings()
        assertEquals(Page.PAUSE, flow.page)
    }

    @Test fun `interruption never dismisses a completed result`() {
        val flow = UiFlow()
        flow.show(Page.RESULTS)
        flow.interrupt()
        assertEquals(Page.RESULTS, flow.page)
    }

    @Test fun `keyboard navigation skips unavailable encounters and wraps`() {
        val flow = UiFlow()
        val enabled = listOf(true, false, true)
        assertEquals(0, flow.nextFocus(enabled, 1))
        assertEquals(2, flow.nextFocus(enabled, 1))
        assertEquals(0, flow.nextFocus(enabled, 1))
        assertEquals(2, flow.nextFocus(enabled, -1))
        assertEquals(-1, flow.nextFocus(listOf(false, false), 1))
    }

    @Test fun `changing pages clears stale keyboard selection`() {
        val flow = UiFlow()
        flow.nextFocus(listOf(true, true), 1)
        flow.nextFocus(listOf(true, true), 1)
        flow.show(Page.MAP)
        assertEquals(-1, flow.focused)
    }

    @Test fun `repeated settings action retains its original return page`() {
        val flow = UiFlow().apply { show(Page.PAUSE) }
        flow.openSettings()
        flow.openSettings()
        flow.closeSettings()
        assertEquals(Page.PAUSE, flow.page)
    }

    @Test fun `buttons rendered for the previous page cannot receive input`() {
        val settings = MenuButton("settings", 0f, 0f, 100f, 100f, "SETTINGS")
        assertEquals(listOf(settings), buttonsForPage(Page.START, Page.START, listOf(settings)))
        assertTrue(buttonsForPage(Page.START, Page.SETTINGS, listOf(settings)).isEmpty())
        assertTrue(buttonsForPage(null, Page.START, listOf(settings)).isEmpty())
    }
}
