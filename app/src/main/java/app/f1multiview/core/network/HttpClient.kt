package app.f1multiview.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
