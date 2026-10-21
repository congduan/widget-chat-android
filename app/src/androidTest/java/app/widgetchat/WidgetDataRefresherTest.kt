package app.widgetchat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.widgetchat.data.DataCache
import app.widgetchat.data.SystemSources
import app.widgetchat.data.WidgetDataRefresher
import app.widgetchat.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetDataRefresherTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(): Context {
        val name = "data-test-" + System.nanoTime()
        return object : android.content.ContextWrapper(base.createDeviceProtectedStorageContext()) {
            override fun getFilesDir(): File = File(super.getFilesDir(), name).apply { mkdirs() }
        }
    }

    private fun spec(derived: SourceSpec) = WidgetSpec(
        name = "数据测试",
        data = DataConfig(
            mapOf(
                "seed" to SourceSpec(SourceType.STATIC, value = mapOf("n" to "2")),
                "derived" to derived.copy(dependsOn = listOf("seed"))
            )
        ),
        root = WidgetNode(
            NodeType.COLUMN,
            children = listOf(
                WidgetNode(NodeType.TEXT, binding = Binding.DATA, source = "derived", field = "answer", fallback = "--")
            )
        )
    )

    @Test fun staticAndScriptSourcesRefresh() = runBlocking {
        val context = isolated()
        val recordId = "record-script-ok"
        val spec = spec(
            SourceSpec(
                SourceType.SCRIPT,
                script = "function refresh(ctx) { var n = Number(ctx.sources.seed.n); return { answer: n * 21, label: 'ok' }; }",
                sample = mapOf("answer" to "42", "label" to "ok")
            )
        )

        val outcome = WidgetDataRefresher.refresh(context, recordId, spec, force = true)

        val error = DataCache(context).sources(recordId)["derived"]?.error
        assertEquals("script error: $error", 2, outcome.total)
        assertEquals("script error: $error", 0, outcome.failures)
        val cached = DataCache(context).snapshot(recordId)
        assertEquals("2", cached["seed"]?.get("n"))
        assertEquals("42", cached["derived"]?.get("answer"))
        assertEquals("ok", cached["derived"]?.get("label"))
    }

    @Test fun scriptFailureKeepsDeclaredFallback() = runBlocking {
        val context = isolated()
        val recordId = "record-script-fail"
        val spec = spec(
            SourceSpec(
                SourceType.SCRIPT,
                script = "function refresh() { throw new Error('boom'); }",
                fallback = mapOf("answer" to "--")
            )
        )

        val outcome = WidgetDataRefresher.refresh(context, recordId, spec, force = true)

        assertEquals(2, outcome.total)
        assertEquals(1, outcome.failures)
        val cached = DataCache(context).sources(recordId)["derived"]
        assertNotNull(cached?.error)
        assertEquals("--", cached?.values?.get("answer"))
        assertEquals("2", DataCache(context).snapshot(recordId)["seed"]?.get("n"))
    }

    @Test fun systemClockSourceRefreshes() = runBlocking {
        val context = isolated()
        val recordId = "record-system"
        val widget = WidgetSpec(
            name = "系统测试",
            data = DataConfig(mapOf("clock" to SourceSpec(SourceType.SYSTEM, system = SystemProvider.CLOCK))),
            root = WidgetNode(
                NodeType.COLUMN,
                children = listOf(
                    WidgetNode(NodeType.TEXT, binding = Binding.DATA, source = "clock", field = "date", fallback = "--")
                )
            )
        )

        val outcome = WidgetDataRefresher.refresh(context, recordId, widget, force = true)

        val error = DataCache(context).sources(recordId)["clock"]?.error
        assertEquals("system error: $error", 1, outcome.total)
        assertEquals("system error: $error", 0, outcome.failures)
        val cached = DataCache(context).snapshot(recordId)["clock"]
        assertNotNull(cached)
        assertTrue(cached!!.getValue("date").matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue(cached.getValue("time").matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(cached.getValue("timezone").isNotBlank())
    }

    @Test fun everySystemProviderReturnsBoundedFields() {
        val fieldKey = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")
        SystemProvider.entries.forEach { provider ->
            val values = SystemSources.collect(base, provider)
            assertTrue("$provider 未返回字段", values.isNotEmpty())
            values.forEach { (key, value) ->
                assertTrue("$provider.$key 字段名不合法", fieldKey.matches(key))
                assertTrue("$provider.$key 超过 400 字", value.length <= 400)
            }
        }
    }
}
