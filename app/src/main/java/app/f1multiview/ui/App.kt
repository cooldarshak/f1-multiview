package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.media3.common.util.UnstableApi
import app.f1multiview.core.playback.Quality
import app.f1multiview.media.PlayerPool
import app.f1multiview.model.*
import app.f1multiview.viewmodel.*
import android.app.Activity
import android.app.PictureInPictureParams
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
    val pool = remember(context) { PlayerPool(context) }
    val errors by pool.errors.collectAsState()
    var fullscreenStreamId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreenMultiview by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(pool) { onDispose { pool.release() } }

    val startedFeeds = remember(pool) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(ui.streams, ui.selectedStreamIds, ui.mainStreamId) {
        val selectedIds = ui.selectedStreamIds.toSet()
        startedFeeds.keys.filterNot { it in selectedIds }.toList().forEach { startedFeeds.remove(it) }
        pool.retain(selectedIds)
        val ordered = ui.selectedStreamIds.mapNotNull { id ->
            ui.streams.firstOrNull { it.id == id && it.url != null }
        }
        ordered.forEach { stream ->
            if (startedFeeds[stream.id] != true) {
                pool.load(stream)
                startedFeeds[stream.id] = true
                delay(500L)
            }
        }
        val mainId = ui.mainStreamId ?: ordered.firstOrNull()?.id
        pool.setAudioPlayer(mainId)
        if (ordered.isNotEmpty()) {
            // Start every prepared player once. Do not seek/speed-adjust immediately;
            // let each live manifest reach READY first.
            pool.playAll()
        }
    }

    // Let every selected feed buffer independently, then perform one stable
    // alignment pass. VOD sync is seek-only; live sync uses a gentle live-edge correction.
    LaunchedEffect(ui.selectedStreamIds, ui.mainStreamId) {
        if (ui.selectedStreamIds.size > 1) {
            delay(4_000L)
            val mainId = ui.mainStreamId
            if (mainId != null) pool.syncToMain(mainId)
        }
    }

    BackHandler(enabled = fullscreenStreamId != null || fullscreenMultiview) {
        fullscreenStreamId = null
        fullscreenMultiview = false
    }

    if (fullscreenMultiview) {
        FullscreenMultiview(
            ui = ui,
            pool = pool,
            errors = errors,
            onClose = { fullscreenMultiview = false },
            onToggleStream = vm::toggleStream,
            onSetMainStream = vm::setMainStream,
            onLayout = vm::setLayout
        )
        return
    }

    val fullscreenStream = ui.streams.firstOrNull { it.id == fullscreenStreamId }
    if (fullscreenStream != null) {
        FullscreenPlayer(fullscreenStream, pool, errors[fullscreenStream.id]) { fullscreenStreamId = null }
        return
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Header(ui, vm)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Hero(ui) }
            item { Archive(ui, vm) }
            item { ui.providerError?.let { ErrorBanner(it) } }
            item {
                PitWall(                    ui = ui,
                    pool = pool,
                    errors = errors,
                    onFullscreenAll = { fullscreenMultiview = true },
                    onToggleStream = vm::toggleStream,
                    onSetMainStream = vm::setMainStream
                )
            }
        }
    }
}

@Composable
private fun Header(ui: UiState, vm: MultiViewViewModel) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF101116)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        F1TvLogo()
        Spacer(Modifier.width(22.dp))
        Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = .1f)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.session?.name ?: "F1 MULTIVIEW", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (ui.session?.live == true) "LIVE NOW" else "F1 TV", color = if (ui.session?.live == true) Red else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Surface(color = Color.White.copy(alpha = .06f), shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (ui.timingStatus == "OFFLINE") Muted else Color(0xFF59D56D)))
                Spacer(Modifier.width(6.dp))
                Text(ui.timingStatus, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        TextButton({ vm.signOut() }) { Text("SIGN OUT", color = White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Hero(ui: UiState) {
    Box(
        Modifier.fillMaxWidth().height(170.dp).background(
            Brush.horizontalGradient(listOf(Color(0xFF292A32), Color(0xFF121318), Color(0xFF24090B)))
        ).padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) {
                    Text(if (ui.session?.live == true) "LIVE" else "REPLAY", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(ui.session?.series ?: "F1", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(9.dp))
            Text(
                ui.session?.name ?: "Choose a Grand Prix",
                color = White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(ui.session?.dateLabel ?: "Select a session below", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Text("MULTIVIEW", color = Color.White.copy(alpha = .045f), fontSize = 43.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun Archive(ui: UiState, vm: MultiViewViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        SectionHeader("F1 TV ARCHIVE", "SEASON")
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(ui.vodSeasons) { season ->
                Pill(ui.selectedSeason == season, season.year.toString()) { vm.selectVodSeason(season) }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("GRAND PRIX", ui.selectedSeason?.year?.toString() ?: "")
        if (ui.vodEvents.isEmpty() && ui.selectedSeason != null) {
            Text("No Grand Prix events found for ${ui.selectedSeason.year}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(ui.vodEvents) { event ->
                    EventCard(ui.selectedEvent == event, prettyEvent(event.meetingName)) { vm.selectVodEvent(event) }
                }
            }
        }

        if (ui.vodSessions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionHeader("SESSION", "SELECT")
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.vodSessions) { session ->
                    SessionCard(ui.session?.id == session.contentId, session.title, session.type.uppercase()) { vm.selectVodSession(session) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, meta: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(17.dp).background(Red, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        Text(meta, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Pill(selected: Boolean, title: String, onClick: () -> Unit) {
    Surface(
        Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).focusable(),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Red else Surface2,
        border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = .07f))
    ) {
        Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp))
    }
}

@Composable
private fun EventCard(selected: Boolean, title: String, onClick: () -> Unit) {
    Surface(
        Modifier.widthIn(min = 150.dp, max = 260.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).focusable(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF2A0D0F) else Surface1,
        border = BorderStroke(1.dp, if (selected) Red else Color.White.copy(alpha = .07f))
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(if (selected) "SELECTED" else "GRAND PRIX", color = if (selected) Red else Muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SessionCard(selected: Boolean, title: String, type: String, onClick: () -> Unit) {
    Surface(
        Modifier.clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick).focusable(),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) Red else Surface2
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(type, color = Color.White.copy(alpha = .62f), fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun PitWall(
    ui: UiState,
    pool: PlayerPool,
    errors: Map<String, String>,
    onFullscreenAll: () -> Unit,
    onToggleStream: (String) -> Unit,
    onSetMainStream: (String) -> Unit
) {
    val selected = ui.selectedStreamIds.mapNotNull { id -> ui.streams.firstOrNull { it.id == id } }.take(6)
    var feedPanelOpen by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(28.dp).background(Red, RoundedCornerShape(3.dp)))
        Text("LIVE PIT WALL", color = White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 14.dp))
        Spacer(Modifier.weight(1f))
        Text(if (selected.isEmpty()) "SELECT FEEDS" else selected.size.toString() + "/6", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Surface(Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).clickable { feedPanelOpen = !feedPanelOpen }, shape = RoundedCornerShape(10.dp), color = if (feedPanelOpen) Red else Surface2, border = if (feedPanelOpen) null else BorderStroke(1.dp, Color.White.copy(alpha = .09f))) {
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
            Surface(Modifier.clip(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp), color = if (isMain) Red else if (picked) Color(0xFF5A1012) else Surface2, border = BorderStroke(1.dp, if (isMain) Red else Color.White.copy(alpha = .08f))) {
                Column(Modifier.widthIn(min = 135.dp, max = 190.dp).padding(horizontal = 9.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { onToggleStream(stream.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { isMain -> "MAIN FEED"; picked -> "IN MULTIVIEW"; else -> stream.kind.name }, color = if (isMain || picked) White.copy(alpha = .88f) else Muted, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(if (picked) "✓" else "+", color = White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(5.dp))
                    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable { onSetMainStream(stream.id) }, shape = RoundedCornerShape(7.dp), color = if (isMain) Color.Black.copy(alpha = .28f) else Red.copy(alpha = .18f)) {
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.clip(RoundedCornerShape(9.dp)).clickable { onFullscreenAll() }, shape = RoundedCornerShape(9.dp), color = Red) {
            Text("OPEN MULTIVIEW FULLSCREEN", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        CanonicalMultiviewLayout(selected, pool, errors, false, {}, Modifier.fillMaxWidth().height(430.dp))
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
private fun ResizeHandle(orientation:Orientation,enabled:Boolean,onDelta:(Float)->Unit){
    Box(
        Modifier
            .then(if(orientation==Orientation.Horizontal) Modifier.width(10.dp).fillMaxHeight() else Modifier.height(10.dp).fillMaxWidth())
            .draggable(orientation=orientation,enabled=enabled,state=rememberDraggableState{onDelta(it)})
            .background(if(enabled) Red.copy(alpha=.75f) else Color.White.copy(alpha=.08f)),
        contentAlignment=Alignment.Center
    ){ if(enabled) Text(if(orientation==Orientation.Horizontal) "⋮" else "⋯",color=White,fontSize=10.sp,fontWeight=FontWeight.Black) }
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

    val tileModifier = if (onFocus != null) modifier.clickable { onFocus(stream.id) } else modifier
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
                        Surface(Modifier.clickable { pool.clear(stream.id); pool.load(stream); pool.play(stream.id) }.focusable(), color = Red, shape = RoundedCornerShape(50)) {
                            Text("RETRY", color = White, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
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
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var feedPickerOpen by rememberSaveable { mutableStateOf(false) }
    var editSize by rememberSaveable { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf(ui.layout) }
    var activeFeedId by rememberSaveable { mutableStateOf(ui.mainStreamId ?: selected.firstOrNull()?.id) }
    var menu by rememberSaveable { mutableStateOf<String?>(null) }
    var trackVersion by remember { mutableIntStateOf(0) }
    var speed by rememberSaveable(activeFeedId) { mutableFloatStateOf(1f) }
    var quality by rememberSaveable(activeFeedId) { mutableStateOf(Quality.AUTO) }
    var fit by rememberSaveable(activeFeedId) { mutableStateOf(false) }

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
        CanonicalMultiviewLayout(selected, pool, errors, editSize, { activeFeedId = it; menu = null }, Modifier.fillMaxSize(), SURFACE_TYPE_TEXTURE_VIEW)

        if (!controlsVisible) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(12.dp).clickable { controlsVisible = true }.focusable(),
                color = Color.Black.copy(alpha = .78f),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))
            ) {
                Text("SHOW CONTROLS", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
            }
        }

        if (controlsVisible) {
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = .88f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.clickable(onClick = onClose), color = Surface2, shape = RoundedCornerShape(8.dp)) { Text("‹ BACK", color = White, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) }
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
                            Surface(Modifier.widthIn(min = 145.dp, max = 205.dp), shape = RoundedCornerShape(8.dp), color = if (isMain) Red else if (picked) Color(0xFF5A1012) else Surface2) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(Modifier.fillMaxWidth().clickable { onToggleStream(stream.id) }, verticalAlignment = Alignment.CenterVertically) {
                                        Text(stream.driver?.takeIf { it.isNotBlank() } ?: stream.title, color = White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (picked) "✓" else "+", color = White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Surface(Modifier.fillMaxWidth().clickable { onSetMainStream(stream.id) }, shape = RoundedCornerShape(6.dp), color = if (isMain) Color.Black.copy(alpha = .28f) else Red.copy(alpha = .18f)) {
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
                    ResizeHandle(Orientation.Horizontal, editSize) { splitX = (splitX + it / 1000f).coerceIn(.2f, .8f) }
                    PlayerTile(selected[1], pool, errors[selected[1].id], Modifier.weight(1f - splitX).fillMaxHeight(), {}, onFocus, surfaceType)
                }
            selected.size == 3 ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    PlayerTile(selected[0], pool, errors[selected[0].id], Modifier.weight(mainX).fillMaxHeight(), {}, onFocus, surfaceType)
                    ResizeHandle(Orientation.Horizontal, editSize) { mainX = (mainX + it / 1000f).coerceIn(.35f, .78f) }
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
                        ResizeHandle(Orientation.Horizontal, editSize) { topX = (topX + it / 1400f).coerceIn(.25f, .75f) }
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
                        ResizeHandle(Orientation.Horizontal, editSize) { topX = (topX + it / 1400f).coerceIn(.18f, .52f) }
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
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { playing = player.isPlaying; duration = player.duration.takeIf { it > 0 } ?: 0L }
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
                        "quality" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(Quality.AUTO to "Auto", Quality.UHD to "4K", Quality.FHD to "1080p", Quality.HD to "720p", Quality.SD to "480p").forEach { (q, label) -> Control(quality == q, label) { onQuality(q); pool.setQuality(stream.id, q); onMenu(null) } }
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
private fun FullscreenPlayer(stream: StreamSource, pool: PlayerPool, error: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val player = remember(stream.id) { pool.get(stream.id) }

    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var ready by remember(stream.id) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var position by remember(stream.id) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(stream.id) { mutableLongStateOf(player.duration.takeIf { it > 0 } ?: 0L) }
    var controlsVisible by rememberSaveable(stream.id) { mutableStateOf(true) }
    var speed by rememberSaveable(stream.id) { mutableFloatStateOf(1f) }
    var quality by rememberSaveable(stream.id) { mutableStateOf(Quality.AUTO) }
    var fit by rememberSaveable(stream.id) { mutableStateOf(false) }
    var muted by rememberSaveable(stream.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var trackVersion by remember { mutableIntStateOf(0) }

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
        Modifier.fillMaxSize().background(Color.Black).clickable { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center
    ) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW)

        if (!controlsVisible) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(12.dp).clickable { controlsVisible = true }.focusable(),
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
                    Surface(Modifier.clickable { onClose() }.focusable(), color=Color.Black.copy(alpha=.65f), shape=RoundedCornerShape(9.dp)) {
                        Text("‹  BACK", color=White, fontSize=10.sp, fontWeight=FontWeight.Black, modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(color=Red, shape=RoundedCornerShape(4.dp)) {
                        Text(if(stream.isLive)"LIVE" else "REPLAY", color=White, fontSize=8.sp, fontWeight=FontWeight.Black, modifier=Modifier.padding(horizontal=7.dp,vertical=5.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stream.driver?.takeIf{it.isNotBlank()}?:stream.title,color=White,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    SmallPlayerButton("PIP"){enterPip()}
                    Spacer(Modifier.width(6.dp))
                    SmallPlayerButton(if (fit) "FIT" else "FILL"){ fit=!fit; player.videoScalingMode = if (fit) C.VIDEO_SCALING_MODE_SCALE_TO_FIT else C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING }
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
                            Surface(Modifier.clickable{player.seekToDefaultPosition();player.play()}.focusable(),color=Red,shape=RoundedCornerShape(50)){
                                Text("GO LIVE",color=White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=11.dp,vertical=7.dp))
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
                                        Text("VIDEO QUALITY",color=Muted,fontSize=8.sp,fontWeight=FontWeight.Black)
                                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                            listOf(Quality.AUTO to "Auto",Quality.UHD to "4K",Quality.FHD to "1080p",Quality.HD to "720p",Quality.SD to "480p").forEach{(q,l)->Control(quality==q,l){quality=q;pool.setQuality(stream.id,q);menu=null}}
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
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).focusable(),color=Color.Black.copy(alpha=.65f),shape=RoundedCornerShape(8.dp)){
        Text(label,color=White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=10.dp,vertical=7.dp))
    }
}
@Composable private fun PlayerControlButton(label:String,onClick:()->Unit){
    Surface(Modifier.clip(RoundedCornerShape(50)).clickable(onClick=onClick).focusable(),color=if(label=="PAUSE"||label=="PLAY")Red else Color.White.copy(alpha=.12f),shape=RoundedCornerShape(50)){
        Text(label,color=White,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=11.dp,vertical=8.dp))
    }
}
@Composable private fun MenuButton(label:String,onClick:()->Unit){
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick).focusable(),color=Color.White.copy(alpha=.08f),shape=RoundedCornerShape(8.dp)){
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
    Surface(Modifier.size(width = 50.dp, height = 38.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick).focusable(), shape = RoundedCornerShape(9.dp), color = if (selected) Red else Surface2, border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
        Box(Modifier.padding(7.dp), contentAlignment = Alignment.Center) { LayoutGlyph(preset, selected) }
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
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).focusable(), shape = RoundedCornerShape(8.dp), color = if (selected) Red else Surface2) {
        Text(label, color = White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Surface(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).focusable(), shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = .07f)) {
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