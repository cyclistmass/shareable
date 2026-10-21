package com.mauro.wgbg

/**
 * Where the game-start screen's choices live between runs (#68, #69), as one line ([GameSetup.encode]). Saved on every
 * change (0.3.4), not only when a game starts: closing the app or going Back before Play used to lose them all. UI-free
 * (a fake [read]/[write] in tests); [of] binds it to the app's preferences.
 */
class SetupStore(private val read: () -> String?, private val write: (String) -> Unit) {
    /** The last setup saved, or null when there is none or it's damaged (the caller falls back to the defaults). */
    fun load(): GameSetup? = GameSetup.decode(read())

    fun save(setup: GameSetup) = write(setup.encode())

    companion object {
        const val KEY = "last"

        fun of(prefs: android.content.SharedPreferences) =
            SetupStore({ prefs.getString(KEY, null) }, { prefs.edit().putString(KEY, it).apply() })
    }
}
