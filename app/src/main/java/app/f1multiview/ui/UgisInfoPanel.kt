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
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import app.f1multiview.model.*
import app.f1multiview.media.RadioPlayer
import app.f1multiview.media.DebugPresentationSettings
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
                var screenshotMode by remember { mutableStateOf(DebugPresentationSettings.isScreenshotModeEnabled(context)) }
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("PLAYBACK SETTINGS",color=InfoWhite,fontSize=14.sp,fontWeight=FontWeight.ExtraBold)
                    Surface(color=InfoSurface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text("Screenshot Mode",color=InfoWhite,fontWeight=FontWeight.Bold)
                                Text(
                                    if (BuildConfig.DEBUG)
                                        "Debug-only. Clear/non-DRM test media may use TextureView; protected F1 TV video stays on the secure SurfaceView."
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
                        "For F1 TV Widevine playback, Screenshot Mode cannot capture the protected video frame. It will keep playback visible instead of switching the protected feed to a black TextureView.",
                        color=InfoMuted,fontSize=10.sp
                    )
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
                "tracker" -> TrackMapPanel(ui=ui,isTv=isTv)
                "timing" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {
                        Surface(color=InfoSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)){
                                    Text(if(ui.session?.live==false) "REPLAY TIMING" else "LIVE TIMING",color=InfoWhite,fontWeight=FontWeight.Black)
                                    Text(ui.liveSessionInfo.name+" · "+ui.liveSessionInfo.sessionType,color=InfoMuted,fontSize=8.sp)
                                }
                                Text(ui.timingStatus,color=if(ui.timingStatus=="LIVE") Color(0xFF66E07A) else InfoMuted,fontSize=9.sp,fontWeight=FontWeight.Black)
                            }
                        }
                    }
                    item { InfoRow("WX","Weather","Air "+ui.weather.air+" · Track "+ui.weather.track+" · Humidity "+ui.weather.humidity,"Wind "+ui.weather.wind) }
                    items(ui.timing){r->
                        F1TimingRow(r)
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
                        InfoRow(t.driver,"SPEED "+t.speed+" km/h","RPM "+t.rpm+" · GEAR "+t.gear+" · THR "+t.throttle+"% · BRK "+t.brake+"%","LAP "+t.lap+" · "+if(t.drs)"DRS" else "DRS OFF")
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
@Composable private fun F1TimingRow(r:TimingRow){
    Surface(color=if(r.position%2==0) InfoSurface else InfoSurface2,shape=RoundedCornerShape(1.dp),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
            Text(r.position.toString(),color=InfoRed,fontWeight=FontWeight.Black,fontSize=10.sp,modifier=Modifier.width(34.dp))
            Column(Modifier.weight(1f)){
                Text(r.driver,color=InfoWhite,fontWeight=FontWeight.Bold,fontSize=11.sp)
                Text("P"+r.position+"  ·  "+r.speed+" km/h",color=InfoMuted,fontSize=7.sp)
            }
            Text(r.gap,color=InfoWhite,fontSize=9.sp,modifier=Modifier.width(72.dp))
            Text(r.lastLap,color=InfoWhite,fontSize=9.sp,modifier=Modifier.width(72.dp))
            Text("S1 "+r.sector1+"  S2 "+r.sector2+"  S3 "+r.sector3,color=InfoWhite.copy(alpha=.82f),fontSize=8.sp,modifier=Modifier.width(220.dp))
            Text(r.tyre,color=if(r.tyre.contains("S",true)) InfoRed else if(r.tyre.contains("M",true)) InfoYellow else InfoWhite,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.width(58.dp))
        }
    }
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
