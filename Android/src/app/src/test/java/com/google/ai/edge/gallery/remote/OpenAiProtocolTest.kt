package com.google.ai.edge.gallery.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProtocolTest {
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
      "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\",\\\"input\\\":\\\"{ }\\\"}\"}}]}}]}"
    )
    val done = accumulator.consumeSseLine("data: [DONE]")

    assertEquals("hello", text.text)
    assertTrue(done.done)
    assertEquals(
      OpenAiToolCall(
        id = "call_1",
        name = "runMcpTool",
        arguments = "{\"toolName\":\"shell\",\"input\":\"{ }\"}",
      ),
      accumulator.completedToolCalls().single(),
    )
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
