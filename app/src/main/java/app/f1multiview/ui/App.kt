package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.compose.ContentFrame
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.media3.common.util.UnstableApi
import app.f1multiview.core.playback.Quality
import app.f1multiview.core.playback.VodSession
import app.f1multiview.core.playback.VodEvent
import app.f1multiview.media.UnifiedMultiviewEngine
import app.f1multiview.media.EnginePlayerHandle
import app.f1multiview.media.RadioPlayer
import app.f1multiview.media.HdrPresentationDiagnostics
import app.f1multiview.media.DebugPresentationSettings
import app.f1multiview.media.AppLogger
import app.f1multiview.media.OpenTiledSecureSurfaceView
import app.f1multiview.media.OpenTiledCompositorView
import app.f1multiview.BuildConfig
import app.f1multiview.model.*
import app.f1multiview.viewmodel.*
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.widget.FrameLayout
import kotlinx.coroutines.delay
import kotlin.math.max

private val Red = Color(0xFFE10600)
private val Bg = Color(0xFF0B0B10)
private val Surface1 = Color(0xFF14151B)
private val Surface2 = Color(0xFF1C1D24)
private val White = Color(0xFFF5F5F7)
private val Muted = Color(0xFF9698A2)

@Composable
fun App(vm: MultiViewViewModel) {
    val context = LocalContext.current
    AppLogger.initialize(context)
    val ui by vm.ui.collectAsState()
    F1Theme {
        when (ui.auth) {
            AuthState.Checking, AuthState.SignedOut, is AuthState.SigningIn, is AuthState.Error -> LoginScreen(ui.auth, vm)
            AuthState.SignedIn -> MultiViewScreen(ui, vm)
        }
    }
}

@Composable
private fun F1TvLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("F1", color = Red, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
        Spacer(Modifier.width(7.dp))
        Text("TV", color = White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun LoginScreen(auth: AuthState, vm: MultiViewViewModel) {
    val errorMessage = (auth as? AuthState.Error)?.message
    F1BrowserLogin(
        vm = vm,
        errorMessage = errorMessage
    )
}

@Composable
private fun MultiViewScreen(ui: UiState, vm: MultiViewViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = remember(context) { isTelevision(context) }
    val isPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val compactPhone = !isTv && configuration.screenWidthDp < 600

    val engine = remember(context) { UnifiedMultiviewEngine(context) }
    LaunchedEffect(context) { HdrPresentationDiagnostics.log(context, "multiview-enter") }
    val radioPlayer = remember(context) { RadioPlayer(context) }
    val errors by engine.errors.collectAsState()
    var fullscreenStreamId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenMultiview by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(engine, radioPlayer) { onDispose { engine.release(); radioPlayer.release() } }
    val startedFeeds = remember(engine) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(ui.session?.id, ui.mainStreamId, ui.session?.live) {
        val session = ui.session ?: return@LaunchedEffect
        if (session.live) return@LaunchedEffect
        while (true) {
            val mainId = ui.mainStreamId ?: break
            val player = engine.player(mainId)
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            if (player.currentMediaItem != null && position >= 10_000L) {
                vm.saveContinueWatching(session, position, duration, mainId)
            }
            delay(5_000L)
        }
    }

    LaunchedEffect(ui.pendingResume?.contentId, ui.mainStreamId) {
        val pending = ui.pendingResume ?: return@LaunchedEffect
        val mainId = ui.mainStreamId ?: return@LaunchedEffect
        val player = engine.player(mainId)
        repeat(40) {
            if (player.currentMediaItem != null && player.duration > 0L) {
                val target = pending.positionMs.coerceIn(0L, (player.duration - 15_000L).coerceAtLeast(0L))
                player.seekTo(target)
                player.playWhenReady = true
                vm.clearPendingResume()
                return@LaunchedEffect
            }
            delay(250L)
        }
    }


    LaunchedEffect(ui.streams, ui.selectedStreamIds, ui.mainStreamId) {
        val selectedIds = ui.selectedStreamIds.toSet()
        startedFeeds.keys.filterNot { it in selectedIds }.toList().forEach { startedFeeds.remove(it) }
        val videoSelectedIds = ui.selectedStreamIds.filter { id ->
            ui.streams.firstOrNull { it.id == id }?.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)
        }.toSet()
        engine.retain(videoSelectedIds)
        val ordered = ui.selectedStreamIds.mapNotNull { id ->
            ui.streams.firstOrNull { it.id == id && it.url != null && it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA) }
        }
        val mainId = ui.mainStreamId?.takeIf { it in videoSelectedIds } ?: ordered.firstOrNull()?.id
        // Timing and Driver Tracker are native data feeds, not video decoders.
        // UnifiedMultiviewEngine now decides which logical video feeds receive physical
        // decoder slots based on the active viewport and reference feed.
        val scheduled = engine.updateViewport(
            streams = ordered,
            visibleIds = videoSelectedIds,
            referenceId = mainId,
            autoplay = false
        )
        startedFeeds.keys.retainAll(scheduled)
    }

    LaunchedEffect(fullscreenMultiview) {
        if (fullscreenMultiview && ui.selectedStreamIds.isNotEmpty()) {
            delay(150L)
            // Establish the reference clock before all followers are necessarily READY.
            // UnifiedMultiviewEngine will automatically attach each later-ready feed to this clock.
            val mainId = ui.mainStreamId ?: ui.selectedStreamIds.firstOrNull()
            if (mainId != null) {
                engine.syncToMain(mainId)
            }
            engine.playAll()
        }
    }

    LaunchedEffect(ui.selectedStreamIds, ui.liveSessionInfo.circuitKey) {
        if (ui.selectedStreamIds.any { id -> ui.streams.firstOrNull { it.id == id }?.kind == StreamKind.TRACK_MAP }) {
            vm.loadTrackMapGeometry()
        }
    }

    LaunchedEffect(ui.selectedStreamIds, ui.mainStreamId) {
        if (ui.selectedStreamIds.size > 1) {
            // Do not wait 1.5s for a one-shot sync. The UnifiedMultiviewEngine continuously watches
            // the reference and newly-ready feeds now join automatically.
            ui.mainStreamId?.let { mainId ->
                engine.syncToMain(
                    mainId,
                    ui.streams.associate {
                        it.id to (it.channelId?.let { cid -> ui.replayChannelDiffs[cid] } ?: 0L)
                    }
                )
            }
        }
    }

    // Continue Watching resumes directly into the fullscreen player once the saved
    // session has produced a playable stream. The existing pending-resume effect
    // performs the actual seek/play operation.
    LaunchedEffect(ui.pendingResume?.contentId, ui.selectedStreamIds) {
        if (ui.pendingResume != null && ui.selectedStreamIds.isNotEmpty()) {
            fullscreenMultiview = true
        }
    }


    BackHandler(enabled = fullscreenStreamId != null || fullscreenMultiview) {
        fullscreenStreamId = null
        fullscreenMultiview = false
    }

    if (fullscreenMultiview) {
        FullscreenMultiview(ui, vm, engine, errors, { engine.stopAll(); fullscreenMultiview = false }, vm::updateReplayTiming)
        return
    }

    val fullscreenStream = ui.streams.firstOrNull { it.id == fullscreenStreamId }
    if (fullscreenStream != null) {
        FullscreenPlayer(fullscreenStream, ui, engine, errors[fullscreenStream.id], vm, { id -> fullscreenStreamId = id; vm.setMainStream(id) }) { engine.stopAll(); fullscreenStreamId = null }
        return
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        F1HomeScreen(
            ui = ui,
            vm = vm,
            isTv = isTv,
            compactPhone = compactPhone,
            onOpenMultiview = { fullscreenMultiview = true },
            onOpenEditorial = { fullscreenMultiview = true },
            onOpenSession = { session ->
                vm.selectVodSession(session)
                fullscreenMultiview = true
            }
        )
        if (ui.selectedPanel != null) {
            UgisInfoPanel(
                ui = ui,
                vm = vm,
                radioPlayer = radioPlayer,
                isTv = isTv,
                onScreenshotModeChanged = { enabled ->
                    if (BuildConfig.DEBUG) {
                        DebugPresentationSettings.setScreenshotMode(context, enabled)
                    }
                }
            )
        }
    }
}
private fun displaySupportsHdr(context: Context): Boolean {
    val display = (context as? Activity)?.display ?: return false
    return if (Build.VERSION.SDK_INT >= 34) {
        display.mode.supportedHdrTypes.isNotEmpty()
    } else {
        @Suppress("DEPRECATION")
        display.hdrCapabilities.supportedHdrTypes.isNotEmpty()
    }
}

private fun isTelevision(context: Context): Boolean {
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION || context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@Composable
private fun Header(ui: UiState, vm: MultiViewViewModel, compactPhone: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101116)).padding(horizontal = if (compactPhone) 11.dp else 18.dp, vertical = if (compactPhone) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (compactPhone) {
            Text("F1", color = Red, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
            Spacer(Modifier.width(7.dp))
            Text("TV", color = White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        } else F1TvLogo()
        Spacer(Modifier.width(if (compactPhone) 12.dp else 22.dp))
        Box(Modifier.width(1.dp).height(if (compactPhone) 24.dp else 28.dp).background(Color.White.copy(alpha = .1f)))
        Spacer(Modifier.width(if (compactPhone) 10.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.session?.name ?: "F1 MULTIVIEW", color = White, fontSize = if (compactPhone) 11.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ui.session?.live == true) "LIVE NOW" else "F1 TV", color = if (ui.session?.live == true) Red else Muted, fontSize = if (compactPhone) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
        }
        if (!compactPhone) {
            Surface(color = Color.White.copy(alpha = .06f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (ui.timingStatus == "OFFLINE") Muted else Color(0xFF59D56D)))
                    Spacer(Modifier.width(6.dp))
                    Text(ui.timingStatus, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        TextButton({ vm.panel(if (ui.selectedPanel == "info") null else "info") }, contentPadding = PaddingValues(horizontal = if (compactPhone) 4.dp else 8.dp)) {
            Text("INFO", color = White, fontSize = if (compactPhone) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
        }
        TextButton({ vm.panel(if (ui.selectedPanel == "settings") null else "settings") }, contentPadding = PaddingValues(horizontal = if (compactPhone) 4.dp else 8.dp)) {
            Text("SETTINGS", color = White, fontSize = if (compactPhone) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
        }
        TextButton({ vm.signOut() }, contentPadding = PaddingValues(horizontal = if (compactPhone) 4.dp else 8.dp)) {
            Text("SIGN OUT", color = White, fontSize = if (compactPhone) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Hero(ui: UiState, isTv: Boolean, isPortrait: Boolean) {
    val height = when { isTv -> 230.dp; isPortrait -> 175.dp; else -> 205.dp }
    Box(Modifier.fillMaxWidth().height(height).background(Color(0xFF171820))) {
        ui.session?.artworkUrl?.let { url -> F1Artwork(url, ui.session.name, Modifier.fillMaxSize(), ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Black.copy(alpha = .36f), Red.copy(alpha = .16f)))))
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = if (isTv) 34.dp else 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) { Text(if (ui.session?.live == true) "LIVE" else "REPLAY", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) }
                Spacer(Modifier.width(8.dp))
                Text(ui.session?.series ?: "F1", color = White.copy(alpha = .78f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(ui.session?.name ?: "Choose a Grand Prix", color = White, fontSize = if (isTv) 30.sp else if (isPortrait) 23.sp else 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ui.session?.dateLabel ?: "Select a session below", color = White.copy(alpha = .72f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Text(ui.session?.seasonYear?.toString() ?: "F1", color = Color.White.copy(alpha = .12f), fontSize = if (isTv) 76.sp else 54.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(end = if (isTv) 34.dp else 18.dp, top = 16.dp))
    }
}


@Composable
private fun ContinueWatchingSection(
    entries: List<ContinueWatchingEntry>,
    isTv: Boolean,
    compactPhone: Boolean,
    onResume: (ContinueWatchingEntry) -> Unit,
    onRemove: (String) -> Unit
) {
    val side = if (compactPhone) 12.dp else if (isTv) 28.dp else 18.dp
    Column(Modifier.fillMaxWidth().padding(top = if (compactPhone) 8.dp else 14.dp)) {
        SectionHeader("CONTINUE WATCHING", entries.size.toString() + " RESUMABLE", side)
        LazyRow(
            contentPadding = PaddingValues(horizontal = side, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compactPhone) 10.dp else 14.dp),
            modifier = Modifier.focusGroup()
        ) {
            items(entries, key = { it.contentId }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    compactPhone = compactPhone,
                    isTv = isTv,
                    onResume = { onResume(entry) },
                    onRemove = { onRemove(entry.contentId) }
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: ContinueWatchingEntry,
    compactPhone: Boolean,
    isTv: Boolean,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val width = if (compactPhone) 245.dp else if (isTv) 310.dp else 275.dp
    val imageHeight = if (compactPhone) 138.dp else if (isTv) 174.dp else 155.dp
    val progress = if (entry.durationMs > 0L) {
        (entry.positionMs.toFloat() / entry.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onResume)
            .focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = Surface2,
        border = BorderStroke(2.dp, if (focused) Red else Color.White.copy(alpha = .07f))
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(imageHeight)) {
                F1Artwork(
                    entry.artworkUrl ?: entry.backgroundArtworkUrl,
                    entry.title,
                    Modifier.fillMaxSize(),
                    ContentScale.Crop,
                    "continue-" + entry.contentId
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .05f), Color.Black.copy(alpha = .82f))
                        )
                    )
                )
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clickable(onClick = onRemove).focusable(),
                    color = Color.Black.copy(alpha = .72f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))
                ) {
                    Text("×", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp)) {
                    Text(
                        "RESUME  " + formatResumeTime(entry.positionMs),
                        color = White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Red,
                        trackColor = Color.White.copy(alpha = .22f)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    entry.title,
                    color = White,
                    fontSize = if (compactPhone) 13.sp else 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(entry.series.takeIf { it.isNotBlank() }, entry.stage.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .uppercase(),
                    color = Muted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun formatResumeTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    return if (h > 0) "%d:%02d".format(h, m) else "%02d:%02d".format(m, total % 60L)
}

@Composable
private fun Archive(ui: UiState, vm: MultiViewViewModel, isTv: Boolean, compactPhone: Boolean) {
    val side = if (compactPhone) 12.dp else if (isTv) 28.dp else 18.dp
    val selectedEvent = ui.selectedEvent
    val seriesOptions = remember(ui.vodEvents, ui.vodSessions) {
        (ui.vodEvents.map { it.series } + ui.vodSessions.map { it.series })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ if (it == "F1") 0 else 1 }, { it }))
    }
    val selectedSeries = ui.selectedSeries.takeIf { it in seriesOptions } ?: seriesOptions.firstOrNull() ?: "F1"
    // Archive hierarchy is strictly Year -> Grand Prix -> Weekend sessions.
    // Keep an extra season-year guard here so an API response containing events
    // from multiple years can never leak another year's races into the selected year.
    val selectedYear = ui.selectedSeason?.year
    val eventsForSeries = ui.vodEvents.filter { event ->
        val matchesSeries = event.series == selectedSeries || selectedSeries == "F1" && event.series == "F1"
        val matchesYear = selectedYear == null || event.seasonYear == selectedYear
        matchesSeries && matchesYear
    }
    val activeEvent = selectedEvent?.takeIf { event ->
        event in eventsForSeries && (selectedYear == null || event.seasonYear == selectedYear)
    } ?: eventsForSeries.firstOrNull()
    val firstEventFocusRequester = remember { FocusRequester() }
    LaunchedEffect(ui.selectedSeason?.year, selectedSeries, eventsForSeries.size) {
        if (isTv && eventsForSeries.isNotEmpty()) {
            delay(80L)
            firstEventFocusRequester.requestFocus()
        }
    }
    val sessionsForEvent = if (activeEvent != null) {
        ui.vodSessions.filter { it.eventPageId == activeEvent.pageId && (selectedSeries == "F1" || it.series == selectedSeries) }
    } else emptyList()

    Column(Modifier.fillMaxWidth().padding(top = if (compactPhone) 8.dp else 14.dp)) {
        if (activeEvent != null) {
            ArchiveHero(activeEvent, compactPhone, isTv)
            Spacer(Modifier.height(if (compactPhone) 14.dp else 20.dp))
        }

        // LEVEL 1: YEAR
        SectionHeader("YEAR", ui.selectedSeason?.year?.toString() ?: "", side)
        LazyRow(
            contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.focusGroup()
        ) {
            items(ui.vodSeasons) { season ->
                ArchivePill(ui.selectedSeason == season, season.year.toString()) {
                    vm.selectVodSeason(season)
                }
            }
        }

        if (seriesOptions.size > 1) {
            SectionHeader("SERIES", selectedSeries.uppercase(), side)
            LazyRow(
                contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
            ) {
                items(seriesOptions) { series ->
                    ArchivePill(selectedSeries == series, series) { vm.setSeries(series) }
                }
            }
        }

        // LEVEL 2: GRAND PRIX / EVENT
        SectionHeader(
            if (selectedSeries == "F1") "GRAND PRIX" else "EVENTS",
            selectedYear?.toString() ?: "",
            side
        )
        if (eventsForSeries.isEmpty()) {
            Text(
                "No archive events found for this selection.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = side, vertical = 12.dp)
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = side, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compactPhone) 10.dp else 14.dp),
                modifier = Modifier.focusGroup()
            ) {
                items(eventsForSeries) { event ->
                    ArchiveEventCard(
                        event = event,
                        selected = activeEvent?.pageId == event.pageId,
                        compactPhone = compactPhone,
                        focusRequester = if (event.pageId == eventsForSeries.firstOrNull()?.pageId) firstEventFocusRequester else null,
                        onClick = { vm.selectVodEvent(event) }
                    )
                }
            }
        }

        if (activeEvent != null) {
            Spacer(Modifier.height(if (compactPhone) 8.dp else 12.dp))
            // LEVEL 3: SESSIONS for the selected Grand Prix
            WeekendSessions(
                sessions = sessionsForEvent,
                compactPhone = compactPhone,
                isTv = isTv,
                selectedId = ui.session?.id,
                side = side,
                onSelect = vm::selectVodSession
            )
        }
    }
}

@Composable
private fun ArchiveHero(event: VodEvent, compactPhone: Boolean, isTv: Boolean) {
    val height = when {
        compactPhone -> 175.dp
        isTv -> 270.dp
        else -> 225.dp
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF171820))
    ) {
        F1Artwork(
            event.backgroundArtworkUrl ?: event.artworkUrl,
            event.meetingName,
            Modifier.fillMaxSize(),
            ContentScale.Crop,
            "event-" + event.pageId
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = .88f),
                        Color.Black.copy(alpha = .54f),
                        Color.Black.copy(alpha = .18f)
                    )
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(
                horizontal = if (isTv) 34.dp else 20.dp,
                vertical = if (compactPhone) 16.dp else 22.dp
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        if (event.isTest) "TEST" else "GRAND PRIX",
                        color = White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    event.series,
                    color = White.copy(alpha = .82f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                if (event.meetingNumber > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ROUND " + event.meetingNumber,
                        color = White.copy(alpha = .62f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                prettyEvent(event.meetingName),
                color = White,
                fontSize = if (isTv) 31.sp else if (compactPhone) 22.sp else 27.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (event.startTime > 0L) {
                Text(
                    java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(event.startTime)),
                    color = White.copy(alpha = .68f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ArchivePill(selected: Boolean, title: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Red else Surface2,
        border = if (selected || focused) BorderStroke(2.dp, if (selected) Red else White) else BorderStroke(1.dp, Color.White.copy(alpha = .08f))
    ) {
        Text(
            title,
            color = White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun ArchiveEventCard(
    event: VodEvent,
    selected: Boolean,
    compactPhone: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val width = if (compactPhone) 205.dp else 270.dp
    val height = if (compactPhone) 145.dp else 175.dp
    Surface(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
             .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(10.dp),
        color = Surface2,
        border = BorderStroke(2.dp, if (selected || focused) Red else Color.White.copy(alpha = .07f))
    ) {
        Box(Modifier.fillMaxSize()) {
            F1Artwork(
                event.artworkUrl ?: event.backgroundArtworkUrl,
                event.meetingName,
                Modifier.fillMaxSize(),
                ContentScale.Crop,
                "event-card-" + event.pageId
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .92f))
                    )
                )
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(
                    horizontal = 12.dp,
                    vertical = 10.dp
                )
            ) {
                Text(
                    prettyEvent(event.meetingName),
                    color = White,
                    fontSize = if (compactPhone) 13.sp else 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (event.isTest) "TEST" else "ROUND " + event.meetingNumber,
                    color = White.copy(alpha = .68f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun WeekendSessions(
    sessions: List<VodSession>,
    compactPhone: Boolean,
    isTv: Boolean,
    selectedId: String?,
    side: androidx.compose.ui.unit.Dp,
    onSelect: (VodSession) -> Unit
) {
    val groups = listOf(
        "PRACTICE" to listOf("practice-1", "practice-2", "practice-3", "practice"),
        "SPRINT" to listOf("sprint-qualifying", "sprint"),
        "QUALIFYING" to listOf("qualifying"),
        "RACE" to listOf("race"),
        "SHOWS & EXTRAS" to listOf("pre-show", "post-show", "f1-kids")
    )
    groups.forEach { (heading, stages) ->
        val stageSessions = sessions.filter { it.stage in stages }
        if (stageSessions.isNotEmpty()) {
            Spacer(Modifier.height(if (compactPhone) 10.dp else 14.dp))
            SectionHeader(heading, stageSessions.size.toString() + " VIDEOS", side)
            LazyRow(
                contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compactPhone) 10.dp else 14.dp),
                modifier = Modifier.focusGroup()
            ) {
                items(stageSessions) { session ->
                    SessionCard(
                        selectedId == session.contentId,
                        session.title,
                        session.broadcastVariant.replace("-", " ").uppercase(),
                        session.artworkUrl,
                        isTv,
                        onClick = { onSelect(session) }
                    )
                }
            }
        }
    }
}
@Composable
private fun SectionHeader(title: String, meta: String, side: androidx.compose.ui.unit.Dp = 18.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(17.dp).background(Red, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        Text(meta, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Pill(selected: Boolean, title: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.clip(RoundedCornerShape(9.dp)) .clickable(onClick = onClick).focusable().onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(9.dp), color = if (selected) Red else Surface2, border = if (selected) null else if (focused) BorderStroke(2.dp, White) else BorderStroke(1.dp, Color.White.copy(alpha = .07f))) {
        Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp))
    }
}

@Composable
private fun FeaturedReplayCard(session: VodSession, selected: Boolean, compactPhone: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val width = if (compactPhone) 360.dp else 470.dp
    val imageHeight = if (compactPhone) 205.dp else 265.dp
    Surface(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
             .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            ,
        shape = RoundedCornerShape(10.dp),
        color = Surface2,
        border = BorderStroke(2.dp, if (selected) Red else if (focused) White else Color.White.copy(alpha = .08f))
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(imageHeight)) {
                F1Artwork(session.artworkUrl, session.title, Modifier.fillMaxSize(), ContentScale.Crop, session.contentId)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .18f)))))
                Surface(
                    Modifier.align(Alignment.TopStart).padding(12.dp),
                    color = Color.Black.copy(alpha = .86f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "FEATURED",
                        color = White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text(
                    session.title,
                    color = White,
                    fontSize = if (compactPhone) 16.sp else 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "F1 · " + session.type.uppercase(),
                    color = White.copy(alpha = .68f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun EventCard(selected: Boolean, title: String, artworkUrl: String?, seasonYear: Int, isTv: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val width = if (isTv) 280.dp else 220.dp
    val imageHeight = if (isTv) 158.dp else 124.dp
    Surface(
        Modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).focusable().onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF2A0D0F) else Surface1,
        border = BorderStroke(2.dp, if (selected) Red else if (focused) White else Color.White.copy(alpha = .07f))
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(imageHeight)) {
                F1Artwork(artworkUrl, title, Modifier.fillMaxSize(), ContentScale.Crop, title + seasonYear)
                Surface(Modifier.align(Alignment.TopStart).padding(9.dp), color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(4.dp)) {
                    Text(seasonYear.toString(), color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (selected) "SELECTED" else "GRAND PRIX", color = if (selected) Red else Muted, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun SessionCard(selected: Boolean, title: String, type: String, artworkUrl: String?, isTv: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val width = if (isTv) 280.dp else 220.dp
    val imageHeight = if (isTv) 158.dp else 124.dp
    Surface(
        Modifier.width(width)
            .clip(RoundedCornerShape(12.dp))
             .clickable(onClick = onClick).focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF2A0D0F) else Surface2,
        border = BorderStroke(2.dp, if (selected || focused) Red else Color.White.copy(alpha = .07f))
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(imageHeight)) {
                F1Artwork(artworkUrl, title, Modifier.fillMaxSize(), ContentScale.Crop, title + type)
                Surface(Modifier.align(Alignment.TopEnd).padding(9.dp), color = if (selected) Red else Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(4.dp)) {
                    Text(type, color = White, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("SESSION · " + type, color = White.copy(alpha = .58f), fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
@Composable
private fun PitWall(
    ui: UiState,
    engine: UnifiedMultiviewEngine,
    errors: Map<String, String>,
    isTv: Boolean,
    compactPhone: Boolean,
    onFullscreenAll: () -> Unit,
    onToggleStream: (String) -> Unit,
    onSetMainStream: (String) -> Unit
) {
    val backendStatus by engine.backendStatus.collectAsState()
    val tiledSelected = ui.selectedTiledFeedIds.ifEmpty { ui.tiledMultiviewSession?.feedIds?.take(24).orEmpty() }
    val selected = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id } }
        .let { if (backendStatus.kind == app.f1multiview.media.MultiviewBackendKind.OPEN_TME) it else it.take(4) }
    var wallAspect by rememberSaveable { mutableFloatStateOf(16f / 9f) }
    LaunchedEffect(ui.mainStreamId, selected.size) {
        repeat(16) {
            val id = ui.mainStreamId ?: selected.firstOrNull()?.id
            val d = id?.let(engine::currentVideoDiagnostics)
            if (d != null && d.width > 0 && d.height > 0) {
                wallAspect = (d.width.toFloat() / d.height.toFloat()).coerceIn(1.2f, 2.4f)
                return@LaunchedEffect
            }
            delay(500L)
        }
    }
    var feedPanelOpen by rememberSaveable { mutableStateOf(false) }
    var feedToggleFocused by remember { mutableStateOf(false) }
    var tvResizeMode by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(28.dp).background(Red, RoundedCornerShape(3.dp)))
        Text("LIVE PIT WALL", color = White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 14.dp))
        Spacer(Modifier.weight(1f))
        Text(if (selected.isEmpty()) "SELECT FEEDS" else if (backendStatus.kind == app.f1multiview.media.MultiviewBackendKind.OPEN_TME) tiledSelected.size.toString() + "/24" else selected.size.toString() + "/4", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Surface(
            Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
                .clickable { feedPanelOpen = !feedPanelOpen }
                .focusable()
                .onFocusChanged { feedToggleFocused = it.isFocused },
            shape = RoundedCornerShape(10.dp),
            color = if (feedPanelOpen) Red else Surface2,
            border = if (feedToggleFocused) BorderStroke(2.dp, White) else if (feedPanelOpen) null else BorderStroke(1.dp, Color.White.copy(alpha = .09f))
        ) {
            Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("FEEDS", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(7.dp))
                Text(ui.streams.size.toString(), color = White.copy(alpha = .72f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text(if (feedPanelOpen) "▲" else "▼", color = White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
    if (feedPanelOpen) LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.streams) { stream ->
            val picked = stream.id in ui.selectedStreamIds
            val isMain = stream.id == ui.mainStreamId
            var feedFocused by remember(stream.id) { mutableStateOf(false) }
            Surface(Modifier.clip(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp), color = if (isMain) Red else if (picked) Color(0xFF5A1012) else Surface2, border = BorderStroke(1.dp, if (isMain) Red else Color.White.copy(alpha = .08f))) {
                Column(Modifier.widthIn(min = 135.dp, max = 190.dp).padding(horizontal = 9.dp, vertical = 7.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onToggleStream(stream.id) }
                            .focusable()
                            .onFocusChanged { feedFocused = it.isFocused }
                            .then(if (feedFocused) Modifier.border(2.dp, White, RoundedCornerShape(6.dp)) else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { isMain -> "MAIN FEED"; picked -> "IN MULTIVIEW"; else -> stream.kind.name }, color = if (isMain || picked) White.copy(alpha = .88f) else Muted, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(if (picked) "✓" else "+", color = White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(5.dp))
                    var mainActionFocused by remember { mutableStateOf(false) }
                    Surface(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                            .clickable { onSetMainStream(stream.id) }
                            .focusable()
                            .onFocusChanged { mainActionFocused = it.isFocused },
                        shape = RoundedCornerShape(7.dp),
                        color = if (isMain) Color.Black.copy(alpha = .28f) else Red.copy(alpha = .18f),
                        border = BorderStroke(2.dp, if (mainActionFocused) White else Color.Transparent)
                    ) {
                        Text(if (isMain) "MAIN" else "SET AS MAIN — REPLACE CURRENT", color = White, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (selected.isEmpty()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(170.dp), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Surface1)) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("CHOOSE YOUR FEEDS", color = White, fontWeight = FontWeight.ExtraBold)
                Text(if (backendStatus.kind == app.f1multiview.media.MultiviewBackendKind.OPEN_TME) "Choose up to 24 logical feeds. Mark any feed as MAIN to replace the current main feed." else "Choose up to 4 feeds. Mark any feed as MAIN to replace the current main feed.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ui.session?.live == true && ui.streams.any { it.kind == StreamKind.TRACK }) {
            Control(false, "TRACKER") {
                val tracker = ui.streams.firstOrNull { it.kind == StreamKind.TRACK }
                if (tracker != null) onSetMainStream(tracker.id)
            }
        }
        if (isTv) {
            Control(tvResizeMode, if (tvResizeMode) "DONE RESIZE" else "RESIZE") {
                tvResizeMode = !tvResizeMode
            }
            Control(false, "SYNC ALL") {
                engine.playAll()
                val mainId = ui.mainStreamId ?: selected.firstOrNull()?.id
                if (mainId != null) engine.syncToMain(mainId)
            }
        }
        Spacer(Modifier.weight(1f))
        run { var fullscreenFocused by remember { mutableStateOf(false) }
        Surface(
            Modifier.clip(RoundedCornerShape(9.dp))
                .clickable { onFullscreenAll() }
                .focusable()
                .onFocusChanged { fullscreenFocused = it.isFocused },
            shape = RoundedCornerShape(9.dp),
            color = Red,
            border = if (fullscreenFocused) BorderStroke(2.dp, White) else null
        ) {
            Text("OPEN MULTIVIEW FULLSCREEN", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = if (compactPhone) 12.dp else 18.dp)) {
        if (backendStatus.kind == app.f1multiview.media.MultiviewBackendKind.OPEN_TME) {
            val protectedSource = ui.streams.firstOrNull { it.id == ui.mainStreamId }?.drmLicenseUrl != null
            val protectedCompositorSupported = remember(protectedSource) {
                !protectedSource || engine.protectedTiledCompositorSupported()
            }
            if (protectedSource && !protectedCompositorSupported) {
                OpenTiledSecureWall(
                    engine = engine,
                    session = ui.tiledMultiviewSession,
                    modifier = Modifier.fillMaxWidth().aspectRatio(wallAspect)
                )
            } else {
                OpenTiledMultiviewWall(
                    engine = engine,
                    feedIds = tiledSelected,
                    protectedSource = protectedSource,
                    modifier = Modifier.fillMaxWidth().aspectRatio(wallAspect)
                )
            }
        } else {
            CanonicalMultiviewLayout(
                ui,
                selected,
                engine,
                errors,
                if (isTv) tvResizeMode else false,
                {},
                modifier = Modifier.fillMaxWidth().aspectRatio(wallAspect)
            )
        }
    }
}

@Composable
private fun OpenTiledMultiviewWall(
    engine: UnifiedMultiviewEngine,
    feedIds: List<String>,
    protectedSource: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val screenshotMode by DebugPresentationSettings.screenshotMode.collectAsState()

    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {

    /*
     * AndroidView owns the View instance it creates. Never return a remembered/external
     * View from factory: doing so allows the same View to retain an old parent and causes
     * "The specified child already has a parent" during Compose's ViewHolder creation.
     * The engine binding follows the AndroidView lifecycle instead.
     */
    if (!screenshotMode) key(protectedSource) {
        AndroidView(
                modifier = Modifier.fillMaxSize(),
            factory = {
                FrameLayout(context).apply {
                    val tiledView = engine.openTiledView(
                        context,
                        protectedOutput = protectedSource
                    )
                    addView(
                        tiledView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    engine.attachOpenTiledView(tiledView)
                }
            },
            update = { container ->
                engine.selectOpenTiledFeeds(feedIds)
            },
            onRelease = { released ->
                val tiledView = released.getChildAt(0) as? OpenTiledCompositorView
                if (tiledView != null) {
                    engine.detachOpenTiledView(tiledView)
                }
                released.removeAllViews()
            }
        )
    }
        if (screenshotMode) {
            ScreenshotPlaceholder(label = "TILED MULTIVIEW", modifier = Modifier.fillMaxSize())
        }
    }
}
@Composable
private fun OpenTiledSecureWall(
    engine: UnifiedMultiviewEngine,
    session: app.f1multiview.core.playback.TiledMultiviewSession?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val screenshotMode by DebugPresentationSettings.screenshotMode.collectAsState()
    if (session == null) return

    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {

    if (!screenshotMode) AndroidView(
            modifier = Modifier.fillMaxSize(),
        factory = {
            FrameLayout(context).apply {
                val secureView = OpenTiledSecureSurfaceView(context)
                secureView.setSession(session)
                addView(
                    secureView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                engine.attachOpenTiledSecureView(secureView)
            }
        },
        update = { container ->
            val secureView = container.getChildAt(0) as? OpenTiledSecureSurfaceView
            if (secureView != null) {
                secureView.setSession(session)
            }
            engine.selectOpenTiledFeeds(session.feedIds.take(24))
        },
        onRelease = { released ->
            val secureView = released.getChildAt(0) as? OpenTiledSecureSurfaceView
            if (secureView != null) {
                engine.detachOpenTiledSecureView(secureView)
            }
            released.removeAllViews()
        }
    )
        if (screenshotMode) {
            ScreenshotPlaceholder(label = "SECURE TILED MULTIVIEW", modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ResizableCompactWall(selected: List<StreamSource>, engine: UnifiedMultiviewEngine, errors: Map<String,String>, onFullscreen:(String)->Unit, editSize:Boolean) {
    var splitX by rememberSaveable { mutableFloatStateOf(.5f) }
    var splitY by rememberSaveable { mutableFloatStateOf(.55f) }
    val gap=7.dp
    if(selected.size==2){
        Row(Modifier.fillMaxWidth().height(300.dp),horizontalArrangement=Arrangement.spacedBy(gap)){
            PlayerTile(selected[0],engine,errors[selected[0].id],Modifier.weight(splitX).fillMaxHeight(),onFullscreen)
            ResizeHandle(Orientation.Horizontal,editSize){delta->splitX=(splitX+delta/700f).coerceIn(.25f,.75f)}
            PlayerTile(selected[1],engine,errors[selected[1].id],Modifier.weight(1f-splitX).fillMaxHeight(),onFullscreen)
        }
    } else {
        Column(Modifier.fillMaxWidth().height(390.dp),verticalArrangement=Arrangement.spacedBy(gap)){
            PlayerTile(selected[0],engine,errors[selected[0].id],Modifier.weight(splitY).fillMaxWidth(),onFullscreen)
            ResizeHandle(Orientation.Vertical,editSize){delta->splitY=(splitY+delta/900f).coerceIn(.28f,.72f)}
            Row(Modifier.weight(1f-splitY).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)){
                PlayerTile(selected[1],engine,errors[selected[1].id],Modifier.weight(splitX).fillMaxHeight(),onFullscreen)
                ResizeHandle(Orientation.Horizontal,editSize){delta->splitX=(splitX+delta/700f).coerceIn(.2f,.8f)}
                PlayerTile(selected[2],engine,errors[selected[2].id],Modifier.weight(1f-splitX).fillMaxHeight(),onFullscreen)
                if(selected.size>3){                    // Additional feeds stay visible in a second compact row.
                }
            }
        }
        if(selected.size>3){
            Row(Modifier.fillMaxWidth().height(190.dp).padding(top=gap),horizontalArrangement=Arrangement.spacedBy(gap)){
                selected.drop(3).forEach{stream->PlayerTile(stream,engine,errors[stream.id],Modifier.weight(1f).fillMaxHeight(),onFullscreen)}
            }
        }
    }
}
@Composable
private fun ResizableSplitWall(selected:List<StreamSource>,engine:UnifiedMultiviewEngine,errors:Map<String,String>,onFullscreen:(String)->Unit,editSize:Boolean){
    if(selected.size<2)return
    var split by rememberSaveable { mutableFloatStateOf(.5f) }
    Row(Modifier.fillMaxWidth().height(360.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        PlayerTile(selected[0],engine,errors[selected[0].id],Modifier.weight(split).fillMaxHeight(),onFullscreen)
        ResizeHandle(Orientation.Horizontal,editSize){delta->split=(split+delta/900f).coerceIn(.25f,.75f)}
        PlayerTile(selected[1],engine,errors[selected[1].id],Modifier.weight(1f-split).fillMaxHeight(),onFullscreen)
    }
}
@Composable
private fun ResizableDesktopWall(selected:List<StreamSource>,engine:UnifiedMultiviewEngine,errors:Map<String,String>,onFullscreen:(String)->Unit,editSize:Boolean){
    var mainWeight by rememberSaveable { mutableFloatStateOf(.62f) }
    var h1 by rememberSaveable { mutableFloatStateOf(.34f) }
    var h2 by rememberSaveable { mutableFloatStateOf(.33f) }
    val side=selected.drop(1)
    if(side.isEmpty()){PlayerTile(selected[0],engine,errors[selected[0].id],Modifier.fillMaxWidth().height(430.dp),onFullscreen);return}
    Row(Modifier.fillMaxWidth().height(430.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        PlayerTile(selected[0],engine,errors[selected[0].id],Modifier.weight(mainWeight).fillMaxHeight(),onFullscreen)
        ResizeHandle(Orientation.Horizontal,editSize){delta->mainWeight=(mainWeight+delta/900f).coerceIn(.35f,.78f)}
        Column(Modifier.weight(1f-mainWeight).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(7.dp)){
            if(side.size>=1)PlayerTile(side[0],engine,errors[side[0].id],Modifier.weight(h1).fillMaxWidth(),onFullscreen)
            if(side.size>=2){
                ResizeHandle(Orientation.Vertical,editSize){delta->h1=(h1+delta/700f).coerceIn(.18f,.62f);h2=(h2-delta/700f).coerceIn(.18f,.62f)}
                PlayerTile(side[1],engine,errors[side[1].id],Modifier.weight(h2).fillMaxWidth(),onFullscreen)
            }
            if(side.size>=3){
                ResizeHandle(Orientation.Vertical,editSize){delta->h2=(h2+delta/700f).coerceIn(.18f,.62f)}
                PlayerTile(side[2],engine,errors[side[2].id],Modifier.weight((1f-h1-h2).coerceIn(.12f,.64f)).fillMaxWidth(),onFullscreen)
            }
            side.drop(3).forEach{stream->PlayerTile(stream,engine,errors[stream.id],Modifier.weight(1f).fillMaxWidth(),onFullscreen)}
        }
    }
}
@Composable
private fun ResizeHandle(
    orientation: Orientation,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    onDelta: (Float) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val step = 28f
    val modifier = Modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .then(
            if (enabled) {
                Modifier
                    .focusable()
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false
                        else {
                            val delta = when (event.key) {
                                Key.DirectionLeft -> if (orientation == Orientation.Horizontal) -step else null
                                Key.DirectionRight -> if (orientation == Orientation.Horizontal) step else null
                                Key.DirectionUp -> if (orientation == Orientation.Vertical) -step else null
                                Key.DirectionDown -> if (orientation == Orientation.Vertical) step else null
                                else -> null
                            }
                            if (delta != null) { onDelta(delta); true } else false
                        }
                    }
            } else Modifier
        )
        .then(
            if (orientation == Orientation.Horizontal) {
                Modifier.width(10.dp).fillMaxHeight()
            } else Modifier.height(10.dp).fillMaxWidth()
        )
        .draggable(
            orientation = orientation,
            enabled = enabled,
            state = rememberDraggableState { onDelta(it) }
        )
        .background(if (enabled) Red.copy(alpha = .75f) else Color.White.copy(alpha = .08f))
        .then(if (focused) Modifier.border(2.dp, White, RoundedCornerShape(3.dp)) else Modifier)

    Box(modifier, contentAlignment = Alignment.Center) {
        if (enabled) {
            Text(
                if (orientation == Orientation.Horizontal) "⋮" else "⋯",
                color = White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun ScreenshotPlaceholder(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF101116),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SCREENSHOT MODE", color = White.copy(alpha = .55f), fontSize = 9.sp, fontWeight = FontWeight.Black)
                Text("VIDEO", color = White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 3.dp))
                Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun F1HdrPlayerSurface(
    engine: UnifiedMultiviewEngine,
    player: EnginePlayerHandle,
    stream: StreamSource,
    modifier: Modifier,
    source: String
) {
    val context = LocalContext.current
    val screenshotMode by DebugPresentationSettings.screenshotMode.collectAsState()
    val decoderGeneration by engine.decoderGeneration.collectAsState()

    if (screenshotMode) {
        ScreenshotPlaceholder(
            label = stream.driver?.takeIf { it.isNotBlank() } ?: stream.title,
            modifier = modifier
        )
        return
    }

    LaunchedEffect(player.id, decoderGeneration, source) {
        if (engine.hasDecoder(player.id)) {
            engine.updateSurface(player.id, player, source)
        }
    }

    key(player.id) {
        AndroidView(
            modifier = modifier,
            factory = { FrameLayout(context) },
            update = { container ->
                engine.attachSurface(
                    feedId = player.id,
                    player = player,
                    stream = stream,
                    source = source,
                    container = container,
                    screenshotMode = false
                )
            },
            onRelease = { released ->
                engine.detachSurface(player.id, player, released)
            }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerTile(stream: StreamSource, engine: UnifiedMultiviewEngine, error: String?, modifier: Modifier, onFullscreen: (String) -> Unit, onFocus: ((String) -> Unit)? = null, active: Boolean = false, surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW, showOverlay: Boolean = true) {
    val player = remember(stream.id) { engine.player(stream.id) }
    val context = LocalContext.current
    val activity = context as? Activity
    val displayHdr = displaySupportsHdr(context)
    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var ready by remember(stream.id) { mutableStateOf(player.playbackState == Player.STATE_READY) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { ready = state == Player.STATE_READY }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var tileFocused by remember { mutableStateOf(false) }
    val tileModifier = if (onFocus != null) {
        modifier.clickable { onFocus(stream.id) }.focusable().onFocusChanged { tileFocused = it.isFocused }
            .then(if (tileFocused) Modifier.border(2.dp, White, RoundedCornerShape(10.dp)) else Modifier)
    } else modifier
    // Active and focused are intentionally different:
    // - focus = where the TV/D-pad cursor is
    // - active = the feed currently targeted by multiview controls
    // Keep the active indicator subtle so it does not compete with the video.
    Card(
        tileModifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            F1HdrPlayerSurface(engine = engine, player = player, stream = stream, modifier = Modifier.fillMaxSize(), source = "multiview-" + stream.id)
            if (stream.url == null && error == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stream.title, color = White, fontWeight = FontWeight.Bold)
                    Text("CONNECTING…", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (showOverlay) {
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color.Black.copy(alpha = .58f)).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) {
                    Text(if (stream.isLive) "LIVE" else "REPLAY", color = White, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(7.dp))
                if (active) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(Red, RoundedCornerShape(50))
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(if (engine.isMuted(stream.id)) "MUTED" else "AUDIO ON", color = if (engine.isMuted(stream.id)) Color.White.copy(alpha = .42f) else Color(0xFF66E07A), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(when { error != null -> "ERROR"; !ready -> "LOADING"; playing -> "PLAYING"; else -> "PAUSED" }, color = if (error != null) Color(0xFFFF7777) else Color.White.copy(alpha = .6f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))

            }
            }
            if (error != null) {
                Surface(Modifier.align(Alignment.Center).padding(12.dp), shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = .92f), border = BorderStroke(1.dp, Red.copy(alpha = .65f))) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYBACK UNAVAILABLE", color = White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text(error.replace("PlaybackException: ", "").replace("Source error", "Source unavailable"), color = Muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        run { var retryFocused by remember { mutableStateOf(false) }
                        Surface(
                            Modifier.clickable { engine.clear(stream.id); engine.load(stream); engine.play(stream.id) }.focusable()
                                .onFocusChanged { retryFocused = it.isFocused },
                            color = Red,
                            shape = RoundedCornerShape(50),
                            border = if (retryFocused) BorderStroke(2.dp, White) else null
                        ) {
                            Text("RETRY", color = White, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(UnstableApi::class)
@Composable
private fun FullscreenFeedRail(
    ui: UiState,
    engine: UnifiedMultiviewEngine,
    vm: MultiViewViewModel,
    activeId: String,
    onSwitchStream: (String) -> Unit,
    modifier: Modifier
) {
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val candidates = remember(ui.streams) { ui.streams }
    val listState = rememberLazyListState()
    val previewIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(activeId, listState.firstVisibleItemIndex, candidates, ui.streams) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val visibleIds = visibleItems
            .mapNotNull { candidates.getOrNull(it.index)?.id }
            .filter { id ->
                candidates.firstOrNull { it.id == id }?.kind !in
                    setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK)
            }
            .take(4)

        val firstVisibleIndex = visibleItems.minOfOrNull { it.index } ?: listState.firstVisibleItemIndex
        val lastVisibleIndex = visibleItems.maxOfOrNull { it.index } ?: listState.firstVisibleItemIndex
        val preloadIds = (firstVisibleIndex - 2..lastVisibleIndex + 2)
            .mapNotNull { candidates.getOrNull(it)?.id }
            .distinct()

        previewIds.filterNot { it in preloadIds }.toList().forEach { previewIds.remove(it) }
        preloadIds.forEach { id ->
            if (id !in previewIds) previewIds.add(id)
            vm.prepareStream(id)
        }

        val activeStream = candidates.firstOrNull { it.id == activeId }
        if (activeStream != null &&
            activeStream.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK) &&
            activeStream.url == null
        ) {
            vm.prepareStream(activeId)
        }

        val viewportStreams = candidates
            .filter { it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK) }
            .filter { it.id == activeId || it.id in preloadIds }

        val referenceId = activeId.takeIf { id ->
            candidates.firstOrNull { it.id == id }?.kind !in
                setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK)
        }

        engine.updateViewport(
            streams = viewportStreams,
            visibleIds = visibleIds.toSet(),
            referenceId = referenceId,
            autoplay = true
        )
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF0D0E12),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .06f))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(candidates, key = { it.id }) { candidate ->
                val active = candidate.id == activeId
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .height(if (isTv) 88.dp else 78.dp)
                        .clickable { onSwitchStream(candidate.id) }
                        .focusable(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) Color(0xFF251619) else Color(0xFF15161B),
                    border = BorderStroke(
                        1.dp,
                        if (active) HomeFeedRed else Color.White.copy(alpha = .055f)
                    )
                ) {
                    Box(Modifier.fillMaxSize()) {
                        val previewPlayer = remember(candidate.id) { engine.player(candidate.id) }
                        when {
                            candidate.kind == StreamKind.TRACK_MAP -> Box(Modifier.fillMaxSize()) { TrackMapPanel(ui, isTv) }
                            candidate.kind == StreamKind.F1_DASH_DATA -> Box(Modifier.fillMaxSize()) { F1DashDataFeed(ui, isTv) }
                            candidate.url != null -> {
                                F1HdrPlayerSurface(
                                    engine = engine,
                                    player = previewPlayer,
                                    stream = candidate,
                                    modifier = Modifier.fillMaxSize(),
                                    source = "feed-rail-" + candidate.id
                                )
                            }
                            else -> F1Artwork(
                                url = null,
                                title = candidate.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                fallbackSeed = candidate.id
                            )
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = .74f))
                                    )
                                )
                        )

                        Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                            Text(
                                candidate.driver?.takeIf { it.isNotBlank() } ?: candidate.title,
                                color = White,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    active -> "ACTIVE"
                                    candidate.kind == StreamKind.TRACK_MAP -> "TRACKER"
                                    candidate.kind == StreamKind.F1_DASH_DATA -> "DATA"
                                    candidate.url != null -> "PREVIEW"
                                    else -> "LOAD"
                                },
                                color = if (active) White else White.copy(alpha = .54f),
                                fontSize = 5.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }

                        if (candidate.isLive) {
                            Surface(
                                Modifier.align(Alignment.TopStart).padding(4.dp),
                                color = HomeFeedRed,
                                shape = RoundedCornerShape(3.dp)
                            ) {
                                Text(
                                    "LIVE",
                                    color = White,
                                    fontSize = 5.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenMultiview(
    ui: UiState,
    vm: MultiViewViewModel,
    engine: UnifiedMultiviewEngine,
    errors: Map<String, String>,
    onClose: () -> Unit,
    onReplayPosition: (Long) -> Unit
) {
    val selected = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id } }.take(4)
    val openTiled = engine.multiviewBackendStatus().kind == app.f1multiview.media.MultiviewBackendKind.OPEN_TME
    val tiledSelected = ui.selectedTiledFeedIds.ifEmpty { ui.tiledMultiviewSession?.feedIds?.take(24).orEmpty() }.take(24)
    val context = LocalContext.current
    val displayHdr = displaySupportsHdr(context)
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var railOpen by rememberSaveable { mutableStateOf(true) }
    var activeFeedId by rememberSaveable { mutableStateOf(ui.mainStreamId ?: selected.firstOrNull()?.id) }
    var menu by rememberSaveable { mutableStateOf<String?>(null) }
    var speed by rememberSaveable(activeFeedId) { mutableFloatStateOf(1f) }
    var quality by remember(activeFeedId, engine) {
        mutableStateOf(activeFeedId?.let(engine::getQuality) ?: Quality.AUTO)
    }
    var fit by rememberSaveable(activeFeedId) { mutableStateOf(false) }
    var trackVersion by remember { mutableIntStateOf(0) }
    val fullscreenBackFocusRequester = remember { FocusRequester() }
    val fullscreenShowControlsFocusRequester = remember { FocusRequester() }
    var fullscreenBackFocused by remember { mutableStateOf(false) }
    var fullscreenShowControlsFocused by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            menu != null -> menu = null
            controlsVisible -> controlsVisible = false
            railOpen -> railOpen = false
            else -> onClose()
        }
    }

    LaunchedEffect(activeFeedId, ui.streams) {
        val id = activeFeedId ?: return@LaunchedEffect
        val stream = ui.streams.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (stream.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK)) {
            engine.setAudioPlayer(id)
        }
    }

    val active = ui.streams.firstOrNull { it.id == activeFeedId } ?: selected.firstOrNull()
    val activePlayer = active?.takeIf {
        it.kind !in setOf(StreamKind.TIMING, StreamKind.TRACK, StreamKind.TRACK_MAP, StreamKind.F1_DASH, StreamKind.F1_DASH_DATA)
    }?.let { engine.player(it.id) }

    LaunchedEffect(activePlayer, ui.session?.live) {
        if (activePlayer != null && ui.session?.live == false) {
            while (true) {
                onReplayPosition(activePlayer.currentPosition)
                delay(500L)
            }
        }
    }

    DisposableEffect(activePlayer) {
        if (activePlayer == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackVersion++ }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    val audioTracks = remember(activePlayer, trackVersion) {
        activePlayer?.currentTracks?.groups?.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) emptyList()
            else (0 until group.length).mapNotNull { index ->
                if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, group.getTrackFormat(index))
            }
        } ?: emptyList()
    }
    val textTracks = remember(activePlayer, trackVersion) {
        activePlayer?.currentTracks?.groups?.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) emptyList()
            else (0 until group.length).mapNotNull { index ->
                if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, group.getTrackFormat(index))
            }
        } ?: emptyList()
    }

    LaunchedEffect(controlsVisible) {
        delay(80L)
        if (controlsVisible) fullscreenBackFocusRequester.requestFocus()
        else fullscreenShowControlsFocusRequester.requestFocus()
    }

    LaunchedEffect(controlsVisible, menu) {
        if (controlsVisible && menu == null) {
            delay(5_000L)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter && !controlsVisible) {
                    controlsVisible = true
                    true
                } else false
            }
            .pointerInput(controlsVisible, menu) {
                detectTapGestures {
                    if (menu == null) controlsVisible = !controlsVisible
                }
            }
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(top = if (controlsVisible) 52.dp else 0.dp, bottom = if (controlsVisible) 80.dp else 0.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 7.dp, end = if (railOpen) 3.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            ) {
                val activeStream = active ?: ui.streams.firstOrNull()
                if (activeStream != null) {
                    if (openTiled) {
                        OpenTiledMultiviewWall(
                            engine = engine,
                            feedIds = tiledSelected,
                            protectedSource = ui.streams.firstOrNull { it.id == ui.mainStreamId }?.drmLicenseUrl != null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        MultiviewFeedTile(
                            stream = activeStream,
                            ui = ui,
                            engine = engine,
                            error = errors[activeStream.id],
                            modifier = Modifier.fillMaxSize(),
                            onFocus = { activeFeedId = it; menu = null },
                            active = true,
                            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                            showOverlay = false
                        )
                    }
                }
            }

            if (railOpen) {
                FullscreenFeedRail(
                    ui = ui,
                    engine = engine,
                    vm = vm,
                    activeId = activeFeedId ?: ui.mainStreamId.orEmpty(),
                    onSwitchStream = { id ->
                        activeFeedId = id
                        menu = null
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.29f)
                        .padding(start = 3.dp, end = 7.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                Spacer(Modifier.width(30.dp))
            }
        }

        RailCollapseButton(
            open = railOpen,
            onClick = { railOpen = !railOpen; menu = null }
        )

        if (controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = .68f),
                                Color.Black.copy(alpha = .20f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(Modifier.fillMaxWidth().focusGroup(), verticalAlignment = Alignment.CenterVertically) {
                    ChromeButton(
                        label = "‹",
                        onClick = onClose,
                        focusRequester = fullscreenBackFocusRequester,
                        onFocus = { fullscreenBackFocused = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("MULTIVIEW", color = White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(4.dp))
                    Text(if (openTiled) "TILED" else "DIRECTOR", color = Muted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    CompactLayoutPicker(selected = ui.layout, onSelect = vm::setLayout)
                    Spacer(Modifier.width(7.dp))
                    SmallPlayerButton("SYNC") {
                        engine.playAll()
                        val mainId = active?.takeIf {
                            it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA, StreamKind.TIMING, StreamKind.TRACK)
                        }?.id
                        if (mainId != null) {
                            engine.setAudioPlayer(mainId)
                            engine.syncToMain(mainId)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    SmallPlayerButton("HIDE") { controlsVisible = false }
                }
            }
        } else {
            Box(Modifier.align(Alignment.TopStart).padding(9.dp).zIndex(21f)) {
                ChromeButton(
                    label = "⌄",
                    onClick = { controlsVisible = true },
                    focusRequester = fullscreenShowControlsFocusRequester,
                    onFocus = { fullscreenShowControlsFocused = it }
                )
            }
        }

        if (activePlayer != null && controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .42f), Color.Black.copy(alpha = .78f))
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                FullscreenFeedControls(
                    stream = active!!,
                    player = activePlayer,
                    engine = engine,
                    audioTracks = audioTracks,
                    textTracks = textTracks,
                    speed = speed,
                    quality = quality,
                    fit = fit,
                    menu = menu,
                    displayHdr = displayHdr,
                    onReplayPosition = onReplayPosition,
                    onSpeed = { speed = it },
                    onQuality = { quality = it },
                    onFit = { fit = it },
                    onMenu = { menu = it },
                    onMute = { muted -> engine.setMuted(active!!.id, muted) },
                    muted = engine.isMuted(active!!.id),
                    playing = activePlayer.isPlaying,
                    onPlay = { activePlayer.play() },
                    onPause = { activePlayer.pause() },
                    onSeek = { delta -> activePlayer.seekTo((activePlayer.currentPosition + delta).coerceAtLeast(0L)) }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(stream: StreamSource, ui: UiState, engine: UnifiedMultiviewEngine, error: String?, vm: MultiViewViewModel, onSwitchStream: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val displayHdr = displaySupportsHdr(context)
    val player = remember(stream.id) { engine.player(stream.id) }

    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var ready by remember(stream.id) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var position by remember(stream.id) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(stream.id) { mutableLongStateOf(player.duration.takeIf { it > 0 } ?: 0L) }
    var controlsVisible by rememberSaveable(stream.id) { mutableStateOf(true) }
    var speed by rememberSaveable(stream.id) { mutableFloatStateOf(1f) }
    var quality by remember(stream.id, engine) {
        mutableStateOf(engine.getQuality(stream.id))
    }
    var fit by rememberSaveable(stream.id) { mutableStateOf(false) }
    var muted by rememberSaveable(stream.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var channelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var trackVersion by remember { mutableIntStateOf(0) }
    val fullscreenBackFocusRequester = remember { FocusRequester() }
    val fullscreenShowControlsFocusRequester = remember { FocusRequester() }
    var fullscreenBackFocused by remember { mutableStateOf(false) }
    var fullscreenShowControlsFocused by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            menu != null -> menu = null
            channelPickerOpen -> channelPickerOpen = false
            controlsVisible -> controlsVisible = false
            else -> onClose()
        }
    }

    LaunchedEffect(controlsVisible) {
        delay(80L)
        if (controlsVisible) fullscreenBackFocusRequester.requestFocus()
        else fullscreenShowControlsFocusRequester.requestFocus()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { ready = state == Player.STATE_READY }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackVersion++ }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                duration = player.duration.takeIf { it > 0 } ?: 0L            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }

    val audioTracks = remember(trackVersion) {
        player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) emptyList()
            else (0 until group.length).mapNotNull { index ->
                val f = group.getTrackFormat(index)
                if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, f)
            }
        }
    }
    val textTracks = remember(trackVersion) {
        player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) emptyList()
            else (0 until group.length).mapNotNull { index ->
                val f = group.getTrackFormat(index)
                if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, f)
            }
        }
    }

    fun trackLabel(format: androidx.media3.common.Format, fallback: String): String =
        format.label ?: format.language?.uppercase() ?: fallback

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            val videoWidth = player.videoSize.width.coerceAtLeast(16)
            val videoHeight = player.videoSize.height.coerceAtLeast(9)
            val ratio = Rational(videoWidth, videoHeight)
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter && !controlsVisible) {
                    controlsVisible = true
                    true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } },
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.73f).fillMaxHeight()) {
                F1HdrPlayerSurface(
                    engine = engine,
                    player = player,
                    stream = stream,
                    modifier = Modifier.fillMaxSize(),
                    source = "fullscreen-" + stream.id
                )
            }
            if (ui.streams.count { it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA) } > 1) {
                FullscreenFeedRail(
                    ui = ui,
                    engine = engine,
                    vm = vm,
                    activeId = stream.id,
                    onSwitchStream = onSwitchStream,
                    modifier = Modifier.weight(0.27f).fillMaxHeight()
                )
            }
        }

        if (!controlsVisible) {
            Surface(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .focusRequester(fullscreenShowControlsFocusRequester)
                    .clickable { controlsVisible = true }
                    .focusable()
                    .onFocusChanged { fullscreenShowControlsFocused = it.isFocused }
                    .border(2.dp, if (fullscreenShowControlsFocused) White else Color.Transparent, RoundedCornerShape(9.dp)),
                color = Color.Black.copy(alpha = .78f),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))
            ) {
                Text("SHOW CONTROLS", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
            }
        }

        if (channelPickerOpen) {
            Surface(
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).fillMaxWidth(0.92f),
                color = Color(0xFF101116).copy(alpha = .98f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
            ) {
                LazyRow(contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier) {
                    items(ui.streams) { candidate ->
                        val active = candidate.id == stream.id
                        Control(active, candidate.driver?.takeIf { it.isNotBlank() } ?: candidate.title) {
                            channelPickerOpen = false
                            onSwitchStream(candidate.id)
                        }
                    }
                }
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.72f), Color.Transparent, Color.Black.copy(alpha=.90f))))) {
                 Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(14.dp).focusGroup(), horizontalArrangement=Arrangement.spacedBy(7.dp), verticalAlignment=Alignment.CenterVertically) {
                     Surface(
                         Modifier
                             .focusRequester(fullscreenBackFocusRequester)
                             .clickable { onClose() }
                             .focusable()
                             .onFocusChanged { fullscreenBackFocused = it.isFocused }
                             .border(2.dp, if (fullscreenBackFocused) White else Color.Transparent, RoundedCornerShape(9.dp)),
                         color=Color.Black.copy(alpha=.65f),
                         shape=RoundedCornerShape(9.dp)
                     ) {
                         Text("‹  BACK", color=White, fontSize=10.sp, fontWeight=FontWeight.Black, modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp))
                     }
                     Spacer(Modifier.width(12.dp))
                     Surface(color=Red, shape=RoundedCornerShape(4.dp)) {
                         Text(if(stream.isLive)"LIVE" else "REPLAY", color=White, fontSize=8.sp, fontWeight=FontWeight.Black, modifier=Modifier.padding(horizontal=7.dp,vertical=5.dp))
                     }
                     Spacer(Modifier.width(8.dp))
                     Text(stream.driver?.takeIf{it.isNotBlank()}?:stream.title,color=White,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                     Spacer(Modifier.weight(1f))
                     SmallPlayerButton("CHANNEL"){ channelPickerOpen = !channelPickerOpen; menu = null }
                     Spacer(Modifier.width(6.dp))
                     SmallPlayerButton("PIP"){enterPip()}
                     Spacer(Modifier.width(6.dp))
                     SmallPlayerButton(if (fit) "FIT" else "FILL"){ fit=!fit; player.videoScalingMode = if (fit) C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING }
                     Spacer(Modifier.width(6.dp))
                     SmallPlayerButton("HIDE"){ controlsVisible = false }
                 }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp).focusGroup()) {
                    if(duration>0L){
                        Slider(
                            value=position.toFloat().coerceIn(0f,duration.toFloat()),
                            onValueChange={position=it.toLong()},
                            onValueChangeFinished={player.seekTo(position.coerceIn(0L,duration))},
                            valueRange=0f..duration.toFloat(),
                            colors=SliderDefaults.colors(thumbColor=Red,activeTrackColor=Red,inactiveTrackColor=Color.White.copy(alpha=.28f))
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Text("LIVE",color=Red,fontSize=8.sp,fontWeight=FontWeight.Black)
                            Spacer(Modifier.weight(1f))
                            run { var goLiveFocused by remember { mutableStateOf(false) }
                            Surface(
                                Modifier.clickable { player.seekToDefaultPosition(); player.play() }.focusable()
                                    .onFocusChanged { goLiveFocused = it.isFocused },
                                color = Red,
                                shape = RoundedCornerShape(50),
                                border = if (goLiveFocused) BorderStroke(2.dp, White) else null
                            ){
                                Text("GO LIVE",color=White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=11.dp,vertical=7.dp))
                            }
                            }
                        }
                    }
                    LazyRow(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement=Arrangement.spacedBy(7.dp), verticalAlignment=Alignment.CenterVertically){
                        item { PlayerControlButton("↶ 10"){player.seekTo(max(0L,player.currentPosition-10_000L))} }
                        item { PlayerControlButton(if(playing)"PAUSE" else "PLAY"){if(playing)player.pause()else player.play()} }
                        item { PlayerControlButton("10 ↷"){player.seekTo(player.currentPosition+10_000L)} }
                        item { PlayerControlButton(if(muted)"MUTE" else "SOUND"){muted=!muted;player.volume=if(muted)0f else 1f} }
                        item { MenuButton("SPEED  " + "%.2f".format(speed)){menu=if(menu=="speed")null else "speed"} }
                        item { MenuButton("QUALITY  " + quality.label()){menu=if(menu=="quality")null else "quality"} }
                        item { MenuButton("AUDIO"){menu=if(menu=="audio")null else "audio"} }
                        item { MenuButton("SUBS"){menu=if(menu=="text")null else "text"} }
                        item { MenuButton("MORE"){menu=if(menu=="more")null else "more"} }
                    }

                    if(menu!=null){
                        Surface(Modifier.fillMaxWidth().padding(top=8.dp),shape=RoundedCornerShape(12.dp),color=Color(0xFF17181F).copy(alpha=.98f),border=BorderStroke(1.dp,Color.White.copy(alpha=.12f))){
                            Column(Modifier.padding(10.dp)){
                                when(menu){
                                    "speed"->{
                                        Text("PLAYBACK SPEED",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach{v->Control(speed==v,""+v+"x"){speed=v;player.setPlaybackParameters(PlaybackParameters(v));menu=null}}
                                        }
                                    }
                                    "quality"->{
                                        val resolutions = engine.availableVideoResolutionsForQualityMenu(stream.id)
                                        val diagnostics = engine.currentVideoDiagnostics(stream.id)
                                        Text("VIDEO QUALITY",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        Text(
                                            (
                                                if (diagnostics != null) {
                                                    "ACTIVE  " + diagnostics.width + "×" + diagnostics.height +
                                                        if (diagnostics.hdr) "  •  HDR" else "  •  SDR"
                                                } else if (resolutions.isNotEmpty()) {
                                                    "AVAILABLE  " + resolutions.joinToString { it.first.toString() + "×" + it.second }
                                                } else "TRACKS NOT READY"
                                            ) + if (diagnostics?.hdr == true) {
                                                if (displayHdr) "  •  DISPLAY HDR" else "  •  DISPLAY SDR"
                                            } else "",
                                            color=White.copy(alpha=.72f), fontSize=7.sp, fontWeight=FontWeight.Bold,
                                            modifier=Modifier.padding(top=5.dp)
                                        )
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            listOf(Quality.AUTO to "Auto",Quality.UHD to "4K",Quality.FHD to "1080p",Quality.HD to "720p",Quality.SD to "480p").forEach{(q,l)->
                                                val available = engine.qualityAvailable(stream.id,q)
                                                Control(quality==q, if (available || q==Quality.AUTO) l else "$l — N/A") {
                                                    if (available || q==Quality.AUTO) {
                                                        quality=q
                                                        engine.setQuality(stream.id,q)
                                                        menu=null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "audio"->{                                        Text("AUDIO TRACKS",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        if(audioTracks.isEmpty())Text("No alternate audio tracks reported by F1 TV.",color=Muted,fontSize=9.sp,modifier=Modifier.padding(top=7.dp))
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            audioTracks.forEachIndexed{index,(gi,ti,f)->Control(false,trackLabel(f,"Audio "+(index+1))){player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[gi].mediaTrackGroup,ti)).build();menu=null}}
                                        }
                                    }
                                    "text"->{
                                        Text("SUBTITLES",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            Control(false,"Off"){player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build();menu=null}
                                            textTracks.forEachIndexed{index,(gi,ti,f)->Control(false,trackLabel(f,"Subtitle "+(index+1))){player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[gi].mediaTrackGroup,ti)).build();menu=null}}
                                        }
                                    }
                                    "more"->{
                                        Text("PLAYER",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            Control(false,"PIP"){enterPip();menu=null}
                                            Control(false,if(fit)"FIT" else "FILL"){fit=!fit;player.videoScalingMode = if (fit) C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING;menu=null}
                                            Control(false,"RETRY"){player.prepare();player.play();menu=null}
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(formatPosition(position)+(if(duration>0L)" / "+formatPosition(duration) else "  •  LIVE"),color=Color.White.copy(alpha=.72f),fontSize=8.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=2.dp))
                }
            }
        }
    }
}
@Composable private fun SmallPlayerButton(label:String,onClick:()->Unit){
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).focusable().onFocusChanged { focused = it.isFocused },
        color=Color.Black.copy(alpha=.65f),
        shape=RoundedCornerShape(8.dp),
        border=if (focused) BorderStroke(3.dp, White) else null
    ){
        Text(label,color=White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=10.dp,vertical=7.dp))
    }
}
@Composable
private fun RailCollapseButton(open: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier
            .align(Alignment.CenterEnd)
            .width(30.dp)
            .height(64.dp)
            .clickable(onClick = onClick)
            .focusable()
            .zIndex(25f),
        color = Color.Black.copy(alpha = .68f),
        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (open) "›" else "‹", color = White, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun ChromeButton(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier
            .size(30.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged {
                focused = it.isFocused
                onFocus?.invoke(it.isFocused)
            },
        color = Color.Black.copy(alpha = .54f),
        shape = RoundedCornerShape(50.dp),
        border = BorderStroke(1.dp, if (focused) White else Color.White.copy(alpha = .10f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = White, fontSize = 15.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun CompactLayoutPicker(selected: LayoutPreset, onSelect: (LayoutPreset) -> Unit) {
    Surface(
        color = Color.White.copy(alpha = .13f),
        shape = RoundedCornerShape(50.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .06f))
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(LayoutPreset.SINGLE, LayoutPreset.SPLIT_2, LayoutPreset.GRID_4, LayoutPreset.GRID_6).forEach { preset ->
                Surface(
                    Modifier
                        .size(30.dp, 24.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .clickable { onSelect(preset) }
                        .focusable(),
                    color = if (selected == preset) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                        LayoutGlyph(preset, selected == preset)
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerControlButton(label:String,onClick:()->Unit){
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick=onClick).focusable().onFocusChanged { focused = it.isFocused },
        color=if(label=="PAUSE"||label=="PLAY")Red else Color.White.copy(alpha=.12f),
        shape=RoundedCornerShape(50),
        border=if (focused) BorderStroke(2.dp, White) else null
    ){
        Text(label,color=White,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=11.dp,vertical=8.dp))
    }
}
@Composable private fun MenuButton(label:String,onClick:()->Unit){
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).focusable().onFocusChanged { focused = it.isFocused },
        color=Color.White.copy(alpha=.08f),
        shape=RoundedCornerShape(8.dp),
        border=if (focused) BorderStroke(2.dp, White) else null
    ){
        Text(label,color=White,fontSize=8.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(horizontal=9.dp,vertical=8.dp))
    }
}
private fun formatPosition(ms:Long):String{
    val total=(ms/1000L).coerceAtLeast(0L);val h=total/3600L;val m=(total%3600L)/60L;val s=total%60L
    return if(h>0) "%d:%02d:%02d".format(h,m,s) else "%02d:%02d".format(m,s)
}

private fun Quality.label(): String = when (this) {
    Quality.AUTO -> "AUTO"
    Quality.UHD -> "4K"
    Quality.FHD -> "1080"
    Quality.HD -> "720"
    Quality.SD -> "480"
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        color = Color(0xFF260F11),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, Red.copy(alpha = .35f))
    ) {
        Text(message, color = Color(0xFFFFB4B4), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(11.dp))
    }
}

@Composable
private fun LayoutOption(preset: LayoutPreset, selected: Boolean, onClick: () -> Unit) {
    run { var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.size(width = 50.dp, height = 38.dp).clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Red else Surface2,
        border = if (focused && !selected) BorderStroke(2.dp, White) else if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = .08f))
    ) {
        Box(Modifier.padding(7.dp), contentAlignment = Alignment.Center) { LayoutGlyph(preset, selected) }
    }
    }
}

@Composable
private fun LayoutGlyph(preset: LayoutPreset, selected: Boolean) {
    val c = if (selected) Color.Black.copy(alpha = .9f) else Color.White.copy(alpha = .9f)
    val gap = 2.dp
    when (preset) {
        LayoutPreset.SINGLE -> Box(Modifier.fillMaxSize().border(1.dp, c, RoundedCornerShape(2.dp)))
        LayoutPreset.SPLIT_2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.weight(1f).fillMaxHeight().border(2.dp, c, RoundedCornerShape(2.dp)))
            Box(Modifier.weight(1f).fillMaxHeight().border(2.dp, c, RoundedCornerShape(2.dp)))
        }
        LayoutPreset.GRID_4 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.weight(2f).fillMaxHeight().border(2.dp, c, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(3) { Box(Modifier.weight(1f).fillMaxWidth().border(2.dp, c, RoundedCornerShape(2.dp))) }
            }
        }
        LayoutPreset.GRID_6 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.weight(3f).fillMaxHeight().border(2.dp, c, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { Box(Modifier.weight(1f).fillMaxWidth().border(2.dp, c, RoundedCornerShape(2.dp))) }
            }
            Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(3) { Box(Modifier.weight(1f).fillMaxWidth().border(2.dp, c, RoundedCornerShape(2.dp))) }
            }
        }    }
}

@Composable
private fun Control(selected: Boolean, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Red else Surface2,
        border = if (focused) BorderStroke(3.dp, White) else if (selected) BorderStroke(1.dp, White.copy(alpha = .55f)) else null
    ) {
        Text(label, color = White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).focusable().onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = .07f),
        border = if (focused) BorderStroke(3.dp, White) else null) {
        Text(label, color = White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun DividerV() {
    Box(Modifier.width(1.dp).height(22.dp).background(Color.White.copy(alpha = .1f)))
}

private fun prettyEvent(name: String): String {
    return name.replace("FORMULA 1", "F1", ignoreCase = true)
        .replace("ULA 1", "F1", ignoreCase = true)
        .replace(Regex("""\s+"""), " ")
        .trim()
        .replace(Regex("""\s+(20[0-9]{2})$"""), "")
        .replace(Regex("""\s+(?:in|at)\s+[A-Z][A-Za-z .'-]+$"""), "")
}