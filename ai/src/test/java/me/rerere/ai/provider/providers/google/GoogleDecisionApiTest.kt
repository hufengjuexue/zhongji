package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.DecisionGenerationParams
import me.rerere.ai.provider.DecisionQuestion
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoogleDecisionApiTest {
    private val provider = GoogleProvider(OkHttpClient())
    private val decisionModel = Model(
        modelId = "gemini-decision-1",
        displayName = "Gemini Decision 1",
        type = ModelType.DECISION,
    )

    @Test
    fun `buildDecisionsRequestBody matches official v1beta decisions schema`() {
        val body = provider.buildDecisionsRequestBody(
            DecisionGenerationParams(
                model = decisionModel,
                input = "[User]: 今天去哪吃？",
                questions = linkedMapOf(
                    "c_0" to DecisionQuestion.Score(
                        instructions = "Relevance check",
                        criteria = listOf("0: irrelevant", "1: tangential", "2: relevant", "3: essential"),
                    ),
                    "c_1" to DecisionQuestion.Noul(
                        instructions = "Binary check",
                        trueDescription = "Relevant",
                        falseDescription = "Irrelevant",
                    ),
                ),
            ),
        )

        assertEquals("gemini-decision-1", body["model"]?.jsonPrimitive?.content)
        assertEquals("[User]: 今天去哪吃？", body["input"]?.jsonPrimitive?.content)
        val questions = body["questions"]!!.jsonObject
        val scoreQ = questions["c_0"]!!.jsonObject
        assertEquals("score", scoreQ["type"]?.jsonPrimitive?.content)
        assertEquals("Relevance check", scoreQ["instructions"]?.jsonPrimitive?.content)
        assertEquals(4, scoreQ["criteria"]!!.jsonArray.size)
        assertEquals("0: irrelevant", scoreQ["criteria"]!!.jsonArray[0].jsonPrimitive.content)

        val noulQ = questions["c_1"]!!.jsonObject
        assertEquals("noul", noulQ["type"]?.jsonPrimitive?.content)
        assertEquals("Relevant", noulQ["criteria"]!!.jsonObject["true_description"]?.jsonPrimitive?.content)
        assertEquals("Irrelevant", noulQ["criteria"]!!.jsonObject["false_description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseDecisionsResponse parses scores confidence and usage`() {
        val response = json.parseToJsonElement(
            """
            {
              "answers": {
                "c_0": { "type": "score", "score": 2.65, "confidence": 0.91 },
                "c_1": { "type": "score", "score": 0.35, "confidence": 0.82 }
              },
              "usage": {
                "total_input_tokens": 120,
                "total_output_tokens": 0,
                "total_tokens": 120
              }
            }
            """.trimIndent()
        ).jsonObject

        val parsed = provider.parseDecisionsResponse(response)
        assertEquals(2.65, parsed.answers["c_0"]?.score ?: -1.0, 0.001)
        assertEquals(0.91, parsed.answers["c_0"]?.confidence ?: -1.0, 0.001)
        assertEquals(0.35, parsed.answers["c_1"]?.score ?: -1.0, 0.001)
        assertNotNull(parsed.usage)
        assertEquals(120, parsed.usage?.promptTokens)
        assertEquals(120, parsed.usage?.totalTokens)
    }
}
