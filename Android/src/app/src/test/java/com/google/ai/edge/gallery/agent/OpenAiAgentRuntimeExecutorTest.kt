package com.google.ai.edge.gallery.agent

import com.google.ai.edge.gallery.remote.OpenAiChatGateway
import com.google.ai.edge.gallery.remote.OpenAiCompletionEvent
import com.google.ai.edge.gallery.remote.OpenAiMessage
import com.google.ai.edge.gallery.remote.OpenAiProvider
import com.google.ai.edge.gallery.remote.OpenAiProviderSource
import com.google.ai.edge.gallery.remote.OpenAiProviderType
import com.google.ai.edge.gallery.remote.OpenAiToolCall
import com.google.ai.edge.gallery.remote.RemoteMcpToolRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAgentRuntimeExecutorTest {
  private val provider =
    OpenAiProvider(
      id = "remote",
      name = "Remote",
      type = OpenAiProviderType.OPENAI_COMPATIBLE,
      baseUrl = "http://host:8000/v1",
      model = "qwen",
    )

  @Test
  fun `remote text response is exposed through existing AgentEvents`() = runBlocking {
    val gateway =
      ScriptedGateway(
        listOf(
          listOf(
            OpenAiCompletionEvent.TextDelta("hello "),
            OpenAiCompletionEvent.TextDelta("world"),
            OpenAiCompletionEvent.Completed(emptyList()),
          )
        )
      )
    val executor =
      OpenAiAgentRuntimeExecutor(
        providerSource = FixedProviderSource(provider),
        gateway = gateway,
        mcpToolRunner = null,
      )
    executor.resetSession(
      AgentRuntimeConfig(model = provider.toRemoteModel(), taskId = "llm_chat")
    )

    val events =
      executor
        .executeStream(AgentExecutionContext(), AgentRequest(query = "hi"))
        .toList()

    assertTrue(events.any { it is AgentEvent.StreamToken && it.token == "hello " })
    assertTrue(events.any { it is AgentEvent.StreamToken && it.token == "world" })
    assertEquals("hello world", events.filterIsInstance<AgentEvent.LoopTerminated>().single().finalResponse)
  }

  @Test
  fun `remote tool call runs Gallery MCP tool then continues model turn`() = runBlocking {
    val gateway =
      ScriptedGateway(
        listOf(
          listOf(
            OpenAiCompletionEvent.Completed(
              listOf(
                OpenAiToolCall(
                  id = "call_1",
                  name = "runMcpTool",
                  arguments = "{\"toolName\":\"machine_status\",\"input\":\"{ }\"}",
                )
              )
            )
          ),
          listOf(
            OpenAiCompletionEvent.TextDelta("G8 is online"),
            OpenAiCompletionEvent.Completed(emptyList()),
          ),
        )
      )
    val runner = RecordingMcpRunner()
    val executor =
      OpenAiAgentRuntimeExecutor(
        providerSource = FixedProviderSource(provider),
        gateway = gateway,
        mcpToolRunner = runner,
      )
    executor.resetSession(
      AgentRuntimeConfig(model = provider.toRemoteModel(), taskId = "agent_chat")
    )

    val events =
      executor
        .executeStream(AgentExecutionContext(), AgentRequest(query = "check G8"))
        .toList()

    assertEquals(listOf("machine_status" to "{ }"), runner.calls)
    assertEquals("G8 is online", events.filterIsInstance<AgentEvent.LoopTerminated>().single().finalResponse)
    assertTrue(
      gateway.requests[1].any {
        it.role == "tool" && it.toolCallId == "call_1" && it.content!!.contains("succeeded")
      }
    )
  }

  private class FixedProviderSource(private val provider: OpenAiProvider) : OpenAiProviderSource {
    override suspend fun findById(id: String): OpenAiProvider? = provider.takeIf { it.id == id }
  }

  private class RecordingMcpRunner : RemoteMcpToolRunner {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun run(toolName: String, input: String): String {
      calls += toolName to input
      return "{\"status\":\"succeeded\",\"result\":\"ok\"}"
    }
  }

  private class ScriptedGateway(
    private val turns: List<List<OpenAiCompletionEvent>>
  ) : OpenAiChatGateway {
    val requests = mutableListOf<List<OpenAiMessage>>()
    private var index = 0

    override fun streamCompletion(
      provider: OpenAiProvider,
      messages: List<OpenAiMessage>,
      enableMcpTool: Boolean,
    ): Flow<OpenAiCompletionEvent> = flow {
      requests += messages.toList()
      for (event in turns[index++]) emit(event)
    }
  }
}
