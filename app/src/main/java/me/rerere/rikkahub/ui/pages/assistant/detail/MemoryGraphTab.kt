package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartScatter
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.GraphNodeType
import me.rerere.rikkahub.data.model.MemoryGraphEdge
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryGraphSnapshot
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.ui.components.RikkaConfirmDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MAX_CANVAS_NODES = 40

@Composable
fun MemoryGraphTab(
    snapshot: MemoryGraphSnapshot,
    enableMemory: Boolean,
    showAddRelationDialog: Boolean,
    onDismissAddRelationDialog: () -> Unit,
    onOpenAddRelationDialog: () -> Unit,
    onUpsertRelation: (
        fromName: String,
        toName: String,
        relation: String,
        fromType: GraphNodeType,
        toType: GraphNodeType,
        excerpt: String,
        replaceRelations: List<String>,
    ) -> Unit,
    onSupersedeEdge: (edgeId: String) -> Unit,
    onRestoreEdge: (edgeId: String) -> Unit,
    onDeleteEdge: (edgeId: String) -> Unit,
    onDeleteNode: (nodeId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<GraphNodeType?>(null) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var showHistoricalSection by remember { mutableStateOf(true) }
    var pendingDeleteNode by remember { mutableStateOf<MemoryGraphNode?>(null) }
    var pendingDeleteEdge by remember { mutableStateOf<MemoryGraphEdge?>(null) }
    var prefillFromNode by remember { mutableStateOf<MemoryGraphNode?>(null) }

    val filteredNodes = remember(snapshot, searchQuery, selectedType) {
        val base = if (searchQuery.isBlank() && selectedType == null) {
            snapshot.nodes
        } else {
            snapshot.matchNodes(searchQuery, selectedType)
        }
        val degreeMap = mutableMapOf<String, Int>()
        snapshot.currentEdges().forEach { edge ->
            degreeMap[edge.fromId] = (degreeMap[edge.fromId] ?: 0) + 1
            degreeMap[edge.toId] = (degreeMap[edge.toId] ?: 0) + 1
        }
        base.sortedWith(
            compareByDescending<MemoryGraphNode> { degreeMap[it.id] ?: 0 }
                .thenByDescending { it.updatedAt }
        )
    }

    val selectedNode = remember(snapshot, selectedNodeId) {
        selectedNodeId?.let { snapshot.nodeMap[it] }
    }

    if (showAddRelationDialog || prefillFromNode != null) {
        AddGraphRelationDialog(
            initialFromName = prefillFromNode?.name.orEmpty(),
            initialFromType = prefillFromNode?.type ?: GraphNodeType.CHARACTER,
            onDismiss = {
                prefillFromNode = null
                onDismissAddRelationDialog()
            },
            onConfirm = { fromName, toName, relation, fromType, toType, excerpt, replaceRelations ->
                onUpsertRelation(
                    fromName,
                    toName,
                    relation,
                    fromType,
                    toType,
                    excerpt,
                    replaceRelations,
                )
                prefillFromNode = null
                onDismissAddRelationDialog()
            },
        )
    }

    if (snapshot.nodes.isEmpty()) {
        ExtensionEmptyState(
            message = stringResource(R.string.assistant_page_graph_empty),
            description = stringResource(
                if (enableMemory) {
                    R.string.assistant_page_graph_empty_hint
                } else {
                    R.string.assistant_page_memory_empty_disabled_hint
                }
            ),
            buttonText = stringResource(
                if (enableMemory) {
                    R.string.assistant_page_graph_add_relation
                } else {
                    R.string.settings
                }
            ),
            onAction = if (enableMemory) onOpenAddRelationDialog else onOpenSettings,
            icon = HugeIcons.ChartScatter,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部统计胶囊
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            ) {
                Text(
                    text = stringResource(
                        R.string.assistant_page_graph_stats,
                        snapshot.nodes.size,
                        snapshot.currentEdges().size,
                        snapshot.historicalEdges().size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 交互式星系网络画布（仅展示当前生效关系边，点击节点聚焦时间线）
        val canvasNodes = remember(filteredNodes) { filteredNodes.take(MAX_CANVAS_NODES) }
        val canvasNodeIds = remember(canvasNodes) { canvasNodes.map { it.id }.toSet() }
        val canvasEdges = remember(snapshot, canvasNodeIds) {
            snapshot.currentEdges().filter { it.fromId in canvasNodeIds && it.toId in canvasNodeIds }
        }
        GraphNetworkCanvasCard(
            nodes = canvasNodes,
            edges = canvasEdges,
            selectedNodeId = selectedNodeId,
            onSelectNode = { nodeId ->
                selectedNodeId = if (selectedNodeId == nodeId) null else nodeId
            },
        )

        // 搜索框与实体类型过滤栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            placeholder = { Text(stringResource(R.string.assistant_page_graph_search_hint)) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedType == null,
                onClick = { selectedType = null },
                label = { Text(stringResource(R.string.assistant_page_graph_type_all)) },
            )
            GraphNodeType.entries.forEach { type ->
                val count = snapshot.nodes.count { it.type == type }
                if (count > 0) {
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = if (selectedType == type) null else type },
                        label = { Text("${nodeTypeLabel(type)} ($count)") },
                    )
                }
            }
        }

        // 选中实体详情与“当前关系 vs 历史时间线”面板
        if (selectedNode != null) {
            SelectedNodeTimelineCard(
                node = selectedNode,
                snapshot = snapshot,
                showHistorical = showHistoricalSection,
                onToggleHistorical = { showHistoricalSection = !showHistoricalSection },
                onAddRelationFromNode = { prefillFromNode = selectedNode },
                onSupersedeEdge = onSupersedeEdge,
                onRestoreEdge = onRestoreEdge,
                onDeleteEdge = { pendingDeleteEdge = it },
                onDeleteNode = { pendingDeleteNode = selectedNode },
                onSelectRelatedNode = { selectedNodeId = it },
            )
        }

        // 实体目录列表
        Text(
            text = stringResource(R.string.assistant_page_graph_entities_title, filteredNodes.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (filteredNodes.isEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.assistant_page_graph_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            CardGroup {
                filteredNodes.forEach { node ->
                    val allEdges = snapshot.edgesOf(node.id, includeHistorical = true)
                    val activeCount = allEdges.count { it.isCurrent }
                    val pastCount = allEdges.size - activeCount
                    val isSelected = node.id == selectedNodeId
                    item(
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(nodeTypeColor(node.type))
                                )
                                Text(
                                    text = node.name,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = nodeTypeColor(node.type).copy(alpha = 0.14f),
                                ) {
                                    Text(
                                        text = nodeTypeLabel(node.type),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = nodeTypeColor(node.type),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            val parts = buildList {
                                if (node.aliases.isNotEmpty()) {
                                    add(node.aliases.joinToString(", "))
                                }
                                if (node.summary.isNotBlank()) {
                                    add(node.summary)
                                }
                                add(
                                    stringResource(
                                        R.string.assistant_page_graph_node_edge_counts,
                                        activeCount,
                                        pastCount,
                                    )
                                )
                            }
                            Text(
                                text = parts.joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            selectedNodeId = if (selectedNodeId == node.id) null else node.id
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    RikkaConfirmDialog(
        show = pendingDeleteNode != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteNode?.let {
                onDeleteNode(it.id)
                if (selectedNodeId == it.id) selectedNodeId = null
            }
            pendingDeleteNode = null
        },
        onDismiss = { pendingDeleteNode = null },
        text = {
            Text(
                text = pendingDeleteNode?.let {
                    stringResource(R.string.assistant_page_graph_delete_node_confirm, it.name)
                }.orEmpty(),
            )
        },
    )

    RikkaConfirmDialog(
        show = pendingDeleteEdge != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteEdge?.let { onDeleteEdge(it.id) }
            pendingDeleteEdge = null
        },
        onDismiss = { pendingDeleteEdge = null },
        text = {
            val edge = pendingDeleteEdge
            val fromName = edge?.let { snapshot.nodeMap[it.fromId]?.name ?: it.fromId }.orEmpty()
            val toName = edge?.let { snapshot.nodeMap[it.toId]?.name ?: it.toId }.orEmpty()
            Text(text = if (edge != null) "$fromName --[${edge.relation}]--> $toName" else "")
        },
    )
}

@Composable
private fun GraphNetworkCanvasCard(
    nodes: List<MemoryGraphNode>,
    edges: List<MemoryGraphEdge>,
    selectedNodeId: String?,
    onSelectNode: (String) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val textMeasurer = rememberTextMeasurer()

    val positions = remember(nodes, edges, canvasSize) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 || nodes.isEmpty()) {
            emptyMap()
        } else {
            computeGraphLayout(
                nodes = nodes,
                edges = edges,
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
            )
        }
    }

    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceBadgeColor = MaterialTheme.colorScheme.surface
    val labelStyle = remember(onSurfaceColor) {
        TextStyle(color = onSurfaceColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
    val edgeLabelStyle = remember(onSurfaceColor) {
        TextStyle(color = onSurfaceColor.copy(alpha = 0.78f), fontSize = 9.sp)
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, outlineColor.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(surfaceColor),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(positions) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(0.55f, 2.8f)
                            zoom = nextZoom
                            panOffset += pan
                        }
                    }
                    .pointerInput(positions, panOffset, zoom) {
                        detectTapGestures { tapOffset ->
                            if (positions.isEmpty()) return@detectTapGestures
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val worldTap = center + (tapOffset - center - panOffset) / zoom
                            val hitRadius = 46f / zoom
                            val hit = positions.entries.minByOrNull { (_, pos) ->
                                hypot(worldTap.x - pos.x, worldTap.y - pos.y)
                            }?.takeIf { (_, pos) ->
                                hypot(worldTap.x - pos.x, worldTap.y - pos.y) <= hitRadius
                            }
                            if (hit != null) {
                                onSelectNode(hit.key)
                            }
                        }
                    },
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                fun toScreen(world: Offset): Offset = center + panOffset + (world - center) * zoom

                // 绘制当前生效的关系边与方向箭头
                edges.forEach { edge ->
                    val p1 = positions[edge.fromId]?.let(::toScreen) ?: return@forEach
                    val p2 = positions[edge.toId]?.let(::toScreen) ?: return@forEach
                    val isHighlighted = selectedNodeId == null ||
                        edge.fromId == selectedNodeId ||
                        edge.toId == selectedNodeId
                    val edgeAlpha = if (isHighlighted) 0.72f else 0.16f
                    val edgeColor = if (selectedNodeId != null && isHighlighted) {
                        primaryColor.copy(alpha = edgeAlpha)
                    } else {
                        onSurfaceColor.copy(alpha = edgeAlpha)
                    }
                    drawLine(
                        color = edgeColor,
                        start = p1,
                        end = p2,
                        strokeWidth = if (selectedNodeId != null && isHighlighted) 3f else 2f,
                    )

                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val dist = hypot(dx, dy)
                    if (dist > 36f) {
                        val nodeRadius = (20f * zoom).coerceIn(14f, 30f)
                        val ux = dx / dist
                        val uy = dy / dist
                        val tip = Offset(p2.x - ux * (nodeRadius + 4f), p2.y - uy * (nodeRadius + 4f))
                        val angle = atan2(uy, ux)
                        val arrowLen = 11f
                        val wing1 = Offset(
                            tip.x - arrowLen * cos(angle - 0.45f),
                            tip.y - arrowLen * sin(angle - 0.45f),
                        )
                        val wing2 = Offset(
                            tip.x - arrowLen * cos(angle + 0.45f),
                            tip.y - arrowLen * sin(angle + 0.45f),
                        )
                        val arrowPath = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(wing1.x, wing1.y)
                            lineTo(wing2.x, wing2.y)
                            close()
                        }
                        drawPath(arrowPath, color = edgeColor)

                        if (isHighlighted && edges.size <= 36) {
                            val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                            val measured = textMeasurer.measure(
                                text = edge.relation.take(10),
                                style = edgeLabelStyle,
                                maxLines = 1,
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    mid.x - measured.size.width / 2f,
                                    mid.y - measured.size.height / 2f,
                                ),
                            )
                        }
                    }
                }

                // 绘制节点与名称标签
                nodes.forEach { node ->
                    val pos = positions[node.id]?.let(::toScreen) ?: return@forEach
                    val baseColor = nodeTypeColor(node.type)
                    val isSelected = node.id == selectedNodeId
                    val isConnected = selectedNodeId == null || isSelected || edges.any {
                        (it.fromId == selectedNodeId && it.toId == node.id) ||
                            (it.toId == selectedNodeId && it.fromId == node.id)
                    }
                    val alpha = if (isConnected) 1f else 0.35f
                    val radius = if (isSelected) (24f * zoom).coerceIn(18f, 34f) else (18f * zoom).coerceIn(12f, 28f)

                    if (isSelected) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.22f),
                            radius = radius + 9f,
                            center = pos,
                        )
                        drawCircle(
                            color = primaryColor,
                            radius = radius + 3f,
                            center = pos,
                            style = Stroke(width = 3f),
                        )
                    }
                    drawCircle(
                        color = surfaceBadgeColor,
                        radius = radius,
                        center = pos,
                    )
                    drawCircle(
                        color = baseColor.copy(alpha = alpha),
                        radius = radius - 2f,
                        center = pos,
                    )

                    val measured = textMeasurer.measure(
                        text = node.name.take(12),
                        style = labelStyle.copy(color = onSurfaceColor.copy(alpha = alpha)),
                        maxLines = 1,
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            pos.x - measured.size.width / 2f,
                            pos.y + radius + 4f,
                        ),
                    )
                }
            }

            // 角落重置视角按钮与提示
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (zoom != 1f || panOffset != Offset.Zero) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ) {
                        IconButton(
                            onClick = {
                                zoom = 1f
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Refresh03,
                                contentDescription = stringResource(R.string.assistant_page_graph_reset_view),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedNodeTimelineCard(
    node: MemoryGraphNode,
    snapshot: MemoryGraphSnapshot,
    showHistorical: Boolean,
    onToggleHistorical: () -> Unit,
    onAddRelationFromNode: () -> Unit,
    onSupersedeEdge: (String) -> Unit,
    onRestoreEdge: (String) -> Unit,
    onDeleteEdge: (MemoryGraphEdge) -> Unit,
    onDeleteNode: () -> Unit,
    onSelectRelatedNode: (String) -> Unit,
) {
    val allEdges = remember(snapshot, node.id) {
        snapshot.edgesOf(node.id, includeHistorical = true)
            .sortedWith(compareByDescending<MemoryGraphEdge> { it.isCurrent }.thenByDescending { it.validFrom })
    }
    val currentEdges = remember(allEdges) { allEdges.filter { it.isCurrent } }
    val pastEdges = remember(allEdges) { allEdges.filter { !it.isCurrent } }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = nodeTypeColor(node.type).copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = nodeTypeLabel(node.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = nodeTypeColor(node.type),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (node.aliases.isNotEmpty()) {
                        Text(
                            text = node.aliases.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (node.summary.isNotBlank()) {
                        Text(
                            text = node.summary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row {
                    IconButton(onClick = onAddRelationFromNode) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.assistant_page_graph_add_relation),
                        )
                    }
                    IconButton(onClick = onDeleteNode) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.assistant_page_graph_current_relations, currentEdges.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (currentEdges.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_page_graph_no_current_relations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                currentEdges.forEach { edge ->
                    GraphEdgeRow(
                        edge = edge,
                        focusNodeId = node.id,
                        snapshot = snapshot,
                        onSelectRelatedNode = onSelectRelatedNode,
                        onToggleState = { onSupersedeEdge(edge.id) },
                        onDelete = { onDeleteEdge(edge) },
                    )
                }
            }

            if (pastEdges.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleHistorical() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clock01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.assistant_page_graph_historical_timeline, pastEdges.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (showHistorical) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = showHistorical) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pastEdges.forEach { edge ->
                            GraphEdgeRow(
                                edge = edge,
                                focusNodeId = node.id,
                                snapshot = snapshot,
                                onSelectRelatedNode = onSelectRelatedNode,
                                onToggleState = { onRestoreEdge(edge.id) },
                                onDelete = { onDeleteEdge(edge) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphEdgeRow(
    edge: MemoryGraphEdge,
    focusNodeId: String,
    snapshot: MemoryGraphSnapshot,
    onSelectRelatedNode: (String) -> Unit,
    onToggleState: () -> Unit,
    onDelete: () -> Unit,
) {
    val fromNode = snapshot.nodeMap[edge.fromId]
    val toNode = snapshot.nodeMap[edge.toId]
    val otherNodeId = if (edge.fromId == focusNodeId) edge.toId else edge.fromId
    val timeRange = remember(edge.validFrom, edge.validTo, edge.isCurrent) {
        val fromText = formatGraphDate(edge.validFrom)
        if (edge.isCurrent) {
            fromText
        } else {
            val toText = edge.validTo?.let(::formatGraphDate) ?: "?"
            "$fromText -> $toText"
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (edge.isCurrent) {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelectRelatedNode(otherNodeId) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "${fromNode?.name ?: edge.fromId}  --[${edge.relation}]-->  ${toNode?.name ?: edge.toId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (edge.isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (edge.excerpt.isNotBlank()) {
                    Text(
                        text = edge.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (edge.isCurrent) {
                        stringResource(R.string.assistant_page_graph_valid_since, timeRange)
                    } else {
                        stringResource(R.string.assistant_page_graph_valid_span, timeRange)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleState) {
                    Text(
                        text = stringResource(
                            if (edge.isCurrent) {
                                R.string.assistant_page_graph_mark_past
                            } else {
                                R.string.assistant_page_graph_restore
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddGraphRelationDialog(
    initialFromName: String,
    initialFromType: GraphNodeType,
    onDismiss: () -> Unit,
    onConfirm: (
        fromName: String,
        toName: String,
        relation: String,
        fromType: GraphNodeType,
        toType: GraphNodeType,
        excerpt: String,
        replaceRelations: List<String>,
    ) -> Unit,
) {
    var fromName by remember(initialFromName) { mutableStateOf(initialFromName) }
    var fromType by remember(initialFromType) { mutableStateOf(initialFromType) }
    var relation by remember { mutableStateOf("") }
    var toName by remember { mutableStateOf("") }
    var toType by remember { mutableStateOf(GraphNodeType.CHARACTER) }
    var excerpt by remember { mutableStateOf("") }
    var replaceOld by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_page_graph_add_relation)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = fromName,
                    onValueChange = { fromName = it },
                    label = { Text(stringResource(R.string.assistant_page_graph_field_from)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GraphNodeType.entries.forEach { type ->
                        FilterChip(
                            selected = fromType == type,
                            onClick = { fromType = type },
                            label = { Text(nodeTypeLabel(type)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = relation,
                    onValueChange = { relation = it },
                    label = { Text(stringResource(R.string.assistant_page_graph_field_relation)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toName,
                    onValueChange = { toName = it },
                    label = { Text(stringResource(R.string.assistant_page_graph_field_to)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GraphNodeType.entries.forEach { type ->
                        FilterChip(
                            selected = toType == type,
                            onClick = { toType = type },
                            label = { Text(nodeTypeLabel(type)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = replaceOld,
                    onValueChange = { replaceOld = it },
                    label = { Text(stringResource(R.string.assistant_page_graph_field_replace_old)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = excerpt,
                    onValueChange = { excerpt = it },
                    label = { Text(stringResource(R.string.assistant_page_graph_field_excerpt)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = fromName.isNotBlank() && relation.isNotBlank() && toName.isNotBlank(),
                onClick = {
                    val replaceList = replaceOld
                        .split(',', '，', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onConfirm(
                        fromName.trim(),
                        toName.trim(),
                        relation.trim(),
                        fromType,
                        toType,
                        excerpt.trim(),
                        replaceList,
                    )
                },
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

@Composable
private fun nodeTypeLabel(type: GraphNodeType): String = when (type) {
    GraphNodeType.CHARACTER -> stringResource(R.string.assistant_page_graph_type_character)
    GraphNodeType.LOCATION -> stringResource(R.string.assistant_page_graph_type_location)
    GraphNodeType.FACTION -> stringResource(R.string.assistant_page_graph_type_faction)
    GraphNodeType.ITEM -> stringResource(R.string.assistant_page_graph_type_item)
    GraphNodeType.VOW -> stringResource(R.string.assistant_page_graph_type_vow)
    GraphNodeType.EVENT -> stringResource(R.string.assistant_page_graph_type_event)
    GraphNodeType.CONCEPT -> stringResource(R.string.assistant_page_graph_type_concept)
}

private fun nodeTypeColor(type: GraphNodeType): Color = when (type) {
    GraphNodeType.CHARACTER -> Color(0xFF6750A4)
    GraphNodeType.LOCATION -> Color(0xFF0B8043)
    GraphNodeType.FACTION -> Color(0xFFC5221F)
    GraphNodeType.ITEM -> Color(0xFFD97706)
    GraphNodeType.VOW -> Color(0xFFAD1457)
    GraphNodeType.EVENT -> Color(0xFF0284C7)
    GraphNodeType.CONCEPT -> Color(0xFF5F6368)
}

private fun formatGraphDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

/**
 * 轻量力导向环形布局（零运行时分配，仅在节点/边/尺寸变化时重算）
 */
private fun computeGraphLayout(
    nodes: List<MemoryGraphNode>,
    edges: List<MemoryGraphEdge>,
    width: Float,
    height: Float,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    val cx = width / 2f
    val cy = height / 2f
    if (nodes.size == 1) return mapOf(nodes.first().id to Offset(cx, cy))

    val padX = 56f.coerceAtMost(width * 0.2f)
    val padY = 48f.coerceAtMost(height * 0.2f)
    val rx = (width - padX * 2f) * 0.36f
    val ry = (height - padY * 2f) * 0.36f

    val posX = FloatArray(nodes.size)
    val posY = FloatArray(nodes.size)
    val indexById = HashMap<String, Int>(nodes.size)

    nodes.forEachIndexed { idx, node ->
        indexById[node.id] = idx
        val angle = (2.0 * PI * idx) / nodes.size
        posX[idx] = cx + (cos(angle) * rx).toFloat()
        posY[idx] = cy + (sin(angle) * ry).toFloat()
    }

    val edgePairs = edges.mapNotNull { edge ->
        val a = indexById[edge.fromId] ?: return@mapNotNull null
        val b = indexById[edge.toId] ?: return@mapNotNull null
        if (a != b) a to b else null
    }

    val idealLen = min(width, height) * 0.32f
    val dispX = FloatArray(nodes.size)
    val dispY = FloatArray(nodes.size)

    repeat(48) { step ->
        dispX.fill(0f)
        dispY.fill(0f)
        val temp = (1f - step / 48f) * 18f + 2f

        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val dx = posX[i] - posX[j]
                val dy = posY[i] - posY[j]
                val dist = max(12f, hypot(dx, dy))
                val rep = (idealLen * idealLen) / (dist * 1.4f)
                val fx = (dx / dist) * rep * 0.08f
                val fy = (dy / dist) * rep * 0.08f
                dispX[i] += fx
                dispY[i] += fy
                dispX[j] -= fx
                dispY[j] -= fy
            }
        }

        for ((a, b) in edgePairs) {
            val dx = posX[a] - posX[b]
            val dy = posY[a] - posY[b]
            val dist = max(12f, hypot(dx, dy))
            val attr = (dist - idealLen) * 0.14f
            val fx = (dx / dist) * attr
            val fy = (dy / dist) * attr
            dispX[a] -= fx
            dispY[a] -= fy
            dispX[b] += fx
            dispY[b] += fy
        }

        for (i in nodes.indices) {
            // 轻微向中心拉力避免飘散
            dispX[i] += (cx - posX[i]) * 0.03f
            dispY[i] += (cy - posY[i]) * 0.03f
            val mag = max(1f, hypot(dispX[i], dispY[i]))
            val clamp = min(mag, temp)
            posX[i] = (posX[i] + (dispX[i] / mag) * clamp).coerceIn(padX, width - padX)
            posY[i] = (posY[i] + (dispY[i] / mag) * clamp).coerceIn(padY, height - padY)
        }
    }

    return buildMap(nodes.size) {
        nodes.forEachIndexed { idx, node ->
            put(node.id, Offset(posX[idx], posY[idx]))
        }
    }
}
