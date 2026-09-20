/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.agent

import android.content.Context
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.remote.OPENAI_JSON
import com.google.ai.edge.gallery.remote.OpenAiChatGateway
import com.google.ai.edge.gallery.remote.OpenAiCompletionEvent
import com.google.ai.edge.gallery.remote.OpenAiMessage
import com.google.ai.edge.gallery.remote.OpenAiProviderSource
import com.google.ai.edge.gallery.remote.OpenAiRequestToolCall
import com.google.ai.edge.gallery.remote.RemoteMcpToolRunner
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_REMOTE_TOOL_LOOPS = 8
private object RemoteModelInstance

class OpenAiAgentRuntimeExecutor(
  private val providerSource: OpenAiProviderSource,
  private val gateway: OpenAiChatGateway,
  private val mcpToolRunner: RemoteMcpToolRunner?,
) : AgentRuntimeExecutor {
  private data class RemoteSession(
    val config: AgentRuntimeConfig,
    val sessionId: String,
    val messages: MutableList<OpenAiMessage>,
  )

  private val activeSession = AtomicReference<RemoteSession?>(null)
  private val activeJob = AtomicReference<Job?>(null)
  private val historyMutex = Mutex()

  override val activeSessionId: String?
    get() = activeSession.get()?.sessionId

  override val activeModelInfo: ActiveModelInfo?
    get() =
      activeSession.get()?.config?.let { config ->
        ActiveModelInfo(
          model = config.model,
          taskId = config.taskId,
          supportImage = false,
        )
      }

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  ) {
    val error =
      runCatching { resetSession(config) }
        .exceptionOrNull()
        ?.message
        .orEmpty()
    onDone(error)
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val providerId = config.model.metadata.remoteProviderId
    require(providerId.isNotBlank()) { "Remote model is missing provider id" }
    requireNotNull(providerSource.findById(providerId)) {
      "Remote provider not found: " + providerId
    }
    val sessionId = config.sessionId.ifEmpty { generateSessionId() }
    val messages = mutableListOf<OpenAiMessage>()
    config.systemInstruction?.takeIf { it.isNotBlank() }?.let {
      messages += OpenAiMessage(role = "system", content = it)
    }
    messages += config.initialTextMessages.map { OpenAiMessage(role = it.role, content = it.content) }
    activeSession.set(
      RemoteSession(
        config = config,
        sessionId = sessionId,
        messages = messages,
      )
    )
    config.model.instance = RemoteModelInstance
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> = flow {
    emit(AgentEvent.LoopInitiated(request))
    val session = activeSession.get()
    if (session == null) {
      emit(AgentEvent.Error("Remote model is not initialized"))
      return@flow
    }

    val provider =
      providerSource.findById(session.config.model.metadata.remoteProviderId)
    if (provider == null) {
      emit(AgentEvent.Error("Remote provider not found"))
      return@flow
    }

    val job = currentCoroutineContext()[Job]
    activeJob.set(job)

    try {
      historyMutex.withLock {
        session.messages += OpenAiMessage(role = "user", content = request.query)
      }

      repeat(MAX_REMOTE_TOOL_LOOPS) {
        val messages = historyMutex.withLock { session.messages.toList() }
        val assistantText = StringBuilder()
        var toolCalls = emptyList<com.google.ai.edge.gallery.remote.OpenAiToolCall>()

        gateway
          .streamCompletion(
            provider = provider,
            messages = messages,
            enableMcpTool = mcpToolRunner != null,
          )
          .collect { event ->
            when (event) {
              is OpenAiCompletionEvent.TextDelta -> {
                assistantText.append(event.text)
                emit(AgentEvent.StreamToken(token = event.text))
              }
              is OpenAiCompletionEvent.Completed -> {
                toolCalls = event.toolCalls
              }
            }
          }

        if (toolCalls.isEmpty()) {
          val finalText = assistantText.toString()
          if (finalText.isNotEmpty()) {
            historyMutex.withLock {
              session.messages += OpenAiMessage(role = "assistant", content = finalText)
            }
          }
          emit(AgentEvent.StreamToken(token = "", done = true))
          emit(AgentEvent.LoopTerminated(finalResponse = finalText))
          return@flow
        }

        historyMutex.withLock {
          session.messages +=
            OpenAiMessage(
              role = "assistant",
              content = assistantText.toString().ifEmpty { null },
              toolCalls = toolCalls.map { it.toRequestToolCall() },
            )
        }

        for (toolCall in toolCalls) {
          val result =
            if (toolCall.name != "runMcpTool" || mcpToolRunner == null) {
              """{"status":"failed","error":"Unsupported remote tool call"}"""
            } else {
              runMcpToolCall(toolCall.arguments)
            }
          historyMutex.withLock {
            session.messages +=
              OpenAiMessage(
                role = "tool",
                content = result,
                toolCallId = toolCall.id,
              )
          }
        }
      }

      emit(AgentEvent.Error("Remote tool loop exceeded " + MAX_REMOTE_TOOL_LOOPS + " turns"))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emit(AgentEvent.Error(e.message ?: "Remote inference failed"))
    } finally {
      activeJob.compareAndSet(job, null)
    }
  }

  private suspend fun runMcpToolCall(arguments: String): String {
    val args =
      runCatching { OPENAI_JSON.parseToJsonElement(arguments).jsonObject }
        .getOrElse {
          return """{"status":"failed","error":"Invalid runMcpTool arguments"}"""
        }
    val toolName = args["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val input = args["input"]?.jsonPrimitive?.contentOrNull ?: "{}"
    if (toolName.isBlank()) {
      return """{"status":"failed","error":"toolName is required"}"""
    }
    return mcpToolRunner?.run(toolName, input)
      ?: """{"status":"failed","error":"MCP tool runner is unavailable"}"""
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    var finalOutput = ""
    var success = true
    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> finalOutput = event.finalResponse
        is AgentEvent.Error -> {
          success = false
          finalOutput = event.errorMessage
        }
        else -> Unit
      }
    }
    return AgentResponse(output = finalOutput, isSuccessful = success)
  }

  override fun interrupt() {
    activeJob.getAndSet(null)?.cancel()
  }

  override fun cleanUp(onDone: () -> Unit) {
    interrupt()
    activeSession.getAndSet(null)?.config?.model?.instance = null
    onDone()
  }
}
