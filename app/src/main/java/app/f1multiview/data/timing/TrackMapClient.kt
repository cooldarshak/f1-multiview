package app.f1multiview.data.timing

import android.content.Context

import app.f1multiview.model.TrackCorner
import app.f1multiview.model.TrackDriverPosition
import app.f1multiview.model.TrackMapGeometry
import app.f1multiview.model.TrackPoint
import app.f1multiview.media.AppLogger
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
internal data class PositionMappingValidation(val valid: Boolean?, val matched: Int, val total: Int, val reason: String)

class TrackMapClient(private val context: Context? = null, httpClient: OkHttpClient? = null) {
    companion object {
        private const val MAX_NORMALIZED_FIT_RESIDUAL = 0.12
        internal const val MIN_CENTERLINE_POINTS = 20
internal fun geometryLookupYears(requestedYear: Int): List<Int> = listOf(requestedYear)

        /**
         * Confirm that several actual position-feed samples share the selected centreline coordinate frame.
         * Geometry point count or circuit identity alone does not prove that live Position X/Y can be overlaid.
         */
        internal fun validatePositionCoordinateMapping(
            centerline: List<TrackPoint>,
            positions: List<TrackDriverPosition>,
            marginRatio: Double = 0.15,
            minimumMatchRatio: Double = 0.5
        ): PositionMappingValidation {
            val line = centerline.filter { it.x.isFinite() && it.y.isFinite() }
            if (line.size < MIN_CENTERLINE_POINTS) {
                return PositionMappingValidation(null, 0, positions.size, "validated centreline unavailable")
            }
            val samples = positions.filter { it.x.isFinite() && it.y.isFinite() }
            if (samples.size < 3) {
                return PositionMappingValidation(null, 0, samples.size, "need at least three valid position samples")
            }
            val minX = line.minOf { it.x }
            val maxX = line.maxOf { it.x }
            val minY = line.minOf { it.y }
            val maxY = line.maxOf { it.y }
            val span = max(maxX - minX, maxY - minY)
            if (!span.isFinite() || span <= 1e-9) {
                return PositionMappingValidation(false, 0, samples.size, "degenerate centreline bounds")
            }
            val margin = span * marginRatio.coerceIn(0.0, 1.0)
            val maxCenterlineDistance = span * 0.02
            fun distanceToCenterline(point: TrackDriverPosition): Double {
                var best = Double.POSITIVE_INFINITY
                for (index in 0 until line.lastIndex) {
                    val a = line[index]
                    val b = line[index + 1]
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val lengthSquared = dx * dx + dy * dy
                    val t = if (lengthSquared <= 1e-12) 0.0 else
                        (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
                    val nearestX = a.x + t * dx
                    val nearestY = a.y + t * dy
                    best = min(best, hypot(point.x - nearestX, point.y - nearestY))
                }
                return best
            }
            val matched = samples.count { point ->
                val insideBounds = point.x >= minX - margin && point.x <= maxX + margin &&
                    point.y >= minY - margin && point.y <= maxY + margin
                insideBounds && distanceToCenterline(point) <= maxCenterlineDistance
            }
            val ratio = matched.toDouble() / samples.size.toDouble()
            val valid = ratio >= minimumMatchRatio.coerceIn(0.0, 1.0)
            return PositionMappingValidation(
                valid = valid,
                matched = matched,
                total = samples.size,
                reason = if (valid) "position samples are near the selected circuit centreline"
                    else "position samples do not fit the selected circuit coordinate frame"
            )
        }
        /** Parse a bundled circuit only when it contains a complete, finite native centreline. */
        internal fun parseBundledGeometry(catalogue: JSONObject, circuitKey: Int, requestedYear: Int): TrackMapGeometry? {
            val item = catalogue.optJSONObject("circuits")?.optJSONObject(circuitKey.toString()) ?: return null
            if (item.optInt("circuitKey", circuitKey) != circuitKey) return null
            val itemYear = item.optInt("year", 0)
            val client = TrackMapClient()
            val centerline = client.parseTrackCoordinates(item.optJSONArray("x"), item.optJSONArray("y"))
            if (centerline.size < MIN_CENTERLINE_POINTS) return null
            val rawCorners = item.optJSONArray("corners") ?: JSONArray()
            val corners = buildList {
                for (i in 0 until rawCorners.length()) {
                    val corner = rawCorners.optJSONObject(i) ?: continue
                    val p = corner.optJSONObject("trackPosition") ?: continue
                    val x = p.optDouble("x", Double.NaN)
                    val y = p.optDouble("y", Double.NaN)
                    if (!x.isFinite() || !y.isFinite()) continue
                    add(TrackCorner(corner.optInt("number", 0), corner.optString("letter"), x, y,
                        corner.optDouble("length", 0.0), corner.optDouble("angle", 0.0)))
                }
            }.sortedWith(compareBy<TrackCorner> { it.distance }.thenBy { it.number })
            if (corners.size < 3) return null
            return TrackMapGeometry(circuitKey, itemYear.takeIf { it > 0 } ?: requestedYear,
                item.optDouble("rotation", 0.0), corners, centerline)
        }

        /** Name-based fallback rejects ties; it never picks an arbitrary circuit. */
        internal fun matchBundledCircuit(catalogue: JSONObject, meetingName: String, circuitName: String): Pair<Int, String>? {
            val client = TrackMapClient()
            val circuits = catalogue.optJSONObject("circuits") ?: return null
            val wantedMeeting = client.normalize(meetingName)
            val wantedCircuit = client.normalize(circuitName)
            if (wantedMeeting.isBlank() && wantedCircuit.isBlank()) return null
            val candidates = mutableListOf<Triple<Int, String, Int>>()
            val keys = circuits.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = circuits.optJSONObject(key) ?: continue
                val circuitKey = item.optInt("circuitKey", key.toIntOrNull() ?: 0)
                if (circuitKey <= 0) continue
                val aliases = buildList {
                    add(item.optString("name"))
                    val values = item.optJSONArray("aliases") ?: JSONArray()
                    for (i in 0 until values.length()) {
                        val alias = values.optString(i)
                        if (alias.isNotBlank()) add(alias)
                    }
                }.map(client::normalize).filter(String::isNotBlank).distinct()
                val score = aliases.maxOfOrNull { alias ->
                    when {
                        wantedCircuit.isNotBlank() && alias == wantedCircuit -> 100
                        wantedMeeting.isNotBlank() && alias == wantedMeeting -> 95
                        wantedCircuit.isNotBlank() && (alias.contains(wantedCircuit) || wantedCircuit.contains(alias)) -> 80
                        wantedMeeting.isNotBlank() && (alias.contains(wantedMeeting) || wantedMeeting.contains(alias)) -> 70
                        else -> 0
                    }
                } ?: 0
                candidates += Triple(circuitKey, item.optString("name"), score)
            }
            val selectedIndex = client.uniqueBestIndex(candidates.map { it.third }, minimumScore = 70) ?: return null
            val selected = candidates[selectedIndex]
            return selected.first to selected.second
        }



    }
    private val http = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Resolve names from the validated bundled catalogue first. OpenF1 is intentionally not
     * used as a circuit lookup or timing dependency. When the local catalogue cannot identify a
     * circuit unambiguously, fail honestly rather than selecting a guessed key.
     */
    suspend fun loadByName(year: Int, meetingName: String, circuitName: String = ""): Result<TrackMapGeometry> {
        val catalogue = readBundledCatalogue()
        val bundledMatch = catalogue?.let { matchBundledCircuit(it, meetingName, circuitName) }
        if (bundledMatch != null) {
            val bundled = catalogue?.let { parseBundledGeometry(it, bundledMatch.first, year) }
            if (bundled != null) {
                AppLogger.i(
                    "TrackMap",
                    "TRACK_GEOMETRY_LOCAL_MATCH circuitKey=${bundled.circuitKey} year=${bundled.year} points=${bundled.centerline.size}"
                )
                AppLogger.i("TrackMap", "TRACK_GEOMETRY_IDENTITY_VALIDATED circuitKey=${bundled.circuitKey} source=bundled")
                return Result.success(bundled)
            }
            AppLogger.i("TrackMap", "TRACK_GEOMETRY_REMOTE_FALLBACK circuitKey=${bundledMatch.first} requestedYear=$year reason=bundled_geometry_not_eligible")
            return load(bundledMatch.first, year, circuitName.ifBlank { bundledMatch.second })
        }

        val reason = if (catalogue == null) "bundled_catalogue_unavailable" else "no_unambiguous_bundled_match"
        AppLogger.w(
            "TrackMap",
            "TRACK_GEOMETRY_UNAVAILABLE reason=$reason meetingNamePresent=${meetingName.isNotBlank()} circuitNamePresent=${circuitName.isNotBlank()}"
        )
        return Result.failure(java.io.IOException("No unambiguous bundled circuit match for meeting='$meetingName' circuit='$circuitName'"))
    }

    suspend fun load(circuitKey: Int, year: Int, circuitName: String = ""): Result<TrackMapGeometry> {
        val catalogue = readBundledCatalogue()
        val bundled = catalogue?.let { parseBundledGeometry(it, circuitKey, year) }
        if (bundled != null) {
            AppLogger.i("TrackMap", "TRACK_GEOMETRY_LOCAL_MATCH circuitKey=$circuitKey year=${bundled.year} points=${bundled.centerline.size}")
            AppLogger.i("TrackMap", "TRACK_GEOMETRY_IDENTITY_VALIDATED circuitKey=$circuitKey source=bundled")
            return Result.success(bundled)
        }

        // Only use the requested season remotely. A dense centreline from an older layout is
        // not proof that it matches this session's circuit configuration.
        var lastFailure: Throwable? = null
        for (geometryYear in geometryLookupYears(year)) {
            AppLogger.i("TrackMap", "TRACK_GEOMETRY_REMOTE_FALLBACK circuitKey=$circuitKey requestedYear=$year geometryYear=$geometryYear")
            val remote = loadSingleYear(circuitKey, geometryYear, circuitName)
            val geometry = remote.getOrNull()
            if (geometry != null &&
                geometry.circuitKey == circuitKey &&
                geometry.year == geometryYear &&
                geometry.centerline.size >= MIN_CENTERLINE_POINTS
            ) {
                AppLogger.i("TrackMap", "TRACK_GEOMETRY_IDENTITY_VALIDATED circuitKey=$circuitKey year=$geometryYear source=remote")
                return Result.success(geometry)
            }
            lastFailure = remote.exceptionOrNull()
                ?: java.io.IOException("Circuit geometry identity/year validation failed for key=$circuitKey requestedYear=$geometryYear")
        }

        val failure = lastFailure
            ?: java.io.IOException("No validated circuit geometry found for key=$circuitKey year=$year")
        AppLogger.w("TrackMap", "TRACK_GEOMETRY_UNAVAILABLE circuitKey=$circuitKey year=$year reason=${failure.javaClass.simpleName}")
        return Result.failure(failure)
    }

    private fun readBundledCatalogue(): JSONObject? = runCatching {
        val appContext = context ?: return@runCatching null
        appContext.assets.open("circuit_geometry_2026.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private suspend fun loadSingleYear(
        circuitKey: Int,
        geometryYear: Int,
        circuitName: String
    ): Result<TrackMapGeometry> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.multiviewer.app/api/v1/circuits/$circuitKey/$geometryYear"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "F1MultiView/1.0 Android").build()
                val root = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Circuit geometry HTTP " + response.code)
                    JSONObject(response.body?.string().orEmpty())
                }
                val responseKey = root.optInt("circuitKey", circuitKey)
                val responseYear = root.optInt("year", geometryYear)
                if (responseKey != circuitKey) {
                    error("Circuit geometry identity mismatch requestedKey=$circuitKey responseKey=$responseKey")
                }
                if (responseYear != geometryYear) {
                    error("Circuit geometry year mismatch requestedYear=$geometryYear responseYear=$responseYear")
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
                // MultiViewer's circuit response includes the sampled circuit trace as parallel
                // x/y arrays in the same telemetry coordinate system as corner.trackPosition.
                // Prefer that native trace so driver X/Y coordinates remain in the same
                // telemetry frame. Keep GeoJSON only as a fallback for older payloads that
                // genuinely omit the trace.
                val nativeCenterline = parseTrackCoordinates(root.optJSONArray("x"), root.optJSONArray("y"))
                val centerline = when {
                    nativeCenterline.size >= MIN_CENTERLINE_POINTS -> nativeCenterline
                    circuitName.isNotBlank() && orderedCorners.size >= 3 ->
                        loadAlignedCenterline(circuitName, orderedCorners)
                    else -> emptyList()
                }
                if (centerline.size < MIN_CENTERLINE_POINTS && orderedCorners.isEmpty()) {
                    error("Circuit response has neither a valid centreline nor a corner catalogue")
                }
                TrackMapGeometry(
                    circuitKey = circuitKey,
                    year = root.optInt("year", geometryYear),
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
        // A label made only of session/track suffixes can normalize to an empty key.
        // Empty-string containment matches every feature, which could silently select
        // the first circuit in the catalogue and place drivers on the wrong map.
        if (wanted.isBlank()) return emptyList()
        val scoredFeatures = mutableListOf<Pair<JSONObject, Int>>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val name = normalize(props.optString("Name"))
            val location = normalize(props.optString("Location"))
            val score = scoreGeoJsonCircuit(name, location, wanted)
            scoredFeatures += feature to score
        }
        // Circuit labels can be shared by alternate layouts or historic venues. Never
        // silently pick whichever equal-scoring feature happens to appear first.
        val selectedIndex = uniqueBestIndex(scoredFeatures.map { it.second }, minimumScore = 80) ?: return emptyList()
        val feature = scoredFeatures[selectedIndex].first
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
        val geographicLength = lengths.lastOrNull() ?: 0.0
        // GeoJSON datasets disagree on whether a circuit's optional "length" is metres,
        // kilometres, or absent. Never mix that metadata unit with F1 corner distances.
        // Normalize the authoritative ordered corner distances onto the complete geographic
        // centreline instead; this preserves relative corner placement independent of units.
        val maxCornerDistance = corners.maxOfOrNull { it.distance } ?: 0.0
        if (geographicLength <= 0.0 || maxCornerDistance <= 0.0) return emptyList()

        val matches = corners.mapNotNull { corner ->
            if (corner.distance <= 0.0 || corner.distance > maxCornerDistance * 1.001) return@mapNotNull null
            val scaledDistance = (corner.distance / maxCornerDistance * geographicLength)
                .coerceIn(0.0, geographicLength)
            val geoPoint = sampleAtDistance(geoPoints, lengths, scaledDistance)
            geoPoint?.let { Triple(it.lon, it.lat, corner.x to corner.y) }
        }
        if (matches.size < 3) return emptyList()
        val xSamples = matches.map { Triple(it.first, it.second, it.third.first) }
        val ySamples = matches.map { Triple(it.first, it.second, it.third.second) }
        val xFit = fitAffine(xSamples) ?: return emptyList()
        val yFit = fitAffine(ySamples) ?: return emptyList()
        // The least-squares fit always returns coefficients for a solvable matrix, even when
        // the selected GeoJSON feature or distance alignment is wrong. Validate the fit against
        // every published corner before exposing the centreline to the UI.
        val residualRatio = normalizedFitResidual(matches, xFit, yFit) ?: return emptyList()
        if (residualRatio > MAX_NORMALIZED_FIT_RESIDUAL) {
            error(
                "Circuit centreline alignment rejected: corner residual " +
                    "%.3f exceeds %.3f".format(java.util.Locale.US, residualRatio, MAX_NORMALIZED_FIT_RESIDUAL)
            )
        }
        val transformed = geoPoints.map { p ->
            TrackPoint(xFit[0] * p.lon + xFit[1] * p.lat + xFit[2], yFit[0] * p.lon + yFit[1] * p.lat + yFit[2])
        }
        if (transformed.any { !it.x.isFinite() || !it.y.isFinite() }) return emptyList()
        return transformed
    }


    /**
     * Parses MultiViewer's native circuit trace. Both arrays must be complete, aligned,
     * finite, and sufficiently dense; rejecting the whole trace is safer than drawing
     * a truncated or fabricated circuit.
     */
    internal fun parseTrackCoordinates(x: JSONArray?, y: JSONArray?): List<TrackPoint> {
        if (x == null || y == null || x.length() != y.length() || x.length() < MIN_CENTERLINE_POINTS) {
            return emptyList()
        }
        val points = ArrayList<TrackPoint>(x.length())
        for (i in 0 until x.length()) {
            val px = x.optDouble(i, Double.NaN)
            val py = y.optDouble(i, Double.NaN)
            if (!px.isFinite() || !py.isFinite()) return emptyList()
            points += TrackPoint(px, py)
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        if (maxX - minX <= 1e-9 || maxY - minY <= 1e-9) return emptyList()
        return points
    }

    private data class GeoPoint(val lon: Double, val lat: Double)

    /** Scores GeoJSON features against the normalized circuit label without guessing on vague location matches. */
    internal fun scoreGeoJsonCircuit(name: String, location: String, wanted: String): Int {
        if (wanted.isBlank()) return 0
        return when {
            name.isNotBlank() && name == wanted -> 100
            name.isNotBlank() && (name.contains(wanted) || wanted.contains(name)) -> 80
            location.isNotBlank() && location == wanted -> 90
            location.isNotBlank() && (wanted.contains(location) || location.contains(wanted)) -> 30
            else -> 0
        }
    }

    /** Returns the index of the sole highest-scoring candidate, or null for no match/ties. */
    internal fun uniqueBestIndex(scores: List<Int>, minimumScore: Int): Int? {
        if (scores.isEmpty()) return null
        val best = scores.maxOrNull() ?: return null
        if (best < minimumScore || scores.count { it == best } != 1) return null
        return scores.indexOf(best)
    }

    internal fun normalize(value: String): String {
        // A selected replay name may contain ": Qualifying 1" or "Race" after the meeting name.
        // Strip session labels first; then normalize "Singapore GP" and "Singapore Grand Prix"
        // to the same key so a missing circuit key can still resolve to the correct circuit.
        return value.lowercase()
            .substringBefore(":")
            .substringBefore(" — ")
            .substringBefore(" - ")
            .filter { it.isLetterOrDigit() }
            .replace("grandprix", "")
            .replace("qualifying", "")
            .replace("practice", "")
            .replace("sprint", "")
            .replace("race", "")
            .replace(Regex("fp[123]"), "")
            .replace(Regex("[0-9]+"), "")
            .replace("gp", "")
            .replace("international", "")
            .replace("streetcircuit", "")
            .replace("circuit", "")
            .replace("autodromo", "")
            .replace("raceway", "")
    }

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

    /**
     * RMS corner residual divided by the target-circuit bounding-box diagonal.
     * A dimensionless metric lets us reject visibly bad alignments across circuits of different
     * sizes. Null means the targets have no useful spatial extent or coefficients are invalid.
     */
    internal fun normalizedFitResidual(
        matches: List<Triple<Double, Double, Pair<Double, Double>>>,
        xFit: DoubleArray,
        yFit: DoubleArray
    ): Double? {
        if (matches.size < 3 || xFit.size != 3 || yFit.size != 3) return null
        if (xFit.any { !it.isFinite() } || yFit.any { !it.isFinite() }) return null
        val targetX = matches.map { it.third.first }
        val targetY = matches.map { it.third.second }
        val diagonal = hypot(targetX.maxOrNull()!! - targetX.minOrNull()!!, targetY.maxOrNull()!! - targetY.minOrNull()!!)
        if (!diagonal.isFinite() || diagonal <= 1e-9) return null
        val squaredError = matches.sumOf { (lon, lat, target) ->
            val dx = xFit[0] * lon + xFit[1] * lat + xFit[2] - target.first
            val dy = yFit[0] * lon + yFit[1] * lat + yFit[2] - target.second
            dx * dx + dy * dy
        }
        return sqrt(squaredError / matches.size) / diagonal
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
