package app.f1multiview.data.f1tv

import org.json.JSONArray
import org.json.JSONObject

data class CatalogSessionInfo(
    val stage: String,
    val broadcastVariant: String,
    val series: String,
    val startTime: Long
)

internal object F1CatalogParser {
    fun flatten(root: JSONArray): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    out += value
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        return out
    }

    fun pageId(node: JSONObject): Int? {
        val actions = node.optJSONArray("actions") ?: return null
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            if (!action.optString("key").equals("onClick", true)) continue
            val match = Regex("/PAGE/(\\d+)/", RegexOption.IGNORE_CASE).find(action.optString("uri"))
            if (match != null) return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    fun title(node: JSONObject, meta: JSONObject = node.optJSONObject("metadata") ?: JSONObject()): String {
        val emf = meta.optJSONObject("emfAttributes") ?: JSONObject()
        return listOf(node.optString("title"), meta.optString("title"), meta.optString("plainText"),
            emf.optString("Global_Title"), emf.optString("Meeting_Name"))
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    fun series(node: JSONObject, meta: JSONObject = node.optJSONObject("metadata") ?: JSONObject()): String {
        val emf = meta.optJSONObject("emfAttributes") ?: JSONObject()
        val raw = listOf(emf.optString("Series"), emf.optString("Series_Name"),
            meta.optString("series"), meta.optString("seriesName"), node.optString("series"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        return normalizeSeries(raw, title(node, meta))
    }

    fun normalizeSeries(raw: String, fallbackText: String = ""): String {
        val value = raw.trim()
        if (value.isNotBlank()) return when {
            value.equals("FORMULA 1", true) || value.equals("F1", true) -> "F1"
            value.equals("FORMULA 2", true) || value.equals("F2", true) -> "F2"
            value.equals("FORMULA 3", true) || value.equals("F3", true) -> "F3"
            value.contains("F1 ACADEMY", true) -> "F1 Academy"
            value.contains("PORSCHE", true) -> "Porsche Supercup"
            else -> value
        }
        val text = fallbackText.lowercase()
        return when {
            text.contains("f1 academy") -> "F1 Academy"
            text.contains("formula 2") || text.contains(" f2 ") -> "F2"
            text.contains("formula 3") || text.contains(" f3 ") -> "F3"
            text.contains("porsche supercup") -> "Porsche Supercup"
            else -> "F1"
        }
    }

    fun eventDate(node: JSONObject, meta: JSONObject): Long {
        val props = node.optJSONArray("properties")?.optJSONObject(0) ?: JSONObject()
        return listOf(props.optString("meeting_Start_Date"), props.optString("meetingStartDate"),
            props.optString("startDate"), meta.optString("meeting_Start_Date"),
            meta.optString("meetingStartDate"), meta.optString("startDate"))
            .firstNotNullOfOrNull { parseTime(it) } ?: 0L
    }

    fun sessionInfo(node: JSONObject): CatalogSessionInfo? {
        val meta = node.optJSONObject("metadata") ?: return null
        val emf = meta.optJSONObject("emfAttributes") ?: JSONObject()
        val text = title(node, meta)
        val combined = text.lowercase() + " " + emf.optString("VideoType").lowercase() + " " + meta.optString("contentSubtype").lowercase()

        val stage = when {
            combined.contains("pre-show") || combined.contains("preshow") || combined.contains("pre race show") || combined.contains("preview") -> "pre-show"
            combined.contains("post-show") || combined.contains("postrace") || combined.contains("post race show") || combined.contains("post race") -> "post-show"
            combined.contains("race in 30") -> "race-in-30"
            combined.contains("highlights") || combined.contains("best of") -> "highlights"
            combined.contains("press conference") || combined.contains("post-race press") || combined.contains("post race press") -> "press-conference"
            combined.contains("conference") -> "conference"
            combined.contains("f1 kids") || combined.contains("f1kids") -> "f1-kids"
            combined.contains("sprint qualifying") || combined.contains("sprint shootout") -> "sprint-qualifying"
            combined.contains("sprint") -> "sprint"
            combined.contains("qualifying") || combined.contains("quali") -> "qualifying"
            combined.contains("practice 1") || combined.contains("fp1") -> "practice-1"
            combined.contains("practice 2") || combined.contains("fp2") -> "practice-2"
            combined.contains("practice 3") || combined.contains("fp3") -> "practice-3"
            combined.contains("practice") || combined.contains("free practice") -> "practice"
            combined.contains("race") || combined.contains("grand prix") -> "race"
            else -> return null
        }

        val variant = when {
            stage == "pre-show" -> "pre-show"
            stage == "post-show" -> "post-show"
            stage == "f1-kids" -> "f1-kids"
            stage == "highlights" || stage == "race-in-30" || stage == "press-conference" || stage == "conference" -> "editorial"
            combined.contains("full race") || combined.contains("full session") -> "main"
            else -> "main"
        }

        val props = node.optJSONArray("properties")?.optJSONObject(0) ?: JSONObject()
        val time = listOf(props.optString("session_Start_Date"), props.optString("sessionStartDate"),
            props.optString("startDate"), meta.optString("session_Start_Date"),
            meta.optString("sessionStartDate"), meta.optString("startDate"))
            .firstNotNullOfOrNull { parseTime(it) } ?: 0L

        return CatalogSessionInfo(stage, variant, series(node, meta), time)
    }

    private fun parseTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        value.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000L else it }
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }
}
