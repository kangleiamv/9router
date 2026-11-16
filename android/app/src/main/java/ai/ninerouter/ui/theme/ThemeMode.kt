package ai.ninerouter.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * Appearance preference, stored outside the database so the activity can pick
 * it before the first frame. Changing it recreates the activity, which is the
 * only reliable way to re-read the palette getters in every screen.
 */
object ThemeMode {
    const val KEY = "theme_mode"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "nine_router_ui"

    fun stored(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM)
        return if (value in setOf(SYSTEM, LIGHT, DARK)) value!! else SYSTEM
    }

    fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun isDark(context: Context): Boolean = when (stored(context)) {
        LIGHT -> false
        DARK -> true
        else -> systemDark(context)
    }

    /** Persist [mode] and recreate [context] when it is an activity. */
    fun set(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, if (mode in setOf(SYSTEM, LIGHT, DARK)) mode else SYSTEM)
            .apply()
        (context as? Activity)?.recreate()
    }

    /** Quick toggle used by the drawer: dark becomes light and vice versa. */
    fun toggle(context: Context) {
        set(context, if (isDark(context)) LIGHT else DARK)
    }
}
