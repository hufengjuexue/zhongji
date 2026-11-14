package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ui.toast.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.KeyRotationMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.supportsQuota
import me.rerere.ai.util.GoogleQuotaBucket
import me.rerere.ai.util.KeyCooldownReason
import me.rerere.ai.util.KeyPoolItemSnapshot
import me.rerere.ai.util.KeyPoolMonitor
import me.rerere.ai.util.maskApiKey
import me.rerere.ai.util.parseApiKeys
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.account.AccountLoginStatus
import me.rerere.rikkahub.data.ai.account.ChatGPTAuth
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.rikkahub.data.ai.account.ProviderAccountManager
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.UserCircle
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.ai.longLabel
import me.rerere.rikkahub.ui.components.ai.rememberProviderQuota
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.toMessageTimeString
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.time.toJavaInstant

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    allowTypeSwitch: Boolean = false,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        provider.description()

        CardGroup {
            // 提供商创建后类型就定死了，只有模型的提供商覆盖可以换类型
            if (allowTypeSwitch) {
                formItem {
                    ConnectedChoiceButtons(
                        options = ProviderSetting.Types,
                        selected = provider::class,
                        onSelect = { onEdit(provider.convertTo(it)) },
                    ) { type, selected ->
                        ConnectedChoiceLabel(text = type.simpleName ?: "", selected = selected)
                    }
                }
            }
            switchItem(
                checked = provider.enabled,
                onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_enable)) },
            )
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
            is ProviderSetting.ChatGPT -> ProviderConfigureAccount(
                provider = provider,
                onEdit = onEdit,
                serviceName = "ChatGPT",
                plan = provider.credential?.plan?.let { "ChatGPT ${ChatGPTAuth.planDisplayName(it)}" },
                disclaimer = stringResource(R.string.setting_provider_page_chatgpt_disclaimer),
            )

            is ProviderSetting.Grok -> ProviderConfigureAccount(
                provider = provider,
                onEdit = onEdit,
                serviceName = "Grok",
                plan = null,
                disclaimer = stringResource(R.string.setting_provider_page_grok_disclaimer),
            )
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    // 账号凭据不能带到可以自填地址的类型上，从账号类转过来时 key、地址和额度查询都用目标类型的默认值
    val source = this as? ProviderSetting.ApiKey
    val apiKey = source?.apiKey.orEmpty()
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = source?.baseUrl?.convertToTargetBaseUrl(targetDefaultBaseUrl) ?: targetDefaultBaseUrl

    val converted: ProviderSetting.ApiKey = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            customHeaders = this.customHeaders, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl
        )
        else -> error("Unsupported provider type: $type")
    }
    // 沿用用户配的额度查询
    return source?.let { converted.withQuotaQuery(it.quotaQuery) } ?: converted
}

internal fun ProviderSetting.ApiKey.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider is ProviderSetting.ApiKey && defaultProvider::class == this::class) {
        return defaultProvider.baseUrl
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.ApiKey.isUsingDefaultBaseUrl(): Boolean = baseUrl == defaultBaseUrlForReset()

// 地址不是默认值时，输入框右侧出现的恢复默认按钮
private fun resetBaseUrlButton(
    provider: ProviderSetting.ApiKey,
    onReset: () -> Unit,
): (@Composable () -> Unit)? {
    if (provider.isUsingDefaultBaseUrl()) return null
    return {
        IconButton(onClick = onReset) {
            Icon(
                imageVector = HugeIcons.Refresh03,
                contentDescription = stringResource(R.string.setting_model_page_reset_to_default),
            )
        }
    }
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

// 标题在上、输入框在下的表单项；secret 为 true 时默认遮住内容，右侧是显示/隐藏按钮
private fun CardGroupScope.textFieldItem(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    secret: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    monospace: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(label)) },
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono)
        } else LocalTextStyle.current,
        supportingText = supportingText,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            }
        } else trailingIcon,
    )
}

private fun CardGroupScope.nameItem(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_name,
    value = provider.name,
    onValueChange = { onEdit(provider.copyProvider(name = it)) },
)

private fun CardGroupScope.apiKeyItem(
    apiKey: String,
    onValueChange: (String) -> Unit,
) = textFieldItem(
    label = R.string.setting_provider_page_api_key,
    value = apiKey,
    onValueChange = { onValueChange(it.trim()) },
    secret = true,
    maxLines = 3,
)

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)

    CardGroup {
        nameItem(provider, onEdit)
        apiKeyItem(provider.apiKey) { onEdit(provider.copy(apiKey = it)) }
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
        textFieldItem(
            label = R.string.setting_provider_page_api_path,
            value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
            onValueChange = {
                onEdit(
                    if (provider.useResponseApi) {
                        provider.copy(responsesPath = it.trim())
                    } else {
                        provider.copy(chatCompletionsPath = it.trim())
                    }
                )
            },
            enabled = !provider.builtIn,
        )
    }

    CardGroup {
        switchItem(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_response_api)) },
        )
        switchItem(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_include_history_reasoning)) },
        )
    }
}

// 账号类提供商：登录状态、用量和说明
@Composable
private fun ProviderConfigureAccount(
    provider: ProviderSetting.Account,
    onEdit: (provider: ProviderSetting) -> Unit,
    serviceName: String,
    plan: String?,
    disclaimer: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountManager = koinInject<ProviderAccountManager>()
    val loginStatus by accountManager.loginStatus.collectAsStateWithLifecycle()
    val status = loginStatus[provider.id]
    val credential = provider.credential

    CardGroup {
        nameItem(provider, onEdit)
    }

    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_account)) }) {
        if (credential != null) {
            item(
                leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                supportingContent = plan?.let { { Text(it) } },
                headlineContent = { Text(credential.email ?: stringResource(R.string.setting_provider_page_signed_in)) },
            )
            item(
                onClick = { scope.launch { accountManager.logout(provider.id) } },
                leadingContent = {
                    CardGroupIcon(
                        icon = HugeIcons.Logout03,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_sign_out)) },
            )
        } else if (status is AccountLoginStatus.DeviceCode) {
            item(
                onClick = { CustomTabsOAuthAuthorizationLauncher.launch(context, status.verificationUrl) },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                overlineContent = { Text(stringResource(R.string.setting_provider_page_device_code_hint)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_page_device_code_reopen)) },
                headlineContent = {
                    Text(
                        text = status.userCode,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        fontFamily = JetbrainsMono,
                    )
                },
            )
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { CardGroupIcon(HugeIcons.Cancel01) },
                headlineContent = { Text(stringResource(R.string.cancel)) },
            )
        } else if (status == AccountLoginStatus.Authorizing) {
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_page_waiting_authorization_desc)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_waiting_authorization)) },
            )
        } else {
            item(
                onClick = { accountManager.startLogin(provider.id, context) },
                leadingContent = { CardGroupIcon(HugeIcons.Login03) },
                supportingContent = (status as? AccountLoginStatus.Failed)?.let { failed ->
                    { Text(failed.message, color = MaterialTheme.colorScheme.error) }
                },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_sign_in_with, serviceName)) },
            )
        }
    }

    if (provider.supportsQuota) {
        AccountUsage(provider)
    }

    Text(
        text = disclaimer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// 订阅用量：每个窗口一条进度和重置时间
@Composable
private fun AccountUsage(provider: ProviderSetting.Account) {
    val result = rememberProviderQuota(provider)
    val limits = result?.getOrNull() as? ProviderQuota.UsageLimits
    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_usage)) }) {
        when {
            result == null -> item(
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_loading)) },
            )

            limits == null -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                supportingContent = {
                    Text(
                        text = result.exceptionOrNull()?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_failed)) },
            )

            limits.windows.isEmpty() -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_none)) },
            )

            else -> limits.windows.forEach { window ->
                val reached = limits.limitReached || window.usedPercent >= 100
                formItem(
                    headlineContent = { Text(window.longLabel()) },
                    supportingContent = window.resetsAt?.let { resetsAt ->
                        {
                            val time = LocalDateTime.ofInstant(resetsAt.toJavaInstant(), ZoneId.systemDefault())
                            Text(stringResource(R.string.setting_provider_page_usage_resets, time.toMessageTimeString()))
                        }
                    },
                    trailingContent = {
                        Text(
                            text = "${window.usedPercent.roundToInt()}%",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = if (reached) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                ) {
                    LinearWavyProgressIndicator(
                        progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (reached) MaterialTheme.colorScheme.error else ProgressIndicatorDefaults.linearColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    CardGroup {
        nameItem(provider, onEdit)
        apiKeyItem(provider.apiKey) { onEdit(provider.copy(apiKey = it)) }
        textFieldItem(
            label = R.string.setting_provider_page_api_base_url,
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
            trailingIcon = resetBaseUrlButton(provider) {
                onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
            },
        )
    }

    CardGroup {
        switchItem(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_caching)) },
        )
        if (provider.promptCaching) {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl)) },
            ) {
                ConnectedChoiceButtons(
                    options = ClaudePromptCacheTtl.entries,
                    selected = provider.promptCacheTtl,
                    onSelect = { onEdit(provider.copy(promptCacheTtl = it)) },
                ) { ttl, selected ->
                    ConnectedChoiceLabel(
                        text = when (ttl) {
                            ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                            ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                        },
                        selected = selected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }
    val useServiceAccount = provider.vertexAI && provider.useServiceAccount

    CardGroup {
        nameItem(provider, onEdit)
        if (!useServiceAccount) {
            googleKeyPoolItem(
                apiKey = provider.apiKey,
                auxiliaryApiKey = provider.auxiliaryApiKey,
                onApiKeyChange = { onEdit(provider.copy(apiKey = it)) },
            )
            googleAuxiliaryKeyItem(
                auxiliaryApiKey = provider.auxiliaryApiKey,
                onAuxiliaryKeyChange = { onEdit(provider.copy(auxiliaryApiKey = it)) },
            )
        }
        if (!provider.vertexAI) {
            textFieldItem(
                label = R.string.setting_provider_page_api_base_url,
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                isError = provider.baseUrl.isNotBlank() && (
                    !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                    ),
                supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                    { Text("The base URL usually ends with `/v1beta`") }
                } else null,
                trailingIcon = resetBaseUrlButton(provider) {
                    onEdit(provider.copy(baseUrl = provider.defaultBaseUrlForReset()))
                },
            )
        }
    }

    CardGroup {
        if (!useServiceAccount) {
            formItem(
                headlineContent = { Text("多 Key 调度策略") },
                supportingContent = {
                    Text(
                        when (provider.keyRotationMode) {
                            KeyRotationMode.SEQUENTIAL ->
                                "推荐防风控：同模型优先用第 1 把可用 Key，用尽或撞 429 再切下一把，避免多项目横跳"
                            KeyRotationMode.STICKY ->
                                "不同对话分散绑定不同 Key，单场对话内锁定同一把以保前缀缓存"
                            KeyRotationMode.ROUND_ROBIN ->
                                "每次请求在健康池里轮换不同 Key，分散 5 RPM 高频并发限制"
                        }
                    )
                },
            ) {
                ConnectedChoiceButtons(
                    options = KeyRotationMode.entries,
                    selected = provider.keyRotationMode,
                    onSelect = { onEdit(provider.copy(keyRotationMode = it)) },
                ) { mode, selected ->
                    ConnectedChoiceLabel(
                        text = when (mode) {
                            KeyRotationMode.SEQUENTIAL -> "顺序吃空"
                            KeyRotationMode.STICKY -> "缓存优先"
                            KeyRotationMode.ROUND_ROBIN -> "逐次轮询"
                        },
                        selected = selected,
                    )
                }
            }
        }
        switchItem(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
            headlineContent = { Text(stringResource(R.string.setting_provider_page_vertex_ai)) },
        )
        if (provider.vertexAI) {
            switchItem(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_use_service_account)) },
            )
        } else {
            switchItem(
                checked = provider.useInteractionsApi,
                onCheckedChange = { onEdit(provider.copy(useInteractionsApi = it)) },
                headlineContent = { Text("Interactions API (Beta)") },
            )
        }
    }

    if (useServiceAccount) {
        CardGroup {
            item(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_import_service_account_json)) },
                trailingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_service_account_email,
                value = provider.serviceAccountEmail,
                onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_private_key,
                value = provider.privateKey,
                onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                secret = true,
                minLines = 3,
                maxLines = 6,
                monospace = true,
            )
            textFieldItem(
                label = R.string.setting_provider_page_location,
                value = provider.location,
                onValueChange = { onEdit(provider.copy(location = it.trim())) },
            )
            textFieldItem(
                label = R.string.setting_provider_page_project_id,
                value = provider.projectId,
                onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            )
        }
    }
}

private fun CardGroupScope.googleKeyPoolItem(
    apiKey: String,
    auxiliaryApiKey: String,
    onApiKeyChange: (String) -> Unit,
) = formItem(
    headlineContent = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("主力 Key 号池")
            GoogleKeyPoolSummaryBadge(apiKey = apiKey)
        }
    },
) {
    GoogleKeyPoolEditor(
        apiKey = apiKey,
        auxiliaryApiKey = auxiliaryApiKey,
        onApiKeyChange = onApiKeyChange,
    )
}

private fun CardGroupScope.googleAuxiliaryKeyItem(
    auxiliaryApiKey: String,
    onAuxiliaryKeyChange: (String) -> Unit,
) = formItem(
    headlineContent = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Embedding / Decision 专用 Key")
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(999.dp),
            ) {
                val count = remember(auxiliaryApiKey) { parseApiKeys(auxiliaryApiKey).size }
                Text(
                    text = if (count == 0) "默认复用主池 #1" else "已独立 ($count 把)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    },
    supportingContent = {
        Text("Embedding 2 (1K/日) 与 Decision 1 (10K/日) 专用通道；填 1~2 把即可与聊天号池物理隔离，留空则固定复用主池第 1 把")
    },
) {
    GoogleAuxiliaryKeyField(
        auxiliaryApiKey = auxiliaryApiKey,
        onAuxiliaryKeyChange = onAuxiliaryKeyChange,
    )
}

@Composable
private fun GoogleAuxiliaryKeyField(
    auxiliaryApiKey: String,
    onAuxiliaryKeyChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = auxiliaryApiKey,
        onValueChange = { onAuxiliaryKeyChange(it.trim()) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 2,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
        placeholder = {
            Text(
                "可选：粘贴专用项目 Key（留空即固定用主池 #1）",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        visualTransformation = if (!visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )
}

@Composable
private fun GoogleKeyPoolSummaryBadge(apiKey: String) {
    val statusVersion by KeyPoolMonitor.statusVersion.collectAsStateWithLifecycle()
    val pool = remember(apiKey, statusVersion) { KeyPoolMonitor.inspectPool(apiKey) }
    val total = pool.size
    val cooling = pool.count { it.hasAnyCooling }
    val ready = total - cooling

    val (containerColor, contentColor, label) = when {
        total == 0 -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "未配置",
        )
        cooling == 0 -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "$total 把就绪",
        )
        ready > 0 -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "$ready 就绪 / $cooling 部分冷却",
        )
        else -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "全池冷却 ($cooling)",
        )
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun GoogleDailyUsageDashboard(
    pool: List<KeyPoolItemSnapshot>,
    auxPool: List<KeyPoolItemSnapshot>,
) {
    val keyCount = pool.size
    if (keyCount == 0 && auxPool.isEmpty()) return

    val combinedForAux = remember(pool, auxPool) { pool + auxPool }
    val embUsed = combinedForAux.sumOf { it.bucketUsage[GoogleQuotaBucket.EMBEDDING]?.used ?: 0 }
    val decUsed = combinedForAux.sumOf { it.bucketUsage[GoogleQuotaBucket.DECISION]?.used ?: 0 }
    val otherChat = pool.sumOf { it.otherChatCount }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (keyCount > 0) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GoogleQuotaBucket.CHAT_RP_BUCKETS.forEach { bucket ->
                    val usedSum = pool.sumOf { it.bucketUsage[bucket]?.used ?: 0 }
                    val exhaustedKeys = pool.count {
                        it.bucketUsage[bucket]?.reason == KeyCooldownReason.RPD_EXHAUSTED
                    }
                    val totalCap = keyCount * bucket.perKeyDailyLimit
                    val progress = if (totalCap > 0) (usedSum.toFloat() / totalCap).coerceIn(0f, 1f) else 0f
                    val allExhausted = exhaustedKeys >= keyCount

                    Surface(
                        color = if (allExhausted) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = bucket.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (exhaustedKeys > 0) {
                                    Text(
                                        text = "${exhaustedKeys}满",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(
                                text = "$usedSum / $totalCap",
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetbrainsMono),
                                color = if (allExhausted) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (allExhausted) 1f else progress)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            if (allExhausted) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (embUsed > 0 || decUsed > 0 || otherChat > 0) {
            val parts = buildList {
                if (embUsed > 0) add("Embedding 今日 $embUsed 次")
                if (decUsed > 0) add("Decision 今日 $decUsed 次")
                if (otherChat > 0) add("其他模型 $otherChat 次")
            }
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun GoogleKeyPoolEditor(
    apiKey: String,
    auxiliaryApiKey: String,
    onApiKeyChange: (String) -> Unit,
) {
    val toaster = LocalToaster.current
    val statusVersion by KeyPoolMonitor.statusVersion.collectAsStateWithLifecycle()
    val pool = remember(apiKey, statusVersion) { KeyPoolMonitor.inspectPool(apiKey) }
    val auxPool = remember(auxiliaryApiKey, statusVersion) { KeyPoolMonitor.inspectPool(auxiliaryApiKey) }
    val coolingCount = pool.count { it.hasAnyCooling }
    val hasAnyUsage = remember(pool, auxPool) {
        (pool + auxPool).any { snap ->
            snap.otherChatCount > 0 || snap.bucketUsage.values.any { it.used > 0 }
        }
    }

    var visible by remember { mutableStateOf(false) }
    var showAllKeys by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (pool.isEmpty()) {
            GoogleRpQuickStartBanner(
                onOpenGuide = { showGuideDialog = true },
            )
        }

        GoogleDailyUsageDashboard(pool = pool, auxPool = auxPool)

        OutlinedTextField(
            value = apiKey,
            onValueChange = { onApiKeyChange(it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            placeholder = {
                Text(
                    "每行一个、或用英文逗号/空格分隔填入多个项目 Key",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            supportingText = {
                Text(
                    when {
                        pool.isEmpty() ->
                            "支持一次粘贴多账号 / 多项目 Key；别忘了点右下角保存生效"
                        pool.size == 1 ->
                            "当前 1 把 Key；点下方「批量导入」或换行追加即可组池（PT 零点自动清零）"
                        else ->
                            "已识别 ${pool.size} 把不重复 Key，每日容量 ${pool.size * 20} 次/模型（PT 零点自动清零）"
                    }
                )
            },
            visualTransformation = if (!visible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = { showImportDialog = true },
            ) {
                Icon(
                    imageVector = HugeIcons.FileImport,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("批量导入", maxLines = 1)
            }

            if (coolingCount > 0) {
                OutlinedButton(
                    onClick = {
                        KeyPoolMonitor.reset(apiKey)
                        toaster.show("已重置 $coolingCount 把 Key 的冷却状态", type = ToastType.Success)
                    },
                ) {
                    Icon(
                        imageVector = HugeIcons.Refresh03,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重置冷却", maxLines = 1)
                }
            } else if (hasAnyUsage) {
                TextButton(
                    onClick = {
                        val allKeysRaw = "$apiKey\n$auxiliaryApiKey"
                        KeyPoolMonitor.resetDailyUsage(allKeysRaw)
                        toaster.show("已清零今日请求计数", type = ToastType.Success)
                    },
                ) {
                    Text("清零计数", maxLines = 1)
                }
            }

            if (pool.isNotEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { showGuideDialog = true },
                ) {
                    Text("? 攻略", maxLines = 1)
                }
            }
        }

        if (pool.isNotEmpty()) {
            val maxCollapsed = 5
            val visibleKeys = if (showAllKeys || pool.size <= maxCollapsed) {
                pool
            } else {
                pool.take(maxCollapsed)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                visibleKeys.forEachIndexed { index, snap ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "#${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(26.dp),
                            )
                            Text(
                                text = maskApiKey(snap.key),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            if (snap.globalReason != KeyCooldownReason.READY) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = when (snap.globalReason) {
                                            KeyCooldownReason.AUTH_ERROR -> "鉴权失败"
                                            KeyCooldownReason.SERVER_ERROR -> "异常"
                                            else -> "冷却"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            } else {
                                GoogleQuotaBucket.CHAT_RP_BUCKETS.forEach { bucket ->
                                    val bu = snap.bucketUsage[bucket]
                                    val used = bu?.used ?: 0
                                    val reason = bu?.reason ?: KeyCooldownReason.READY
                                    val (bg, fg, pillText) = when {
                                        reason == KeyCooldownReason.RPD_EXHAUSTED -> Triple(
                                            MaterialTheme.colorScheme.errorContainer,
                                            MaterialTheme.colorScheme.onErrorContainer,
                                            if (used > 0) "${bucket.shortLabel} $used 满" else "${bucket.shortLabel} 满",
                                        )
                                        reason == KeyCooldownReason.RPM_LIMIT -> Triple(
                                            MaterialTheme.colorScheme.tertiaryContainer,
                                            MaterialTheme.colorScheme.onTertiaryContainer,
                                            "${bucket.shortLabel} 限",
                                        )
                                        used > 0 -> Triple(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.onPrimaryContainer,
                                            "${bucket.shortLabel} $used",
                                        )
                                        else -> Triple(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                            "${bucket.shortLabel} 0",
                                        )
                                    }
                                    Surface(
                                        color = bg,
                                        contentColor = fg,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(
                                            text = pillText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetbrainsMono),
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    val updated = parseApiKeys(apiKey).filterNot { it == snap.key }
                                    onApiKeyChange(updated.joinToString("\n"))
                                },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }

                if (pool.size > maxCollapsed) {
                    TextButton(
                        onClick = { showAllKeys = !showAllKeys },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            if (showAllKeys) "收起列表" else "展开全部 ${pool.size} 把 Key"
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        val existingKeys = remember(apiKey) { parseApiKeys(apiKey) }
        val importedKeys = remember(importText) {
            parseApiKeys(importText).map { it.trim('"', '\'') }.filter { it.isNotBlank() }.distinct()
        }
        val newlyAddedCount = remember(existingKeys, importedKeys) {
            val existingSet = existingKeys.toSet()
            importedKeys.count { it !in existingSet }
        }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("批量导入 Google API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "直接粘贴多把 AI Studio Key（支持换行、逗号、分号或空格分隔），会自动去重。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 10,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        placeholder = {
                            Text(
                                "AIzaSy...\nAIzaSy...\nAIzaSy...",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    Text(
                        text = if (importedKeys.isEmpty()) {
                            "尚未识别到 Key"
                        } else {
                            "已识别 ${importedKeys.size} 把（其中新增 $newlyAddedCount 把）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (existingKeys.isNotEmpty()) {
                        OutlinedButton(
                            enabled = importedKeys.isNotEmpty(),
                            onClick = {
                                val merged = (existingKeys + importedKeys).distinct()
                                onApiKeyChange(merged.joinToString("\n"))
                                showImportDialog = false
                                toaster.show("已追加 $newlyAddedCount 把 Key（记得点保存）", type = ToastType.Success)
                            },
                        ) {
                            Text("追加")
                        }
                    }
                    Button(
                        enabled = importedKeys.isNotEmpty(),
                        onClick = {
                            onApiKeyChange(importedKeys.joinToString("\n"))
                            showImportDialog = false
                            toaster.show("已导入 ${importedKeys.size} 把 Key（记得点保存）", type = ToastType.Success)
                        },
                    ) {
                        Text(if (existingKeys.isEmpty()) "导入" else "覆盖")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showGuideDialog) {
        GoogleRpGuideDialog(
            onDismiss = { showGuideDialog = false },
        )
    }
}

private const val AI_STUDIO_API_KEY_URL = "https://aistudio.google.com/apikey"
private const val NOTEBOOK_LM_URL = "https://notebooklm.google.com/"

private fun openExternalUrl(
    uriHandler: UriHandler,
    url: String,
    onError: (String) -> Unit,
) {
    runCatching { uriHandler.openUri(url) }
        .onFailure { onError("无法打开浏览器: ${it.message}") }
}

@Composable
private fun GoogleRpQuickStartBanner(
    onOpenGuide: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "吃透免费机制，Google 原生生态就是 RP 的终极答案",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "1 个项目 = 3.6(20) + 3.8(20) + Hydrogen(20) = 60 次对话/日\n+ Embedding 2 (1K/日) & Decision 1 (10K/日)",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            )
            Text(
                text = "× 别在同项目建多个 Key（额度共享无效）\n√ 点「创建新项目」，每项目只拿 1 把 Key",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        openExternalUrl(uriHandler, AI_STUDIO_API_KEY_URL) { msg ->
                            toaster.show(msg, type = ToastType.Error)
                        }
                    },
                ) {
                    Text("去 AI Studio 建项目", maxLines = 1)
                }
                TextButton(onClick = onOpenGuide) {
                    Text("完整攻略 & Source 闭环", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GoogleRpGuideDialog(
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val toaster = LocalToaster.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google RP 号池 × Source 工程指南") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "全世界最懂 RP 的底座其实是 Google：把官方免费额度与记忆生态吃干榨净，比任何不稳定反代都稳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )

                GoogleGuideSectionCard(
                    title = "1. 一眼算清免费账本（关键看 Project）",
                    body = "• 配额按 Project 独立计算，同一项目下建多个 Key 额度共享无效。\n" +
                        "• 每个独立项目每天白嫖：Gemini 3.6 Flash (20) + 3.8 Flash (20) + Hydrogen (20) = 60 次主力 RP，另加 Embedding 2 (1K/日) 与 Decision 1 (10K/日)。\n" +
                        "• 叠满 5~20 把项目 Key 即可日均 300~1,200 次直连，北京时间每日约 16:00（PT 零点）自动刷新。",
                )

                GoogleGuideSectionCard(
                    title = "2. 三步安全建池（防风控铁律）",
                    body = "• ① 在 AI Studio 点「创建新项目」，每项目只生成 1 把 Key（建议用 rp-01、notes-02 等普通命名，别一口气暴建）。\n" +
                        "• ② 点主界面「批量导入」一次性粘贴全部 Key，系统自动去重。\n" +
                        "• ③ 策略保持默认「顺序吃空」：第 1 把 Key 的某个模型用尽 20 次才切下一把，避免多项目横跳；同把 Key 的 Hydrogen 用尽不会误伤 3.8 与 3.6。",
                )

                GoogleGuideSectionCard(
                    title = "3. Kairos 模型与记忆抄作业",
                    body = "• 主力对话：Hydrogen / Gemini 3.8 Flash / Gemini 3.6 Flash 轮换（顶部 3 栏实时显示总余量）。\n" +
                        "• 记忆检索与决策：选择 gemini-embedding-2 与 gemini-decision-1。\n" +
                        "• 「Embedding / Decision 专用 Key」留空即可：默认固定只复用主池 #1，绝不唤醒其余项目。",
                )

                GoogleGuideSectionCard(
                    title = "4. 全谷歌闭环：Kairos + NotebookLM Source 工程",
                    body = "• 体验公式：综合体验 = 逻辑底座 × 语言颗粒度，别把上万字设定集硬塞进开局系统词。\n" +
                        "• NotebookLM 做静态 Source：把原作、人物小传与世界观丢进 NotebookLM 拆解关系、生成精细章节大纲与场景锚点，Kairos 开聊前只做 1~2 句精准定调。\n" +
                        "• Kairos 负责动态记忆：日常对话由 Embedding 2 + Decision 1 自动提炼进本地时序图谱并在尾部近因注入，死保 Prefix Cache 前缀缓存命中。",
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            openExternalUrl(uriHandler, AI_STUDIO_API_KEY_URL) { msg ->
                                toaster.show(msg, type = ToastType.Error)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("AI Studio", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            openExternalUrl(uriHandler, NOTEBOOK_LM_URL) { msg ->
                                toaster.show(msg, type = ToastType.Error)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("NotebookLM", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("我看懂了")
            }
        },
    )
}

@Composable
private fun GoogleGuideSectionCard(
    title: String,
    body: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
