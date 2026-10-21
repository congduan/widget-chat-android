package app.widgetchat.ai

import android.util.Log
import app.widgetchat.data.ModelConfig
import app.widgetchat.data.ModelPresets
import app.widgetchat.model.SpecCodec
import app.widgetchat.model.WidgetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One turn of the chat. [role] is either "user" or "assistant". */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        fun user(text: String) = ChatTurn("user", text)
        fun assistant(text: String) = ChatTurn("assistant", text)
    }
}

/**
 * Calls an OpenAI-compatible /chat/completions endpoint and turns the answer into a [WidgetSpec].
 * Rendering never depends on this: the model is only invoked when the user sends a message.
 */
object WidgetAssistant {
    data class Reply(val text: String, val spec: WidgetSpec?, val specError: String?)

    private const val TAG = "WidgetAssistant"
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_HISTORY_TURNS = 8
    private const val MAX_TURN_CHARS = 4000
    private const val MAX_ERROR_CHARS = 240

    /**
     * Streams the completion and reports each text delta through [onChunk] on the main thread,
     * so the UI can render it as it arrives. Returns the same parsed result as a blocking call.
     */
    suspend fun stream(
        config: ModelConfig,
        history: List<ChatTurn>,
        current: WidgetSpec?,
        onChunk: (String) -> Unit
    ): Reply = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "请先在设置中填写 API Key、模型名和 https 接口地址" }
        val started = System.currentTimeMillis()
        val connection = open(config, requestBody(config, history, current, stream = true))
        try {
            val code = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            Log.i(TAG, "POST ${config.endpoint} model=${config.model} code=$code contentType=$contentType")
            if (code !in 200..299) {
                val text = connection.errorStream?.use(::readCapped).orEmpty()
                throw IllegalStateException("HTTP $code ${apiError(text)}")
            }
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                val full = StringBuilder()
                var chunks = 0
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]") break
                        val chunk = sseContent(line) ?: continue
                        if (chunks == 0) Log.i(TAG, "first delta after ${System.currentTimeMillis() - started}ms")
                        chunks++
                        full.append(chunk)
                        withContext(Dispatchers.Main) { onChunk(chunk) }
                    }
                }
                Log.i(TAG, "stream done chunks=$chunks in ${System.currentTimeMillis() - started}ms")
                return@withContext parseReply(full.toString())
            }
            // Some gateways ignore stream=true, or stream SSE under a non-SSE content type.
            val body = connection.inputStream.use(::readCapped)
            if (body.trimStart().startsWith("data:")) {
                val full = StringBuilder()
                body.lineSequence().forEach { line -> sseContent(line)?.let { full.append(it) } }
                val text = full.toString()
                if (text.isNotEmpty()) {
                    withContext(Dispatchers.Main) { onChunk(text) }
                    return@withContext parseReply(text)
                }
            }
            val content = extractMessage(body)
            Log.i(TAG, "non-stream response in ${System.currentTimeMillis() - started}ms")
            withContext(Dispatchers.Main) { onChunk(content) }
            parseReply(content)
        } finally {
            connection.disconnect()
        }
    }

    /** Visible for tests: reads one SSE line and returns its delta text, or null when irrelevant. */
    fun sseContent(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val choice = runCatching {
            (SpecCodec.json.parseToJsonElement(payload).jsonObject["choices"] as? JsonArray)
                ?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return null
        val content = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content?.takeIf { it.isNotEmpty() }
    }

    /** Visible for tests: reads choices[0].message.content of a chat completion response. */
    fun extractMessage(raw: String): String {
        val root = SpecCodec.json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: throw IllegalStateException("模型响应缺少 choices")
        val first = choices.firstOrNull()?.jsonObject ?: throw IllegalStateException("模型响应为空")
        val text = first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return text?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("模型没有返回内容")
    }

    /** Visible for tests: parses the model's JSON body into a reply plus an optional spec. */
    fun parseReply(raw: String): Reply {
        val cleaned = stripFence(raw)
        val start = cleaned.indexOf('{')
        require(start >= 0) { "模型没有返回 JSON 配置" }
        val end = cleaned.lastIndexOf('}')
        val candidate = if (end > start) cleaned.substring(start, end + 1) else cleaned.substring(start)
        val obj = SpecCodec.json.parseToJsonElement(candidate).jsonObject
        val text = stringOf(obj, "reply") ?: stringOf(obj, "message") ?: stringOf(obj, "text") ?: ""
        val specJson = specJson(obj) ?: return Reply(text.ifBlank { "模型没有返回组件配置" }, null, null)
        return runCatching { SpecCodec.decode(specJson) }.fold(
            onSuccess = { Reply(text, it, null) },
            onFailure = { Reply(text, null, it.message ?: it.javaClass.simpleName) }
        )
    }

    private fun specJson(obj: JsonObject): String? {
        val element = obj["spec"] ?: obj["widget"] ?: return null
        val primitive = runCatching { element.jsonPrimitive }.getOrNull()
        return if (primitive != null && primitive.isString) primitive.contentOrNull else element.toString()
    }

    private fun stringOf(obj: JsonObject, key: String): String? =
        runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun stripFence(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', "").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        return text
    }

    private fun requestBody(
        config: ModelConfig,
        history: List<ChatTurn>,
        current: WidgetSpec?,
        stream: Boolean
    ): String =
        buildJsonObject {
            put("model", config.model)
            put("stream", stream)
            put("temperature", 0.4)
            if (config.provider == ModelPresets.GLM) {
                // GLM-4.5+ thinks by default, which delays the first streamed token by seconds.
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            // response_format is intentionally omitted: some OpenAI-compatible gateways reject
            // it together with streaming, and the system prompt already enforces a JSON answer.
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt(current))
                }
                history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                    addJsonObject {
                        put("role", turn.role)
                        put("content", turn.content.take(MAX_TURN_CHARS))
                    }
                }
            }
        }.toString()

    private fun open(config: ModelConfig, body: String): HttpURLConnection {
        val url = URL(config.endpoint)
        require(url.protocol == "https") { "接口地址必须使用 https" }
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream, application/json")
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection
    }

    private fun readCapped(stream: InputStream): String {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() <= MAX_RESPONSE_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, minOf(read, MAX_RESPONSE_BYTES + 1 - out.size()))
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun apiError(raw: String): String {
        val message = runCatching {
            SpecCodec.json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return (message ?: raw).take(MAX_ERROR_CHARS)
    }

    private fun systemPrompt(current: WidgetSpec?): String = buildString {
        appendLine("你是 Android 桌面小组件（Widget Chat）的配置生成器。只输出一个 JSON 对象，不要 markdown 代码块，不要解释。")
        appendLine("""输出：{"reply":"一句话说明","spec":WidgetSpec}""")
        appendLine("WidgetSpec:{schemaVersion:2,name(1-60字),background:#RRGGBB,padding:0-32,root:节点}")
        appendLine(
            "节点:{type:COLUMN|ROW|TEXT|SPACER|PROGRESS,text,textSize:10-48,maxLines:1-6,color:#RRGGBB," +
                "padding:0-24,size:0-64(SPACER),value:0-100(PROGRESS)," +
                "binding:LITERAL|DATE|BATTERY|COUNTDOWN|DATA,targetDate:YYYY-MM-DD(COUNTDOWN)," +
                "source/field/fallback(DATA),click:scheme://…,children(COLUMN/ROW)}"
        )
        appendLine(
            "规则：root 必须 COLUMN/ROW；节点≤40、嵌套≤6；深色背景配浅色文字；" +
                "优先 DATE/BATTERY/COUNTDOWN，默认不要 data.sources；禁用 SCRIPT；HTTP 必须 https；" +
                "PROGRESS 仅 LITERAL/BATTERY/DATA；reply 用中文且≤60 字。"
        )
        appendLine(
            """示例：{"reply":"电量与日期","spec":{"schemaVersion":2,"name":"电量看板","background":"#202342","padding":16,"root":{"type":"COLUMN","children":[{"type":"TEXT","text":"今日电量","textSize":16,"color":"#BFC7F2"},{"type":"SPACER","size":8},{"type":"TEXT","textSize":40,"binding":"BATTERY"},{"type":"PROGRESS","binding":"BATTERY"},{"type":"TEXT","textSize":12,"binding":"DATE"}]}}}"""
        )
        if (current != null) {
            appendLine("当前配置（修改时请输出完整新配置）：")
            appendLine(SpecCodec.encode(current))
        }
    }
}
