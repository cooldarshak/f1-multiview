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
import app.f1multiview.data.ContinueWatchingStore
import app.f1multiview.data.f1tv.AuthorizedF1TvGateway
import app.f1multiview.data.f1tv.TmePlaybackParser
import app.f1multiview.data.timing.LiveTimingClient
import app.f1multiview.data.timing.ReplayTimingClient
import app.f1multiview.data.timing.TrackMapClient
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
    val telemetry: List<DriverTelemetry> =DemoRepository.telemetry(), val liveSessionInfo: LiveSessionInfo = LiveSessionInfo(),val timing: List<TimingRow> =DemoRepository.timing(), val currentLap:Int = 0, val totalLaps:Int = 0,
    val raceControl: List<RaceControlEvent> =DemoRepository.raceControl(), val weather: TimingWeather = TimingWeather(),val sessionClock:String = "-",val selectedPanel:String?=null,val syncOffsetMs:Long=0,
    val providerError:String?=null,val vodSeasons: List<VodSeason> =emptyList(),val selectedSeason:VodSeason?=null,
    val vodEvents: List<VodEvent> =emptyList(),val selectedEvent:VodEvent?=null,val vodSessions: List<VodSession> =emptyList(),
    val quality:Quality=Quality.AUTO,val timingStatus:String="OFFLINE",val selectedStreamIds:List<String> = emptyList(),
    val mainStreamId:String? = null,
    val savedSetups: List<SavedSetup> = emptyList(),
    val calendar: List<CalendarRace> = emptyList(),
    val standings: List<StandingRow> = emptyList(),
    val results: List<ResultRow> = emptyList(),
    val showsDocs: List<EditorialItem> = emptyList(),
    val teamRadio: List<TeamRadioItem> = emptyList(), val replayChannelDiffs: Map<String,Long> = emptyMap(),
    val trackPositions: List<TrackDriverPosition> = emptyList(), val trackStatus: TrackStatusInfo = TrackStatusInfo(), val trackGeometry: TrackMapGeometry? = null,
    val customRadioUrl:String = "", val radioDelayMs:Long = 0L, val preferCustomRadio:Boolean = false, val selectedSeries:String = "F1",
    val continueWatching: List<ContinueWatchingEntry> = emptyList(),
    val pendingResume: ContinueWatchingEntry? = null,
    val tiledMultiviewSession: TiledMultiviewSession? = null,
    val selectedTiledFeedIds: List<String> = emptyList()
)
class MultiViewViewModel(application:Application):AndroidViewModel(application){
    private val store=SavedSetupStore(application)
     private val continueStore=ContinueWatchingStore(application)
    private val provider:PlaybackGateway=AuthorizedF1TvGateway(application)
    private val trackMapClient=TrackMapClient()
    private val timingClient=LiveTimingClient(viewModelScope){ provider.liveTimingHeaders() }
    private val replayTimingClient=ReplayTimingClient()
    private val featureClient=UgisFeatureClient(application)
    private val prefs=application.getSharedPreferences("f1_multiview_radio",0)
    private val _ui=MutableStateFlow(UiState());val ui=_ui.asStateFlow()
    init{
        _ui.value=_ui.value.copy(customRadioUrl=prefs.getString("radio_url","").orEmpty(),radioDelayMs=prefs.getLong("radio_delay_ms",0L),preferCustomRadio=prefs.getBoolean("radio_prefer",false),selectedSeries=prefs.getString("series_filter","F1").orEmpty())
        timingClient.start()
        viewModelScope.launch{timingClient.rows.collect{rows->if(rows.isNotEmpty())_ui.value=_ui.value.copy(timing=rows)}}
        viewModelScope.launch{timingClient.status.collect{status->_ui.value=_ui.value.copy(timingStatus=status)}}
        viewModelScope.launch{timingClient.raceControl.collect{events->if(events.isNotEmpty())_ui.value=_ui.value.copy(raceControl=events)}}
        viewModelScope.launch{timingClient.weather.collect{weather->_ui.value=_ui.value.copy(weather=weather)}}
        viewModelScope.launch{timingClient.teamRadio.collect{items->_ui.value=_ui.value.copy(teamRadio=items)}}
        viewModelScope.launch{timingClient.telemetry.collect{items->if(items.isNotEmpty())_ui.value=_ui.value.copy(telemetry=items)}}
        viewModelScope.launch{timingClient.sessionInfo.collect{info->_ui.value=_ui.value.copy(liveSessionInfo=info)}}
        viewModelScope.launch{timingClient.trackPositions.collect{positions->_ui.value=_ui.value.copy(trackPositions=positions)}}
        viewModelScope.launch{timingClient.trackStatus.collect{status->_ui.value=_ui.value.copy(trackStatus=status)}}
        viewModelScope.launch{timingClient.lapCount.collect{laps->_ui.value=_ui.value.copy(currentLap=laps.first,totalLaps=laps.second)}}
        viewModelScope.launch{timingClient.sessionClock.collect{clock->_ui.value=_ui.value.copy(sessionClock=clock)}}
        viewModelScope.launch{val restored=provider.restoreSession().getOrDefault(false);if(restored){timingClient.restart();loadSessions();loadVodSeasons();loadShowsDocs()}else _ui.value=_ui.value.copy(auth=AuthState.SignedOut)}
        viewModelScope.launch{
            store.setups.collect { setups -> _ui.value = _ui.value.copy(savedSetups = setups) }
        }
        viewModelScope.launch {
            continueStore.entries.collect { entries ->
                _ui.value = _ui.value.copy(continueWatching = entries)
            }
        }
        viewModelScope.launch{
            store.setup.collect{setup->
                if(setup!=null&&setup.streamIds.isNotEmpty()&&_ui.value.streams.isNotEmpty()){
                    val allStreams=_ui.value.streams
                    val availableIds=allStreams.map { it.id }.toSet()
                    val restored=setup.streamIds.filter { it in availableIds }
                    val savedMain=setup.mainStreamId?.takeIf { it in restored }
                    val maxFeeds=when(setup.layout){
                        LayoutPreset.SINGLE->1
                        LayoutPreset.SPLIT_2->2
                        LayoutPreset.GRID_4->4
                        LayoutPreset.GRID_6->if (setup.streamIds.any { it in availableIds } && openTiledCapable()) 24 else 4
                    }
                    val mainId=savedMain ?: restored.firstOrNull()
                    val selected=(listOfNotNull(mainId)+restored.filterNot { it==mainId }).distinct().take(maxFeeds)
                    _ui.value=_ui.value.copy(layout=setup.layout,selectedStreamIds=selected,mainStreamId=mainId)
                }
            }
        }
    }
    fun signInWithSessionToken(token:String)=viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signInWithSessionToken(token).fold({ _ui.value=_ui.value.copy(auth=AuthState.SignedIn);timingClient.restart();loadSessions();loadVodSeasons();loadShowsDocs()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Browser sign-in failed"),providerError=it.message)})}
    fun signIn(username:String,password:String){if(username.isBlank()||password.isBlank())return;viewModelScope.launch{_ui.value=_ui.value.copy(auth=AuthState.SigningIn,providerError=null);provider.signIn(ProviderCredentials(username.trim(),password)).fold({_ui.value=_ui.value.copy(auth=AuthState.SignedIn);timingClient.restart();loadSessions();loadVodSeasons();loadShowsDocs()},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Sign-in failed"),providerError=it.message)})}}
    fun signOut()=viewModelScope.launch{provider.signOut();_ui.value=UiState(auth=AuthState.SignedOut)}
    private suspend fun loadSessions(){provider.sessions().fold({sessions->val first=sessions.firstOrNull();_ui.value=_ui.value.copy(auth=AuthState.SignedIn,sessions=sessions,session=first);if(first!=null)loadStreams(first)},{_ui.value=_ui.value.copy(auth=AuthState.Error(it.message?:"Unable to load F1 TV sessions"),providerError=it.message)})}
    fun loadVodSeasons()=viewModelScope.launch{provider.vodSeasons().onSuccess{seasons->val selected=seasons.firstOrNull();_ui.value=_ui.value.copy(vodSeasons=seasons,selectedSeason=selected);if(selected!=null)loadVodEvents(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodEvents(season:VodSeason){provider.vodEvents(season).onSuccess{allEvents->
        val selectedSeries = RacingSeries.fromId(_ui.value.selectedSeries).id
        val events = allEvents.filter { it.series.equals(selectedSeries,true) }
        val selected=events.maxWithOrNull(compareBy<VodEvent>{it.meetingNumber}.thenBy{it.meetingName})?:events.firstOrNull();_ui.value=_ui.value.copy(vodEvents=events,selectedEvent=selected);if(selected!=null)loadVodSessions(selected)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    private suspend fun loadVodSessions(event:VodEvent){provider.vodSessions(event).onSuccess{sessions->val ordered=sessions.sortedWith(compareBy<VodSession>{when{it.stage.equals("race",true)&&!it.title.contains("highlight",true)->0;it.stage.equals("race",true)->1;it.stage.equals("qualifying",true)->2;it.stage.equals("sprint",true)->3;it.stage.equals("sprint-qualifying",true)->4;it.stage.startsWith("practice",true)->5;it.stage.equals("pre-show",true)->6;it.stage.equals("post-show",true)->7;else->8}}.thenBy{it.startTime}.thenBy{it.title});val selected=ordered.firstOrNull{it.stage.equals("race",true)&&!it.title.contains("highlight",true)}?:ordered.firstOrNull();_ui.value=_ui.value.copy(vodSessions=ordered,selectedEvent=event);if(selected!=null){val session=Session(selected.contentId,selected.title,selected.series,"Replay",false,event.seasonYear,event.pageId,selected.series,selected.stage,event.meetingNumber,selected.artworkUrl?:event.artworkUrl, selected.backgroundArtworkUrl?:event.backgroundArtworkUrl);_ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),mainStreamId=null,providerError=null);loadStreams(session);loadReplayTiming(session)}}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun selectVodSeason(season:VodSeason)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedSeason=season,selectedEvent=null,vodEvents=emptyList(),vodSessions=emptyList());loadVodEvents(season)}
    fun selectVodEvent(event:VodEvent)=viewModelScope.launch{_ui.value=_ui.value.copy(selectedEvent=event,vodSessions=emptyList());loadVodSessions(event)}
    fun playEditorial(item: EditorialItem)=viewModelScope.launch {
        // Editorial cards are real F1 TV content, not decorative tiles. Resolve the
        // item's content id through the same playback gateway used by race sessions.
        val session = Session(
            id = item.contentId,
            name = item.title,
            series = "F1",
            sessionType = "Editorial",
            live = false,
            seasonYear = _ui.value.selectedSeason?.year,
            eventPageId = item.pageId,
            country = "F1",
            dateLabel = "Editorial",
            meetingNumber = null,
            artworkUrl = item.artworkUrl,
            backgroundArtworkUrl = item.artworkUrl
        )
        _ui.value = _ui.value.copy(
            session = session,
            streams = emptyList(),
            selectedStreamIds = emptyList(),
            mainStreamId = null,
            providerError = null,
            pendingResume = null
        )
        loadStreams(session)
    }

    fun selectVodSession(vod:VodSession)=viewModelScope.launch{
         val session=Session(vod.contentId,vod.title,vod.series,"Replay",false,_ui.value.selectedSeason?.year,vod.eventPageId,vod.series,vod.type,_ui.value.selectedEvent?.meetingNumber,vod.artworkUrl?:_ui.value.selectedEvent?.artworkUrl, vod.backgroundArtworkUrl?:_ui.value.selectedEvent?.backgroundArtworkUrl)
         val resume=continueStore.entries.first().firstOrNull { it.contentId == vod.contentId }
         _ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),mainStreamId=null,providerError=null,pendingResume=resume)
         loadStreams(session, autoSelectFeeds = true)
         loadReplayTiming(session)
     }
    fun prepareStream(id:String)=viewModelScope.launch {
        val source=_ui.value.streams.firstOrNull{it.id==id} ?: return@launch
        if(source.url==null && source.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)) {
            resolveSource(source)
        }
    }

    fun setQuality(q:Quality){_ui.value=_ui.value.copy(quality=q)}

     fun clearPendingResume() {
         _ui.value = _ui.value.copy(pendingResume = null)
     }

     fun saveContinueWatching(session: Session, positionMs: Long, durationMs: Long, streamId: String?) {
         if (session.live || positionMs < 10_000L) return
         val safeDuration = durationMs.coerceAtLeast(0L)
         val safePosition = positionMs.coerceAtLeast(0L)
         if (safeDuration > 0L && safePosition >= (safeDuration - 15_000L).coerceAtLeast(0L)) {
             viewModelScope.launch { continueStore.remove(session.id) }
             return
         }
         viewModelScope.launch {
             continueStore.upsert(
                 ContinueWatchingEntry(
                     contentId = session.id,
                     title = session.name,
                     series = session.series,
                     eventPageId = session.eventPageId ?: 0,
                     stage = session.sessionType,
                     positionMs = safePosition,
                     durationMs = safeDuration,
                     updatedAtMs = System.currentTimeMillis(),
                     artworkUrl = session.artworkUrl,
                     backgroundArtworkUrl = session.backgroundArtworkUrl,
                     seasonYear = session.seasonYear,
                     meetingNumber = session.meetingNumber,
                     streamId = streamId
                 )
             )
         }
     }

     fun removeContinueWatching(contentId: String) =
         viewModelScope.launch { continueStore.remove(contentId) }

     fun resumeContinueWatching(entry: ContinueWatchingEntry) = viewModelScope.launch {
         val season = _ui.value.vodSeasons.firstOrNull { it.year == entry.seasonYear }
             ?: provider.vodSeasons().getOrNull()?.firstOrNull { it.year == entry.seasonYear }
         if (season == null) {
             _ui.value = _ui.value.copy(providerError = "Unable to locate the saved race season")
             return@launch
         }
         val events = provider.vodEvents(season).getOrElse {
             _ui.value = _ui.value.copy(providerError = it.message ?: "Unable to load saved race")
             return@launch
         }
         val event = events.firstOrNull { it.pageId == entry.eventPageId } ?: events.firstOrNull()
         if (event == null) {
             _ui.value = _ui.value.copy(providerError = "Saved race is no longer available")
             return@launch
         }
         val sessions = provider.vodSessions(event).getOrElse {
             _ui.value = _ui.value.copy(providerError = it.message ?: "Unable to load saved session")
             return@launch
         }
         val vod = sessions.firstOrNull { it.contentId == entry.contentId }
         if (vod == null) {
             _ui.value = _ui.value.copy(providerError = "Saved session is no longer available")
             return@launch
         }
         val session = Session(vod.contentId, vod.title, vod.series, "Replay", false,
             season.year, vod.eventPageId, vod.series, vod.type, event.meetingNumber,
             vod.artworkUrl ?: event.artworkUrl,
             vod.backgroundArtworkUrl ?: event.backgroundArtworkUrl)
         _ui.value = _ui.value.copy(
             selectedSeason = season,
             vodEvents = events,
             selectedEvent = event,
             vodSessions = sessions.sortedBy { it.startTime },
             session = session,
             streams = emptyList(),
             tiledMultiviewSession = null, selectedTiledFeedIds = emptyList(),
             selectedStreamIds = emptyList(),
             mainStreamId = null,
             providerError = null,
             pendingResume = entry
         )
         loadStreams(session)
         loadReplayTiming(session)
     }
    fun setSession(session:Session)=viewModelScope.launch{_ui.value=_ui.value.copy(session=session,streams=emptyList(),selectedStreamIds=emptyList(),tiledMultiviewSession=null,providerError=null);loadStreams(session)}
    private suspend fun loadStreams(session:Session, autoSelectFeeds:Boolean = false){
        provider.streams(session.id).fold(
            {sources->
                val visible=sources
                val mainSource=visible.firstOrNull { it.kind == StreamKind.WORLD } ?: visible.firstOrNull()
                val playableSources = visible.filter {
                    it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)
                }
                val selected = if (autoSelectFeeds) {
                    listOfNotNull(mainSource?.id) +
                        playableSources.filter { it.id != mainSource?.id }.take(3).map { it.id }
                } else {
                    listOfNotNull(mainSource?.id)
                }
                val selectedDistinct = selected.distinct().take(4)
                _ui.value=_ui.value.copy(
                    streams=visible,
                    selectedStreamIds=selectedDistinct,
                    mainStreamId=mainSource?.id,
                    providerError=null
                )
                val sourcesToResolve = if (autoSelectFeeds) {
                    visible.filter { it.id in selectedDistinct && it.url == null && it.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA) }
                } else {
                    listOfNotNull(mainSource?.takeIf { it.url == null })
                }
                for (source in sourcesToResolve) {
                    resolveSource(source)
                }
            },
            {_ui.value=_ui.value.copy(providerError=it.message?:"Unable to load streams")}
        )
    }
    private suspend fun resolveSource(source:StreamSource){
        val contentId=source.contentId?:return
        provider.resolve(PlaybackRequest(contentId,source.channelId,_ui.value.quality)).onSuccess{playback->
            // TME describes the multiview playback session, not an arbitrary
            // secondary feed. Only the current reference/main feed may establish or
            // replace the session-level TME contract.
            val isReference = source.id == _ui.value.mainStreamId
            _ui.value=_ui.value.copy(
                tiledMultiviewSession = if (isReference) playback.tiledMultiview
                    else _ui.value.tiledMultiviewSession,
                selectedTiledFeedIds = if (isReference) {
                    val tiled = playback.tiledMultiview
                    if (tiled != null) {
                        val mainStream = _ui.value.streams.firstOrNull { it.id == _ui.value.mainStreamId }
                        val channel = mainStream?.channelId?.trim().takeIf { !it.isNullOrBlank() }
                        val mainIndex = channel?.let { ch ->
                            tiled.feeds.indexOfFirst { it.channelId?.toString() == ch }
                        } ?: -1
                        listOfNotNull(
                            if (mainIndex >= 0) tiled.feedIds.getOrNull(mainIndex) else tiled.feedIds.firstOrNull()
                        )
                    } else {
                        _ui.value.selectedTiledFeedIds
                    }
                } else _ui.value.selectedTiledFeedIds,
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
                        playToken=playback.playToken,
                        tmeJson=playback.tmeJson
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
        val f1Dash=streams.firstOrNull{it.kind==StreamKind.F1_DASH} ?: streams.firstOrNull{it.kind==StreamKind.F1_DASH_DATA}
        val picked=when(name){
            "side" -> listOfNotNull(world,obc).map{it.id}
            "quad" -> listOfNotNull(world,tracker,data,f1Dash).map{it.id}
            else -> listOfNotNull(world).map{it.id}
        }
        val layout=if(name=="side") LayoutPreset.SPLIT_2 else if(name=="quad") LayoutPreset.GRID_4 else LayoutPreset.SINGLE
        _ui.value=_ui.value.copy(layout=layout,selectedStreamIds=picked.take(if(layout==LayoutPreset.SPLIT_2)2 else if(layout==LayoutPreset.GRID_4)4 else 1),mainStreamId=picked.firstOrNull())
        persist()
        picked.mapNotNull{id->streams.firstOrNull{it.id==id}}.filter{it.url==null && it.kind in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)}.forEach{viewModelScope.launch{resolveSource(it)}}
    }
    private fun maxLogicalFeeds(layout: LayoutPreset = _ui.value.layout): Int = when (layout) {
        LayoutPreset.SINGLE -> 1
        LayoutPreset.SPLIT_2 -> 2
        LayoutPreset.GRID_4 -> 4
        LayoutPreset.GRID_6 -> 4
    }

    private fun openTiledCapable(): Boolean {
        val mainId = _ui.value.mainStreamId ?: return false
        val source = _ui.value.streams.firstOrNull { it.id == mainId } ?: return false
        return source.tmeJson?.let(TmePlaybackParser::parse)?.isTiledSource == true
    }

    fun setLayout(layout:LayoutPreset){
    val maxFeeds = maxLogicalFeeds(layout)
    _ui.value = _ui.value.copy(
        layout = layout,
        selectedStreamIds = _ui.value.selectedStreamIds.take(maxFeeds),
        providerError = null
    )
    persist()
}
fun toggleStream(id:String)=viewModelScope.launch{
    val current = _ui.value.selectedStreamIds
    val maxFeeds = maxLogicalFeeds()
    if (id in current) {
        if (id == _ui.value.mainStreamId) return@launch
        _ui.value = _ui.value.copy(
            selectedStreamIds = current.filterNot { it == id },
            providerError = null
        )
        persist()
        return@launch
    }

    if (current.size >= maxFeeds) {
        _ui.value = _ui.value.copy(
            providerError = "4 simultaneous feeds is the current Director limit."
        )
        return@launch
    }

    val needed = current.size + 1
    val requiredLayout = when {
        needed <= 1 -> LayoutPreset.SINGLE
        needed <= 2 -> LayoutPreset.SPLIT_2
        else -> LayoutPreset.GRID_4
    }
    val targetLayout = if (current.size <= 1 || current.size + 1 > maxFeeds) {
        requiredLayout
    } else {
        _ui.value.layout
    }

    _ui.value = _ui.value.copy(
        layout = targetLayout,
        selectedStreamIds = (current + id).distinct(),
        providerError = null
    )
    persist()

    val source = _ui.value.streams.firstOrNull { it.id == id } ?: return@launch
    if (source.url == null &&
        source.kind !in setOf(StreamKind.TRACK_MAP, StreamKind.F1_DASH_DATA)
    ) {
        resolveSource(source)
    }
}
    fun streamIdForTiledFeed(feedId: String): String? {
        val session = _ui.value.tiledMultiviewSession ?: return null
        val index = session.feedIds.indexOf(feedId)
        if (index < 0) return null
        val channel = session.feeds.getOrNull(index)?.channelId?.toString()
        return _ui.value.streams.firstOrNull { it.channelId?.trim() == channel }?.id
    }

    private fun tiledFeedIdForStream(streamId: String): String? {
        val session = _ui.value.tiledMultiviewSession ?: return null
        val source = _ui.value.streams.firstOrNull { it.id == streamId } ?: return null
        val channel = source.channelId?.trim().takeIf { !it.isNullOrBlank() }
        if (channel != null) {
            val index = session.feeds.indexOfFirst { it.channelId?.toString() == channel }
            if (index >= 0) return session.feedIds.getOrNull(index)
        }
        return source.id.takeIf { it in session.feedIds }
    }

    fun toggleTiledStream(streamId: String) {
        val tileId = tiledFeedIdForStream(streamId) ?: return
        val session = _ui.value.tiledMultiviewSession ?: return
        val validIds = session.feedIds.take(24)
        if (tileId !in validIds) return
        val current = _ui.value.selectedTiledFeedIds.filter { it in validIds }.distinct()
        if (tileId in current) {
            if (current.size <= 1) return
            _ui.value = _ui.value.copy(
                selectedTiledFeedIds = current.filterNot { it == tileId },
                providerError = null
            )
        } else {
            ensureTiledFeedSelected(tileId)
        }
        persist()
    }

    fun ensureTiledStreamSelected(streamId: String) {
        tiledFeedIdForStream(streamId)?.let(::ensureTiledFeedSelected)
    }

    fun ensureTiledFeedSelected(feedId: String) {
        val session = _ui.value.tiledMultiviewSession ?: return
        val validIds = session.feedIds.take(24)
        if (feedId !in validIds) return
        val current = _ui.value.selectedTiledFeedIds.filter { it in validIds }.distinct()
        if (feedId in current) return

        val needed = current.size + 1
        val targetLayout = when {
            needed <= 1 -> LayoutPreset.SINGLE
            needed <= 2 -> LayoutPreset.SPLIT_2
            needed <= 4 -> LayoutPreset.GRID_4
            else -> LayoutPreset.GRID_6
        }
        val capacity = when (targetLayout) {
            LayoutPreset.SINGLE -> 1
            LayoutPreset.SPLIT_2 -> 2
            LayoutPreset.GRID_4 -> 4
            LayoutPreset.GRID_6 -> 6
        }
        _ui.value = _ui.value.copy(
            layout = targetLayout,
            selectedTiledFeedIds = (current + feedId).distinct().take(capacity),
            providerError = null
        )
        persist()
    }

    fun toggleTiledFeed(feedId: String) {
        val session = _ui.value.tiledMultiviewSession ?: return
        val validIds = session.feedIds
        if (feedId !in validIds) return
        val current = _ui.value.selectedTiledFeedIds.filter { it in validIds }.distinct()
        val next = if (feedId in current) {
            current.filterNot { it == feedId }
        } else {
            if (current.size >= 24) {
                _ui.value = _ui.value.copy(providerError = "The tiled source has reached its 24-feed logical limit.")
                return
            }
            current + feedId
        }
        _ui.value = _ui.value.copy(
            selectedTiledFeedIds = next,
            providerError = null
        )
    }

    fun activateTracker(){
    _ui.value=_ui.value.copy(selectedPanel="tracker")
    loadTrackMapGeometry()
}
fun activateRaceMap(){
    _ui.value=_ui.value.copy(selectedPanel="tracker")
    loadTrackMapGeometry()
}
fun loadTrackMapGeometry()=viewModelScope.launch{
    val info=_ui.value.liveSessionInfo
    val key=info.circuitKey ?: return@launch
    val year=info.year ?: java.time.Year.now().value
    trackMapClient.load(key,year).onSuccess{geometry->_ui.value=_ui.value.copy(trackGeometry=geometry,providerError=null)}
        .onFailure{_ui.value=_ui.value.copy(providerError="Track map geometry unavailable: "+(it.message?:"unknown error"))}
}
    fun setMainStream(id:String)=viewModelScope.launch{
        val source=_ui.value.streams.firstOrNull{it.id==id} ?: return@launch
        if (source.kind == StreamKind.TRACK_MAP || source.kind == StreamKind.F1_DASH_DATA) return@launch
        val current=_ui.value.selectedStreamIds
        val maxFeeds=maxLogicalFeeds()
        val oldMain = _ui.value.mainStreamId
        val next = listOf(id) + current.filterNot { it == id || it == oldMain }
        _ui.value=_ui.value.copy(selectedStreamIds=next.distinct().take(maxFeeds),mainStreamId=id,providerError=null)
        persist()
        if(source.url==null) resolveSource(source)
    }
    fun setSeries(value:String)=viewModelScope.launch{
        val series=RacingSeries.fromId(value).id
        prefs.edit().putString("series_filter",series).apply()
        _ui.value=_ui.value.copy(selectedSeries=series,calendar=emptyList(),standings=emptyList(),results=emptyList(),vodEvents=emptyList(),vodSessions=emptyList(),session=null,streams=emptyList(),selectedStreamIds=emptyList(),mainStreamId=null)
        loadVodSeasons()
        if(series=="F1") loadCalendar()
        if(series!="F1") _ui.value=_ui.value.copy(providerError="Calendar, standings and results are currently sourced from the F1 Jolpica feed; "+RacingSeries.fromId(series).displayName+" playback/catalog filtering is supported.")
    }
    fun setCustomRadioUrl(value:String){prefs.edit().putString("radio_url",value).apply();_ui.value=_ui.value.copy(customRadioUrl=value)}
    fun setRadioDelayMs(value:Long){prefs.edit().putLong("radio_delay_ms",value.coerceIn(0L,120_000L)).apply();_ui.value=_ui.value.copy(radioDelayMs=value.coerceIn(0L,120_000L))}
    fun setPreferCustomRadio(value:Boolean){prefs.edit().putBoolean("radio_prefer",value).apply();_ui.value=_ui.value.copy(preferCustomRadio=value)}
    fun loadShowsDocs()=viewModelScope.launch{provider.showsAndDocs().onSuccess{_ui.value=_ui.value.copy(showsDocs=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}}
    fun loadCalendar()=viewModelScope.launch{
        if(_ui.value.selectedSeries!="F1"){_ui.value=_ui.value.copy(calendar=emptyList(),providerError="Calendar is available from Jolpica for Formula 1; "+RacingSeries.fromId(_ui.value.selectedSeries).displayName+" uses the F1 TV event catalog.");return@launch}
        featureClient.calendar().onSuccess{_ui.value=_ui.value.copy(calendar=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}
    }
    fun loadStandings()=viewModelScope.launch{
        if(_ui.value.selectedSeries!="F1"){_ui.value=_ui.value.copy(standings=emptyList(),providerError="Standings are available from Jolpica for Formula 1; "+RacingSeries.fromId(_ui.value.selectedSeries).displayName+" playback is supported separately.");return@launch}
        featureClient.standings().onSuccess{_ui.value=_ui.value.copy(standings=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}
    }
    fun loadResults()=viewModelScope.launch{
        if(_ui.value.selectedSeries!="F1"){_ui.value=_ui.value.copy(results=emptyList(),providerError="Results are available from Jolpica for Formula 1; "+RacingSeries.fromId(_ui.value.selectedSeries).displayName+" playback is supported separately.");return@launch}
        featureClient.results().onSuccess{_ui.value=_ui.value.copy(results=it)}.onFailure{_ui.value=_ui.value.copy(providerError=it.message)}
    }
    fun panel(panel:String?){_ui.value=_ui.value.copy(selectedPanel=panel)}
    fun updateReplayTiming(positionMs:Long){ val rows=replayTimingClient.rowsAt(positionMs); if(rows.isNotEmpty()) _ui.value=_ui.value.copy(timing=rows,timingStatus="REPLAY") }
    fun loadReplayTiming(session:Session)=viewModelScope.launch{ val year=session.seasonYear ?: return@launch; val meeting=session.meetingNumber ?: return@launch; replayTimingClient.load(year,meeting,session.sessionType).onSuccess{ _ui.value=_ui.value.copy(replayChannelDiffs=replayTimingClient.sync().channelDiffs) }.onFailure{ if(_ui.value.session?.id==session.id) _ui.value=_ui.value.copy(providerError="Replay timing unavailable: "+(it.message?:"archive not found")) } }
    fun sync(delta:Long){_ui.value=_ui.value.copy(syncOffsetMs=_ui.value.syncOffsetMs+delta);replayTimingClient.nudge(delta)}
    fun setReplayTimingOffset(offsetMs:Long){_ui.value=_ui.value.copy(syncOffsetMs=offsetMs);replayTimingClient.setSyncOffset(offsetMs)}
    fun calibrateReplayTiming(videoPositionMs:Long,timingPositionMs:Long){setReplayTimingOffset(replayTimingClient.calibratedOffset(videoPositionMs,timingPositionMs))}

    fun providerError(message:String?){_ui.value=_ui.value.copy(providerError=message)}
    fun saveCurrentSetup(name:String="My Race View"){val current=_ui.value;viewModelScope.launch{store.save(SavedSetup("setup-"+System.currentTimeMillis(),name,current.layout,current.selectedStreamIds,current.mainStreamId))}}
    fun saveNamedSetup(id:String, name:String){val current=_ui.value;viewModelScope.launch{store.save(SavedSetup(id,name,current.layout,current.selectedStreamIds,current.mainStreamId))}}
    fun loadSavedSetup(setup:SavedSetup){val available=_ui.value.streams.map{it.id}.toSet();val ids=setup.streamIds.filter{it in available};val max=maxLogicalFeeds(setup.layout);val main=setup.mainStreamId?.takeIf{it in ids}?:ids.firstOrNull();_ui.value=_ui.value.copy(layout=setup.layout,selectedStreamIds=listOfNotNull(main)+ids.filterNot{it==main}.take(max-1),mainStreamId=main);persist()}
    fun deleteSavedSetup(id:String)=viewModelScope.launch{store.delete(id)}
    fun clearSavedSetup()=viewModelScope.launch{store.clear()}
    override fun onCleared(){timingClient.stop();super.onCleared()}
    private fun persist(){ val current=_ui.value; viewModelScope.launch { store.saveCurrent(SavedSetup("current","Current View",current.layout,current.selectedStreamIds,current.mainStreamId)) } }
}
