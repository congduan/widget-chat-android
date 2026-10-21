# P0 技术决策

## 共享 RemoteViews 渲染

P0 采用 RemoteViews，暂不引入 Glance。WidgetSpec 递归映射到白名单 XML 布局；应用内预览通过 RemoteViews.apply() 加载同一视图树。这样先验证系统限制及布局行为，保留将来换渲染器的协议边界。

预览与桌面仍可能因宿主尺寸、字体缩放、圆角和内边距产生差异，需要实际启动器验证。基础布局最多 40 节点、6 层；v2 含数据源时 JSON 最大 64 KiB，脚本单段最大 16 KiB。暂不支持任意视图、URL 动作和运行时下发脚本。

0.1.1 根据真机反馈补充尺寸适配：读取启动器 OPTION_APPWIDGET_SIZES，为各个精确尺寸生成 RemoteViews；旧启动器使用 min/max 尺寸回退。排版先按字体实际测量结果缩小；很矮的卡片隐藏刷新页脚，仍不足时只保留主要读数与可容纳的标题。新添加默认 4×3，旧的 4×2 实例无需重建即可使用紧凑布局。真机三个现有实例报告的可用尺寸均为约 335×158dp；旧版 200dp 预览与固定底栏是本次裁切的直接原因。应用预览可跟随已绑定桌面的真实尺寸。

## 存储与实例

P0 用 AtomicFile 保存小型组件库，替代原计划中较重的 Room 初始化。更新在进程内串行，先写临时文件再提交；遇到损坏数据不静默重置。WidgetStore 是后续迁移 Room 的边界。

记录 ID 与 appWidgetId 分离。桌面实例映射到记录；另存副本获得新记录 ID；编辑一个记录会更新它的所有实例。删除桌面实例只移除绑定。

## 系统集成

配置 Activity 只有在系统 ID 确实属于本应用 Provider 时才接受配置。取消返回 RESULT_CANCELED；绑定和首次绘制后返回 RESULT_OK。系统 pin 回调使用明确目标的单次 mutable PendingIntent，允许启动器填入新分配的 appWidgetId，并验证 Provider 归属。

P0 用 AppWidgetProvider 的 30 分钟系统周期与手动刷新。v2 增加 WorkManager 周期任务（最短 15 分钟）和本地 DataCache：渲染只读缓存，取数在后台完成。后台不要求精确执行，不使用常驻前台服务。

## 数据源

WidgetSpec v2 增加 `data.sources`，支持 STATIC、HTTP（仅 https，响应上限 256 KiB）、SCRIPT 和 SYSTEM。脚本由 QuickJS 在独立线程执行，必须同步返回扁平 JSON 对象；字段数、单值长度、脚本长度和超时都有上限。依赖通过 `dependsOn` 拓扑排序，失败时保留上次成功值或声明式 `fallback`。

SYSTEM 是固定白名单的宿主提供器（BATTERY、CLOCK、NETWORK、LOCALE、STORAGE），在 Kotlin 侧同步采集，结果和 STATIC 一样进入 DataCache，因此脚本仍只读 `ctx.sources`，无需给 QuickJS 挂宿主函数。该设计不引入新的超时或泄漏风险，只新增 `ACCESS_NETWORK_STATE` 声明；需要运行时授权、后台受限（定位、传感器、剪贴板）或需要异步的能力不进入本阶段。代价是新增能力要改代码，不能由配置或模型临时扩展。

QuickJS 使用 `wang.harlon.quickjs:wrapper-android:3.2.0`（Apache-2.0）预编译库，四个 ABI 的 `LOAD` 段均按 16 KB（0x4000）对齐，APK 通过 `zipalign -P 16` 校验；运行时设置 8 MB 内存上限。该库未暴露中断处理器，脚本超时仍是软超时，需要硬超时时应改为独立进程执行。

## AI

本阶段界面明确标注未接模型。P2 将生成的 WidgetSpec 接入当前同一校验器，候选预览通过后再保存，避免模型输出直接改变有效组件。

## 构建版本

AGP 8.7.3、Gradle 8.9、Kotlin 2.0.21、Compose BOM 2024.12.01、JDK 17、compile/target SDK 35、min SDK 31。优先使用本机已具备的构建工具，不宣称为最新依赖组合。

## 验收边界

单元和 Android 仪器测试分别记录。实际桌面添加、缩放、回调、进程恢复以及不同启动器行为必须单独验证；编译或视图 inflate 成功不等于完整 P0 验收。

官方依据：
- https://developer.android.com/develop/ui/views/appwidgets
- https://developer.android.com/develop/ui/views/appwidgets/configuration
- https://developer.android.com/develop/ui/views/appwidgets/advanced
