package app.widgetchat.model

import android.content.Context
import app.widgetchat.R
import java.time.LocalDate

object Templates {
    /** Pinduoduo deep link used by the sample template; requires the app to be installed. */
    const val PINDUODUO_DEAL = "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?station_code=A029963005"

    /**
     * @param context supplies localized names and copy; when null (JVM tests) Chinese defaults are used.
     */
    fun all(today: LocalDate = LocalDate.now(), context: Context? = null): List<WidgetSpec> = listOf(
        WidgetSpec(name = context.text(R.string.template_daily_name, "每日一句"), root = column(
            text(context.text(R.string.template_daily_quote, "留一点时间，给自己。"), 28, "#A8F0CE"), spacer(),
            WidgetNode(NodeType.TEXT, binding = Binding.DATE, textSize = 14, color = "#BDCBDC")
        )),
        WidgetSpec(name = context.text(R.string.template_battery_name, "电量看板"), background = "#202342", root = column(
            text(context.text(R.string.template_battery_title, "随时，充满能量"), 16, "#BFC7F2"), spacer(),
            WidgetNode(NodeType.TEXT, textSize = 40, binding = Binding.BATTERY, color = "#D2D9FF"),
            WidgetNode(NodeType.PROGRESS, binding = Binding.BATTERY), spacer(),
            WidgetNode(NodeType.TEXT, textSize = 12, binding = Binding.DATE)
        )),
        WidgetSpec(name = context.text(R.string.template_countdown_name, "期待的日子"), background = "#30251F", root = column(
            text(context.text(R.string.template_countdown_title, "下一个值得期待的日子"), 16, "#F8C9A8"), spacer(),
            WidgetNode(NodeType.TEXT, textSize = 30, binding = Binding.COUNTDOWN, targetDate = today.plusDays(30).toString()),
            spacer(), text(context.text(R.string.template_countdown_hint, "在 JSON 中修改 targetDate"), 12, "#D4BBA8")
        )),
        WidgetSpec(name = context.text(R.string.template_pdd_name, "拼多多秒杀"), background = "#241A16", root = column(
            text(context.text(R.string.template_pdd_title, "限时秒杀"), 22, "#FF7A45"),
            text(context.text(R.string.template_pdd_hint, "点击按钮打开拼多多会场"), 12, "#C9B4A8"), spacer(),
            WidgetNode(
                NodeType.TEXT,
                text = context.text(R.string.template_pdd_button, "去逛逛 →"),
                textSize = 18,
                color = "#FFFFFF",
                click = PINDUODUO_DEAL
            ),
            spacer(), text("A029963005", 11, "#8C7A70")
        ))
    )
    private fun text(s: String, size: Int, color: String) = WidgetNode(NodeType.TEXT, text = s, textSize = size, color = color)
    private fun spacer() = WidgetNode(NodeType.SPACER)
    private fun column(vararg nodes: WidgetNode) = WidgetNode(NodeType.COLUMN, children = nodes.toList())
    private fun Context?.text(id: Int, fallback: String): String = this?.getString(id) ?: fallback
}
