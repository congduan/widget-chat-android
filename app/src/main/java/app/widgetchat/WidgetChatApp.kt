package app.widgetchat

import android.app.Application
import android.content.Context
import app.widgetchat.i18n.AppLocale

class WidgetChatApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }
}
