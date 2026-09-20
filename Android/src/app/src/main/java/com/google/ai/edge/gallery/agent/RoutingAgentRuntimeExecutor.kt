/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.agent

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow

/**
 * Routes each session to the existing local runtime or the remote OpenAI-compatible runtime.
 *
 * The active delegate is selected during initialize/reset and then used for execution lifecycle
 * calls until another model is selected.
 */
class RoutingAgentRuntimeExecutor(
  private val local: AgentRuntimeExecutor,
  private val remote: AgentRuntimeExecutor,
) : AgentRuntimeExecutor {
  private val active = AtomicReference<AgentRuntimeExecutor?>(null)

  private fun delegateFor(config: AgentRuntimeConfig): AgentRuntimeExecutor =
    if (config.model.isRemoteOpenAi) remote else local

  private fun activeDelegate(): AgentRuntimeExecutor =
    active.get() ?: error("Agent runtime is not initialized")

  override val activeSessionId: String?
    get() = active.get()?.activeSessionId

  override val activeModelInfo: ActiveModelInfo?
    get() = active.get()?.activeModelInfo

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  ) {
    val delegate = delegateFor(config)
    active.set(delegate)
    delegate.initialize(context, config, onDone)
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> = activeDelegate().executeStream(context, request)

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse = activeDelegate().execute(context, request)

  override fun interrupt() {
    active.get()?.interrupt()
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    val delegate = delegateFor(config)
    active.set(delegate)
    delegate.resetSession(config)
  }

  override fun cleanUp(onDone: () -> Unit) {
    val delegate = active.getAndSet(null)
    if (delegate == null) onDone() else delegate.cleanUp(onDone)
  }
}
