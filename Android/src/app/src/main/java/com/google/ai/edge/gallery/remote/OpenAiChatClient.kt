/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface OpenAiCompletionEvent {
  data class TextDelta(val text: String) : OpenAiCompletionEvent
  data class Completed(val toolCalls: List<OpenAiToolCall>) : OpenAiCompletionEvent
}

interface OpenAiChatGateway {
  fun streamCompletion(
    provider: OpenAiProvider,
    messages: List<OpenAiMessage>,
    enableMcpTool: Boolean,
  ): Flow<OpenAiCompletionEvent>
}

@Singleton
class KtorOpenAiChatGateway @Inject constructor() : OpenAiChatGateway {
  private val client = HttpClient(Android)

  override fun streamCompletion(
    provider: OpenAiProvider,
    messages: List<OpenAiMessage>,
    enableMcpTool: Boolean,
  ): Flow<OpenAiCompletionEvent> = flow {
    provider.validationError()?.let { error -> throw IllegalArgumentException(error) }
    val request =
      OpenAiChatRequest(
        model = provider.model,
        messages = messages,
        stream = true,
        tools = if (enableMcpTool) listOf(RUN_MCP_TOOL_SPEC) else null,
      )
    val response =
      client.post(provider.effectiveBaseUrl + "/chat/completions") {
        contentType(ContentType.Application.Json)
        if (provider.apiKey.isNotBlank()) {
          header(HttpHeaders.Authorization, "Bearer " + provider.apiKey)
        }
        setBody(OPENAI_JSON.encodeToString(OpenAiChatRequest.serializer(), request))
      }

    if (!response.status.isSuccess()) {
      val body = response.bodyAsText().take(2048)
      throw IllegalStateException(
        "Remote provider returned HTTP " + response.status.value + ": " + body
      )
    }

    val responseType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
    if (!responseType.contains("text/event-stream")) {
      val result = parseOpenAiNonStreamingResponse(response.bodyAsText())
      if (result.content.isNotEmpty()) emit(OpenAiCompletionEvent.TextDelta(result.content))
      emit(OpenAiCompletionEvent.Completed(result.toolCalls))
      return@flow
    }

    val accumulator = OpenAiStreamAccumulator()
    val channel = response.bodyAsChannel()
    while (true) {
      val line = channel.readUTF8Line() ?: break
      val update = accumulator.consumeSseLine(line)
      if (update.text.isNotEmpty()) emit(OpenAiCompletionEvent.TextDelta(update.text))
      if (update.done) break
    }
    emit(OpenAiCompletionEvent.Completed(accumulator.completedToolCalls()))
  }
}
