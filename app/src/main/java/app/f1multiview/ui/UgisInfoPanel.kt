package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import app.f1multiview.SyntheticMultiviewPrototypeActivity
import app.f1multiview.SingleDecoderTileProofActivity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.f1multiview.model.*
import app.f1multiview.media.RadioPlayer
import app.f1multiview.media.DebugPresentationSettings
import app.f1multiview.media.AppLogger
import app.f1multiview.BuildConfig
import app.f1multiview.core.update.AppUpdateManager
import kotlinx.coroutines.launch
import app.f1multiview.viewmodel.MultiViewViewModel
import app.f1multiview.viewmodel.UiState

private val InfoWhite = Color(0xFFF5F5F7)
private val InfoMuted = Color(0xFF9698A2)
private val InfoRed = Color(0xFFE10600)
private val InfoSurface = Color(0xFF101116)
private val InfoSurface2 = Color(0xFF17181E)
private val InfoLine = Color(0xFF292B33)
private val InfoGreen = Color(0xFF58D68D)
private val InfoYellow = Color(0xFFE4C24A)

@Composable
fun UgisInfoPanel(
    ui: UiState,
    vm: MultiViewViewModel,
    radioPlayer: RadioPlayer,
    isTv: Boolean,
    onScreenshotModeChanged: (Boolean) -> Unit
) {
    val section = ui.selectedPanel?.takeIf { it in setOf("timing","tracker","shows","calendar","standings","results","radio","telemetry","updates","saved","settings") } ?: "calendar"
    BackHandler(enabled = true) { vm.panel(null) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val updateManager=remember(context){AppUpdateManager(context)}
    var updateMessage by remember{mutableStateOf("")}
    Surface(Modifier.fillMaxSize(),color=Color.Black.copy(alpha=.96f)) {
        Column(Modifier.fillMaxSize().padding(if(isTv) 18.dp else 12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column { Text("F1  ·  RACE CONTROL",color=InfoWhite,fontSize=18.sp,fontWeight=FontWeight.Black); Text(ui.liveSessionInfo.name+"  ·  "+ui.liveSessionInfo.sessionType,color=InfoMuted,fontSize=8.sp) }
                Spacer(Modifier.weight(1f))
                TextButton({vm.panel(null)}) { Text("CLOSE",color=InfoWhite,fontWeight=FontWeight.Bold) }
            }
            Row(Modifier.fillMaxWidth().focusGroup().background(InfoSurface2).padding(5.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                InfoButton(section=="timing","LIVE TIMING"){vm.panel("timing")}
                InfoButton(section=="telemetry","TELEMETRY"){vm.panel("telemetry")}
                InfoButton(section=="calendar","CALENDAR"){vm.panel("calendar");vm.loadCalendar()}
                InfoButton(section=="standings","STANDINGS"){vm.panel("standings");vm.loadStandings()}
                InfoButton(section=="results","RESULTS"){vm.panel("results");vm.loadResults()}
                InfoButton(section=="shows","SHOWS & DOCS"){vm.panel("shows");vm.loadShowsDocs()}
                InfoButton(section=="radio","RADIO"){vm.panel("radio")}
                InfoButton(section=="tracker","TRACKER"){vm.activateTracker()}
                InfoButton(section=="tracker","RACE MAP"){vm.activateRaceMap()}
                InfoButton(section=="saved","SAVED VIEWS"){vm.panel("saved")}
                InfoButton(section=="settings","SETTINGS"){vm.panel("settings")}
                Row(Modifier.focusGroup(),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("SERIES",color=InfoMuted,fontSize=8.sp,fontWeight=FontWeight.Black)
                    RacingSeries.entries.forEach { series ->
                        InfoButton(ui.selectedSeries==series.id,series.id){vm.setSeries(series.id)}
                    }
                }
                InfoButton(section=="updates","CHECK UPDATES"){vm.panel("updates");scope.launch{updateManager.check().onSuccess{info->if(info==null)updateMessage="You are up to date." else {updateMessage="Update ${info.versionName} available.";updateManager.downloadAndInstall(info)}}.onFailure{updateMessage=it.message.orEmpty()}}}
            }
            Spacer(Modifier.height(8.dp))
            if(section=="saved"){
                var name by remember{mutableStateOf("Race View")}
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(value=name,onValueChange={name=it},label={Text("New view name")},singleLine=true)
                    InfoButton(false,"SAVE CURRENT VIEW"){vm.saveCurrentSetup(name.ifBlank{"Race View"})}
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){
                        items(ui.savedSetups){setup->
                            Surface(color=InfoSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()){
                                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                                    Column(Modifier.weight(1f)){Text(setup.name,color=InfoWhite,fontWeight=FontWeight.Bold);Text(setup.layout.name+" · "+setup.streamIds.size+" feeds",color=InfoMuted,fontSize=9.sp)}
                                    InfoButton(false,"LOAD"){vm.loadSavedSetup(setup)}
                                    InfoButton(false,"DELETE"){vm.deleteSavedSetup(setup.id)}
                                }
                            }
                        }
                    }
                }
            }
            if(section=="settings"){
                if (BuildConfig.DEBUG) {
                    Surface(
                        color = InfoSurface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Single Decoder Tile Proof", color = InfoWhite, fontWeight = FontWeight.Bold)
                                Text("Two clear HEVC tile inputs merged into one video stream. Shows decoder diagnostics on device.", color = InfoMuted, fontSize = 10.sp)
                            }
                            InfoButton(false, "RUN TEST") {
                                context.startActivity(Intent(context, SingleDecoderTileProofActivity::class.java))
                            }
                        }
                    }
                    Surface(
                        color = InfoSurface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Synthetic Multiview Prototype", color = InfoWhite, fontWeight = FontWeight.Bold)
                                Text(
                                    "Debug-only test using three locally generated clear H.264 clips. It does not access F1 or DRM streams.",
                                    color = InfoMuted,
                                    fontSize = 10.sp
                                )
                            }
                            InfoButton(false, "RUN TEST") {
                                context.startActivity(Intent(context, SyntheticMultiviewPrototypeActivity::class.java))
                            }
                        }
                    }
                }
                var screenshotMode by remember { mutableStateOf(DebugPresentationSettings.isScreenshotModeEnabled(context)) }
                var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }
                val logEntries by AppLogger.entries.collectAsState()
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("PLAYBACK SETTINGS",color=InfoWhite,fontSize=14.sp,fontWeight=FontWeight.ExtraBold)
                    Surface(color=InfoSurface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text("Screenshot Mode",color=InfoWhite,fontWeight=FontWeight.Bold)
                                Text(
                                    if (BuildConfig.DEBUG)
                                        "Debug-only. Replaces video pixels with a screenshot-safe placeholder. F1 TV Widevine playback remains on the secure SurfaceView."
                                    else
                                        "Unavailable in release builds.",
                                    color=InfoMuted,fontSize=10.sp
                                )
                            }
                            if(BuildConfig.DEBUG){
                                Switch(
                                    checked=screenshotMode,
                                    onCheckedChange={
                                        screenshotMode=it
                                        onScreenshotModeChanged(it)
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Screenshot Mode does not disable DRM, change the decoder, or reload playback. It masks video pixels so the surrounding UI, controls, focus states and layout can be captured.",
                        color=InfoMuted,fontSize=10.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("DIAGNOSTICS",color=InfoWhite,fontSize=14.sp,fontWeight=FontWeight.ExtraBold)
                    Surface(color=InfoSurface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){
                                    Text("App Logging",color=InfoWhite,fontWeight=FontWeight.Bold)
                                    Text(
                                        if(loggingEnabled) "ON · a new log file is created for this app launch."
                                        else "OFF · no new diagnostic entries are recorded.",
                                        color=InfoMuted,fontSize=10.sp
                                    )
                                    Text(
                                        "Location: Download/F1 MultiView Logs/YYYY-MM-DD/",
                                        color=InfoMuted,fontSize=9.sp
                                    )
                                }
                                Switch(
                                    checked=loggingEnabled,
                                    onCheckedChange={
                                        loggingEnabled=it
                                        AppLogger.setEnabled(context,it)
                                    }
                                )
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                InfoButton(false,"COPY LOGS"){ AppLogger.copyToClipboard(context) }
                                InfoButton(false,"CLEAR LOGS"){ AppLogger.clear() }
                            }
                            Surface(
                                color=Color.Black.copy(alpha=.35f),
                                shape=RoundedCornerShape(6.dp),
                                modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=300.dp)
                            ){
                                LazyColumn(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalArrangement=Arrangement.spacedBy(2.dp)
                                ){
                                    items(logEntries){ line ->
                                        Text(
                                            line,
                                            color=InfoMuted,
                                            fontSize=8.sp,
                                            lineHeight=10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(section=="updates"){
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(updateMessage.ifBlank{"Check the update manifest for a newer APK."},color=InfoWhite,fontSize=12.sp)
                    Text("Updates require the same application signing key as the installed build.",color=InfoMuted,fontSize=10.sp)
                }
            }
            if(section=="radio"){
                val radioState by radioPlayer.state.collectAsState()
                val fallback="https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO.mp3"
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(value=ui.customRadioUrl,onValueChange=vm::setCustomRadioUrl,label={Text("Custom Radio URL")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("Built-in fallback: Grand Prix Radio",color=InfoMuted,fontSize=10.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        InfoButton(radioState.playing,"PLAY RADIO"){radioPlayer.play(ui.customRadioUrl.ifBlank{fallback},ui.radioDelayMs)}
                        InfoButton(false,"STOP"){radioPlayer.stop()}
                        InfoButton(false,"-5s"){vm.setRadioDelayMs(ui.radioDelayMs-5000)}
                        InfoButton(false,"+5s"){vm.setRadioDelayMs(ui.radioDelayMs+5000)}
                        Text("Delay "+(ui.radioDelayMs/1000)+"s",color=InfoWhite,modifier=Modifier.padding(9.dp))
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Checkbox(checked=ui.preferCustomRadio,onCheckedChange=vm::setPreferCustomRadio)
                        Text("Prefer custom radio for live sessions",color=InfoWhite,fontSize=11.sp)
                    }
                }
            }
            when(section) {
                "tracker" -> TrackMapPanel(ui=ui,isTv=isTv,modifier=Modifier.weight(1f).fillMaxWidth())
                "timing" -> LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {
                        Surface(color=InfoSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)){
                                    Text(if(ui.session?.live==false) "REPLAY TIMING" else "LIVE TIMING",color=InfoWhite,fontWeight=FontWeight.Black)
                                    Text(ui.liveSessionInfo.name+" · "+ui.liveSessionInfo.sessionType,color=InfoMuted,fontSize=8.sp)
                                    Text("CLOCK "+ui.sessionClock+"  ·  LAP "+ui.currentLap+"/"+ui.totalLaps+"  ·  TRACK "+ui.trackStatus.label,color=InfoMuted,fontSize=7.sp,fontWeight=FontWeight.Bold)
                                }
                                val statusLabel = if (ui.session?.live == false && !ui.replaySyncCalibrated) "SYNC UNVERIFIED" else ui.timingStatus
                                Text(statusLabel,color=when { ui.timingStatus=="LIVE" -> Color(0xFF66E07A); ui.timingStatus in setOf("STALE","NO_DATA","ERROR","REPLAY_UNAVAILABLE") -> InfoRed; ui.timingStatus=="SUBSCRIBED" -> InfoYellow; ui.session?.live==false && !ui.replaySyncCalibrated -> InfoYellow; else -> InfoMuted },fontSize=9.sp,fontWeight=FontWeight.Black)
                            }
                        }
                    }
                    if (ui.session?.live == false && !ui.replaySyncCalibrated) {
                        item {
                            InfoRow(
                                "SYNC",
                                "Replay timing offset unverified",
                                "The archive loaded, but video-to-timing alignment has not been verified. Offset buttons adjust timing but do not prove alignment.",
                                "UNVERIFIED"
                            )
                        }
                    }
                    item { InfoRow("WX","Weather","Air "+ui.weather.air+" °C · Track "+ui.weather.track+" °C · Humidity "+ui.weather.humidity+"%","Wind "+ui.weather.wind+" "+ui.weather.windDirection+" · Rain "+ui.weather.rainfall) }
                    item {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp, vertical=3.dp),
                            horizontalArrangement=Arrangement.spacedBy(14.dp)
                        ) {
                            listOf("POS / DRIVER","INT","GAP","LAST LAP","BEST LAP","SPEED","TYRE","PIT").forEach {
                                Text(it,color=InfoMuted,fontSize=7.sp,fontWeight=FontWeight.Black)
                            }
                        }
                    }
                    items(ui.timing){r->
                        F1TimingRow(r,ui.driverInfo.firstOrNull { it.number == r.driverNumber },isTv)
                    }
                    if(ui.session?.live==false){
                        item{
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                                Text("REPLAY OFFSET "+(ui.syncOffsetMs/1000)+"s",color=InfoMuted,fontSize=9.sp)
                                InfoButton(false,"-1s"){vm.sync(-1000)}
                                InfoButton(false,"-250ms"){vm.sync(-250)}
                                InfoButton(false,"+250ms"){vm.sync(250)}
                                InfoButton(false,"+1s"){vm.sync(1000)}
                            }
                        }
                    }
                    if(ui.raceControl.isNotEmpty()){
                        item { Text("RACE CONTROL",color=InfoMuted,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=8.dp)) }
                        items(ui.raceControl){e->
                            InfoRow(e.time,e.message,e.severity,"")
                        }
                    }
                    if(ui.teamRadio.isNotEmpty()){
                        item { Text("TEAM RADIO",color=InfoMuted,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=8.dp)) }
                        items(ui.teamRadio){r->
                            Surface(onClick={radioPlayer.play(r.url,ui.radioDelayMs)},color=InfoSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().focusable()){
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                                    Text("RADIO",color=InfoRed,fontWeight=FontWeight.Black,modifier=Modifier.width(52.dp))
                                    Column(Modifier.weight(1f)){Text(r.driver.ifBlank{"Driver"},color=InfoWhite,fontWeight=FontWeight.Bold);Text(r.time,color=InfoMuted,fontSize=10.sp)}
                                    Text("PLAY",color=InfoWhite,fontSize=9.sp,fontWeight=FontWeight.Black)
                                }
                            }
                        }
                    }
                }
                "telemetry" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    item { InfoRow("SESSION",ui.liveSessionInfo.name,ui.liveSessionInfo.meeting+" · "+ui.liveSessionInfo.country,ui.liveSessionInfo.sessionType) }
                    items(ui.telemetry){t->
                        InfoRow(t.driver,"SPEED "+t.speed+" km/h","RPM "+t.rpm+" · GEAR "+t.gear+" · THR "+t.throttle+"% · BRK "+t.brake+"%","LAP "+t.lap+" · "+t.lapTime)
                    }
                }
                "calendar" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.calendar){r-> InfoRow(r.round.toString(),r.name,listOf(r.circuit,r.location).filter{it.isNotBlank()}.joinToString(" · "),r.date)}
                }
                "standings" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.standings){r-> InfoRow(r.position,r.name,r.constructor,r.points+" pts")}
                }
                "shows" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(ui.showsDocs){r->
                        Surface(onClick={vm.setSession(Session(r.contentId,r.title,"F1","Editorial",false,series="F1",sessionType="show",artworkUrl=r.artworkUrl))},color=InfoSurface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                            Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                                Text("F1",color=InfoRed,fontWeight=FontWeight.Black,modifier=Modifier.width(38.dp))
                                Column(Modifier.weight(1f)){Text(r.title,color=InfoWhite,fontWeight=FontWeight.Bold);Text("F1 TV EDITORIAL · PLAY",color=InfoMuted,fontSize=9.sp)}
                            }
                        }
                    }
                }
                "settings" -> {}
                else -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.results){r-> InfoRow(r.position,r.name,r.constructor+" · "+r.status,r.points+" pts")}
                }
            }
        }
    }
}

@Composable private fun InfoButton(selected:Boolean,label:String,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Surface(onClick=onClick,modifier=Modifier.focusable().onFocusChanged{focused=it.isFocused},shape=RoundedCornerShape(8.dp),color=if(selected)InfoRed else InfoSurface,border=if(focused)BorderStroke(2.dp,InfoWhite) else null){
        Text(label,color=InfoWhite,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=14.dp,vertical=9.dp))
    }
}
@Composable
private fun F1TimingRow(r: TimingRow, driver: DriverInfo?, isTv: Boolean) {
    val team = runCatching { Color(android.graphics.Color.parseColor("#" + (driver?.teamColor ?: "FFFFFF").removePrefix("#").padStart(6, 'F'))) }
        .getOrDefault(InfoWhite)
    val width = if (isTv) 760.dp else 680.dp
    Surface(
        color = if (r.position % 2 == 0) InfoSurface else InfoSurface2,
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp, vertical=6.dp)
        ) {
            Row(
                Modifier.width(width),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = if (r.position == 1) InfoRed else InfoSurface,
                    shape = RoundedCornerShape(2.dp),
                    border = if (r.position == 1) null else BorderStroke(1.dp, InfoLine)
                ) {
                    Text(r.position.toString().padStart(2, '0'), color=InfoWhite, fontSize=10.sp, fontWeight=FontWeight.Black,
                        modifier=Modifier.width(28.dp).padding(vertical=5.dp), textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
                Box(Modifier.width(3.dp).height(30.dp).background(team))
                Column(Modifier.width(92.dp)) {
                    Text(r.driver, color=InfoWhite, fontSize=11.sp, fontWeight=FontWeight.Black, maxLines=1)
                    Text(driver?.team ?: ("CAR " + r.driverNumber), color=InfoMuted, fontSize=7.sp, maxLines=1)
                }
                TimingMetric("GEAR", r.gear.takeIf { it > 0 }?.toString() ?: "—", 36.dp, InfoWhite)
                TimingMetric("RPM", r.rpm.takeIf { it > 0 }?.toString() ?: "—", 54.dp, InfoWhite)
                TimingMetric("KM/H", r.speed, 52.dp, InfoWhite)
                TimingMetric("DRS", when (r.drsActive) { true -> "ON"; false -> "OFF"; null -> "—" }, 42.dp,
                    if (r.drsActive == true) InfoGreen else InfoWhite)
                TimingMetric("INT", if (r.position == 1) "LEADER" else r.interval, 70.dp, InfoWhite)
                TimingMetric("GAP", r.leaderGap, 72.dp, InfoWhite)
                TimingMetric("LAST", when { r.inPit -> "IN PIT"; r.pitOut -> "PIT OUT"; else -> r.lastLap }, 76.dp,
                    if (r.inPit) InfoYellow else InfoWhite)
                TimingMetric("BEST", r.bestLap, 76.dp, timingStatusColor(r.bestLapStatus))
                Column(Modifier.width(54.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("TYRE",color=InfoMuted,fontSize=6.sp,fontWeight=FontWeight.Black)
                    Text(r.tyre.takeIf { it.isNotBlank() && it != "-" } ?: "—",
                        color=compoundStatusColor(r.tyre),fontSize=8.sp,fontWeight=FontWeight.Black,maxLines=1)
                    if (r.stintNumber > 0) Text("STINT "+r.stintNumber+r.stintLaps?.let { " · "+it+"L" }.orEmpty(),color=InfoMuted,fontSize=5.sp,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.width(62.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("STOPS",color=InfoMuted,fontSize=6.sp,fontWeight=FontWeight.Black)
                    val pitLabel = buildString {
                        append(r.pitStops)
                        if (r.inPit) append(" · IN")
                        else if (r.pitOut) append(" · OUT")
                        if (r.stopped) append(" · STOP")
                        if (r.retired) append(" · RET")
                    }
                    Text(pitLabel,color=if(r.inPit||r.stopped) InfoYellow else InfoWhite,fontSize=7.sp,fontWeight=FontWeight.Black,maxLines=1)
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(
                Modifier.width(width),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectorCell("S1",r.sector1,r.sector1Status,r.sector1Segments)
                SectorCell("S2",r.sector2,r.sector2Status,r.sector2Segments)
                SectorCell("S3",r.sector3,r.sector3Status,r.sector3Segments)
                Spacer(Modifier.weight(1f))
                if (r.bestLapStatus == "PURPLE") {
                    Text("OVERALL BEST",color=Color(0xFFD36BFF),fontSize=7.sp,fontWeight=FontWeight.Black)
                } else if (r.bestLapStatus == "GREEN") {
                    Text("PERSONAL BEST",color=InfoGreen,fontSize=7.sp,fontWeight=FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun TimingMetric(label: String, value: String, width: androidx.compose.ui.unit.Dp, valueColor: Color) {
    Column(Modifier.width(width)) {
        Text(label,color=InfoMuted,fontSize=6.sp,fontWeight=FontWeight.Black)
        Text(value.ifBlank { "—" },color=valueColor,fontSize=9.sp,fontWeight=FontWeight.Bold,maxLines=1)
    }
}

@Composable
private fun SectorCell(label: String, value: String, status: String, segments: List<String>) {
    Column(Modifier.width(112.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,color=InfoMuted,fontSize=7.sp,fontWeight=FontWeight.Black)
            Text(value.ifBlank { "—" },color=timingStatusColor(status),fontSize=8.sp,fontWeight=FontWeight.Bold,maxLines=1)
        }
        if (segments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(5.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                segments.forEach { segment ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(timingStatusColor(segment)))
                }
            }
        }
    }
}

private fun timingStatusColor(status: String): Color = when (status.uppercase()) {
    "PURPLE" -> Color(0xFFD36BFF)
    "GREEN" -> InfoGreen
    "YELLOW" -> InfoYellow
    "BLUE" -> Color(0xFF4AA3FF)
    "GRAY","GREY" -> Color(0xFF454953)
    else -> InfoWhite
}

private fun compoundStatusColor(compound: String): Color = when {
    compound.contains("SOFT",true) -> Color(0xFFE10600)
    compound.contains("MEDIUM",true) -> InfoYellow
    compound.contains("HARD",true) -> InfoWhite
    compound.contains("INTER",true) -> InfoGreen
    compound.contains("WET",true) -> Color(0xFF4AA3FF)
    else -> InfoMuted
}

@Composable private fun InfoRow(pos:String,title:String,sub:String,meta:String){
    Surface(color=InfoSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            Text(pos,color=InfoRed,fontWeight=FontWeight.Black,modifier=Modifier.width(38.dp))
            Column(Modifier.weight(1f)){Text(title,color=InfoWhite,fontWeight=FontWeight.Bold);if(sub.isNotBlank())Text(sub,color=InfoMuted,fontSize=10.sp)}
            Text(meta,color=InfoWhite.copy(alpha=.78f),fontSize=10.sp)
        }
    }
}
