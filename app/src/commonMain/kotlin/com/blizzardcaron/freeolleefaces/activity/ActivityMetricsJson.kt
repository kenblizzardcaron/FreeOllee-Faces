package com.blizzardcaron.freeolleefaces.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON codec for the activity metric config. Decoding NEVER throws: a null/blank/corrupt
 * store yields [ActivityMetricsConfig.DEFAULT]. The recording list is defaults-merged against the
 * canonical set ([ActivityMetricsConfig.RECORDING_METRICS]) so the result always holds exactly the
 * canonical metrics — stored ones keep order+enabled, missing ones are appended enabled, unknown
 * names are dropped. A legacy stored `"glance"` key (from before the glance mode was removed) is
 * simply never read — that IS the migration.
 */
object ActivityMetricsJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: ActivityMetricsConfig): String = buildJsonObject {
        put("recording", encodeList(config.recording))
    }.toString()

    fun decode(raw: String?): ActivityMetricsConfig {
        if (raw.isNullOrBlank()) return ActivityMetricsConfig.DEFAULT
        return runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("Not a JSON object")
            ActivityMetricsConfig(
                recording = merge(parseList(obj, "recording"), ActivityMetricsConfig.RECORDING_METRICS),
            )
        }.getOrDefault(ActivityMetricsConfig.DEFAULT)
    }

    private fun encodeList(items: List<ActivityMetricItem>) = buildJsonArray {
        for (item in items) add(
            buildJsonObject {
                put("m", JsonPrimitive(item.metric.name))
                put("e", JsonPrimitive(item.enabled))
            }
        )
    }

    private fun parseList(obj: JsonObject, key: String): List<ActivityMetricItem> =
        obj[key]?.jsonArray?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["m"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val metric = ActivityMetric.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            ActivityMetricItem(metric, o["e"]?.jsonPrimitive?.booleanOrNull ?: true)
        } ?: emptyList()

    /** Keep stored-and-canonical items in stored order, then append any canonical metric the store
     *  omitted (enabled). Dedupe by metric (first occurrence wins). */
    private fun merge(stored: List<ActivityMetricItem>, canonical: List<ActivityMetric>): List<ActivityMetricItem> {
        val keep = stored.filter { it.metric in canonical }.distinctBy { it.metric }
        val present = keep.map { it.metric }.toSet()
        val appended = canonical.filter { it !in present }.map { ActivityMetricItem(it) }
        return keep + appended
    }
}
