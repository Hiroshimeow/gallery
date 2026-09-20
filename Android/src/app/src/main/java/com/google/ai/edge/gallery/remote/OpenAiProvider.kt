/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.remote

import com.google.ai.edge.gallery.data.BackendSpec
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.LlmProfile
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelMetadata
import com.google.ai.edge.gallery.data.RuntimeType
import kotlinx.serialization.Serializable

const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"

@Serializable
enum class OpenAiProviderType {
  OPENAI,
  OPENAI_COMPATIBLE,
}

fun remoteModelsForTask(taskId: String, providers: List<OpenAiProvider>): List<Model> =
  if (taskId == BuiltInTaskId.LLM_CHAT || taskId == BuiltInTaskId.LLM_AGENT_CHAT) {
    providers.map { it.toRemoteModel() }
  } else {
    emptyList()
  }

@Serializable
data class OpenAiProvider(
  val id: String,
  val name: String,
  val type: OpenAiProviderType,
  val baseUrl: String = "",
  val apiKey: String = "",
  val model: String,
) {
  val effectiveBaseUrl: String
    get() =
      when (type) {
        OpenAiProviderType.OPENAI -> OPENAI_DEFAULT_BASE_URL
        OpenAiProviderType.OPENAI_COMPATIBLE -> baseUrl.trim().trimEnd('/')
      }

  fun validationError(): String? {
    if (id.isBlank()) return "Provider id is required"
    if (name.isBlank()) return "Provider name is required"
    if (model.isBlank()) return "Model is required"
    if (type == OpenAiProviderType.OPENAI && apiKey.isBlank()) return "OpenAI API key is required"
    val url = effectiveBaseUrl
    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
      return "Base URL must start with http:// or https://"
    }
    return null
  }

  fun toRemoteModel(): Model {
    val safeName =
      buildString {
          append("remote_")
          append(id)
          append("_")
          append(model)
        }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val providerLabel =
      if (type == OpenAiProviderType.OPENAI) "OpenAI" else "OpenAI-compatible"
    return Model(
      name = safeName,
      displayName = "$name · $model",
      info = "Remote $providerLabel provider",
      backendSpec = BackendSpec(runtimeType = RuntimeType.OPENAI_REMOTE),
      llmProfile = LlmProfile(),
      metadata = ModelMetadata(remoteProviderId = id, remoteModelId = model),
    )
  }
}
