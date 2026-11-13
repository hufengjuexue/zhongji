package me.rerere.rikkahub.data.sync

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupCompatibilityTest {
    private val kairosFilesDir = File("/data/user/0/me.kairos.app/files")

    @Test
    fun `upstream rikkahub settings json migrates, decodes and remaps local urls`() {
        val rikkahubSettingsJson = """
            {
              "sponsorAlertDismissedAt": 1730000000,
              "launchCount": 42,
              "dynamicColor": false,
              "displaySetting": {
                "userAvatar": {
                  "type": "me.rerere.rikkahub.data.model.Avatar.Image",
                  "url": "file:///data/user/0/me.rerere.rikkahub/files/upload/user_avatar.png"
                },
                "chatCustomFontPath": "/data/user/0/me.rerere.rikkahub/files/fonts/LXGW.ttf",
                "backgroundEffectType": "future_unknown_effect"
              },
              "searchServices": [
                {
                  "type": "rikkahub",
                  "apiKey": "rk-test-key",
                  "depth": "deep"
                }
              ],
              "assistants": [
                {
                  "id": "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
                  "name": "Test Role",
                  "avatar": {
                    "type": "me.rerere.rikkahub.data.model.Avatar.Image",
                    "url": "file:///data/data/me.rerere.rikkahub/files/upload/bot_avatar.png"
                  },
                  "background": "file:///data/user/0/me.rerere.rikkahub/files/upload/bg.jpg",
                  "presetMessages": [
                    {
                      "id": "11111111-1111-1111-1111-111111111111",
                      "role": "user",
                      "parts": [
                        {
                          "type": "image",
                          "url": "file:///data/user/0/me.rerere.rikkahub/files/upload/preset.png"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val migrated = SettingsJsonMigrator.migrate(rikkahubSettingsJson)
        val decoded = JsonInstant.decodeFromString<Settings>(migrated)
        assertFalse(decoded.init)
        assertEquals(BackgroundEffectType.BLUR, decoded.displaySetting.backgroundEffectType)
        assertTrue(decoded.searchServices.single() is SearchServiceOptions.RikkaHubOptions)

        val remapped = decoded.remapImportedFileUrls(kairosFilesDir)
        assertEquals(
            Avatar.Image("file:///data/user/0/me.kairos.app/files/upload/user_avatar.png"),
            remapped.displaySetting.userAvatar,
        )
        assertEquals(
            "/data/user/0/me.kairos.app/files/fonts/LXGW.ttf",
            remapped.displaySetting.chatCustomFontPath,
        )
        val assistant = remapped.assistants.single()
        assertEquals(
            Avatar.Image("file:///data/user/0/me.kairos.app/files/upload/bot_avatar.png"),
            assistant.avatar,
        )
        assertEquals(
            "file:///data/user/0/me.kairos.app/files/upload/bg.jpg",
            assistant.background,
        )
        val presetImage = assistant.presetMessages.single().parts.single() as UIMessagePart.Image
        assertEquals(
            "file:///data/user/0/me.kairos.app/files/upload/preset.png",
            presetImage.url,
        )
    }

    @Test
    fun `remapAppFileUrl handles multi-user paths, preserves encodings, and ignores non-app urls`() {
        assertEquals(
            "file:///data/user/0/me.kairos.app/files/upload/my%20pic.png",
            FileUtils.remapAppFileUrl(
                kairosFilesDir,
                "file:///data/user/10/me.rerere.rikkahub/files/upload/my%20pic.png",
            ),
        )
        assertEquals(
            "file:///data/user/0/me.kairos.app/files/media_creation/video/clip.mp4",
            FileUtils.remapAppFileUrl(
                kairosFilesDir,
                "file:///data/data/me.rerere.rikkahub/files/media_creation/video/clip.mp4",
            ),
        )
        assertEquals(
            "file:///data/user/0/me.kairos.app/files/upload/already.png",
            FileUtils.remapAppFileUrl(
                kairosFilesDir,
                "file:///data/user/0/me.kairos.app/files/upload/already.png",
            ),
        )
        assertEquals(
            "https://example.com/files/upload/remote.png",
            FileUtils.remapAppFileUrl(kairosFilesDir, "https://example.com/files/upload/remote.png"),
        )
        assertEquals(
            "fonts/LXGW.ttf",
            FileUtils.remapAppFileUrl(kairosFilesDir, "fonts/LXGW.ttf"),
        )
    }

    @Test
    fun `remapJsonFileUrls rewrites embedded file urls without mutating structure`() {
        val raw = """{"part":{"type":"document","url":"file:///data/user/0/me.rerere.rikkahub/files/upload/doc.pdf"},"count":3}"""
        val element = JsonInstant.parseToJsonElement(raw)
        val remapped = remapJsonFileUrls(element, kairosFilesDir)
        val expected = """{"part":{"type":"document","url":"file:///data/user/0/me.kairos.app/files/upload/doc.pdf"},"count":3}"""
        assertEquals(JsonInstant.parseToJsonElement(expected), remapped)
    }
}
