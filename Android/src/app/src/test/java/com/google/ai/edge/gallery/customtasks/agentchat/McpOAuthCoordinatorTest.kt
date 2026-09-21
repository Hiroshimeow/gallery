package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthCoordinatorTest {
  @Test
  fun `www authenticate resource metadata wins discovery`() {
    val header =
      "Bearer realm=\"mcp\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\""
    assertEquals(
      "https://example.com/.well-known/oauth-protected-resource/mcp",
      resourceMetadataUrlFromWwwAuthenticate(header),
    )
  }

  @Test
  fun `protected resource metadata keeps resource path`() {
    assertEquals(
      "https://device.hcu-lab.me/.well-known/oauth-protected-resource/mcp",
      protectedResourceMetadataUrl("https://device.hcu-lab.me/mcp"),
    )
  }

  @Test
  fun `authorization server metadata uses root issuer well known path`() {
    assertEquals(
      "https://device.hcu-lab.me/.well-known/oauth-authorization-server",
      authorizationServerMetadataUrl("https://device.hcu-lab.me/"),
    )
  }

  @Test
  fun `pkce s256 matches rfc7636 example`() {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    assertEquals(
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      pkceChallenge(verifier),
    )
  }

  @Test
  fun `generated verifier is url safe and long enough`() {
    val verifier = generateCodeVerifier()
    assertTrue(verifier.length >= 43)
    assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
  }
}
