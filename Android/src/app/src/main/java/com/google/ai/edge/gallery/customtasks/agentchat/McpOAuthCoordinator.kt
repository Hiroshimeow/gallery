/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.net.Uri
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.McpAuth
import com.google.ai.edge.gallery.proto.UserData
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val OAUTH_CALLBACK_URI = "com.google.ai.edge.gallery://oauth/mcp"
private const val TOKEN_REFRESH_SKEW_SECONDS = 60L

data class McpOAuthCompletion(val serverUrl: String, val error: String? = null)

@Singleton
class McpOAuthCoordinator
@Inject
constructor(private val userDataDataStore: DataStore<UserData>) {
  private val json = Json { ignoreUnknownKeys = true }
  private val client = HttpClient(Android)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val accessTokens = ConcurrentHashMap<String, String>()
  private val refreshJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
  private val _completions = MutableSharedFlow<McpOAuthCompletion>(extraBufferCapacity = 8)
  val completions = _completions.asSharedFlow()

  suspend fun beginAuthorization(serverUrl: String): String {
    val resource = normalizeResourceUrl(serverUrl)
    val resourceProbe = client.get(resource)
    val protectedMetadataUrl =
      resourceMetadataUrlFromWwwAuthenticate(resourceProbe.headers[HttpHeaders.WWWAuthenticate])
        ?: protectedResourceMetadataUrl(resource)
    val protectedResponse = client.get(protectedMetadataUrl)
    check(protectedResponse.status.isSuccess()) {
      "Protected-resource metadata returned HTTP ${protectedResponse.status.value}"
    }
    val protectedJson = json.parseToJsonElement(protectedResponse.bodyAsText()).jsonObject
    val advertisedResource =
      protectedJson["resource"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: resource
    val authorizationServer =
      protectedJson["authorization_servers"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: error("OAuth protected-resource metadata has no authorization server")

    val metadataResponse = client.get(authorizationServerMetadataUrl(authorizationServer))
    check(metadataResponse.status.isSuccess()) {
      "Authorization metadata returned HTTP ${metadataResponse.status.value}"
    }
    val metadata = json.parseToJsonElement(metadataResponse.bodyAsText()).jsonObject
    val issuer = metadata["issuer"]?.jsonPrimitive?.contentOrNull ?: authorizationServer
    val authorizationEndpoint =
      metadata["authorization_endpoint"]?.jsonPrimitive?.contentOrNull
        ?: error("Authorization metadata has no authorization_endpoint")
    val tokenEndpoint =
      metadata["token_endpoint"]?.jsonPrimitive?.contentOrNull
        ?: error("Authorization metadata has no token_endpoint")
    val registrationEndpoint =
      metadata["registration_endpoint"]?.jsonPrimitive?.contentOrNull
        ?: error("Authorization server does not support dynamic client registration")

    val clientId = registerNativeClient(registrationEndpoint)
    val verifier = generateCodeVerifier()
    val challenge = pkceChallenge(verifier)
    val state = randomUrlSafe(24)
    val scopes =
      protectedJson["scopes_supported"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it == "mcp:tools" || it == "offline_access" }
        ?.ifEmpty { listOf("mcp:tools") }
        ?: listOf("mcp:tools", "offline_access")
    val scopeText = scopes.distinct().joinToString(" ")

    val oauth =
      McpAuth.OAuth.newBuilder()
        .setIssuer(issuer)
        .setAuthorizationEndpoint(authorizationEndpoint)
        .setTokenEndpoint(tokenEndpoint)
        .setRegistrationEndpoint(registrationEndpoint)
        .setClientId(clientId)
        .setScope(scopeText)
        .setResource(advertisedResource)
        .setPendingState(state)
        .setCodeVerifier(verifier)
        .setRedirectUri(OAUTH_CALLBACK_URI)
        .build()
    persistOAuth(resource, oauth)

    val params =
      linkedMapOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to OAUTH_CALLBACK_URI,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "state" to state,
        "scope" to scopeText,
        "resource" to advertisedResource,
      )
    return authorizationEndpoint + "?" + params.entries.joinToString("&") { (key, value) ->
      "${urlEncode(key)}=${urlEncode(value)}"
    }
  }

  fun isOAuthCallback(uri: Uri?): Boolean =
    uri != null &&
      uri.scheme == "com.google.ai.edge.gallery" &&
      uri.host == "oauth" &&
      uri.path == "/mcp"

  suspend fun completeAuthorizationCallback(uri: Uri): Result<String> {
    val state = uri.getQueryParameter("state").orEmpty()
    val authMap = userDataDataStore.data.first().mcpAuthsMap
    val entry =
      authMap.entries.firstOrNull { (_, auth) ->
        auth.authMethodCase == McpAuth.AuthMethodCase.OAUTH && auth.oauth.pendingState == state
      }

    val serverUrl = entry?.key.orEmpty()
    val oauthError = uri.getQueryParameter("error")
    if (!oauthError.isNullOrBlank()) {
      val description = uri.getQueryParameter("error_description").orEmpty()
      val message = listOf(oauthError, description).filter { it.isNotBlank() }.joinToString(": ")
      _completions.emit(McpOAuthCompletion(serverUrl, message))
      return Result.failure(IllegalStateException(message))
    }

    val code = uri.getQueryParameter("code").orEmpty()
    if (state.isBlank() || code.isBlank()) {
      return Result.failure(IllegalArgumentException("OAuth callback is missing code or state"))
    }
    if (entry == null) {
      return Result.failure(IllegalStateException("OAuth callback state does not match a pending MCP login"))
    }

    val oauth = entry.value.oauth
    val callbackIssuer = uri.getQueryParameter("iss")
    if (!callbackIssuer.isNullOrBlank() && callbackIssuer != oauth.issuer) {
      val message = "OAuth issuer mismatch"
      _completions.emit(McpOAuthCompletion(serverUrl, message))
      return Result.failure(IllegalStateException(message))
    }

    return runCatching {
      val token = exchangeAuthorizationCode(oauth, code)
      persistOAuth(serverUrl, token)
      cacheAndSchedule(serverUrl, token)
      _completions.emit(McpOAuthCompletion(serverUrl))
      serverUrl
    }.onFailure { failure ->
      _completions.emit(McpOAuthCompletion(serverUrl, failure.message ?: "OAuth token exchange failed"))
    }
  }

  suspend fun ensureAccessToken(serverUrl: String): String? {
    val normalized = normalizeResourceUrl(serverUrl)
    val auth = userDataDataStore.data.first().mcpAuthsMap[normalized] ?: return null
    if (auth.authMethodCase != McpAuth.AuthMethodCase.OAUTH) return null
    val oauth = auth.oauth
    val now = System.currentTimeMillis() / 1000
    if (oauth.accessToken.isNotBlank() && oauth.expiresAtEpochSeconds > now + TOKEN_REFRESH_SKEW_SECONDS) {
      cacheAndSchedule(normalized, oauth)
      return oauth.accessToken
    }
    if (oauth.refreshToken.isNotBlank()) return refresh(normalized, oauth).accessToken
    return oauth.accessToken.takeIf { it.isNotBlank() }?.also { accessTokens[normalized] = it }
  }

  fun currentAccessToken(serverUrl: String): String? =
    accessTokens[normalizeResourceUrl(serverUrl)]

  suspend fun forceRefresh(serverUrl: String): String? {
    val normalized = normalizeResourceUrl(serverUrl)
    val auth = userDataDataStore.data.first().mcpAuthsMap[normalized] ?: return null
    if (auth.authMethodCase != McpAuth.AuthMethodCase.OAUTH || auth.oauth.refreshToken.isBlank()) {
      return null
    }
    return refresh(normalized, auth.oauth).accessToken
  }

  suspend fun remove(serverUrl: String) {
    val normalized = normalizeResourceUrl(serverUrl)
    refreshJobs.remove(normalized)?.cancel()
    accessTokens.remove(normalized)
    userDataDataStore.updateData { userData ->
      userData.toBuilder().removeMcpAuths(normalized).build()
    }
  }

  private suspend fun registerNativeClient(registrationEndpoint: String): String {
    val body =
      """
      {
        "client_name":"Google AI Edge Gallery",
        "redirect_uris":["$OAUTH_CALLBACK_URI"],
        "grant_types":["authorization_code","refresh_token"],
        "response_types":["code"],
        "token_endpoint_auth_method":"none",
        "application_type":"native"
      }
      """.trimIndent()
    val response =
      client.post(registrationEndpoint) {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    check(response.status.isSuccess()) {
      "OAuth client registration returned HTTP ${response.status.value}: ${response.bodyAsText().take(512)}"
    }
    val registered = json.parseToJsonElement(response.bodyAsText()).jsonObject
    return registered["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: error("OAuth registration response has no client_id")
  }

  private suspend fun exchangeAuthorizationCode(oauth: McpAuth.OAuth, code: String): McpAuth.OAuth {
    val response =
      client.post(oauth.tokenEndpoint) {
        setBody(
          FormDataContent(
            Parameters.build {
              append("grant_type", "authorization_code")
              append("code", code)
              append("client_id", oauth.clientId)
              append("redirect_uri", oauth.redirectUri)
              append("code_verifier", oauth.codeVerifier)
              if (oauth.resource.isNotBlank()) append("resource", oauth.resource)
            }
          )
        )
      }
    check(response.status.isSuccess()) {
      "OAuth token exchange returned HTTP ${response.status.value}: ${response.bodyAsText().take(512)}"
    }
    return applyTokenResponse(oauth, response.bodyAsText())
  }

  private suspend fun refresh(serverUrl: String, oauth: McpAuth.OAuth): McpAuth.OAuth {
    val response =
      client.post(oauth.tokenEndpoint) {
        setBody(
          FormDataContent(
            Parameters.build {
              append("grant_type", "refresh_token")
              append("refresh_token", oauth.refreshToken)
              append("client_id", oauth.clientId)
              if (oauth.resource.isNotBlank()) append("resource", oauth.resource)
            }
          )
        )
      }
    check(response.status.isSuccess()) {
      "OAuth refresh returned HTTP ${response.status.value}: ${response.bodyAsText().take(512)}"
    }
    val updated = applyTokenResponse(oauth, response.bodyAsText())
    persistOAuth(serverUrl, updated)
    cacheAndSchedule(serverUrl, updated)
    return updated
  }

  private fun applyTokenResponse(current: McpAuth.OAuth, body: String): McpAuth.OAuth {
    val token = json.parseToJsonElement(body).jsonObject
    val accessToken =
      token["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("OAuth token response has no access_token")
    val refreshToken =
      token["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: current.refreshToken
    val expiresIn = token["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
    val scope = token["scope"]?.jsonPrimitive?.contentOrNull ?: current.scope
    return current
      .toBuilder()
      .setAccessToken(accessToken)
      .setRefreshToken(refreshToken)
      .setExpiresAtEpochSeconds(System.currentTimeMillis() / 1000 + expiresIn)
      .setScope(scope)
      .clearPendingState()
      .clearCodeVerifier()
      .build()
  }

  private suspend fun persistOAuth(serverUrl: String, oauth: McpAuth.OAuth) {
    val normalized = normalizeResourceUrl(serverUrl)
    val auth = McpAuth.newBuilder().setOauth(oauth).build()
    userDataDataStore.updateData { current ->
      current.toBuilder().putMcpAuths(normalized, auth).build()
    }
  }

  private fun cacheAndSchedule(serverUrl: String, oauth: McpAuth.OAuth) {
    val normalized = normalizeResourceUrl(serverUrl)
    if (oauth.accessToken.isNotBlank()) accessTokens[normalized] = oauth.accessToken
    refreshJobs.remove(normalized)?.cancel()
    if (oauth.refreshToken.isBlank() || oauth.expiresAtEpochSeconds <= 0) return
    val waitMs =
      ((oauth.expiresAtEpochSeconds - TOKEN_REFRESH_SKEW_SECONDS) * 1000 - System.currentTimeMillis())
        .coerceAtLeast(1_000L)
    refreshJobs[normalized] =
      scope.launch {
        delay(waitMs)
        runCatching { forceRefresh(normalized) }
      }
  }
}

internal fun normalizeResourceUrl(url: String): String = url.trim().trimEnd('/')

internal fun resourceMetadataUrlFromWwwAuthenticate(header: String?): String? {
  if (header.isNullOrBlank()) return null
  return Regex("""resource_metadata=\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
    .find(header)
    ?.groupValues
    ?.getOrNull(1)
    ?.takeIf { it.isNotBlank() }
}

internal fun protectedResourceMetadataUrl(resourceUrl: String): String {
  val uri = URI(resourceUrl)
  val path = uri.rawPath.orEmpty().ifBlank { "/" }
  val suffix = if (path == "/") "" else path
  return "${uri.scheme}://${uri.rawAuthority}/.well-known/oauth-protected-resource${suffix}"
}

internal fun authorizationServerMetadataUrl(issuerUrl: String): String {
  val issuer = URI(issuerUrl)
  val path = issuer.rawPath.orEmpty().trimEnd('/')
  val suffix = if (path.isBlank()) "" else path
  return "${issuer.scheme}://${issuer.rawAuthority}/.well-known/oauth-authorization-server${suffix}"
}

internal fun pkceChallenge(verifier: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
  return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun generateCodeVerifier(): String = randomUrlSafe(48)

private fun randomUrlSafe(byteCount: Int): String {
  val bytes = ByteArray(byteCount)
  SecureRandom().nextBytes(bytes)
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun urlEncode(value: String): String =
  URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
