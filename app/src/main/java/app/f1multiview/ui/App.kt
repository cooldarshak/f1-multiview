package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.f1multiview.core.playback.Quality
import app.f1multiview.media.PlayerPool
import app.f1multiview.media.SyncEngine
import app.f1multiview.model.*
import app.f1multiview.viewmodel.*
import kotlinx.coroutines.delay

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
    var browser by remember { mutableStateOf(false) }

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
    val sync = remember(pool) { SyncEngine(pool::all) }
    val errors by pool.errors.collectAsState()

    DisposableEffect(pool) { onDispose { pool.release() } }

    LaunchedEffect(ui.streams) {
        pool.retain(ui.streams.map { it.id }.toSet())
        ui.streams.forEach(pool::load)
    }
    LaunchedEffect(sync) {
        while (true) {
            sync.synchronize()
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Header(ui, vm)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { Hero(ui) }
            item { Archive(ui, vm) }
            item { ui.providerError?.let { ErrorBanner(it) } }
            item { PitWall(ui, pool, errors) }
        }
        Controls(ui, vm, pool)
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
private fun PitWall(ui: UiState, pool: PlayerPool, errors: Map<String, String>) {
    val maxFeeds = when (ui.layout) {
        LayoutPreset.SINGLE -> 1
        LayoutPreset.SPLIT_2 -> 2
        LayoutPreset.GRID_4 -> 4
        LayoutPreset.GRID_6 -> 6
    }
    val feeds = ui.streams.take(maxFeeds)
    Spacer(Modifier.height(20.dp))
    SectionHeader("LIVE PIT WALL", "${feeds.size} FEEDS")

    if (feeds.isEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(190.dp),
            shape = RoundedCornerShape(17.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1)
        ) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("NO VIDEO FEEDS", color = White, fontWeight = FontWeight.ExtraBold)
                Text("Select a session to load the authorized F1 TV feeds.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 9.dp)) {
        val columns = when (ui.layout) {
            LayoutPreset.SINGLE -> 1
            LayoutPreset.SPLIT_2, LayoutPreset.GRID_4 -> 2
            LayoutPreset.GRID_6 -> if (maxWidth >= 900.dp) 3 else 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            feeds.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(if (columns == 1) 215.dp else 180.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { stream ->
                        PlayerTile(stream, pool, errors[stream.id], Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PlayerTile(stream: StreamSource, pool: PlayerPool, error: String?, modifier: Modifier) {
    val player = remember(stream.id) { pool.get(stream.id) }
    var playing by remember(stream.id) { mutableStateOf(player.isPlaying) }
    var ready by remember(stream.id) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var quality by rememberSaveable(stream.id) { mutableStateOf(Quality.AUTO) }
    var qualityMenu by remember(stream.id) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) {
                ready = state == Player.STATE_READY
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Card(
        modifier.border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (stream.url != null) {
                AndroidView(
                    factory = { c -> PlayerView(c).apply { useController = false; this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.player = player }
                )
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stream.title, color = White, fontWeight = FontWeight.Bold)
                    Text("CONNECTING…", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = .58f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Red, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        if (stream.isLive) "LIVE" else "REPLAY",
                        color = White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    stream.driver?.takeIf { it.isNotBlank() } ?: stream.title,
                    color = White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !ready -> "LOADING"
                        playing -> "PLAYING"
                        else -> "PAUSED"
                    },
                    color = Color.White.copy(alpha = .6f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (error != null) {
                Surface(
                    Modifier.align(Alignment.Center).padding(12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = .9f),
                    border = BorderStroke(1.dp, Red.copy(alpha = .6f))
                ) {
                    Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYBACK UNAVAILABLE", color = White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            error.replace("PlaybackException: ", "").replace("Source error", "Source unavailable"),
                            color = Muted,
                            fontSize = 8.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Player-local controls: quality is available only after the media has prepared,
            // like a normal video player.
            if (ready) {
                Row(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .86f))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Surface(
                        Modifier.clickable {
                            if (playing) pool.pause(stream.id) else pool.play(stream.id)
                        }.focusable(),
                        shape = RoundedCornerShape(50),
                        color = Red
                    ) {
                        Text(
                            if (playing) "PAUSE" else "PLAY",
                            color = White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Box {
                        Surface(
                            Modifier.clickable { qualityMenu = true }.focusable(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = .72f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                        ) {
                            Text(
                                "QUALITY  ${quality.label()}",
                                color = White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = qualityMenu,
                            onDismissRequest = { qualityMenu = false }
                        ) {
                            listOf(
                                Quality.AUTO to "Auto",
                                Quality.UHD to "4K",
                                Quality.FHD to "1080p",
                                Quality.HD to "720p",
                                Quality.SD to "480p"
                            ).forEach { (option, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (option == quality) "✓  " + label else label,
                                            fontWeight = if (option == quality) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        quality = option
                                        qualityMenu = false
                                        pool.setQuality(stream.id, option)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
private fun Controls(ui: UiState, vm: MultiViewViewModel, pool: PlayerPool) {
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF111217), shadowElevation = 10.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VIEW", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
            listOf(LayoutPreset.SINGLE to "1", LayoutPreset.SPLIT_2 to "2", LayoutPreset.GRID_4 to "4", LayoutPreset.GRID_6 to "6").forEach { (preset, label) ->
                Control(ui.layout == preset, label) { vm.setLayout(preset) }
            }
            DividerV()
            Action("PLAY ALL") { pool.playAll() }
            Action("PAUSE ALL") { pool.pauseAll() }
            Action("TIMING") { vm.panel("timing") }
        }
    }
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
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("\\s+(20[0-9]{2})$"), "")
}
