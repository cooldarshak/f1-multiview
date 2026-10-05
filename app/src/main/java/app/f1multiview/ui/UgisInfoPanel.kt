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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.f1multiview.model.*
import app.f1multiview.viewmodel.MultiViewViewModel
import app.f1multiview.viewmodel.UiState

private val InfoWhite = Color(0xFFF5F5F7)
private val InfoMuted = Color(0xFF9698A2)
private val InfoRed = Color(0xFFE10600)
private val InfoSurface = Color(0xFF14151B)

@Composable
fun UgisInfoPanel(ui: UiState, vm: MultiViewViewModel, isTv: Boolean) {
    val section = ui.selectedPanel?.takeIf { it in setOf("calendar","standings","results") } ?: "calendar"
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
            }
            Spacer(Modifier.height(14.dp))
            when(section) {
                "calendar" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.calendar){r-> InfoRow(r.round.toString(),r.name,listOf(r.circuit,r.location).filter{it.isNotBlank()}.joinToString(" · "),r.date)}
                }
                "standings" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(ui.standings){r-> InfoRow(r.position,r.name,r.constructor,r.points+" pts")}
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
