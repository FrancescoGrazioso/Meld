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
    fun decode(value: String?): MutableMap<String, QobuzMatchOverride> {
        if (value.isNullOrBlank()) return mutableMapOf()
        return runCatching {
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
        }.getOrDefault(mutableMapOf())
    }

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
