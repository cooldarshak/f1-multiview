package app.f1multiview.ui

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    LaunchedEffect(drivers) {
        if(selected==null || drivers.none{it.number==selected}) selected=drivers.firstOrNull()?.number
    }

    Column(
        Modifier.fillMaxSize().background(MapBg).focusGroup()
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
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("LIVE TRACK MAP",color=MapWhite,fontSize=17.sp,fontWeight=FontWeight.Black)
                Text(
                    ui.liveSessionInfo.meeting+" · "+ui.liveSessionInfo.sessionType+
                        " · "+ui.trackStatus.label+
                        if(drivers.isEmpty()) " · WAITING FOR GPS" else " · "+drivers.size+" CARS",
                    color=MapMuted,fontSize=9.sp
                )
            }
            MapBadge(ui.trackStatus.label,trackStatusColor(ui.trackStatus.code))
            Spacer(Modifier.width(8.dp))
            Text("ZOOM "+String.format("%.2fx",zoom),color=MapMuted,fontSize=8.sp,fontWeight=FontWeight.Bold)
        }

        Surface(
            Modifier.weight(1f).fillMaxWidth().focusable(),
            color=MapBg,shape=RoundedCornerShape(12.dp),
            border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))
        ){
            TrackCanvas(
                geometry=ui.trackGeometry,
                drivers=drivers,
                selectedNumber=selected,
                zoom=zoom,
                focusSelected=focusSelected,
                panX=panX,
                panY=panY,
                modifier=Modifier.fillMaxSize().padding(10.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().focusGroup(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TrackControl("−"){zoom=max(1f,zoom-.25f)}
            TrackControl("+"){zoom=min(4f,zoom+.25f)}
            TrackControl(if(focusSelected)"UNFOCUS" else "FOCUS"){focusSelected=!focusSelected}
            TrackControl("PAN ◀"){panX-=.12f}
            TrackControl("PAN ▶"){panX+=.12f}
            TrackControl("PAN ▲"){panY-=.12f}
            TrackControl("PAN ▼"){panY+=.12f}
        }
        if(drivers.isNotEmpty()){
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement=Arrangement.spacedBy(6.dp)
            ){
                items(drivers.size){ index ->
                    val d=drivers[index]
                    TrackDriverButton(
                        d=d,selected=d.number==selected,
                        onClick={selected=d.number;focusSelected=true}
                    )
                }
            }
        }
        if(ui.trackGeometry==null && drivers.isNotEmpty()){
            Text("Loading circuit geometry…",color=MapMuted,fontSize=9.sp,modifier=Modifier.padding(top=5.dp))
        }
        Text(
            "D-pad: ◀ ▶ select driver · ▲ ▼ zoom · OK focus selected. GPS comes from F1 Position.z; the F1 TV tracker video is not used.",
            color=MapMuted,fontSize=8.sp,modifier=Modifier.padding(top=5.dp)
        )
    }
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
                    canvas.nativeCanvas.drawText(c.number.toString()+c.letter,p.x,p.y-10f,paint)
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
                canvas.nativeCanvas.drawText(d.acronym,p.x,p.y-12f,paint)
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
