package app.f1multiview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.f1multiview.core.playback.Quality
import app.f1multiview.media.PlayerPool
import app.f1multiview.media.SyncEngine
import app.f1multiview.model.*
import app.f1multiview.viewmodel.*
import kotlinx.coroutines.delay

@Composable fun App(vm:MultiViewViewModel){val ui by vm.ui.collectAsState();F1Theme{when(ui.auth){AuthState.Checking,AuthState.SignedOut,is AuthState.SigningIn,is AuthState.Error->LoginScreen(ui.auth,vm);AuthState.SignedIn->MultiViewScreen(ui,vm)}}}

@Composable private fun LoginScreen(auth:AuthState,vm:MultiViewViewModel){
    var email by rememberSaveable{mutableStateOf("")};var password by rememberSaveable{mutableStateOf("")};var browser by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().background(Color(0xFF06070A)).padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Text("F1 MULTIVIEW",style=MaterialTheme.typography.displaySmall);Spacer(Modifier.height(8.dp));Text("Sign in with your F1 TV subscription",color=Color.LightGray);Spacer(Modifier.height(24.dp))
        OutlinedTextField(email,{email=it},label={Text("F1 TV email")},singleLine=true,modifier=Modifier.widthIn(max=460.dp).fillMaxWidth());Spacer(Modifier.height(10.dp))
        OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.widthIn(max=460.dp).fillMaxWidth());Spacer(Modifier.height(16.dp))
        Button({vm.signIn(email,password)},enabled=auth !is AuthState.SigningIn){Text(if(auth is AuthState.SigningIn)"SIGNING IN…" else "SIGN IN")}
        TextButton({browser=true}){Text("SIGN IN WITH F1 TV BROWSER")}
        if(auth is AuthState.Error)Text(auth.message,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))
    }
    if(browser)F1BrowserLogin({browser=false},vm)
}

@Composable private fun MultiViewScreen(ui:UiState,vm:MultiViewViewModel){
    val context=LocalContext.current;val pool=remember(context){PlayerPool(context)};val sync=remember(pool){SyncEngine(pool::all)};val errors by pool.errors.collectAsState()
    DisposableEffect(pool){onDispose{pool.release()}}
    LaunchedEffect(ui.streams){pool.retain(ui.streams.map{it.id}.toSet());ui.streams.forEach(pool::load)}
    LaunchedEffect(sync){while(true){sync.synchronize();delay(250)}}
    Column(Modifier.fillMaxSize().background(Color(0xFF06070A))){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("F1 MULTIVIEW",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.weight(1f));TextButton({vm.signOut()}){Text("SIGN OUT")}}
        LazyRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ui.sessions){s->FilterChip(ui.session==s,{vm.setSession(s)},label={Text(s.name)})}}
        ArchivePicker(ui,vm)
        ui.providerError?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
        Row(Modifier.fillMaxWidth().weight(1f).padding(10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.weight(1f).fillMaxHeight()){val count=ui.streams.size;val cols=if(count<=1)1 else 2;val rows=((count+cols-1)/cols).coerceAtLeast(1);Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){for(r in 0 until rows){Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){for(c in 0 until cols){val i=r*cols+c;if(i<count)PlayerTile(ui.streams[i],pool,errors[ui.streams[i].id],Modifier.weight(1f).fillMaxHeight())else Spacer(Modifier.weight(1f))}}}}}
            if(ui.selectedPanel!=null)SidePanel(ui,vm,Modifier.width(330.dp).fillMaxHeight())
        }
        BottomBar(ui,vm,pool)
    }
}

@Composable private fun ArchivePicker(ui:UiState,vm:MultiViewViewModel){
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)){
        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ui.vodSeasons){s->FilterChip(ui.selectedSeason==s,{vm.selectVodSeason(s)},label={Text(s.year.toString())})}}
        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ui.vodEvents){e->FilterChip(ui.selectedEvent==e,{vm.selectVodEvent(e)},label={Text(e.meetingName)})}}
        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(ui.vodSessions){s->AssistChip({vm.selectVodSession(s)},{Text(s.type.uppercase()+" · "+s.series)})}}
    }
}

@Composable private fun PlayerTile(s:StreamSource,pool:PlayerPool,error:String?,m:Modifier){
    val player=remember(s.id){pool.get(s.id)};var playing by remember(s.id){mutableStateOf(player.isPlaying)}
    DisposableEffect(player){val l=object:Player.Listener{override fun onIsPlayingChanged(v:Boolean){playing=v}};player.addListener(l);onDispose{player.removeListener(l)}}
    Card(m.border(1.dp,Color(0xFF292C34)).focusable()){Box(Modifier.fillMaxSize().background(Color(0xFF15171C))){
        if(s.url!=null)AndroidView(factory={c->PlayerView(c).apply{useController=false;this.player=player}},modifier=Modifier.fillMaxSize(),update={it.player=player}) else Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Text(s.title);Text("WAITING FOR AUTHORIZED STREAM",color=Color.LightGray)}
        error?.let{Text("PLAYBACK ERROR: "+it,Modifier.align(Alignment.Center).padding(16.dp),color=Color.White)}
        Row(Modifier.align(Alignment.TopStart).padding(8.dp)){AssistChip({pool.setAudioPlayer(s.id)},label={Text(if(s.driver.isNullOrBlank())"LIVE" else s.driver)})}
        if(s.url!=null)Button({if(playing)pool.pause(s.id)else pool.play(s.id)},Modifier.align(Alignment.BottomStart).padding(8.dp)){Text(if(playing)"PAUSE" else "PLAY")}
    }}
}

@Composable private fun SidePanel(ui:UiState,vm:MultiViewViewModel,m:Modifier){Card(m){Column(Modifier.fillMaxSize().padding(12.dp)){Row{Text(ui.selectedPanel!!);Spacer(Modifier.weight(1f));TextButton({vm.panel(null)}){Text("X")}};when(ui.selectedPanel){"timing"->Timing(ui.timing);"race control"->RaceControl(ui.raceControl);else->Timing(ui.timing)}}}}
@Composable private fun Timing(rows:List<TimingRow>){LazyColumn{items(rows){r->Row(Modifier.fillMaxWidth().padding(8.dp)){Text(r.position.toString()+". "+r.driver,Modifier.weight(1f));Text(r.gap+" "+r.tyre)}}}}
@Composable private fun RaceControl(rows:List<RaceControlEvent>){LazyColumn{items(rows){r->Text(r.time+"  "+r.message,Modifier.padding(8.dp))}}}

@Composable private fun BottomBar(ui:UiState,vm:MultiViewViewModel,pool:PlayerPool){
    Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
        Text("LAYOUT");listOf(LayoutPreset.SINGLE to "1",LayoutPreset.SPLIT_2 to "2",LayoutPreset.GRID_4 to "4",LayoutPreset.GRID_6 to "6").forEach{(p,t)->FilterChip(ui.layout==p,{vm.setLayout(p)},label={Text(t)})};Spacer(Modifier.weight(1f));Text("QUALITY")
        listOf(Quality.AUTO to "AUTO",Quality.UHD to "4K",Quality.FHD to "1080",Quality.HD to "720",Quality.SD to "480").forEach{(q,label)->FilterChip(ui.quality==q,{vm.setQuality(q);pool.setQuality(q)},label={Text(label)})}
        AssistChip({pool.playAll()},{Text("PLAY ALL")});AssistChip({pool.pauseAll()},{Text("PAUSE ALL")});AssistChip({vm.panel("timing")},{Text("TIMING "+ui.timingStatus)})
    }
}
