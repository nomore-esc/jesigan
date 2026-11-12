package app.jesigan

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Scenario(
    val id: String,
    val title: String,
    val destination: String,
    val destLat: Double,
    val destLng: Double,
    val appointment: String,
    val startTime: String,
    val weekday: Int,
    val remainMin: Int,
    val defaultLotId: String,
    val lots: List<Lot>,
    val seedHistory: Map<String, List<Int>>,
)

/** 시연 시나리오(assets/scenarios.json) + 서울 열린데이터 실시간 주차 대수 */
object LotRepository {

    fun loadScenarios(context: Context): List<Scenario> {
        val root = JSONObject(context.assets.open("scenarios.json").bufferedReader().readText())
        val arr = root.getJSONArray("scenarios")
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val la = s.getJSONArray("lots")
            val lots = (0 until la.length()).map { j ->
                val l = la.getJSONObject(j)
                Lot(
                    id = l.getString("id"), name = l.getString("name"),
                    lat = l.getDouble("lat"), lng = l.getDouble("lng"),
                    total = l.getInt("total"), occupied = l.getInt("occupied"),
                    walkMin = l.getInt("walkMin"), extraDriveMin = l.getInt("extraDriveMin"),
                    seoulCode = l.optString("seoulCode").ifEmpty { null },
                )
            }
            val seedObj = s.optJSONObject("seedHistory")
            val seed = mutableMapOf<String, List<Int>>()
            seedObj?.keys()?.forEach { k ->
                val a = seedObj.getJSONArray(k)
                seed[k] = (0 until a.length()).map { a.getInt(it) }
            }
            Scenario(
                s.getString("id"), s.getString("title"), s.getString("destination"),
                s.getDouble("destLat"), s.getDouble("destLng"), s.getString("appointment"),
                s.getString("startTime"), s.getInt("weekday"), s.getInt("remainMin"),
                s.getString("defaultLotId"), lots, seed,
            )
        }
    }

    /**
     * 서울 GetParkingInfo 실시간 조회. 네트워크 호출이므로 백그라운드 스레드에서 호출.
     * 인증키가 없으면 "sample" 키 (최대 5건) 사용.
     * @return 갱신된 주차장 수
     */
    fun refreshSeoulLive(lots: List<Lot>, apiKey: String = "sample"): Int {
        val wanted = lots.filter { it.seoulCode != null }
        if (wanted.isEmpty()) return 0
        val conn = URL("http://openapi.seoul.go.kr:8088/$apiKey/json/GetParkingInfo/1/5/").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val rows = JSONObject(body).getJSONObject("GetParkingInfo").getJSONArray("row")
        var updated = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val lot = wanted.firstOrNull { it.seoulCode == r.getString("PKLT_CD") } ?: continue
            if (r.optString("PRK_STTS_YN") != "1") continue
            lot.occupied = r.getDouble("NOW_PRK_VHCL_CNT").toInt()
            lot.live = true
            updated++
            SdkLog.add("서울 API", "GetParkingInfo", "${lot.name} ${lot.free}면 남음 (${r.optString("NOW_PRK_VHCL_UPDT_TM")})")
        }
        return updated
    }
}
