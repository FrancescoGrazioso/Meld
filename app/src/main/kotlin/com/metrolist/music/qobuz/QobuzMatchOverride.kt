/**
 * Manual Qobuz match override — lets the user pin a specific Qobuz track ID
 * to a given mediaId when the auto-matcher picks the wrong candidate. The
 * override travels through the playback pipeline as a [QobuzAudioProvider]
 * direct-ID short-circuit (see toQobuzTrackIdOrNull), so playback skips the
 * fuzzy search entirely.
 *
 * Persisted in DataStore as a JSON map { mediaId: { trackId, label, hires, bitDepth, samplingRateKhz } }.
 */
package com.metrolist.music.qobuz

import org.json.JSONObject

data class QobuzMatchOverride(
    val qobuzTrackId: String,
    val label: String,
    val hires: Boolean = false,
    val bitDepth: Int? = null,
    val samplingRateKhz: Double? = null,
) {
    fun providerMediaId(): String = "qobuz:track:$qobuzTrackId"
}

object QobuzMatchOverrides {
    // Memoize the last decode keyed on the raw JSON string. decode() runs on the
    // ExoPlayer loader thread for every stream resolve, so re-parsing the whole
    // overrides JSON each time is wasteful when it rarely changes. Keyed on the
    // exact input, so no manual invalidation is needed — a changed overrides
    // string simply misses and re-parses.
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedMap: Map<String, QobuzMatchOverride> = emptyMap()

    fun decode(value: String?): MutableMap<String, QobuzMatchOverride> {
        if (value.isNullOrBlank()) return mutableMapOf()
        val snapshot = if (value == cachedRaw) {
            cachedMap
        } else {
            val parsed = parse(value)
            cachedRaw = value
            cachedMap = parsed
            parsed
        }
        // Defensive copy so callers (e.g. the set/encode path) can mutate freely.
        return LinkedHashMap(snapshot)
    }

    private fun parse(value: String): Map<String, QobuzMatchOverride> =
        runCatching {
            val root = JSONObject(value)
            val out = mutableMapOf<String, QobuzMatchOverride>()
            root.keys().forEach { mediaId ->
                val obj = root.optJSONObject(mediaId) ?: return@forEach
                val trackId = obj.optString("trackId").takeIf { it.isNotBlank() } ?: return@forEach
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: trackId
                val hires = obj.optBoolean("hires", false)
                val bitDepth = if (obj.has("bitDepth") && !obj.isNull("bitDepth")) {
                    obj.optInt("bitDepth").takeIf { it > 0 }
                } else null
                val sampling = if (obj.has("samplingRateKhz") && !obj.isNull("samplingRateKhz")) {
                    obj.optDouble("samplingRateKhz").takeIf { it > 0.0 }
                } else null
                out[mediaId] = QobuzMatchOverride(
                    qobuzTrackId = trackId,
                    label = label,
                    hires = hires,
                    bitDepth = bitDepth,
                    samplingRateKhz = sampling,
                )
            }
            out
        }.getOrDefault(emptyMap())

    fun encode(overrides: Map<String, QobuzMatchOverride>): String {
        val root = JSONObject()
        overrides.forEach { (mediaId, override) ->
            val obj = JSONObject()
                .put("trackId", override.qobuzTrackId)
                .put("label", override.label)
                .put("hires", override.hires)
            if (override.bitDepth != null) obj.put("bitDepth", override.bitDepth)
            if (override.samplingRateKhz != null) obj.put("samplingRateKhz", override.samplingRateKhz)
            root.put(mediaId, obj)
        }
        return root.toString()
    }
}
