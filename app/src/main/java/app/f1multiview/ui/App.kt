package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.media3.common.util.UnstableApi
import app.f1multiview.core.playback.Quality
import app.f1multiview.core.playback.VodSession
import app.f1multiview.media.PlayerPool
import app.f1multiview.media.RadioPlayer
import app.f1multiview.model.*
import app.f1multiview.viewmodel.*
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
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
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var browser by remember { mutableStateOf(true) }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1C1D23), Bg, Color.Black))
        )
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Red))
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            F1TvLogo()
            Spacer(Modifier.height(10.dp))
            Text("YOUR PERSONAL PIT WALL", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(26.dp))
            Card(
                Modifier.widthIn(max = 470.dp).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Surface1)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Sign in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Use your F1 TV subscription", color = Muted, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(17.dp))
                    Button(
                        { vm.signIn(email, password) },
                        enabled = auth !is AuthState.SigningIn,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(if (auth is AuthState.SigningIn) "SIGNING IN…" else "SIGN IN", fontWeight = FontWeight.Bold)
                    }
                    TextButton({ browser = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("USE F1 TV BROWSER LOGIN", color = White, fontWeight = FontWeight.Bold)
                    }
                    if (auth is AuthState.Error) {
                        Text(auth.message, color = Color(0xFFFF8A8A), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (browser) F1BrowserLogin({ browser = false }, vm)
}

@Composable
private fun MultiViewScreen(ui: UiState, vm: MultiViewViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = remember(context) { isTelevision(context) }
    val isPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val compactPhone = !isTv && configuration.screenWidthDp < 600

    val pool = remember(context) { PlayerPool(context) }
    val radioPlayer = remember(context) { RadioPlayer(context) }
    val errors by pool.errors.collectAsState()
    var fullscreenStreamId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenMultiview by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(pool, radioPlayer) { onDispose { pool.release(); radioPlayer.release() } }
    val startedFeeds = remember(pool) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(ui.streams, ui.selectedStreamIds, ui.mainStreamId) {
        val selectedIds = ui.selectedStreamIds.toSet()
        startedFeeds.keys.filterNot { it in selectedIds }.toList().forEach { startedFeeds.remove(it) }
        pool.retain(selectedIds)
        val ordered = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id && it.url != null } }
        ordered.forEach { stream ->
            if (startedFeeds[stream.id] != true) {
                pool.load(stream)
                startedFeeds[stream.id] = true
                delay(500L)
            }
        }
        val mainId = ui.mainStreamId ?: ordered.firstOrNull()?.id
        pool.setAudioPlayer(mainId)
        if (ordered.isNotEmpty()) pool.playAll()
    }

    LaunchedEffect(ui.selectedStreamIds, ui.mainStreamId) {
        if (ui.selectedStreamIds.size > 1) {
            delay(1_500L)
            ui.mainStreamId?.let { pool.syncToMain(it) }
        }
    }

    BackHandler(enabled = fullscreenStreamId != null || fullscreenMultiview) {
        fullscreenStreamId = null
        fullscreenMultiview = false
    }

    if (fullscreenMultiview) {
        FullscreenMultiview(ui, pool, errors, { fullscreenMultiview = false }, vm::toggleStream, vm::setMainStream, vm::setLayout)
        return
    }

    val fullscreenStream = ui.streams.firstOrNull { it.id == fullscreenStreamId }
    if (fullscreenStream != null) {
        FullscreenPlayer(fullscreenStream, ui, pool, errors[fullscreenStream.id], { id -> fullscreenStreamId = id; vm.setMainStream(id) }) { fullscreenStreamId = null }
        return
    }

    val backgroundArtwork = ui.session?.backgroundArtworkUrl
        ?: ui.vodSessions.firstOrNull()?.backgroundArtworkUrl
        ?: ui.selectedEvent?.backgroundArtworkUrl

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!backgroundArtwork.isNullOrBlank()) {
            F1Artwork(
                backgroundArtwork,
                ui.session?.name ?: ui.vodSessions.firstOrNull()?.title ?: "F1 TV",
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = .62f),
                            Color.Black.copy(alpha = .72f),
                            Bg.copy(alpha = .96f)
                        )
                    )
                )
            )
        }
        Column(Modifier.fillMaxSize()) {
            Header(ui, vm, compactPhone)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = if (compactPhone) 10.dp else 16.dp)) {
            item { Archive(ui, vm, isTv, compactPhone) }
            item { ui.providerError?.let { ErrorBanner(it) } }
            item {
                PitWall(ui, pool, errors, isTv, compactPhone, { fullscreenMultiview = true }, vm::toggleStream, vm::setMainStream)
            }
        }
        }
        if (ui.selectedPanel != null) UgisInfoPanel(ui, vm, radioPlayer, isTv)
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
    val eventsForSeries = ui.vodEvents.filter { it.series == selectedSeries || selectedSeries == "F1" && it.series == "F1" }
    val activeEvent = selectedEvent?.takeIf { it in eventsForSeries } ?: eventsForSeries.firstOrNull()
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

        SectionHeader("F1 TV ARCHIVE", "SEASONS", side)
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
                modifier = Modifier.focusGroup()
            ) {
                items(seriesOptions) { series ->
                    ArchivePill(selectedSeries == series, series) { vm.setSeries(series) }
                }
            }
        }

        SectionHeader(
            if (selectedSeries == "F1") "GRAND PRIX" else "EVENTS",
            ui.selectedSeason?.year?.toString() ?: "",
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
    Surface(Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(9.dp), color = if (selected) Red else Surface2, border = if (selected) null else if (focused) BorderStroke(2.dp, White) else BorderStroke(1.dp, Color.White.copy(alpha = .07f))) {
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
        Modifier.width(width).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).onFocusChanged { focused = it.isFocused },
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
            .clickable(onClick = onClick)
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
    pool: PlayerPool,
    errors: Map<String, String>,
    isTv: Boolean,
    compactPhone: Boolean,
    onFullscreenAll: () -> Unit,
    onToggleStream: (String) -> Unit,
    onSetMainStream: (String) -> Unit
) {
    val selected = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id } }.take(6)
    var feedPanelOpen by rememberSaveable { mutableStateOf(false) }
    var feedToggleFocused by remember { mutableStateOf(false) }
    var tvResizeMode by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(28.dp).background(Red, RoundedCornerShape(3.dp)))
        Text("LIVE PIT WALL", color = White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 14.dp))
        Spacer(Modifier.weight(1f))
        Text(if (selected.isEmpty()) "SELECT FEEDS" else selected.size.toString() + "/6", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Surface(
            Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
                .clickable { feedPanelOpen = !feedPanelOpen }
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
                Text("Choose up to 6 feeds. Mark any feed as MAIN to replace the current main feed.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isTv) {
            Control(tvResizeMode, if (tvResizeMode) "DONE RESIZE" else "RESIZE") {
                tvResizeMode = !tvResizeMode
            }
            Control(false, "SYNC ALL") {
                pool.playAll()
                val mainId = ui.mainStreamId ?: selected.firstOrNull()?.id
                if (mainId != null) pool.syncToMain(mainId)
            }
        }
        Spacer(Modifier.weight(1f))
        run { var fullscreenFocused by remember { mutableStateOf(false) }
        Surface(
            Modifier.clip(RoundedCornerShape(9.dp))
                .clickable { onFullscreenAll() }
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
        CanonicalMultiviewLayout(
            selected,
            pool,
            errors,
            if (isTv) tvResizeMode else false,
            {},
            Modifier.fillMaxWidth().height(if (compactPhone) 340.dp else if (isTv) 500.dp else 430.dp)
        )
    }
}
@Composable
private fun ResizableCompactWall(selected: List<StreamSource>, pool: PlayerPool, errors: Map<String,String>, onFullscreen:(String)->Unit, editSize:Boolean) {
    var splitX by rememberSaveable { mutableFloatStateOf(.5f) }
    var splitY by rememberSaveable { mutableFloatStateOf(.55f) }
    val gap=7.dp
    if(selected.size==2){
        Row(Modifier.fillMaxWidth().height(300.dp),horizontalArrangement=Arrangement.spacedBy(gap)){
            PlayerTile(selected[0],pool,errors[selected[0].id],Modifier.weight(splitX).fillMaxHeight(),onFullscreen)
            ResizeHandle(Orientation.Horizontal,editSize){delta->splitX=(splitX+delta/700f).coerceIn(.25f,.75f)}
            PlayerTile(selected[1],pool,errors[selected[1].id],Modifier.weight(1f-splitX).fillMaxHeight(),onFullscreen)
        }
    } else {
        Column(Modifier.fillMaxWidth().height(390.dp),verticalArrangement=Arrangement.spacedBy(gap)){
            PlayerTile(selected[0],pool,errors[selected[0].id],Modifier.weight(splitY).fillMaxWidth(),onFullscreen)
            ResizeHandle(Orientation.Vertical,editSize){delta->splitY=(splitY+delta/900f).coerceIn(.28f,.72f)}
            Row(Modifier.weight(1f-splitY).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap)){
                PlayerTile(selected[1],pool,errors[selected[1].id],Modifier.weight(splitX).fillMaxHeight(),onFullscreen)
                ResizeHandle(Orientation.Horizontal,editSize){delta->splitX=(splitX+delta/700f).coerceIn(.2f,.8f)}
                PlayerTile(selected[2],pool,errors[selected[2].id],Modifier.weight(1f-splitX).fillMaxHeight(),onFullscreen)
                if(selected.size>3){                    // Additional feeds stay visible in a second compact row.
                }
            }
        }
        if(selected.size>3){
            Row(Modifier.fillMaxWidth().height(190.dp).padding(top=gap),horizontalArrangement=Arrangement.spacedBy(gap)){
                selected.drop(3).forEach{stream->PlayerTile(stream,pool,errors[stream.id],Modifier.weight(1f).fillMaxHeight(),onFullscreen)}
            }
        }
    }
}
@Composable
private fun ResizableSplitWall(selected:List<StreamSource>,pool:PlayerPool,errors:Map<String,String>,onFullscreen:(String)->Unit,editSize:Boolean){
    if(selected.size<2)return
    var split by rememberSaveable { mutableFloatStateOf(.5f) }
    Row(Modifier.fillMaxWidth().height(360.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        PlayerTile(selected[0],pool,errors[selected[0].id],Modifier.weight(split).fillMaxHeight(),onFullscreen)
        ResizeHandle(Orientation.Horizontal,editSize){delta->split=(split+delta/900f).coerceIn(.25f,.75f)}
        PlayerTile(selected[1],pool,errors[selected[1].id],Modifier.weight(1f-split).fillMaxHeight(),onFullscreen)
    }
}
@Composable
private fun ResizableDesktopWall(selected:List<StreamSource>,pool:PlayerPool,errors:Map<String,String>,onFullscreen:(String)->Unit,editSize:Boolean){
    var mainWeight by rememberSaveable { mutableFloatStateOf(.62f) }
    var h1 by rememberSaveable { mutableFloatStateOf(.34f) }
    var h2 by rememberSaveable { mutableFloatStateOf(.33f) }
    val side=selected.drop(1)
    if(side.isEmpty()){PlayerTile(selected[0],pool,errors[selected[0].id],Modifier.fillMaxWidth().height(430.dp),onFullscreen);return}
    Row(Modifier.fillMaxWidth().height(430.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        PlayerTile(selected[0],pool,errors[selected[0].id],Modifier.weight(mainWeight).fillMaxHeight(),onFullscreen)
        ResizeHandle(Orientation.Horizontal,editSize){delta->mainWeight=(mainWeight+delta/900f).coerceIn(.35f,.78f)}
        Column(Modifier.weight(1f-mainWeight).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(7.dp)){
            if(side.size>=1)PlayerTile(side[0],pool,errors[side[0].id],Modifier.weight(h1).fillMaxWidth(),onFullscreen)
            if(side.size>=2){
                ResizeHandle(Orientation.Vertical,editSize){delta->h1=(h1+delta/700f).coerceIn(.18f,.62f);h2=(h2-delta/700f).coerceIn(.18f,.62f)}
                PlayerTile(side[1],pool,errors[side[1].id],Modifier.weight(h2).fillMaxWidth(),onFullscreen)
            }
            if(side.size>=3){
                ResizeHandle(Orientation.Vertical,editSize){delta->h2=(h2+delta/700f).coerceIn(.18f,.62f)}
                PlayerTile(side[2],pool,errors[side[2].id],Modifier.weight((1f-h1-h2).coerceIn(.12f,.64f)).fillMaxWidth(),onFullscreen)
            }
            side.drop(3).forEach{stream->PlayerTile(stream,pool,errors[stream.id],Modifier.weight(1f).fillMaxWidth(),onFullscreen)}
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
    val keyModifier = if (enabled) {
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val delta = when {
                        orientation == Orientation.Horizontal && it.key == Key.DirectionLeft -> -step
                        orientation == Orientation.Horizontal && it.key == Key.DirectionRight -> step
                        orientation == Orientation.Vertical && it.key == Key.DirectionUp -> -step
                        orientation == Orientation.Vertical && it.key == Key.DirectionDown -> step
                        else -> null
                    }
                    if (delta != null) {
                        onDelta(delta)
                        true
                    } else {
                        false
                    }
                }
            }
    } else {
        Modifier
    }

    Box(
        Modifier
            .then(if (orientation == Orientation.Horizontal) Modifier.width(10.dp).fillMaxHeight() else Modifier.height(10.dp).fillMaxWidth())
            .then(keyModifier)
            .draggable(orientation = orientation, enabled = enabled, state = rememberDraggableState { onDelta(it) })
            .background(if (enabled) Red.copy(alpha = .75f) else Color.White.copy(alpha = .08f))
            .then(if (focused) Modifier.border(2.dp, White, RoundedCornerShape(3.dp)) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            Text(if (orientation == Orientation.Horizontal) "⋮" else "⋯", color = White, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerTile(stream: StreamSource, pool: PlayerPool, error: String?, modifier: Modifier, onFullscreen: (String) -> Unit, onFocus: ((String) -> Unit)? = null, surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW) {
    val player = remember(stream.id) { pool.get(stream.id) }
    val context = LocalContext.current
    val activity = context as? Activity
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
        modifier.clickable { onFocus(stream.id) }.onFocusChanged { tileFocused = it.isFocused }
            .then(if (tileFocused) Modifier.border(2.dp, White, RoundedCornerShape(10.dp)) else Modifier)
    } else modifier
    Card(tileModifier.border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), surfaceType = surfaceType, contentScale = ContentScale.Fit, keepContentOnReset = true)
            if (stream.url == null && error == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stream.title, color = White, fontWeight = FontWeight.Bold)
                    Text("CONNECTING…", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color.Black.copy(alpha = .58f)).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) {
                    Text(if (stream.isLive) "LIVE" else "REPLAY", color = White, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(if (pool.isMuted(stream.id)) "MUTED" else "AUDIO ON", color = if (pool.isMuted(stream.id)) Color.White.copy(alpha = .42f) else Color(0xFF66E07A), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(when { error != null -> "ERROR"; !ready -> "LOADING"; playing -> "PLAYING"; else -> "PAUSED" }, color = if (error != null) Color(0xFFFF7777) else Color.White.copy(alpha = .6f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))

            }
            if (error != null) {
                Surface(Modifier.align(Alignment.Center).padding(12.dp), shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = .92f), border = BorderStroke(1.dp, Red.copy(alpha = .65f))) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYBACK UNAVAILABLE", color = White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text(error.replace("PlaybackException: ", "").replace("Source error", "Source unavailable"), color = Muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        run { var retryFocused by remember { mutableStateOf(false) }
                        Surface(
                            Modifier.clickable { pool.clear(stream.id); pool.load(stream); pool.play(stream.id) }
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
private fun FullscreenMultiview(
    ui: UiState,
    pool: PlayerPool,
    errors: Map<String, String>,
    onClose: () -> Unit,
    onToggleStream: (String) -> Unit,
    onSetMainStream: (String) -> Unit,
    onLayout: (LayoutPreset) -> Unit
) {
    val selected = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id } }.take(6)
    val context = LocalContext.current
    val displayHdr = displaySupportsHdr(context)
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var feedPickerOpen by rememberSaveable { mutableStateOf(false) }
    var editSize by rememberSaveable { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf(ui.layout) }
    var activeFeedId by rememberSaveable { mutableStateOf(ui.mainStreamId ?: selected.firstOrNull()?.id) }
    var menu by rememberSaveable { mutableStateOf<String?>(null) }
    var trackVersion by remember { mutableIntStateOf(0) }
    var speed by rememberSaveable(activeFeedId) { mutableFloatStateOf(1f) }
    var quality by remember(activeFeedId, pool) {
        mutableStateOf(activeFeedId?.let(pool::getQuality) ?: Quality.AUTO)
    }
    var fit by rememberSaveable(activeFeedId) { mutableStateOf(false) }
    val fullscreenBackFocusRequester = remember { FocusRequester() }
    val fullscreenShowControlsFocusRequester = remember { FocusRequester() }
    var fullscreenBackFocused by remember { mutableStateOf(false) }
    var fullscreenShowControlsFocused by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible) {
        delay(80L)
        if (controlsVisible) fullscreenBackFocusRequester.requestFocus()
        else fullscreenShowControlsFocusRequester.requestFocus()
    }

    LaunchedEffect(ui.selectedStreamIds, ui.mainStreamId) {
        if (activeFeedId !in ui.selectedStreamIds) activeFeedId = ui.mainStreamId ?: ui.selectedStreamIds.firstOrNull()
    }

    val active = selected.firstOrNull { it.id == activeFeedId } ?: selected.firstOrNull()
    val activePlayer = active?.let { pool.get(it.id) }

    DisposableEffect(activePlayer) {
        if (activePlayer == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackVersion++ }
        }
        activePlayer.addListener(listener)
        onDispose { activePlayer.removeListener(listener) }
    }

    val audioTracks = remember(activePlayer, trackVersion) {
        activePlayer?.currentTracks?.groups?.flatMapIndexed { groupIndex, group ->            if (group.type != C.TRACK_TYPE_AUDIO) emptyList()
            else (0 until group.length).mapNotNull { index -> if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, group.getTrackFormat(index)) }
        } ?: emptyList()
    }
    val textTracks = remember(activePlayer, trackVersion) {
        activePlayer?.currentTracks?.groups?.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_TEXT) emptyList()
            else (0 until group.length).mapNotNull { index -> if (!group.isTrackSupported(index)) null else Triple(groupIndex, index, group.getTrackFormat(index)) }
        } ?: emptyList()
    }

    fun playAll() = selected.forEach { pool.play(it.id) }
    fun pauseAll() = selected.forEach { pool.pause(it.id) }
    fun seekAll(deltaMs: Long) = selected.forEach { val p = pool.get(it.id); p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L)) }

    LaunchedEffect(controlsVisible, feedPickerOpen, editSize, menu) {
        if (controlsVisible && !feedPickerOpen && !editSize && menu == null) {
            delay(5_000L)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(controlsVisible, feedPickerOpen, editSize, menu) {
                detectTapGestures {
                    if (!feedPickerOpen && !editSize && menu == null) {
                        controlsVisible = !controlsVisible
                    }
                }
            }
    ) {
        CanonicalMultiviewLayout(selected, pool, errors, editSize, { activeFeedId = it; menu = null }, Modifier.fillMaxSize(), SURFACE_TYPE_SURFACE_VIEW)

        if (!controlsVisible) {
            // SurfaceView/TextureView can consume touch events underneath Compose.
            // Keep a transparent Compose hit target over the whole fullscreen area so
            // a normal tap always brings the controls back.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { controlsVisible = true } }
                    .zIndex(20f)
            )
            Surface(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .focusRequester(fullscreenShowControlsFocusRequester)
                    .clickable { controlsVisible = true }
                    .onFocusChanged { fullscreenShowControlsFocused = it.isFocused }
                    .border(2.dp, if (fullscreenShowControlsFocused) White else Color.Transparent, RoundedCornerShape(9.dp))
                    .zIndex(21f),
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
                LazyRow(
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.focusGroup()
                ) {
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
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = .88f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        Modifier
                            .focusRequester(fullscreenBackFocusRequester)
                            .clickable(onClick = onClose)
                            .onFocusChanged { fullscreenBackFocused = it.isFocused }
                            .border(2.dp, if (fullscreenBackFocused) White else Color.Transparent, RoundedCornerShape(8.dp)),
                        color = Surface2,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("‹ BACK", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) }
                    Spacer(Modifier.width(7.dp))
                    Text("MULTIVIEW", color = White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(6.dp))
                    Text("${selected.size} FEEDS", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    listOf(LayoutPreset.SINGLE to "1", LayoutPreset.SPLIT_2 to "2", LayoutPreset.GRID_4 to "4", LayoutPreset.GRID_6 to "6").forEach { (preset, label) ->
                        Control(layout == preset, "LAYOUT " + label) { layout = preset; onLayout(preset) }
                        Spacer(Modifier.width(5.dp))
                    }
                    Control(editSize, if (editSize) "DONE RESIZE" else "RESIZE") { editSize = !editSize }
                    Spacer(Modifier.width(5.dp))
                    Control(false, "FEEDS ${ui.streams.size}") { feedPickerOpen = !feedPickerOpen }
                    Spacer(Modifier.width(5.dp))
                    Control(false, "SYNC ALL") {
                        pool.playAll()
                        val mainId = ui.mainStreamId ?: active?.id
                        if (mainId != null) pool.syncToMain(mainId)
                    }
                    Spacer(Modifier.width(5.dp))
                    Control(false, "HIDE") { controlsVisible = false }
                }
                if (feedPickerOpen) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(ui.streams) { stream ->
                            val picked = stream.id in ui.selectedStreamIds
                            val isMain = stream.id == ui.mainStreamId
                            var feedFocused by remember(stream.id) { mutableStateOf(false) }
                            Surface(Modifier.widthIn(min = 145.dp, max = 205.dp), shape = RoundedCornerShape(8.dp), color = if (isMain) Red else if (picked) Color(0xFF5A1012) else Surface2) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { onToggleStream(stream.id) }
                                            .onFocusChanged { feedFocused = it.isFocused }
                                            .then(if (feedFocused) Modifier.border(2.dp, White, RoundedCornerShape(6.dp)) else Modifier),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (picked) "✓" else "+", color = White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    var mainActionFocused by remember(stream.id) { mutableStateOf(false) }
                                    Surface(
                                        Modifier.fillMaxWidth().clickable { onSetMainStream(stream.id) }
                                            .onFocusChanged { mainActionFocused = it.isFocused },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isMain) Color.Black.copy(alpha = .28f) else Red.copy(alpha = .18f),
                                        border = if (mainActionFocused) BorderStroke(2.dp, White) else BorderStroke(1.dp, Color.Transparent)
                                    ) {
                                        Text(if (isMain) "MAIN FEED" else "SET AS MAIN — REPLACE", color = White, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        if (active != null && controlsVisible) {
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {                FullscreenFeedControls(
                    stream = active, player = pool.get(active.id), pool = pool, audioTracks = audioTracks, textTracks = textTracks,
                    speed = speed, quality = quality, fit = fit, menu = menu,
                    onSpeed = { speed = it }, onQuality = { quality = it }, onFit = { fit = it }, onMenu = { menu = it },
                    onMute = { pool.setMuted(active.id, !pool.isMuted(active.id)) },
                    onPlayAll = ::playAll, onPauseAll = ::pauseAll, onSeekAll = ::seekAll
                )
            }
        }
    }
}

@Composable
private fun CanonicalMultiviewLayout(
    selected: List<StreamSource>,
    pool: PlayerPool,
    errors: Map<String, String>,
    editSize: Boolean,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
    surfaceType: Int = SURFACE_TYPE_SURFACE_VIEW
) {
    val gap = 6.dp
    var splitX by rememberSaveable { mutableFloatStateOf(.5f) }
    var splitY by rememberSaveable { mutableFloatStateOf(.58f) }
    var mainX by rememberSaveable { mutableFloatStateOf(.62f) }
    var topX by rememberSaveable { mutableFloatStateOf(.33f) }
    var topX2 by rememberSaveable { mutableFloatStateOf(.5f) }
    var bottomX by rememberSaveable { mutableFloatStateOf(.5f) }
    var bottomX2 by rememberSaveable { mutableFloatStateOf(.5f) }
    var gridY by rememberSaveable { mutableFloatStateOf(.5f) }
    val firstResizeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editSize, selected.size) {
        if (editSize && selected.size > 1) {
            delay(60L)
            firstResizeFocusRequester.requestFocus()
        }
    }

    Box(modifier) {
        when {
            selected.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("NO FEEDS SELECTED", color = White, fontWeight = FontWeight.Bold)
                }
            selected.size == 1 ->
                PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.fillMaxSize(), {}, onFocus, surfaceType)
            selected.size == 2 ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.weight(splitX).fillMaxHeight(), {}, onFocus, surfaceType)
                    ResizeHandle(Orientation.Horizontal, editSize, firstResizeFocusRequester) { splitX = (splitX + it / 1000f).coerceIn(.2f, .8f) }
                    PlayerTile(selected[1], pool, errors[selected[1].id], Modifier.weight(1f - splitX).fillMaxHeight(), {}, onFocus, surfaceType)
                }
            selected.size == 3 ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.weight(mainX).fillMaxHeight(), {}, onFocus, surfaceType)
                    ResizeHandle(Orientation.Horizontal, editSize, firstResizeFocusRequester) { mainX = (mainX + it / 1000f).coerceIn(.35f, .78f) }
                    Column(Modifier.weight(1f - mainX).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        PlayerTile(selected[1], pool, errors[selected[1].id], Modifier.weight(splitY).fillMaxWidth(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Vertical, editSize) { splitY = (splitY + it / 900f).coerceIn(.2f, .8f) }
                        PlayerTile(selected[2], pool, errors[selected[2].id], Modifier.weight(1f - splitY).fillMaxWidth(), {}, onFocus, surfaceType)
                    }
                }
            selected.size == 4 ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.weight(gridY).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.weight(topX).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize, firstResizeFocusRequester) { topX = (topX + it / 1400f).coerceIn(.25f, .75f) }
                        PlayerTile(selected[1], pool, errors[selected[1].id], Modifier.weight(1f - topX).fillMaxHeight(), {}, onFocus, surfaceType)
                    }
                    ResizeHandle(Orientation.Vertical, editSize) { gridY = (gridY + it / 1000f).coerceIn(.25f, .75f) }
                    Row(Modifier.weight(1f - gridY).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        PlayerTile(selected[2], pool, errors[selected[2].id], Modifier.weight(bottomX).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize) { bottomX = (bottomX + it / 1400f).coerceIn(.25f, .75f) }
                        PlayerTile(selected[3], pool, errors[selected[3].id], Modifier.weight(1f - bottomX).fillMaxHeight(), {}, onFocus, surfaceType)
                    }
                }
            else ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.weight(gridY).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.weight(topX).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize, firstResizeFocusRequester) { topX = (topX + it / 1400f).coerceIn(.18f, .52f) }
                        PlayerTile(selected[1], pool, errors[selected[1].id], Modifier.weight((1f - topX) * topX2).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize) { topX2 = (topX2 + it / 1200f).coerceIn(.25f, .75f) }
                        PlayerTile(selected[2], pool, errors[selected[2].id], Modifier.weight((1f - topX) * (1f - topX2)).fillMaxHeight(), {}, onFocus, surfaceType)
                    }
                    ResizeHandle(Orientation.Vertical, editSize) { gridY = (gridY + it / 1000f).coerceIn(.25f, .75f) }
                    Row(Modifier.weight(1f - gridY).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        PlayerTile(selected[3], pool, errors[selected[3].id], Modifier.weight(bottomX).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize) { bottomX = (bottomX + it / 1400f).coerceIn(.18f, .52f) }
                        PlayerTile(selected[4], pool, errors[selected[4].id], Modifier.weight((1f - bottomX) * bottomX2).fillMaxHeight(), {}, onFocus, surfaceType)
                        ResizeHandle(Orientation.Horizontal, editSize) { bottomX2 = (bottomX2 + it / 1200f).coerceIn(.25f, .75f) }
                        PlayerTile(selected[5], pool, errors[selected[5].id], Modifier.weight((1f - bottomX) * (1f - bottomX2)).fillMaxHeight(), {}, onFocus, surfaceType)
                    }
                }
        }
    }
}
@OptIn(UnstableApi::class)
@Composable
private fun FullscreenFeedControls(
    stream: StreamSource,
    player: androidx.media3.exoplayer.ExoPlayer,
    pool: PlayerPool,
    audioTracks: List<Triple<Int, Int, androidx.media3.common.Format>>,
    textTracks: List<Triple<Int, Int, androidx.media3.common.Format>>,
    speed: Float,
    quality: Quality,
    fit: Boolean,
    menu: String?,
    onSpeed: (Float) -> Unit,
    onQuality: (Quality) -> Unit,
    onFit: (Boolean) -> Unit,
    onMenu: (String?) -> Unit,
    onMute: () -> Unit,
    onPlayAll: () -> Unit,
    onPauseAll: () -> Unit,
    onSeekAll: (Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var position by remember(stream.id) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(stream.id) { mutableLongStateOf(player.duration.takeIf { it > 0 } ?: 0L) }
    var trackVersion by remember(stream.id) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { playing = player.isPlaying; duration = player.duration.takeIf { it > 0 } ?: 0L }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackVersion++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(250L)
        }
    }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .96f)))).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (duration > 0L) {
            Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()), onValueChange = { position = it.toLong() }, onValueChangeFinished = { player.seekTo(position.coerceIn(0L, duration)) }, valueRange = 0f..duration.toFloat(), colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = Color.White.copy(alpha = .28f)))
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = Red, fontSize = 8.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                PlayerControlButton("GO LIVE") { player.seekToDefaultPosition(); player.play() }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerControlButton("↶ 10") { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
            PlayerControlButton(if (playing) "PAUSE" else "PLAY") { if (playing) player.pause() else player.play() }
            PlayerControlButton("10 ↷") { player.seekTo(player.currentPosition + 10_000L) }
            Spacer(Modifier.weight(1f))
            MenuButton(if (pool.isMuted(stream.id)) "UNMUTE" else "MUTE") { onMute() }
            MenuButton("PIP") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {                    val w = player.videoSize.width.coerceAtLeast(16)
                    val h = player.videoSize.height.coerceAtLeast(9)
                    val ratio = Rational(w, h)
                    activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
                }
            }
            MenuButton("RETRY") { player.prepare(); player.play() }
            MenuButton("SPEED " + "%.2f".format(speed) + "x") { onMenu(if (menu == "speed") null else "speed") }
            MenuButton("QUALITY " + quality.label()) { onMenu(if (menu == "quality") null else "quality") }
            MenuButton("AUDIO") { onMenu(if (menu == "audio") null else "audio") }
            MenuButton("SUBS") { onMenu(if (menu == "text") null else "text") }
            MenuButton(if (fit) "FIT" else "FILL") { onFit(!fit); player.videoScalingMode = if (!fit) C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING }
        }
        if (menu != null) {
            Surface(Modifier.fillMaxWidth().padding(top = 7.dp), shape = RoundedCornerShape(11.dp), color = Color(0xFF17181F).copy(alpha = .98f), border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))) {
                Column(Modifier.padding(9.dp)) {
                    when (menu) {
                        "speed" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> Control(speed == v, v.toString() + "x") { onSpeed(v); player.setPlaybackParameters(PlaybackParameters(v)); onMenu(null) } }
                        }
                        "quality" -> {
                            val resolutions = remember(trackVersion) { pool.availableVideoResolutionsForQualityMenu(stream.id) }
                            val diagnostics = remember(trackVersion) { pool.currentVideoDiagnostics(stream.id) }
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
                                modifier=Modifier.padding(bottom=5.dp)
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(Quality.AUTO to "Auto", Quality.UHD to "4K", Quality.FHD to "1080p", Quality.HD to "720p", Quality.SD to "480p").forEach { (q, label) ->
                                    val available = pool.qualityAvailable(stream.id, q)
                                    Control(quality == q, if (available || q == Quality.AUTO) label else "$label — N/A") {
                                        if (available || q == Quality.AUTO) {
                                            onQuality(q)
                                            pool.setQuality(stream.id, q)
                                            onMenu(null)
                                        }
                                    }
                                }
                            }
                        }
                        "audio" -> {
                            Text("AUDIO TRACKS", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                audioTracks.forEachIndexed { index, (gi, ti, f) ->
                                    Control(false, f.label ?: f.language?.uppercase() ?: "Audio " + (index + 1)) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[gi].mediaTrackGroup, ti)).build()
                                        onMenu(null)
                                    }
                                }
                                if (audioTracks.isEmpty()) Text("No alternate audio tracks reported by F1 TV.", color = Muted, fontSize = 9.sp)
                            }
                        }
                        "text" -> {
                            Text("SUBTITLES", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Control(false, "Off") { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); onMenu(null) }
                                textTracks.forEachIndexed { index, (gi, ti, f) ->
                                    Control(false, f.label ?: f.language?.uppercase() ?: "Subtitle " + (index + 1)) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(player.currentTracks.groups[gi].mediaTrackGroup, ti)).build()
                                        onMenu(null)
                                    }
                                }
                                if (textTracks.isEmpty()) Text("No subtitle tracks reported by F1 TV.", color = Muted, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
        Text(formatPosition(position) + if (duration > 0L) " / " + formatPosition(duration) else "  •  LIVE", color = Color.White.copy(alpha = .72f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(stream: StreamSource, ui: UiState, pool: PlayerPool, error: String?, onSwitchStream: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val displayHdr = displaySupportsHdr(context)
    val player = remember(stream.id) { pool.get(stream.id) }

    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var ready by remember(stream.id) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var position by remember(stream.id) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(stream.id) { mutableLongStateOf(player.duration.takeIf { it > 0 } ?: 0L) }
    var controlsVisible by rememberSaveable(stream.id) { mutableStateOf(true) }
    var speed by rememberSaveable(stream.id) { mutableFloatStateOf(1f) }
    var quality by remember(stream.id, pool) {
        mutableStateOf(pool.getQuality(stream.id))
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
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } },
        contentAlignment = Alignment.Center
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW)

        if (!controlsVisible) {
            Surface(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .focusRequester(fullscreenShowControlsFocusRequester)
                    .clickable { controlsVisible = true }
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

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.72f), Color.Transparent, Color.Black.copy(alpha=.90f))))) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                    Surface(
                        Modifier
                            .focusRequester(fullscreenBackFocusRequester)
                            .clickable { onClose() }
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

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp)) {
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
                                Modifier.clickable { player.seekToDefaultPosition(); player.play() }
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
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        PlayerControlButton("↶ 10"){player.seekTo(max(0L,player.currentPosition-10_000L))}
                        PlayerControlButton(if(playing)"PAUSE" else "PLAY"){if(playing)player.pause()else player.play()}
                        PlayerControlButton("10 ↷"){player.seekTo(player.currentPosition+10_000L)}
                        Spacer(Modifier.weight(1f))
                        PlayerControlButton(if(muted)"MUTE" else "SOUND"){muted=!muted;player.volume=if(muted)0f else 1f}
                        MenuButton("SPEED  " + "%.2f".format(speed)){menu=if(menu=="speed")null else "speed"}
                        MenuButton("QUALITY  " + quality.label()){menu=if(menu=="quality")null else "quality"}
                        MenuButton("AUDIO"){menu=if(menu=="audio")null else "audio"}
                        MenuButton("SUBS"){menu=if(menu=="text")null else "text"}
                        MenuButton("MORE"){menu=if(menu=="more")null else "more"}
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
                                        val resolutions = pool.availableVideoResolutionsForQualityMenu(stream.id)
                                        val diagnostics = pool.currentVideoDiagnostics(stream.id)
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
                                                val available = pool.qualityAvailable(stream.id,q)
                                                Control(quality==q, if (available || q==Quality.AUTO) l else "$l — N/A") {
                                                    if (available || q==Quality.AUTO) {
                                                        quality=q
                                                        pool.setQuality(stream.id,q)
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
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).onFocusChanged { focused = it.isFocused },
        color=Color.Black.copy(alpha=.65f),
        shape=RoundedCornerShape(8.dp),
        border=if (focused) BorderStroke(2.dp, White) else null
    ){
        Text(label,color=White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=10.dp,vertical=7.dp))
    }
}
@Composable private fun PlayerControlButton(label:String,onClick:()->Unit){
    var focused by remember { mutableStateOf(false) }
    Surface(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick=onClick).onFocusChanged { focused = it.isFocused },
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
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).onFocusChanged { focused = it.isFocused },
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
            .clickable(onClick = onClick)
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
        LayoutPreset.SINGLE -> Box(Modifier.fillMaxSize().border(2.dp, c, RoundedCornerShape(2.dp)))
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
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Red else Surface2,
        border = if (focused && !selected) BorderStroke(2.dp, White) else null
    ) {
        Text(label, color = White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = .07f),
        border = if (focused) BorderStroke(2.dp, White) else null) {
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