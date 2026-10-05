package app.f1multiview.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.f1multiview.core.playback.*
import app.f1multiview.data.DemoRepository
import app.f1multiview.data.UgisFeatureClient
import app.f1multiview.data.CalendarRace
import app.f1multiview.data.StandingRow
import app.f1multiview.data.ResultRow
import app.f1multiview.data.SavedSetupStore
import app.f1multiview.data.f1tv.AuthorizedF1TvGateway
import app.f1multiview.data.timing.LiveTimingClient
import app.f1multiview.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Checking:AuthState
    data object SignedOut:AuthState
    data object SigningIn:AuthState
    data object SignedIn:AuthState
    data class Error(val message:String):AuthState
}
data class UiState(
    val auth:AuthState=AuthState.Checking,val layout:LayoutPreset=LayoutPreset.GRID_4,val session:Session?=null,
    val sessions: List<Session> =emptyList(),val streams: List<StreamSource> =emptyList(),
    val telemetry: List<DriverTelemetry> =DemoRepository.telemetry(),val timing: List<TimingRow> =DemoRepository.timing(),
    val raceControl: List<RaceControlEvent> =DemoRepository.raceControl(), val weather: TimingWeather = TimingWeather(),val selectedPanel:String?=null,val syncOffsetMs:Long=0,
    val providerError:String?=null,val vodSeasons: List<VodSeason> =emptyList(),val selectedSeason:VodSeason?=null,
    val vodEvents: List<VodEvent> =emptyList(),val selectedEvent:VodEvent?=null,val vodSessions: List<VodSession> =emptyList(),
    val quality:Quality=Quality.AUTO,val timingStatus:String="OFFLINE",val selectedStreamIds:List<String> = emptyList(),
    val mainStreamId:String? = null,
    val savedSetups: List<SavedSetup> = emptyList(),
    val calendar: List<CalendarRace> = emptyList(),
    val standings: List<StandingRow> = emptyList(),
    val results: List<ResultRow> = emptyList(),
    val showsDocs: List<EditorialItem> = emptyList(),
    val customRadioUrl:String = "", val radioDelayMs:Long = 0L, val preferCustomRadio:Boolean = false, val selectedSeries:String = "F1"
)
class MultiViewViewModel(application:Application):AndroidViewModel(application){
    private val store=SavedSetupStore(application)
    private val provider:PlaybackGateway=AuthorizedF1TvGateway(application)
    private val timingClient=LiveTimingClient(viewModelScope)
    private val featureClient=UgisFeatureClient()
    private val prefs=application.getSharedPreferences("f1_multiview_radio",0)
    private val _ui=MutableStateFlow(UiState());val ui=_ui.asStateFlow()
    init{
        _ui.value=_ui.value.copy(customRadioUrl=prefs.getString("radio_url","").orEmpty(),radioDelayMs=prefs.getLong("radio_delay_ms",0L),preferCustomRadio=prefs.getBoolean("radio_prefer",false),selectedSeries=prefs.getString("series_filter","F1").orEmpty())
        timingClient.start()
        viewModelScope.launch{timingClient.rows.collect{rows->if(rows.isNotEmpty())_ui.value=_ui.value.copy(timing=rows)}}
        viewModelScope.launch{timingClient.status.collect{status->_ui.value=_ui.value.copy(timingStatus=status)}}
        viewModelScope.launch{timingClient.raceControl.collect{events->if(events.isNotEmpty())_ui.value=_ui.value.copy(raceControl=events)}}
        viewModelScope.launch{timingClient.weather.collect{weather->_ui.value=_ui.value.copy(weather=weather)}}
        viewModelScope.launch{val restored=provider.restoreSession().getOrDefault(false);if(restored){loadSessions();loadVodSeasons()}else _ui.value=_ui.value.copy(auth=AuthState.SignedOut)}
        viewModelScope.launch{
            store.setups.collect { setups -> _ui.value = _ui.value.copy(savedSetups = setups) }
        }
        viewModelScope.launch{
            store.setup.collect{setup->
                if(setup!=null&&setup.streamIds.isNotEmpty()&&_ui.value.streams.isNotEmpty()){
                    val allStreams=_ui.value.streams
                    val availableIds=allStreams.map { it.id }.toSet()
                    val restored=setup.streamIds.filter { it in availableIds }
                    val savedMain=setup.mainStreamId?.takeIf { it in restored }
                    val maxFeeds=when(setup.layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6}
                    val mainId=savedMain ?: restored.firstOrNull()
                    val selected=(listOfNotNull(mainId)+restored.filterNot { it==mainId }).distinct().take(maxFeeds)
                    _ui.value=_ui.value.copy(layout=setup.layout,selectedStreamIds=selected,mainStreamId=mainId)
                }
            }
        }
    }
    fun signInWithSessionToken(token:String)=viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signInWithSessionToken(token).fold({ _ui.value=_ui.value.copy(auth=AuthState.SignedIn);loadSessions();loadVodSeasons()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Browser sign-in failed"),providerError=it.message)})}
    fun signIn(username:String,password:String){if(username.isBlank()||password.isBlank())return;viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signIn(ProviderCredentials(username.trim(),password)).fold({_ui.value=_ui.value.copy(auth=AuthState.SignedIn);loadSessions();loadVodSeasons()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Sign-in failed"),providerError=it.message)})}}
    fun signOut()=viewModelScope.launch{provider.signOut();_ui.value=UiState(auth=AuthState.SignedOut)}
    private suspend fun loadSessions(){provider.sessions().fold({sessions->val first=sessions.firstOrNull();_ui.value=_ui.value.copy(auth=AuthState.SignedIn,sessions=sessions,session=first);if(first!=null)loadStreams(first)},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Unable to load F1 TV sessions"),providerError=it.message)})}
    fun loadVodSeasons()=viewModelScope.launch{provider.vodSeasons().onSuccess{seasons->val selected=seasons.firstOrNull();_ui.value=_ui.value.copy(vodSeasons=seasons,selectedSeason=selected);if(selected!=null)loadVodEvents(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodEvents(season:VodSeason){provider.vodEvents(season).onSuccess{events->val selected=events.maxWithOrNull(compareBy<VodEvent>{it.meetingNumber}.thenBy{it.meetingName})?:events.firstOrNull();_ui.value=_ui.value.copy(vodEvents=events,selectedEvent=selected);if(selected!=null)loadVodSessions(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodSessions(event:VodEvent){provider.vodSessions(event).onSuccess{sessions->val ordered=sessions.sortedWith(compareBy<VodSession>{when{it.stage.equals("race",true)&&!it.title.contains("highlight",true)->0;it.stage.equals("race",true)->1;it.stage.equals("qualifying",true)->2;it.stage.equals("sprint",true)->3;it.stage.equals("sprint-qualifying",true)->4;it.stage.startsWith("practice",true)->5;it.stage.equals("pre-show",true)->6;it.stage.equals("post-show",true)->7;else->8}}.thenBy{it.startTime}.thenBy{it.title});val selected=ordered.firstOrNull{it.stage.equals("race",true)&&!it.title.contains("highlight",true)}?:ordered.firstOrNull();_ui.value=_ui.value.copy(vodSessions=ordered,selectedEvent=event);if(selected!=null){val session=Session(selected.contentId,selected.title,selected.series,"Replay",false,event.seasonYear,event.pageId,selected.series,selected.stage,event.meetingNumber,selected.artworkUrl?:event.artworkUrl, selected.backgroundArtworkUrl?:event.backgroundArtworkUrl);_ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),mainStreamId=null,providerError=null);loadStreams(session)}}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun selectVodSeason(season:VodSeason)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedSeason=season,selectedEvent=null,vodEvents=emptyList(),vodSessions=emptyList());loadVodEvents(season)}
    fun selectVodEvent(event:VodEvent)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedEvent=event,vodSessions=emptyList());loadVodSessions(event)}
    fun selectVodSession(vod:VodSession)=viewModelScope.launch{val session=Session(vod.contentId,vod.title,vod.series,"Replay",false,_ui.value.selectedSeason?.year,vod.eventPageId,vod.series,vod.type,_ui.value.selectedEvent?.meetingNumber,vod.artworkUrl?:_ui.value.selectedEvent?.artworkUrl, vod.backgroundArtworkUrl?:_ui.value.selectedEvent?.backgroundArtworkUrl);_ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),providerError=null);loadStreams(session)}
    fun setQuality(q:Quality){_ui.value=_ui.value.copy(quality=q)}
    fun setSession(session:Session)=viewModelScope.launch{_ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),providerError=null);loadStreams(session)}
    private suspend fun loadStreams(session:Session){
        provider.streams(session.id).fold(
            {sources->
                val visible=sources
                val mainSource=visible.firstOrNull { it.kind == StreamKind.WORLD } ?: visible.firstOrNull()
                _ui.value=_ui.value.copy(
                    streams=visible,
                    selectedStreamIds=listOfNotNull(mainSource?.id),
                    mainStreamId=mainSource?.id,
                    providerError=null
                )
                if (mainSource != null && mainSource.url == null) {
                    resolveSource(mainSource)
                }
            },
            {_ui.value=_ui.value.copy(providerError=it.message?:"Unable to load streams")}
        )
    }
    private suspend fun resolveSource(source:StreamSource){
        val contentId=source.contentId?:return
        provider.resolve(PlaybackRequest(contentId,source.channelId,_ui.value.quality)).onSuccess{playback->
            _ui.value=_ui.value.copy(
                streams=_ui.value.streams.map{
                    if(it.id==source.id) it.copy(
                        url=playback.manifestUrl,
                        drmLicenseUrl=playback.licenseUrl,
                        requestHeaders=playback.streamHeaders,
                        drmRequestHeaders=playback.licenseHeaders,
                        playApiVersion=playback.playApiVersion,
                        platform=playback.platform,
                        streamType=playback.streamType,
                        ascendonToken=playback.ascendonToken,
                        entitlementToken=playback.entitlementToken,
                        drmType=playback.drmType,
                        playToken=playback.playToken
                    ) else it
                },
                providerError=null
            )
        }.onFailure{
            _ui.value=_ui.value.copy(providerError=it.message?:"Playback resolution failed")
        }
    }
    fun applyPreset(name:String){
        val streams=_ui.value.streams
        val world=streams.firstOrNull{it.kind==StreamKind.WORLD}
        val obc=streams.firstOrNull{it.kind==StreamKind.ONBOARD}
        val tracker=streams.firstOrNull{it.kind==StreamKind.TRACK}
        val data=streams.firstOrNull{it.kind==StreamKind.DATA}
        val picked=when(name){
            "side" -> listOfNotNull(world,obc).map{it.id}
            "quad" -> listOfNotNull(world,obc,tracker,data).map{it.id}
            else -> listOfNotNull(world).map{it.id}
        }
        val layout=if(name=="side") LayoutPreset.SPLIT_2 else if(name=="quad") LayoutPreset.GRID_4 else LayoutPreset.SINGLE
        _ui.value=_ui.value.copy(layout=layout,selectedStreamIds=picked.take(if(layout==LayoutPreset.SPLIT_2)2 else if(layout==LayoutPreset.GRID_4)4 else 1),mainStreamId=picked.firstOrNull())
        persist()
        picked.mapNotNull{id->streams.firstOrNull{it.id==id}}.filter{it.url==null}.forEach{viewModelScope.launch{resolveSource(it)}}
    }
    fun setLayout(layout:LayoutPreset){
    val maxFeeds=when(layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6}
    _ui.value=_ui.value.copy(layout=layout,selectedStreamIds=_ui.value.selectedStreamIds.take(maxFeeds))
    persist()
}
fun toggleStream(id:String)=viewModelScope.launch{
    val current=_ui.value.selectedStreamIds
    val maxFeeds=when(_ui.value.layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6}
    if(id in current){
        if(id == _ui.value.mainStreamId) return@launch
        _ui.value=_ui.value.copy(selectedStreamIds=current.filterNot{it==id})
        persist()
        return@launch
    }
    if(current.size>=6) return@launch
    val needed=current.size+1
    val requiredLayout=when{
        needed<=1->LayoutPreset.SINGLE
        needed<=2->LayoutPreset.SPLIT_2
        needed<=4->LayoutPreset.GRID_4
        else->LayoutPreset.GRID_6
    }
    val currentMax=when(_ui.value.layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6}
    val targetLayout=if(currentMax<needed)requiredLayout else _ui.value.layout
    _ui.value=_ui.value.copy(layout=targetLayout,selectedStreamIds=current+id,providerError=null)
    persist()
    val source=_ui.value.streams.firstOrNull{it.id==id} ?: return@launch
    if(source.url==null) resolveSource(source)
}
    fun setMainStream(id:String)=viewModelScope.launch{
        val source=_ui.value.streams.firstOrNull{it.id==id} ?: return@launch
        val current=_ui.value.selectedStreamIds
        val maxFeeds=when(_ui.value.layout){
            LayoutPreset.SINGLE->1
            LayoutPreset.SPLIT_2->2
            LayoutPreset.GRID_4->4
            LayoutPreset.GRID_6->6
        }
        val oldMain = _ui.value.mainStreamId
        val next = listOf(id) + current.filterNot { it == id || it == oldMain }
        _ui.value=_ui.value.copy(selectedStreamIds=next.distinct().take(maxFeeds),mainStreamId=id,providerError=null)
        persist()
        if(source.url==null) resolveSource(source)
    }
    fun setSeries(value:String){prefs.edit().putString("series_filter",value).apply();_ui.value=_ui.value.copy(selectedSeries=value)}
    fun setCustomRadioUrl(value:String){prefs.edit().putString("radio_url",value).apply();_ui.value=_ui.value.copy(customRadioUrl=value)}
    fun setRadioDelayMs(value:Long){prefs.edit().putLong("radio_delay_ms",value.coerceIn(0L,120_000L)).apply();_ui.value=_ui.value.copy(radioDelayMs=value.coerceIn(0L,120_000L))}
    fun setPreferCustomRadio(value:Boolean){prefs.edit().putBoolean("radio_prefer",value).apply();_ui.value=_ui.value.copy(preferCustomRadio=value)}
    fun loadShowsDocs()=viewModelScope.launch{provider.showsAndDocs().onSuccess{_ui.value=_ui.value.copy(showsDocs=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun loadCalendar()=viewModelScope.launch{featureClient.calendar().onSuccess{_ui.value=_ui.value.copy(calendar=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun loadStandings()=viewModelScope.launch{featureClient.standings().onSuccess{_ui.value=_ui.value.copy(standings=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun loadResults()=viewModelScope.launch{featureClient.results().onSuccess{_ui.value=_ui.value.copy(results=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun panel(panel:String?){_ui.value=_ui.value.copy(selectedPanel=panel)}
    fun sync(delta:Long){_ui.value=_ui.value.copy(syncOffsetMs=_ui.value.syncOffsetMs+delta)}
    fun providerError(message:String?){_ui.value=_ui.value.copy(providerError=message)}
    fun saveCurrentSetup(name:String="My Race View"){val current=_ui.value;viewModelScope.launch{store.save(SavedSetup("setup-"+System.currentTimeMillis(),name,current.layout,current.selectedStreamIds,current.mainStreamId))}}
    fun saveNamedSetup(id:String, name:String){val current=_ui.value;viewModelScope.launch{store.save(SavedSetup(id,name,current.layout,current.selectedStreamIds,current.mainStreamId))}}
    fun loadSavedSetup(setup:SavedSetup){val available=_ui.value.streams.map{it.id}.toSet();val ids=setup.streamIds.filter{it in available};val max=when(setup.layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6};val main=setup.mainStreamId?.takeIf{it in ids}?:ids.firstOrNull();_ui.value=_ui.value.copy(layout=setup.layout,selectedStreamIds=listOfNotNull(main)+ids.filterNot{it==main}.take(max-1),mainStreamId=main);persist()}
    fun deleteSavedSetup(id:String)=viewModelScope.launch{store.delete(id)}
    fun clearSavedSetup()=viewModelScope.launch{store.clear()}
    override fun onCleared(){timingClient.stop();super.onCleared()}
    private fun persist(){saveCurrentSetup()}
}
