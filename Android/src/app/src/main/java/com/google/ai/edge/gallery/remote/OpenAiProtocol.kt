/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val OPENAI_JSON =
  Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
  }

@Serializable
data class OpenAiChatRequest(
  val model: String,
  val messages: List<OpenAiMessage>,
  val stream: Boolean,
  val tools: List<OpenAiToolSpec>? = null,
)

@Serializable
data class OpenAiMessage(
  val role: String,
  val content: String? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null,
  @SerialName("tool_calls") val toolCalls: List<OpenAiRequestToolCall>? = null,
)

@Serializable
data class OpenAiToolSpec(
  val type: String = "function",
  val function: OpenAiFunctionSpec,
)

@Serializable
data class OpenAiFunctionSpec(
  val name: String,
  val description: String,
  val parameters: JsonObject,
)

@Serializable
data class OpenAiRequestToolCall(
  val id: String,
  val type: String = "function",
  val function: OpenAiRequestFunctionCall,
)

@Serializable
data class OpenAiRequestFunctionCall(
  val name: String,
  val arguments: String,
)

data class OpenAiToolCall(
  val id: String,
  val name: String,
  val arguments: String,
) {
  fun toRequestToolCall(): OpenAiRequestToolCall =
    OpenAiRequestToolCall(
      id = id,
      function = OpenAiRequestFunctionCall(name = name, arguments = arguments),
    )
}

data class OpenAiCompletionResult(
  val content: String,
  val toolCalls: List<OpenAiToolCall>,
)

data class OpenAiStreamUpdate(
  val text: String = "",
  val done: Boolean = false,
)

val RUN_MCP_TOOL_SPEC =
  OpenAiToolSpec(
    function =
      OpenAiFunctionSpec(
        name = "runMcpTool",
        description = "Run one enabled MCP tool by exact tool name.",
        parameters =
          JsonObject(
            mapOf(
              "type" to JsonPrimitive("object"),
              "properties" to
                JsonObject(
                  mapOf(
                    "toolName" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "description" to JsonPrimitive("Exact MCP tool name."),
                        )
                      ),
                    "input" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("string"),
                          "description" to JsonPrimitive("JSON object encoded as a string."),
                        )
                      ),
                  )
                ),
              "required" to JsonArray(listOf(JsonPrimitive("toolName"), JsonPrimitive("input"))),
            )
          ),
      )
  )

class OpenAiStreamAccumulator {
  private data class PartialToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
  )

  private val partialCalls = mutableMapOf<Int, PartialToolCall>()

  fun consumeSseLine(line: String): OpenAiStreamUpdate {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(":")) return OpenAiStreamUpdate()
    val payload = if (trimmed.startsWith("data:")) trimmed.removePrefix("data:").trim() else trimmed
    if (payload == "[DONE]") return OpenAiStreamUpdate(done = true)

    val root = OPENAI_JSON.parseToJsonElement(payload).jsonObject
    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return OpenAiStreamUpdate()
    val delta = choice["delta"]?.jsonObject ?: return OpenAiStreamUpdate()
    val text = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

    delta["tool_calls"]?.jsonArray?.forEach { element ->
      val call = element.jsonObject
      val index = call["index"]?.jsonPrimitive?.intOrNull ?: 0
      val partial = partialCalls.getOrPut(index) { PartialToolCall() }
      call["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { partial.id = it }
      val function = call["function"]?.jsonObject
      function?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
        partial.name = it
      }
      function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { partial.arguments.append(it) }
    }

    return OpenAiStreamUpdate(text = text)
  }

  fun completedToolCalls(): List<OpenAiToolCall> =
    partialCalls
      .toSortedMap()
      .values
      .mapNotNull { call ->
        if (call.id.isBlank() || call.name.isBlank()) null
        else OpenAiToolCall(call.id, call.name, call.arguments.toString())
      }
}

fun parseOpenAiNonStreamingResponse(body: String): OpenAiCompletionResult {
  val root = OPENAI_JSON.parseToJsonElement(body).jsonObject
  val message =
    root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
      ?: return OpenAiCompletionResult(content = "", toolCalls = emptyList())
  val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
  val toolCalls =
    message["tool_calls"]
      ?.jsonArray
      ?.mapNotNull { element ->
        val call = element.jsonObject
        val id = call["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val function = call["function"]?.jsonObject ?: return@mapNotNull null
        val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank() || name.isBlank()) null else OpenAiToolCall(id, name, arguments)
      }
      .orEmpty()
  return OpenAiCompletionResult(content = content, toolCalls = toolCalls)
}
