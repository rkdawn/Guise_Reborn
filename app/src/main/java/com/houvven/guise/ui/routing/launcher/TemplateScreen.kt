package com.houvven.guise.ui.routing.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
// 手势两条铁律（历史上各踩过一次，勿违反）：
//   1. pointerInput 的 key 不能包含手势过程中会变化的状态（如 editMode），
//      否则 key 一变 Compose 重启手势协程，进行中的拖拽被掐断；
//   2. 并行的 detectTapGestures 不要传 onLongPress（空 lambda 也不行）——
//      传了它就会参与长按竞争并消费长按事件，把拖拽检测器饿死。
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
 */
private fun buildSections(templates: List<Template>, mineLabel: String): List<TemplateSection> {
    val grouped = templates.groupBy(::templateGroupKey).toMutableMap()
    TemplateGroupStore.customGroups().forEach { name ->
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

// ---------------------------------------------------------------------------
// 分区拖拽排序状态机
// ---------------------------------------------------------------------------

/**
 * 长按（或编辑态按住手柄）开始拖拽：拖动中按实测行高换算目标位置就地
 * 换位，松手后由调用方持久化。行高由布局实时回写——编辑态行高与普通态
 * 不同，必须每次测量，不能用首次值缓存。
 */
@Stable
private class SectionDragState {
    /** 正在拖拽的分区 key；null = 未拖拽。 */
    var draggingKey by mutableStateOf<String?>(null)
        private set

    /** 累计位移：被拖分区视觉跟随 + 换位换算。 */
    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** 分区头行高 + 列表间距，onGloballyPositioned 每次布局回写。 */
    var stridePx by mutableFloatStateOf(0f)

    /** 拖拽中的实时顺序。 */
    val liveOrder = mutableStateListOf<String>()

    val isDragging: Boolean get() = draggingKey != null

    fun start(key: String, currentOrder: List<String>) {
        liveOrder.clear()
        liveOrder.addAll(currentOrder)
        draggingKey = key
        offsetPx = 0f
    }

    fun dragBy(key: String, deltaY: Float) {
        offsetPx += deltaY
        if (stridePx <= 0f) return
        val from = liveOrder.indexOf(key)
        if (from < 0) return
        val target = (from + (offsetPx / stridePx).roundToInt())
            .coerceIn(0, liveOrder.lastIndex)
        if (target != from) {
            liveOrder.add(target, liveOrder.removeAt(from))
            offsetPx -= (target - from) * stridePx
        }
    }

    /** 正常松手：返回待持久化的顺序（未拖过为 null）并重置。 */
    fun finish(): List<String>? {
        val order = if (liveOrder.isNotEmpty()) liveOrder.toList() else null
        reset()
        return order
    }

    fun cancel() = reset()

    private fun reset() {
        draggingKey = null
        offsetPx = 0f
        liveOrder.clear()
    }
}

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
    val sections = remember(templates, groupRevision, mineLabel) {
        buildSections(templates, mineLabel)
    }
    // 折叠状态；缺省折叠，页面打开时是紧凑的索引视图。
    val collapsedKeys = remember { mutableStateMapOf<String, Boolean>() }

    // —— 编辑态 + 拖拽排序 ——
    var editMode by remember { mutableStateOf(false) }
    val dragState = remember { SectionDragState() }

    fun startSectionDrag(key: String) {
        editMode = true
        dragState.start(key, sections.map { it.key })
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
    }

    // 拖拽中按实时顺序渲染，松手后回到正式顺序。
    val visibleSections = if (dragState.isDragging && dragState.liveOrder.isNotEmpty()) {
        dragState.liveOrder.mapNotNull { key -> sections.firstOrNull { it.key == key } }
    } else {
        sections
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
        LazyColumn(
            modifier = Modifier.padding(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding(),
            ),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            visibleSections.forEach { section ->
                val storedCollapsed = collapsedKeys[section.key] != false
                // 编辑态全部强制折叠：标题行等高，拖拽才好对位。
                val collapsed = storedCollapsed || editMode
                stickyHeader(key = "header-${section.key}") {
                    SectionHeader(
                        section = section,
                        collapsed = collapsed,
                        editMode = editMode,
                        dragState = dragState,
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
                existingNames = sections.map { it.displayName },
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
                existingNames = sections.map { it.displayName },
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
    onTap: () -> Unit,
    onDeleteRequest: () -> Unit,
    onDragStart: () -> Unit,
    onDragFinish: (persist: Boolean) -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { SECTION_SPACING.toPx() }
    val dragging = dragState.draggingKey == section.key
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
                translationY = if (dragging) dragState.offsetPx else 0f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    // 每次布局都更新：编辑态行高与普通态不同。
                    dragState.stridePx = coordinates.size.height + spacingPx
                }
                .padding(horizontal = 16.dp)
                .pointerInput(section.key) {
                    // 点按：折叠/展开；编辑态下点自建组名改名。
                    // 不传 onLongPress（见文件头铁律 2）。
                    detectTapGestures(onTap = { onTap() })
                }
                .pointerInput(section.key) {
                    // 长按命中即 onDragStart（不等位移）→ 进编辑态并可直接拖。
                    // key 不含 editMode（见文件头铁律 1）。
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragState.dragBy(section.key, dragAmount.y)
                        },
                        onDragEnd = { onDragFinish(true) },
                        onDragCancel = { onDragFinish(false) },
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
                color = MaterialTheme.colorScheme.onSurface,
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
                                onDragStart = { onDragStart() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragState.dragBy(section.key, dragAmount.y)
                                },
                                onDragEnd = { onDragFinish(true) },
                                onDragCancel = { onDragFinish(false) },
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
                    fontSize = 13.sp,
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
    val exclusiveIcon = remember(isExclusive, template.packageName) {
        if (!isExclusive) {
            null
        } else {
            template.packageName?.let { packageName ->
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap()
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val installed = !isExclusive || exclusiveIcon != null
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
                if (exclusiveIcon == null) {
                    SimplifyImage(
                        Icons.Default.DoNotDisturbOnTotalSilence,
                        modifier = iconModifier,
                    )
                } else {
                    SimplifyImage(exclusiveIcon, iconModifier)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = template.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 15.sp,
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
                        fontSize = 13.sp,
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
                    fontSize = 13.sp,
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
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_group_move)) },
        text = {
            Column {
                (listOf<String?>(null) + TemplateGroupStore.customGroups())
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
