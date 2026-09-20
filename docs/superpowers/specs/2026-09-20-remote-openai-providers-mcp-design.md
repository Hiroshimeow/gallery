# Remote OpenAI Providers + MCP Design

## Goal
Keep Google AI Edge Gallery's existing LiteRT/AICore local inference and PR #1217 local API server, while adding remote OpenAI providers that can be selected from the same chat UI. Remote Agent Chat must be able to use Gallery's existing MCP tools.

## Scope
V1 supports exactly two provider presets:
1. **OpenAI** — fixed default base URL `https://api.openai.com/v1`, configurable API key and model.
2. **OpenAI-compatible** — configurable base URL, optional API key, and model.

Both support text chat with streaming. Agent Chat additionally sends OpenAI tool schemas and executes returned tool calls through Gallery's existing `ToolDispatcher`, including `runMcpTool`.

No Anthropic/Gemini/Ollama-specific protocol adapters, image/audio remote upload, Responses API-only features, or provider discovery beyond optional `GET /v1/models`.

## Architecture
- Persist provider profiles in a small DataStore-backed repository.
- Represent configured remote models as Gallery `Model` values with a dedicated remote runtime type and provider metadata, but never run them through the download pipeline.
- Route model initialization/execution by runtime type:
  - LiteRT/AICore: existing `DefaultAgentRuntimeExecutor`.
  - Remote OpenAI: `OpenAiAgentRuntimeExecutor`.
- The remote executor owns conversation history per session, emits existing `AgentEvent` values, and therefore reuses the current Chat UI unchanged.
- For Agent Chat, tools come from existing `ToolsProvider`. The executor maps them to OpenAI function-tool schemas, executes returned calls through existing `ToolDispatcher`, appends tool results, and continues until the model returns assistant text or the bounded tool loop limit is reached.
- PR #1217 remains untouched functionally: the phone can still serve an on-device model on port 8080 while Gallery itself chats to a stronger remote endpoint.

## UI
Settings gains **Remote providers** with Add/Edit/Delete:
- Provider type: OpenAI or OpenAI-compatible
- Name
- Base URL (locked to OpenAI default for OpenAI preset)
- API key
- Model
- Test connection

Configured remote models are appended to AI Chat and Agent Chat model choices and marked as remote. They do not show download actions.

## Security
- API keys are stored only in app-private DataStore.
- Authorization header is omitted when key is blank.
- Error bodies shown to the user are bounded and must not echo Authorization values.
- HTTP is allowed for LAN/Tailscale custom endpoints because this feature explicitly targets local servers.

## Compatibility
- `/v1/chat/completions` is the primary protocol for both presets.
- Streaming uses SSE `data:` frames ending in `[DONE]`.
- Non-streaming response fallback is accepted.
- Tool calls use OpenAI `tools: [{type:"function", function:{...}}]`.
- Multiple tool calls in one assistant turn are supported sequentially.
- Tool loop is bounded.

## Testing
Unit tests cover provider normalization/validation, request construction, SSE accumulation, tool-call parsing, tool-result continuation, and remote model classification. Existing local runtime tests must remain green. CI must produce a release APK.
