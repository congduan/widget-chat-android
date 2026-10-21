package app.widgetchat.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.widgetchat.R
import app.widgetchat.data.WidgetDataScheduler
import app.widgetchat.data.WidgetStore
import app.widgetchat.i18n.AppLocale
import app.widgetchat.widget.ChatWidgetProvider

class ConfigureActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!ChatWidgetProvider.owns(this, id)) { finish(); return }
        val store = WidgetStore(this)
        val strings = AppLocale.localized(this)
        val state = runCatching { store.read() }.getOrElse { finish(); return }
        setContent {
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalConfiguration provides strings.resources.configuration
            ) { WidgetTheme {
                var selected by remember { mutableStateOf(state.bindings[id.toString()] ?: state.records.first().id) }
                var error by remember { mutableStateOf<String?>(null) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.configure_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.configure_instance, id))
                        state.records.forEach { record ->
                            FilterChip(selected = selected == record.id, onClick = { selected = record.id }, label = { Text(record.spec.name) })
                        }
                        val record = state.records.first { it.id == selected }
                        WidgetPreview(record.spec, recordId = record.id)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            try {
                                store.bind(id, selected)
                                WidgetDataScheduler.sync(this@ConfigureActivity, record)
                                WidgetDataScheduler.requestNow(this@ConfigureActivity, record.id, record.spec)
                                ChatWidgetProvider.update(this@ConfigureActivity, id)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            } catch (e: Exception) {
                                error = strings.getString(R.string.configure_save_failed, e.message ?: e.javaClass.simpleName)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.configure_apply)) }
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.configure_cancel)) }
                    }
                }
            } }
        }
    }
}
