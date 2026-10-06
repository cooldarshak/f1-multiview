package app.f1multiview.ui

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
fun TrackMapPanel(ui:UiState,isTv:Boolean){
    val drivers=ui.trackPositions
    var selected by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var focusSelected by remember { mutableStateOf(false) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val mapFocusRequester=remember{FocusRequester()}

    LaunchedEffect(Unit){ mapFocusRequester.requestFocus() }
    LaunchedEffect(drivers) {
        if(selected==null || drivers.none{it.number==selected}) selected=drivers.firstOrNull()?.number
    }

    Column(
        Modifier.fillMaxSize().background(MapBg).padding(if(isTv) 6.dp else 2.dp).focusGroup()
            .onPreviewKeyEvent {
                if(it.type!=KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when(it.key){
                    Key.DirectionLeft -> { selected=previousDriver(drivers,selected); focusSelected=true; true }
                    Key.DirectionRight -> { selected=nextDriver(drivers,selected); focusSelected=true; true }
                    Key.DirectionUp -> { zoom=min(4f,zoom+0.25f); true }
                    Key.DirectionDown -> { zoom=max(1f,zoom-0.25f); true }
                    Key.Enter, Key.NumPadEnter -> { focusSelected=!focusSelected; true }
                    else -> false
                }
            }
    ){
        RaceHeader(ui,drivers.size)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Surface(Modifier.weight(1.65f).fillMaxHeight().focusRequester(mapFocusRequester).focusable(),color=MapBg,shape=RoundedCornerShape(5.dp),border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))){
                TrackCanvas(geometry=ui.trackGeometry,drivers=drivers,selectedNumber=selected,zoom=zoom,focusSelected=focusSelected,panX=panX,panY=panY,modifier=Modifier.fillMaxSize().padding(5.dp))
            }
            Column(Modifier.weight(.85f).fillMaxHeight()){
                RaceLeaderboard(drivers,selected){selected=it;focusSelected=true}
                Spacer(Modifier.height(6.dp))
                RaceControlCompact(ui.raceControl)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().focusGroup(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            TrackControl("−"){zoom=max(1f,zoom-.25f)}
            TrackControl("+"){zoom=min(4f,zoom+.25f)}
            TrackControl(if(focusSelected)"UNFOCUS" else "FOCUS"){focusSelected=!focusSelected}
            TrackControl("◀"){panX-=.12f}
            TrackControl("▶"){panX+=.12f}
            TrackControl("▲"){panY-=.12f}
            TrackControl("▼"){panY+=.12f}
            Spacer(Modifier.weight(1f))
            Text("D-PAD  DRIVER  •  ▲▼ ZOOM  •  OK FOCUS",color=MapMuted,fontSize=8.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=5.dp,vertical=8.dp))
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
        val points=mutableListOf<TrackPoint>()
        geometry?.corners?.forEach{points+=TrackPoint(it.x,it.y)}
        drivers.forEach{points+=TrackPoint(it.x,it.y)}
        if(points.isEmpty()) return@Canvas

        val selected=drivers.firstOrNull{it.number==selectedNumber}
        val cx0=if(focusSelected && selected!=null)selected.x else points.map{it.x}.average()
        val cy0=if(focusSelected && selected!=null)selected.y else points.map{it.y}.average()
        val rot=-(geometry?.rotation ?: 0.0)*Math.PI/180.0
        fun transform(p:TrackPoint):TrackPoint{
            val dx=p.x-cx0
            val dy=p.y-cy0
            val rx=dx*cos(rot)-dy*sin(rot)
            val ry=dx*sin(rot)+dy*cos(rot)
            return TrackPoint(rx,ry)
        }
        val transformed=points.map(::transform)
        val minX=transformed.minOf{it.x};val maxX=transformed.maxOf{it.x}
        val minY=transformed.minOf{it.y};val maxY=transformed.maxOf{it.y}
        val span=max(maxX-minX,maxY-minY).coerceAtLeast(1.0)
        val scale=min(size.width,size.height)*.78f/span.toFloat()*zoom
        val center=Offset(size.width/2f + panX*size.width,size.height/2f + panY*size.height)
        fun screen(p:TrackPoint):Offset{
            val t=transform(p)
            return Offset(center.x+t.x.toFloat()*scale,center.y-t.y.toFloat()*scale)
        }

        val track=geometry?.corners?.map{screen(TrackPoint(it.x,it.y))}
        if(track!=null && track.size>=2){
            val edge=Path()
            edge.moveTo(track.first().x,track.first().y)
            track.drop(1).forEach{edge.lineTo(it.x,it.y)}
            edge.close()
            drawPath(edge,color=MapTrackEdge,style=Stroke(width=18f))
            drawPath(edge,color=MapTrack,style=Stroke(width=11f))
            geometry.corners.forEachIndexed{index,c->
                if(c.number<=0)return@forEachIndexed
                val p=track[index]
                drawCircle(Color.White.copy(alpha=.55f),radius=7f,center=p)
                drawIntoCanvas{canvas->
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                        color=android.graphics.Color.WHITE
                        textSize=18f
                        textAlign=Paint.Align.CENTER
                        typeface=android.graphics.Typeface.DEFAULT_BOLD
                    }
                    drawContext.canvas.nativeCanvas.drawText(c.number.toString()+c.letter,p.x,p.y-10f,paint)
                }
            }
        }

        drivers.forEach{d->
            val p=screen(TrackPoint(d.x,d.y))
            val color=teamColor(d.teamColor)
            d.trail.takeLast(6).forEachIndexed{index,t->
                val alpha=(index+1)/10f
                drawCircle(color.copy(alpha=alpha),radius=2.5f,center=screen(t))
            }
            if(d.number==selectedNumber){
                drawCircle(MapWhite,radius=13f,center=p,style=Stroke(width=3f))
            }
            drawCircle(color,radius=8f,center=p)
            drawIntoCanvas{canvas->
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                    this.color=android.graphics.Color.WHITE
                    textSize=16f
                    textAlign=Paint.Align.CENTER
                    typeface=android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(4f,0f,0f,android.graphics.Color.BLACK)
                }
                drawContext.canvas.nativeCanvas.drawText(d.acronym,p.x,p.y-12f,paint)
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
