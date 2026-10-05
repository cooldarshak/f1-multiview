package app.f1multiview.model

enum class StreamKind { WORLD, ONBOARD, TIMING, TRACK, HELICAM, DATA }
enum class LayoutPreset { SINGLE, SPLIT_2, GRID_4, GRID_6 }
data class StreamSource(
    val id: String, val title: String, val kind: StreamKind, val url: String? = null,
    val driver: String? = null, val drmLicenseUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(), val drmRequestHeaders: Map<String, String> = emptyMap(),
    val contentId: String? = null, val channelId: String? = null, val isLive: Boolean = true,
    val playApiVersion: String? = null, val platform: String? = null, val streamType: String? = null,
    val ascendonToken: String? = null, val entitlementToken: String? = null, val drmType: String? = null, val playToken: String? = null
)
data class DriverTelemetry(val driver: String, val speed: Int, val rpm: Int, val gear: Int, val throttle: Int, val brake: Int, val drs: Boolean, val lap: Int, val lapTime: String)
data class TimingRow(val position: Int, val driver: String, val gap: String, val lastLap: String, val tyre: String, val pitStops: Int, val sector1: String = "-", val sector2: String = "-", val sector3: String = "-", val speed: String = "-", val drs: Boolean = false)
data class RaceControlEvent(val time: String, val message: String, val severity: String)
data class TimingWeather(val air: String = "-", val track: String = "-", val humidity: String = "-", val wind: String = "-", val rainfall: String = "-")
data class Session(
    val id: String, val name: String, val country: String, val dateLabel: String, val live: Boolean,
    val seasonYear: Int? = null, val eventPageId: Int? = null, val series: String = "F1",
    val sessionType: String = "other",
    val artworkUrl: String? = null,
    val backgroundArtworkUrl: String? = null
)
data class SavedSetup(val id: String, val name: String, val layout: LayoutPreset, val streamIds: List<String>, val mainStreamId: String? = null)
