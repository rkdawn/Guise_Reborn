package com.houvven.guise.ui.routing.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoNotDisturbOnTotalSilence
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.houvven.guise.R
import com.houvven.guise.db.Template
import com.houvven.guise.db.TemplateGroupStore
import com.houvven.guise.db.TemplateTransfer
import com.houvven.guise.ui.GlobalSnackbarHost
import com.houvven.guise.ui.components.SearchBox
import com.houvven.guise.ui.components.simplify.SimplifyDropdownMenuItem
import com.houvven.guise.ui.components.simplify.SimplifyIcon
import com.houvven.guise.ui.components.simplify.SimplifyImage
import com.houvven.guise.ui.routing.LauncherState
import com.houvven.guise.ui.routing.LocalNavController
import com.houvven.guise.ui.routing.NavRoutingTypes
import com.houvven.guise.ui.routing.navigateWithTemplate
import com.houvven.guise.ui.routing.template.EnableTemplateDialog
import com.houvven.guise.ui.utils.encodeTemplateQrBitmap
import com.houvven.guise.ui.utils.saveBitmapToDownloadDir
import com.houvven.guise.xposed.PackageConfig
import com.houvven.guise.xposed.config.ModuleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================
// 模板页（整页重构版）
//
// 单页可折叠分区列表：我的模板 → 自建分组 → 厂商分区。
// 交互（与整体设计语言对齐的扁平行，无卡片）：
//   点分区标题        → 展开/折叠
//   长按分区标题      → 进入编辑态，可不松手直接拖动换位
//   编辑态            → 全部折叠；行内红色删除（我的模板除外）、右侧拖拽手柄、
//                       点自建组名改名；顶栏切换为「完成」，返回键退出
//   长按模板行        → 编辑 / 导出二维码 / 移动 / 删除
//   顶栏 ⋮ 菜单       → 建组 / 导入 / 导出
//
// 手势三条铁律（每条都踩过一次，勿违反）：
//   1. pointerInput 的 key 不能包含手势过程中会变化的状态（如 editMode），
//      否则 key 一变 Compose 重启手势协程，进行中的拖拽被掐断；
//   2. 并行的 detectTapGestures 不要传 onLongPress（空 lambda 也不行）——
//      传了它就会参与长按竞争并消费长按事件，把拖拽检测器饿死；
//   3. pointerInput 协程只启动一次，捕获的是「首次组合」的 lambda——
//      回调必须经 rememberUpdatedState 读最新值，否则 lambda 里冻结的
//      局部变量（如 storedCollapsed）永远停留在首次值，点按永不生效。
// =============================================================================

private val requestEnable = mutableStateOf(false)
private val requestEnableTemplate = mutableStateOf<Template?>(null)

// ---------------------------------------------------------------------------
// 分组模型
// ---------------------------------------------------------------------------

/** 「我的模板」分区 key。带前导空格，保证不会与品牌 key 撞名。 */
private const val GROUP_KEY_MINE = " mine"

/** 自建分组的 key 前缀，与品牌 key 隔离。 */
private const val GROUP_KEY_PREFIX_CUSTOM = "custom:"

/** 分区行间距（LazyColumn spacedBy 与拖拽步长共用，必须一致）。 */
private val SECTION_SPACING = 4.dp

/** 拖拽时距视口上下边缘多近开始自动滚动。 */
private val AUTO_SCROLL_EDGE = 72.dp

/** 自动滚动每帧的步长（px）。约 60fps 下 8px/帧 ≈ 480px/s。 */
private const val AUTO_SCROLL_STEP_PX = 8f

/** 顶部搜索框占位高度：列表顶部内边距要让开这么多，否则首行被压住。 */
private val SEARCH_BAR_HEIGHT = 56.dp

/** 品牌标识：取配置里的 brand，空白归入「我的模板」。 */
private fun templateBrandKey(template: Template): String =
    runCatching { ModuleConfig.fromJson(template.configuration).brand.trim() }
        .getOrNull().orEmpty().lowercase()

/**
 * 分区归属：显式指定了自建分组 → 该分组；有品牌 → 品牌分区；
 * 其余（无品牌的用户模板）→ 「我的模板」。
 */
private fun templateGroupKey(template: Template): String {
    template.groupName?.takeIf { it.isNotBlank() }
        ?.let { return GROUP_KEY_PREFIX_CUSTOM + it }
    val brand = templateBrandKey(template)
    if (brand.isNotEmpty()) return brand
    return GROUP_KEY_MINE
}

/** 品牌官方写法（源数据大小写混乱：samsung/Redmi/REDMI/vivo）。 */
private val canonicalBrandDisplayNames = mapOf(
    "xiaomi" to "Xiaomi",
    "redmi" to "REDMI",
    "huawei" to "HUAWEI",
    "honor" to "HONOR",
    "oppo" to "OPPO",
    "oneplus" to "OnePlus",
    "vivo" to "Vivo",
    "iqoo" to "iQOO",
    "samsung" to "Samsung",
    "apple" to "Apple",
    "nubia" to "Nubia",
    "google" to "Google",
)

/** 一个分区：稳定 key、展示名、名称自然排序后的模板。 */
private data class TemplateSection(
    val key: String,
    val displayName: String,
    val templates: List<Template>,
) {
    val isMine get() = key == GROUP_KEY_MINE
    val isCustom get() = key.startsWith(GROUP_KEY_PREFIX_CUSTOM)
}

/**
 * 组装分区列表：
 * - 名册（TemplateGroupStore）里的空自建分组也要出现；
 * - 排序：用户手动拖拽过的顺序优先，未记录过的分区按
 *   「我的模板 < 自建分组 < 厂商（按展示名）」排在末尾。
 *
 * [customGroups] 由调用方一次读好传进来：这是同步的 SharedPreferences 读取，
 * 不放在组合里反复调用。
 */
private fun buildSections(
    templates: List<Template>,
    mineLabel: String,
    customGroups: List<String>,
): List<TemplateSection> {
    val grouped = templates.groupBy(::templateGroupKey).toMutableMap()
    customGroups.forEach { name ->
        grouped.putIfAbsent(GROUP_KEY_PREFIX_CUSTOM + name, emptyList())
    }
    val savedOrder = TemplateGroupStore.sectionOrder()
    return grouped.map { (key, group) ->
        val display = when {
            key == GROUP_KEY_MINE -> mineLabel
            key.startsWith(GROUP_KEY_PREFIX_CUSTOM) ->
                key.removePrefix(GROUP_KEY_PREFIX_CUSTOM)
            else -> canonicalBrandDisplayNames[key]
                // 未知品牌：出现次数最多的原始写法胜出
                ?: group.map {
                    runCatching { ModuleConfig.fromJson(it.configuration).brand.trim() }
                        .getOrDefault("")
                }.groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key.orEmpty()
        }
        TemplateSection(
            key = key,
            displayName = display,
            templates = group.sortedWith { a, b -> naturalNameCompare(a.name, b.name) },
        )
    }.sortedWith(
        compareBy<TemplateSection> {
            savedOrder.indexOf(it.key).let { index -> if (index >= 0) index else Int.MAX_VALUE }
        }.thenBy {
            when {
                it.isMine -> 0
                it.isCustom -> 1
                else -> 2
            }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
    )
}

// ---------------------------------------------------------------------------
// 数字感知的名称排序（K70 < K80 < K100，Xiaomi 13 < 14 < 17）
// ---------------------------------------------------------------------------

private val naturalSortChunkRegex = Regex("""(\d+)|(\D+)""")

private fun naturalNameCompare(a: String, b: String): Int {
    val chunksA = naturalSortChunkRegex.findAll(a).map { it.value }.toList()
    val chunksB = naturalSortChunkRegex.findAll(b).map { it.value }.toList()
    val iterA = chunksA.iterator()
    val iterB = chunksB.iterator()
    while (iterA.hasNext() && iterB.hasNext()) {
        val chunkA = iterA.next()
        val chunkB = iterB.next()
        val cmp = if (chunkA[0].isDigit() && chunkB[0].isDigit()) {
            // BigInteger：数字段可能超过 Long（如构建号）
            chunkA.toBigInteger().compareTo(chunkB.toBigInteger())
        } else {
            chunkA.compareTo(chunkB, ignoreCase = true)
        }
        if (cmp != 0) return cmp
    }
    return when {
        iterA.hasNext() -> 1
        iterB.hasNext() -> -1
        else -> a.compareTo(b, ignoreCase = true)
    }
}

/**
 * 模板是否命中搜索词：匹配模板名，也匹配型号副标题（用户常记得的是
 * 「SGT-AL00」这类型号代码而不是模板名）。
 */
private fun Template.matchesQuery(query: String): Boolean {
    if (name.contains(query, ignoreCase = true)) return true
    val model = runCatching { ModuleConfig.fromJson(configuration).model.trim() }
        .getOrDefault("")
    if (model.isNotBlank() && model.contains(query, ignoreCase = true)) return true
    return description?.contains(query, ignoreCase = true) == true
}

// ---------------------------------------------------------------------------
// 分区拖拽排序状态机
// ---------------------------------------------------------------------------

/**
 * 长按（或编辑态按住手柄）开始拖拽：拖动中按实测的分区头位置换算目标位置
 * 就地换位，松手后由调用方持久化。
 *
 * 位置一律走实测而不是固定步长：stickyHeader 会把滚动中的分区头钉在视口
 * 顶部，它的 onGloballyPositioned 报告的是被钳制后的位置。若把这份数据写进
 * 共享字段，最后一个复合的分区会覆盖掉正在拖拽分区的真实行高，换位阈值随之
 * 漂移。因此每个分区的 bounds 各自记录，只信任「本次布局真正移动过」的那些。
 */
@Stable
private class SectionDragState {
    /** 正在拖拽的分区 key；null = 未拖拽。 */
    var draggingKey by mutableStateOf<String?>(null)
        private set

    /** 被拖分区叠加的视觉位移（px，正数向下）。同时决定它落在哪个槽位。 */
    var offsetY by mutableFloatStateOf(0f)
        private set

    /**
     * 各分区头的实测基准位置（相对列表原点，已剔除拖动位移）。
     *
     * 必须按分区各记一份，不能用共享的单一「行高」：stickyHeader 会把滚动中的
     * 分区头钉在视口顶部，它的测量值被钳制，写入共享字段后会覆盖掉正在拖拽
     * 分区的真实位置，换位阈值随之漂移。
     */
    var layoutBounds by mutableStateOf<Map<String, HeaderBounds>>(emptyMap())
        private set

    /** 本帧累积的指针拖动增量，由布局效果消费。 */
    private var pointerDeltaY = 0f

    /** 本帧累积的自动滚动增量，由布局效果消费。 */
    private var scrollDeltaY = 0f

    /** 拖拽中的实时顺序。 */
    val liveOrder = mutableStateListOf<String>()

    /** 进入拖拽那一刻的顺序，用于判断松手时是否真的换过位。 */
    private var originalOrder: List<String> = emptyList()

    /** 本次拖拽是否真的发生过换位（长按只是进编辑态、未拖动时不算）。 */
    private var reordered = false

    val isDragging: Boolean get() = draggingKey != null

    /** 被拖分区头的实测基准位置（相对列表原点）；尚未测量到时为 null。 */
    val draggingBoundsTop: Float?
        get() = draggingKey?.let(layoutBounds::get)?.top

    /** 开启本次拖拽，[currentOrder] 为进入拖拽那一刻的顺序。 */
    fun begin(key: String, currentOrder: List<String>) {
        liveOrder.clear()
        liveOrder.addAll(currentOrder)
        originalOrder = currentOrder.toList()
        reordered = false
        draggingKey = key
        offsetY = 0f
        pointerDeltaY = 0f
        scrollDeltaY = 0f
        layoutBounds = emptyMap()
    }

    /** 记录指针拖动增量。位置计算与换位判定都在 [tick] 里统一做。 */
    fun dragBy(deltaY: Float) {
        pointerDeltaY += deltaY
    }

    /**
     * 回写一个分区头的实测基准位置。
     *
     * 每帧所有可见分区头都会走一遍这里，写完之后 [tick] 才能拿到同一帧的一致
     * 布局；因此这里只累积、不做换位判定。
     */
    fun measure(key: String, baselineTop: Float, height: Float) {
        if (baselineTop.isNaN() || height <= 0f) return
        val existing = layoutBounds[key]
        if (existing != null && existing.top == baselineTop && existing.height == height) return
        layoutBounds = layoutBounds + (key to HeaderBounds(baselineTop, height))
    }

    /** 自动滚动带动列表后，等价于被拖分区反向位移了这么多。 */
    fun scrollBy(deltaY: Float) {
        scrollDeltaY += deltaY
    }

    /**
     * 拖拽帧循环：消费本帧增量 → 平移 → 判定换位。由 LaunchedEffect 驱动，
     * 只在 [draggingKey] 非空时运行。
     */
    fun tick() {
        val key = draggingKey ?: return
        val delta = pointerDeltaY + scrollDeltaY
        pointerDeltaY = 0f
        scrollDeltaY = 0f

        val from = liveOrder.indexOf(key)
        if (from < 0) return

        // 先平移，再按平移后的落点判定槽位 —— 顺序反了会永远慢一帧。
        offsetY += delta

        val dragged = layoutBounds[key] ?: return
        val to = nearestSlot(dragged, layoutBounds, liveOrder, key, offsetY) ?: return
        if (to == from) return

        // 被拖分区头渲染在 dragged.top + offsetY，换位后它应当落在新槽位，
        // 因此 offsetY 要加上「新槽位 - 基准位置」，否则视觉位置会跳一格。
        val anchorTop = slotTop(to, layoutBounds, liveOrder)
        liveOrder.add(to, liveOrder.removeAt(from))
        offsetY += anchorTop - dragged.top
        reordered = true
    }

    /**
     * 拖拽结束后取最终顺序。仅当真的换过位才返回待持久化的顺序，否则返回
     * null —— 长按只是进编辑态，未拖动时不能把当时的顺序固化下来。
     *
     * 固化一次的后果：此后新增的品牌分区不在 savedOrder 里，indexOf 得到 -1
     * 被排到末尾，默认排序规则永久失效。
     */
    fun finish(): List<String>? {
        val order = if (reordered && liveOrder.toList() != originalOrder) liveOrder.toList() else null
        reset()
        return order
    }

    fun cancel() = reset()

    private fun reset() {
        draggingKey = null
        offsetY = 0f
        layoutBounds = emptyMap()
        pointerDeltaY = 0f
        scrollDeltaY = 0f
        liveOrder.clear()
        originalOrder = emptyList()
        reordered = false
    }

    /**
     * [dragged] 在 [offsetY] 位移后落在哪个槽位。
     *
     * 判定方式：把「除被拖分区以外的所有分区头」按与预测落点的距离排序，取最近的
     * 作为锚点，落在锚点上方则插入锚点之前，下方则之后。不能拿被拖分区自己的
     * 实测值参与比较 —— 那是拖动开始前的旧值，会把结果吸回原位。
     */
    private fun nearestSlot(
        dragged: HeaderBounds,
        bounds: Map<String, HeaderBounds>,
        order: List<String>,
        draggingKey: String,
        offsetY: Float,
    ): Int? {
        val predictedTop = dragged.top + offsetY
        val anchor = order.withIndex()
            .filter { it.value != draggingKey }
            .mapNotNull { (index, key) -> bounds[key]?.let { index to it.top } }
            .minByOrNull { (_, top) -> abs(top - predictedTop) }
            ?: return null
        return if (predictedTop < anchor.second) anchor.first else anchor.first + 1
    }

    /** 槽位对应的分区头位置；无实测数据时返回 0。 */
    private fun slotTop(index: Int, bounds: Map<String, HeaderBounds>, order: List<String>): Float =
        order.getOrNull(index)?.let(bounds::get)?.top ?: 0f
}

/** 分区头相对列表原点的基准位置（已剔除自身 translationY）。
 *
 * [top] 是「渲染位置 = top + offsetY」里的那个 top：非拖拽分区 offsetY 恒为 0，
 * 拖拽分区由调用方把已叠加的 translationY 减掉。这样反复测量不会把视觉位移
 * 当成真实位移累积进判定。
 */
private data class HeaderBounds(val top: Float, val height: Float)

// ---------------------------------------------------------------------------
// 主界面
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TemplateScreen() {
    val navController = LocalNavController.current
    val templates = LauncherState.templates.value

    // —— 各模板已配置应用数（ON_RESUME 时重算）——
    val configurationRevision = PackageConfig.configurationRevision.intValue
    val templateSignatures = remember(templates) {
        templates.map { it.id to it.configuration }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeGeneration by remember { mutableIntStateOf(0) }
    var appliedCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(templateSignatures, configurationRevision, resumeGeneration) {
        appliedCounts = withContext(Dispatchers.IO) {
            val usageBySignature = ModuleConfig.getAllSaved()
                .asSequence()
                .filter { it.enabled }
                .groupingBy(ModuleConfig::parameterSignature)
                .eachCount()
            templateSignatures.associate { (templateId, configuration) ->
                val signature = ModuleConfig.fromJson(configuration).parameterSignature()
                templateId to (usageBySignature[signature] ?: 0)
            }
        }
    }

    // —— 分区数据：groupRevision 驱动名册/排序变化后的重算 ——
    var groupRevision by remember { mutableIntStateOf(0) }
    val mineLabel = stringResource(R.string.template_group_mine)
    // 自建分组名单只读一次，交给分区组装和「移动到分组」对话框共用；
    // TemplateGroupStore 走的是同步 SharedPreferences，不能在组合里反复调用。
    val customGroups = remember(groupRevision) { TemplateGroupStore.customGroups() }
    val sections = remember(templates, customGroups, mineLabel) {
        buildSections(templates, mineLabel, customGroups)
    }
    // 建组/改名时用于查重的名字集合。
    //
    // 只比对现有分区的展示名是不够的：「自建分组」与「品牌分区」是两套互不
    // 相干的 key 空间（custom: 前缀 vs 品牌 key），用户可以建一个叫「Xiaomi」
    // 的自建分组，它不会与品牌分区撞 key，于是列表里出现两个都叫 Xiaomi 的
    // 分区、行为却完全不同。把已知品牌名一并纳入，从入口堵住。
    val sectionDisplayNames = sections.map { it.displayName }
    val brandDisplayNames = canonicalBrandDisplayNames.values.toList()
    // 折叠状态；缺省折叠，页面打开时是紧凑的索引视图。
    val collapsedKeys = remember { mutableStateMapOf<String, Boolean>() }
    // 分区被删除/改名后清掉对应条目。不清的话名字会一直挂在表里，同一个 key
    // 再次出现（重建同名分组）时会带着删除前的折叠状态回来。
    val sectionKeys = sections.map { it.key }
    LaunchedEffect(sectionKeys) {
        collapsedKeys.keys.retainAll(sectionKeys.toSet())
    }

    // —— 编辑态 + 拖拽排序 ——
    var editMode by remember { mutableStateOf(false) }
    /** 搜索词：按模板名/型号副标题过滤，命中分区自动展开。 */
    var searchQuery by remember { mutableStateOf("") }
    val dragState = remember { SectionDragState() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    /** 列表自身在窗口中的纵坐标，用于把分区头的窗口测量换算成列表内坐标。 */
    var listWindowTop by remember { mutableFloatStateOf(0f) }
    /** 列表视口高度（px），自动滚动的边界判定用。 */
    var viewportPx by remember { mutableFloatStateOf(0f) }
    /**
     * 进入编辑态前的折叠状态快照。
     *
     * 编辑态把所有分区强制折叠只是「显示上折叠」，不能写回 collapsedKeys ——
     * 那份状态是用户手动设置的结果，写回等于借一次排序把用户的展开习惯抹掉，
     * 退出编辑态后内容会莫名其妙全部收起来。这里整体备份、退出时还原。
     */
    var collapsedSnapshot by remember { mutableStateOf<Map<String, Boolean>?>(null) }

    fun startSectionDrag(key: String) {
        if (!editMode) collapsedSnapshot = collapsedKeys.toMap()
        editMode = true
        // 搜索框在编辑态隐藏，残留的查询会把分区过滤掉，看起来像分区丢了。
        searchQuery = ""
        dragState.begin(key, sections.map { it.key })
    }

    fun finishSectionDrag(persist: Boolean) {
        if (persist) {
            dragState.finish()?.let(TemplateGroupStore::saveSectionOrder)
        } else {
            dragState.cancel()
        }
        groupRevision++
    }

    fun exitEditMode() {
        dragState.cancel()
        editMode = false
        collapsedSnapshot?.let { snapshot ->
            collapsedKeys.clear()
            snapshot.forEach { (key, value) -> collapsedKeys[key] = value }
        }
        collapsedSnapshot = null
    }

    // B5：拖拽帧循环。指针增量与自动滚动增量统一在这里消费，保证「平移」和
    // 「换位」用的是同一帧的数据。分区头位置由各自回写，不依赖任何全局步长，
    // 因此 stickyHeader 钉住谁都不影响判定。
    LaunchedEffect(dragState) {
        while (dragState.isDragging) {
            withFrameNanos { }
            dragState.tick()
            // 拖到视口上下边缘时自动滚动，让被拖分区能到达屏幕外的目标位置。
            val baselineTop = dragState.draggingBoundsTop
            if (baselineTop != null && viewportPx > 0f) {
                val top = baselineTop + dragState.offsetY
                val edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
                val stepPx = when {
                    top < edgePx -> -AUTO_SCROLL_STEP_PX
                    top > viewportPx - edgePx -> AUTO_SCROLL_STEP_PX
                    else -> 0f
                }
                if (stepPx != 0f) {
                    val consumed = listState.scrollBy(stepPx)
                    // 列表内容上移 consumed，等价于被拖分区在该坐标系里反向位移。
                    if (consumed != 0f) dragState.scrollBy(consumed)
                }
            }
        }
    }

    // 拖拽中按实时顺序渲染，松手后回到正式顺序。
    val visibleSections = if (dragState.isDragging && dragState.liveOrder.isNotEmpty()) {
        dragState.liveOrder.mapNotNull { key -> sections.firstOrNull { it.key == key } }
    } else {
        sections
    }

    // —— 搜索过滤 ——
    //
    // 只过滤展示，不动 visibleSections —— 拖拽预览用的是分区全集，
    // 搜索过滤掺进去会让排序时缺少分区。
    val searching = searchQuery.isNotBlank()
    val displayedSections = remember(visibleSections, searchQuery) {
        if (searchQuery.isBlank()) {
            visibleSections
        } else {
            visibleSections.mapNotNull { section ->
                val hit = section.templates.filter { it.matchesQuery(searchQuery) }
                section.copy(templates = hit).takeIf { hit.isNotEmpty() }
            }
        }
    }

    // —— 对话框状态 ——
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renameGroupTarget by remember { mutableStateOf<String?>(null) }
    var moveGroupTemplate by remember { mutableStateOf<Template?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<TemplateSection?>(null) }

    // 编辑态下返回键退出编辑态而不是退出页面。
    BackHandler(enabled = editMode) { exitEditMode() }

    Scaffold(
        topBar = {
            var topBarMenuExpanded by remember { mutableStateOf(false) }
            var transferDialog by remember { mutableStateOf<TemplateTransferMode?>(null) }
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.action_template),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.W500,
                    )
                },
                actions = {
                    if (editMode) {
                        TextButton(onClick = { exitEditMode() }) {
                            Text(stringResource(R.string.template_group_edit_done))
                        }
                    } else {
                        IconButton(onClick = { topBarMenuExpanded = true }) {
                            SimplifyIcon(Icons.Default.MoreVert)
                        }
                        transferDialog?.let { mode ->
                            TemplateTransferDialog(
                                mode = mode,
                                onDismiss = { transferDialog = null },
                                onScanQr = {
                                    navController.navigate(NavRoutingTypes.SCAN_TEMPLATE_QR.name)
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = topBarMenuExpanded,
                            onDismissRequest = { topBarMenuExpanded = false },
                        ) {
                            SimplifyDropdownMenuItem(
                                text = stringResource(R.string.template_group_create),
                                onClick = {
                                    topBarMenuExpanded = false
                                    showCreateGroupDialog = true
                                },
                            )
                            SimplifyDropdownMenuItem(
                                text = stringResource(R.string.import_data),
                                onClick = {
                                    topBarMenuExpanded = false
                                    transferDialog = TemplateTransferMode.IMPORT
                                },
                            )
                            SimplifyDropdownMenuItem(
                                text = stringResource(R.string.export_data),
                                onClick = {
                                    topBarMenuExpanded = false
                                    transferDialog = TemplateTransferMode.EXPORT
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(NavRoutingTypes.ADD_TEMPLATE.name) },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                SimplifyIcon(Icons.Default.Add)
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = paddingValues.calculateTopPadding() + SEARCH_BAR_HEIGHT,
                    bottom = paddingValues.calculateBottomPadding(),
                )
                // 分区头的测量以窗口为原点，这里记录列表自身的窗口纵坐标，
                // 换算成「列表内布局坐标」后才能与滚动偏移相减。
                .onGloballyPositioned { coordinates ->
                    listWindowTop = coordinates.positionInWindow().y
                    viewportPx = coordinates.size.height.toFloat()
                },
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            displayedSections.forEach { section ->
                val storedCollapsed = collapsedKeys[section.key] != false
                // 编辑态全部强制折叠：标题行等高，拖拽才好对位。
                // 搜索时反过来全部展开，否则命中结果藏在折叠的分区里看不见。
                val collapsed = if (searching) false else storedCollapsed || editMode
                stickyHeader(key = "header-${section.key}") {
                    SectionHeader(
                        section = section,
                        collapsed = collapsed,
                        editMode = editMode,
                        dragState = dragState,
                        listWindowTop = listWindowTop,
                        onTap = {
                            when {
                                editMode && section.isCustom ->
                                    renameGroupTarget = section.displayName
                                editMode -> Unit
                                else -> collapsedKeys[section.key] = !storedCollapsed
                            }
                        },
                        onDeleteRequest = { deleteGroupTarget = section },
                        onDragStart = { startSectionDrag(section.key) },
                        onDragFinish = ::finishSectionDrag,
                    )
                }
                if (!collapsed) {
                    section.templates.forEach { template ->
                        item(key = "tpl-${template.id}") {
                            TemplateRow(
                                template = template,
                                appliedAppCount = appliedCounts[template.id] ?: 0,
                                onMoveToGroup = { moveGroupTemplate = template },
                            )
                        }
                    }
                }
            }
        }

            // 搜索框浮在列表之上；进入编辑态时隐藏（编辑态下所有分区强制
            // 折叠，搜索的展开结果会被折叠逻辑覆盖，两者语义冲突）。
            if (!editMode) {
                SearchBox(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_placeholder),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 15.dp, vertical = 3.dp)
                        .zIndex(2f),
                )
            }
        }

        requestEnableTemplate.value?.let {
            EnableTemplateDialog(state = requestEnable, template = it)
        }

        // —— 整组删除确认 ——
        deleteGroupTarget?.let { section ->
            DeleteGroupDialog(
                section = section,
                onConfirm = {
                    if (section.isCustom) {
                        LauncherState.deleteGroup(section.displayName, section.templates)
                    } else {
                        LauncherState.deleteTemplates(section.templates)
                    }
                    TemplateGroupStore.removeSectionKey(section.key)
                    groupRevision++
                    deleteGroupTarget = null
                },
                onDismiss = { deleteGroupTarget = null },
            )
        }

        // —— 创建分组 ——
        if (showCreateGroupDialog) {
            GroupNameDialog(
                title = stringResource(R.string.template_group_create),
                initialName = "",
                existingNames = sectionDisplayNames + brandDisplayNames,
                onDismiss = { showCreateGroupDialog = false },
                onConfirm = { name ->
                    TemplateGroupStore.add(name)
                    groupRevision++
                    showCreateGroupDialog = false
                },
            )
        }

        // —— 重命名自建分组：名册 + 组内模板 + 排序记录一起跟随 ——
        renameGroupTarget?.let { oldName ->
            GroupNameDialog(
                title = stringResource(R.string.template_group_rename),
                initialName = oldName,
                existingNames = sectionDisplayNames + brandDisplayNames,
                onDismiss = { renameGroupTarget = null },
                onConfirm = { newName ->
                    LauncherState.renameGroup(oldName, newName)
                    TemplateGroupStore.renameSectionKey(
                        GROUP_KEY_PREFIX_CUSTOM + oldName,
                        GROUP_KEY_PREFIX_CUSTOM + newName,
                    )
                    groupRevision++
                    renameGroupTarget = null
                },
            )
        }

        // —— 移动模板到分组（「自动」= 恢复按品牌分组）——
        moveGroupTemplate?.let { template ->
            MoveToGroupDialog(
                customGroups = customGroups,
                onDismiss = { moveGroupTemplate = null },
                onSelect = { groupName ->
                    LauncherState.setTemplateGroup(template, groupName)
                    moveGroupTemplate = null
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 分区标题行
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(
    section: TemplateSection,
    collapsed: Boolean,
    editMode: Boolean,
    dragState: SectionDragState,
    listWindowTop: Float,
    onTap: () -> Unit,
    onDeleteRequest: () -> Unit,
    onDragStart: () -> Unit,
    onDragFinish: (persist: Boolean) -> Unit,
) {
    val dragging = dragState.draggingKey == section.key
    // 铁律 3：pointerInput 协程只启动一次，捕获首次组合的 lambda；
    // 经 rememberUpdatedState 转发，保证手势回调永远读到最新值
    // （否则 onTap 里冻结的 storedCollapsed 会让分区「展开后关不掉」）。
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragFinish by rememberUpdatedState(onDragFinish)
    Surface(
        // 拖拽中的分区提层 + 位移跟随 + 底色反馈。
        color = if (dragging) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragState.offsetY else 0f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    // 换算成「相对列表原点的坐标」：窗口坐标 - 列表原点。
                    // 这个差值已经把滚动算了进去（内容随滚动整体上移），
                    // 因此列表滚不滚都不影响槽位之间的比较，无需单独读
                    // scrollOffset —— 那反而会和自动滚动赛跑。
                    // 再减掉自身的 translationY，剔除拖动带来的视觉位移。
                    val baseline = coordinates.positionInWindow().y -
                        listWindowTop -
                        (if (dragging) dragState.offsetY else 0f)
                    dragState.measure(
                        key = section.key,
                        baselineTop = baseline,
                        height = coordinates.size.height.toFloat(),
                    )
                }
                .padding(horizontal = 16.dp)
                .pointerInput(section.key) {
                    // 点按：折叠/展开；编辑态下点自建组名改名。
                    // 不传 onLongPress（见文件头铁律 2）。
                    detectTapGestures(onTap = { currentOnTap() })
                }
                .pointerInput(section.key) {
                    // 长按命中即 onDragStart（不等位移）→ 进编辑态并可直接拖。
                    // key 不含 editMode（见文件头铁律 1）。
                    detectDragGesturesAfterLongPress(
                        onDragStart = { currentOnDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.dragBy(dragAmount.y)
                        },
                        onDragEnd = { currentOnDragFinish(true) },
                        onDragCancel = { currentOnDragFinish(false) },
                    )
                }
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editMode) {
                if (section.isMine) {
                    Spacer(modifier = Modifier.size(32.dp))
                } else {
                    IconButton(
                        onClick = onDeleteRequest,
                        modifier = Modifier.size(32.dp),
                    ) {
                        SimplifyIcon(
                            Icons.Default.RemoveCircleOutline,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = section.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W500,
                // 分区标题用主色，与顶栏标题、其它页面的小节标题保持同一套
                // 「标题 = primary」的用法。
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (editMode) {
                SimplifyIcon(
                    Icons.Default.DragHandle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .pointerInput(section.key) {
                            // 编辑态下的直接拖拽入口：按住手柄即拖，无需再次
                            // 长按。独立挂点，不与标题行的点按/长按检测器竞争。
                            detectDragGestures(
                                onDragStart = { currentOnDragStart() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragState.dragBy(dragAmount.y)
                                },
                                onDragEnd = { currentOnDragFinish(true) },
                                onDragCancel = { currentOnDragFinish(false) },
                            )
                        },
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.template_group_count_short,
                        section.templates.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(2.dp))
                SimplifyIcon(
                    if (collapsed) {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 模板行（扁平行：名称 + 型号副标题；长按弹出操作菜单）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateRow(
    template: Template,
    appliedAppCount: Int,
    onMoveToGroup: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val isExclusive = template.type == Template.Type.EXCLUSIVE
    val exclusivePackage = template.packageName?.takeIf { isExclusive }
    // 「是否安装」以包名能否解析为准，不能拿图标是否已解码来判断：图标是异步
    // 加载的，加载期间为 null，若据此判定会对着已安装的应用弹「未安装」。
    val installed = exclusivePackage == null ||
        remember(exclusivePackage) {
            runCatching {
                context.packageManager.getApplicationInfo(exclusivePackage, 0)
            }.isSuccess
        }
    // B8：图标解码放在 IO 线程。原实现在组合期直接 getApplicationIcon + toBitmap，
    // 自适应图标要先渲染到 Bitmap 再读回，主线程做这件事会让列表滚动掉帧；
    // 一轮编辑态下所有模板行同时组合时尤其明显。
    val exclusiveIcon by produceState<ImageBitmap?>(null, exclusivePackage) {
        value = if (exclusivePackage == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(exclusivePackage)
                        .toBitmap()
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }

    // 副标题：型号代码（如 SGT-AL00）优先，其次用户描述。
    val subtitle = remember(template.id, template.configuration, template.description) {
        val model = runCatching { ModuleConfig.fromJson(template.configuration).model.trim() }
            .getOrDefault("")
        model.ifBlank { template.description?.trim().orEmpty() }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (appliedAppCount > 0) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                }
            )
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isExclusive && !installed) {
                        GlobalSnackbarHost.showOnErrorByDismissPrevious(
                            resources.getString(R.string.exclusive_template_app_not_installed)
                        )
                    } else if (isExclusive) {
                        requestEnable.value = true
                        requestEnableTemplate.value = template
                    } else {
                        LocalNavController.current.navigateWithTemplate(
                            NavRoutingTypes.ENABLE_TEMPLATE.name,
                            template,
                        )
                    }
                },
                onLongClick = { menuExpanded = true },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isExclusive) {
                val iconModifier = Modifier
                    .size(25.dp)
                    .padding(bottom = 5.dp)
                // 用局部变量接收，才能对这种委托属性做智能转换。
                val icon = exclusiveIcon
                if (icon == null) {
                    SimplifyImage(
                        Icons.Default.DoNotDisturbOnTotalSilence,
                        modifier = iconModifier,
                    )
                } else {
                    SimplifyImage(icon, iconModifier)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = template.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isExclusive) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.template_badge_exclusive),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (appliedAppCount > 0) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.template_applied_app_count,
                        appliedAppCount,
                        appliedAppCount,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(10.dp, (-5).dp),
        ) {
            SimplifyDropdownMenuItem(text = stringResource(R.string.edit), onClick = {
                menuExpanded = false
                LocalNavController.current.navigateWithTemplate(
                    NavRoutingTypes.EDIT_TEMPLATE.name,
                    template,
                )
            })
            SimplifyDropdownMenuItem(text = stringResource(R.string.export_qr), onClick = {
                menuExpanded = false
                scope.launch {
                    exportTemplateQrCode(template)
                        .onSuccess {
                            GlobalSnackbarHost.showByDismissPrevious(
                                resources.getString(R.string.export_success, it)
                            )
                        }
                        .onFailure {
                            GlobalSnackbarHost.showOnErrorByDismissPrevious(
                                resources.getString(R.string.export_failed, it.message.orEmpty())
                            )
                        }
                }
            })
            SimplifyDropdownMenuItem(
                text = stringResource(R.string.template_group_move),
                onClick = {
                    menuExpanded = false
                    onMoveToGroup()
                },
            )
            SimplifyDropdownMenuItem(text = stringResource(R.string.delete), onClick = {
                menuExpanded = false
                LauncherState.deleteTemplate(template)
            })
        }
    }
}

/** 导出单个模板的分享二维码到下载目录，返回保存位置的 Uri。 */
private suspend fun exportTemplateQrCode(template: Template): Result<android.net.Uri> =
    runCatching {
    val bitmap = withContext(Dispatchers.Default) {
        encodeTemplateQrBitmap(TemplateTransfer.encode(listOf(template)))
    }
    try {
        withContext(Dispatchers.IO) {
            saveBitmapToDownloadDir("Guise-Template-${template.id}.png", bitmap).getOrThrow()
        }
    } finally {
        bitmap.recycle()
    }
}

// ---------------------------------------------------------------------------
// 对话框
// ---------------------------------------------------------------------------

/** 整组删除确认。 */
@Composable
private fun DeleteGroupDialog(
    section: TemplateSection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_group_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.template_group_delete_message,
                    section.displayName,
                    section.templates.size,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 分组创建/重命名共用：非空且不与现有分区重名才可确认。 */
@Composable
private fun GroupNameDialog(
    title: String,
    initialName: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val duplicate = existingNames.any {
        it.equals(trimmed, ignoreCase = true) && !it.equals(initialName, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = duplicate,
                    label = { Text(stringResource(R.string.template_group_name_hint)) },
                )
                if (duplicate) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.template_group_name_duplicate),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onConfirm(trimmed) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 移动模板到分组：null = 自动（按品牌分组），其余为全部自建分组。 */
@Composable
private fun MoveToGroupDialog(
    customGroups: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_group_move)) },
        text = {
            Column {
                (listOf<String?>(null) + customGroups)
                    .forEach { option ->
                        Text(
                            text = option ?: stringResource(R.string.template_group_auto),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
