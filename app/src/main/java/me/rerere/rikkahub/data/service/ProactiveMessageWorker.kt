package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.uuid.Uuid

class ProactiveMessageWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val chatService: ChatService by inject()

    companion object {
        private const val TAG = "ProactiveMessageWorker"
        private const val UNIQUE_WORK_NAME = "kairos_proactive_message_work"
        private const val ONESHOT_WORK_NAME = "kairos_proactive_message_oneshot"
        private const val KEY_MANUAL = "manual"

        fun scheduleNext(context: Context, setting: ProactiveMessageSetting) {
            val safe = setting.normalized()
            if (!safe.enabled) {
                cancel(context)
                return
            }
            val delayMinutes = Random.nextInt(safe.minIntervalMinutes, safe.maxIntervalMinutes + 1)
            val request = OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            Log.i(TAG, "Scheduled next proactive check in $delayMinutes min")
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            wm.cancelUniqueWork(ONESHOT_WORK_NAME)
        }

        private fun isInQuietHours(hour: Int, setting: ProactiveMessageSetting): Boolean {
            if (!setting.quietHoursEnabled) return false
            val start = setting.quietStartHour.coerceIn(0, 23)
            val end = setting.quietEndHour.coerceIn(0, 23)
            return if (start <= end) {
                hour in start until end
            } else {
                hour >= start || hour < end
            }
        }
    }

    override suspend fun doWork(): Result {
        val settings = settingsStore.awaitLoaded()
        val proactive = settings.proactiveMessageSetting.normalized()
        val isManual = inputData.getBoolean(KEY_MANUAL, false)
        if (!proactive.enabled && !isManual) {
            return Result.success()
        }
        try {
            if (!isManual && isInQuietHours(LocalTime.now().hour, proactive)) {
                Log.d(TAG, "In quiet hours, skipping proactive check")
                return Result.success()
            }

            val targetConv = proactive.conversationId
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { conversationRepository.getConversationById(it) }
                ?: run {
                    val assistantId = proactive.assistantId
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?: settings.assistantId
                    conversationRepository.getConversationsOfAssistant(assistantId)
                        .first()
                        .maxByOrNull { it.updateAt }
                }

            if (targetConv == null || targetConv.currentMessages.isEmpty()) {
                return Result.success()
            }

            val assistant = settings.getAssistantOf(targetConv)
            val model = settings.getChatModelOf(targetConv) ?: return Result.success()
            val providerSetting = model.findProvider(settings.providers) ?: return Result.success()

            val systemText = buildString {
                if (assistant.systemPrompt.isNotBlank()) {
                    append(assistant.systemPrompt)
                    append("\n\n")
                }
                append(
                    "Write one natural, in-character proactive follow-up message continuing this conversation, " +
                        "or reply exactly [PASS] if it is better to stay quiet."
                )
            }
            val historyTail = targetConv.currentMessages.takeLast(12)
            val requestMessages = buildList {
                add(UIMessage.system(systemText))
                addAll(historyTail)
                add(
                    UIMessage.user(
                        "[System note: Current time is ${LocalTime.now().withSecond(0).withNano(0)}. " +
                            "Respond in character naturally or output [PASS] to skip.]"
                    )
                )
            }

            val response = providerManager.getProviderByType(providerSetting).generateText(
                providerSetting = providerSetting,
                messages = requestMessages,
                params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature,
                    topP = assistant.topP,
                    topK = assistant.topK,
                    maxTokens = assistant.maxTokens ?: 300,
                    disableReasoning = true,
                    systemPromptInChat = assistant.enableAntiEmptyResponse,
                    enableProxyFix = assistant.enableProxyFix,
                    sessionId = targetConv.id.toString(),
                ),
            )
            val text = response.message.toText().trim()
            if (text.isBlank() || "[PASS]" in text) {
                return Result.success()
            }

            chatService.appendProactiveAssistantMessage(
                conversationId = targetConv.id,
                message = UIMessage.assistant(text),
            )

            val senderName = assistant.name.ifBlank {
                applicationContext.getString(R.string.assistant_page_default_assistant)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                targetConv.id.hashCode(),
                conversationIntent(applicationContext, targetConv.id.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            applicationContext.sendNotification(
                channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                notificationId = targetConv.id.hashCode(),
            ) {
                title = senderName
                content = text.take(120)
                autoCancel = true
                useDefaults = true
                category = NotificationCompat.CATEGORY_MESSAGE
                contentIntent = pendingIntent
            }
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Proactive message failed", e)
            return Result.success()
        } finally {
            val latest = settingsStore.settingsFlow.value.proactiveMessageSetting.normalized()
            if (latest.enabled) {
                scheduleNext(applicationContext, latest)
            }
        }
    }
}
