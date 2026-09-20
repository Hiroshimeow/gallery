package com.google.ai.edge.gallery.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.RuntimeType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
  @Test
  fun `openai preset uses official base url`() {
    val provider =
      OpenAiProvider(
        id = "openai",
        name = "OpenAI",
        type = OpenAiProviderType.OPENAI,
        baseUrl = "http://ignored.example/v1/",
        apiKey = "sk-test",
        model = "gpt-5.6",
      )

    assertEquals("https://api.openai.com/v1", provider.effectiveBaseUrl)
    assertTrue(provider.validationError().isNullOrEmpty())
  }

  @Test
  fun `compatible provider normalizes trailing slash`() {
    val provider =
      OpenAiProvider(
        id = "a5000",
        name = "A5000",
        type = OpenAiProviderType.OPENAI_COMPATIBLE,
        baseUrl = "http://100.69.33.37:8000/v1/",
        apiKey = "",
        model = "qwen",
      )

    assertEquals("http://100.69.33.37:8000/v1", provider.effectiveBaseUrl)
    assertTrue(provider.validationError().isNullOrEmpty())
  }

  @Test
  fun `compatible provider requires http url and model`() {
    val invalidUrl =
      OpenAiProvider(
        id = "bad",
        name = "Bad",
        type = OpenAiProviderType.OPENAI_COMPATIBLE,
        baseUrl = "ftp://host/v1",
        model = "qwen",
      )
    val missingModel = invalidUrl.copy(baseUrl = "http://host:8000/v1", model = "")

    assertFalse(invalidUrl.validationError().isNullOrEmpty())
    assertFalse(missingModel.validationError().isNullOrEmpty())
  }

  @Test
  fun `repository saves updates and deletes providers`() = runBlocking {
    val file = File.createTempFile("openai_provider_test", ".preferences_pb").also { it.delete() }
    try {
      val repository = OpenAiProviderRepository(PreferenceDataStoreFactory.create { file })
      val first =
        OpenAiProvider(
          id = "a5000",
          name = "A5000",
          type = OpenAiProviderType.OPENAI_COMPATIBLE,
          baseUrl = "http://host:8000/v1",
          model = "qwen",
        )
      repository.save(first)
      assertEquals(listOf(first), repository.readAll())

      val updated = first.copy(model = "qwen-32b")
      repository.save(updated)
      assertEquals(listOf(updated), repository.readAll())

      repository.delete(first.id)
      assertTrue(repository.readAll().isEmpty())
    } finally {
      file.delete()
    }
  }

  @Test
  fun `remote models are offered only to ai chat and agent chat`() {
    val provider =
      OpenAiProvider(
        id = "remote",
        name = "Remote",
        type = OpenAiProviderType.OPENAI_COMPATIBLE,
        baseUrl = "http://host:8000/v1",
        model = "qwen",
      )

    assertEquals(1, remoteModelsForTask(BuiltInTaskId.LLM_CHAT, listOf(provider)).size)
    assertEquals(1, remoteModelsForTask(BuiltInTaskId.LLM_AGENT_CHAT, listOf(provider)).size)
    assertTrue(remoteModelsForTask(BuiltInTaskId.LLM_ASK_IMAGE, listOf(provider)).isEmpty())
  }

  @Test
  fun `provider maps to remote model with provider id`() {
    val provider =
      OpenAiProvider(
        id = "a5000",
        name = "A5000",
        type = OpenAiProviderType.OPENAI_COMPATIBLE,
        baseUrl = "http://host:8000/v1",
        model = "qwen-32b",
      )

    val model = provider.toRemoteModel()

    assertEquals(RuntimeType.OPENAI_REMOTE, model.backendSpec.runtimeType)
    assertTrue(model.isRemoteOpenAi)
    assertEquals("a5000", model.metadata.remoteProviderId)
    assertEquals("qwen-32b", model.metadata.remoteModelId)
    assertTrue(model.isLlm)
  }
}
