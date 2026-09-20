/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val PROVIDERS_KEY = stringPreferencesKey("remote_openai_providers")
private val PROVIDER_JSON = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

interface OpenAiProviderSource {
  suspend fun findById(id: String): OpenAiProvider?
}

@Singleton
class OpenAiProviderRepository
@Inject
constructor(private val dataStore: DataStore<Preferences>) : OpenAiProviderSource {
  val providers: Flow<List<OpenAiProvider>> =
    dataStore.data.map { preferences -> decode(preferences[PROVIDERS_KEY]) }

  suspend fun readAll(): List<OpenAiProvider> = providers.first()

  override suspend fun findById(id: String): OpenAiProvider? = readAll().firstOrNull { it.id == id }

  suspend fun save(provider: OpenAiProvider) {
    provider.validationError()?.let { error -> throw IllegalArgumentException(error) }
    dataStore.edit { preferences ->
      val current = decode(preferences[PROVIDERS_KEY])
      val updated = current.filterNot { it.id == provider.id } + provider
      preferences[PROVIDERS_KEY] =
        PROVIDER_JSON.encodeToString(ListSerializer(OpenAiProvider.serializer()), updated)
    }
  }

  suspend fun delete(id: String) {
    dataStore.edit { preferences ->
      val updated = decode(preferences[PROVIDERS_KEY]).filterNot { it.id == id }
      preferences[PROVIDERS_KEY] =
        PROVIDER_JSON.encodeToString(ListSerializer(OpenAiProvider.serializer()), updated)
    }
  }

  private fun decode(raw: String?): List<OpenAiProvider> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        PROVIDER_JSON.decodeFromString(ListSerializer(OpenAiProvider.serializer()), raw)
      }
      .getOrDefault(emptyList())
  }
}
