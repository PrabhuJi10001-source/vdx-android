package com.vdx.memory

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * On-device Room memory for the phone app.
 *
 * Contacts, prefs, aliases, a small action log — SQLite on the device.
 * Not Agent OS, not KGS, not a cloud graph. Caps keep recall cheap on a phone.
 */
class MemoryStore(private val context: Context) {

    private val db = VdxMemoryDatabase.getInstance(context)
    private val nodeDao = db.memoryNodeDao()
    private val edgeDao = db.memoryEdgeDao()
    private val episodeDao = db.actionEpisodeDao()
    private val contradictionDao = db.memoryContradictionDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun remember(
        type: String,
        name: String,
        value: String,
        contextNote: String = "",
        aliases: String = "",
        source: String = "user",
        scope: String = "global"
    ) {
        kotlinx.coroutines.runBlocking {
            rememberSuspend(type, name, value, contextNote, aliases, source, scope)
        }
    }

    suspend fun rememberSuspend(
        type: String,
        name: String,
        value: String,
        contextNote: String = "",
        aliases: String = "",
        source: String = "user",
        scope: String = "global"
    ) {
        val key = name.lowercase().trim()
        val existing = nodeDao.getByName(key) ?: nodeDao.getByName(name)
        if (existing != null) {
            if (existing.value != value) {
                contradictionDao.insert(
                    MemoryContradiction(
                        nodeIdA = existing.id,
                        nodeIdB = existing.id,
                        fieldA = "value",
                        valueA = existing.value,
                        valueB = value
                    )
                )
            }
            nodeDao.upsert(
                existing.copy(
                    type = type,
                    value = value,
                    context = contextNote,
                    aliases = aliases.ifBlank { existing.aliases },
                    source = source,
                    scope = scope,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            if (nodeDao.count() >= MAX_NODES) return
            nodeDao.upsert(
                MemoryNode(
                    type = type,
                    name = key,
                    value = value,
                    context = contextNote,
                    aliases = aliases.ifBlank { key },
                    source = source,
                    scope = scope
                )
            )
        }
    }

    suspend fun recall(name: String): String? {
        val key = name.lowercase().trim()
        val node = nodeDao.getByName(key)
            ?: nodeDao.getByName(name)
            ?: nodeDao.searchByAlias(key).firstOrNull()
            ?: nodeDao.searchByAlias(name).firstOrNull()
        if (node != null) {
            nodeDao.incrementReads(node.id)
            return node.value
        }
        return null
    }

    fun recallSync(name: String): String? = try {
        kotlinx.coroutines.runBlocking { recall(name) }
    } catch (_: Exception) {
        null
    }

    suspend fun getRelated(name: String, relationType: String? = null): List<MemoryNode> {
        val node = nodeDao.getByName(name.lowercase()) ?: nodeDao.getByName(name) ?: return emptyList()
        val edges = edgeDao.getEdgesForNode(node.id)
            .filter { relationType == null || it.relationType == relationType }
        return edges.mapNotNull { edge ->
            val otherId = if (edge.sourceId == node.id) edge.targetId else edge.sourceId
            nodeDao.getById(otherId)
        }
    }

    suspend fun recallPath(startName: String, maxDepth: Int = MAX_GRAPH_DEPTH): List<MemoryNode> {
        val start = nodeDao.getByName(startName.lowercase()) ?: nodeDao.getByName(startName) ?: return emptyList()
        val visited = linkedSetOf<Long>()
        var frontier = listOf(start.id)
        repeat(maxDepth) {
            val next = mutableListOf<Long>()
            for (id in frontier) {
                for (target in edgeDao.getTargetsFrom(id)) {
                    if (visited.add(target)) next.add(target)
                }
            }
            frontier = next
            if (frontier.isEmpty()) return@repeat
        }
        return visited.mapNotNull { nodeDao.getById(it) }
    }

    fun relate(sourceName: String, targetName: String, relationType: String) {
        scope.launch {
            val source = nodeDao.getByName(sourceName.lowercase()) ?: nodeDao.getByName(sourceName) ?: return@launch
            val target = nodeDao.getByName(targetName.lowercase()) ?: nodeDao.getByName(targetName) ?: return@launch
            edgeDao.insert(
                MemoryEdge(sourceId = source.id, targetId = target.id, relationType = relationType)
            )
        }
    }

    fun forget(name: String) {
        kotlinx.coroutines.runBlocking {
            val node = nodeDao.getByName(name.lowercase()) ?: nodeDao.getByName(name) ?: return@runBlocking
            edgeDao.deleteEdgesForNode(node.id)
            nodeDao.deleteById(node.id)
        }
    }

    suspend fun search(query: String): List<MemoryNode> = nodeDao.search(query)

    /**
     * Ranked, deduped, relevance-scored retrieval.
     * Charter: retrieve only relevant memories; rank by relevance AND recency;
     * deduplicate overlapping data. Returns scored nodes, most relevant first.
     */
    suspend fun retrieveRelevant(query: String, maxResults: Int = MAX_RECALL, scopeFilter: String? = null): List<ScoredMemory> {
        val t = query.lowercase().trim()
        val words = t.split(Regex("""\s+""")).filter { it.length > 2 }

        val matches = LinkedHashMap<Long, ScoredMemory>()
        // exact-name / alias hits rank highest
        val byName = nodeDao.getByName(t) ?: nodeDao.search(t).firstOrNull()
        if (byName != null) {
            matches[byName.id] = ScoredMemory(byName, 1.0f, System.currentTimeMillis() - byName.lastReadAt)
        }
        // token matches (relevance = token overlap, then weighted by vitality & recency)
        for (word in words) {
            nodeDao.search(word).forEach { node ->
                if (scopeFilter != null && node.scope != scopeFilter && node.scope != "global") return@forEach
                val relevance = computeRelevance(node, word, t)
                val existing = matches[node.id]
                if (existing == null || relevance > existing.score) {
                    matches[node.id] = ScoredMemory(node, relevance, System.currentTimeMillis() - node.lastReadAt)
                }
            }
        }
        return matches.values
            .sortedWith(compareByDescending<ScoredMemory> { it.score }.thenBy { it.ageReadMs })
            .take(maxResults)
    }

    private fun computeRelevance(node: MemoryNode, word: String, fullQuery: String): Float {
        val tokens = node.name.split(Regex("""\s+""")) + node.aliases.split(',').map { it.trim().lowercase() }
        var score = 0.0f
        if (node.name == word) score += 1.0f
        if (tokens.any { it == word }) score += 0.6f
        if (node.value.lowercase().contains(word)) score += 0.3f
        if (node.context.lowercase().contains(fullQuery)) score += 0.2f
        return score.coerceAtMost(1.0f)
    }

    data class ScoredMemory(
        val node: MemoryNode,
        val score: Float,
        val ageReadMs: Long
    )


    suspend fun getByType(type: String): List<MemoryNode> = nodeDao.getByType(type)

    suspend fun getTopMemories(limit: Int = 20): List<MemoryNode> = nodeDao.getTopByVitality(limit)

    suspend fun count(): Int = nodeDao.count()

    fun computeVitality() {
        scope.launch {
            val nodes = nodeDao.getAll()
            for (node in nodes) {
                val decayRate = TYPE_DECAY[node.type] ?: 0.02f
                val daysSinceRead = (System.currentTimeMillis() - node.lastReadAt) / 86_400_000f
                val score = (
                    (node.reads7d * 15f) +
                        (node.reads30d * 3f) -
                        (node.correctionEvents * 10f) -
                        (daysSinceRead * decayRate * 100f)
                    ).coerceIn(0f, 100f)
                val state = when {
                    score >= 80f -> "thriving"
                    score >= 50f -> "active"
                    score >= 20f -> "fading"
                    score >= 1f -> "dormant"
                    else -> "extinct"
                }
                nodeDao.updateVitality(node.id, score, state)
            }
        }
    }

    fun recordCorrection(name: String) {
        scope.launch {
            val node = nodeDao.getByName(name.lowercase()) ?: nodeDao.getByName(name) ?: return@launch
            nodeDao.recordCorrection(node.id)
        }
    }

    fun recordEpisode(
        goal: String,
        action: String,
        target: String,
        outcome: String,
        memoriesReferenced: List<Long> = emptyList(),
        errorDetail: String = "",
        durationMs: Long = 0
    ) {
        scope.launch {
            episodeDao.insert(
                ActionEpisode(
                    goal = goal,
                    action = action,
                    target = target,
                    outcome = outcome,
                    memoriesReferenced = memoriesReferenced.joinToString(",", prefix = "[", postfix = "]"),
                    errorDetail = errorDetail,
                    durationMs = durationMs
                )
            )
        }
    }

    suspend fun getUnresolvedContradictions(): List<MemoryContradiction> =
        contradictionDao.getUnresolved()

    suspend fun getVocabularyAliases(): List<String> {
        return nodeDao.getAll().flatMap { node ->
            listOf(node.name) + node.aliases.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }.distinct()
    }

    companion object {
        /** Phone cap — this is not a knowledge graph service. */
        const val MAX_NODES = 500
        const val MAX_RECALL = 5
        const val MAX_GRAPH_DEPTH = 2

        private val TYPE_DECAY = mapOf(
            "fact" to 0.01f,
            "rule" to 0.01f,
            "preference" to 0.02f,
            "habit" to 0.02f,
            "error-pattern" to 0.02f,
            "decision" to 0.03f,
            "assumption" to 0.09f,
            "goal" to 0.00f,
            "warning" to 0.00f,
            "hypothesis" to 0.06f,
            "temporary" to 0.08f,
            "contact" to 0.01f,
            "location" to 0.02f,
            "relationship" to 0.01f,
            "default" to 0.02f
        )
    }
}
