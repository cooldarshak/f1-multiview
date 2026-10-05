package app.f1multiview.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.f1multiview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class AppUpdateInfo(val versionCode:Int,val versionName:String,val apkUrl:String,val notes:String="")

class AppUpdateManager(private val context:Context){
    private val http=OkHttpClient()
    private val manifestUrl="https://raw.githubusercontent.com/cooldarshak/f1-multiview/main/version.json"

    suspend fun check():Result<AppUpdateInfo?> = withContext(Dispatchers.IO){
        runCatching{
            val req=Request.Builder().url(manifestUrl).header("Cache-Control","no-cache").build()
            http.newCall(req).execute().use { response->
                if(!response.isSuccessful) error("Update check HTTP ${response.code}")
                val j=JSONObject(response.body?.string().orEmpty())
                val info=AppUpdateInfo(j.optInt("versionCode"),j.optString("versionName"),j.optString("apkUrl"),j.optString("notes"))
                if(info.versionCode>BuildConfig.VERSION_CODE && info.apkUrl.isNotBlank()) info else null
            }
        }
    }

    suspend fun downloadAndInstall(info:AppUpdateInfo):Result<Unit> = withContext(Dispatchers.IO){
        runCatching{
            val req=Request.Builder().url(info.apkUrl).build()
            http.newCall(req).execute().use { response->
                if(!response.isSuccessful) error("APK download HTTP ${response.code}")
                val file=File(context.cacheDir,"F1MultiView-${info.versionCode}.apk")
                response.body?.byteStream()?.use { input->file.outputStream().use { output->input.copyTo(output)} }
                if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()){
                    val settings=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settings)
                    error("Enable Install unknown apps for F1 MultiView, then check for updates again.")
                }
                val uri=FileProvider.getUriForFile(context,context.packageName+".fileprovider",file)
                val intent=Intent(Intent.ACTION_VIEW).apply{
                    setDataAndType(uri,"application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
