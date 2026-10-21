package app.widgetchat.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language preference. Applied by wrapping contexts, so it works on API 31+ without
 * relying on the per-app language service added in Android 13.
 */
object AppLocale {
    const val SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    private const val PREFS = "app-settings"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    internal fun peek(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM
        android.util.Log.i("AppLocale", "value=$value all=${prefs.all} path=${context.filesDir.parentFile}/shared_prefs")
        return value
    }

    fun set(context: Context, tag: String) {
        val value = if (tag == CHINESE || tag == ENGLISH) tag else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, value).apply()
    }

    /** Returns [base] or a copy of it whose resources use the selected language. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        if (tag != CHINESE && tag != ENGLISH) return base
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /** Shorthand for components that render outside an Activity, such as app widgets. */
    fun localized(context: Context): Context = wrap(context)
}
