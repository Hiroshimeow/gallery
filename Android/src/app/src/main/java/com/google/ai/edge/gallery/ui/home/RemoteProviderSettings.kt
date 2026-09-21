/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.remote.OPENAI_DEFAULT_BASE_URL
import com.google.ai.edge.gallery.remote.OpenAiProvider
import com.google.ai.edge.gallery.remote.OpenAiProviderType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.UUID

@Composable
fun RemoteProviderSettings(modelManagerViewModel: ModelManagerViewModel) {
  val providers by modelManagerViewModel.remoteProviders.collectAsState()
  var editingId by remember { mutableStateOf<String?>(null) }
  var editingType by remember { mutableStateOf<OpenAiProviderType?>(null) }
  var name by remember { mutableStateOf("") }
  var baseUrl by remember { mutableStateOf("") }
  var apiKey by remember { mutableStateOf("") }
  var modelIds by remember { mutableStateOf<List<String>>(emptyList()) }
  var manualModel by remember { mutableStateOf("") }
  var maxOutputTokens by remember { mutableStateOf("0") }
  var error by remember { mutableStateOf("") }
  var loadingModels by remember { mutableStateOf(false) }

  fun startNew(type: OpenAiProviderType) {
    editingId = null
    editingType = type
    name = if (type == OpenAiProviderType.OPENAI) "OpenAI" else "Remote"
    baseUrl = if (type == OpenAiProviderType.OPENAI) OPENAI_DEFAULT_BASE_URL else ""
    apiKey = ""
    modelIds = emptyList()
    manualModel = ""
    maxOutputTokens = "0"
    error = ""
  }

  fun startEdit(provider: OpenAiProvider) {
    editingId = provider.id
    editingType = provider.type
    name = provider.name
    baseUrl = provider.effectiveBaseUrl
    apiKey = provider.apiKey
    modelIds = provider.effectiveModelIds
    manualModel = ""
    maxOutputTokens = provider.maxOutputTokens.toString()
    error = ""
  }

  fun closeEditor() {
    editingId = null
    editingType = null
    error = ""
    loadingModels = false
  }

  fun buildProvider(type: OpenAiProviderType): OpenAiProvider? {
    val tokenLimit = maxOutputTokens.toIntOrNull()
    if (tokenLimit == null || tokenLimit < 0) {
      error = "Max output tokens must be 0 or greater"
      return null
    }
    val provider =
      OpenAiProvider(
        id = editingId ?: UUID.randomUUID().toString(),
        name = name.trim(),
        type = type,
        baseUrl = if (type == OpenAiProviderType.OPENAI) "" else baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = "",
        models = modelIds,
        maxOutputTokens = tokenLimit,
      )
    provider.validationError()?.let {
      error = it
      return null
    }
    return provider
  }

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "Remote providers · OpenAI/MCP build",
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      "One endpoint can expose many models. Gallery loads /v1/models and every discovered model becomes selectable directly inside chat.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    providers.forEach { provider ->
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(provider.name, style = MaterialTheme.typography.bodyMedium)
            Text(
              "${provider.effectiveModelIds.size} models · ${provider.effectiveBaseUrl}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(
            onClick = {
              error = ""
              modelManagerViewModel.refreshRemoteProviderModels(provider) { result ->
                result.exceptionOrNull()?.let { error = it.message ?: "Failed to load models" }
              }
            }
          ) {
            Text("Refresh")
          }
          TextButton(onClick = { startEdit(provider) }) { Text("Edit") }
          TextButton(onClick = { modelManagerViewModel.deleteRemoteProvider(provider.id) }) {
            Text("Delete")
          }
        }
        if (provider.effectiveModelIds.isNotEmpty()) {
          Text(
            provider.effectiveModelIds.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (editingType == null) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { startNew(OpenAiProviderType.OPENAI) }) {
          Text("Add OpenAI")
        }
        OutlinedButton(onClick = { startNew(OpenAiProviderType.OPENAI_COMPATIBLE) }) {
          Text("Add compatible")
        }
      }
    } else {
      val type = editingType!!
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      if (type == OpenAiProviderType.OPENAI_COMPATIBLE) {
        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it },
          label = { Text("Base URL") },
          placeholder = { Text("http://100.x.x.x:8000/v1") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
      } else {
        Text(
          OPENAI_DEFAULT_BASE_URL,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text(if (type == OpenAiProviderType.OPENAI) "API key" else "API key (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
      )
      OutlinedTextField(
        value = maxOutputTokens,
        onValueChange = { maxOutputTokens = it.filter(Char::isDigit) },
        label = { Text("Max output tokens") },
        supportingText = {
          Text("0 = endpoint/model default. Remote chat is not limited by Gallery's 1024-token benchmark sliders.")
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )

      Text(
        if (modelIds.isEmpty()) "No models loaded yet" else "Models (${modelIds.size})",
        style = MaterialTheme.typography.labelLarge,
      )
      modelIds.forEach { id ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          TextButton(onClick = { modelIds = modelIds.filterNot { it == id } }) { Text("Remove") }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = manualModel,
          onValueChange = { manualModel = it },
          label = { Text("Manual model ID") },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        OutlinedButton(
          onClick = {
            val id = manualModel.trim()
            if (id.isNotEmpty()) {
              modelIds = (modelIds + id).distinct()
              manualModel = ""
            }
          }
        ) {
          Text("Add")
        }
      }

      if (error.isNotEmpty()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          enabled = !loadingModels,
          onClick = {
            val provider = buildProvider(type) ?: return@OutlinedButton
            loadingModels = true
            error = ""
            modelManagerViewModel.refreshRemoteProviderModels(provider) { result ->
              loadingModels = false
              result
                .onSuccess { updated ->
                  modelIds = updated.effectiveModelIds
                  closeEditor()
                }
                .onFailure { throwable ->
                  error =
                    "Endpoint saved, but /v1/models failed: " +
                      (throwable.message ?: "unknown error") +
                      ". Add model IDs manually and Save."
                }
            }
          }
        ) {
          Text(if (loadingModels) "Loading…" else "Save & load models")
        }
        OutlinedButton(
          enabled = !loadingModels,
          onClick = {
            val provider = buildProvider(type) ?: return@OutlinedButton
            modelManagerViewModel.saveRemoteProvider(provider) { error = it }
            closeEditor()
          }
        ) {
          Text("Save")
        }
        TextButton(onClick = { closeEditor() }) { Text("Cancel") }
      }
    }
  }
}
