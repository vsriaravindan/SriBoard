// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AI Settings screen — manage provider, API key, presets.
 * API key is stored in SharedPreferences (device-protected storage).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scrollState = rememberScrollState()

    val currentProvider = remember {
        mutableStateOf(AiPrefs.getProvider(context))
    }
    val apiKey = remember {
        mutableStateOf(AiPrefs.getApiKey(context))
    }
    val model = remember {
        mutableStateOf(AiPrefs.getModel(context))
    }
    val endpoint = remember {
        mutableStateOf(AiPrefs.getEndpoint(context))
    }
    val enabled = remember {
        mutableStateOf(prefs.getBoolean(Settings.PREF_AI_ENABLED, false))
    }
    var presets by remember {
        mutableStateOf(AiPresetManager.getPresets(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_ai)) },
                navigationIcon = {
                    TextButton(onClick = onClickBack) {
                        Text("< " + stringResource(R.string.settings_screen_preferences))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Master switch
            SwitchPreferenceItem(
                title = "Enable AI Features",
                checked = enabled.value,
                onCheckedChange = {
                    enabled.value = it
                    prefs.edit().putBoolean(Settings.PREF_AI_ENABLED, it).apply()
                }
            )

            if (!enabled.value) return@Column

            HorizontalDivider()
            Text("Provider", style = MaterialTheme.typography.titleSmall)

            // Provider dropdown
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = currentProvider.value.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ai_provider)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    AiPrefs.Provider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                currentProvider.value = provider
                                prefs.edit().putString(Settings.PREF_AI_PROVIDER, provider.name).apply()
                                // Reset model to default for this provider
                                model.value = provider.defaultModel
                                prefs.edit().putString(Settings.PREF_AI_MODEL, provider.defaultModel).apply()
                                // Set default endpoint if not custom
                                if (!provider.requiresEndpoint) {
                                    endpoint.value = provider.defaultEndpoint()
                                    prefs.edit().putString(Settings.PREF_AI_ENDPOINT, provider.defaultEndpoint()).apply()
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }

            // API Key
            OutlinedTextField(
                value = apiKey.value,
                onValueChange = {
                    apiKey.value = it
                    prefs.edit().putString(Settings.PREF_AI_API_KEY, it).apply()
                },
                label = { Text("API Key") },
                placeholder = { Text("sk-... or AIza...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            )

            // Model
            OutlinedTextField(
                value = model.value,
                onValueChange = {
                    model.value = it
                    prefs.edit().putString(Settings.PREF_AI_MODEL, it).apply()
                },
                label = { Text(stringResource(R.string.ai_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Endpoint (for OpenAI-compatible providers)
            if (currentProvider.value == AiPrefs.Provider.CUSTOM_OPENAI) {
                OutlinedTextField(
                    value = endpoint.value,
                    onValueChange = {
                        endpoint.value = it
                        prefs.edit().putString(Settings.PREF_AI_ENDPOINT, it).apply()
                    },
                    label = { Text(stringResource(R.string.ai_custom_endpoint)) },
                    placeholder = { Text("https://api.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            } else {
                Text(
                    text = "Endpoint: ${endpoint.value.ifBlank { "default" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()
            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Only enabled presets appear as toolbar keys. Long press and drag to reorder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Preset list
            presets.forEachIndexed { index, preset ->
                AiPresetCard(
                    preset = preset,
                    onPromptChange = { newPrompt ->
                        val updated = presets.toMutableList()
                        updated[index] = updated[index].copy(prompt = newPrompt)
                        presets = updated
                        AiPresetManager.savePresets(context, updated)
                    },
                    onEnabledChange = { newEnabled ->
                        val updated = presets.toMutableList()
                        updated[index] = updated[index].copy(enabled = newEnabled)
                        presets = updated
                        AiPresetManager.savePresets(context, updated)
                    },
                    onMoveUp = {
                        if (index > 0) {
                            AiPresetManager.reorderPresets(context, index, index - 1)
                            presets = AiPresetManager.getPresets(context)
                        }
                    },
                    onMoveDown = {
                        if (index < presets.size - 1) {
                            AiPresetManager.reorderPresets(context, index, index + 1)
                            presets = AiPresetManager.getPresets(context)
                        }
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AiPresetCard(
    preset: AiPresetManager.SerializablePreset,
    onPromptChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val displayName = try {
        val type = AiPrefs.PresetType.valueOf(preset.type)
        when (type) {
            AiPrefs.PresetType.FIX -> "Fix (English)"
            AiPrefs.PresetType.TRANSLATE_TAMIL -> "Translate to Tamil"
            else -> "Custom ${type.ordinal - 1}"
        }
    } catch (_: Exception) {
        preset.type
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (preset.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = preset.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                // Move buttons
                TextButton(onClick = onMoveUp, enabled = preset.enabled) { Text("▲") }
                TextButton(onClick = onMoveDown, enabled = preset.enabled) { Text("▼") }
            }
            if (preset.enabled) {
                val promptText = remember(preset.prompt) { mutableStateOf(preset.prompt) }
                OutlinedTextField(
                    value = promptText.value,
                    onValueChange = {
                        promptText.value = it
                        onPromptChange(it)
                    },
                    label = { Text(stringResource(R.string.ai_custom_prompt)) },
                    placeholder = { Text(stringResource(R.string.ai_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 2,
                    maxLines = 4
                )
            }
        }
    }
}

@Composable
private fun SwitchPreferenceItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
