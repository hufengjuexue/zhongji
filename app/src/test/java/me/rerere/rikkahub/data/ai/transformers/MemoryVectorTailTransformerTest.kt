package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.provider.DecisionAnswer
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.resolveDecisionModel
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryVectorTailTransformerTest {
    private val sampleChunks = listOf(
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

    @Test
    fun `lexical fallback recalls relevant Chinese and English chunks in order`() {
        val hits = MemoryVectorTailTransformer.recallByLexical(
            query = "还记得实验室和白袜那件事吗？",
            chunks = sampleChunks,
        )

        assertEquals(1, hits.size)
        assertEquals("/topics/fetish.md", hits.first().path)
    }

    @Test
    fun `lexical fallback returns empty for unrelated query`() {
        val hits = MemoryVectorTailTransformer.recallByLexical(
            query = "量子纠缠超导实验报告",
            chunks = sampleChunks.take(1),
        )

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `rankDecisionAnswers filters low scores and preserves path-order sorting`() {
        val questions = MemoryVectorTailTransformer.buildDecisionQuestions(sampleChunks)
        assertEquals(setOf("c_0", "c_1", "c_2"), questions.keys)

        val ranked = MemoryVectorTailTransformer.rankDecisionAnswers(
            candidates = sampleChunks,
            answers = mapOf(
                "c_0" to DecisionAnswer(score = 0.4, confidence = 0.9),
                "c_1" to DecisionAnswer(score = 2.7, confidence = 0.85),
                "c_2" to DecisionAnswer(score = 1.9, confidence = 0.8),
            ),
        )

        assertEquals(listOf("/history/dialogue", "/topics/fetish.md"), ranked.map { it.path })
    }

    @Test
    fun `parseListwiseIndices extracts valid indices handles NONE and rejects noise`() {
        assertEquals(
            listOf(2, 0),
            MemoryVectorTailTransformer.parseListwiseIndices("2, 0, 99, 2", candidateCount = 3),
        )
        assertEquals(
            emptyList<Int>(),
            MemoryVectorTailTransformer.parseListwiseIndices("NONE", candidateCount = 3),
        )
        assertNull(
            MemoryVectorTailTransformer.parseListwiseIndices("sorry I cannot decide", candidateCount = 3),
        )
    }

    @Test
    fun `buildCoarseCandidatePool unions embedding and lexical without duplicates`() {
        val pool = MemoryVectorTailTransformer.buildCoarseCandidatePool(
            query = "冰美式 白袜",
            chunks = sampleChunks,
            scoredEmbed = listOf(
                sampleChunks[0] to 0.5f,
                sampleChunks[1] to 0.05f,
            ),
            threshold = 0.25f,
        )

        assertEquals(2, pool.size)
        assertTrue(sampleChunks[0] in pool)
        assertTrue(sampleChunks[1] in pool)
    }

    @Test
    fun `resolveDecisionModel picks explicit chat or decision model and auto-picks decision only`() {
        val chatModel = Model(modelId = "gemini-2.5-flash-lite", displayName = "Flash Lite", type = ModelType.CHAT)
        val decisionModel = Model(modelId = "gemini-decision-1", displayName = "Decision 1", type = ModelType.DECISION)
        val google = ProviderSetting.Google(
            name = "Google",
            enabled = true,
            models = listOf(chatModel, decisionModel),
        )
        val googleChatOnly = ProviderSetting.Google(
            name = "Google Chat Only",
            enabled = true,
            models = listOf(chatModel),
        )

        // 未指定时自动发现 Decision 模型，不擅自劫持普通 Chat 模型
        assertEquals(
            decisionModel.id,
            Settings(providers = listOf(google)).resolveDecisionModel()?.second?.id,
        )
        assertNull(
            Settings(providers = listOf(googleChatOnly)).resolveDecisionModel(),
        )

        // 显式指定轻量 Chat 模型作精排时正常解析
        assertEquals(
            chatModel.id,
            Settings(providers = listOf(googleChatOnly), vectorDecisionModelId = chatModel.id)
                .resolveDecisionModel()?.second?.id,
        )
    }
}
