package com.google.ai.edge.gallery.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelAllowlistVersionFallbackTest {
  @Test
  fun `current patch falls back to immediately previous patch`() {
    assertEquals(
      listOf("1_0_20", "1_0_19"),
      getAllowlistVersionCandidates("1.0.20"),
    )
  }

  @Test
  fun `zero patch does not cross minor boundary`() {
    assertEquals(
      listOf("1_0_0"),
      getAllowlistVersionCandidates("1.0.0"),
    )
  }

  @Test
  fun `unparseable version uses exact normalized version only`() {
    assertEquals(
      listOf("dev_build"),
      getAllowlistVersionCandidates("dev.build"),
    )
  }
}
