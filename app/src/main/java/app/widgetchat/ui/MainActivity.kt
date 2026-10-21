package app.widgetchat.ui

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.widgetchat.R
import app.widgetchat.ai.ChatTurn
import app.widgetchat.ai.WidgetAssistant
import app.widgetchat.data.AppSettings
import app.widgetchat.data.DataCache
import app.widgetchat.data.ModelConfig
import app.widgetchat.data.ModelPresets
import app.widgetchat.data.ModelSettings
import app.widgetchat.data.SavedWidget
import app.widgetchat.data.WidgetDataRefresher
import app.widgetchat.data.WidgetDataScheduler
import app.widgetchat.data.WidgetStore
import app.widgetchat.i18n.AppLocale
import app.widgetchat.model.*
import app.widgetchat.render.WidgetRenderer
import app.widgetchat.render.WidgetSizing
import app.widgetchat.widget.ChatWidgetProvider
import app.widgetchat.widget.PinReceiver
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Canvas = Color(0xFF10151D)
private val Mint = Color(0xFFA8F0CE)

@Composable
fun WidgetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Canvas, surface = Color(0xFF19222F), secondary = Color(0xFFB8C9E3)), content = content)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { ChatWidgetProvider.updateAll(this) }.onFailure {
            android.widget.Toast.makeText(this, getString(R.string.toast_refresh_failed, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show()
        }
        AppLocale.peek(this)
        setContent {
            val localized = remember { AppLocale.localized(this@MainActivity) }
            CompositionLocalProvider(
                // Kept explicit: after Activity.recreate() the default owner can be missing,
                // which makes rememberLauncherForActivityResult throw and leaves a blank window.
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration
            ) { WidgetTheme { Studio(recordIdFrom(intent)) } }
        }
    }

    /** Accepts both an explicit extra and widgetchat://edit/<recordId>/<widgetId>. */
    private fun recordIdFrom(intent: Intent?): String? {
        intent?.getStringExtra("recordId")?.takeIf { it.isNotBlank() }?.let { return it }
        val data = intent?.data ?: return null
        return data.takeIf { it.scheme == "widgetchat" && it.host == "edit" }?.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}

@Composable
fun WidgetPreview(spec: WidgetSpec, height: Int = 200, refreshKey: Int = 0, width: Float? = null, recordId: String? = null) {
    // Render with the host activity context, not the language-wrapped one.
    val context = LocalView.current.context
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val previewWidth = if (width == null) maxWidth else minOf(width.dp, maxWidth)
        AndroidView(
            modifier = Modifier.width(previewWidth).height(height.dp),
            factory = { FrameLayout(it) },
            update = { frame ->
                refreshKey.hashCode()
                frame.removeAllViews()
                val view = WidgetRenderer.render(
                    context, spec, recordId,
                    width = previewWidth.value, height = height.toFloat(), preview = true
                ).apply(context, frame)
                frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )
    }
}

@Composable
private fun LanguagePicker(onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = AppLocale.current(LocalContext.current)
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                when (current) {
                    AppLocale.CHINESE -> stringResource(R.string.language_zh)
                    AppLocale.ENGLISH -> stringResource(R.string.language_en)
                    else -> stringResource(R.string.language_system)
                }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                AppLocale.SYSTEM to R.string.language_system,
                AppLocale.CHINESE to R.string.language_zh,
                AppLocale.ENGLISH to R.string.language_en
            ).forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = { expanded = false; onSelect(tag) }
                )
            }
        }
    }
}

private const val TAB_WIDGETS = 0
private const val TAB_TEMPLATES = 1
private const val TAB_CHAT = 2
private const val TAB_SETTINGS = 3

/** Top bar, title, subtitle and model chips live inside the chat list, before the messages. */
private const val CHAT_HEADER_ITEMS = 4

@Composable
private fun Studio(initialId: String?) {
    val context = LocalContext.current
    // LocalContext is the language-wrapped Context, so the host Activity comes from the View.
    val hostActivity = LocalView.current.context as? Activity
    val scope = rememberCoroutineScope()
    val strings = remember(context) { AppLocale.localized(context) }
    val store = remember { WidgetStore(context) }
    val settings = remember { AppSettings(context) }
    val cache = remember { DataCache(context) }
    val modelSettings = remember { ModelSettings(context) }
    val initial = remember { runCatching { store.read() } }
    if (initial.isFailure) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.error_library), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.error_library_kept))
            Text(initial.exceptionOrNull()?.message ?: stringResource(R.string.error_unknown))
        } }
        return
    }
    var records by remember { mutableStateOf(initial.getOrThrow().records) }
    val first = records.find { it.id == initialId } ?: records.first()
    var selectedId by rememberSaveable { mutableStateOf<String?>(first.id) }
    var draft by rememberSaveable { mutableStateOf(SpecCodec.encode(first.spec)) }
    var preview by remember { mutableStateOf(runCatching { SpecCodec.decode(draft) }.getOrDefault(first.spec)) }
    var status by remember { mutableStateOf(strings.getString(R.string.status_idle)) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var previewHeight by rememberSaveable { mutableIntStateOf(200) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var followDesktop by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_WIDGETS) }
    var autoRefresh by rememberSaveable { mutableStateOf(settings.autoRefresh) }
    var cacheBytes by remember { mutableLongStateOf(cache.sizeBytes()) }
    var modelConfig by remember { mutableStateOf(modelSettings.read()) }
    var modelProfiles by remember { mutableStateOf(modelSettings.profiles()) }
    var modelForm by remember { mutableStateOf(ModelForm.from(modelConfig)) }
    val chatMessages = remember { mutableStateListOf<ChatTurn>() }
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }
    var pendingSpec by remember { mutableStateOf<WidgetSpec?>(null) }
    val widgetsScroll = rememberScrollState()
    val templatesScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val hostSize = remember(selectedId, refreshKey, records) {
        val state = store.read()
        val manager = AppWidgetManager.getInstance(context)
        val id = manager.getAppWidgetIds(ComponentName(context, ChatWidgetProvider::class.java))
            .firstOrNull { state.bindings[it.toString()] == selectedId }
        id?.let {
            val sizes = WidgetSizing.sizes(manager.getAppWidgetOptions(it))
            if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                sizes.minByOrNull { size -> size.width } else sizes.maxByOrNull { size -> size.width }
        }
    }
    val shownHeight = if (followDesktop && hostSize != null) hostSize.height.toInt().coerceIn(40, 600) else previewHeight
    val dirty = records.find { it.id == selectedId }?.let { SpecCodec.encode(it.spec) != draft } ?: true

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WidgetDataScheduler.syncAll(context) }
    }
    LaunchedEffect(tab) {
        if (tab == TAB_SETTINGS) cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
    }

    fun select(record: SavedWidget) {
        selectedId = record.id; draft = SpecCodec.encode(record.spec); preview = record.spec
        status = strings.getString(R.string.status_selected, record.spec.name)
    }
    fun save(copy: Boolean = false): SavedWidget {
        val parsed = SpecCodec.decode(draft)
        val record = store.save(if (copy) null else selectedId, parsed)
        records = store.read().records
        selectedId = record.id; draft = SpecCodec.encode(record.spec); preview = record.spec
        WidgetDataScheduler.sync(context, record)
        WidgetDataScheduler.requestNow(context, record.id, record.spec)
        ChatWidgetProvider.updateAll(context)
        return record
    }

    fun refreshData() {
        val record = records.find { it.id == selectedId }
        if (record == null) { status = strings.getString(R.string.status_save_first); refreshKey++; return }
        scope.launch {
            status = strings.getString(R.string.status_refreshing)
            val outcome = runCatching {
                withContext(Dispatchers.IO) { WidgetDataRefresher.refresh(context, record.id, preview, force = true) }
            }
            if (!dirty) ChatWidgetProvider.updateRecord(context, record.id)
            refreshKey++
            status = outcome.fold(
                { result ->
                    when {
                        result.total == 0 -> strings.getString(R.string.status_refreshed_local)
                        result.failures == 0 -> strings.getString(R.string.status_refreshed)
                        else -> strings.getString(R.string.status_refresh_partial, result.failures, result.total)
                    }
                },
                { strings.getString(R.string.status_refresh_failed, it.message ?: it.javaClass.simpleName) }
            )
        }
    }
    fun attempt(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            status = strings.getString(R.string.status_operation_failed, e.message ?: e.javaClass.simpleName)
        }
    }
    fun saveModel() {
        val form = modelForm
        when {
            form.model.isBlank() -> status = strings.getString(R.string.status_model_missing_name)
            !form.baseUrl.trim().startsWith("https://") -> status = strings.getString(R.string.status_model_invalid_url)
            else -> {
                val next = ModelConfig(
                    provider = form.provider,
                    baseUrl = form.baseUrl.trim().trimEnd('/'),
                    model = form.model.trim(),
                    apiKey = form.apiKey.trim().ifBlank { modelConfig.apiKey }
                )
                runCatching { modelSettings.save(next) }.fold(
                    onSuccess = {
                        modelConfig = next
                        modelProfiles = modelSettings.profiles()
                        modelForm = form.copy(apiKey = "")
                        status = strings.getString(R.string.status_model_saved)
                    },
                    onFailure = { status = strings.getString(R.string.chat_failed, it.message ?: it.javaClass.simpleName) }
                )
            }
        }
    }
    fun sendChat() {
        val prompt = chatInput.trim()
        if (prompt.isEmpty() || chatBusy) return
        if (!modelConfig.isConfigured) {
            status = strings.getString(R.string.chat_not_configured)
            tab = TAB_SETTINGS
            return
        }
        chatMessages += ChatTurn.user(prompt)
        chatInput = ""
        chatBusy = true
        status = strings.getString(R.string.chat_generating)
        val raw = StringBuilder()
        var replyIndex = -1
        fun render(text: String) {
            val value = text.ifEmpty { "…" }
            if (replyIndex < 0) {
                chatMessages += ChatTurn.assistant(value)
                replyIndex = chatMessages.lastIndex
            } else {
                chatMessages[replyIndex] = ChatTurn.assistant(value)
            }
        }
        val history = chatMessages.toList()
        scope.launch {
            val outcome = runCatching {
                WidgetAssistant.stream(modelConfig, history, preview) { chunk ->
                    raw.append(chunk)
                    render(streamPreview(raw.toString()))
                }
            }
            chatBusy = false
            outcome.fold(
                onSuccess = { reply ->
                    val finalText = reply.text.ifBlank { streamPreview(raw.toString()) }
                    if (finalText.isNotBlank()) render(finalText)
                    when {
                        reply.spec != null -> {
                            pendingSpec = reply.spec
                            status = strings.getString(R.string.chat_spec_ready)
                        }
                        reply.specError != null -> {
                            val message = strings.getString(R.string.chat_invalid_spec, reply.specError)
                            render(message)
                            status = message
                        }
                        else -> status = strings.getString(R.string.chat_reply_only)
                    }
                },
                onFailure = {
                    val message = strings.getString(R.string.chat_failed, it.message ?: it.javaClass.simpleName)
                    render(message)
                    status = message
                }
            )
        }
    }
    fun applyPendingSpec() {
        val spec = pendingSpec ?: return
        pendingSpec = null
        selectedId = null
        draft = SpecCodec.encode(spec)
        preview = spec
        status = strings.getString(R.string.status_applied_ai)
        tab = TAB_WIDGETS
        scope.launch { widgetsScroll.scrollTo(0) }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) attempt {
            val raw = SpecCodec.encode(SpecCodec.decode(draft))
            context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                ?: error(strings.getString(R.string.error_write_file))
            status = strings.getString(R.string.status_exported)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (result.size() <= SpecCodec.MAX_JSON_BYTES) {
                    val n = stream.read(buffer, 0, minOf(buffer.size, SpecCodec.MAX_JSON_BYTES + 1 - result.size()))
                    if (n < 0) break
                    result.write(buffer, 0, n)
                }
                result.toByteArray() } ?: error(strings.getString(R.string.error_read_file))
            require(bytes.size <= SpecCodec.MAX_JSON_BYTES) { strings.getString(R.string.error_too_large) }
            val spec = SpecCodec.decode(bytes.toString(Charsets.UTF_8))
            selectedId = null; draft = SpecCodec.encode(spec); preview = spec
            status = strings.getString(R.string.status_imported)
        }
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Scaffold(
        containerColor = Canvas,
        // Only the status bar is handled here; the bottom bar pads itself, and the keyboard
        // padding is applied per tab so it never stacks with the navigation bar height.
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            if (!imeVisible) {
                NavigationBar(containerColor = Color(0xFF141C26)) {
                    StudioTab(TAB_WIDGETS, tab, R.string.tab_widgets, Icons.Filled.Home) { tab = it }
                    StudioTab(TAB_TEMPLATES, tab, R.string.tab_templates, Icons.Filled.Star) { tab = it }
                    StudioTab(TAB_CHAT, tab, R.string.tab_chat, Icons.AutoMirrored.Filled.Send) { tab = it }
                    StudioTab(TAB_SETTINGS, tab, R.string.tab_settings, Icons.Filled.Settings) { tab = it }
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                TAB_TEMPLATES -> TemplatesTab(strings, templatesScroll) { spec ->
                    selectedId = null
                    draft = SpecCodec.encode(spec)
                    preview = spec
                    status = strings.getString(R.string.status_template_loaded, spec.name)
                    tab = TAB_WIDGETS
                    scope.launch { widgetsScroll.scrollTo(0) }
                }
                TAB_CHAT -> ChatTab(
                    messages = chatMessages,
                    input = chatInput,
                    busy = chatBusy,
                    pendingSpec = pendingSpec,
                    status = status,
                    profiles = modelProfiles,
                    activeConfig = modelConfig,
                    onSelectProfile = { profile ->
                        modelConfig = profile
                        modelForm = ModelForm.from(profile)
                        modelSettings.save(profile)
                        modelProfiles = modelSettings.profiles()
                        status = strings.getString(
                            R.string.status_model_switched,
                            strings.getString(ModelPresets.labelRes(profile.provider)) + " · " + profile.model
                        )
                    },
                    onManageModels = { tab = TAB_SETTINGS },
                    onInputChange = { chatInput = it },
                    onSend = { sendChat() },
                    onApplySpec = { applyPendingSpec() }
                )
                TAB_SETTINGS -> SettingsTab(
                    scroll = settingsScroll,
                    versionName = versionName,
                    cacheBytes = cacheBytes,
                    autoRefresh = autoRefresh,
                    status = status,
                    modelForm = modelForm,
                    hasSavedKey = modelConfig.apiKey.isNotBlank(),
                    onModelFormChange = { modelForm = it },
                    onSaveModel = { saveModel() },
                    onClearKey = {
                        modelSettings.clearKey()
                        modelConfig = modelConfig.copy(apiKey = "")
                        modelProfiles = modelSettings.profiles()
                        modelForm = modelForm.copy(apiKey = "")
                        status = strings.getString(R.string.status_key_cleared)
                    },
                    onAutoRefresh = { enabled ->
                        autoRefresh = enabled
                        settings.autoRefresh = enabled
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (enabled) WidgetDataScheduler.syncAll(context) else WidgetDataScheduler.cancelAll(context)
                            }
                            status = strings.getString(if (enabled) R.string.status_auto_refresh_on else R.string.status_auto_refresh_off)
                        }
                    },
                    onRefreshBound = {
                        scope.launch {
                            withContext(Dispatchers.IO) { WidgetDataScheduler.refreshBoundNow(context) }
                            status = strings.getString(R.string.status_bound_refreshed)
                        }
                    },
                    onClearCache = {
                        scope.launch {
                            withContext(Dispatchers.IO) { cache.clearAll() }
                            cacheBytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
                            refreshKey++
                            status = strings.getString(R.string.status_cache_cleared)
                        }
                    },
                    onLanguage = { tag ->
                        AppLocale.set(context, tag)
                        hostActivity?.recreate()
                    }
                )
                else -> Column(Modifier.fillMaxSize().imePadding()
                    .verticalScroll(widgetsScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StudioTopBar()
                    Text(stringResource(R.string.hero_title), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.hero_subtitle), color = Color(0xFFADBDCF))

                    Text(stringResource(R.string.section_mine), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        records.forEach { record ->
                            FilterChip(selected = record.id == selectedId, onClick = { select(record) }, label = { Text(record.spec.name) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            attempt {
                                val spec = Templates.all(context = context).first()
                                selectedId = null; draft = SpecCodec.encode(spec); preview = spec
                                status = strings.getString(R.string.status_new_draft)
                            }
                        }) { Text(stringResource(R.string.action_new_draft)) }
                        TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.action_import)) }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.section_preview), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (dirty) R.string.state_dirty else R.string.state_saved),
                            color = if (dirty) Color(0xFFFFC58E) else Mint
                        )
                    }
                    if (hostSize != null) Text(
                        stringResource(R.string.host_size, hostSize.width.toInt(), hostSize.height.toInt()),
                        style = MaterialTheme.typography.labelMedium, color = Mint
                    )
                    WidgetPreview(preview, shownHeight, refreshKey, if (followDesktop) hostSize?.width else null, selectedId)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hostSize != null) FilterChip(selected = followDesktop, onClick = { followDesktop = true; refreshKey++ }, label = { Text(stringResource(R.string.chip_follow_host)) })
                        FilterChip(selected = !followDesktop && previewHeight == 100, onClick = { followDesktop = false; previewHeight = 100 }, label = { Text(stringResource(R.string.chip_small)) })
                        FilterChip(selected = (!followDesktop || hostSize == null) && previewHeight == 200, onClick = { followDesktop = false; previewHeight = 200 }, label = { Text(stringResource(R.string.chip_standard)) })
                        TextButton(onClick = { refreshData() }) { Text(stringResource(R.string.action_refresh_data)) }
                    }
                    Text(stringResource(R.string.fit_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A3BA))

                    TextButton(onClick = { editorOpen = !editorOpen }) {
                        Text(stringResource(if (editorOpen) R.string.section_json_collapse else R.string.section_json))
                    }
                    if (editorOpen) {
                        Text(stringResource(R.string.json_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().height(300.dp),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), label = { Text(stringResource(R.string.json_label)) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                attempt {
                                    preview = SpecCodec.decode(draft)
                                    status = strings.getString(R.string.status_preview_ok)
                                }
                            }) { Text(stringResource(R.string.action_validate)) }
                            TextButton(onClick = {
                                attempt {
                                    SpecCodec.decode(draft)
                                    exporter.launch("widget-spec.json")
                                }
                            }) { Text(stringResource(R.string.action_export)) }
                        }
                    }
                    Text(status, color = Mint, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            attempt { save(); status = strings.getString(R.string.status_saved) }
                        }) { Text(stringResource(R.string.action_save)) }
                        OutlinedButton(onClick = {
                            attempt { save(true); status = strings.getString(R.string.status_saved_copy) }
                        }) { Text(stringResource(R.string.action_save_copy)) }
                    }
                    TextButton(enabled = records.find { it.id == selectedId }?.history?.isNotEmpty() == true, onClick = {
                        attempt {
                            select(store.undo(requireNotNull(selectedId)))
                            records = store.read().records
                            ChatWidgetProvider.updateAll(context)
                            status = strings.getString(R.string.status_undone)
                        }
                    }) { Text(stringResource(R.string.action_undo)) }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        attempt {
                            val manager = AppWidgetManager.getInstance(context)
                            if (!manager.isRequestPinAppWidgetSupported) {
                                status = strings.getString(R.string.status_pin_unsupported)
                            } else {
                                val record = if (dirty) save() else records.first { it.id == selectedId }
                                val callback = Intent(context, PinReceiver::class.java).apply {
                                    data = Uri.parse("widgetchat://pin/${UUID.randomUUID()}")
                                    putExtra("recordId", record.id)
                                }
                                val pending = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
                                val accepted = manager.requestPinAppWidget(ComponentName(context, ChatWidgetProvider::class.java), null, pending)
                                status = strings.getString(if (accepted) R.string.status_pin_requested else R.string.status_pin_rejected)
                            }
                        }
                    }) { Text(stringResource(R.string.action_add_widget)) }
                    Text(stringResource(R.string.footer_note), modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF90A3BA))
                }
            }
        }
    }
}

@Composable
private fun RowScope.StudioTab(index: Int, current: Int, label: Int, icon: ImageVector, onSelect: (Int) -> Unit) {
    NavigationBarItem(
        selected = current == index,
        onClick = { onSelect(index) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1) }
    )
}

@Composable
private fun StudioTopBar() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("WIDGET CHAT", style = MaterialTheme.typography.labelLarge, color = Mint)
        Text("ANDROID / P0", style = MaterialTheme.typography.labelSmall, color = Color(0xFF90A3BA))
    }
}

@Composable
private fun TemplatesTab(strings: Context, scroll: ScrollState, onUse: (WidgetSpec) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StudioTopBar()
        Text(stringResource(R.string.templates_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.templates_subtitle), color = Color(0xFFADBDCF))
        Templates.all(context = strings).forEach { spec ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium)
                    WidgetPreview(spec, height = 140)
                    OutlinedButton(onClick = { onUse(spec) }) { Text(stringResource(R.string.action_use_template)) }
                }
            }
        }
    }
}

@Composable
private fun ChatTab(
    messages: List<ChatTurn>,
    input: String,
    busy: Boolean,
    pendingSpec: WidgetSpec?,
    status: String,
    profiles: List<ModelConfig>,
    activeConfig: ModelConfig,
    onSelectProfile: (ModelConfig) -> Unit,
    onManageModels: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onApplySpec: () -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val tailLength = messages.lastOrNull()?.content?.length ?: 0
    val lastItemIndex = CHAT_HEADER_ITEMS + messages.lastIndex
    LaunchedEffect(messages.size, tailLength, busy, imeBottom) {
        if (messages.isNotEmpty()) listState.scrollToItem(lastItemIndex)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { StudioTopBar() }
            item { Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineLarge) }
            item { Text(stringResource(R.string.chat_subtitle), color = Color(0xFFADBDCF)) }
            item { ModelChipsRow(profiles, activeConfig, onSelectProfile, onManageModels) }
            if (messages.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.chat_card_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.chat_card_example), color = Color(0xFFADBDCF))
                            Text(stringResource(R.string.chat_card_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(messages) { turn -> ChatBubble(turn) }
            if (busy) {
                item { Text(stringResource(R.string.chat_generating), color = Mint, style = MaterialTheme.typography.bodyMedium) }
            }
            pendingSpec?.let { spec ->
                item {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetPreview(spec, height = 120)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(spec.name, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFADBDCF), modifier = Modifier.weight(1f))
                                TextButton(onClick = onApplySpec) { Text(stringResource(R.string.chat_apply_spec)) }
                            }
                        }
                    }
                }
            }
        }
        Text(status, color = Mint, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input, onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
            Button(onClick = onSend, enabled = !busy && input.isNotBlank()) {
                Text(stringResource(R.string.chat_send))
            }
        }
    }
}

@Composable
private fun ModelChipsRow(
    profiles: List<ModelConfig>,
    activeConfig: ModelConfig,
    onSelectProfile: (ModelConfig) -> Unit,
    onManageModels: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.chat_switch_model), style = MaterialTheme.typography.labelMedium, color = Color(0xFF90A3BA))
        (profiles.ifEmpty { listOf(activeConfig) }).forEach { profile ->
            val selected = profile.provider == activeConfig.provider &&
                profile.baseUrl == activeConfig.baseUrl &&
                profile.model == activeConfig.model
            FilterChip(
                selected = selected,
                onClick = { onSelectProfile(profile) },
                label = {
                    Text(
                        stringResource(ModelPresets.labelRes(profile.provider)) + " · " + profile.model,
                        maxLines = 1
                    )
                }
            )
        }
        TextButton(onClick = onManageModels) { Text(stringResource(R.string.chat_manage_models)) }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val user = turn.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) Color(0xFF243244) else Color(0xFF16202B),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                turn.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (user) Color(0xFFE8EEF7) else Color(0xFFD6E2F0)
            )
        }
    }
}

@Composable
private fun SettingsTab(
    scroll: ScrollState,
    versionName: String,
    cacheBytes: Long,
    autoRefresh: Boolean,
    status: String,
    modelForm: ModelForm,
    hasSavedKey: Boolean,
    onModelFormChange: (ModelForm) -> Unit,
    onSaveModel: () -> Unit,
    onClearKey: () -> Unit,
    onAutoRefresh: (Boolean) -> Unit,
    onRefreshBound: () -> Unit,
    onClearCache: () -> Unit,
    onLanguage: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StudioTopBar()
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.settings_subtitle), color = Color(0xFFADBDCF))

        ModelSection(modelForm, hasSavedKey, onModelFormChange, onSaveModel, onClearKey)

        HorizontalDivider(color = Color(0xFF26313F))
        Text(stringResource(R.string.settings_refresh_section), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.settings_auto_refresh))
                Text(stringResource(R.string.settings_auto_refresh_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A3BA))
            }
            Switch(checked = autoRefresh, onCheckedChange = onAutoRefresh)
        }
        OutlinedButton(onClick = onRefreshBound) { Text(stringResource(R.string.action_refresh_bound)) }

        HorizontalDivider(color = Color(0xFF26313F))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
            LanguagePicker(onSelect = onLanguage)
        }

        HorizontalDivider(color = Color(0xFF26313F))
        Text(stringResource(R.string.settings_storage_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_cache_size, formatBytes(cacheBytes)))
        OutlinedButton(onClick = onClearCache) { Text(stringResource(R.string.action_clear_cache)) }

        Text(status, color = Mint, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_version, versionName), style = MaterialTheme.typography.labelMedium, color = Color(0xFF90A3BA))
        Text(stringResource(R.string.footer_note), modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF90A3BA))
    }
}

private data class ModelForm(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = ""
) {
    companion object {
        fun from(config: ModelConfig) = ModelForm(config.provider, config.baseUrl, config.model)
    }
}

@Composable
private fun ModelSection(
    form: ModelForm,
    hasSavedKey: Boolean,
    onFormChange: (ModelForm) -> Unit,
    onSave: () -> Unit,
    onClearKey: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_model_section), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.model_provider))
            Box {
                TextButton(onClick = { expanded = true }) { Text(stringResource(ModelPresets.labelRes(form.provider))) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ModelPresets.all.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(preset.labelRes)) },
                            onClick = {
                                expanded = false
                                onFormChange(
                                    form.copy(
                                        provider = preset.id,
                                        baseUrl = preset.baseUrl.ifBlank { form.baseUrl },
                                        model = preset.defaultModel.ifBlank { form.model }
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = form.model, onValueChange = { onFormChange(form.copy(model = it)) },
            label = { Text(stringResource(R.string.model_name)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        val commonModels = ModelPresets.commonModels(form.provider)
        if (commonModels.isNotEmpty()) {
            Text(stringResource(R.string.model_common), style = MaterialTheme.typography.labelMedium, color = Color(0xFF90A3BA))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                commonModels.forEach { name ->
                    FilterChip(
                        selected = form.model == name,
                        onClick = { onFormChange(form.copy(model = name)) },
                        label = { Text(name) }
                    )
                }
            }
        }
        OutlinedTextField(
            value = form.baseUrl, onValueChange = { onFormChange(form.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.model_base_url)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
        )
        OutlinedTextField(
            value = form.apiKey, onValueChange = { onFormChange(form.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.model_api_key)) },
            placeholder = { Text(stringResource(if (hasSavedKey) R.string.model_key_saved else R.string.model_key_missing)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text(stringResource(R.string.action_save_model)) }
            TextButton(onClick = onClearKey) { Text(stringResource(R.string.action_clear_key)) }
        }
        Text(stringResource(R.string.model_profiles_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A3BA))
        Text(stringResource(R.string.model_privacy_hint), style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A3BA))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * While the model is still emitting the JSON envelope, the chat bubble shows only the growing
 * `reply` value instead of the raw spec, so the stream stays readable.
 */
private fun streamPreview(raw: String): String {
    val key = "\"reply\""
    val keyIndex = raw.indexOf(key)
    if (keyIndex < 0) return ""
    val colon = raw.indexOf(':', keyIndex + key.length)
    if (colon < 0) return ""
    var index = raw.indexOf('"', colon + 1)
    if (index < 0) return ""
    index++
    val out = StringBuilder()
    while (index < raw.length) {
        val c = raw[index]
        when {
            c == '\\' && index + 1 < raw.length -> {
                val escaped = raw[index + 1]
                out.append(if (escaped == 'n') '\n' else escaped)
                index += 2
            }
            c == '"' -> break
            else -> {
                out.append(c)
                index++
            }
        }
    }
    return out.toString().trim()
}
