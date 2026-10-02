package com.houvven.guise.ui.routing.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoNotDisturbOnTotalSilence
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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

private val requestEnable = mutableStateOf(false)
private val requestEnableTemplate = mutableStateOf<Template?>(null)

/** Brand identity used for grouping; blank brands collapse into one "other" bucket. */
private fun templateBrandKey(template: Template): String =
    runCatching { ModuleConfig.fromJson(template.configuration).brand.trim() }
        .getOrNull().orEmpty().lowercase()

private const val GROUP_KEY_MINE = " mine"

/** 自建分组的 section key 前缀，避免与品牌 key 冲突。 */
private const val GROUP_KEY_PREFIX_CUSTOM = "custom:"

/**
 * Canonical brand display names keyed by lowercase brand. Source data mixes
 * casings (samsung/Redmi/REDMI/vivo); sections always show the official form.
 * Unknown brands (e.g. user-created) fall back to their most frequent casing.
 */
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

/**
 * Section grouping: a template explicitly assigned to a custom group goes
 * there; otherwise templates with a brand go to that brand's section; all
 * brand-less templates form the first "我的模板" section.
 */
private fun templateGroupKey(template: Template): String {
    template.groupName?.takeIf { it.isNotBlank() }
        ?.let { return GROUP_KEY_PREFIX_CUSTOM + it }
    val brand = templateBrandKey(template)
    if (brand.isNotEmpty()) return brand
    return GROUP_KEY_MINE
}

/**
 * Numeric-aware name ordering: digit runs compare by value, everything else
 * case-insensitively. Keeps model generations intuitive
 * (K70 < K80 < K90 < K100, Xiaomi 13 < 14 < 15 < 17).
 */
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
            // BigInteger: digit runs can exceed Long (e.g. incremental build ids).
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateCard(
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
    var expanded by remember { mutableStateOf(false) }

    val headIcon = @Composable {
        if (isExclusive) {
            val modifier = Modifier
                .size(25.dp)
                .padding(bottom = 5.dp)
            if (exclusiveIcon == null) {
                SimplifyImage(
                    Icons.Default.DoNotDisturbOnTotalSilence,
                    modifier = modifier,
                )
            } else {
                SimplifyImage(exclusiveIcon, modifier)
            }
        }
    }

    val content = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            headIcon()
            if (isExclusive) {
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = template.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
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
                if (!template.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = template.description!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
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
    }

    Card(
        modifier = Modifier
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
                        val navHostController = LocalNavController.current
                        navHostController.navigateWithTemplate(
                            NavRoutingTypes.ENABLE_TEMPLATE.name,
                            template,
                        )
                    }
                },
                onLongClick = { expanded = true },
            ),
        shape = RoundedCornerShape(10.dp),
    ) {
        content()
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(10.dp, (-5).dp),
        ) {
            SimplifyDropdownMenuItem(text = stringResource(R.string.edit), onClick = {
                expanded = false
                LocalNavController.current.navigateWithTemplate(
                    NavRoutingTypes.EDIT_TEMPLATE.name,
                    template,
                )
            })
            SimplifyDropdownMenuItem(text = stringResource(R.string.export_qr), onClick = {
                expanded = false
                scope.launch {
                    val result = runCatching {
                        val bitmap = withContext(Dispatchers.Default) {
                            encodeTemplateQrBitmap(TemplateTransfer.encode(listOf(template)))
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                saveBitmapToDownloadDir(
                                    "Guise-Template-${template.id}.png",
                                    bitmap,
                                ).getOrThrow()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    result.onSuccess {
                        GlobalSnackbarHost.showByDismissPrevious(
                            resources.getString(R.string.export_success, it)
                        )
                    }.onFailure {
                        GlobalSnackbarHost.showOnErrorByDismissPrevious(
                            resources.getString(R.string.export_failed, it.message.orEmpty())
                        )
                    }
                }
            })
            SimplifyDropdownMenuItem(text = stringResource(R.string.template_group_move), onClick = {
                expanded = false
                onMoveToGroup()
            })
            SimplifyDropdownMenuItem(text = stringResource(R.string.delete), onClick = {
                expanded = false
                LauncherState.deleteTemplate(template)
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TemplateScreen() {
    val navController = LocalNavController.current
    val templates = LauncherState.templates.value
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

    // 自建分组对话框：创建 / 重命名；移动模板到分组的目标模板。
    // 声明在 topBar 之前，菜单「建组」需要引用。
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var renameGroupTarget by remember { mutableStateOf<String?>(null) }
    var moveGroupTemplate by remember { mutableStateOf<Template?>(null) }
    var showManageDialog by remember { mutableStateOf(false) }

    val topBar = @Composable {
        var topBarMenuExpanded by remember { mutableStateOf(false) }
        var transferDialog by remember { mutableStateOf<TemplateTransferMode?>(null) }
        TopAppBar(
            title = { Text(stringResource(R.string.action_template)) },
            actions = {
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
                        text = stringResource(R.string.template_group_manage),
                        onClick = {
                            topBarMenuExpanded = false
                            showManageDialog = true
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
            },
        )
    }

    val floatingButton = @Composable {
        FloatingActionButton(
            onClick = { navController.navigate(NavRoutingTypes.ADD_TEMPLATE.name) },
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            SimplifyIcon(Icons.Default.Add)
        }
    }

    // Single-page collapsible sections: section key -> (display name,
    // name-sorted templates). "我的模板" (brand-less) first, then custom groups,
    // then manufacturers alphabetically by display name. Custom groups come
    // from TemplateGroupStore so empty groups survive; groupRevision forces
    // recomposition when the registry changes without touching templates.
    var groupRevision by remember { mutableIntStateOf(0) }
    val sections = remember(templates, groupRevision) {
        val grouped = templates.groupBy(::templateGroupKey).toMutableMap()
        TemplateGroupStore.customGroups().forEach { name ->
            grouped.putIfAbsent(GROUP_KEY_PREFIX_CUSTOM + name, emptyList())
        }
        grouped.map { (key, group) ->
            val display = when {
                key == GROUP_KEY_MINE -> ""
                key.startsWith(GROUP_KEY_PREFIX_CUSTOM) ->
                    key.removePrefix(GROUP_KEY_PREFIX_CUSTOM)
                else -> canonicalBrandDisplayNames[key]
                    // Unknown brand: most frequent original casing wins.
                    ?: group.map {
                        runCatching { ModuleConfig.fromJson(it.configuration).brand.trim() }
                            .getOrDefault("")
                    }.groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key.orEmpty()
            }
            val sorted = group.sortedWith { x, y -> naturalNameCompare(x.name, y.name) }
            Triple(key, display, sorted)
        }.sortedWith(
            // 手动排序优先；未记录过的分区（新增品牌/分组）按默认规则排在末尾。
            compareBy<Triple<String, String, List<Template>>> {
                TemplateGroupStore.sectionOrder().indexOf(it.first)
                    .let { index -> if (index >= 0) index else Int.MAX_VALUE }
            }.thenBy {
                when {
                    it.first == GROUP_KEY_MINE -> 0
                    it.first.startsWith(GROUP_KEY_PREFIX_CUSTOM) -> 1
                    else -> 2
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second }
        )
    }

    // Collapse state per section; sections default to collapsed so the page
    // opens as a compact manufacturer index.
    val collapsedSections = remember { mutableStateMapOf<String, Boolean>() }

    // 长按分区标题直接在列表内拖拽排序：拖拽期间全部分区强制折叠为等高
    // 标题行，松手后顺序持久化。headerStridePx 为实测标题行高 + 列表间距。
    var draggingSectionKey by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }
    val liveOrder = remember { mutableStateListOf<String>() }
    var headerStridePx by remember { mutableFloatStateOf(0f) }
    val sectionSpacingPx = with(LocalDensity.current) { 8.dp.toPx() }

    val displaySections = if (draggingSectionKey != null && liveOrder.isNotEmpty()) {
        liveOrder.mapNotNull { key -> sections.firstOrNull { it.first == key } }
    } else {
        sections
    }

    // Brand sections (not "我的模板") can be deleted as a whole; holds the
    // (key, display name, templates) of the group awaiting confirmation.
    var pendingDeleteGroup by remember {
        mutableStateOf<Triple<String, String, List<Template>>?>(null)
    }

    @Composable
    fun sectionDisplayName(key: String, brandDisplay: String): String =
        if (key == GROUP_KEY_MINE) stringResource(R.string.template_group_mine) else brandDisplay

    Scaffold(
        topBar = topBar,
        floatingActionButton = floatingButton,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding(),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            displaySections.forEach { (key, brandDisplay, groupTemplates) ->
                val storedCollapsed = collapsedSections[key] != false
                // 拖拽排序期间全部分区强制折叠，标题行等高才好对位。
                val collapsed = storedCollapsed || draggingSectionKey != null
                stickyHeader(key = "header-$key") {
                    val sectionName = sectionDisplayName(key, brandDisplay)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .zIndex(if (key == draggingSectionKey) 1f else 0f)
                            .graphicsLayer {
                                translationY =
                                    if (key == draggingSectionKey) draggingOffset else 0f
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    if (headerStridePx == 0f) {
                                        headerStridePx =
                                            coordinates.size.height + sectionSpacingPx
                                    }
                                }
                                .combinedClickable(
                                    onClick = {
                                        collapsedSections[key] = !storedCollapsed
                                    },
                                    // 长按不给单击让路，拖拽手势接管；空实现仅
                                    // 用于避免长按松手后误触发折叠切换。
                                    onLongClick = {},
                                )
                                .pointerInput(key) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            liveOrder.clear()
                                            liveOrder.addAll(sections.map { it.first })
                                            draggingSectionKey = key
                                            draggingOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingOffset += dragAmount.y
                                            if (headerStridePx > 0f) {
                                                val from = liveOrder.indexOf(key)
                                                if (from >= 0) {
                                                    val target = (from +
                                                        (draggingOffset / headerStridePx)
                                                            .roundToInt())
                                                        .coerceIn(0, liveOrder.lastIndex)
                                                    if (target != from) {
                                                        liveOrder.add(
                                                            target,
                                                            liveOrder.removeAt(from),
                                                        )
                                                        draggingOffset -=
                                                            (target - from) * headerStridePx
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            if (liveOrder.isNotEmpty()) {
                                                TemplateGroupStore.saveSectionOrder(
                                                    liveOrder.toList()
                                                )
                                            }
                                            draggingSectionKey = null
                                            draggingOffset = 0f
                                            liveOrder.clear()
                                            groupRevision++
                                        },
                                        onDragCancel = {
                                            draggingSectionKey = null
                                            draggingOffset = 0f
                                            liveOrder.clear()
                                        },
                                    )
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.template_group_header,
                                    sectionName,
                                    groupTemplates.size,
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SimplifyIcon(
                                if (collapsed) {
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                }
                            )
                        }
                    }
                }
                if (!collapsed) {
                    // 单列整行卡片：两列并排会把长型号名挤成省略号。
                    groupTemplates.forEach { template ->
                        item(key = "tpl-${template.id}") {
                            TemplateCard(
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

        pendingDeleteGroup?.let { (key, display, groupTemplates) ->
            AlertDialog(
                onDismissRequest = { pendingDeleteGroup = null },
                title = { Text(stringResource(R.string.template_group_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.template_group_delete_message,
                            display,
                            groupTemplates.size,
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (key.startsWith(GROUP_KEY_PREFIX_CUSTOM)) {
                                LauncherState.deleteGroup(display, groupTemplates)
                                groupRevision++
                            } else {
                                LauncherState.deleteTemplates(groupTemplates)
                            }
                            pendingDeleteGroup = null
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteGroup = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // 创建分组：名称唯一（不与已有分组/品牌分区重名）。
        if (showCreateGroupDialog) {
            GroupNameDialog(
                title = stringResource(R.string.template_group_create),
                initialName = "",
                existingNames = sections.map { it.second },
                onDismiss = { showCreateGroupDialog = false },
                onConfirm = { name ->
                    TemplateGroupStore.add(name)
                    groupRevision++
                    showCreateGroupDialog = false
                },
            )
        }

        // 重命名自建分组：名册与组内模板一起改。
        renameGroupTarget?.let { oldName ->
            GroupNameDialog(
                title = stringResource(R.string.template_group_rename),
                initialName = oldName,
                existingNames = sections.map { it.second },
                onDismiss = { renameGroupTarget = null },
                onConfirm = { newName ->
                    LauncherState.renameGroup(oldName, newName)
                    groupRevision++
                    renameGroupTarget = null
                },
            )
        }

        // 分组管理：改名/删除集中在这个弹窗，主界面不放任何管理按钮。
        if (showManageDialog) {
            AlertDialog(
                onDismissRequest = { showManageDialog = false },
                title = { Text(stringResource(R.string.template_group_manage)) },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(
                            sections.filter { it.first != GROUP_KEY_MINE },
                            key = { it.first },
                        ) { (key, display, groupTemplates) ->
                            val name = sectionDisplayName(key, display)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.template_group_header,
                                        name,
                                        groupTemplates.size,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (key.startsWith(GROUP_KEY_PREFIX_CUSTOM)) {
                                    TextButton(onClick = { renameGroupTarget = name }) {
                                        Text(stringResource(R.string.template_group_rename))
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        pendingDeleteGroup =
                                            Triple(key, name, groupTemplates)
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showManageDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // 移动模板到分组：「自动」恢复按品牌分组。
        moveGroupTemplate?.let { template ->
            AlertDialog(
                onDismissRequest = { moveGroupTemplate = null },
                title = { Text(stringResource(R.string.template_group_move)) },
                text = {
                    Column {
                        // null = 自动（按品牌分组），其余为全部自建分组。
                        (listOf<String?>(null) + TemplateGroupStore.customGroups())
                            .forEach { option ->
                                Text(
                                    text = option
                                        ?: stringResource(R.string.template_group_auto),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            LauncherState.setTemplateGroup(template, option)
                                            moveGroupTemplate = null
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { moveGroupTemplate = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/** 分组命名/重命名对话框：非空且不与现有分区重名才可确认。 */
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
