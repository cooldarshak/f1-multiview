package app.f1multiview.data.timing

import app.f1multiview.model.TrackCorner
import app.f1multiview.model.TrackMapGeometry
import app.f1multiview.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Circuit geometry from the MultiViewer corner catalogue plus the public f1-circuits
 * GeoJSON centreline. The geographic centreline is aligned to the F1 telemetry coordinate
 * system using the published corner distances/positions; corner points are annotations,
 * never used as a substitute for the track path.
 */
class TrackMapClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Resolve missing F1 SessionInfo circuit keys by matching the meeting/circuit name against
     * OpenF1's public meetings catalogue. OpenF1 returns MultiViewer's circuit_key and circuit_info_url,
     * so the resulting key is suitable for the existing authoritative MultiViewer geometry endpoint.
     */
    suspend fun loadByName(year: Int, meetingName: String, circuitName: String = ""): Result<TrackMapGeometry> {
        val lookup = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.openf1.org/v1/meetings?year=" + year)
                    .header("User-Agent", "F1MultiView/1.0 Android")
                    .build()
                val meetings = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Circuit lookup HTTP " + response.code)
                    JSONArray(response.body?.string().orEmpty())
                }
                val wantedMeeting = normalize(meetingName)
                val wantedCircuit = normalize(circuitName)
                if (wantedMeeting.isBlank() && wantedCircuit.isBlank()) error("Missing meeting and circuit names")
                var selected: JSONObject? = null
                var bestScore = 0
                for (i in 0 until meetings.length()) {
                    val item = meetings.optJSONObject(i) ?: continue
                    val name = normalize(item.optString("meeting_name"))
                    val official = normalize(item.optString("meeting_official_name"))
                    val shortName = normalize(item.optString("circuit_short_name"))
                    val location = normalize(item.optString("location"))
                    val score = when {
                        wantedMeeting.isNotBlank() && name == wantedMeeting -> 100
                        wantedMeeting.isNotBlank() && official.contains(wantedMeeting) -> 95
                        wantedMeeting.isNotBlank() && name.contains(wantedMeeting) -> 90
                        wantedMeeting.isNotBlank() && wantedMeeting.contains(name) && name.isNotBlank() -> 80
                        wantedCircuit.isNotBlank() && shortName == wantedCircuit -> 100
                        wantedCircuit.isNotBlank() && shortName.contains(wantedCircuit) -> 90
                        wantedCircuit.isNotBlank() && location.contains(wantedCircuit) -> 80
                        wantedCircuit.isNotBlank() && wantedCircuit.contains(location) && location.isNotBlank() -> 70
                        else -> 0
                    }
                    if (score > bestScore) {
                        bestScore = score
                        selected = item
                    }
                }
                val item = selected?.takeIf { bestScore >= 70 }
                    ?: error("No circuit match for meeting metadata")
                item.optInt("circuit_key", 0).takeIf { it > 0 }
                    ?: error("Matched meeting has no circuit key")
            }
        }
        return lookup.fold(
            onSuccess = { key -> load(key, year, circuitName.ifBlank { meetingName }) },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun load(circuitKey: Int, year: Int, circuitName: String = ""): Result<TrackMapGeometry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.multiviewer.app/api/v1/circuits/$circuitKey/$year"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "F1MultiView/1.0 Android").build()
                val root = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Circuit geometry HTTP " + response.code)
                    JSONObject(response.body?.string().orEmpty())
                }
                val corners = mutableListOf<TrackCorner>()
                val items = root.optJSONArray("corners") ?: JSONArray()
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
                val orderedCorners = corners.sortedWith(compareBy<TrackCorner> { it.distance }.thenBy { it.number })
                val centerline = if (circuitName.isNotBlank() && orderedCorners.size >= 3) {
                    loadAlignedCenterline(circuitName, orderedCorners)
                } else emptyList()
                if (centerline.size < 20) {
                    // Do not draw a misleading corner-to-corner polygon as a circuit.
                    // A missing/ambiguous source is explicitly represented as unavailable.
                    if (orderedCorners.isEmpty()) error("Circuit corner catalogue was empty")
                }
                TrackMapGeometry(
                    circuitKey = circuitKey,
                    year = year,
                    rotation = root.optDouble("rotation", 0.0),
                    corners = orderedCorners,
                    centerline = centerline
                )
            }
        }

    private fun loadAlignedCenterline(circuitName: String, corners: List<TrackCorner>): List<TrackPoint> {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/bacinger/f1-circuits/master/f1-circuits.geojson")
            .header("User-Agent", "F1MultiView/1.0 Android")
            .build()
        val geo = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Circuit centreline HTTP " + response.code)
            JSONObject(response.body?.string().orEmpty())
        }
        val features = geo.optJSONArray("features") ?: return emptyList()
        val wanted = normalize(circuitName)
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val name = normalize(props.optString("Name"))
            val location = normalize(props.optString("Location"))
            val score = when {
                name == wanted -> 100
                name.contains(wanted) || wanted.contains(name) -> 80
                location.isNotBlank() && (wanted.contains(location) || location.contains(wanted)) -> 30
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = feature }
        }
        val feature = best?.takeIf { bestScore >= 80 } ?: return emptyList()
        val properties = feature.optJSONObject("properties") ?: return emptyList()
        val geometry = feature.optJSONObject("geometry") ?: return emptyList()
        if (!geometry.optString("type").equals("LineString", true)) return emptyList()
        val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
        val geoPoints = buildList {
            for (i in 0 until coordinates.length()) {
                val p = coordinates.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                val lon = p.optDouble(0, Double.NaN)
                val lat = p.optDouble(1, Double.NaN)
                if (lon.isFinite() && lat.isFinite()) add(GeoPoint(lon, lat))
            }
        }
        if (geoPoints.size < 20) return emptyList()
        val lengths = cumulativeDistances(geoPoints)
        val trackLength = properties.optDouble("length", lengths.lastOrNull() ?: 0.0)
        if (trackLength <= 0.0) return emptyList()

        val matches = corners.mapNotNull { corner ->
            if (corner.distance <= 0.0 || corner.distance > trackLength * 1.08) return@mapNotNull null
            val scaledDistance = (corner.distance / trackLength * lengths.last()).coerceIn(0.0, lengths.last())
            val geoPoint = sampleAtDistance(geoPoints, lengths, scaledDistance)
            geoPoint?.let { Triple(it.lon, it.lat, corner.x to corner.y) }
        }
        if (matches.size < 3) return emptyList()
        val xFit = fitAffine(matches.map { Triple(it.first, it.second, it.third.first) }) ?: return emptyList()
        val yFit = fitAffine(matches.map { Triple(it.first, it.second, it.third.second) }) ?: return emptyList()
        val transformed = geoPoints.map { p ->
            TrackPoint(xFit[0] * p.lon + xFit[1] * p.lat + xFit[2], yFit[0] * p.lon + yFit[1] * p.lat + yFit[2])
        }
        if (transformed.any { !it.x.isFinite() || !it.y.isFinite() }) return emptyList()
        return transformed
    }

    private data class GeoPoint(val lon: Double, val lat: Double)

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
            .replace("grandprix", "")
            .replace("international", "")
            .replace("streetcircuit", "")
            .replace("circuit", "")
            .replace("autodromo", "")
            .replace("raceway", "")

    private fun cumulativeDistances(points: List<GeoPoint>): List<Double> {
        val out = ArrayList<Double>(points.size)
        var total = 0.0
        out += 0.0
        for (i in 1 until points.size) {
            total += haversine(points[i - 1], points[i])
            out += total
        }
        return out
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun sampleAtDistance(points: List<GeoPoint>, cumulative: List<Double>, distance: Double): GeoPoint? {
        if (points.isEmpty() || points.size != cumulative.size) return null
        val index = cumulative.binarySearch(distance).let { if (it >= 0) it else (-it - 1) }
            .coerceIn(1, points.lastIndex)
        val start = cumulative[index - 1]
        val end = cumulative[index]
        val fraction = if (end <= start) 0.0 else ((distance - start) / (end - start)).coerceIn(0.0, 1.0)
        return GeoPoint(
            points[index - 1].lon + (points[index].lon - points[index - 1].lon) * fraction,
            points[index - 1].lat + (points[index].lat - points[index - 1].lat) * fraction
        )
    }

    /** Least-squares affine transform: target = a*lon + b*lat + c. */
    private fun fitAffine(samples: List<Triple<Double, Double, Double>>): DoubleArray? {
        if (samples.size < 3) return null
        val matrix = Array(3) { DoubleArray(4) }
        samples.forEach { (lon, lat, target) ->
            val v = doubleArrayOf(lon, lat, 1.0)
            for (r in 0..2) {
                for (c in 0..2) matrix[r][c] += v[r] * v[c]
                matrix[r][3] += v[r] * target
            }
        }
        for (pivot in 0..2) {
            var best = pivot
            for (r in pivot + 1..2) if (abs(matrix[r][pivot]) > abs(matrix[best][pivot])) best = r
            if (abs(matrix[best][pivot]) < 1e-12) return null
            val swap = matrix[pivot]; matrix[pivot] = matrix[best]; matrix[best] = swap
            val divisor = matrix[pivot][pivot]
            for (c in pivot..3) matrix[pivot][c] /= divisor
            for (r in 0..2) if (r != pivot) {
                val factor = matrix[r][pivot]
                for (c in pivot..3) matrix[r][c] -= factor * matrix[pivot][c]
            }
        }
        return doubleArrayOf(matrix[0][3], matrix[1][3], matrix[2][3])
    }
}
