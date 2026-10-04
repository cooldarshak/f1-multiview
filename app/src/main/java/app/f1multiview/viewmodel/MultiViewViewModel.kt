package app.f1multiview.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.f1multiview.core.playback.*
import app.f1multiview.data.DemoRepository
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
    val raceControl: List<RaceControlEvent> =DemoRepository.raceControl(),val selectedPanel:String?=null,val syncOffsetMs:Long=0,
    val providerError:String?=null,val vodSeasons: List<VodSeason> =emptyList(),val selectedSeason:VodSeason?=null,
    val vodEvents: List<VodEvent> =emptyList(),val selectedEvent:VodEvent?=null,val vodSessions: List<VodSession> =emptyList(),
    val quality:Quality=Quality.AUTO,val timingStatus:String="OFFLINE"
)
class MultiViewViewModel(application:Application):AndroidViewModel(application){
    private val store=SavedSetupStore(application)
    private val provider:PlaybackGateway=AuthorizedF1TvGateway(application)
    private val timingClient=LiveTimingClient(viewModelScope)
    private val _ui=MutableStateFlow(UiState());val ui=_ui.asStateFlow()
    init{
        timingClient.start()
        viewModelScope.launch{timingClient.rows.collect{rows->if(rows.isNotEmpty())_ui.value=_ui.value.copy(timing=rows)}}
        viewModelScope.launch{timingClient.status.collect{status->_ui.value=_ui.value.copy(timingStatus=status)}}
        viewModelScope.launch{val restored=provider.restoreSession().getOrDefault(false);if(restored){loadSessions();loadVodSeasons()}else _ui.value=_ui.value.copy(auth=AuthState.SignedOut)}
        viewModelScope.launch{store.setup.collect{setup->if(setup!=null&&setup.streamIds.isNotEmpty()){val streams=_ui.value.streams.filter{it.id in setup.streamIds};if(streams.isNotEmpty())_ui.value=_ui.value.copy(layout=setup.layout,streams=streams)}}}
    }
    fun signInWithSessionToken(token:String)=viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signInWithSessionToken(token).fold({ _ui.value=_ui.value.copy(auth=AuthState.SignedIn);loadSessions();loadVodSeasons()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Browser sign-in failed"),providerError=it.message)})}
    fun signIn(username:String,password:String){if(username.isBlank()||password.isBlank())return;viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signIn(ProviderCredentials(username.trim(),password)).fold({_ui.value=_ui.value.copy(auth=AuthState.SignedIn);loadSessions();loadVodSeasons()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Sign-in failed"),providerError=it.message)})}}
    fun signOut()=viewModelScope.launch{provider.signOut();_ui.value=UiState(auth=AuthState.SignedOut)}
    private suspend fun loadSessions(){provider.sessions().fold({sessions->val first=sessions.firstOrNull();_ui.value=_ui.value.copy(auth=AuthState.SignedIn,sessions=sessions,session=first);if(first!=null)loadStreams(first)},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Unable to load F1 TV sessions"),providerError=it.message)})}
    fun loadVodSeasons()=viewModelScope.launch{provider.vodSeasons().onSuccess{seasons->val selected=seasons.firstOrNull();_ui.value=_ui.value.copy(vodSeasons=seasons,selectedSeason=selected);if(selected!=null)loadVodEvents(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodEvents(season:VodSeason){provider.vodEvents(season).onSuccess{events->val selected=events.firstOrNull();_ui.value=_ui.value.copy(vodEvents=events,selectedEvent=selected);if(selected!=null)loadVodSessions(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodSessions(event:VodEvent){provider.vodSessions(event).onSuccess{sessions->_ui.value=_ui.value.copy(vodSessions=sessions,selectedEvent=event)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun selectVodSeason(season:VodSeason)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedSeason=season,selectedEvent=null,vodEvents=emptyList(),vodSessions=emptyList());loadVodEvents(season)}
    fun selectVodEvent(event:VodEvent)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedEvent=event,vodSessions=emptyList());loadVodSessions(event)}
    fun selectVodSession(vod:VodSession)=viewModelScope.launch{val session=Session(vod.contentId,vod.title,vod.series,"Replay",false,_ui.value.selectedSeason?.year,vod.eventPageId,vod.series,vod.type);_ui.value=_ui.value.copy(session=session,streams=emptyList(),providerError=null);loadStreams(session)}
    fun setQuality(q:Quality){_ui.value=_ui.value.copy(quality=q)}
    fun setSession(session:Session)=viewModelScope.launch{_ui.value=_ui.value.copy(session=session,streams=emptyList(),providerError=null);loadStreams(session)}
    private suspend fun loadStreams(session:Session){provider.streams(session.id).fold({sources->_ui.value=_ui.value.copy(streams=sources.take(6),providerError=null);resolveVisible(sources.take(6))},{_ui.value=_ui.value.copy(providerError=it.message?:"Unable to load streams")})}
    private suspend fun resolveVisible(sources:List<StreamSource>){sources.forEach{source->val contentId=source.contentId?:return@forEach;provider.resolve(PlaybackRequest(contentId,source.channelId,_ui.value.quality)).onSuccess{playback->_ui.value=_ui.value.copy(streams=_ui.value.streams.map{if(it.id==source.id)it.copy(url=playback.manifestUrl,drmLicenseUrl=playback.licenseUrl,requestHeaders=playback.streamHeaders,drmRequestHeaders=playback.licenseHeaders)else it})}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}}
    fun setLayout(layout:LayoutPreset){val count=when(layout){LayoutPreset.SINGLE->1;LayoutPreset.SPLIT_2->2;LayoutPreset.GRID_4->4;LayoutPreset.GRID_6->6};_ui.value=_ui.value.copy(layout=layout,streams=_ui.value.streams.take(count));persist()}
    fun panel(panel:String?){_ui.value=_ui.value.copy(selectedPanel=panel)}
    fun sync(delta:Long){_ui.value=_ui.value.copy(syncOffsetMs=_ui.value.syncOffsetMs+delta)}
    fun providerError(message:String?){_ui.value=_ui.value.copy(providerError=message)}
    fun saveCurrentSetup(name:String="My Race View"){val current=_ui.value;viewModelScope.launch{store.save(SavedSetup("default",name,current.layout,current.streams.map{it.id}))}}
    fun clearSavedSetup()=viewModelScope.launch{store.clear()}
    override fun onCleared(){timingClient.stop();super.onCleared()}
    private fun persist(){saveCurrentSetup()}
}
