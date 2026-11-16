package app.hermes.channels

/**
 * Aurus Plus: extras that are not part of the open app. This is the open app's side of them: it has none, and
 * everything here answers "nothing". A build with the extras puts its own file in this one's place
 * (see `app/build.gradle.kts`).
 */
object Plus {
    const val PRESENT = false

    /** the colour of what is in focus; 0 = the app's own */
    fun accent(prefs: Prefs): Int = 0

    /** the tab of the extras in the settings, if there is one; [restyle] draws the app again after a change of its look */
    fun tab(a: MainActivity, ui: Ui, prefs: Prefs, restyle: () -> Unit, refocus: () -> Unit): Page.Tab? = null
}
