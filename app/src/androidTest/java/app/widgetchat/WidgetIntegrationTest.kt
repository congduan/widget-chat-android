package app.widgetchat

import android.content.Context
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.widgetchat.data.WidgetStore
import app.widgetchat.model.*
import app.widgetchat.render.WidgetRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolatedStore(): WidgetStore {
        val name = "test-" + System.nanoTime()
        return WidgetStore(context.createDeviceProtectedStorageContext().let { base ->
            object : android.content.ContextWrapper(base) {
                override fun getFilesDir(): File = File(base.filesDir, name).apply { mkdirs() }
            }
        })
    }
    @Test fun instancesRemainIndependentAndPersist() {
        val store = isolatedStore()
        val records = store.read().records
        store.bind(101, records[0].id); store.bind(102, records[1].id)
        val changed = records[0].spec.copy(name = "独立修改")
        store.save(records[0].id, changed)
        val state = store.read()
        assertEquals(records[0].id, state.bindings["101"])
        assertEquals(records[1].id, state.bindings["102"])
        assertEquals(records[1].spec, state.records.first { it.id == records[1].id }.spec)
        assertEquals(changed, state.records.first { it.id == records[0].id }.spec)
        assertEquals(records[0].spec, store.undo(records[0].id).spec)
        store.unbind(intArrayOf(101))
        assertNull(store.read().bindings["101"])
        assertEquals(records[1].id, store.read().bindings["102"])
    }
    @Test fun invalidSaveDoesNotReplaceValidRecord() {
        val store = isolatedStore()
        val original = store.read().records.first()
        assertThrows(IllegalArgumentException::class.java) { store.save(original.id, original.spec.copy(background = "bad")) }
        assertEquals(original, store.read().records.first())
    }
    @Test fun unchangedSaveDoesNotConsumeUndoHistory() {
        val store = isolatedStore()
        val original = store.read().records.first()
        val changed = original.spec.copy(name = "新版本")
        store.save(original.id, changed)
        store.save(original.id, changed)
        assertEquals(original.spec, store.undo(original.id).spec)
    }
    @Test fun smallLauncherSizesDoNotClipTemplateTextVertically() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = context.resources.displayMetrics.density
            for ((w, h) in listOf(180 to 80, 300 to 100, 300 to 110, 300 to 140, 320 to 200)) {
                Templates.all().forEach { spec ->
                    val rendered = WidgetRenderer.render(context, spec, null, width = w.toFloat(), height = h.toFloat()).apply(context, FrameLayout(context))
                    val wp = (w * density).toInt(); val hp = (h * density).toInt()
                    rendered.measure(android.view.View.MeasureSpec.makeMeasureSpec(wp, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(hp, android.view.View.MeasureSpec.EXACTLY))
                    rendered.layout(0, 0, wp, hp)
                    val content = rendered.findViewById<android.view.ViewGroup>(R.id.widget_content)
                    fun check(view: android.view.View) {
                        if (view is android.widget.TextView && view.text.isNotEmpty()) {
                            val rect = android.graphics.Rect(0, 0, view.width, view.height)
                            content.offsetDescendantRectToMyCoords(view, rect)
                            assertTrue("${spec.name} ${w}x${h}: ${view.text} bottom=${rect.bottom}, available=${content.height}", rect.bottom <= content.height)
                            assertTrue("文字自身高度被裁切: ${view.text}", (view.layout?.height ?: 0) <= view.height)
                        }
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
                    }
                    check(content)
                }
            }
        }
    }

    @Test fun reportActualLauncherSizes() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(android.content.ComponentName(context, app.widgetchat.widget.ChatWidgetProvider::class.java))
        ids.forEach { id ->
            val sizes = app.widgetchat.render.WidgetSizing.sizes(manager.getAppWidgetOptions(id))
            android.util.Log.i("WidgetChatSize", "id=$id usableSizes=$sizes")
        }
    }
    @Test fun allTemplatesInflateAsActualRemoteViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val specs = Templates.all() + Templates.all().first().copy(root = WidgetNode(NodeType.COLUMN, children = listOf(
                WidgetNode(NodeType.ROW, children = listOf(WidgetNode(NodeType.TEXT, text = "横排"), WidgetNode(NodeType.SPACER, size = 12), WidgetNode(NodeType.TEXT, text = "正常")))
            )))
            specs.forEach { spec ->
                val parent = FrameLayout(context)
                val rendered = WidgetRenderer.render(context, spec, null).apply(context, parent)
                assertNotNull(rendered.findViewById<android.view.View>(R.id.widget_content))
                rendered.measure(android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(440, android.view.View.MeasureSpec.EXACTLY))
                rendered.layout(0, 0, 700, 440)
                assertEquals(700, rendered.measuredWidth)
            }
        }
    }
}
