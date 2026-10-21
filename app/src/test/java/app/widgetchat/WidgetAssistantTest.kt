package app.widgetchat

import app.widgetchat.ai.WidgetAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAssistantTest {
    private val spec = """
        {"schemaVersion":2,"name":"AI 电量","background":"#202342","padding":16,
         "root":{"type":"COLUMN","children":[
           {"type":"TEXT","text":"电量","textSize":14},
           {"type":"TEXT","binding":"BATTERY","textSize":36},
           {"type":"PROGRESS","binding":"BATTERY"}]}}
    """.trimIndent()

    @Test
    fun parsesReplyWithSpec() {
        val reply = WidgetAssistant.parseReply("""{"reply":"已生成电量卡片","spec":$spec}""")
        assertEquals("已生成电量卡片", reply.text)
        assertNotNull(reply.spec)
        assertEquals("AI 电量", reply.spec?.name)
        assertNull(reply.specError)
    }

    @Test
    fun stripsMarkdownFence() {
        val raw = "```json\n{\"reply\":\"ok\",\"spec\":$spec}\n```"
        val reply = WidgetAssistant.parseReply(raw)
        assertNotNull(reply.spec)
        assertEquals("ok", reply.text)
    }

    @Test
    fun acceptsSpecAsEscapedString() {
        val escaped = spec.replace("\"", "\\\"").replace("\n", "\\n")
        val reply = WidgetAssistant.parseReply("""{"reply":"ok","spec":"$escaped"}""")
        assertNotNull(reply.spec)
    }

    @Test
    fun reportsInvalidSpecWithoutThrowing() {
        val bad = spec.replace("\"textSize\":36", "\"textSize\":99")
        val reply = WidgetAssistant.parseReply("""{"reply":"bad","spec":$bad}""")
        assertNull(reply.spec)
        assertNotNull(reply.specError)
    }

    @Test
    fun replyWithoutSpecStillSurfacesText() {
        val reply = WidgetAssistant.parseReply("""{"reply":"还需要更多信息"}""")
        assertEquals("还需要更多信息", reply.text)
        assertNull(reply.spec)
    }

    @Test
    fun extractsChoiceContent() {
        val raw = """{"choices":[{"index":0,"message":{"role":"assistant","content":"hello"}}]}"""
        assertEquals("hello", WidgetAssistant.extractMessage(raw))
    }

    @Test
    fun readsSseDelta() {
        assertEquals(
            "你好",
            WidgetAssistant.sseContent("""data: {"choices":[{"delta":{"content":"你好"}}]}""")
        )
        assertNull(WidgetAssistant.sseContent("data: [DONE]"))
        assertNull(WidgetAssistant.sseContent(""))
        assertNull(WidgetAssistant.sseContent("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(WidgetAssistant.sseContent("event: ping"))
    }

    @Test
    fun rejectsResponseWithoutChoices() {
        val failure = runCatching { WidgetAssistant.extractMessage("""{"error":"boom"}""") }
        assertTrue(failure.isFailure)
    }
}
