package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorTailTransformerTest {
    @Test
    fun `lexical fallback recalls relevant Chinese and English chunks in order`() {
        val chunks = listOf(
            MemoryVectorTailTransformer.RecallChunk(
                namespace = "mem_chunk",
                path = "/topics/life.md",
                title = "生活日常",
                order = 0,
                text = "平时喜欢喝冰美式和深夜写 Kotlin 代码。",
            ),
            MemoryVectorTailTransformer.RecallChunk(
                namespace = "mem_chunk",
                path = "/topics/fetish.md",
                title = "衣着偏好",
                order = 1,
                text = "在实验室那次穿着白袜和百褶裙。",
            ),
            MemoryVectorTailTransformer.RecallChunk(
                namespace = "dialog_chunk",
                path = "/history/dialogue",
                title = "早期对话回顾",
                order = 2,
                text = "用户: 上次我们约好去海边看日出。\n角色: 好，我记得带防晒。",
            ),
        )

        val hits = MemoryVectorTailTransformer.recallByLexical(
            query = "还记得实验室和白袜那件事吗？",
            chunks = chunks,
        )

        assertEquals(1, hits.size)
        assertEquals("/topics/fetish.md", hits.first().path)
    }

    @Test
    fun `lexical fallback returns empty for unrelated query`() {
        val chunks = listOf(
            MemoryVectorTailTransformer.RecallChunk(
                namespace = "mem_chunk",
                path = "/topics/life.md",
                title = "生活",
                order = 0,
                text = "今天下午去图书馆借了一本拓扑学教材。",
            ),
        )

        val hits = MemoryVectorTailTransformer.recallByLexical(
            query = "量子纠缠超导实验报告",
            chunks = chunks,
        )

        assertTrue(hits.isEmpty())
    }
}
