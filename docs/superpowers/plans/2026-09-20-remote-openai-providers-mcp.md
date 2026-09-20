# Remote OpenAI Providers + MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenAI and custom OpenAI-compatible remote providers to Gallery while preserving local inference and enabling existing MCP tools from remote Agent Chat.

**Architecture:** Persist provider profiles, expose them as remote model choices, and route remote models through a new OpenAI-compatible `AgentRuntimeExecutor`. Reuse Gallery's existing chat event stream, ToolDispatcher, ToolsProvider, and MCP implementation.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, Ktor client, kotlinx.serialization, existing Gallery agent/MCP stack.

## Global Constraints
- Base branch remains PR #1217.
- Do not regress LiteRT/AICore local inference or PR #1217 server mode.
- V1 supports only OpenAI and OpenAI-compatible Chat Completions.
- Keep implementation minimal; no provider-specific SDKs.

---

### Task 1: Provider model, persistence, and remote model classification
**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/remote/OpenAiProvider.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/remote/OpenAiProviderRepository.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelEnums.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/BackendSpec.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Model.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/remote/OpenAiProviderTest.kt`

- [ ] Write failing tests for OpenAI default URL, compatible URL normalization, provider validation, and remote model classification.
- [ ] Run the focused test and confirm RED.
- [ ] Implement minimal provider/profile/runtime metadata.
- [ ] Run focused tests and confirm GREEN.

### Task 2: OpenAI-compatible protocol client
**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/remote/OpenAiChatClient.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/remote/OpenAiProtocol.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/remote/OpenAiProtocolTest.kt`

- [ ] Write failing tests for Chat Completions JSON, SSE text deltas, fragmented tool-call arguments, non-streaming fallback, and bounded errors.
- [ ] Confirm RED.
- [ ] Implement Ktor HTTP/SSE protocol mapping without a new dependency.
- [ ] Confirm GREEN.

### Task 3: Remote AgentRuntimeExecutor and MCP tool loop
**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/OpenAiAgentRuntimeExecutor.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentRuntimeConfig.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatTaskModule.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/OpenAiAgentRuntimeExecutorTest.kt`

- [ ] Write failing tests proving remote text emits existing AgentEvents and OpenAI tool calls dispatch existing tools then continue.
- [ ] Confirm RED.
- [ ] Implement remote executor with bounded tool loop and cancellation.
- [ ] Route AI Chat and Agent Chat to the remote executor for remote models.
- [ ] Confirm GREEN.

### Task 4: Provider settings and model injection
**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/RemoteProviderSettings.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`
- Test: `Android/src/app/src/test/java/com/google/ai/edge/gallery/remote/RemoteModelFactoryTest.kt`

- [ ] Write failing tests for provider -> AI Chat/Agent Chat remote model mapping.
- [ ] Confirm RED.
- [ ] Add minimal Add/Edit/Delete/Test UI and inject remote models into eligible tasks.
- [ ] Ensure remote models bypass download requirements.
- [ ] Confirm GREEN.

### Task 5: Regression and APK
- [ ] Run focused new tests.
- [ ] Run existing `:app:testDebugUnitTest`.
- [ ] Run `:app:assembleRelease`.
- [ ] Verify no Android source regression against local provider paths.
- [ ] Push feature branch and publish a test APK from GitHub Actions.
