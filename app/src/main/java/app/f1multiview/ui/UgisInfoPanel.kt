package app.f1multiview.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.f1multiview.model.*
import app.f1multiview.media.RadioPlayer
import app.f1multiview.core.update.AppUpdateManager
import kotlinx.coroutines.launch
import app.f1multiview.viewmodel.MultiViewViewModel
import app.f1multiview.viewmodel.UiState

private val InfoWhite = Color(0xFFF5F5F7)
private val InfoMuted = Color(0xFF9698A2)
private val InfoRed = Color(0xFFE10600)
private val InfoSurface = Color(0xFF14151B)

@Composable
fun UgisInfoPanel(ui: UiState, vm: MultiViewViewModel, radioPlayer: RadioPlayer, isTv: Boolean) {
    val section = ui.selectedPanel?.takeIf { it in setOf("shows","calendar","standings","results","radio","updates","saved") } ?: "calendar"
    val scope=rememberCoroutineScope()
    val updateManager=remember{AppUpdateManager(LocalContext.current)}
    var updateMessage by remember{mutableStateOf("")}
    Surface(Modifier.fillMaxSize(),color=Color.Black.copy(alpha=.96f)) {
        Column(Modifier.fillMaxSize().padding(if(isTv) 28.dp else 18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("F1 INFO",color=InfoWhite,fontSize=22.sp,fontWeight=FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                TextButton({vm.panel(null)}) { Text("CLOSE",color=InfoWhite,fontWeight=FontWeight.Bold) }
            }
            Row(Modifier.fillMaxWidth().focusGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                InfoButton(section=="calendar","CALENDAR"){vm.panel("calendar");vm.loadCalendar()}
                InfoButton(section=="standings","STANDINGS"){vm.panel("standings");vm.loadStandings()}
                InfoButton(section=="results","RESULTS"){vm.panel("results");vm.loadResults()}
                InfoButton(section=="shows","SHOWS & DOCS"){vm.panel("shows");vm.loadShowsDocs()}
                InfoButton(section=="radio","RADIO"){vm.panel("radio")}
                InfoButton(section=="saved","SAVED VIEWS"){vm.panel("saved")}
                InfoButton(section=="updates","CHECK UPDATES"){vm.panel("updates");scope.launch{updateManager.check().onSuccess{info->if(info==null)updateMessage="You are up to date." else {updateMessage="Update ${info.versionName} available.";updateManager.downloadAndInstall(info)}}.onFailure{updateMessage=it.message.orEmpty()}}}
            }
            Spacer(Modifier.height(14.dp))
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
                "calendar" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.calendar){r-> InfoRow(r.round.toString(),r.name,listOf(r.circuit,r.location).filter{it.isNotBlank()}.joinToString(" · "),r.date)}
                }
                "standings" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.standings){r-> InfoRow(r.position,r.name,r.constructor,r.points+" pts")}
                }
                "shows" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(ui.showsDocs){r->
                        Surface(color=InfoSurface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                            Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                                Text("F1",color=InfoRed,fontWeight=FontWeight.Black,modifier=Modifier.width(38.dp))
                                Column(Modifier.weight(1f)){Text(r.title,color=InfoWhite,fontWeight=FontWeight.Bold);Text("F1 TV EDITORIAL",color=InfoMuted,fontSize=9.sp)}
                            }
                        }
                    }
                }
                else -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.results){r-> InfoRow(r.position,r.name,r.constructor+" · "+r.status,r.points+" pts")}
                }
            }
        }
    }
}

@Composable private fun InfoButton(selected:Boolean,label:String,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Surface(Modifier.onFocusChanged{focused=it.isFocused},shape=RoundedCornerShape(8.dp),color=if(selected)InfoRed else InfoSurface,border=if(focused)BorderStroke(2.dp,InfoWhite) else null,onClick=onClick){
        Text(label,color=InfoWhite,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=14.dp,vertical=9.dp))
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
