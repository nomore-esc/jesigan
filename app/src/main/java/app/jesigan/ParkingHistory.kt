package app.jesigan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 차가 직접 잰 주차 소요시간 기록.
 * 측정: 목적지 반경 500m 진입 → 기어 P. 장소 · 요일 · 시간대 · 주차장별로 최근 5회 보관.
 */
class ParkingHistory(context: Context) {
    private val prefs = context.getSharedPreferences("parking_history", Context.MODE_PRIVATE)

    private fun key(dest: String, weekday: Int, hour: Int, lotId: String) = "$dest|$weekday|$hour|$lotId"

    fun record(dest: String, weekday: Int, hour: Int, lotId: String, minutes: Int) {
        val k = key(dest, weekday, hour, lotId)
        val arr = JSONArray(prefs.getString(k, "[]"))
        arr.put(minutes)
        while (arr.length() > 5) arr.remove(0)
        prefs.edit().putString(k, arr.toString()).putString("last|$dest|$weekday|$hour", lotId).apply()
        bumpVisit(dest, weekday, hour)
    }

    fun average(dest: String, weekday: Int, hour: Int, lotId: String): Int? {
        val arr = JSONArray(prefs.getString(key(dest, weekday, hour, lotId), "[]"))
        if (arr.length() == 0) return null
        var sum = 0
        for (i in 0 until arr.length()) sum += arr.getInt(i)
        return Math.round(sum.toDouble() / arr.length()).toInt()
    }

    fun count(dest: String, weekday: Int, hour: Int, lotId: String): Int =
        JSONArray(prefs.getString(key(dest, weekday, hour, lotId), "[]")).length()

    fun lastLot(dest: String, weekday: Int, hour: Int): String? = prefs.getString("last|$dest|$weekday|$hour", null)

    private fun bumpVisit(dest: String, weekday: Int, hour: Int) {
        val visits = JSONObject(prefs.getString("visits", "{}"))
        val k = "$weekday|$hour"
        val o = visits.optJSONObject(k) ?: JSONObject()
        o.put(dest, o.optInt(dest, 0) + 1)
        visits.put(k, o)
        prefs.edit().putString("visits", visits.toString()).apply()
    }

    /** 이 요일 · 시간대에 가장 자주 간 곳 (2회 이상) */
    fun frequentDestination(weekday: Int, hour: Int): Pair<String, Int>? {
        val o = JSONObject(prefs.getString("visits", "{}")).optJSONObject("$weekday|$hour") ?: return null
        var best: Pair<String, Int>? = null
        o.keys().forEach { d -> val c = o.getInt(d); if (c >= 2 && (best == null || c > best!!.second)) best = d to c }
        return best
    }

    fun clear() = prefs.edit().clear().apply()
}
