package com.houvven.guise.ui.routing.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoNotDisturbOnTotalSilence
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.houvven.guise.R
import com.houvven.guise.db.Template
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

private val requestEnable = mutableStateOf(false)
private val requestEnableTemplate = mutableStateOf<Template?>(null)

/** Brand identity used for grouping; blank brands collapse into one "other" bucket. */
private fun templateBrandKey(template: Template): String =
    runCatching { ModuleConfig.fromJson(template.configuration).brand.trim() }
        .getOrNull().orEmpty().lowercase()

private const val GROUP_KEY_MINE = " mine"

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
    "vivo" to "vivo",
    "iqoo" to "iQOO",
    "samsung" to "Samsung",
    "apple" to "Apple",
    "asus" to "ASUS",
    "nubia" to "nubia",
    "google" to "Google",
)

/**
 * Section grouping: templates with a brand go to that brand's section (bundled
 * or user-created alike); all brand-less templates (user-created or bundled
 * generic ones) form the first "我的模板" section.
 */
private fun templateGroupKey(template: Template): String {
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
private fun TemplateCard(template: Template, appliedAppCount: Int) {
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
    // name-sorted templates). "我的模板" (brand-less) first, then manufacturers
    // alphabetically by display name.
    val sections = remember(templates) {
        templates.groupBy(::templateGroupKey)
            .map { (key, group) ->
                val display = canonicalBrandDisplayNames[key]
                    // Unknown brand: most frequent original casing wins.
                    ?: group.map {
                        runCatching { ModuleConfig.fromJson(it.configuration).brand.trim() }
                            .getOrDefault("")
                    }.groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key.orEmpty()
                val sorted = group.sortedWith { x, y -> naturalNameCompare(x.name, y.name) }
                Triple(key, display, sorted)
            }
            .sortedWith(
                compareBy<Triple<String, String, List<Template>>> {
                    if (it.first == GROUP_KEY_MINE) 0 else 1
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second }
            )
    }

    // Collapse state per section; sections default to collapsed so the page
    // opens as a compact manufacturer index.
    val collapsedSections = remember { mutableStateMapOf<String, Boolean>() }

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
            sections.forEach { (key, brandDisplay, groupTemplates) ->
                val collapsed = collapsedSections[key] != false
                stickyHeader(key = "header-$key") {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { collapsedSections[key] = !collapsed }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.template_group_header,
                                    sectionDisplayName(key, brandDisplay),
                                    groupTemplates.size,
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
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
                    // Two models per row; a trailing single item gets a spacer
                    // so both columns stay equally wide.
                    groupTemplates.chunked(2).forEachIndexed { rowIndex, pair ->
                        item(key = "row-$key-$rowIndex") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { template ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        TemplateCard(
                                            template = template,
                                            appliedAppCount = appliedCounts[template.id] ?: 0,
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        requestEnableTemplate.value?.let {
            EnableTemplateDialog(state = requestEnable, template = it)
        }
    }
}
