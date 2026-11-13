package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphTest {
    @Test
    fun `normalizeGraphName collapses whitespace case and punctuation`() {
        assertEquals(
            MemoryGraphSnapshot.normalizeGraphName("Silver Blade"),
            MemoryGraphSnapshot.normalizeGraphName("  silver-blade "),
        )
        assertEquals("苍白哨塔", MemoryGraphSnapshot.normalizeGraphName("【苍白·哨塔】"))
    }

    @Test
    fun `findNetworkPaths ignores superseded historical edges`() {
        val alice = MemoryGraphNode(
            id = "n1",
            assistantId = "a1",
            type = GraphNodeType.CHARACTER,
            name = "Alice",
            nameNormalized = "alice",
            aliases = listOf("艾莉丝"),
        )
        val bob = MemoryGraphNode(
            id = "n2",
            assistantId = "a1",
            type = GraphNodeType.CHARACTER,
            name = "Bob",
            nameNormalized = "bob",
        )
        val guild = MemoryGraphNode(
            id = "n3",
            assistantId = "a1",
            type = GraphNodeType.FACTION,
            name = "Night Watch",
            nameNormalized = "nightwatch",
        )

        val oldEnemyEdge = MemoryGraphEdge(
            id = "e1",
            assistantId = "a1",
            fromId = alice.id,
            toId = bob.id,
            relation = "宿敌",
            validFrom = 100L,
            validTo = 200L,
            isCurrent = false,
        )
        val currentAllyEdge = MemoryGraphEdge(
            id = "e2",
            assistantId = "a1",
            fromId = alice.id,
            toId = bob.id,
            relation = "盟友",
            validFrom = 200L,
            isCurrent = true,
        )
        val guildEdge = MemoryGraphEdge(
            id = "e3",
            assistantId = "a1",
            fromId = bob.id,
            toId = guild.id,
            relation = "效力于",
            validFrom = 150L,
            isCurrent = true,
        )

        val snapshot = MemoryGraphSnapshot(
            nodes = listOf(alice, bob, guild),
            edges = listOf(oldEnemyEdge, currentAllyEdge, guildEdge),
        )

        val matched = snapshot.matchNodes("艾莉丝")
        assertEquals(listOf(alice), matched)

        val paths = snapshot.findNetworkPaths(setOf(alice.id), maxDepth = 2, limit = 10)
        val visitedEdgeIds = paths.flatten().map { it.id }.toSet()
        assertTrue("e2" in visitedEdgeIds)
        assertTrue("e3" in visitedEdgeIds)
        assertTrue("e1" !in visitedEdgeIds)
    }
}
