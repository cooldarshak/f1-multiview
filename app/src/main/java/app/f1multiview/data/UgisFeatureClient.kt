package app.f1multiview.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CalendarRace(val round:Int,val name:String,val circuit:String,val location:String,val date:String,val time:String?)
data class StandingRow(val position:String,val name:String,val constructor:String,val points:String,val wins:String)
data class ResultRow(val position:String,val name:String,val constructor:String,val points:String,val status:String)

class UgisFeatureClient(context: Context? = null) {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val base = "https://api.jolpi.ca/ergast/f1"
    private val userAgent = "F1MultiView/1.2"
    private val cache = LinkedHashMap<String, Pair<Long, JSONObject>>(16, 0.75f, true)
    private val cacheTtlMs = 5 * 60 * 1000L
    private val diskCache = context?.getSharedPreferences("f1_feature_cache", Context.MODE_PRIVATE)

    suspend fun calendar(season:String="current"):Result<List<CalendarRace>> = getCached("$base/$season/races/").map { root ->
        val races = root.optJSONObject("MRData")?.optJSONObject("RaceTable")?.optJSONArray("Races") ?: org.json.JSONArray()
        (0 until races.length()).mapNotNull { i ->
            val r=races.optJSONObject(i) ?: return@mapNotNull null
            val c=r.optJSONObject("Circuit") ?: JSONObject()
            val l=c.optJSONObject("Location") ?: JSONObject()
            CalendarRace(r.optString("round").toIntOrNull()?:0,r.optString("raceName"),c.optString("circuitName"),l.optString("locality"),r.optString("date"),r.optString("time").takeIf{it.isNotBlank()})
        }
    }

    suspend fun standings(season:String="current"):Result<List<StandingRow>> = getCached("$base/$season/driverstandings/").map { root ->
        val lists=root.optJSONObject("MRData")?.optJSONObject("StandingsTable")?.optJSONArray("StandingsLists") ?: org.json.JSONArray()
        val rows=lists.optJSONObject(0)?.optJSONArray("DriverStandings") ?: org.json.JSONArray()
        (0 until rows.length()).mapNotNull { i ->
            val x=rows.optJSONObject(i) ?: return@mapNotNull null
            val d=x.optJSONObject("Driver") ?: JSONObject()
            val cs=x.optJSONArray("Constructors")
            val constructor=if(cs!=null&&cs.length()>0) cs.optJSONObject(0)?.optString("name").orEmpty() else ""
            StandingRow(x.optString("positionText").ifBlank{x.optString("position")},listOf(d.optString("givenName"),d.optString("familyName")).filter{it.isNotBlank()}.joinToString(" "),constructor,x.optString("points"),x.optString("wins"))
        }
    }

    suspend fun results(season:String="current"):Result<List<ResultRow>> = getCached("$base/$season/last/results/").map { root ->
        val races=root.optJSONObject("MRData")?.optJSONObject("RaceTable")?.optJSONArray("Races") ?: org.json.JSONArray()
        val rows=races.optJSONObject(0)?.optJSONArray("Results") ?: org.json.JSONArray()
        (0 until rows.length()).mapNotNull { i ->
            val x=rows.optJSONObject(i) ?: return@mapNotNull null
            val d=x.optJSONObject("Driver") ?: JSONObject()
            val c=x.optJSONObject("Constructor") ?: JSONObject()
            ResultRow(x.optString("positionText").ifBlank{x.optString("position")},listOf(d.optString("givenName"),d.optString("familyName")).filter{it.isNotBlank()}.joinToString(" "),c.optString("name"),x.optString("points"),x.optString("status"))
        }
    }

    private suspend fun getCached(url:String):Result<JSONObject> = withContext(Dispatchers.IO) {
        synchronized(cache) {
            val hit = cache[url]
            if (hit != null && System.currentTimeMillis() - hit.first < cacheTtlMs) return@withContext Result.success(JSONObject(hit.second.toString()))
        }
        runCatching {
            val request=Request.Builder().url(url).header("User-Agent",userAgent).build()
            http.newCall(request).execute().use { response ->
                if(!response.isSuccessful) error("F1 data service HTTP ${response.code}")
                val json=JSONObject(response.body?.string().orEmpty())
                synchronized(cache) { cache[url]=System.currentTimeMillis() to JSONObject(json.toString()) }
                diskCache?.edit()?.putString(url,json.toString())?.putLong(url+"@ts",System.currentTimeMillis())?.apply()
                json
            }
        }.recoverCatching { error ->
            synchronized(cache) { cache[url]?.second?.let { return@recoverCatching JSONObject(it.toString()) } }
            diskCache?.getString(url,null)?.let { return@recoverCatching JSONObject(it) }
            throw error
        }
    }

