package app.jesigan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Pleos Connect SDK 연결부.
 * - Vehicle SDK · Gear: 기어 P = 주차 완료 (주차 소요시간 측정 종료점)
 * - NaviHelper SDK: 목적지 · 남은 시간 · 현재 위치 · 주차장 경유지 추가 · 도착 이벤트
 * SDK 호출은 모두 SdkLog에 기록되어 화면의 "SDK 활동" 패널에 표시됩니다.
 */
class PleosBridge(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onGear(parked: Boolean, label: String)
        fun onDestination(name: String, lat: Double, lng: Double, remainMin: Int)
        fun onLocation(lat: Double, lng: Double)
        fun onWaypointArrived(name: String)
        fun onDestinationArrived()
    }

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var gear: Gear? = null
    private var navi: NaviHelper? = null
    var vehicleReady = false
        private set
    var naviReady = false
        private set

    /** 내비에 진행 중인 경로가 있을 때만 경유지를 넣음 (경로 없이 넣으면 내비 검색 화면이 앞으로 나옴) */
    var hasRoute = false
        private set

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gearSel: VehicleGear?) {
            val g = gearSel ?: VehicleGear.GEAR_UNKNOWN
            SdkLog.add("Vehicle", "GearSelectionListener.onSelected", g.name)
            main.post { listener.onGear(g == VehicleGear.GEAR_PARK, label(g)) }
        }

        override fun onFailed(e: Exception) {
            SdkLog.add("Vehicle", "Gear 오류", e.message ?: e.javaClass.simpleName)
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val raw = destinationInfo.remainTime
            // remainTime 단위는 문서에 명시되지 않음. 초 단위로 보고 분으로 올림 (원값은 로그에 남김)
            val min = if (raw <= 0) 0 else Math.ceil(raw / 60.0).toInt()
            hasRoute = destinationInfo.name.isNotBlank() && raw > 0
            SdkLog.add("NaviHelper", "onDestinationInfo", "${destinationInfo.name} · remainTime=$raw")
            main.post { listener.onDestination(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude, min) }
        }

        override fun onDestinationChanged() {
            SdkLog.add("NaviHelper", "onDestinationChanged", "목적지 다시 조회")
            navi?.getDestinationInfo()
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            main.post { listener.onLocation(currentLocationInfo.latitude, currentLocationInfo.longitude) }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            SdkLog.add("NaviHelper", "onWayPointArrived", waypointArrivedInfo.name)
            main.post { listener.onWaypointArrived(waypointArrivedInfo.name) }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            SdkLog.add("NaviHelper", "onDestinationArrived", "")
            main.post { listener.onDestinationArrived() }
        }

        override fun onReRouteEnded() {
            SdkLog.add("NaviHelper", "onReRouteEnded", "재탐색 완료")
        }

        override fun onError(errorCode: NaviErrorCode) {
            SdkLog.add("NaviHelper", "onError", errorCode.toString())
        }
    }

    fun start() {
        try {
            val v = Vehicle(context)
            v.initialize()
            vehicle = v
            SdkLog.add("Vehicle", "initialize", "Vehicle 서비스 연결")
            main.postDelayed({ attachGear() }, 800)
        } catch (t: Throwable) {
            SdkLog.add("Vehicle", "initialize 실패", t.message ?: t.javaClass.simpleName)
        }
        try {
            val n = NaviHelper(context)
            navi = n
            n.initialize({
                naviReady = true
                n.addListener(naviListener)
                SdkLog.add("NaviHelper", "initialize", "Navi 서비스 연결")
                n.getDestinationInfo()
                n.getCurrentLocationInfo()
            }, { t ->
                SdkLog.add("NaviHelper", "initialize 실패", t.message ?: t.javaClass.simpleName)
            })
        } catch (t: Throwable) {
            SdkLog.add("NaviHelper", "initialize 실패", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun attachGear() {
        val v = vehicle ?: return
        try {
            val g = v.getGear()
            gear = g
            g.registerGearSelection(gearListener)
            g.getCurrentGearSelectionState({ curSel ->
                val cur = curSel ?: VehicleGear.GEAR_UNKNOWN
                vehicleReady = true
                SdkLog.add("Vehicle", "getCurrentGearSelectionState", cur.name)
                main.post { listener.onGear(cur == VehicleGear.GEAR_PARK, label(cur)) }
            }, { e -> SdkLog.add("Vehicle", "getCurrentGearSelectionState 실패", e.message ?: "") })
        } catch (t: Throwable) {
            SdkLog.add("Vehicle", "Gear 연결 실패", t.message ?: t.javaClass.simpleName)
        }
    }

    fun refreshNavi() {
        try {
            navi?.getDestinationInfo()
            navi?.getCurrentLocationInfo()
        } catch (t: Throwable) {
            Log.w(TAG, t)
        }
    }

    /** 주차장을 첫 번째 경유지로 추가 (목적지는 그대로 → 주차 후 도보 안내) */
    fun setParkingWaypoint(lot: Lot, replace: Boolean) {
        val n = navi
        if (n == null || !naviReady) {
            SdkLog.add("NaviHelper", "addWaypoint (시연)", "${lot.name} · Navi 미연결")
            return
        }
        if (!hasRoute) {
            SdkLog.add("NaviHelper", "addWaypoint 대기", "${lot.name} · 내비 경로 시작 시 추가")
            return
        }
        try {
            if (replace) {
                n.removeWaypoint(WaypointIndex.FIRST)
                SdkLog.add("NaviHelper", "removeWaypoint", "기존 주차장 경유지 제거")
            }
            n.addWaypoint(
                RequestWaypointInfo(
                    latitude = lot.lat, longitude = lot.lng, poiName = lot.name,
                    waypointIndex = WaypointIndex.FIRST, poiId = lot.id, address = "",
                    poiSubId = "0", poiProvider = "",
                )
            )
            SdkLog.add("NaviHelper", "addWaypoint", lot.name)
        } catch (t: Throwable) {
            SdkLog.add("NaviHelper", "addWaypoint 실패", t.message ?: t.javaClass.simpleName)
        }
    }

    fun release() {
        try { gear?.unregisterGearSelection(gearListener) } catch (_: Throwable) {}
        try { navi?.removeListener(naviListener); navi?.release() } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
    }

    private fun label(g: VehicleGear) = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN -> "-"
        else -> "D"
    }

    companion object {
        private const val TAG = "PleosBridge"
    }
}

/** 화면의 "SDK 활동" 패널용 기록 */
object SdkLog {
    data class Entry(val sdk: String, val call: String, val detail: String)

    private val entries = ArrayDeque<Entry>()
    private val main = Handler(Looper.getMainLooper())
    var onChange: (() -> Unit)? = null

    fun add(sdk: String, call: String, detail: String) {
        Log.i("SdkLog", "[$sdk] $call $detail")
        synchronized(entries) {
            entries.addFirst(Entry(sdk, call, detail))
            while (entries.size > 7) entries.removeLast()
        }
        main.post { onChange?.invoke() }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }
}
