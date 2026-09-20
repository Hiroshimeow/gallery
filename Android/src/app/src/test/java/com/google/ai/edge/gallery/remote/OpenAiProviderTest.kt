package com.google.ai.edge.gallery.remote

import com.google.ai.edge.gallery.data.RuntimeType
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
