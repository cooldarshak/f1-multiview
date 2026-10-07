package app.f1multiview.model

enum class StreamKind { WORLD, ONBOARD, TIMING, TRACK, HELICAM, DATA, F1_DASH }
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
data class TimingRow(val position: Int, val driver: String, val gap: String, val lastLap: String, val tyre: String, val pitStops: Int, val sector1: String = "-", val sector2: String = "-", val sector3: String = "-", val speed: String = "-", val drs: Boolean = false, val bestLap: String = "-", val lap: Int = 0, val sector1Status: String = "NORMAL", val sector2Status: String = "NORMAL", val sector3Status: String = "NORMAL", val sector1Segments: List<String> = emptyList(), val sector2Segments: List<String> = emptyList(), val sector3Segments: List<String> = emptyList(), val interval: String = "-", val leaderGap: String = "-")
data class RaceControlEvent(val time: String, val message: String, val severity: String)
data class TeamRadioItem(val time:String,val driver:String,val url:String,val durationMs:Long=0L)
data class TimingWeather(val air: String = "-", val track: String = "-", val humidity: String = "-", val wind: String = "-", val rainfall: String = "-", val windDirection: String = "-")
data class Session(
    val id: String, val name: String, val country: String, val dateLabel: String, val live: Boolean,
    val seasonYear: Int? = null, val eventPageId: Int? = null, val series: String = "F1",
    val sessionType: String = "other",
    val meetingNumber: Int? = null,
    val artworkUrl: String? = null,
    val backgroundArtworkUrl: String? = null
)
data class SavedSetup(val id: String, val name: String, val layout: LayoutPreset, val streamIds: List<String>, val mainStreamId: String? = null)


data class ContinueWatchingEntry(
    val contentId: String,
    val title: String,
    val series: String = "F1",
    val eventPageId: Int,
    val stage: String = "other",
    val positionMs: Long,
    val durationMs: Long = 0L,
    val updatedAtMs: Long,
    val artworkUrl: String? = null,
    val backgroundArtworkUrl: String? = null,
    val seasonYear: Int? = null,
    val meetingNumber: Int? = null,
    val streamId: String? = null
)


enum class RacingSeries(val id: String, val displayName: String) {
    F1("F1", "Formula 1"),
    F2("F2", "Formula 2"),
    F3("F3", "Formula 3"),
    F1_ACADEMY("F1 Academy", "F1 Academy"),
    PORSCHE_SUPERCUP("Porsche Supercup", "Porsche Supercup");

    companion object {
        fun fromId(value: String?): RacingSeries =
            entries.firstOrNull { it.id.equals(value?.trim(), true) } ?: F1
    }
}

data class LiveSessionInfo(
    val name: String = "-",
    val meeting: String = "-",
    val country: String = "-",
    val sessionType: String = "-",
    val status: String = "-",
    val circuitKey: Int? = null,
    val year: Int? = null
)


data class TrackDriverPosition(
    val number: String,
    val name: String = "-",
    val acronym: String = "-",
    val team: String = "-",
    val teamColor: String = "FFFFFF",
    val x: Double,
    val y: Double,
    val z: Double = 0.0,
    val position: Int = 0,
    val speed: Int = 0,
    val lap: Int = 0,
    val inPit: Boolean = false,
    val stopped: Boolean = false,
    val retired: Boolean = false,
    val updatedAtMs: Long = 0L,
    val trail: List<TrackPoint> = emptyList()
)

data class TrackPoint(val x: Double, val y: Double)

data class TrackStatusInfo(
    val code: Int = 1,
    val label: String = "GREEN",
    val message: String = ""
)

data class TrackCorner(
    val number: Int,
    val letter: String = "",
    val x: Double,
    val y: Double,
    val distance: Double = 0.0,
    val angle: Double = 0.0
)

data class TrackMapGeometry(
    val circuitKey: Int,
    val year: Int,
    val rotation: Double = 0.0,
    val corners: List<TrackCorner> = emptyList()
)
