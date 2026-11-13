package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.service.ProactiveMessageWorker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Select
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingProactiveMessagePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val proactive = settings.proactiveMessageSetting
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun updateProactive(next: ProactiveMessageSetting) {
        val normalized = next.normalized()
        vm.updateSettings { it.copy(proactiveMessageSetting = normalized) }
        if (normalized.enabled) {
            ProactiveMessageWorker.scheduleNext(context, normalized)
        } else {
            ProactiveMessageWorker.cancel(context)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_proactive_message)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = proactive.enabled,
                        onCheckedChange = { checked ->
                            updateProactive(proactive.copy(enabled = checked))
                        },
                        headlineContent = { Text(stringResource(R.string.proactive_enable_title)) },
                        supportingContent = { Text(stringResource(R.string.proactive_enable_desc)) },
                    )
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.proactive_permission_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.proactive_permission_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.proactive_permission_button))
                        }
                    }
                }
            }

            item {
                var minText by remember(proactive.minIntervalMinutes) {
                    mutableStateOf(proactive.minIntervalMinutes.toString())
                }
                var maxText by remember(proactive.maxIntervalMinutes) {
                    mutableStateOf(proactive.maxIntervalMinutes.toString())
                }
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.proactive_interval_title)) },
                        supportingContent = { Text(stringResource(R.string.proactive_interval_desc)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = minText,
                                onValueChange = { v ->
                                    if (v.all(Char::isDigit)) {
                                        minText = v
                                        v.toIntOrNull()?.let { n ->
                                            updateProactive(proactive.copy(minIntervalMinutes = n))
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.proactive_min_minutes)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = maxText,
                                onValueChange = { v ->
                                    if (v.all(Char::isDigit)) {
                                        maxText = v
                                        v.toIntOrNull()?.let { n ->
                                            updateProactive(proactive.copy(maxIntervalMinutes = n))
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.proactive_max_minutes)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    formItem(
                        headlineContent = { Text(stringResource(R.string.proactive_target_assistant)) },
                    ) {
                        val assistantOptions = listOf("") + settings.assistants.map { it.id.toString() }
                        Select(
                            options = assistantOptions,
                            selectedOption = if (proactive.assistantId in assistantOptions) proactive.assistantId else "",
                            onOptionSelected = { id -> updateProactive(proactive.copy(assistantId = id)) },
                            optionToString = { id ->
                                if (id.isBlank()) {
                                    stringResource(R.string.proactive_follow_current_assistant)
                                } else {
                                    settings.assistants.firstOrNull { it.id.toString() == id }?.name?.ifBlank { id } ?: id
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                var startText by remember(proactive.quietStartHour) {
                    mutableStateOf(proactive.quietStartHour.toString())
                }
                var endText by remember(proactive.quietEndHour) {
                    mutableStateOf(proactive.quietEndHour.toString())
                }
                CardGroup {
                    switchItem(
                        checked = proactive.quietHoursEnabled,
                        onCheckedChange = { checked ->
                            updateProactive(proactive.copy(quietHoursEnabled = checked))
                        },
                        headlineContent = { Text(stringResource(R.string.proactive_quiet_hours_title)) },
                        supportingContent = { Text(stringResource(R.string.proactive_quiet_hours_desc)) },
                    )
                    if (proactive.quietHoursEnabled) {
                        formItem {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = startText,
                                    onValueChange = { v ->
                                        if (v.all(Char::isDigit)) {
                                            startText = v
                                            v.toIntOrNull()?.let { h ->
                                                updateProactive(proactive.copy(quietStartHour = h))
                                            }
                                        }
                                    },
                                    label = { Text(stringResource(R.string.proactive_quiet_start)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = endText,
                                    onValueChange = { v ->
                                        if (v.all(Char::isDigit)) {
                                            endText = v
                                            v.toIntOrNull()?.let { h ->
                                                updateProactive(proactive.copy(quietEndHour = h))
                                            }
                                        }
                                    },
                                    label = { Text(stringResource(R.string.proactive_quiet_end)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        ProactiveMessageWorker.runOnce(context)
                        toaster.show(context.getString(R.string.proactive_test_queued), type = ToastType.Info)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.proactive_test_now))
                }
            }
        }
    }
}
