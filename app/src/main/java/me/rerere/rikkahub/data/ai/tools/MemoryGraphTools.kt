package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.GraphNodeType
import me.rerere.rikkahub.data.model.MemoryGraphEdge
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.repository.GraphEdgeInput
import me.rerere.rikkahub.data.repository.GraphEntityInput
import me.rerere.rikkahub.data.repository.GraphRecallMode
import me.rerere.rikkahub.data.repository.GraphRecallResult
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import java.time.Instant

object MemoryGraphToolNames {
    const val RECALL_RELATIONS = "recall_relations"
    const val UPSERT_RELATIONS = "graph_upsert_relations"

    val ALL = setOf(RECALL_RELATIONS, UPSERT_RELATIONS)
}

/**
 * 时序关系图谱工具：按需召回实体多跳脉络 / 历史状态演变，并在背景整理或显式记忆更新时写入或更替关系边。
 */
fun buildMemoryGraphTools(
    repository: MemoryGraphRepository,
    assistantId: String,
    sourceConversationId: String? = null,
): List<Tool> = listOf(
    Tool(
        name = MemoryGraphToolNames.RECALL_RELATIONS,
        description = """
            Query the temporal relationship graph for characters, factions, locations, items, vows, events, or concepts.
            - mode="network" (default): 1-3 hop active relation chains (current facts only).
            - mode="timeline": chronological history of edges involving the entity, including superseded/past states (validFrom -> validTo).
            - mode="neighbors": direct current relations of the matched entity.
            - mode="search": match entity nodes without traversing edges.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("entity", graphStringSchema("Entity name, alias, or search keyword"))
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("network")
                            add("timeline")
                            add("neighbors")
                            add("search")
                        })
                        put("description", "Query mode: network, timeline, neighbors, or search (default: network)")
                    })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            GraphNodeType.entries.forEach { add(it.wireName) }
                        })
                        put("description", "Optional entity type filter")
                    })
                    put("depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Traversal depth for network mode (1-3, default 3)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max matched nodes / edges to return (1-30, default 12)")
                    })
                },
                required = listOf("entity"),
            )
        },
        execute = { args ->
            val entityQuery = args.optString("entity").orEmpty().trim()
            if (entityQuery.isEmpty()) error("entity is required")

            val mode = GraphRecallMode.fromWire(args.optString("mode"))
            val typeFilter = args.optString("type")?.let { GraphNodeType.fromWire(it) }
            val depth = (args.optInt("depth") ?: 3).coerceIn(1, 3)
            val limit = (args.optInt("limit") ?: 12).coerceIn(1, 30)

            val result = repository.recallRelations(
                assistantId = assistantId,
                entityQuery = entityQuery,
                mode = mode,
                typeFilter = typeFilter,
                depth = depth,
                limit = limit,
            )
            formatRecallOutput(entityQuery, result)
        },
    ),
    Tool(
        name = MemoryGraphToolNames.UPSERT_RELATIONS,
        description = """
            Insert or update entities and temporal relationship edges in the memory graph.
            When a relation evolves or replaces an old state (e.g. enemy -> ally, located_in A -> located_in B, holds -> returned),
            pass supersede_edge_ids and/or replace_relation_types so the previous edge is marked historical (is_current=false, valid_to set) instead of overwritten destructively.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("entities", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional entities to register or enrich with type/aliases/summary")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("name", graphStringSchema("Canonical display name"))
                                put("type", graphStringSchema("character | location | faction | item | vow | event | concept"))
                                put("aliases", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "string") })
                                })
                                put("summary", graphStringSchema("Brief canon note on this entity"))
                            })
                            put("required", buildJsonArray { add("name") })
                        })
                    })
                    put("edges", buildJsonObject {
                        put("type", "array")
                        put("description", "Directed relationship facts from -> relation -> to")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("from", graphStringSchema("Source entity name"))
                                put("to", graphStringSchema("Target entity name"))
                                put("relation", graphStringSchema("Concise verb/label describing the relation (e.g. 信任, 宿敌, 持有, 位于, 约定)"))
                                put("from_type", graphStringSchema("Optional source node type"))
                                put("to_type", graphStringSchema("Optional target node type"))
                                put("excerpt", graphStringSchema("Short evidence snippet from the scene/dialogue"))
                                put("supersede_edge_ids", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "string") })
                                    put("description", "Existing edge IDs from recall_relations that this new relation supersedes")
                                })
                                put("replace_relation_types", buildJsonObject {
                                    put("type", "array")
                                    put("items", buildJsonObject { put("type", "string") })
                                    put("description", "Prior relation labels on from->to (or outgoing location/holder state) to mark historical")
                                })
                            })
                            put("required", buildJsonArray {
                                add("from")
                                add("to")
                                add("relation")
                            })
                        })
                    })
                },
            )
        },
        execute = { args ->
            val rawEntities = (args.jsonObject["entities"] as? JsonArray).orEmpty()
            val rawEdges = (args.jsonObject["edges"] as? JsonArray).orEmpty()

            val entities = rawEntities.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = obj.optString("name")?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                GraphEntityInput(
                    name = name,
                    type = GraphNodeType.fromWire(obj.optString("type")),
                    aliases = obj.stringList("aliases"),
                    summary = obj.optString("summary").orEmpty().trim(),
                )
            }

            val edges = rawEdges.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val from = obj.optString("from")?.trim().orEmpty()
                val to = obj.optString("to")?.trim().orEmpty()
                val relation = obj.optString("relation")?.trim().orEmpty()
                if (from.isEmpty() || to.isEmpty() || relation.isEmpty()) return@mapNotNull null
                GraphEdgeInput(
                    fromName = from,
                    toName = to,
                    relation = relation,
                    fromType = obj.optString("from_type")?.let { GraphNodeType.fromWire(it) },
                    toType = obj.optString("to_type")?.let { GraphNodeType.fromWire(it) },
                    excerpt = obj.optString("excerpt").orEmpty().trim(),
                    supersedeEdgeIds = obj.stringList("supersede_edge_ids"),
                    replaceRelationTypes = obj.stringList("replace_relation_types"),
                )
            }

            if (entities.isEmpty() && edges.isEmpty()) {
                return@Tool graphToolOutput {
                    put("error", "At least one entity or edge is required.")
                }
            }

            val forbidden = findForbiddenInGraphInputs(entities, edges)
            if (forbidden != null) {
                return@Tool graphToolOutput {
                    put(
                        "error",
                        "Refused: input appears to contain $forbidden, which is never stored in memory graph."
                    )
                }
            }

            val summary = repository.upsertGraph(
                assistantId = assistantId,
                entities = entities,
                edges = edges,
                sourceConversationId = sourceConversationId,
            )
            graphToolOutput {
                put("nodes_created", summary.nodesCreated)
                put("nodes_updated", summary.nodesUpdated)
                put("edges_created", summary.edgesCreated)
                put("edges_updated", summary.edgesUpdated)
                put("edges_superseded", summary.edgesSuperseded)
                put("touched_edge_ids", buildJsonArray {
                    summary.touchedEdgeIds.forEach { add(it) }
                })
            }
        },
    ),
)

private fun findForbiddenInGraphInputs(
    entities: List<GraphEntityInput>,
    edges: List<GraphEdgeInput>,
): String? {
    entities.forEach { entity ->
        findForbiddenMemoryContent(entity.name)?.let { return it }
        findForbiddenMemoryContent(entity.summary)?.let { return it }
        entity.aliases.forEach { alias ->
            findForbiddenMemoryContent(alias)?.let { return it }
        }
    }
    edges.forEach { edge ->
        findForbiddenMemoryContent(edge.fromName)?.let { return it }
        findForbiddenMemoryContent(edge.toName)?.let { return it }
        findForbiddenMemoryContent(edge.relation)?.let { return it }
        findForbiddenMemoryContent(edge.excerpt)?.let { return it }
    }
    return null
}

private fun formatRecallOutput(
    entityQuery: String,
    result: GraphRecallResult,
): List<UIMessagePart> = graphToolOutput {
    val nodeMap = result.snapshot.nodeMap
    put("mode", result.mode.wireName)
    put("entity", entityQuery)

    put("matched_nodes", buildJsonArray {
        result.matchedNodes.forEach { node ->
            add(nodeToJson(node))
        }
    })

    put("edges", buildJsonArray {
        result.edges.forEach { edge ->
            add(edgeToJson(edge, nodeMap))
        }
    })

    if (result.paths.isNotEmpty()) {
        put("paths", buildJsonArray {
            result.paths.forEach { chain ->
                add(formatChain(chain, nodeMap))
            }
        })
    }

    put("summary_text", buildSummaryText(entityQuery, result, nodeMap))
    if (result.matchedNodes.isEmpty()) {
        put(
            "hint",
            "No matching entity found in the relation graph. Do not retry with identical keywords; check memory_listing or continue in-scene."
        )
    }
}

private fun nodeToJson(node: MemoryGraphNode): JsonObject = buildJsonObject {
    put("id", node.id)
    put("name", node.name)
    put("type", node.type.wireName)
    if (node.aliases.isNotEmpty()) {
        put("aliases", buildJsonArray { node.aliases.forEach { add(it) } })
    }
    if (node.summary.isNotBlank()) {
        put("summary", node.summary)
    }
}

private fun edgeToJson(
    edge: MemoryGraphEdge,
    nodeMap: Map<String, MemoryGraphNode>,
): JsonObject = buildJsonObject {
    put("id", edge.id)
    put("from_id", edge.fromId)
    put("from_name", nodeMap[edge.fromId]?.name ?: edge.fromId)
    put("to_id", edge.toId)
    put("to_name", nodeMap[edge.toId]?.name ?: edge.toId)
    put("relation", edge.relation)
    put("is_current", edge.isCurrent)
    put("valid_from", Instant.ofEpochMilli(edge.validFrom).toString())
    edge.validTo?.let { put("valid_to", Instant.ofEpochMilli(it).toString()) }
    if (edge.excerpt.isNotBlank()) {
        put("excerpt", edge.excerpt)
    }
}

private fun formatChain(
    chain: List<MemoryGraphEdge>,
    nodeMap: Map<String, MemoryGraphNode>,
): String {
    if (chain.isEmpty()) return ""
    return buildString {
        chain.forEachIndexed { index, edge ->
            val fromName = nodeMap[edge.fromId]?.name ?: edge.fromId
            val toName = nodeMap[edge.toId]?.name ?: edge.toId
            if (index == 0) append(fromName)
            append(" --[").append(edge.relation).append("]--> ").append(toName)
        }
    }
}

private fun buildSummaryText(
    entityQuery: String,
    result: GraphRecallResult,
    nodeMap: Map<String, MemoryGraphNode>,
): String = buildString {
    if (result.matchedNodes.isEmpty()) {
        append("No graph entity matched \"$entityQuery\".")
        return@buildString
    }
    appendLine("Matched nodes (${result.matchedNodes.size}):")
    result.matchedNodes.forEach { node ->
        append("- [${node.type.wireName}] ${node.name}")
        if (node.summary.isNotBlank()) append(": ${node.summary}")
        appendLine()
    }
    if (result.edges.isNotEmpty()) {
        appendLine("Edges (${result.edges.size}):")
        result.edges.forEach { edge ->
            val fromName = nodeMap[edge.fromId]?.name ?: edge.fromId
            val toName = nodeMap[edge.toId]?.name ?: edge.toId
            val status = if (edge.isCurrent) "CURRENT" else "PAST -> ${edge.validTo?.let { Instant.ofEpochMilli(it).toString().take(10) } ?: "?"}"
            val since = Instant.ofEpochMilli(edge.validFrom).toString().take(10)
            append("- [$status | since $since | id=${edge.id}] $fromName --[${edge.relation}]--> $toName")
            if (edge.excerpt.isNotBlank()) append(" (${edge.excerpt})")
            appendLine()
        }
    } else {
        appendLine("Edges: (none)")
    }
}.trimEnd()

private fun graphToolOutput(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))

private fun graphStringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun JsonElement.optString(key: String): String? =
    (jsonObject[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement.optInt(key: String): Int? =
    (jsonObject[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        .filter { it.isNotEmpty() }
