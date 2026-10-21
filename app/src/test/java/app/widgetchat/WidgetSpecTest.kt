package app.widgetchat

import app.widgetchat.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WidgetSpecTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val base get() = Templates.all(today).first()
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    private val dataBase get() = base.copy(
        data = DataConfig(
            mapOf(
                "weather" to SourceSpec(SourceType.STATIC, value = mapOf("temp" to "23")),
                "shaped" to SourceSpec(
                    SourceType.SCRIPT,
                    dependsOn = listOf("weather"),
                    script = "function refresh(ctx) { return { temp: ctx.sources.weather.temp }; }",
                    sample = mapOf("temp" to "23")
                )
            )
        ),
        root = WidgetNode(
            NodeType.COLUMN,
            children = listOf(
                WidgetNode(NodeType.TEXT, binding = Binding.DATA, source = "shaped", field = "temp", fallback = "--"),
                WidgetNode(NodeType.PROGRESS, binding = Binding.DATA, source = "weather", field = "temp")
            )
        )
    )

    @Test fun templatesRoundTrip() { Templates.all(today).forEach { assertEquals(it, SpecCodec.decode(SpecCodec.encode(it))) } }
    @Test fun rejectUnsupportedVersions() { rejects { SpecCodec.validate(base.copy(schemaVersion = 3)) } }
    @Test fun rejectUnknownKeys() { rejects { SpecCodec.decode(SpecCodec.encode(base).replace("\"name\"", "\"unknown\"")) } }
    @Test fun rejectBadColor() { rejects { SpecCodec.validate(base.copy(background = "javascript:alert(1)")) } }
    @Test fun rejectTooManyNodes() { rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = List(41) { WidgetNode(NodeType.TEXT) }))) } }
    @Test fun rejectDeepLayout() {
        var node = WidgetNode(NodeType.TEXT)
        repeat(7) { node = WidgetNode(NodeType.COLUMN, children = listOf(node)) }
        rejects { SpecCodec.validate(base.copy(root = node)) }
    }
    @Test fun rejectOversizedJson() { rejects { SpecCodec.decode("x".repeat(SpecCodec.MAX_JSON_BYTES + 1)) } }
    @Test fun rejectPathologicalJsonBeforeParsing() { rejects { SpecCodec.decode("[".repeat(1000)) } }
    @Test fun quotedBracketsAndEscapesDoNotCountAsNesting() {
        val spec = base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, text = "[".repeat(100) + "\\\""))))
        assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
    }
    @Test fun rejectInvalidDate() {
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, binding = Binding.COUNTDOWN, targetDate = "2026-02-30"))))) }
    }
    @Test fun rejectUnsupportedProgressBinding() {
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.PROGRESS, binding = Binding.DATE))))) }
    }
    @Test fun leafCannotHaveChildren() {
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, children = listOf(WidgetNode(NodeType.TEXT))))))) }
    }
    @Test fun countdownHandlesPastTodayAndFuture() {
        val node = WidgetNode(NodeType.TEXT, binding = Binding.COUNTDOWN, targetDate = today.toString())
        assertEquals("就是今天", Bindings.text(node, DataSnapshot(today, 50)))
        assertEquals("还有 1 天", Bindings.text(node, DataSnapshot(today.minusDays(1), 50)))
        assertEquals("已过去 1 天", Bindings.text(node, DataSnapshot(today.plusDays(1), 50)))
    }
    @Test fun unknownBatteryHasExplicitText() {
        assertEquals("电量未知", Bindings.text(WidgetNode(NodeType.TEXT, binding = Binding.BATTERY), DataSnapshot(today, null)))
    }
    @Test fun countdownIsCalendarBasedAcrossLeapDay() {
        val node = WidgetNode(NodeType.TEXT, binding = Binding.COUNTDOWN, targetDate = "2028-03-01")
        assertEquals("还有 2 天", Bindings.text(node, DataSnapshot(LocalDate.of(2028, 2, 28), 50)))
    }

    @Test fun dataSpecRoundTrip() {
        assertEquals(dataBase, SpecCodec.decode(SpecCodec.encode(dataBase)))
    }

    @Test fun versionOneIsMigrated() {
        val raw = SpecCodec.encode(base).replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        val migrated = SpecCodec.decode(raw)
        assertEquals(2, migrated.schemaVersion)
        assertEquals(base, migrated)
    }

    @Test fun dataBindingReadsSnapshot() {
        val spec = SpecCodec.decode(SpecCodec.encode(dataBase))
        val data = DataSnapshot(today, null, mapOf("shaped" to mapOf("temp" to "23")))
        val text = spec.root.children.first { it.type == NodeType.TEXT }
        assertEquals("23", Bindings.text(text, data))
    }

    @Test fun dataBindingFallsBack() {
        val node = WidgetNode(NodeType.TEXT, binding = Binding.DATA, source = "shaped", field = "temp", fallback = "--")
        assertEquals("--", Bindings.text(node, DataSnapshot(today, null)))
    }

    @Test fun dataProgressParsesAndClamps() {
        val node = WidgetNode(NodeType.PROGRESS, binding = Binding.DATA, source = "shaped", field = "temp")
        assertEquals(100, Bindings.progress(node, DataSnapshot(today, null, mapOf("shaped" to mapOf("temp" to "250")))))
        assertEquals(42, Bindings.progress(node, DataSnapshot(today, null, mapOf("shaped" to mapOf("temp" to "42")))))
        assertEquals(node.value, Bindings.progress(node, DataSnapshot(today, null, mapOf("shaped" to mapOf("temp" to "abc")))))
    }

    @Test fun rejectDataBindingWithoutSource() {
        val spec = dataBase.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, binding = Binding.DATA, field = "temp"))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectUnknownDataSource() {
        val spec = dataBase.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, binding = Binding.DATA, source = "missing", field = "temp"))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectHttpWithoutTls() {
        val spec = base.copy(data = DataConfig(mapOf("feed" to SourceSpec(SourceType.HTTP, url = "http://example.com/data.json"))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectOversizedScript() {
        val spec = base.copy(data = DataConfig(mapOf("feed" to SourceSpec(SourceType.SCRIPT, script = "x".repeat(SpecCodec.MAX_SCRIPT_CHARS + 1)))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectDependencyCycle() {
        val spec = base.copy(
            data = DataConfig(
                mapOf(
                    "a" to SourceSpec(SourceType.SCRIPT, script = "function refresh(){return {}}", dependsOn = listOf("b")),
                    "b" to SourceSpec(SourceType.SCRIPT, script = "function refresh(){return {}}", dependsOn = listOf("a"))
                )
            )
        )
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectUnknownWorkConstraint() {
        val spec = base.copy(data = DataConfig(mapOf("feed" to SourceSpec(SourceType.STATIC, value = mapOf("a" to "1"), require = setOf("wifi")))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun staticSourceRequiresValue() {
        val spec = base.copy(data = DataConfig(mapOf("feed" to SourceSpec(SourceType.STATIC))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun systemSourceRequiresProvider() {
        val spec = base.copy(data = DataConfig(mapOf("device" to SourceSpec(SourceType.SYSTEM))))
        rejects { SpecCodec.validate(spec) }
    }

    @Test fun rejectSystemSourceWithForeignFields() {
        rejects {
            SpecCodec.validate(
                base.copy(
                    data = DataConfig(
                        mapOf("device" to SourceSpec(SourceType.SYSTEM, system = SystemProvider.CLOCK, url = "https://example.com"))
                    )
                )
            )
        }
        rejects {
            SpecCodec.validate(
                base.copy(
                    data = DataConfig(
                        mapOf("device" to SourceSpec(SourceType.SYSTEM, system = SystemProvider.CLOCK, dependsOn = listOf("seed"), fallback = mapOf("date" to "--")))
                    )
                )
            )
        }
    }

    @Test fun systemSourceRoundTrips() {
        val spec = base.copy(
            data = DataConfig(mapOf("device" to SourceSpec(SourceType.SYSTEM, system = SystemProvider.BATTERY, sample = mapOf("percent" to "80"))))
        )
        assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
        assertTrue(SpecCodec.encode(spec).contains("\"system\": \"BATTERY\""))
    }

    @Test fun rejectUnknownSystemProvider() {
        val spec = base.copy(data = DataConfig(mapOf("device" to SourceSpec(SourceType.SYSTEM, system = SystemProvider.CLOCK))))
        rejects { SpecCodec.decode(SpecCodec.encode(spec).replace("\"CLOCK\"", "\"CAMERA\"")) }
    }

    @Test fun rejectProviderOnScriptSource() {
        rejects {
            SpecCodec.validate(
                base.copy(
                    data = DataConfig(
                        mapOf("shaped" to SourceSpec(SourceType.SCRIPT, script = "function refresh(){return {}}", system = SystemProvider.CLOCK))
                    )
                )
            )
        }
    }

    @Test fun clickTargetsRoundTrip() {
        val spec = base.copy(
            root = WidgetNode(
                NodeType.COLUMN,
                click = "widgetchat://edit",
                children = listOf(
                    WidgetNode(NodeType.TEXT, text = "天气", click = "https://example.com/weather"),
                    WidgetNode(NodeType.TEXT, text = "音乐", click = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
                )
            )
        )
        assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
    }

    @Test fun thirdPartyDeepLinkWithQueryIsAccepted() {
        val spec = SpecCodec.decode(
            SpecCodec.encode(
                base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(
                    WidgetNode(NodeType.TEXT, text = "拼多多秒杀", click = Templates.PINDUODUO_DEAL)
                )))
            )
        )
        assertEquals(Templates.PINDUODUO_DEAL, spec.root.children.single().click)
    }

    @Test fun pinduoduoTemplateShipsWithClick() {
        val template = Templates.all(today).first { it.name == "拼多多秒杀" }
        assertEquals(Templates.PINDUODUO_DEAL, template.root.children.first { it.click != null }.click)
        assertEquals(template, SpecCodec.decode(SpecCodec.encode(template)))
    }

    @Test fun rejectDangerousClickScheme() {
        listOf("javascript:alert(1)", "file:///data/local/tmp/x", "content://x/y", "intent:#Intent;end", "data:text/html,<b>x</b>").forEach { link ->
            rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, click = link))))) }
        }
    }

    @Test fun rejectClickWithoutScheme() {
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, click = "example.com"))))) }
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, click = "https://example.com/a b"))))) }
    }

    @Test fun rejectOversizedClick() {
        rejects { SpecCodec.validate(base.copy(root = WidgetNode(NodeType.COLUMN, children = listOf(WidgetNode(NodeType.TEXT, click = "app://" + "x".repeat(2048)))))) }
    }
}
