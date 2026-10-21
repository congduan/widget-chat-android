package app.widgetchat.data

import android.content.Context

/** Small, non-sensitive preferences. Widget configuration stays in [WidgetStore]. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("widget-chat-settings", Context.MODE_PRIVATE)

    var autoRefresh: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REFRESH, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_REFRESH, value).apply() }

    companion object { private const val KEY_AUTO_REFRESH = "auto-refresh" }
}
