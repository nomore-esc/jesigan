package app.jesigan

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.LocalTime
import kotlin.concurrent.thread

/**
 * 제시간 — 문 앞 도착 시간.
 * 상태: 출발 → 주행 → (제안) → 주차장 도착·탐색 → 주차 완료 → (다음 방문) 반복 제안
 */
class MainActivity : AppCompatActivity(), PleosBridge.Listener {

    private enum class Mode { PLAN, DRIVING, SUGGEST, SEARCHING, PARKED, REPEAT }

    private lateinit var scenarios: List<Scenario>
    private var scenarioIdx = 0
    private lateinit var sc: Scenario
    private lateinit var lots: List<Lot>

    private lateinit var history: ParkingHistory
    private lateinit var bridge: PleosBridge
    private lateinit var voice: Voice

    private var mode = Mode.PLAN
    private var clock: LocalTime = LocalTime.of(13, 10)
    private var appointment: LocalTime = LocalTime.of(14, 0)
    private var remainMin = 34
    private var chosenId = ""
    private var pendingId: String? = null
    private val declined = mutableSetOf<String>()
    private var suggestions = 0
    private var inAreaSince: LocalTime? = null
    private var lastParkMin = 0
    private var gearLabel = "P"

    // views
    private lateinit var tvClock: TextView
    private lateinit var tvGear: TextView
    private lateinit var tvDest: TextView
    private lateinit var tvAppt: TextView
    private lateinit var lotList: LinearLayout
    private lateinit var tvSdk: TextView
    private lateinit var tvLabel: TextView
    private lateinit var tvBig: TextView
    private lateinit var tvTitle: TextView
    private lateinit var bar: View
    private lateinit var barDrive: View
    private lateinit var barPark: View
    private lateinit var barWalk: View
    private lateinit var tvLegend: TextView
    private lateinit var optCard: View
    private lateinit var tvOptTitle: TextView
    private lateinit var tvOptSub: TextView
    private lateinit var tvOptTime: TextView
    private lateinit var actions: View
    private lateinit var btnPrimary: TextView
    private lateinit var btnSecondary: TextView
    private lateinit var tvVoice: TextView

    private val green by lazy { ContextCompat.getColor(this, R.color.green) }
    private val orange by lazy { ContextCompat.getColor(this, R.color.orange) }
    private val red by lazy { ContextCompat.getColor(this, R.color.red) }
    private val fg by lazy { ContextCompat.getColor(this, R.color.fg) }
    private val sub by lazy { ContextCompat.getColor(this, R.color.sub) }
    private val blue by lazy { ContextCompat.getColor(this, R.color.blue) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        history = ParkingHistory(this)
        voice = Voice(this)
        scenarios = LotRepository.loadScenarios(this)
        SdkLog.onChange = { renderSdk() }
        buildDemoBar()
        bridge = PleosBridge(this, this)
        bridge.start()
        loadScenario(0)
    }

    override fun onDestroy() {
        SdkLog.onChange = null
        bridge.release()
        voice.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scenario

    private fun loadScenario(idx: Int) {
        scenarioIdx = idx
        sc = scenarios[idx]
        lots = sc.lots.map { it.copy() }
        clock = LocalTime.parse(sc.startTime)
        appointment = LocalTime.parse(sc.appointment)
        remainMin = sc.remainMin
        chosenId = sc.defaultLotId
        pendingId = null
        declined.clear()
        suggestions = 0
        inAreaSince = null
        seedHistoryOnce()
        mode = Mode.PLAN
        SdkLog.clear()
        SdkLog.add("NaviHelper", "getDestinationInfo", "${sc.destination} · 남은 ${remainMin}분 (시연값)")
        if (lots.any { it.seoulCode != null }) {
            thread {
                try {
                    LotRepository.refreshSeoulLive(lots)
                } catch (t: Throwable) {
                    SdkLog.add("서울 API", "GetParkingInfo 실패", t.message ?: t.javaClass.simpleName)
                }
                runOnUiThread { evaluatePlan(speak = false); render() }
            }
        }
        evaluatePlan(speak = true)
        render()
    }

    private fun seedHistoryOnce() {
        val prefs = getSharedPreferences("seed", MODE_PRIVATE)
        val flag = "seeded_${sc.id}"
        if (prefs.getBoolean(flag, false)) return
        sc.seedHistory.forEach { (lotId, mins) ->
            mins.forEach { history.record(sc.destination, sc.weekday, hourBucket(), lotId, it) }
        }
        prefs.edit().putBoolean(flag, true).apply()
    }

    private fun hourBucket() = LocalTime.parse(sc.startTime).hour

    // ---------------------------------------------------------------- logic

    private fun ranked(): List<Plan> =
        TripEngine.rank(lots, remainMin) { history.average(sc.destination, sc.weekday, hourBucket(), it.id) }

    private fun planOf(id: String): Plan? = ranked().firstOrNull { it.lot.id == id }

    private fun arrival(p: Plan): LocalTime = clock.plusMinutes(p.totalMin.toLong())

    /** 지금 상황에서 제안이 필요한지 판단해 pendingId 설정 */
    private fun evaluatePlan(speak: Boolean) {
        val r = ranked()
        val current = r.firstOrNull { it.lot.id == chosenId }
        val best = TripEngine.bestCandidate(r, declined + chosenId)
        pendingId = if (best != null && TripEngine.shouldSuggest(current, best, declined, suggestions)) best.lot.id else null
        if (speak && current != null) {
            val p = pendingId?.let { planOf(it) }
            val msg = if (p != null) {
                "지금 가면 ${ko(arrival(current))}, ${slackText(arrival(current))}. ${p.lot.name}으로 가면 ${ko(arrival(p))}이에요. 바꿀까요?"
            } else {
                "문 앞 도착 ${ko(arrival(current))}, ${slackText(arrival(current))}."
            }
            voice.say(msg)
        }
    }

    private fun accept() {
        val id = pendingId ?: return
        val replace = mode != Mode.PLAN || chosenId != sc.defaultLotId
        chosenId = id
        suggestions++
        pendingId = null
        planOf(id)?.let { bridge.setParkingWaypoint(it.lot, replace = replace) }
        mode = if (mode == Mode.SEARCHING) Mode.SEARCHING else Mode.DRIVING
        planOf(chosenId)?.let { voice.say("${it.lot.name}으로 안내할게요. 문 앞 도착 ${ko(arrival(it))}.") }
        render()
    }

    private fun decline() {
        pendingId?.let { declined += it }
        if (mode != Mode.PLAN) suggestions++
        pendingId = null
        if (mode == Mode.PLAN || mode == Mode.SUGGEST) mode = Mode.DRIVING
        planOf(chosenId)?.let { bridge.setParkingWaypoint(it.lot, replace = false) }
        render()
    }

    private fun drive(minutes: Int) {
        if (mode == Mode.PARKED || mode == Mode.REPEAT) return
        clock = clock.plusMinutes(minutes.toLong())
        if (mode == Mode.SEARCHING) {
            maybeFullLot()
            render(); return
        }
        remainMin = (remainMin - minutes).coerceAtLeast(0)
        if (remainMin <= 1) { arriveArea(); return }
        if (mode == Mode.PLAN) mode = Mode.DRIVING
        evaluatePlan(speak = false)
        if (pendingId != null && mode == Mode.DRIVING) {
            mode = Mode.SUGGEST
            val cur = planOf(chosenId)!!
            val p = planOf(pendingId!!)!!
            voice.say("${cur.lot.name}에 자리가 ${cur.lot.free}면밖에 없어요. ${p.lot.name}으로 바꾸면 ${ko(arrival(p))}이에요. 바꿀까요?")
        }
        render()
    }

    /** 주차장이 빠르게 차는 상황 (시연) */
    private fun fillChosenLot() {
        lots.firstOrNull { it.id == chosenId }?.let { it.occupied = it.total - 4 }
        SdkLog.add("서울 API", "GetParkingInfo", "${lots.first { it.id == chosenId }.name} 잔여 4면 (시연)")
        drive(0)
    }

    private fun arriveArea() {
        remainMin = 0
        inAreaSince = clock
        mode = Mode.SEARCHING
        pendingId = null
        SdkLog.add("NaviHelper", "onWayPointArrived", "${planOf(chosenId)?.lot?.name} · 반경 500m 진입 → 측정 시작")
        render()
    }

    /** 도착 후 2분이 지나도 기어 P가 아니면 만차로 보고 다음 후보 제안 */
    private fun maybeFullLot() {
        val since = inAreaSince ?: return
        if (Duration.between(since, clock).toMinutes() < 2) return
        lots.firstOrNull { it.id == chosenId }?.let { it.occupied = it.total }
        val best = TripEngine.bestCandidate(ranked(), declined + chosenId) ?: return
        if (suggestions >= TripEngine.MAX_SUGGESTIONS_PER_TRIP + 1) return
        pendingId = best.lot.id
        voice.say("여기 자리가 없는 것 같아요. ${best.lot.name}은 ${best.lot.free}면 남았어요. 갈까요?")
    }

    private fun parked() {
        if (mode == Mode.PARKED || mode == Mode.REPEAT || mode == Mode.PLAN) return
        val start = inAreaSince ?: clock.minusMinutes(1)
        lastParkMin = Duration.between(start, clock).toMinutes().toInt().coerceAtLeast(1)
        val before = history.average(sc.destination, sc.weekday, hourBucket(), chosenId)
        history.record(sc.destination, sc.weekday, hourBucket(), chosenId, lastParkMin)
        SdkLog.add("기록", "주차 소요시간 저장", "${planOf(chosenId)?.lot?.name} · ${lastParkMin}분 (이전 평균 ${before ?: "-"}분)")
        mode = Mode.PARKED
        pendingId = null
        val walk = lots.first { it.id == chosenId }.walkMin
        voice.say("주차 완료. 걸어서 ${walk}분, ${ko(clock.plusMinutes(walk.toLong()))}에 도착해요.")
        render()
    }

    private fun nextWeek() {
        val keepId = scenarioIdx
        loadScenario(keepId)
        clock = LocalTime.parse(sc.startTime).minusMinutes(5)
        val freq = history.frequentDestination(sc.weekday, hourBucket())
        val last = history.lastLot(sc.destination, sc.weekday, hourBucket())
        if (freq != null && last != null) {
            chosenId = last
            mode = Mode.REPEAT
            SdkLog.add("Vehicle", "initialize", "시동 · 반복 방문 확인 (${freq.first} ${freq.second}회)")
            planOf(last)?.let { voice.say("${sc.destination} 가시나요? 지금 출발하면 ${ko(arrival(it))}에 도착해요.") }
        }
        render()
    }

    // ---------------------------------------------------------------- SDK callbacks

    override fun onGear(parked: Boolean, label: String) {
        gearLabel = label
        if (parked) parked() else render()
    }

    override fun onDestination(name: String, lat: Double, lng: Double, remainMin: Int) {
        // 시연 시나리오와 같은 목적지면 실제 내비의 남은 시간을 사용
        if (name == sc.destination && remainMin > 0 && mode != Mode.PARKED) {
            this.remainMin = remainMin
            render()
        }
    }

    override fun onLocation(lat: Double, lng: Double) {
        if (mode != Mode.DRIVING && mode != Mode.SUGGEST) return
        if (TripEngine.distanceM(lat, lng, sc.destLat, sc.destLng) <= 500) arriveArea()
    }

    override fun onWaypointArrived(name: String) {
        if (mode == Mode.DRIVING || mode == Mode.SUGGEST) arriveArea()
    }

    override fun onDestinationArrived() {}

    // ---------------------------------------------------------------- render

    private fun render() {
        tvClock.text = hm(clock)
        tvGear.text = "기어 $gearLabel"
        tvGear.setTextColor(if (gearLabel == "P") green else fg)
        tvDest.text = sc.destination
        tvAppt.text = "약속 ${ko(appointment)} · ${sc.title}"
        renderLots()
        renderSdk()

        val cur = planOf(chosenId) ?: return
        val pend = pendingId?.let { planOf(it) }
        hideAll()
        when (mode) {
            Mode.PLAN -> {
                tvLabel.text = "${sc.destination} · ${ko(appointment)} 약속"
                val a = arrival(cur)
                tvTitle.text = "지금 가면 ${ko(a)},\n${slackText(a)}"
                tvTitle.setTextColor(slackColor(a))
                showBar(cur)
                if (pend != null) {
                    showOption("${pend.lot.name}으로 가면", TripEngine.reason(pend) + " · " + (if (pend.lot.live) "실시간 " else "") + "${pend.lot.free}면 남음", arrival(pend))
                    showActions("${pend.lot.name}으로 안내", "그대로 갈게요")
                } else {
                    showOption(cur.lot.name, TripEngine.reason(cur), a)
                    pendingId = null
                    showActions("안내 시작", null)
                }
                tvVoice.text = "\"응\"이라고 말해도 돼요  ·  음성 인식은 Pleos STT 연동 예정"
            }
            Mode.DRIVING -> {
                tvLabel.text = "문 앞 도착"
                val a = arrival(cur)
                tvBig.visibility = View.VISIBLE
                tvBig.text = hm(a)
                tvTitle.text = slackText(a)
                tvTitle.setTextColor(slackColor(a))
                tvLegend.visibility = View.VISIBLE
                tvLegend.text = "경유 ${cur.lot.name}  ·  남은 자리 ${cur.lot.free}면  ·  운전 ${cur.driveMin}분 + 주차 ${cur.parkMin}분 + 도보 ${cur.walkMin}분"
            }
            Mode.SUGGEST -> {
                tvLabel.text = "${cur.lot.name} · 남은 자리 ${cur.lot.free}면"
                tvTitle.text = if (pend != null) "자리가 거의 없어요.\n${pend.lot.name}으로 바꿀까요?" else "자리가 거의 없어요."
                tvTitle.setTextColor(fg)
                pend?.let {
                    showOption("${it.lot.name}으로 바꾸면", TripEngine.reason(it) + " · ${it.lot.free}면 남음", arrival(it))
                    showActions("바꿀게요", "아니요")
                }
                tvVoice.text = "듣고 있어요… \"응\" 또는 \"아니\""
            }
            Mode.SEARCHING -> {
                val since = inAreaSince ?: clock
                val m = Duration.between(since, clock).toMinutes()
                tvLabel.text = "${cur.lot.name} 도착 · 주차 시간 재는 중"
                tvBig.visibility = View.VISIBLE
                tvBig.text = "${m}분째"
                tvBig.setTextColor(if (m >= 2) orange else fg)
                tvTitle.text = "기어를 P에 두면 기록이 저장돼요"
                tvTitle.setTextColor(sub)
                if (pend != null) {
                    showOption("자리가 없는 것 같아요. ${pend.lot.name}으로", TripEngine.reason(pend), arrival(pend))
                    showActions("거기로 갈게요", "조금 더 찾을게요")
                }
            }
            Mode.PARKED -> {
                val walk = cur.walkMin
                val doorAt = clock.plusMinutes(walk.toLong())
                tvLabel.text = "${cur.lot.name} · ${ko(clock)} 주차"
                tvTitle.text = "걸어서 ${walk}분,\n${ko(doorAt)}에 도착해요"
                tvTitle.setTextColor(slackColor(doorAt))
                val avg = history.average(sc.destination, sc.weekday, hourBucket(), chosenId)
                val n = history.count(sc.destination, sc.weekday, hourBucket(), chosenId)
                showOption("이번 주차 시간", "이 주차장 평균 ${avg ?: lastParkMin}분 · ${n}회 기록", null, "${lastParkMin}분")
            }
            Mode.REPEAT -> {
                tvLabel.text = "${weekdayKo(sc.weekday)} ${ko(LocalTime.of(hourBucket(), 0))} · 자주 가는 곳"
                val a = arrival(cur)
                tvTitle.text = "${sc.destination} 가시나요?\n지금 출발하면 ${ko(a)}"
                tvTitle.setTextColor(fg)
                showOption("지난번처럼 ${cur.lot.name}으로", TripEngine.reason(cur), a)
                pendingId = null
                showActions("안내 시작", "다른 곳")
            }
        }
    }

    private fun hideAll() {
        tvBig.visibility = View.GONE
        tvBig.setTextColor(fg)
        bar.visibility = View.GONE
        tvLegend.visibility = View.GONE
        optCard.visibility = View.GONE
        actions.visibility = View.GONE
        btnSecondary.visibility = View.GONE
        tvVoice.text = ""
    }

    private fun showBar(p: Plan) {
        bar.visibility = View.VISIBLE
        (barDrive.layoutParams as LinearLayout.LayoutParams).weight = p.driveMin.toFloat().coerceAtLeast(0.5f)
        (barPark.layoutParams as LinearLayout.LayoutParams).weight = p.parkMin.toFloat().coerceAtLeast(0.5f)
        (barWalk.layoutParams as LinearLayout.LayoutParams).weight = p.walkMin.toFloat().coerceAtLeast(0.5f)
        bar.requestLayout()
        tvLegend.visibility = View.VISIBLE
        val src = when (p.parkSource) { ParkSource.HISTORY -> "내 기록"; ParkSource.LIVE -> "실시간 잔여 면수"; ParkSource.DEFAULT -> "예상" }
        tvLegend.text = "운전 ${p.driveMin}분   주차 ${p.parkMin}분 ($src)   도보 ${p.walkMin}분"
    }

    private fun showOption(title: String, subText: String, at: LocalTime?, right: String? = null) {
        optCard.visibility = View.VISIBLE
        tvOptTitle.text = title
        tvOptSub.text = subText
        tvOptTime.text = right ?: at?.let { hm(it) } ?: ""
        tvOptTime.setTextColor(at?.let { slackColor(it) } ?: green)
    }

    private fun showActions(primary: String, secondary: String?) {
        actions.visibility = View.VISIBLE
        btnPrimary.text = primary
        if (secondary != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondary
        }
    }

    private fun onPrimary() {
        when (mode) {
            Mode.PLAN -> if (pendingId != null) accept() else { mode = Mode.DRIVING; planOf(chosenId)?.let { bridge.setParkingWaypoint(it.lot, false) }; render() }
            Mode.SUGGEST, Mode.SEARCHING -> accept()
            Mode.REPEAT -> { mode = Mode.DRIVING; planOf(chosenId)?.let { bridge.setParkingWaypoint(it.lot, false) }; render() }
            else -> {}
        }
    }

    private fun onSecondary() {
        when (mode) {
            Mode.PLAN, Mode.SUGGEST -> decline()
            Mode.SEARCHING -> { pendingId?.let { declined += it }; pendingId = null; render() }
            Mode.REPEAT -> loadScenario(scenarioIdx)
            else -> {}
        }
    }

    private fun renderLots() {
        lotList.removeAllViews()
        val chosenPlanTotal = planOf(chosenId)
        ranked().forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 22, 28, 22)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card2)
                alpha = if (p.lot.id in declined) 0.45f else 1f
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
            val name = TextView(this).apply {
                val tag = when {
                    p.lot.id == chosenId -> "● "
                    else -> ""
                }
                text = tag + p.lot.name + (if (p.lot.live) "  실시간" else "")
                textSize = 26f
                setTextColor(if (p.lot.id == chosenId) blue else fg)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val info = TextView(this).apply {
                text = "${p.lot.free}면 · ${p.totalMin}분  "
                textSize = 22f
                setTextColor(sub)
            }
            val at = TextView(this).apply {
                text = hm(arrival(p))
                textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(slackColor(arrival(p)))
            }
            row.addView(name); row.addView(info); row.addView(at)
            lotList.addView(row, lp)
        }
        if (chosenPlanTotal == null) lotList.addView(TextView(this).apply { text = "후보 없음"; setTextColor(sub) })
    }

    private fun renderSdk() {
        if (!::tvSdk.isInitialized) return
        tvSdk.text = SdkLog.snapshot().joinToString("\n") { "[${it.sdk}] ${it.call}  ${it.detail}" }
    }

    // ---------------------------------------------------------------- demo bar

    private fun buildDemoBar() {
        val row = findViewById<LinearLayout>(R.id.demoButtons)
        val label = TextView(this).apply { text = "시연"; textSize = 22f; setTextColor(sub); setPadding(0, 0, 28, 0) }
        row.addView(label)
        fun add(text: String, action: () -> Unit) {
            val b = TextView(this).apply {
                this.text = text
                textSize = 22f
                setTextColor(fg)
                gravity = Gravity.CENTER
                setPadding(30, 18, 30, 18)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                setOnClickListener { action() }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 14 }
            row.addView(b, lp)
        }
        add("시나리오 바꾸기") { loadScenario((scenarioIdx + 1) % scenarios.size) }
        add("처음부터") { loadScenario(scenarioIdx) }
        add("+3분 주행") { drive(3) }
        add("주차장 차오름") { fillChosenLot() }
        add("주차장 도착") { arriveArea() }
        add("+1분") { drive(1) }
        add("기어 P") { SdkLog.add("Vehicle", "onSelected (시연)", "GEAR_PARK"); onGear(true, "P") }
        add("기어 D") { SdkLog.add("Vehicle", "onSelected (시연)", "GEAR_DRIVE"); onGear(false, "D") }
        add("다음 주 같은 시간") { nextWeek() }
        add("기록 초기화") {
            history.clear()
            getSharedPreferences("seed", MODE_PRIVATE).edit().clear().apply()
            loadScenario(scenarioIdx)
        }
        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.setOnClickListener { onSecondary() }
    }

    // ---------------------------------------------------------------- helpers

    private fun slackMin(at: LocalTime) = Duration.between(at, appointment).toMinutes()

    private fun slackText(at: LocalTime): String {
        val s = slackMin(at)
        return when {
            s >= 3 -> "${s}분 여유 있어요"
            s >= 0 -> "빠듯해요"
            else -> "${-s}분 늦어요"
        }
    }

    private fun slackColor(at: LocalTime): Int {
        val s = slackMin(at)
        return when {
            s >= 3 -> green
            s >= 0 -> orange
            else -> red
        }
    }

    private fun hm(t: LocalTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "%d:%02d".format(h, t.minute)
    }

    private fun ko(t: LocalTime): String {
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return if (t.minute == 0) "${h}시" else "${h}시 ${t.minute}분"
    }

    private fun weekdayKo(calendarDay: Int) = when (calendarDay) {
        1 -> "일요일"; 2 -> "월요일"; 3 -> "화요일"; 4 -> "수요일"; 5 -> "목요일"; 6 -> "금요일"; else -> "토요일"
    }

    private fun bindViews() {
        tvClock = findViewById(R.id.tvClock)
        tvGear = findViewById(R.id.tvGear)
        tvDest = findViewById(R.id.tvDest)
        tvAppt = findViewById(R.id.tvAppt)
        lotList = findViewById(R.id.lotList)
        tvSdk = findViewById(R.id.tvSdk)
        tvLabel = findViewById(R.id.tvLabel)
        tvBig = findViewById(R.id.tvBig)
        tvTitle = findViewById(R.id.tvTitle)
        bar = findViewById(R.id.bar)
        barDrive = findViewById(R.id.barDrive)
        barPark = findViewById(R.id.barPark)
        barWalk = findViewById(R.id.barWalk)
        tvLegend = findViewById(R.id.tvLegend)
        optCard = findViewById(R.id.optCard)
        tvOptTitle = findViewById(R.id.tvOptTitle)
        tvOptSub = findViewById(R.id.tvOptSub)
        tvOptTime = findViewById(R.id.tvOptTime)
        actions = findViewById(R.id.actions)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        tvVoice = findViewById(R.id.tvVoice)
        tvTitle.setTextColor(Color.WHITE)
    }
}
