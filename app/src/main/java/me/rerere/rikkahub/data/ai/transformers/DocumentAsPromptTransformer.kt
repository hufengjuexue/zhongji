package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.files.FileUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = if (ctx.assistant.workspaceId != null) {
                                    resolveWorkspacePath(document)
                                } else {
                                    null
                                }
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = resolveDocumentFile(document) ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = resolveDocumentFile(document)
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }

    private fun resolveDocumentFile(document: UIMessagePart.Document): File? {
        val context = runCatching { get<Context>() }.getOrNull()
        if (context != null) {
            FileUtils.resolveLocalFile(context, document.url)?.let { return it }
        }
        return runCatching { document.url.toUri().toFile() }.getOrNull()
    }
}
