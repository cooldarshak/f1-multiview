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

internal data class HlsSegment(val sequence: Long, val uri: String, val durationUs: Long)
internal data class HlsPlaylist(val initUri: String?, val segments: List<HlsSegment>)

class TmeCmafFeedReader(
    private val context: Context,
    private val requestHeaders: Map<String, String> = emptyMap()
) {
    private val initCache = mutableMapOf<String, ByteArray>()

    internal fun load(url: String): HlsPlaylist {
        val text = get(url).toString(Charsets.UTF_8)
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            var bestBandwidth = -1L
            var best: String? = null
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
                val bandwidth = Regex("""(?:^|,)BANDWIDTH=(\d+)""")
                    .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val child = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                if (bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth
                    best = resolve(url, child)
                }
            }
            require(!best.isNullOrBlank()) { "HLS master has no media variant" }
            return load(best!!)
        }

        var mediaSequence = 0L
        var nextSequence = 0L
        var initUri: String? = null
        var durationUs: Long? = null
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').toLong()
                line.startsWith("#EXT-X-MAP:") ->
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let {
                        initUri = resolve(url, it)
                    }
                line.startsWith("#EXTINF:") ->
                    durationUs = (line.substringAfter(':').substringBefore(',').toDouble() * 1_000_000.0).toLong()
                !line.startsWith("#") && durationUs != null -> {
                    segments += HlsSegment(mediaSequence + nextSequence, resolve(url, line), durationUs!!)
                    nextSequence++
                    durationUs = null
                }
            }
        }
        return HlsPlaylist(initUri, segments)
    }

    internal fun extract(playlist: HlsPlaylist, segment: HlsSegment): TmeCmafSegment {
        val init = playlist.initUri?.let { initCache.getOrPut(it) { get(it) } }.orEmpty()
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
                val csd = format.getByteBuffer("csd-0") ?: error("HEVC track has no csd-0")
                val config = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                extractor.selectTrack(track)
                val maxSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
                val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(1 * 1024 * 1024))
                val samples = mutableListOf<TmeCmafSample>()

                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                        "Encrypted CMAF is not accepted by the clear TME pipeline"
                    }
                    val payload = ByteArray(size)
                    buffer.flip()
                    buffer.get(payload)
                    samples += TmeCmafSample(
                        extractor.sampleTime,
                        0L,
                        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0,
                        payload
                    )
                    if (!extractor.advance()) break
                }

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
            check(c.responseCode in 200..299) { "HTTP " + c.responseCode + " for " + url }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, child: String): String = URI(base).resolve(child).toString()
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
                val processed = mutableSetOf<Long>()
                var configured = false

                while (running) {
                    val playlists = sources.associateWith { readers[it]!!.load(it.url) }

                    // Process the earliest segment present in every tile feed. Never
                    // jump to the live edge of one feed while another feed is behind.
                    val availableSequences = sources.map { source ->
                        playlists[source]?.segments
                            ?.asSequence()
                            ?.map { it.sequence }
                            ?.filterNot(processed::contains)
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

                    val current = sources.mapNotNull { source ->
                        val playlist = playlists[source] ?: return@mapNotNull null
                        val segment = playlist.segments.firstOrNull {
                            it.sequence == commonSequence && it.sequence !in processed
                        } ?: return@mapNotNull null
                        source to readers[source]!!.extract(playlist, segment)
                    }
                    if (current.size != sources.size) {
                        Thread.sleep(250L)
                        continue
                    }

                    if (!configured) {
                        val width = outputWidth ?: sources.maxOf { (it.column + 1) * it.tileWidth }
                        val height = outputHeight ?: sources.maxOf { ((it.row ?: 0) + 1) * it.tileHeight }
                        val configuredSources = sources.mapIndexed { i, source ->
                            source.copy(decoderConfig = current[i].second.decoderConfig)
                        }
                        merger.configure(configuredSources, width, height)
                        configured = true
                    }

                    val sampleMaps = current.associate { (source, segment) ->
                        source.feedId to segment.samples.associateBy { it.timeUs / 1000L }
                    }
                    val timestamps = sampleMaps.values
                        .reduce { common, next -> common intersect next.keys }
                        .sorted()
                    for (timestamp in timestamps) {
                        if (!running) break
                        val tiles = sources.mapNotNull { source ->
                            sampleMaps[source.feedId]?.get(timestamp)?.let { sample ->
                                TmeTileSegment(
                                    source.feedId,
                                    TmeCmafSegmentKey(commonSequence, sample.timeUs, sample.durationUs),
                                    sample.payload,
                                    sample.keyFrame
                                )
                            }
                        }
                        if (tiles.size != sources.size) continue

                        val expectedFeedIds = sources.map { it.feedId }.toSet()
                        var aligned: TmeAlignedSegment? = null
                        tiles.forEach { tile ->
                            if (aligned == null) {
                                aligned = synchronizer.offer(tile, expectedFeedIds)
                            } else {
                                synchronizer.offer(tile, expectedFeedIds)
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

                    processed += commonSequence
                    synchronizer.dropExpired()
                    Thread.sleep(150L)
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
        worker?.interrupt()
        worker = null
    }
}
