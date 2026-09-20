/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.remote

import com.google.ai.edge.gallery.tools.RunMcpTool
import com.google.ai.edge.gallery.tools.ToolsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface RemoteMcpToolRunner {
  suspend fun run(toolName: String, input: String): String
}

class GalleryRemoteMcpToolRunner(
  private val toolsProvider: ToolsProvider,
) : RemoteMcpToolRunner {
  override suspend fun run(toolName: String, input: String): String =
    withContext(Dispatchers.Default) {
      val runner =
        toolsProvider.getAvailableTools().filterIsInstance<RunMcpTool>().firstOrNull()
          ?: return@withContext buildJsonObject {
            put("status", JsonPrimitive("failed"))
            put("error", JsonPrimitive("MCP tool runner is not available"))
          }.toString()
      val result = runner.runMcpTool(toolName = toolName, input = input)
      buildJsonObject {
          result.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        .toString()
    }
}
