package app.f1multiview.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import java.io.File
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class TmeCmafSample(
    val timeUs: Long,
    val durationUs: Long,
    val keyFrame: Boolean,
    val payload: ByteArray
)

data class TmeCmafSegment(
    val sequence: Long,
    val durationUs: Long,
    val samples: List<TmeCmafSample>,
    val decoderConfig: ByteArray,
    val width: Int,
    val height: Int
)

internal data class HlsSegment(
    val sequence: Long,
    val uri: String,
    val durationUs: Long,
    val startTimeUs: Long
)
internal data class HlsPlaylist(
    val initUri: String?,
    val segments: List<HlsSegment>,
    val encryptionMethod: String? = null
)

class TmeCmafFeedReader(
    private val context: Context,
    private val requestHeaders: Map<String, String> = emptyMap()
) {
    private val initCache = mutableMapOf<String, ByteArray>()

    internal fun load(url: String): HlsPlaylist {
        val text = get(url).toString(Charsets.UTF_8).trimStart('\uFEFF')
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        require(lines.firstOrNull() == "#EXTM3U") {
            "Unsupported manifest: expected an HLS playlist (DASH/MPD is not supported by this CMAF reader)"
        }
        val sessionEncryptionMethod = lines.firstOrNull { it.startsWith("#EXT-X-SESSION-KEY:") }
            ?.let(::parseEncryptionMethod)

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            var bestBandwidth = -1L
            var best: String? = null
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val bandwidth = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""")
                    .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val child = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                if (bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth
                    best = resolve(url, child)
                }
            }
            require(!best.isNullOrBlank()) { "HLS master has no media variant" }
            val child = load(best!!)
            return child.copy(encryptionMethod = child.encryptionMethod ?: sessionEncryptionMethod)
        }

        var mediaSequence = 0L
        var nextSequence = 0L
        var initUri: String? = null
        var encryptionMethod: String? = sessionEncryptionMethod
        var durationUs: Long? = null
        var playlistTimeUs = 0L
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').toLong()
                line.startsWith("#EXT-X-KEY:") ->
                    encryptionMethod = parseEncryptionMethod(line)
                line.startsWith("#EXT-X-MAP:") ->
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let {
                        initUri = resolve(url, it)
                    }
                line.startsWith("#EXTINF:") ->
                    durationUs = (line.substringAfter(':').substringBefore(',').toDouble() * 1_000_000.0).toLong()
                !line.startsWith("#") && durationUs != null -> {
                    val duration = durationUs!!
                    segments += HlsSegment(
                        mediaSequence + nextSequence,
                        resolve(url, line),
                        duration,
                        playlistTimeUs
                    )
                    playlistTimeUs += duration
                    nextSequence++
                    durationUs = null
                }
            }
        }
        return HlsPlaylist(initUri, segments, encryptionMethod)
    }

    private fun parseEncryptionMethod(line: String): String? {
        val method = Regex("""(?:^|[, :])METHOD=([^,]+)""")
            .find(line)?.groupValues?.get(1)?.trim()
            ?: return "UNKNOWN"
        return method.takeUnless { it.equals("NONE", ignoreCase = true) }
    }

    /**
     * Inspects one authorized feed without copying or decrypting compressed sample data.
     * It reads manifest/init/fragment metadata only. Protected feeds are reported and
     * rejected before the clear-content extraction path can touch sample payloads.
     */
    internal fun inspectFirstSegment(url: String, feedId: String): TmeCmafFeedEvidence {
        return runCatching {
            val playlist = load(url)
            val segment = playlist.segments.firstOrNull()
                ?: error("HLS playlist has no media segment")
            if (!playlist.encryptionMethod.isNullOrBlank()) {
                return TmeCmafFeedEvidence(
                    feedId = feedId,
                    mimeType = null,
                    width = null,
                    height = null,
                    codecConfigFingerprint = null,
                    encrypted = true,
                    sampleCount = 0,
                    firstSampleTimeUs = null,
                    firstSampleIsSync = null,
                    encryptionMethod = playlist.encryptionMethod
                )
            }

            val init = playlist.initUri?.let { initCache.getOrPut(it) { get(it) } } ?: ByteArray(0)
            val media = get(segment.uri)
            val file = File.createTempFile("f1tme-probe-", ".mp4", context.cacheDir)
            try {
                file.outputStream().use { out ->
                    if (init.isNotEmpty()) out.write(init)
                    out.write(media)
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    } ?: error("CMAF fragment has no video track")
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    val csd = format.getByteBuffer("csd-0")
                    val configBytes = csd?.duplicate()?.let { buffer ->
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    val fingerprint = configBytes?.let {
                        MessageDigest.getInstance("SHA-256").digest(it)
                            .joinToString("") { byte -> "%02x".format(byte) }
                    }
                    val width = format.getInteger(MediaFormat.KEY_WIDTH, -1).takeIf { it > 0 }
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT, -1).takeIf { it > 0 }
                    extractor.selectTrack(track)
                    var sampleCount = 0
                    var firstTimeUs: Long? = null
                    var firstSync: Boolean? = null
                    var encryptedSampleSeen = false
                    while (sampleCount < 32 && extractor.sampleTime >= 0L) {
                        if (firstTimeUs == null) {
                            firstTimeUs = extractor.sampleTime
                            firstSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        }
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                            encryptedSampleSeen = true
                            break
                        }
                        sampleCount++
                        if (!extractor.advance()) break
                    }
                    TmeCmafFeedEvidence(
                        feedId = feedId,
                        mimeType = mime,
                        width = width,
                        height = height,
                        codecConfigFingerprint = fingerprint,
                        ppsTilesEnabled = if (mime.equals("video/hevc", ignoreCase = true)) {
                            configBytes?.let(HevcPpsTileInspector::tilesEnabled)
                        } else {
                            null
                        },
                        encrypted = encryptedSampleSeen ||
                            format.getInteger("is-encrypted", 0) != 0 ||
                            format.getInteger("encrypted", 0) != 0,
                        sampleCount = sampleCount,
                        firstSampleTimeUs = firstTimeUs,
                        firstSampleIsSync = firstSync,
                        encryptionMethod = null
                    )
                } finally {
                    extractor.release()
                }
            } finally {
                file.delete()
            }
        }.getOrElse { error ->
            TmeCmafFeedEvidence(
                feedId = feedId,
                mimeType = null,
                width = null,
                height = null,
                codecConfigFingerprint = null,
                encrypted = false,
                sampleCount = 0,
                firstSampleTimeUs = null,
                firstSampleIsSync = null,
                inspectionError = error.message?.take(180) ?: error.javaClass.simpleName
            )
        }
    }

    internal fun extract(
        playlist: HlsPlaylist,
        segment: HlsSegment,
        timelineOffsetUs: Long = 0L
    ): TmeCmafSegment {
        val init = playlist.initUri?.let { initCache.getOrPut(it) { get(it) } } ?: ByteArray(0)
        val media = get(segment.uri)
        val file = File.createTempFile("f1tme-", ".mp4", context.cacheDir)
        try {
            file.outputStream().use {
                if (init.isNotEmpty()) it.write(init)
                it.write(media)
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/hevc") == true
                } ?: error("CMAF segment has no HEVC track")
                val format = extractor.getTrackFormat(track)
                AppLogger.i("TME", "CMAF_TRACK sequence=${segment.sequence} mime=${format.getString(MediaFormat.KEY_MIME)} size=${format.getInteger(MediaFormat.KEY_WIDTH, -1)}x${format.getInteger(MediaFormat.KEY_HEIGHT, -1)} encryptedTrack=${format.getInteger("encrypted", 0)}")
                val csd = format.getByteBuffer("csd-0") ?: error("HEVC track has no csd-0")
                val config = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                extractor.selectTrack(track)
                val maxSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(1 * 1024 * 1024))
                val samples = mutableListOf<TmeCmafSample>()
                var sampleTimestampOffsetUs: Long? = null
                val expectedSegmentStartUs = timelineOffsetUs + segment.startTimeUs

                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                        AppLogger.e("TME", "CMAF_ENCRYPTED sequence=${segment.sequence} sampleTimeUs=${extractor.sampleTime}; refusing native clear-sample extraction")
                        error("Encrypted CMAF is not accepted by the clear TME pipeline")
                    }
                    val rawSampleTimeUs = extractor.sampleTime
                    if (sampleTimestampOffsetUs == null) {
                        // Some CMAF extractors expose tfdt/CTS on the presentation timeline,
                        // while others expose timestamps relative to the isolated fragment.
                        // Detect the former so the HLS timeline is not double-applied.
                        sampleTimestampOffsetUs =
                            if (kotlin.math.abs(rawSampleTimeUs - expectedSegmentStartUs) <= 100_000L) {
                                0L
                            } else {
                                expectedSegmentStartUs
                            }
                    }
                    val payload = ByteArray(size)
                    buffer.flip()
                    buffer.get(payload)
                    samples += TmeCmafSample(
                        rawSampleTimeUs + requireNotNull(sampleTimestampOffsetUs),
                        0L,
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0,
                        payload
                    )
                    if (!extractor.advance()) break
                }

                AppLogger.i("TME", "CMAF_EXTRACTED sequence=${segment.sequence} samples=${samples.size} durationUs=${segment.durationUs} clearHevc=true")
                val withDurations = samples.mapIndexed { i, sample ->
                    val next = samples.getOrNull(i + 1)?.timeUs
                    sample.copy(
                        durationUs = if (next != null) (next - sample.timeUs).coerceAtLeast(0L)
                        else segment.durationUs / samples.size.coerceAtLeast(1)
                    )
                }
                return TmeCmafSegment(segment.sequence, segment.durationUs, withDurations, config, width, height)
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            requestHeaders.forEach { (key, value) -> c.setRequestProperty(key, value) }
            check(c.responseCode in 200..299) { "HTTP " + c.responseCode + " while fetching CMAF resource" }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, child: String): String = URI(base).resolve(child).toString()
}

internal class TmeTimelineState {
    private var initialized = false
    private var lastSequence = Long.MIN_VALUE
    private var lastStartUs = 0L
    private var lastDurationUs = 0L

    fun resolveOffset(playlist: HlsPlaylist): Long {
        val first = playlist.segments.firstOrNull() ?: return 0L
        if (!initialized) {
            return 0L
        }

        val overlap = playlist.segments.firstOrNull { it.sequence == lastSequence }
        return if (overlap != null) {
            lastStartUs - overlap.startTimeUs
        } else {
            val gap = (first.sequence - lastSequence).coerceAtLeast(1L)
            (lastStartUs + lastDurationUs * gap) - first.startTimeUs
        }
    }

    fun commit(sequence: Long, startUs: Long, durationUs: Long) {
        initialized = true
        lastSequence = sequence
        lastStartUs = startUs
        lastDurationUs = durationUs
    }
}

class TmeCmafCoordinator(
    private val context: Context,
    private val merger: NativeTmeMerger,
    private val decoder: TmeNativeDecoder,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val outputWidth: Int? = null,
    private val outputHeight: Int? = null
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val synchronizer = TmeSegmentSynchronizer()

    fun start(
        sources: List<TmeTileSource>,
        surface: Surface,
        onError: (Throwable) -> Unit = {},
        onDecoderReady: () -> Unit = {}
    ) {
        require(sources.size >= 2)
        stop()
        running = true
        worker = Thread({
            try {
                val readers = sources.associateWith { TmeCmafFeedReader(context, requestHeaders) }
                // Media sequence numbers are monotonic for the live playlist. Retain only
                // the last committed sequence instead of an ever-growing set for the
                // duration of a race.
                var lastProcessedSequence = Long.MIN_VALUE
                var configured = false
                val timelineStates = sources.associate { it.feedId to TmeTimelineState() }.toMutableMap()
                // Network and CMAF extraction for independent tiles are I/O-bound.
                // Running them serially makes the slowest tile stall the whole mosaic.
                val ioPool = Executors.newFixedThreadPool(sources.size)

                try {
                    while (running) {
                        val playlistJobs = sources.associateWith { source ->
                            ioPool.submit(Callable { readers[source]!!.load(source.url) })
                        }
                        val playlists = playlistJobs.mapValues { (source, job) ->
                            runCatching { job.get() }
                                .onFailure {
                                    if (running) {
                                        AppLogger.e(
                                            "TME",
                                            "Playlist refresh failed for ${source.feedId}: ${it.message}"
                                        )
                                    }
                                }
                                .getOrNull()
                        }

                    // Process the earliest segment present in every tile feed. Never
                    // jump to the live edge of one feed while another feed is behind.
                    val availableSequences = sources.map { source ->
                        playlists[source]?.segments
                            ?.asSequence()
                            ?.map { it.sequence }
                            ?.filter { it > lastProcessedSequence }
                            ?.toSet()
                            .orEmpty()
                    }
                    val commonSequence = availableSequences
                        .takeIf { it.isNotEmpty() && it.all { it.isNotEmpty() } }
                        ?.reduce { common, next -> common intersect next }
                        ?.minOrNull()
                    if (commonSequence == null) {
                        synchronizer.dropExpired()
                        Thread.sleep(250L)
                        continue
                    }

                    val extractionJobs = sources.associateWith { source ->
                        val playlist = playlists[source] ?: return@associateWith null
                        val segment = playlist.segments.firstOrNull {
                            it.sequence == commonSequence && it.sequence > lastProcessedSequence
                        } ?: return@associateWith null
                        val timelineOffsetUs = timelineStates.getValue(source.feedId).resolveOffset(playlist)
                        ioPool.submit(
                            Callable {
                                source to readers[source]!!.extract(
                                    playlist,
                                    segment,
                                    timelineOffsetUs
                                )
                            }
                        )
                    }
                    val current = extractionJobs.map { (source, job) ->
                        if (job == null) return@map null
                        runCatching { job.get() }
                            .onFailure {
                                if (running) {
                                    AppLogger.e(
                                        "TME",
                                        "CMAF extraction failed for ${source.feedId}: ${it.message}"
                                    )
                                }
                            }
                            .getOrNull()
                    }.filterNotNull()
                    if (current.size != sources.size) {
                        Thread.sleep(250L)
                        continue
                    }

                    // The native merger needs the physical tile geometry and HEVC
                    // decoder configuration to remain stable across all participating
                    // feeds. Fail the epoch early instead of constructing a malformed
                    // compressed-domain graph.
                    val geometryValid = current.zip(sources).all { (entry, source) ->
                        entry.second.width == source.tileWidth &&
                            entry.second.height == source.tileHeight &&
                            entry.second.samples.isNotEmpty()
                    }
                    if (!geometryValid) {
                        val detail = current.map { (source, segment) ->
                            source.feedId + "=" + segment.width + "x" + segment.height +
                                " samples=" + segment.samples.size
                        }.joinToString(";")
                        throw IllegalStateException("TME tile geometry/sample validation failed: $detail")
                    }

                    AppLogger.i(
                        "TME",
                        "CMAF_EPOCH sequence=$commonSequence " +
                            current.joinToString(" ") { (source, segment) ->
                                source.feedId + ":samples=" + segment.samples.size +
                                    ",size=" + segment.width + "x" + segment.height
                            }
                    )

                    if (!configured) {
                        val width = outputWidth ?: sources.maxOf { (it.column + 1) * it.tileWidth }
                        val height = outputHeight ?: sources.maxOf { ((it.row ?: 0) + 1) * it.tileHeight }
                        val configuredSources = sources.mapIndexed { i, source ->
                            source.copy(decoderConfig = current[i].second.decoderConfig)
                        }
                        merger.configure(configuredSources, width, height)
                        configured = true
                    }

                    // Match every tile against a common reference timestamp instead of
                    // requiring identical millisecond timestamps. Independent CMAF tracks can
                    // differ by small muxing/rounding errors even when they represent the same
                    // video frame.
                    val referenceSamples = current.first().second.samples
                    val samplesByFeed = current.associate { (source, segment) ->
                        source.feedId to segment.samples
                    }
                    val expectedFeedIds = sources.map { it.feedId }.toSet()
                    val consumedByFeed = sources.associate { it.feedId to mutableSetOf<Int>() }

                    for (reference in referenceSamples) {
                        if (!running) break

                        val matches = sources.mapNotNull { source ->
                            val samples = samplesByFeed[source.feedId].orEmpty()
                            val candidates = samples.mapIndexedNotNull { index, sample ->
                                if (index in consumedByFeed[source.feedId].orEmpty()) {
                                    null
                                } else {
                                    val referenceDurationUs = reference.durationUs.takeIf { it > 0L } ?: 40_000L
                                    // F1's Premium pipeline is epoch-locked and uses a common
                                    // encoding cadence across feeds. Do not allow a loose
                                    // multi-frame match: a neighbouring frame from another
                                    // feed would create a visually wrong but superficially
                                    // "synchronized" mosaic.
                                    val maxTimestampDeltaUs = minOf(
                                        50_000L,
                                        maxOf(5_000L, (referenceDurationUs * 45L) / 100L)
                                    )
                                    val delta = kotlin.math.abs(sample.timeUs - reference.timeUs)
                                    if (delta <= maxTimestampDeltaUs) index to sample else null
                                }
                            }
                            candidates.minByOrNull { kotlin.math.abs(it.second.timeUs - reference.timeUs) }
                                ?.also { consumedByFeed[source.feedId]?.add(it.first) }
                        }
                        if (matches.size != sources.size) continue

                        // The synchronizer identity is sequence + media timestamp. Duration is
                        // normalized across the tile set so tiny duration differences cannot
                        // create separate buckets for the same frame.
                        val canonicalTimeUs = matches.map { it.second.timeUs }.average().toLong()
                        val canonicalDurationUs = matches.map { it.second.durationUs }
                            .filter { it > 0L }
                            .minOrNull()
                            ?: reference.durationUs
                        val key = TmeCmafSegmentKey(
                            commonSequence,
                            canonicalTimeUs,
                            canonicalDurationUs
                        )
                        val tiles = matches.mapIndexed { index, match ->
                            TmeTileSegment(
                                sources[index].feedId,
                                key,
                                match.second.payload,
                                match.second.keyFrame
                            )
                        }

                        var aligned: TmeAlignedSegment? = null
                        tiles.forEach { tile ->
                            synchronizer.offer(tile, expectedFeedIds).let { result ->
                                if (result != null) aligned = result
                            }
                        }
                        aligned = aligned ?: synchronizer.pollComplete(expectedFeedIds)
                        val complete = aligned ?: continue
                        val merged = merger.merge(complete)
                        if (merged.isNotEmpty() && !decoder.telemetry().configured) {
                            val first = merged.first()
                            decoder.configure(
                                surface,
                                outputWidth ?: sources.maxOf { (it.column + 1) * it.tileWidth },
                                outputHeight ?: sources.maxOf { ((it.row ?: 0) + 1) * it.tileHeight },
                                requireNotNull(first.codecConfig) {
                                    "GPAC HEVC merger produced no decoder configuration"
                                }
                            )
                            decoder.start()
                            onDecoderReady()
                        }
                        merged.forEach { accessUnit ->
                            var queued = false
                            var attempts = 0
                            while (!queued && attempts++ < 200) {
                                queued = decoder.queue(accessUnit)
                                if (!queued) {
                                    decoder.drain()
                                    Thread.sleep(1L)
                                }
                            }
                            check(queued) { "MediaCodec input queue stalled for native TME" }
                        }
                        decoder.drain()
                    }

                        playlists.forEach { (source, playlist) ->
                            playlist?.segments?.firstOrNull { it.sequence == commonSequence }?.let { segment ->
                                val state = timelineStates.getValue(source.feedId)
                                val offset = state.resolveOffset(playlist)
                                state.commit(
                                    segment.sequence,
                                    segment.startTimeUs + offset,
                                    segment.durationUs
                                )
                            }
                        }
                        lastProcessedSequence = commonSequence
                        synchronizer.dropExpired()
                        // Do not introduce artificial pacing after a successfully
                        // merged sample. Continue immediately so MediaCodec receives
                        // the next access unit as soon as it is available.
                    }
                } finally {
                    ioPool.shutdownNow()
                }
            } catch (t: Throwable) {
                if (running) onError(t)
            }
        }, "f1tme-cmaf").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        val current = worker
        current?.interrupt()
        if (current != null && current !== Thread.currentThread()) {
            runCatching { current.join(2_000L) }
                .onFailure { error ->
                    AppLogger.w("TME", "Timed out waiting for CMAF worker shutdown: " + error.message)
                }
        }
        worker = null
    }
}
