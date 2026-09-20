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
  var model by remember { mutableStateOf("") }
  var error by remember { mutableStateOf("") }

  fun startNew(type: OpenAiProviderType) {
    editingId = null
    editingType = type
    name = if (type == OpenAiProviderType.OPENAI) "OpenAI" else "Remote"
    baseUrl = if (type == OpenAiProviderType.OPENAI) OPENAI_DEFAULT_BASE_URL else ""
    apiKey = ""
    model = ""
    error = ""
  }

  fun startEdit(provider: OpenAiProvider) {
    editingId = provider.id
    editingType = provider.type
    name = provider.name
    baseUrl = provider.effectiveBaseUrl
    apiKey = provider.apiKey
    model = provider.model
    error = ""
  }

  fun closeEditor() {
    editingId = null
    editingType = null
    error = ""
  }

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      "Remote providers",
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      "Use the same Gallery chat UI with OpenAI or any OpenAI-compatible /v1 endpoint.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    providers.forEach { provider ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text(provider.name, style = MaterialTheme.typography.bodyMedium)
          Text(
            provider.model + " · " + provider.effectiveBaseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(onClick = { startEdit(provider) }) { Text("Edit") }
        TextButton(onClick = { modelManagerViewModel.deleteRemoteProvider(provider.id) }) {
          Text("Delete")
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
        value = model,
        onValueChange = { model = it },
        label = { Text("Model") },
        placeholder = { Text("gpt-5.6 / qwen3 / ...") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )

      if (error.isNotEmpty()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = {
            val provider =
              OpenAiProvider(
                id = editingId ?: UUID.randomUUID().toString(),
                name = name.trim(),
                type = type,
                baseUrl = if (type == OpenAiProviderType.OPENAI) "" else baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
              )
            val validationError = provider.validationError()
            if (validationError != null) {
              error = validationError
            } else {
              modelManagerViewModel.saveRemoteProvider(provider) { error = it }
              closeEditor()
            }
          }
        ) {
          Text("Save")
        }
        TextButton(onClick = { closeEditor() }) { Text("Cancel") }
      }
    }
  }
}
