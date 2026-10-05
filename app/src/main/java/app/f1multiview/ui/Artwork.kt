package app.f1multiview.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import app.f1multiview.data.f1tv.F1TvApiClient
import okhttp3.Request

private val artworkClient = OkHttpClient()
private val artworkCache = object : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

@Composable
fun F1Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackSeed: String = title
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(url?.let { artworkCache.get(it) }) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank() || artworkCache.get(url) != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", F1TvApiClient.BROWSER_UA)
                    .build()
                artworkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val bytes = response.body?.bytes() ?: return@runCatching null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }?.also { loaded -> artworkCache.put(url, loaded) }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = title,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        ArtworkFallback(title, modifier, fallbackSeed)
    }
}

@Composable
private fun ArtworkFallback(title: String, modifier: Modifier, seed: String) {
    val variants = listOf(
        listOf(Color(0xFF3A080A), Color(0xFFE10600)),
        listOf(Color(0xFF171821), Color(0xFF4A0B10)),
        listOf(Color(0xFF242630), Color(0xFF8E0A0D)),
        listOf(Color(0xFF0D0E14), Color(0xFF5E1115))
    )
    val pair = variants[(seed.hashCode() and Int.MAX_VALUE) % variants.size]
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(pair))
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .13f), Color.Transparent),
                    radius = 500f
                )
            )
        )
        Text(
            title.uppercase(),
            color = Color.White.copy(alpha = .9f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
        )
    }
}
