package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharaCardImportTest {
    @Test
    fun `parseEmbeddedCharacterBook unpacks array entries and metadata`() {
        val data = Json.parseToJsonElement(
            """
            {
              "name": "测试角色",
              "character_book": {
                "name": "学院设定集",
                "scan_depth": 6,
                "entries": [
                  {
                    "keys": ["禁书库", "档案室"],
                    "content": "禁书库夜间需要管理员指纹。",
                    "comment": "禁书库门禁",
                    "enabled": true,
                    "constant": false,
                    "insertion_order": 50,
                    "position": "before_char",
                    "extensions": { "depth": 2, "scan_depth": 5 }
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject

        val book = parseEmbeddedCharacterBook(data, "测试角色")
        assertNotNull(book)
        assertEquals("学院设定集", book!!.name)
        assertEquals(1, book.entries.size)
        val entry = book.entries.single()
        assertEquals(listOf("禁书库", "档案室"), entry.keywords)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, entry.position)
        assertEquals(2, entry.injectDepth)
        assertEquals(5, entry.scanDepth)
        assertEquals(50, entry.priority)
    }

    @Test
    fun `parseEmbeddedRegexes converts js regex literals and placement`() {
        val data = Json.parseToJsonElement(
            """
            {
              "extensions": {
                "regex_scripts": [
                  {
                    "scriptName": "剥离思考标签",
                    "findRegex": "/<think>[\\s\\S]*?<\\/think>/gi",
                    "replaceString": "",
                    "disabled": false,
                    "markdownOnly": true,
                    "placement": [2]
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject

        val regexes = parseEmbeddedRegexes(data)
        assertEquals(1, regexes.size)
        val regex = regexes.single()
        assertEquals("剥离思考标签", regex.name)
        assertEquals("(?i)<think>[\\s\\S]*?<\\/think>", regex.findRegex)
        assertTrue(regex.visualOnly)
        assertEquals(setOf(AssistantAffectScope.ASSISTANT), regex.affectingScope)
    }
}
