package app.jesigan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private fun hospitalLots() = listOf(
        Lot("main", "병원 본관 주차장", 0.0, 0.0, 1200, 1150, walkMin = 6, extraDriveMin = 0),
        Lot("west", "서문 제2주차장", 0.0, 0.0, 300, 277, walkMin = 8, extraDriveMin = 2),
        Lot("public", "인근 공영주차장", 0.0, 0.0, 200, 159, walkMin = 10, extraDriveMin = 1),
    )

    private val history = mapOf("main" to 14, "west" to 3)

    @Test
    fun doorTimeAddsDriveParkWalk() {
        val ranked = TripEngine.rank(hospitalLots(), 34) { history[it.id] }
        val byId = ranked.associateBy { it.lot.id }
        assertEquals(54, byId.getValue("main").totalMin)   // 34 + 14 + 6  → 13:10 출발 시 2:04
        assertEquals(47, byId.getValue("west").totalMin)   // 36 + 3 + 8   → 1:57
        assertEquals(48, byId.getValue("public").totalMin) // 35 + 3 + 10  → 1:58
        assertEquals("west", ranked.first().lot.id)
    }

    @Test
    fun suggestsFasterLotOnlyWhenWorthIt() {
        val ranked = TripEngine.rank(hospitalLots(), 34) { history[it.id] }
        val main = ranked.first { it.lot.id == "main" }
        val best = ranked.first()
        assertTrue(TripEngine.shouldSuggest(main, best, emptySet(), 0))
        // 거절한 곳은 다시 제안하지 않음
        assertFalse(TripEngine.shouldSuggest(main, best, setOf("west"), 0))
        // 구간당 최대 2회
        assertFalse(TripEngine.shouldSuggest(main, best, emptySet(), 2))
    }

    @Test
    fun deterministicForSameInput() {
        val a = TripEngine.rank(hospitalLots(), 34) { history[it.id] }.map { it.lot.id }
        val b = TripEngine.rank(hospitalLots(), 34) { history[it.id] }.map { it.lot.id }
        assertEquals(a, b)
    }

    @Test
    fun nearlyFullLotOverridesOptimisticHistory() {
        val lot = Lot("west", "서문", 0.0, 0.0, 300, 296, walkMin = 8, extraDriveMin = 2)
        val p = TripEngine.plan(lot, 20, historyMin = 3)
        assertEquals(15, p.parkMin)
        assertEquals(ParkSource.LIVE, p.parkSource)
    }
}
