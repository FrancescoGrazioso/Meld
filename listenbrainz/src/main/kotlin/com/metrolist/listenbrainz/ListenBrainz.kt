/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * ListenBrainz scrobbling client.
 * API docs: https://listenbrainz.readthedocs.io/en/latest/submission_api.html
 */
package com.metrolist.listenbrainz

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

data class LbValidationResponse(
    val code: Int,
    val message: String,
    val valid: Boolean,
    val userName: String?,
)

object ListenBrainz {
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 10

    var token: String = ""
    var enabled: Boolean = false

    private const val BASE_URL = "https://api.listenbrainz.org/1"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest { url(BASE_URL) }
            expectSuccess = false
        }
    }

    suspend fun validateToken(token: String): Result<LbValidationResponse> = runCatching {
        val resp = client.get("$BASE_URL/validate-token") {
            header(HttpHeaders.Authorization, "Token $token")
        }
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val valid = root["valid"]?.jsonPrimitive?.booleanOrNull ?: false
        val response = LbValidationResponse(
            code = root["code"]?.jsonPrimitive?.intOrNull ?: 0,
            message = root["message"]?.jsonPrimitive?.contentOrNull ?: "",
            valid = valid,
            userName = root["user_name"]?.jsonPrimitive?.contentOrNull,
        )
        require(valid) { "ListenBrainz token invalid: ${response.message}" }
        response
    }

    private suspend fun submitListen(
        artist: String,
        track: String,
        album: String?,
        duration: Int?,
        timestamp: Long?,
        listeningType: String,
    ): Result<Unit> {
        val lbToken = token
        return runCatching {
            val trackMetadata = buildJsonObject {
                put("track_name", track)
                put("artist_name", artist)
                album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                duration?.takeIf { it > 0 }?.let { put("duration", it * 1000) }
            }
            val payload = buildJsonObject {
                put("listen_type", listeningType)
                put(
                    "payload",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("track_metadata", trackMetadata)
                                timestamp?.let { put("listened_at", it) }
                            }
                        )
                    }
                )
            }
            val resp = client.post("$BASE_URL/submit-listens") {
                header(HttpHeaders.Authorization, "Token $lbToken")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (!resp.status.isSuccess()) {
                val err = resp.bodyAsText().take(200)
                throw RuntimeException("ListenBrainz submit failed (${resp.status.value}): $err")
            }
        }
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit> = submitListen(
        artist = artist,
        track = track,
        album = album,
        duration = duration,
        timestamp = null,
        listeningType = "playing_now",
    )

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit> = submitListen(
        artist = artist,
        track = track,
        album = album,
        duration = duration,
        timestamp = timestamp,
        listeningType = "single",
    )
}