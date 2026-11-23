package io.github.lubacareer.spacepong.game

enum class Page { START, CLASSIC, BUILDINGS, MAP, UPGRADES, SETTINGS, MATCH, PAUSE, RESULTS, COMPLETE, CONFIRM_NEW, CONFIRM_BRICKS }

/** Navigation never resumes a match implicitly after an OS interruption or settings visit. */
class UiFlow {
    var page = Page.START
        private set
    var focused = -1
        private set
    private var settingsReturn = Page.START

    fun show(next: Page) {
        page = next
        focused = -1
    }

    fun interrupt() {
        if (page == Page.MATCH) show(Page.PAUSE)
    }

    fun openSettings() {
        if (page == Page.SETTINGS) return
        settingsReturn = page
        show(Page.SETTINGS)
    }

    fun closeSettings() {
        show(settingsReturn)
    }

    fun nextFocus(enabled: List<Boolean>, direction: Int): Int {
        if (enabled.none { it }) {
            focused = -1
            return focused
        }
        val step = if (direction < 0) -1 else 1
        var candidate = if (focused !in enabled.indices) {
            if (step > 0) -1 else 0
        } else {
            focused
        }
        repeat(enabled.size) {
            candidate = (candidate + step + enabled.size) % enabled.size
            if (enabled[candidate]) {
                focused = candidate
                return focused
            }
        }
        return -1
    }
}

internal fun buttonsForPage(renderedPage: Page?, activePage: Page, buttons: List<MenuButton>): List<MenuButton> = if (renderedPage == activePage) buttons else emptyList()
