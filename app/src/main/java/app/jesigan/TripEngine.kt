package app.jesigan

/**
 * 문 앞 도착 시간 계산 엔진. 안드로이드/SDK 의존 없음 → 단위 테스트 가능.
 * 같은 입력이면 항상 같은 결과(결정적)를 냅니다.
 */
data class Lot(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val total: Int,
    var occupied: Int,
    /** 주차장 → 목적지 문 앞까지 도보 시간(분) */
    val walkMin: Int,
    /** 목적지 대비 추가 운전 시간(분). 목적지보다 가까우면 음수 가능 */
    val extraDriveMin: Int,
    /** 서울 열린데이터 GetParkingInfo 주차장 코드 (실시간 데이터가 있는 경우) */
    val seoulCode: String? = null,
    var live: Boolean = false,
) {
    val free: Int get() = (total - occupied).coerceAtLeast(0)
}

enum class ParkSource { HISTORY, LIVE, DEFAULT }

data class Plan(
    val lot: Lot,
    val driveMin: Int,
    val parkMin: Int,
    val walkMin: Int,
    val parkSource: ParkSource,
) {
    val totalMin: Int get() = driveMin + parkMin + walkMin
}

object TripEngine {
    const val MAX_SUGGESTIONS_PER_TRIP = 2
    const val MIN_GAIN_MIN = 2
    const val LOW_FREE_SPACES = 5

    /** 내 기록이 없을 때, 잔여 면수 비율로 주차 소요시간을 추정 */
    fun estimateParkMin(lot: Lot): Int {
        if (lot.total <= 0) return 10
        val ratio = lot.free.toDouble() / lot.total
        return when {
            lot.free == 0 -> 20
            ratio >= 0.20 -> 3
            ratio >= 0.05 -> 8
            else -> 15
        }
    }

    fun plan(lot: Lot, remainDriveMin: Int, historyMin: Int?): Plan {
        val drive = (remainDriveMin + lot.extraDriveMin).coerceAtLeast(0)
        // 기록이 있어도 지금 만차에 가까우면 추정치가 더 크면 그 값을 씀 (보수적으로)
        val estimate = estimateParkMin(lot)
        val (park, src) = when {
            historyMin != null && estimate > historyMin && lot.free <= LOW_FREE_SPACES -> estimate to ParkSource.LIVE
            historyMin != null -> historyMin to ParkSource.HISTORY
            lot.live -> estimate to ParkSource.LIVE
            else -> estimate to ParkSource.DEFAULT
        }
        return Plan(lot, drive, park, lot.walkMin, src)
    }

    fun rank(lots: List<Lot>, remainDriveMin: Int, history: (Lot) -> Int?): List<Plan> =
        lots.map { plan(it, remainDriveMin, history(it)) }
            .sortedWith(compareBy<Plan>({ it.totalMin }, { it.walkMin }, { it.lot.id }))

    /** 지금 가는 주차장 대신 다른 곳을 제안해야 하는가 */
    fun shouldSuggest(current: Plan?, best: Plan, declined: Set<String>, suggestionsMade: Int): Boolean {
        if (suggestionsMade >= MAX_SUGGESTIONS_PER_TRIP) return false
        if (best.lot.id in declined) return false
        if (current == null) return true
        if (current.lot.id == best.lot.id) return false
        return current.totalMin - best.totalMin >= MIN_GAIN_MIN || current.lot.free <= LOW_FREE_SPACES
    }

    /** 첫 번째로 거절되지 않은 최선의 후보 */
    fun bestCandidate(ranked: List<Plan>, declined: Set<String>): Plan? = ranked.firstOrNull { it.lot.id !in declined }

    /** 추천 이유 한 문장 */
    fun reason(p: Plan): String {
        val park = when (p.parkSource) {
            ParkSource.HISTORY -> "평소 주차 ${p.parkMin}분"
            ParkSource.LIVE -> "지금 ${p.lot.free}면 남음"
            ParkSource.DEFAULT -> "예상 주차 ${p.parkMin}분"
        }
        return "$park · 도보 ${p.walkMin}분"
    }

    /** 두 지점 사이 거리(m) */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
