package com.pcremote

internal enum class Page { HOME, PAIR, SETTINGS }

/** Input belongs to the visible HOME screen, including when a new Intent opens it. */
internal class PageNavigation(private val disarm: () -> Unit) {
  var page = Page.HOME
    private set

  fun goTo(destination: Page) {
    if (page == Page.HOME && destination != Page.HOME) disarm()
    page = destination
  }

  fun newIntent() {
    disarm()
    page = Page.HOME
  }
}
