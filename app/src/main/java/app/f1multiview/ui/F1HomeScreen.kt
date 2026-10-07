package app.f1multiview.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.f1multiview.core.playback.EditorialItem
import app.f1multiview.core.playback.VodEvent
import app.f1multiview.core.playback.VodSession
import app.f1multiview.model.ContinueWatchingEntry
import app.f1multiview.model.SavedSetup
import app.f1multiview.viewmodel.UiState
import app.f1multiview.viewmodel.MultiViewViewModel
import kotlinx.coroutines.delay

private val HomeRed = Color(0xFFE10600)
private val HomeBg = Color(0xFF0B0B10)
private val HomeSurface = Color(0xFF1C1D25)
private val HomeText = Color(0xFFF7F7F9)
private val HomeMuted = Color(0xFFA6A8B2)

private enum class HomeDestination(val label: String) {
    HOME("Home"), SEASON("2026 Season"), ARCHIVE("Archive"),
    SHOWS("Shows"), DOCUMENTARIES("Documentaries"), MY_LIST("My List")
}

@Composable
fun F1HomeScreen(ui: UiState, vm: MultiViewViewModel, isTv: Boolean, compactPhone: Boolean, onOpenMultiview: () -> Unit, onOpenEditorial: (EditorialItem) -> Unit) {
    var drawer by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(HomeDestination.HOME) }

    Box(Modifier.fillMaxSize().background(HomeBg)) {
        F1HomeBody(ui, vm, isTv, compactPhone, destination, { drawer = true }, { search = true }, onOpenMultiview, onOpenEditorial)
        AnimatedVisibility(drawer, enter=fadeIn(tween(180)), exit=fadeOut(tween(140)), modifier=Modifier.fillMaxSize().zIndex(50f)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.58f)).clickable { drawer=false })
        }
        AnimatedVisibility(drawer, enter=slideInHorizontally(initialOffsetX={-it}, animationSpec=tween(260, easing=FastOutSlowInEasing)), exit=slideOutHorizontally(targetOffsetX={-it}, animationSpec=tween(200)), modifier=Modifier.align(Alignment.CenterStart).zIndex(51f)) {
            F1Drawer(isTv, destination, { destination=it; drawer=false }) { drawer=false }
        }
        AnimatedVisibility(search, enter=fadeIn(tween(160)), exit=fadeOut(tween(120)), modifier=Modifier.fillMaxSize().zIndex(60f)) {
            F1Search(ui, vm) { search=false }
        }
    }
}

@Composable
private fun F1HomeBody(ui:UiState, vm:MultiViewViewModel, isTv:Boolean, compactPhone:Boolean, destination:HomeDestination, onMenu:()->Unit, onSearch:()->Unit, onOpenMultiview:()->Unit, onOpenEditorial:(EditorialItem)->Unit) {
    val side=if(compactPhone)16.dp else if(isTv)38.dp else 24.dp
    Column(Modifier.fillMaxSize()) {
        F1TopBar(compactPhone,onMenu,onSearch,onOpenMultiview)
        when(destination) {
            HomeDestination.HOME -> LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(bottom=40.dp), verticalArrangement=Arrangement.spacedBy(if(compactPhone)22.dp else 30.dp)) {
                item { F1Hero(ui, ui.vodEvents.sortedByDescending{it.meetingNumber}.firstOrNull(), compactPhone, isTv) { event ->
                    vm.selectVodEvent(event)
                    ui.vodSessions.firstOrNull{it.eventPageId==event.pageId && it.stage=="race"}?.let(vm::selectVodSession)
                }}
                if(ui.continueWatching.isNotEmpty()) item { F1RailTitle("Continue Watching","View all",side); ContinueRail(ui.continueWatching,compactPhone,vm::resumeContinueWatching) }
                item { F1RailTitle((ui.selectedSeason?.year ?: 2026).toString()+" Season","View all",side); SeasonRail(ui.vodEvents,compactPhone,vm::selectVodEvent) }
                item { F1RailTitle("Multiview","Open",side); MultiviewCard(compactPhone,onOpenMultiview) }
                if(ui.showsDocs.isNotEmpty()) item { F1RailTitle("Shows","View all",side); EditorialRail(ui.showsDocs.filter{it.pageId!=413}.take(12),compactPhone){item->vm.playEditorial(item);onOpenEditorial(item)} }
                item { F1RailTitle("Documentaries","View all",side); EditorialRail(ui.showsDocs.filter{it.pageId==413}.take(12),compactPhone){item->vm.playEditorial(item);onOpenEditorial(item)} }
                item { F1RailTitle("Popular Races From The Archive","View all",side); SeasonRail(ui.vodEvents.takeLast(12).reversed(),compactPhone,vm::selectVodEvent) }
            }
            HomeDestination.SEASON -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=40.dp)) {
                item{F1PageHeader((ui.selectedSeason?.year?:2026).toString()+" Season","Every Grand Prix and session.",side)}
                item{SeasonRail(ui.vodEvents,compactPhone,vm::selectVodEvent)}
                item{WeekendRail(ui.vodSessions,compactPhone,vm::selectVodSession,side)}
            }
            HomeDestination.ARCHIVE -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=40.dp)) {
                item{F1PageHeader("Archive","Browse seasons and Grand Prix.",side)}
                item{SeasonSelector(ui,vm,side)}
                item{SeasonRail(ui.vodEvents,compactPhone,vm::selectVodEvent)}
                item{WeekendRail(ui.vodSessions,compactPhone,vm::selectVodSession,side)}
            }
            HomeDestination.SHOWS -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=40.dp)) {
                item{F1PageHeader("Shows","Analysis, tech and F1 TV originals.",side)}
                item{EditorialGrid(ui.showsDocs.filter{it.pageId!=413},compactPhone)}
            }
            HomeDestination.DOCUMENTARIES -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=40.dp)) {
                item{F1PageHeader("Documentaries","Stories from Formula 1.",side)}
                item{EditorialGrid(ui.showsDocs.filter{it.pageId==413},compactPhone)}
            }
            HomeDestination.MY_LIST -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=40.dp)) {
                item{F1PageHeader("My List","Saved race-view configurations.",side)}
                item{SavedSetupRail(ui.savedSetups,compactPhone,vm::loadSavedSetup)}
                if(ui.continueWatching.isNotEmpty()) item{F1RailTitle("Continue Watching","",side);ContinueRail(ui.continueWatching,compactPhone,vm::resumeContinueWatching)}
            }
        }
    }
}

@Composable private fun F1TopBar(compact:Boolean,onMenu:()->Unit,onSearch:()->Unit,onOpen:()->Unit) {
    Surface(color=Color(0xFF111218),tonalElevation=3.dp,shadowElevation=2.dp) {
        Row(Modifier.fillMaxWidth().height(if(compact)58.dp else 68.dp).padding(horizontal=if(compact)12.dp else 20.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onMenu){Icon(Icons.Default.Menu,"Menu",tint=HomeText)}
            Spacer(Modifier.weight(1f))
            Text("F1",color=HomeRed,fontSize=if(compact)27.sp else 34.sp,fontWeight=FontWeight.Black,letterSpacing=(-2).sp)
            Spacer(Modifier.width(7.dp));Text("TV",color=HomeText,fontSize=if(compact)21.sp else 27.sp,fontWeight=FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            if(!compact){FilledTonalButton(onClick=onOpen,shape=RoundedCornerShape(50.dp),contentPadding=PaddingValues(horizontal=14.dp)){Icon(Icons.Default.GridView,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("MULTIVIEW")};Spacer(Modifier.width(5.dp))}
            IconButton(onClick=onSearch){Icon(Icons.Default.Search,"Search",tint=HomeText)}
        }
    }
}

@Composable private fun F1Hero(ui:UiState,event:VodEvent?,compact:Boolean,isTv:Boolean,onWatch:(VodEvent)->Unit) {
    val h=when{isTv->360.dp;compact->390.dp;else->430.dp}
    Box(Modifier.fillMaxWidth().height(h)){
        F1Artwork(event?.backgroundArtworkUrl?:ui.session?.backgroundArtworkUrl,event?.meetingName?:ui.session?.name?:"F1 TV",Modifier.fillMaxSize(),ContentScale.Crop,"hero")
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.10f),Color.Black.copy(alpha=.32f),HomeBg.copy(alpha=.98f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal=if(compact)20.dp else 38.dp,vertical=if(compact)24.dp else 32.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Surface(color=HomeRed,shape=RoundedCornerShape(5.dp)){Text("F1",color=Color.White,fontWeight=FontWeight.Black,fontSize=10.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp))};Spacer(Modifier.width(8.dp));Text("2026 SEASON",color=HomeText.copy(alpha=.82f),fontSize=10.sp,fontWeight=FontWeight.Bold)}
            Spacer(Modifier.height(9.dp));Text(prettyEvent(event?.meetingName?:"Welcome to F1 TV"),color=HomeText,fontSize=if(compact)25.sp else 34.sp,fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(if(event!=null)"ROUND "+event.meetingNumber+"  •  FULL WEEKEND" else "LIVE RACING • ONBOARDS • TIMING • MULTIVIEW",color=HomeMuted,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=6.dp))
            Spacer(Modifier.height(16.dp));Button(onClick={event?.let(onWatch)},shape=RoundedCornerShape(50.dp),colors=ButtonDefaults.buttonColors(containerColor=HomeRed),contentPadding=PaddingValues(horizontal=22.dp,vertical=10.dp)){Icon(Icons.Default.PlayArrow,null,Modifier.size(20.dp));Spacer(Modifier.width(7.dp));Text("WATCH NOW",fontWeight=FontWeight.ExtraBold)}
        }
        Surface(Modifier.align(Alignment.TopEnd).padding(16.dp),color=Color.Black.copy(alpha=.34f),shape=RoundedCornerShape(50.dp)){Text("LIVE FEATURES  •  4 FEEDS",color=HomeText,fontSize=9.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=11.dp,vertical=7.dp))}
    }
}

@Composable private fun F1Drawer(isTv:Boolean,selected:HomeDestination,onSelect:(HomeDestination)->Unit,onClose:()->Unit) {
    Surface(Modifier.fillMaxHeight().width(if(isTv)390.dp else 340.dp),color=Color(0xFF121319),tonalElevation=8.dp,shadowElevation=20.dp,shape=RoundedCornerShape(topEnd=28.dp,bottomEnd=28.dp)){
        Column(Modifier.fillMaxSize().padding(22.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("F1",color=HomeRed,fontSize=31.sp,fontWeight=FontWeight.Black,letterSpacing=(-2).sp);Spacer(Modifier.width(7.dp));Text("TV",color=HomeText,fontSize=25.sp,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.weight(1f));IconButton(onClick=onClose){Icon(Icons.Default.Close,"Close",tint=HomeText)}}
            Spacer(Modifier.height(24.dp));Text("EXPLORE",color=HomeMuted,fontSize=10.sp,fontWeight=FontWeight.Black,letterSpacing=1.5.sp,modifier=Modifier.padding(start=10.dp,end=10.dp,bottom=8.dp))
            HomeDestination.entries.forEach{item->val active=item==selected;NavigationDrawerItem(label={Text(item.label,fontWeight=if(active)FontWeight.Bold else FontWeight.Medium)},selected=active,onClick={onSelect(item)},icon={Icon(if(active)Icons.Default.RadioButtonChecked else Icons.Default.ChevronRight,null)},shape=RoundedCornerShape(18.dp),colors=NavigationDrawerItemDefaults.colors(selectedContainerColor=HomeRed.copy(alpha=.16f),selectedIconColor=HomeRed,selectedTextColor=HomeText,unselectedIconColor=HomeMuted,unselectedTextColor=HomeText))}
            Spacer(Modifier.height(20.dp));HorizontalDivider(color=Color.White.copy(alpha=.08f));Spacer(Modifier.height(18.dp))
            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=HomeSurface,border=BorderStroke(1.dp,Color.White.copy(alpha=.07f))){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Person, contentDescription=null, modifier=Modifier.size(28.dp), tint=HomeText);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("F1 TV ACCOUNT",color=HomeMuted,fontSize=9.sp,fontWeight=FontWeight.Black);Text("Signed in",color=HomeText,fontSize=15.sp,fontWeight=FontWeight.Bold)};Icon(Icons.Default.ChevronRight, contentDescription=null, tint=HomeMuted)}}
        }
    }
}

@Composable private fun F1Search(ui:UiState,vm:MultiViewViewModel,onClose:()->Unit) {
    var query by rememberSaveable{mutableStateOf("")};val q=query.trim().lowercase()
    val events=ui.vodEvents.filter{q.isBlank()||it.meetingName.lowercase().contains(q)}.take(12)
    val sessions=ui.vodSessions.filter{q.isBlank()||it.title.lowercase().contains(q)}.take(12)
    val editorial=ui.showsDocs.filter{q.isBlank()||it.title.lowercase().contains(q)}.take(12)
    Surface(Modifier.fillMaxSize(),color=HomeBg){Column(Modifier.fillMaxSize().padding(18.dp)){Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onClose){Icon(Icons.Default.ArrowBack,"Back",tint=HomeText)};Spacer(Modifier.width(6.dp));Text("Search F1 TV",color=HomeText,fontSize=24.sp,fontWeight=FontWeight.Black)};Spacer(Modifier.height(14.dp));OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search races, shows, documentaries…")});Spacer(Modifier.height(20.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(18.dp)){if(events.isNotEmpty())item{F1RailTitle("Grand Prix","",18.dp);SearchRail(events.map{it.meetingName to it.artworkUrl}){t->ui.vodEvents.firstOrNull{it.meetingName==t}?.let(vm::selectVodEvent)}};if(sessions.isNotEmpty())item{F1RailTitle("Videos","",18.dp);SearchRail(sessions.map{it.title to it.artworkUrl}){t->ui.vodSessions.firstOrNull{it.title==t}?.let(vm::selectVodSession)}};if(editorial.isNotEmpty())item{F1RailTitle("Shows & Documentaries","",18.dp);EditorialRail(editorial,false)};if(events.isEmpty()&&sessions.isEmpty()&&editorial.isEmpty())item{Text("No results",color=HomeMuted)}}}}
}

@Composable private fun F1PageHeader(title:String,subtitle:String,side:androidx.compose.ui.unit.Dp){Column(Modifier.padding(start=side,end=side,top=28.dp,bottom=18.dp)){Text(title,color=HomeText,fontSize=30.sp,fontWeight=FontWeight.Black);Text(subtitle,color=HomeMuted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))}}
@Composable private fun F1RailTitle(title:String,action:String,side:androidx.compose.ui.unit.Dp){Row(Modifier.fillMaxWidth().padding(horizontal=side),verticalAlignment=Alignment.CenterVertically){Text(title,color=HomeText,fontSize=18.sp,fontWeight=FontWeight.Black);Spacer(Modifier.weight(1f));if(action.isNotBlank())Text(action,color=HomeMuted,fontSize=11.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun SeasonRail(events:List<VodEvent>,compact:Boolean,onEvent:(VodEvent)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=if(compact)16.dp else 24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.focusGroup()){items(events,key={it.pageId}){e->ContentCard(e.meetingName,e.artworkUrl,if(e.meetingNumber>0)"ROUND "+e.meetingNumber else "GRAND PRIX",compact){onEvent(e)}}}}
@Composable private fun ContinueRail(entries:List<ContinueWatchingEntry>,compact:Boolean,onResume:(ContinueWatchingEntry)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=if(compact)16.dp else 24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.focusGroup()){items(entries,key={it.contentId}){e->ContentCard(e.title,e.artworkUrl?:e.backgroundArtworkUrl,"RESUME "+time(e.positionMs),compact){onResume(e)}}}}
@Composable private fun EditorialRail(items:List<EditorialItem>,compact:Boolean,onClick:(EditorialItem)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=if(compact)16.dp else 24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.focusGroup()){items(items,key={it.contentId}){e->ContentCard(e.title,e.artworkUrl,"F1 TV",compact){onClick(e)}}}}
@Composable private fun WeekendRail(sessions:List<VodSession>,compact:Boolean,onSelect:(VodSession)->Unit,side:androidx.compose.ui.unit.Dp){LazyRow(contentPadding=PaddingValues(horizontal=side,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.focusGroup()){items(sessions,key={it.contentId}){s->ContentCard(s.title,s.artworkUrl,s.stage.replace("-"," ").uppercase(),compact){onSelect(s)}}}}
@Composable private fun SearchRail(items:List<Pair<String,String?>>,onClick:(String)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=18.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){items(items){p->ContentCard(p.first,p.second,"F1 TV",false){onClick(p.first)}}}}
@Composable private fun ContentCard(title:String,url:String?,meta:String,compact:Boolean,onClick:()->Unit){var focused by remember{mutableStateOf(false)};val scale by animateFloatAsState(if(focused)1.035f else 1f,label="cardScale");Surface(Modifier.width(if(compact)205.dp else 250.dp).scale(scale).clickable(onClick=onClick).focusable().onFocusChanged{focused=it.isFocused},shape=RoundedCornerShape(16.dp),color=HomeSurface,border=BorderStroke(if(focused)2.dp else 1.dp,if(focused)HomeRed else Color.White.copy(alpha=.06f))){Column{Box(Modifier.fillMaxWidth().height(if(compact)118.dp else 142.dp)){F1Artwork(url,title,Modifier.fillMaxSize(),ContentScale.Crop,title);Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))));Text(meta,color=Color.White.copy(alpha=.84f),fontSize=8.sp,fontWeight=FontWeight.Black,modifier=Modifier.align(Alignment.BottomStart).padding(9.dp))};Text(title,color=HomeText,fontSize=13.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(11.dp))}}}
@Composable private fun MultiviewCard(compact:Boolean,onOpen:()->Unit){Surface(Modifier.fillMaxWidth().padding(horizontal=if(compact)16.dp else 24.dp).clickable(onClick=onOpen),shape=RoundedCornerShape(22.dp),color=HomeSurface,border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))){Row(Modifier.padding(if(compact)16.dp else 22.dp),verticalAlignment=Alignment.CenterVertically){Surface(color=HomeRed.copy(alpha=.16f),shape=RoundedCornerShape(18.dp)){Icon(Icons.Default.GridView,null,tint=HomeRed,modifier=Modifier.padding(16.dp).size(28.dp))};Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text("F1 MultiView",color=HomeText,fontSize=18.sp,fontWeight=FontWeight.Black);Text("World feed • onboards • timing • driver tracker • data",color=HomeMuted,fontSize=11.sp)};Button(onClick=onOpen,shape=RoundedCornerShape(50.dp),colors=ButtonDefaults.buttonColors(containerColor=HomeRed)){Text("OPEN")}}}}
@Composable private fun SeasonSelector(ui:UiState,vm:MultiViewViewModel,side:androidx.compose.ui.unit.Dp){LazyRow(contentPadding=PaddingValues(horizontal=side,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.focusGroup()){items(ui.vodSeasons){s->FilterChip(selected=ui.selectedSeason==s,onClick={vm.selectVodSeason(s)},label={Text(s.year.toString())})}}}
@Composable private fun EditorialGrid(items:List<EditorialItem>,compact:Boolean){Column(Modifier.padding(horizontal=if(compact)16.dp else 24.dp)){items.chunked(if(compact)2 else 3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){row.forEach{e->Box(Modifier.weight(1f)){ContentCard(e.title,e.artworkUrl,"F1 TV",compact){}};};repeat((if(compact)2 else 3)-row.size){Spacer(Modifier.weight(1f))}};Spacer(Modifier.height(14.dp))}}}
@Composable private fun SavedSetupRail(items:List<SavedSetup>,compact:Boolean,onLoad:(SavedSetup)->Unit){if(items.isEmpty())Text("No saved views yet. Save a layout from Multiview.",color=HomeMuted,modifier=Modifier.padding(18.dp)) else LazyRow(contentPadding=PaddingValues(horizontal=if(compact)16.dp else 24.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){items(items){s->ContentCard(s.name,null,s.layout.name.replace("_"," "),compact){onLoad(s)}}}}
private fun prettyEvent(v:String)=v.replace(Regex("(?i)formula 1|formula one"),"F1").replace(Regex("\\s+")," ").trim()
private fun time(ms:Long):String{val s=(ms/1000).coerceAtLeast(0);return "%02d:%02d".format(s/60,s%60)}
