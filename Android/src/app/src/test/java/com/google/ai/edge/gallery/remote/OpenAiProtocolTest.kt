package com.google.ai.edge.gallery.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProtocolTest {
  @Test
  fun `models response parses all model ids`() {
    val body = """{"object":"list","data":[{"id":"qwen3-32b"},{"id":"qwen3-vl"},{"id":"gpt-oss"}]}"""

    assertEquals(listOf("qwen3-32b", "qwen3-vl", "gpt-oss"), parseOpenAiModelsResponse(body))
  }

  @Test
  fun `multimodal user content serializes text image and wav audio`() {
    val message =
      createOpenAiUserMessage(
        text = "describe this",
        imageDataUrls = listOf("data:image/jpeg;base64,abc"),
        audioBase64Wav = listOf("ZGF0YQ=="),
      )
    val request = OpenAiChatRequest(model = "omni", messages = listOf(message), stream = true)

    val json = OPENAI_JSON.encodeToString(OpenAiChatRequest.serializer(), request)

    assertTrue(json.contains("\"type\":\"text\""))
    assertTrue(json.contains("\"type\":\"image_url\""))
    assertTrue(json.contains("data:image/jpeg;base64,abc"))
    assertTrue(json.contains("\"type\":\"input_audio\""))
    assertTrue(json.contains("\"format\":\"wav\""))
  }

  @Test
  fun `request includes model stream messages and generic mcp tool`() {
    val request =
      OpenAiChatRequest(
        model = "qwen",
        messages =
          listOf(
            OpenAiMessage(role = "system", content = "You are concise"),
            OpenAiMessage(role = "user", content = "check g8"),
          ),
        stream = true,
        tools = listOf(RUN_MCP_TOOL_SPEC),
      )

    val json = OPENAI_JSON.encodeToString(OpenAiChatRequest.serializer(), request)

    assertTrue(json.contains("\"model\":\"qwen\""))
    assertTrue(json.contains("\"stream\":true"))
    assertTrue(json.contains("\"name\":\"runMcpTool\""))
    assertTrue(json.contains("\"toolName\""))
    assertTrue(json.contains("\"input\""))
  }

  @Test
  fun `stream accumulator emits text and reconstructs fragmented tool arguments`() {
    val accumulator = OpenAiStreamAccumulator()

    val text =
      accumulator.consumeSseLine(
        "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"
      )
    accumulator.consumeSseLine(
      "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"runMcpTool\",\"arguments\":\"{\\\"toolName\\\":\\\"shell\"}}]}}]}"
    )
    accumulator.consumeSseLine(
      "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\",\\\"input\\\":\\\"{ }\\\"}\"}}]}}]}"
    )
    val done = accumulator.consumeSseLine("data: [DONE]")

    assertEquals("hello", text.text)
    assertTrue(done.done)
    val toolCall = accumulator.completedToolCalls().single()
    assertEquals("call_1", toolCall.id)
    assertEquals("runMcpTool", toolCall.name)
    assertEquals("{\"toolName\":\"shell\",\"input\":\"{ }\"}", toolCall.arguments)
  }

  @Test
  fun `non streaming response parses assistant content and tool calls`() {
    val body =
      """
      {
        "choices": [{
          "message": {
            "role": "assistant",
            "content": "done",
            "tool_calls": [{
              "id": "call_2",
              "type": "function",
              "function": {"name": "runMcpTool", "arguments": "{\"toolName\":\"status\",\"input\":\"{}\"}"}
            }]
          }
        }]
      }
      """.trimIndent()

    val result = parseOpenAiNonStreamingResponse(body)

    assertEquals("done", result.content)
    assertEquals("call_2", result.toolCalls.single().id)
    assertEquals("runMcpTool", result.toolCalls.single().name)
  }
}
