package app.f1multiview.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadioState(val playing:Boolean=false,val source:String?=null,val delayMs:Long=0L,val error:String?=null)

class RadioPlayer(context:Context){
    private val player=ExoPlayer.Builder(context.applicationContext).build()
    private val _state=MutableStateFlow(RadioState())
    val state:StateFlow<RadioState> = _state.asStateFlow()

    fun play(url:String,delayMs:Long){
        if(url.isBlank()) return
        _state.value=RadioState(true,url,delayMs,null)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }
    fun pause(){player.pause();_state.value=_state.value.copy(playing=false)}
    fun resume(){player.play();_state.value=_state.value.copy(playing=true)}
    fun stop(){player.stop();_state.value=RadioState(delayMs=_state.value.delayMs)}
    fun release(){player.release()}
}
