// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.enableAllAiToolbarKeys
import helium314.keyboard.latin.utils.setAiMenuKeyEnabled
import helium314.keyboard.latin.utils.syncPresetToolbarKeys
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI Settings screen — manage provider, API key, presets.
 * All values are stored in Heliboard's device-protected prefs (context.prefs()).
 * The API key is written only on Save, never auto-saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val scrollState = rememberScrollState()

    // Load current values from prefs once
    val currentProvider = remember { mutableStateOf(AiPrefs.getProvider(context)) }
    val apiKey = remember { mutableStateOf(AiPrefs.getApiKey(context)) }
    val model = remember { mutableStateOf(AiPrefs.getModel(context)) }
    val endpoint = remember { mutableStateOf(AiPrefs.getEndpoint(context)) }
    val enabled = remember { mutableStateOf(prefs.getBoolean(Settings.PREF_AI_ENABLED, false)) }
    var presets by remember { mutableStateOf(AiPresetManager.getPresets(context)) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun saveAll() {
        prefs.edit()
            .putString(Settings.PREF_AI_PROVIDER, currentProvider.value.name)
            .putString(Settings.PREF_AI_API_KEY, apiKey.value.trim())
            .putString(Settings.PREF_AI_MODEL, model.value.trim())
            .putString(Settings.PREF_AI_ENDPOINT, endpoint.value.trim())
            .putBoolean(Settings.PREF_AI_ENABLED, enabled.value)
            .apply()
        AiPresetManager.savePresets(context, presets)
        // Sriboard: when AI is turned on with an API key, enable ALL AI toolbar keys
        // automatically — no manual Settings → Toolbar trip needed. Toggling a preset
        // on also enables its toolbar key. The AI menu key follows the AI master switch.
        if (enabled.value && apiKey.value.trim().isNotBlank()) {
            enableAllAiToolbarKeys(context.prefs())
            Toast.makeText(context, "AI settings saved — all AI toolbar keys enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "AI settings saved", Toast.LENGTH_SHORT).show()
        }
        syncPresetToolbarKeys(context.prefs(), presets)
        setAiMenuKeyEnabled(context.prefs(), enabled.value)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_screen_ai)) },
                navigationIcon = {
                    TextButton(onClick = onClickBack) { Text("< " + stringResource(R.string.settings_screen_preferences)) }
                },
                actions = {
                    TextButton(onClick = { saveAll() }) { Text("Save") }
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
                onCheckedChange = { enabled.value = it }
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
                                // Reset model to this provider's default
                                model.value = provider.defaultModel
                                if (!provider.requiresEndpoint) {
                                    endpoint.value = provider.defaultEndpoint()
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }

            // API Key (masked, saved on Save press)
            OutlinedTextField(
                value = apiKey.value,
                onValueChange = { apiKey.value = it },
                label = { Text("API Key") },
                placeholder = { Text("sk-... or AIza...") },
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "HIDE" else "SHOW")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )

            // Model (free text + quick-pick of the provider's current models)
            var modelMenu by remember { mutableStateOf(false) }
            val availableModels = currentProvider.value.availableModels()
            OutlinedTextField(
                value = model.value,
                onValueChange = { model.value = it },
                label = { Text(stringResource(R.string.ai_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (availableModels.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { modelMenu = true }) { Text("▾") }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                availableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m) },
                                        onClick = {
                                            model.value = m
                                            modelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )

            // Endpoint (editable only for custom OpenAI-compatible)
            if (currentProvider.value == AiPrefs.Provider.CUSTOM_OPENAI) {
                OutlinedTextField(
                    value = endpoint.value,
                    onValueChange = { endpoint.value = it },
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

            // Save button (prominent)
            Button(
                onClick = { saveAll() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("Save AI Settings")
            }

            // Test connection
            Button(
                onClick = {
                    val key = apiKey.value.trim()
                    if (key.isBlank()) {
                        Toast.makeText(context, "Enter an API key first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    testing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AiApiClient.testConnection(
                                currentProvider.value,
                                key,
                                model.value.trim(),
                                endpoint.value.trim()
                            )
                        }
                        testing = false
                        val msg = if (result.success) "✓ Connection OK — API responded"
                        else "✗ ${result.errorMessage}"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(if (testing) "Testing connection…" else "Test Connection")
            }

            HorizontalDivider()
            Text("Presets", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Only enabled presets appear as toolbar keys. Reorder with ▲▼.",
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
                    },
                    onEnabledChange = { newEnabled ->
                        val updated = presets.toMutableList()
                        updated[index] = updated[index].copy(enabled = newEnabled)
                        presets = updated
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

            // Bottom save button
            Button(
                onClick = { saveAll() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("Save AI Settings")
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
    val displayName = AiPresetManager.displayName(preset)

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
                TextButton(onClick = onMoveUp, enabled = preset.enabled) { Text("▲") }
                TextButton(onClick = onMoveDown, enabled = preset.enabled) { Text("▼") }
            }
            if (preset.enabled) {
                var promptText by remember(preset.prompt) { mutableStateOf(preset.prompt) }
                OutlinedTextField(
                    value = promptText,
                    onValueChange = {
                        promptText = it
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
