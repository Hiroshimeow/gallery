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
private const val REMOTE_UI_CONTEXT_LIMIT = 1_048_576

@Serializable
enum class OpenAiProviderType {
  OPENAI,
  OPENAI_COMPATIBLE,
}

@Serializable
data class OpenAiProvider(
  val id: String,
  val name: String,
  val type: OpenAiProviderType,
  val baseUrl: String = "",
  val apiKey: String = "",
  // Legacy single-model field retained so existing saved provider profiles migrate cleanly.
  val model: String = "",
  val models: List<String> = emptyList(),
  // 0 means do not send max_tokens; the endpoint/model decides.
  val maxOutputTokens: Int = 0,
) {
  val effectiveBaseUrl: String
    get() =
      when (type) {
        OpenAiProviderType.OPENAI -> OPENAI_DEFAULT_BASE_URL
        OpenAiProviderType.OPENAI_COMPATIBLE -> baseUrl.trim().trimEnd('/')
      }

  val effectiveModelIds: List<String>
    get() =
      (models + listOf(model))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

  fun validationError(): String? {
    if (id.isBlank()) return "Provider id is required"
    if (name.isBlank()) return "Provider name is required"
    if (type == OpenAiProviderType.OPENAI && apiKey.isBlank()) return "OpenAI API key is required"
    val url = effectiveBaseUrl
    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
      return "Base URL must start with http:// or https://"
    }
    if (maxOutputTokens < 0) return "Max output tokens must be 0 or greater"
    return null
  }

  fun toRemoteModels(): List<Model> = effectiveModelIds.map { toRemoteModel(it) }

  fun toRemoteModel(modelId: String = effectiveModelIds.firstOrNull().orEmpty()): Model {
    require(modelId.isNotBlank()) { "Remote model id is required" }
    val safeName =
      buildString {
          append("remote_")
          append(id)
          append("_")
          append(modelId)
        }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val providerLabel =
      if (type == OpenAiProviderType.OPENAI) "OpenAI" else "OpenAI-compatible"
    return Model(
      name = safeName,
      displayName = "$name · $modelId",
      info = "Remote $providerLabel provider · endpoint-managed context",
      backendSpec = BackendSpec(runtimeType = RuntimeType.OPENAI_REMOTE),
      llmProfile = LlmProfile(maxTokens = REMOTE_UI_CONTEXT_LIMIT),
      supportImage = true,
      supportAudio = true,
      metadata = ModelMetadata(remoteProviderId = id, remoteModelId = modelId),
    )
  }
}

fun remoteModelsForTask(taskId: String, providers: List<OpenAiProvider>): List<Model> =
  if (taskId == BuiltInTaskId.LLM_CHAT || taskId == BuiltInTaskId.LLM_AGENT_CHAT) {
    providers.flatMap { it.toRemoteModels() }
  } else {
    emptyList()
  }
