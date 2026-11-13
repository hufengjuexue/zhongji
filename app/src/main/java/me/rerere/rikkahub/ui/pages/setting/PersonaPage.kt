package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Persona
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel

@Composable
fun PersonaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editingPersona by remember { mutableStateOf<Persona?>(null) }

    editingPersona?.let { draft ->
        PersonaEditorDialog(
            initial = draft,
            onDismiss = { editingPersona = null },
            onSave = { saved ->
                vm.updateSettings { cur ->
                    val exists = cur.personas.any { it.id == saved.id }
                    val updatedList = if (exists) {
                        cur.personas.map { if (it.id == saved.id) saved else it }
                    } else {
                        cur.personas + saved
                    }
                    cur.copy(
                        personas = updatedList,
                        activePersonaId = cur.activePersonaId ?: saved.id,
                    )
                }
                editingPersona = null
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_persona)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { editingPersona = Persona() }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.add))
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.personas.isEmpty()) {
                item {
                    CardGroup {
                        item(
                            onClick = { editingPersona = Persona() },
                            headlineContent = { Text(stringResource(R.string.persona_page_empty_title)) },
                            supportingContent = { Text(stringResource(R.string.persona_page_empty_desc)) },
                        )
                    }
                }
            } else {
                items(settings.personas, key = { it.id.toString() }) { persona ->
                    val isActive = settings.activePersonaId == persona.id
                    CardGroup {
                        item(
                            onClick = { editingPersona = persona },
                            headlineContent = {
                                Text(
                                    text = persona.name.ifBlank { stringResource(R.string.user_default_name) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                val meta = listOfNotNull(
                                    persona.title.takeIf { it.isNotBlank() },
                                    persona.description.take(80).takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            vm.updateSettings { cur ->
                                                cur.copy(activePersonaId = if (isActive) null else persona.id)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Tick01,
                                            contentDescription = null,
                                            tint = if (isActive) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            vm.updateSettings { cur ->
                                                cur.copy(
                                                    personas = cur.personas.filterNot { it.id == persona.id },
                                                    activePersonaId = cur.activePersonaId.takeIf { it != persona.id },
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaEditorDialog(
    initial: Persona,
    onDismiss: () -> Unit,
    onSave: (Persona) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var position by remember(initial.id) { mutableStateOf(initial.position) }
    var depthText by remember(initial.id) { mutableStateOf(initial.depth.toString()) }
    var role by remember(initial.id) { mutableStateOf(initial.role) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_persona)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.persona_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.persona_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.persona_field_description)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Select(
                    options = PersonaInjectionPosition.entries,
                    selectedOption = position,
                    onOptionSelected = { position = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (position == PersonaInjectionPosition.AT_DEPTH) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = depthText,
                            onValueChange = { if (it.all(Char::isDigit)) depthText = it },
                            label = { Text(stringResource(R.string.persona_field_depth)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(100.dp),
                        )
                        Select(
                            options = listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
                            selectedOption = role,
                            onOptionSelected = { role = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.enable), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            title = title.trim(),
                            description = description.trim(),
                            position = position,
                            depth = depthText.toIntOrNull()?.coerceAtLeast(0) ?: 2,
                            role = role,
                            enabled = enabled,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
