package com.google.ai.edge.gallery.agent

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.remote.OpenAiProvider
import com.google.ai.edge.gallery.remote.OpenAiProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingAgentRuntimeExecutorTest {
  @Test
  fun `reset routes remote models to remote executor and local models to local executor`() =
    runBlocking {
      val local = RecordingExecutor()
      val remote = RecordingExecutor()
      val router = RoutingAgentRuntimeExecutor(local = local, remote = remote)

      val remoteModel =
        OpenAiProvider(
            id = "remote",
            name = "Remote",
            type = OpenAiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://host:8000/v1",
            model = "qwen",
          )
          .toRemoteModel()
      router.resetSession(AgentRuntimeConfig(model = remoteModel, taskId = "chat"))
      assertEquals(0, local.resetCount)
      assertEquals(1, remote.resetCount)

      router.resetSession(AgentRuntimeConfig(model = Model(name = "local"), taskId = "chat"))
      assertEquals(1, local.resetCount)
      assertEquals(1, remote.resetCount)
    }

  private class RecordingExecutor : AgentRuntimeExecutor {
    var resetCount = 0

    override suspend fun initialize(
      context: Context,
      config: AgentRuntimeConfig,
      onDone: (String) -> Unit,
    ) {
      onDone("")
    }

    override fun executeStream(
      context: AgentExecutionContext,
      request: AgentRequest,
    ): Flow<AgentEvent> = emptyFlow()

    override suspend fun execute(
      context: AgentExecutionContext,
      request: AgentRequest,
    ): AgentResponse = AgentResponse("", true)

    override fun interrupt() = Unit

    override suspend fun resetSession(config: AgentRuntimeConfig) {
      resetCount += 1
    }

    override fun cleanUp(onDone: () -> Unit) = onDone()
  }
}
