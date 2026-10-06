package app.f1multiview.data.timing

import app.f1multiview.model.TrackCorner
import app.f1multiview.model.TrackMapGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Loads static circuit geometry for the custom Android tracker.
 *
 * This is only circuit furniture (corners/rotation). Live car positions still come from
 * F1 Live Timing Position.z; the official F1 driver-tracker video is never used.
 */
class TrackMapClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun load(circuitKey: Int, year: Int): Result<TrackMapGeometry> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.multiviewer.app/api/v1/circuits/" + circuitKey + "/" + year
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "F1MultiView/1.0 Android")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Circuit geometry HTTP " + response.code)
                }
                val root = JSONObject(response.body?.string().orEmpty())
                val corners = mutableListOf<TrackCorner>()
                val items = root.optJSONArray("corners") ?: org.json.JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val p = item.optJSONObject("trackPosition") ?: continue
                    val x = p.optDouble("x", Double.NaN)
                    val y = p.optDouble("y", Double.NaN)
                    if (!x.isFinite() || !y.isFinite()) continue
                    corners += TrackCorner(
                        number = item.optInt("number", 0),
                        letter = item.optString("letter"),
                        x = x,
                        y = y,
                        distance = item.optDouble("length", 0.0),
                        angle = item.optDouble("angle", 0.0)
                    )
                }
                TrackMapGeometry(
                    circuitKey = circuitKey,
                    year = year,
                    rotation = root.optDouble("rotation", 0.0),
                    corners = corners.sortedWith(compareBy<TrackCorner> { it.distance }.thenBy { it.number })
                )
            }
        }
    }
}
