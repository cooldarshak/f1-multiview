package app.f1multiview.ui

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import app.f1multiview.model.*
import app.f1multiview.viewmodel.UiState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val MapBg=Color(0xFF07080B)
private val MapTrack=Color(0xFF454953)
private val MapTrackEdge=Color(0xFF17191E)
private val MapWhite=Color(0xFFF5F5F7)
private val MapMuted=Color(0xFF9698A2)
private val MapRed=Color(0xFFE10600)

@Composable
fun TrackMapPanel(ui: UiState, isTv: Boolean) {
    val drivers = ui.trackPositions
    var selected by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var focusSelected by remember { mutableStateOf(false) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val mapFocusRequester = remember { FocusRequester() }

    LaunchedEffect(drivers) {
        if (selected == null || drivers.none { it.number == selected }) {
            selected = drivers.firstOrNull()?.number
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFF101010))
            .focusGroup()
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { selected = previousDriver(drivers, selected); focusSelected = true; true }
                    Key.DirectionRight -> { selected = nextDriver(drivers, selected); focusSelected = true; true }
                    Key.DirectionUp -> { zoom = min(4f, zoom + 0.25f); true }
                    Key.DirectionDown -> { zoom = max(1f, zoom - 0.25f); true }
                    Key.Enter, Key.NumPadEnter -> { focusSelected = !focusSelected; true }
                    else -> false
                }
            }
    ) {
        TrackCanvas(
            geometry = ui.trackGeometry,
            drivers = drivers,
            selectedNumber = selected,
            zoom = zoom,
            focusSelected = focusSelected,
            panX = panX,
            panY = panY,
            modifier = Modifier.fillMaxSize().padding(if (isTv) 10.dp else 4.dp)
        )
        if (ui.trackGeometry?.centerline.isNullOrEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("CIRCUIT GEOMETRY UNAVAILABLE", color = MapWhite, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(
                    if (ui.liveSessionInfo.circuitKey == null) "Waiting for session circuit metadata" else "No validated centreline matched this circuit",
                    color = MapMuted, fontSize = 8.sp
                )
            }
        }
        // Minimal controls stay out of the way of the track, matching the supplied map reference.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TrackControl("−") { zoom = max(1f, zoom - 0.25f) }
            TrackControl("+") { zoom = min(4f, zoom + 0.25f) }
            TrackControl("RESET") { zoom = 1f; panX = 0f; panY = 0f; focusSelected = false }
        }
    }
}

@Composable
private fun RaceHeader(ui:UiState,carCount:Int){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
            Text(ui.liveSessionInfo.meeting.ifBlank{"FORMULA 1"}.uppercase()+"  ·  "+ui.liveSessionInfo.sessionType.uppercase(),color=MapWhite,fontSize=13.sp,fontWeight=FontWeight.Black)
            Text(ui.liveSessionInfo.name+"  ·  LAP DATA / LIVE GPS",color=MapMuted,fontSize=8.sp,fontWeight=FontWeight.Bold)
        }
        RaceStat("CARS",carCount.toString());RaceStat("STATUS",ui.trackStatus.label);RaceStat("GPS",if(carCount>0)"LIVE" else "WAIT")
    }
}
@Composable private fun RaceStat(label:String,value:String){
    Column(Modifier.padding(horizontal=8.dp).widthIn(min=48.dp),horizontalAlignment=Alignment.End){
        Text(label,color=MapMuted,fontSize=7.sp,fontWeight=FontWeight.Bold)
        Text(value,color=MapWhite,fontSize=9.sp,fontWeight=FontWeight.Black)
    }
}
@Composable private fun RaceLeaderboard(drivers:List<TrackDriverPosition>,selected:String?,onSelect:(String)->Unit){
    Column(Modifier.fillMaxWidth()){
        Text("LIVE TIMING",color=MapMuted,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(bottom=4.dp))
        Surface(Modifier.fillMaxWidth(),color=Color.White.copy(alpha=.035f),shape=RoundedCornerShape(4.dp)){
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=430.dp),verticalArrangement=Arrangement.spacedBy(1.dp)){
                items(drivers){d->RaceDriverRow(d,d.number==selected){onSelect(d.number)}}
            }
        }
    }
}
@Composable private fun RaceDriverRow(d:TrackDriverPosition,selected:Boolean,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Surface(onClick=onClick,color=when{selected->MapRed.copy(alpha=.9f);focused->Color.White.copy(alpha=.10f);else->Color.White.copy(alpha=.025f)},shape=RoundedCornerShape(2.dp),modifier=Modifier.fillMaxWidth().focusable().onFocusChanged{focused=it.isFocused}){
        Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(d.position>0)d.position.toString() else "—",color=if(selected)MapWhite else MapMuted,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.width(22.dp))
            Text(d.acronym.uppercase(),color=MapWhite,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.width(36.dp))
            Box(Modifier.width(4.dp).height(13.dp).background(teamColor(d.teamColor),RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)){
                Text(d.team,color=if(selected)MapWhite else MapMuted,fontSize=7.sp,maxLines=1)
                Text("L"+d.lap+"  "+if(d.inPit)"PIT" else if(d.stopped)"STOP" else "RUN",color=if(selected)MapWhite else MapMuted,fontSize=7.sp,fontWeight=FontWeight.Bold)
            }
            Column(horizontalAlignment=Alignment.End){
                Text(if(d.speed>0)d.speed.toString()+" km/h" else "—",color=MapWhite,fontSize=7.sp)
                Text(if(d.position>0)"P"+d.position else "—",color=MapMuted,fontSize=7.sp)
            }
        }
    }
}
@Composable private fun RaceControlCompact(events:List<RaceControlEvent>){
    Column(Modifier.fillMaxWidth()){
        Text("RACE CONTROL",color=MapMuted,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(bottom=4.dp))
        Surface(Modifier.fillMaxWidth().heightIn(min=70.dp,max=150.dp),color=Color.White.copy(alpha=.035f),shape=RoundedCornerShape(4.dp)){
            if(events.isEmpty()) Text("No race-control messages",color=MapMuted,fontSize=8.sp,modifier=Modifier.padding(8.dp))
            else LazyColumn(Modifier.fillMaxWidth()){items(events.takeLast(5).asReversed()){e->
                Column(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=5.dp)){
                    Row(Modifier.fillMaxWidth()){Text(e.time,color=MapMuted,fontSize=7.sp,modifier=Modifier.weight(1f));Text(e.severity.uppercase(),color=raceSeverityColor(e.severity),fontSize=7.sp,fontWeight=FontWeight.Black)}
                    Text(e.message,color=MapWhite,fontSize=8.sp,maxLines=2)
                }
            }}
        }
    }
}
private fun raceSeverityColor(value:String)=when(value.lowercase()){
    "red","critical"->Color(0xFFE10600);"yellow","warning"->Color(0xFFD9A400);"green"->Color(0xFF35B968);else->MapMuted
}

@Composable private fun TrackCanvas(
    geometry:TrackMapGeometry?,
    drivers:List<TrackDriverPosition>,
    selectedNumber:String?,
    zoom:Float,
    focusSelected:Boolean,
    panX:Float,
    panY:Float,
    modifier:Modifier
){
    Canvas(modifier){
        // Refuse to draw a cluster of GPS dots as if it were a circuit. The marker coordinates
        // are only meaningful when the matching validated centreline has been resolved.
        val circuit = geometry?.centerline.orEmpty()
        if(circuit.size < 20)return@Canvas
        val selected=drivers.firstOrNull{it.number==selectedNumber}
        val trackPoints=mutableListOf<TrackPoint>().apply {
            addAll(circuit)
            geometry?.corners?.forEach{add(TrackPoint(it.x,it.y))}
        }
        val cx0=if(focusSelected && selected!=null)selected.x else trackPoints.map{it.x}.average()
        val cy0=if(focusSelected && selected!=null)selected.y else trackPoints.map{it.y}.average()
        val rot=-(geometry?.rotation ?: 0.0)*Math.PI/180.0
        fun transform(p:TrackPoint):TrackPoint{
            val dx=p.x-cx0
            val dy=p.y-cy0
            val rx=dx*cos(rot)-dy*sin(rot)
            val ry=dx*sin(rot)+dy*cos(rot)
            return TrackPoint(rx,ry)
        }
        // Scale and centre are derived from fixed circuit geometry only. Moving cars must not
        // make the whole map zoom, jitter, or recenter on every telemetry packet.
        val transformedTrack=trackPoints.map(::transform)
        val minX=transformedTrack.minOf{it.x};val maxX=transformedTrack.maxOf{it.x}
        val minY=transformedTrack.minOf{it.y};val maxY=transformedTrack.maxOf{it.y}
        val span=max(maxX-minX,maxY-minY).coerceAtLeast(1.0)
        val scale=min(size.width,size.height)*.78f/span.toFloat()*zoom
        val center=Offset(size.width/2f + panX*size.width,size.height/2f + panY*size.height)
        fun screen(p:TrackPoint):Offset{
            val t=transform(p)
            return Offset(center.x+t.x.toFloat()*scale,center.y-t.y.toFloat()*scale)
        }

        val centerline=geometry?.centerline.orEmpty().map(::screen)
        if(centerline.size >= 20){
            val path=Path()
            path.moveTo(centerline.first().x,centerline.first().y)
            centerline.drop(1).forEach{path.lineTo(it.x,it.y)}
            drawPath(path,color=MapTrackEdge,style=Stroke(width=18f))
            drawPath(path,color=MapTrack,style=Stroke(width=11f))
        }
        // Corner labels are annotations anchored to the same telemetry coordinate system;
        // they are never joined into a synthetic polygon.
        geometry?.corners?.forEach{corner->
            if(corner.number<=0)return@forEach
            val p=screen(TrackPoint(corner.x,corner.y))
            drawCircle(Color.White.copy(alpha=.55f),radius=7f,center=p)
            drawIntoCanvas{
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    color=android.graphics.Color.WHITE
                    textSize=18f
                    textAlign=Paint.Align.CENTER
                    typeface=android.graphics.Typeface.DEFAULT_BOLD
                }
                drawContext.canvas.nativeCanvas.drawText(corner.number.toString()+corner.letter,p.x,p.y-10f,paint)
            }
        }

        drivers.forEach{d->
            val p=screen(TrackPoint(d.x,d.y))
            val color=teamColor(d.teamColor)
            d.trail.takeLast(6).forEachIndexed{index,t->
                val alpha=(index+1)/10f
                drawCircle(color.copy(alpha=alpha),radius=2.5f,center=screen(t))
            }
            // Square telemetry markers match the supplied MultiViewer map instead of oversized
            // decorative bubbles. All positions remain sourced from the live timing feed.
            if(d.number==selectedNumber){
                drawRect(
                    color = MapWhite,
                    topLeft = Offset(p.x - 6.5f, p.y - 6.5f),
                    size = androidx.compose.ui.geometry.Size(13f, 13f),
                    style = Stroke(width = 2f)
                )
            }
            drawRect(
                color = color,
                topLeft = Offset(p.x - 4f, p.y - 4f),
                size = androidx.compose.ui.geometry.Size(8f, 8f)
            )
            drawIntoCanvas{canvas->
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    this.color=android.graphics.Color.WHITE
                    textSize=13f
                    textAlign=Paint.Align.CENTER
                    typeface=android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(4f,0f,0f,android.graphics.Color.BLACK)
                }
                drawContext.canvas.nativeCanvas.drawText(d.acronym,p.x,p.y-7f,paint)
            }
        }
    }
}

@Composable private fun TrackDriverButton(d:TrackDriverPosition,selected:Boolean,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Surface(onClick=onClick,modifier=Modifier.focusable().onFocusChanged{focused=it.isFocused},
        shape=RoundedCornerShape(8.dp),color=if(selected)MapRed else Color.White.copy(alpha=.08f),
        border=if(focused)BorderStroke(2.dp,MapWhite) else null){
        Column(Modifier.padding(horizontal=9.dp,vertical=6.dp)){
            Text(d.acronym,color=MapWhite,fontSize=9.sp,fontWeight=FontWeight.Black)
            Text(if(d.position>0)"P"+d.position else "—",color=MapMuted,fontSize=7.sp)
        }
    }
}
@Composable private fun TrackControl(label:String,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Surface(onClick=onClick,modifier=Modifier.focusable().onFocusChanged{focused=it.isFocused},
        shape=RoundedCornerShape(8.dp),color=Color.White.copy(alpha=.08f),
        border=if(focused)BorderStroke(2.dp,MapWhite) else null){
        Text(label,color=MapWhite,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=10.dp,vertical=8.dp))
    }
}
@Composable private fun MapBadge(label:String,color:Color){
    Surface(color=color,shape=RoundedCornerShape(5.dp)){Text(label,color=Color.White,fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp))}
}
private fun previousDriver(drivers:List<TrackDriverPosition>,selected:String?):String?{
    if(drivers.isEmpty())return null
    val i=drivers.indexOfFirst{it.number==selected}
    return drivers[if(i<=0)drivers.lastIndex else i-1].number
}
private fun nextDriver(drivers:List<TrackDriverPosition>,selected:String?):String?{
    if(drivers.isEmpty())return null
    val i=drivers.indexOfFirst{it.number==selected}
    return drivers[if(i<0||i==drivers.lastIndex)0 else i+1].number
}
private fun trackStatusColor(code:Int)=when(code){2->Color(0xFFD9A400);4,6,7->Color(0xFFFF8C00);5->Color(0xFFE10600);else->Color(0xFF2E9D57)}
private fun teamColor(hex:String):Color=runCatching{
    Color(android.graphics.Color.parseColor("#"+hex.removePrefix("#").padStart(6,'F')))
}.getOrDefault(Color.White)


@Composable
fun CompactDriverTrackerFeed(ui: UiState, isTv: Boolean, modifier: Modifier = Modifier) {
    val drivers = ui.trackPositions
    val selected = drivers.firstOrNull()?.number
    Column(
        modifier.fillMaxSize().background(MapBg).padding(if (isTv) 5.dp else 3.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("DRIVER TRACKER", color = MapWhite, fontSize = if (isTv) 9.sp else 8.sp, fontWeight = FontWeight.Black)
                Text(
                    ui.liveSessionInfo.name.ifBlank { "LIVE TRACK" } + " · " + ui.trackStatus.label,
                    color = MapMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
            Text("${drivers.size} CARS", color = MapMuted, fontSize = 6.sp, fontWeight = FontWeight.Black)
        }
        if (drivers.isEmpty()) {
            val timingRows = ui.timing.sortedBy { it.position }.take(10)
            if (timingRows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WAITING FOR TRACK DATA", color = MapMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text("F1 did not provide GPS positions for this session yet", color = MapMuted, fontSize = 6.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(5.dp)) {
                    Text("TRACK POSITION UNAVAILABLE · LIVE TIMING ACTIVE", color = MapMuted, fontSize = 6.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    timingRows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.position.toString().padStart(2, '0'), color = MapWhite, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(18.dp))
                            Text(row.driver, color = MapWhite, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(34.dp))
                            Text(row.gap, color = MapMuted, fontSize = 6.sp, modifier = Modifier.weight(1f))
                            Text(row.lastLap, color = MapWhite, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            Surface(
                Modifier.fillMaxWidth().weight(1f),
                color = MapBg,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
            ) {
                TrackCanvas(
                    geometry = ui.trackGeometry,
                    drivers = drivers,
                    selectedNumber = selected,
                    zoom = 1f,
                    focusSelected = false,
                    panX = 0f,
                    panY = 0f,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                drivers.take(6).forEach { d ->
                    Surface(
                        color = teamColor(d.teamColor).copy(alpha = .9f),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            d.acronym.uppercase(),
                            color = Color.White,
                            fontSize = 6.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
