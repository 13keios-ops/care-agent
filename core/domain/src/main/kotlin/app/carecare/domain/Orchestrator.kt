package app.carecare.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 사용자 응답. 음성과 터치가 같은 통로로 들어온다 (UX-04). */
sealed interface UserReply {
    /** [typed]면 화면에서 글로 입력한 음성 대체 응답이다(마이크가 없는 시연 환경용) */
    data class Voice(val text: String, val confidence: Float?, val typed: Boolean = false) : UserReply
    /** 음성 입력을 대신하는 글 입력. 받는 즉시 음성과 같은 해석 경로로 넘긴다 */
    data class Text(val text: String) : UserReply
    /** 운전자가 글 입력을 시작함: 응답 대기를 품목 고르기만큼(45초) 다시 늘린다(입력 중 시간 초과로 버려지지 않게) */
    data object Typing : UserReply
    /** [value]: 같은 종류의 선택지가 여럿일 때(예: 지역 이름) 고른 값 */
    data class Choose(val option: OptionKind, val value: String? = null) : UserReply
    /** 화면에서 특정 장소의 "여기로 안내"를 누름 = 그 장소 하나에 대한 승인 (7.2) */
    data class PickPlace(val placeId: String) : UserReply
    data object NextPlace : UserReply
    data class Confirm(val yes: Boolean) : UserReply
    data class Items(val codes: List<ItemCode>) : UserReply
    data object Timeout : UserReply
}

enum class ConversationPhase { ASK_OPTIONS, PROPOSE_PLACE, ASK_ITEMS, CONFIRM_RECORD, RESULT }

data class ConversationView(
    val key: String,
    val situation: SituationKind,
    val message: String,
    val phase: ConversationPhase,
    val options: List<CareOption> = emptyList(),
    val places: List<PlaceDistance> = emptyList(),
    val proposedPlaceId: String? = null,
    val draftItems: List<ItemCode> = emptyList(),
    val draftDate: LocalDate? = null,
    val draftOdometerKm: Double? = null,
    val heard: String? = null,
    val speaking: Boolean = false,
    val listening: Boolean = false,
    val sourceLabel: String,
    /** 상황별 화면 데이터 */
    val visual: SituationVisual? = null,
    /** 질문 순번. 화면이 터치 응답에 붙여 보내, 지나간 질문의 버튼 터치를 가려낸다 */
    val seq: Int = 0,
)

data class PendingSituation(
    val key: String,
    val kind: SituationKind,
    val createdAt: Instant,
    val source: DataSource,
    val incidentId: String? = null,
    val visitId: String? = null,
    val reminderKey: String? = null,
    val waitForPark: Boolean = kind.waitForPark,
    val minParkEpoch: Int = 0,
    val deferCount: Int = 0,
    /** 자동으로 다시 묻지 않고, 사용자가 화면에서 열 때까지 기다린다 */
    val manualOnly: Boolean = false,
    /** 운전자가 먼저 말한 정비 내용(예: Gleo AI로 "엔진오일이랑 필터 기록해 줘"). 있으면 품목 질문을 건너뛴다 */
    val utterance: String? = null,
)

data class ActivityLine(val at: Instant, val text: String, val source: DataSource?)

data class AgentState(
    val runtime: RuntimeState = RuntimeState.STARTING,
    val gear: Gear = Gear.UNKNOWN,
    val odometerKm: Double? = null,
    val fuelPercent: Int? = null,
    val evSoc: Int? = null,
    /** 주행가능거리(km) */
    val rangeKm: Double? = null,
    /** 이동 맥락(목적지까지 거리 등). 시연 데이터일 수 있으며 basis에 근거가 있다 */
    val trip: TripContext? = null,
    val locationKnown: Boolean = false,
    /** 바퀴별 공기압 요약(대화가 없어도 차량 상태 요약에 씀) */
    val tires: SituationVisual.Tires = SituationVisual.Tires(emptyMap(), emptySet(), null),
    val conversation: ConversationView? = null,
    /** 동작 흐름(순서도 진행). 대화가 끝나도 다음 상황이 시작될 때까지 남긴다 */
    val flow: FlowTrace? = null,
    val pending: List<PendingSituation> = emptyList(),
    val incidents: List<Incident> = emptyList(),
    val records: List<MaintenanceRecord> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val intervals: List<ServiceInterval> = emptyList(),
    val handoffs: List<ServiceHandoffCard> = emptyList(),
    val visitOptIn: Boolean = false,
    val voiceEnabled: Boolean = true,
    val demoShortDwell: Boolean = false,
    val capabilities: List<CapabilitySnapshot> = emptyList(),
    val activity: List<ActivityLine> = emptyList(),
)

data class AgentConfig(
    /** 응답 대기 시간. 기획 초안 7.3의 8~12초는 데모 가설이며 터치 대기를 위해 조금 길게 둔다 */
    val replyTimeout: Duration = Duration.ofSeconds(15),
    /** 바퀴별 공기압: 권장 공기압 대비 이 비율 이하면 경고 수준 '낮음'. 미국 TPMS 기준(FMVSS 138, 25% 저하 경고)을 참고한 제품 기본값 */
    val tireLowRatio: Double = 0.75,
    /** 이 비율 이상으로 돌아오면 '낮음' 해제 (경계에서 깜빡이지 않게) */
    val tireClearRatio: Double = 0.85,
    /** 경고등 전 선제 안내: 권장 공기압 대비 이 비율 이하(예: 36psi → 31.7psi 이하)면 '관리 필요'. 제품 기본값 */
    val tireAdviseRatio: Double = 0.88,
    /** 이 비율 이상으로 돌아오면 '관리 필요' 해제 */
    val tireAdviseClearRatio: Double = 0.95,
    /** 화면에서 여러 항목을 고르는 단계(정비 품목)는 정차 중 터치가 여러 번 필요해 더 기다린다 */
    val pickTimeout: Duration = Duration.ofSeconds(45),
    val resultLinger: Duration = Duration.ofSeconds(5),
    /** 새 질문이 뜬 직후 이 시간 안의 화면 터치는 앞 질문에 누른 것(두 번 누름)으로 보고 받지 않는다 */
    val tapGuard: Duration = Duration.ofMillis(1_500),
    /** 정비소 예약: 고른 지역(지금 위치·집)에서 이 거리 안의 정비소만 추천한다 */
    val bookingAreaKm: Double = 15.0,
    val visit: VisitConfig = VisitConfig(),
    /** 시연 모드 체류 기준. 화면에 시연 설정임을 표시한다 */
    val demoVisit: VisitConfig = VisitConfig(minDwell = Duration.ofMinutes(1)),
    val visitRadiusMeters: Double = 120.0,
    /**
     * 잔량 알림 기준(%). 차량 경고등과 별개로 이 제품이 정한 안내 기준이며 안전 임계값이 아니다.
     * 기준 이하로 내려가면 한 번 알리고, 기준+5% 이상으로 오르면 해제로 본다.
     */
    val lowEnergyPercent: Int = 15,
    /** 갈 수 있다고 볼 여유: 주행가능거리 ≥ 거리 × 이 값 (제품 기본값, 안전 임계값 아님) */
    val reachMargin: Double = 1.2,
    val zone: ZoneId = ZoneId.systemDefault(),
    val maxActivity: Int = 40,
)

/**
 * 케어 에이전트의 중심 루프 (명세 8.2 DialogueOrchestrator + FollowUpScheduler).
 *
 * 상태 감지 → 차가 먼저 설명 → 해결 가이드·대안 제시 → 사용자 승인 → 해결 액션 → 기록·후속 관리.
 *
 * 스레드 규칙: 모든 상태 변경은 [scope] 안에서만 일어난다. [scope]는 단일 스레드 디스패처여야 한다.
 * 외부에서 부르는 함수는 [scope]로 작업을 넘긴다.
 */
class CareOrchestrator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val vehicle: VehicleProfile,
    private val places: PlaceCatalog,
    private val speechOut: SpeechOutput,
    private val speechIn: SpeechInput,
    navigation: NavigationGateway,
    private val store: CareStore? = null,
    private val config: AgentConfig = AgentConfig(),
    private val rules: RuleEngine = RuleEngine(RuleEngine.defaultBundle()),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    /** 찾아가는 충전 접수. 기본은 시연용 모의 응답 */
    private val mobileCharge: MobileChargeService = DemoMobileChargeService(clock),
    /** 정비소 예약 접수. 기본은 시연용 모의 응답 */
    private val booking: ServiceBookingService = DemoServiceBookingService(),
) {
    private val normalizer = EventNormalizer()
    private val incidents = IncidentEngine(idGen = idGen)
    private val gate = ApprovalGate(clock, idGen = idGen)
    private val ledger = MaintenanceLedger(gate, clock, idGen)
    private val visits = MaintenanceVisitDetector(config.visit, idGen)
    private val proximity = PlaceProximityTracker(places, config.visitRadiusMeters)
    private val executor = ActionExecutor(gate, navigation)

    private val intervals = mutableListOf<ServiceInterval>()
    private val handoffs = mutableListOf<ServiceHandoffCard>()
    private val pending = linkedMapOf<String, PendingSituation>()
    private val promptedReminders = mutableSetOf<String>()
    private val activity = ArrayDeque<ActivityLine>()

    private var gear = Gear.UNKNOWN
    private var parkEpoch = 0
    private var odometerKm: Double? = null
    private var fuelPercent: Int? = null
    private var evSoc: Int? = null
    private var rangeKm: Double? = null
    /** 처음 받는 0.0은 에뮬레이터·차량 초기값일 수 있어 0이 아닌 값을 한 번 받기 전까지 미확인으로 본다 */
    private var rangeSeenNonZero = false
    private var trip: TripContext? = null
    private var tripFromCommute = false
    private var commute: CommutePattern? = null
    private var commuteNow: Instant? = null
    private var rangeShort = false
    /** 지금 켜져 있는 '목적지까지 부족' 신호의 영역(TRIP:<목적지>) */
    private var rangeShortArea: String? = null
    private var lastFix: LocationFix? = null
    private var runtime = RuntimeState.STARTING
    private var capabilities = emptyList<CapabilitySnapshot>()
    private var voiceEnabled = true
    private var demoShortDwell = false
    private var conversation: ConversationView? = null
    @Volatile private var askSeq = 0
    @Volatile private var askShownAt: Instant = Instant.EPOCH
    /** 지금 대화의 상황별 화면 데이터 */
    private var visual: SituationVisual? = null
    /** 동작 흐름 진행 기록 */
    private var flow: FlowTrace? = null
    /** 퇴근길로 추정한 시각(흐름 창 근거 표시용) */
    private var commuteAt: java.time.ZonedDateTime? = null
    private val tireKpa = linkedMapOf<String, Double>()
    /** 공기압 기준값(kPa): 차량 권장 공기압 */
    private val tireReference: Double = TireUnits.kpa(vehicle.recommendedTirePsi)
    private val tireLow = linkedSetOf<String>()
    private var tireLevel = 0
    /** 경고 수준은 아니지만 권장보다 낮아 관리가 필요한 바퀴 */
    private val tireAdvise = linkedSetOf<String>()
    private var tireAdviseOn = false

    private val replies = Channel<UserReply>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private var loopJob: Job? = null

    // ---------------------------------------------------------------- 외부 입력

    fun start(vehicleSignals: Flow<RawVehicleSignal>? = null, locationFixes: Flow<LocationFix>? = null) {
        check(loopJob == null) { "이미 시작함" }
        loopJob = scope.launch {
            restore()
            publish()
            launch { conversationLoop() }
            vehicleSignals?.let { f -> launch { f.collect { handleSignal(it) } } }
            locationFixes?.let { f -> launch { f.collect { handleFix(it) } } }
        }
    }

    fun submit(raw: RawVehicleSignal) { scope.launch { handleSignal(raw) } }
    /** 함께 바뀐 값(예: 두 바퀴 공기압)을 한 번에 넣는다. 사이에 대화가 시작돼 일부 값만 말하는 일이 없다 */
    fun submitAll(raws: List<RawVehicleSignal>) { scope.launch { raws.forEach { handleSignal(it) } } }
    fun submitFix(fix: LocationFix) { scope.launch { handleFix(fix) } }

    /**
     * 응답. 현재 대화가 받는다. 화면 터치는 그 버튼을 그린 질문의 순번 [seq]를 함께 보낸다.
     * 지나간 질문의 버튼이거나 새 질문이 뜬 직후(두 번 누름)의 터치는 버린다. 앞 질문에 누른 터치가 다음 질문의 승인이 되면 안 된다
     */
    fun reply(r: UserReply, seq: Int? = null) {
        if (seq != null && (seq != askSeq || Duration.between(askShownAt, clock.now()) < config.tapGuard)) return
        replies.trySend(r)
    }

    /** "화면에서 다시 열기" */
    fun reopen(key: String) = scope.launch {
        val p = pending[key] ?: return@launch
        pending[key] = p.copy(manualOnly = false, waitForPark = false, minParkEpoch = 0)
        wake.trySend(Unit); publish()
    }

    /** 사건 목록에서 사용자가 다시 도움을 요청함 */
    fun requestHelp(incidentId: String) = scope.launch {
        val inc = incidents.get(incidentId) ?: return@launch
        val kind = inc.decision.situation ?: return@launch
        if (inc.isClosed) return@launch
        val key = "inc:${inc.id}"
        pending[key] = PendingSituation(key, kind, clock.now(), inc.source, incidentId = inc.id, waitForPark = false)
        wake.trySend(Unit); publish()
    }

    /** 방문 감지 없이 사용자가 직접 정비를 기록함 (9.3: 집에서 교환한 경우 등) */
    fun recordManually(utterance: String? = null) = scope.launch {
        val key = "manual:${idGen()}"
        // 주행 중(음성 비서로 부탁한 경우 등)에는 정차한 뒤에 묻는다 (FR-015)
        val driving = gear != Gear.PARK
        pending[key] = PendingSituation(key, SituationKind.SERVICE_VISIT, clock.now(), DataSource.MANUAL_INPUT, waitForPark = driving,
            minParkEpoch = if (driving) parkEpoch + 1 else 0, utterance = utterance?.trim()?.ifEmpty { null })
        wake.trySend(Unit); publish()
    }

    fun closeIncident(id: String) = scope.launch { moveIncident(id, IncidentStatus.CLOSED_BY_USER); pending.remove("inc:$id"); pending.remove("follow:$id"); publish() }

    fun setVisitOptIn(on: Boolean) = scope.launch {
        visits.setOptIn(on)
        if (!on) pending.values.removeIf { it.kind == SituationKind.SERVICE_VISIT && it.visitId != null }
        log(if (on) "정비 방문 감지 켬 (사용자 동의)" else "정비 방문 감지 끔: 위치 후보 폐기", DataSource.MANUAL_INPUT)
        persist(); publish()
    }

    /** 사용자 지정 교환 주기 (USER_DEFINED). 앱이 임의 수치를 넣지 않는다 (22-4) */
    fun setInterval(code: ItemCode, km: Double?, days: Long?) = scope.launch {
        intervals.removeAll { it.itemCode == code }
        if (km != null || days != null) intervals += ServiceInterval(code, Powertrain.entries.toSet(), km, days, ScheduleSource.USER_DEFINED, null)
        persist(); checkReminders(DataSource.MANUAL_INPUT); publish()
    }

    /** 운전자가 음성 비서(Gleo AI)로 물었을 때 앱이 직접 답한다 */
    fun announce(text: String, logText: String) = scope.launch {
        // 진행 중인 안내(특히 안전 안내)와 겹쳐 말하지 않는다. 그때는 음성 비서가 대신 답한다
        if (conversation != null || active != null) { log("$logText (안내 중이라 앱은 말하지 않음)", DataSource.MANUAL_INPUT); publish(); return@launch }
        log(logText, DataSource.MANUAL_INPUT); publish(); say(text)
    }

    fun setVoiceEnabled(on: Boolean) = scope.launch { voiceEnabled = on; persist(); publish() }

    /** 설정 화면의 '음성 안내 시험'. 음성 출력이 되는지 사용자가 확인한다 */
    fun testVoice() = scope.launch {
        val ok = say("음성 안내를 시험합니다. 이 소리가 들리면 정상이에요.")
        log(if (ok) "음성 안내 시험: 출력 확인" else "음성 안내 시험: 출력 확인 못함", null); publish()
    }

    fun setDemoShortDwell(on: Boolean) = scope.launch {
        demoShortDwell = on
        visits.updateConfig(if (on) config.demoVisit else config.visit)
        log(if (on) "체험 설정: 정비소 체류 기준 ${config.demoVisit.minDwell.toMinutes()}분" else "체류 기준 기본값 복귀", DataSource.MANUAL_INPUT)
        persist(); publish()
    }

    fun deleteRecord(id: String) = scope.launch { ledger.delete(id); persist(); publish() }

    fun setRuntime(state: RuntimeState, caps: List<CapabilitySnapshot>) = scope.launch { runtime = state; capabilities = caps; publish() }

    /** 이동 맥락(목적지까지 거리, 고속도로 여부). null이면 목적지 없음 */
    fun setTrip(t: TripContext?) = scope.launch {
        trip = t; tripFromCommute = false
        if (t == null) inferTrip()
        evaluateRange(t?.source ?: DataSource.MANUAL_INPUT); publish()
    }

    /**
     * 평소 귀가 패턴. 내비 목적지(이동 맥락)가 없고 평일 퇴근 시간대면 집으로 가는 길로 본다.
     * [assumedNow]는 시연 시각이다(화면에 시연 시각으로 표시). null이면 지금 시각
     */
    fun setCommute(pattern: CommutePattern?, assumedNow: Instant? = null) = scope.launch {
        commute = pattern; commuteNow = assumedNow
        inferTrip()
        evaluateRange(pattern?.source ?: DataSource.MANUAL_INPUT); publish()
    }

    private fun inferTrip() {
        if (trip != null && !tripFromCommute) return   // 내비 목적지가 우선
        val c = commute
        val now = (commuteNow ?: clock.now()).atZone(config.zone)
        val t = now.toLocalTime()
        val inWindow = c != null && now.dayOfWeek in c.weekdays && !t.isBefore(c.from) && t.isBefore(c.to)
        commuteAt = if (inWindow) now else null
        trip = if (c != null && inWindow) TripContext(c.homeName, c.homeKm, highway = false, source = c.source, basis = CommutePattern.BASIS, destination = c.destination) else null
        tripFromCommute = trip != null
        if (c != null && inWindow) log("퇴근 시간대(${now.dayOfWeek.name.take(3)} ${t.withSecond(0).withNano(0)}${if (commuteNow != null) ", 체험용 시각" else ""}) → ${c.homeName}으로 가는 길로 추정", c.source)
    }

    /** 목적지까지 갈 수 있는지: 주행가능거리 < 거리 × 여유면 켜짐, 거리 × 1.3 이상이면 꺼짐(경계에서 깜빡이지 않게) */
    private fun evaluateRange(source: DataSource) {
        val t = trip
        val r = rangeKm?.takeIf { rangeSeenNonZero }
        val area = t?.let { "${RuleEngine.RANGE_TRIP}:${it.destinationName}" }
        // 목적지가 바뀌면 이전 목적지 판단을 끄고 새로 판단한다(같은 경고의 반복으로 묻히지 않게)
        if (rangeShort && area != rangeShortArea) emitRange(false, rangeShortArea, null, source)
        val next = when {
            t == null || r == null -> false
            rangeShort -> r < t.remainingKm * 1.3
            else -> r < t.remainingKm * config.reachMargin
        }
        if (next == rangeShort) return
        if (next && t != null && r != null) log("${t.destinationName}까지 ${"%.0f".format(t.remainingKm)}km, 주행가능거리 ${"%.0f".format(r)}km → ${if (r < t.remainingKm) "부족" else "빠듯"}", source)
        emitRange(next, if (next) area else rangeShortArea, r, source)
    }

    private fun emitRange(on: Boolean, area: String?, r: Double?, source: DataSource) {
        rangeShort = on
        rangeShortArea = if (on) area else null
        area ?: return
        val now = clock.now()
        onTelltale(RawVehicleSignal(SignalType.RANGE_REMAINING, source, now, now, area = area, value = if (on) r else null, unit = "km"), now)
    }

    // ---------------------------------------------------------------- 신호 처리

    private fun handleSignal(raw: RawVehicleSignal) {
        when (raw.type) {
            SignalType.GEAR -> onGear(raw.gear ?: Gear.UNKNOWN, raw.source)
            SignalType.ODOMETER -> { odometerKm = raw.value; checkReminders(raw.source) }
            // 지원하지 않는 잔량 값(예: 전기차 에뮬레이터의 연료 0%)은 받지 않는다
            SignalType.FUEL_PERCENT -> if (raw.supported == true) { fuelPercent = raw.value?.toInt(); onLevel(raw, TelltaleKind.LOW_FUEL, fuelPercent) }
            SignalType.EV_SOC -> if (raw.supported == true) { evSoc = raw.value?.toInt(); onLevel(raw, TelltaleKind.LOW_EV_BATTERY, evSoc) }
            SignalType.RANGE_REMAINING -> {
                val v = raw.value
                if (v != null && v > 0) rangeSeenNonZero = true
                if (rangeSeenNonZero) rangeKm = v
                evaluateRange(raw.source)
            }
            SignalType.TELLTALE -> onTelltale(raw, clock.now())
            SignalType.TIRE_PRESSURE -> onTirePressure(raw)
            else -> {}
        }
        publish()
    }

    private fun onTelltale(raw: RawVehicleSignal, now: Instant) {
        if (!rules.covers(raw, vehicle)) return   // 방향지시등처럼 다룰 규칙이 없는 경고등은 사건으로 만들지 않는다
        val n = normalizer.normalize(raw, now)
        val d = rules.evaluate(n, vehicle)
        when (val ev = incidents.onSignal(n, d, now)) {
            is IncidentEvent.Created -> onActivated(ev.incident, d.situation ?: return, raw, now)
            is IncidentEvent.SensorCleared -> {
                val inc = ev.incident
                log("${inc.decision.situation?.label ?: "경고"} 경고등 꺼짐 (센서 해제만 기록, 해결 확정 아님)", raw.source)
                pending.remove("inc:${inc.id}")
                // 조치 확인은 '점검 필요' 규칙의 사건 중 사용자가 도움을 받은 것에만 묻는다 (상황 종류가 아니라 규칙이 정한다).
                // 관련 사건(같은 타이어 경고 묶음)이 아직 켜져 있거나 이미 묻고 있으면 한 번만 묻는다
                val related = relatedOpen(inc)
                val stillActive = related.any { !it.sensorCleared }
                val relatedIds = related.map { it.id }.toSet()
                val alreadyAsking = pending.values.any { it.kind == SituationKind.ISSUE_FOLLOW_UP && it.incidentId in relatedIds } ||
                    conversation?.key?.removePrefix("follow:") in relatedIds
                if (inc.decision.needsInspection() && inc.status != IncidentStatus.OPEN && !inc.userActionCompleted && !stillActive && !alreadyAsking)
                    enqueue(PendingSituation("follow:${inc.id}", SituationKind.ISSUE_FOLLOW_UP, now, raw.source, incidentId = inc.id))
                persist()
            }
            is IncidentEvent.Debounced -> {
                pending.remove("follow:${ev.incident.id}")
                // 안내하기 전에 꺼졌다가 다시 켜진 경고는 아직 아무도 알리지 않았으므로 새로 켜진 것처럼 다룬다
                val inc = ev.incident
                val unannounced = inc.status == IncidentStatus.OPEN && "inc:${inc.id}" !in pending && conversation?.key != "inc:${inc.id}"
                if (unannounced) onActivated(inc, inc.decision.situation ?: return, raw, now)
            }
            is IncidentEvent.Ignored -> {}
        }
    }

    /** 사건이 새로 켜졌을 때: 더 큰 안내에 합치거나, 안내 대기열에 넣는다 */
    private fun onActivated(incident: Incident, kind: SituationKind, raw: RawVehicleSignal, now: Instant) {
        log("${kind.label} 감지 (${raw.telltale?.let { "$it ${raw.telltaleState}" } ?: "${raw.type} ${raw.area}"})", raw.source)
        // 같은 상황(또는 그것을 덮는 더 큰 상황)이 이미 열려 있으면 다시 묻지 않는다. 경고등과 공기압 값이 함께 와도 한 번만 묻는다
        val covering = incidents.all().firstOrNull {
            it.id != incident.id && !it.isClosed && !it.sensorCleared &&
                (it.decision.situation == kind || it.decision.situation?.covers(kind) == true)
        }
        if (covering != null) { absorb(incident.id, covering); persist(); return }
        // 아직 묻지 않았거나 답이 없어 남겨 둔 작은 안내(예: 한 바퀴 저압, 배터리 부족)는 이 안내에 합친다.
        // 지금 묻고 있는 대화는 pending에 없으므로 끊기지 않는다(답이 없어 결과를 보여 주는 중인 것은 pending에 다시 들어와 있다)
        pending.values.filter { it.incidentId != null && kind.covers(it.kind) }.toList()
            .forEach { pending.remove(it.key); absorb(it.incidentId!!, incident) }
        enqueue(PendingSituation("inc:${incident.id}", kind, now, raw.source, incidentId = incident.id))
        persist()
    }

    /**
     * 바퀴별 공기압을 두 단계로 요약해 규칙 신호로 바꾼다.
     * 1) 관리 필요(LOW_ADVISE): 권장 공기압의 88% 이하, 경고등이 켜지기 전에 먼저 안내
     * 2) 경고 수준(LOW_ONE, LOW_MULTI): 권장 공기압의 75% 이하 (SC-A, SC-B)
     */
    private fun onTirePressure(raw: RawVehicleSignal) {
        val wheel = raw.area ?: return
        val kpa = raw.value ?: return
        tireKpa[wheel] = kpa
        val ref = tireReference
        if (wheel in tireLow) { if (kpa >= ref * config.tireClearRatio) tireLow -= wheel }
        else if (kpa <= ref * config.tireLowRatio) tireLow += wheel
        if (wheel in tireAdvise) { if (kpa >= ref * config.tireAdviseClearRatio) tireAdvise -= wheel }
        else if (kpa <= ref * config.tireAdviseRatio) tireAdvise += wheel
        fun emit(area: String, on: Boolean, n: Int) = onTelltale(RawVehicleSignal(SignalType.TIRE_PRESSURE, raw.source, raw.receivedAt, raw.sourceAt,
            area = area, value = if (on) n.toDouble() else null, unit = "wheels"), clock.now())
        val recText = "권장 ${TireUnits.psiText(ref)}"
        // 선제 안내: 경고 수준인 바퀴가 없을 때만 새로 켠다. 모든 바퀴가 회복되면 끈다
        val adviseOn = tireAdvise.isNotEmpty()
        if (adviseOn && !tireAdviseOn && tireLow.isEmpty()) {
            tireAdviseOn = true
            log("타이어 공기압 관리 필요: ${currentAdviseTires().joinToString { "${Playbooks.wheelName(it.wheel)} ${TireUnits.psiText(it.kpa)}" }} (${recText}의 ${(config.tireAdviseRatio * 100).toInt()}% 이하)", raw.source)
            emit(RuleEngine.TIRE_LOW_ADVISE, true, tireAdvise.size)
        } else if (!adviseOn && tireAdviseOn) {
            tireAdviseOn = false
            emit(RuleEngine.TIRE_LOW_ADVISE, false, 0)
        }
        val level = minOf(tireLow.size, 2)
        if (level == tireLevel) { publish(); return }
        if (level > tireLevel) log("타이어 공기압 낮음: ${currentLowTires().joinToString { "${Playbooks.wheelName(it.wheel)} ${TireUnits.psiText(it.kpa)}" }} (${recText}의 ${(config.tireLowRatio * 100).toInt()}% 이하)", raw.source)
        else log("타이어 공기압 회복: 낮은 바퀴 ${tireLow.size}개", raw.source)
        val prev = tireLevel
        tireLevel = level
        if (prev == 0 && level >= 1) emit(RuleEngine.TIRE_LOW_ONE, true, tireLow.size)
        if (prev < 2 && level == 2) emit(RuleEngine.TIRE_LOW_MULTI, true, tireLow.size)
        if (prev == 2 && level < 2) emit(RuleEngine.TIRE_LOW_MULTI, false, 0)
        if (prev >= 1 && level == 0) emit(RuleEngine.TIRE_LOW_ONE, false, 0)
    }

    private fun tiresVisual() = SituationVisual.Tires(tireKpa.toMap(), tireLow.toSet(), tireReference, tireAdvise.toSet() - tireLow)

    /** 대화 중에도 잔량·주행가능거리·공기압은 최신 값으로 보여 준다(신호가 대화 시작 뒤에 들어올 수 있음) */
    private fun liveVisual(): SituationVisual? = when (val v = visual) {
        is SituationVisual.Energy -> v.copy(percent = (if (v.ev) evSoc else fuelPercent) ?: v.percent, rangeKm = rangeKm ?: v.rangeKm)
        is SituationVisual.Tires -> tiresVisual()
        else -> v
    }

    // ---------------------------------------------------------------- 동작 흐름

    private fun flowStart(s: FlowScenario, path: List<String>, facts: List<String>) {
        flow = FlowTrace(s, path, path.last(), facts); publish()
    }

    /** 지금 장의 순서도에 있는 노드만 진행시킨다(다른 장의 노드 이름은 무시) */
    private fun flowTo(vararg ids: String, fact: String? = null) {
        val f = flow ?: return
        val known = CareFlows.of(f.scenario).map { it.id }.toSet()
        val add = ids.filter { it in known }
        if (add.isEmpty() && fact == null) return
        flow = f.copy(visited = f.visited + add, current = add.lastOrNull() ?: f.current, facts = f.facts + listOfNotNull(fact)); publish()
    }

    private fun km0(v: Double) = "%,.0f".format(v)

    /** 상황을 묻기 전에 감지·판단 단계를 근거와 함께 남긴다 */
    private fun flowForHelp(kind: SituationKind, parked: Boolean, usual: PlaceDistance?, usualReachable: Boolean?, mobileAt: PlaceDistance?, due: Reminder?) {
        when (kind) {
            SituationKind.TIRE_PRESSURE_LOW -> {
                val ref = tireReference
                val facts = currentAdviseTires().map { "${Playbooks.wheelName(it.wheel)} ${TireUnits.psiText(it.kpa)} · 권장 ${TireUnits.psiText(ref)}의 ${(it.kpa / ref * 100).toInt()}%" } +
                    listOf("→ 권장의 ${(config.tireAdviseRatio * 100).toInt()}% 이하 · 경고등 전")
                flowStart(FlowScenario.TIRE, listOf("sense", "judge", "advise"), facts)
            }
            SituationKind.TIRE_WARNING, SituationKind.TIRE_MULTI_LOW -> {
                val lows = currentLowTires()
                val ref = tireReference
                val facts = lows.map { "${Playbooks.wheelName(it.wheel)} ${TireUnits.psiText(it.kpa)} · 권장 ${TireUnits.psiText(ref)}의 ${(it.kpa / ref * 100).toInt()}%" } +
                    (if (lows.isNotEmpty()) listOf("→ 권장의 75% 이하 · 경고 수준") else listOf("TPMS 경고등 켜짐"))
                if (kind == SituationKind.TIRE_MULTI_LOW) {
                    val f = flow
                    if (parked && f?.scenario == FlowScenario.TIRE && "stop" in f.visited && "park" !in f.visited) flowTo("park", fact = "정차 확인")
                    else flowStart(FlowScenario.TIRE, listOf("sense", "judge", "multi", if (parked) "park" else "stop"), facts)
                } else flowStart(FlowScenario.TIRE, listOf("sense", "judge", "one"), facts)
            }
            SituationKind.LOW_FUEL -> {
                val facts = mutableListOf("연료 ${fuelPercent ?: "?"}% ≤ 안내 기준 ${config.lowEnergyPercent}%")
                val path = mutableListOf("sense", "judge")
                val d = usual?.distanceKm
                if (usual != null && d != null && rangeKm != null && usualReachable != null) {
                    path += "usual"; path += if (usualReachable) "enough" else "short"
                    facts += "평소 ${usual.place.name} ${km0(d)}km × 1.2 = ${km0(d * config.reachMargin)}km ${if (usualReachable) "≤" else ">"} 주행가능거리 ${km0(rangeKm!!)}km → ${if (usualReachable) "충분" else "부족"}"
                } else path += "short"
                flowStart(FlowScenario.FUEL, path, facts)
            }
            SituationKind.LOW_EV_BATTERY -> flowStart(FlowScenario.LOW_BATTERY, listOf("sense", "judge"), listOf("배터리 ${evSoc ?: "?"}% ≤ 안내 기준 ${config.lowEnergyPercent}%"))
            SituationKind.RANGE_SHORT -> {
                val t = trip ?: return
                val r = rangeKm ?: return
                val verdict = if (r < t.remainingKm) "short" else "tight"
                val gap = "${t.destinationName} ${km0(t.remainingKm)}km · 주행가능거리 ${km0(r)}km → ${if (verdict == "short") "부족" else "빠듯"}"
                if (mobileAt != null) {
                    flowStart(FlowScenario.MOBILE_CHARGE, listOf("compare", "eligible", "rest"), listOf(gap,
                        "전기차 · 고속도로 · 잔여 ${km0(r)}km ≤ ${km0(MobileChargeTerms.MAX_RANGE_KM)}km",
                        "${mobileAt.place.name} ${mobileAt.distanceKm?.let { km0(it) } ?: "?"}km → 갈 수 있음"))
                } else if (t.basis == CommutePattern.BASIS) {
                    val at = commuteAt
                    val day = at?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN) ?: ""
                    val hm = at?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""
                    flowStart(FlowScenario.CHARGE_VIA, listOf("clock", "commute", "home", "compare", verdict),
                        listOf("$day $hm${if (commuteNow != null) "(체험용 시각)" else ""} → 퇴근 시간대 · 내비 목적지 없음", "평소 귀가 패턴 → ${t.destinationName}", gap))
                } else flowStart(FlowScenario.CHARGE_VIA, listOf("dest", "compare", verdict), listOf("목적지 ${t.destinationName}" + (t.basis.ifEmpty { null }?.let { " · $it" } ?: ""), gap))
            }
            SituationKind.MAINTENANCE_DUE -> {
                val last = due?.let { d -> ledger.records().firstOrNull { it.id == d.recordId }?.odometerAtServiceKm }
                val soon = due?.status == ReminderStatus.DUE_SOON
                val dueKm = due?.dueOdometerKm
                val facts = listOfNotNull(
                    if (last != null && dueKm != null) "지난 교환 ${km0(last)}km + 주기 ${km0(dueKm - last)}km(${if (due.scheduleSource == ScheduleSource.OEM_DOCUMENT) "제조사 권장" else "내 설정"}) = ${km0(dueKm)}km" else null,
                    odometerKm?.let { o ->
                        if (soon && dueKm != null) "지금 ${km0(o)}km → 교환까지 ${km0(dueKm - o)}km · 미리 알림(1,000km 전)"
                        else "지금 ${km0(o)}km → 교환 시기 도래"
                    },
                )
                flowStart(FlowScenario.MAINTENANCE_DUE, listOf("odo", "interval", "due", "wait"), facts)
            }
            else -> {}
        }
    }

    /**
     * 시연 데이터: 정비 기록이 하나도 없을 때만 지금 주행거리로 지난 교환 기록 1건을 넣는다(상황 4를 단독으로 시연하기 위함).
     * 승인 관문을 거치지 않는 시연 전용 함수이며 출처를 시뮬레이션으로 남긴다
     */
    fun seedDemoRecord(items: List<ItemCode>, odometerKm: Double? = null) = scope.launch {
        if (ledger.records().isNotEmpty() || items.isEmpty()) return@launch
        // 차량 주행거리가 아직 들어오지 않았으면(시연 직후 지연) 넘겨받은 값을 쓴다
        val odo = (odometerKm ?: this@CareOrchestrator.odometerKm)?.takeIf { it > 0 }
        ledger.restore(listOf(MaintenanceRecord("demo-${idGen()}", items.map { ItemCandidate(it, null, "체험용 데이터") }, today(), odo,
            if (odo != null) ValueSource.VEHICLE_SDK else ValueSource.UNKNOWN, DataSource.SIMULATED_REPLAY, clock.now(), "demo-seed", null, null)))
        log("체험용 데이터: 지난 교환 기록 추가 (${items.joinToString { it.label }})", DataSource.SIMULATED_REPLAY)
        persist(); checkReminders(DataSource.SIMULATED_REPLAY); publish()
    }

    private fun currentLowTires(): List<TireReading> = readings(tireLow)

    /** 관리가 필요한(경고 전) 바퀴. 경고 수준 바퀴도 함께 낮으면 포함한다 */
    private fun currentAdviseTires(): List<TireReading> = readings(tireAdvise + tireLow)

    private fun readings(wheels: Set<String>): List<TireReading> {
        val ref = tireReference
        return wheels.sortedBy { WHEEL_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
            .mapNotNull { w -> tireKpa[w]?.let { TireReading(w, it, ((1 - it / ref) * 100).roundToInt()) } }
    }

    /** 더 큰 안내에 합친 사건: 따로 묻지 않고 후속 확인 대상으로 둔다 */
    private fun absorb(incidentId: String, by: Incident) {
        moveIncident(incidentId, IncidentStatus.NOTIFIED)
        moveIncident(incidentId, IncidentStatus.FOLLOWUP_PENDING)
        log("같은 경고는 '${by.decision.situation?.label}' 안내에 합쳐 다시 묻지 않음", null)
    }

    /** 같은 상황이거나 정비 품목 묶음이 같은 열린 사건 (예: 타이어 경고등, 한 바퀴 저압, 여러 바퀴 저압) */
    private fun relatedOpen(inc: Incident): List<Incident> {
        val k = inc.decision.situation ?: return emptyList()
        val items = Playbooks.relatedItems(k)
        return incidents.all().filter { o ->
            o.id != inc.id && !o.isClosed && o.decision.situation?.let { ok -> ok == k || (items.isNotEmpty() && Playbooks.relatedItems(ok).any { it in items }) } == true
        }
    }

    private val lowLevelActive = mutableSetOf<TelltaleKind>()

    /** 잔량 값이 기준을 넘나들 때 '잔량 부족' 신호를 만든다. 경고등 신호와 지문이 달라 따로 다룬다 (area=LEVEL) */
    private fun onLevel(raw: RawVehicleSignal, kind: TelltaleKind, percent: Int?) {
        percent ?: return
        val on = when {
            percent <= config.lowEnergyPercent && kind !in lowLevelActive -> true
            percent >= config.lowEnergyPercent + 5 && kind in lowLevelActive -> false
            else -> return
        }
        if (on) lowLevelActive += kind else lowLevelActive -= kind
        if (on) log("잔량 ${percent}% (제품 안내 기준 ${config.lowEnergyPercent}% 이하)", raw.source)
        onTelltale(RawVehicleSignal(SignalType.TELLTALE, raw.source, raw.receivedAt, raw.sourceAt, kind,
            if (on) TelltaleState.ON else TelltaleState.OFF, area = LEVEL_AREA), clock.now())
    }

    private fun onGear(g: Gear, source: DataSource) {
        val was = gear
        gear = g
        if (g == Gear.PARK && was != Gear.PARK) {
            parkEpoch++
            proximity.onParked(clock.now(), lastFix?.accuracyMeters, source)?.let { applyEvidence(it) }
            log("정차(P) 확인", source)
        } else if (was == Gear.PARK && g != Gear.PARK) {
            log("주행 시작 ($g)", source)
            interruptParkedOnlyDialog()
        }
        wake.trySend(Unit)
    }

    private fun handleFix(fix: LocationFix) {
        val first = lastFix == null
        lastFix = fix
        if (visits.optedIn) proximity.onFix(fix).forEach { applyEvidence(it) }
        if (first) { log("위치 수신 시작", fix.source); publish() }
    }

    private fun applyEvidence(e: LocationEvidence) {
        val c = visits.onEvidence(e, poiCatalogAvailable = true) ?: return
        val name = places.byId(c.poiId)?.name ?: c.poiId
        when (e.kind) {
            LocationEvidence.Kind.ENTER -> { log("$name 반경 진입 (방문 후보)", e.source); persist() }
            LocationEvidence.Kind.PARKED -> log("$name 에서 정차", e.source)
            LocationEvidence.Kind.EXIT -> {
                log("$name 이탈: 체류 ${c.dwell.toMinutes()}분 → ${if (c.state == VisitState.POSTPONED_UNTIL_SAFE) "다음 정차 때 질문" else "후보 아님"}", e.source)
                if (c.state == VisitState.POSTPONED_UNTIL_SAFE) enqueueVisit(c)
                persist()
            }
            else -> {}
        }
    }

    private fun enqueueVisit(c: VisitCandidate) =
        enqueue(PendingSituation("visit:${c.id}", SituationKind.SERVICE_VISIT, clock.now(), c.source, visitId = c.id, minParkEpoch = parkEpoch + 1))

    /** 같은 기록에서 같은 시점에 도래한 품목(예: 엔진오일과 오일 필터)은 한 번에 묻는다 */
    private fun checkReminders(source: DataSource) {
        currentReminders().filter { it.status == ReminderStatus.DUE || it.status == ReminderStatus.DUE_SOON }
            .groupBy { Triple(it.recordId, it.dueOdometerKm, it.dueDate) }.values.forEach { g ->
                // 미리 알림과 도래 알림은 따로 한 번씩 묻는다(미리 알림 키에는 :soon을 붙인다)
                val keys = g.map { reminderKeyOf(it) + if (it.status == ReminderStatus.DUE_SOON) SOON_SUFFIX else "" }
                val asked = keys.any { it in promptedReminders } ||
                    pending.values.any { it.reminderKey in keys } || conversation?.key?.removePrefix("due:") in keys
                if (!asked) enqueue(PendingSituation("due:${keys.first()}", SituationKind.MAINTENANCE_DUE, clock.now(), source, reminderKey = keys.first()))
            }
    }

    private fun reminderKeyOf(r: Reminder) = "${r.recordId}:${r.itemCode}"

    /** 사용자가 넣은 주기가 우선이고, 없으면 제조사 권장 주기(통상 조건)를 쓴다 */
    private fun currentReminders() =
        IntervalCalculator(intervals + RecommendedIntervals.all).reminders(ledger.records(), vehicle, today(), odometerKm)
            .sortedBy { it.itemCode.ordinal }

    private fun enqueue(p: PendingSituation) {
        if (p.key in pending || conversation?.key == p.key) return
        pending[p.key] = p
        wake.trySend(Unit); publish()
    }

    // ---------------------------------------------------------------- 대화 루프

    /** 지금 진행 중인 대화. 주행을 시작하면 정차 전용 대화를 멈추는 데 쓴다 */
    private var active: Pair<PendingSituation, Job>? = null

    private suspend fun conversationLoop() {
        while (currentCoroutineContext().isActive) {
            val next = pickNext()
            if (next == null) { wake.receive(); continue }
            pending.remove(next.key)
            coroutineScope {
                val job = launch {
                    try {
                        runSituation(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("대화 처리 오류: ${e.message}", null)
                    }
                }
                active = next to job
                job.join()
            }
            active = null
            conversation = null
            visual = null
            publish()
        }
    }

    /** 정차 전용 대화(방문 기록, 조치 확인, 교환 시기)는 주행을 시작하면 멈춘다. 승인 전이라 저장된 것은 없다 */
    private fun interruptParkedOnlyDialog() {
        val (p, job) = active ?: return
        if (!p.kind.waitForPark || !job.isActive) return
        if (conversation?.phase == ConversationPhase.RESULT) return   // 이미 끝나 결과만 보여 주는 중이면 그대로 둔다
        job.cancel()
        val manual = p.source == DataSource.MANUAL_INPUT
        pending[p.key] = if (manual) p.copy(manualOnly = true)
        else p.copy(waitForPark = true, minParkEpoch = parkEpoch + 1, manualOnly = false)
        log("주행 시작: '${p.kind.label}' 대화를 멈춤 → ${if (manual) "화면에 남김" else "다음 정차 때 다시 질문"}", null)
    }

    /** 점검이 필요한 경고를 다루는 중이면 방문 질문 같은 일상 관리는 미룬다 (UT-28) */
    private fun highRiskActive(): Boolean {
        fun inspection(incidentId: String?) = incidentId?.let { incidents.get(it) }?.decision?.needsInspection() == true
        val waiting = pending.values.any { !it.manualOnly && it.kind != SituationKind.ISSUE_FOLLOW_UP && inspection(it.incidentId) }
        val talking = conversation?.key?.takeIf { it.startsWith("inc:") }?.let { inspection(it.removePrefix("inc:")) } == true
        return waiting || talking
    }

    private fun pickNext(): PendingSituation? = pending.values
        .filter { !it.manualOnly && (!it.waitForPark || (gear == Gear.PARK && parkEpoch >= it.minParkEpoch)) }
        .filter { !(it.kind == SituationKind.SERVICE_VISIT && highRiskActive()) }   // UT-28
        .sortedWith(compareBy({ it.kind.priority }, { it.createdAt }))
        .firstOrNull()

    private suspend fun runSituation(p: PendingSituation) = when (p.kind) {
        SituationKind.TIRE_MULTI_LOW, SituationKind.TIRE_WARNING, SituationKind.TIRE_PRESSURE_LOW, SituationKind.LOW_FUEL, SituationKind.LOW_EV_BATTERY, SituationKind.RANGE_SHORT,
        SituationKind.MAINTENANCE_DUE -> runHelp(p)
        SituationKind.SERVICE_VISIT -> if (p.visitId == null) runRecord(p, null, null, emptyList(), p.utterance?.let { UserReply.Voice(it, null) }) else runVisit(p)
        SituationKind.ISSUE_FOLLOW_UP -> runFollowUp(p)
    }

    /** 경고·교환 시기: 설명 → 대안 → (장소 안내 → 승인 → 내비) */
    private suspend fun runHelp(p: PendingSituation) {
        val inc = p.incidentId?.let { incidents.get(it) }
        if (p.incidentId != null && (inc == null || inc.isClosed)) return
        val decision = inc?.decision
        val dues = p.reminderKey?.let { k ->
            val key = k.removeSuffix(SOON_SUFFIX)
            val all = currentReminders()
            val lead = all.firstOrNull { reminderKeyOf(it) == key } ?: return@let emptyList()
            listOf(lead) + all.filter {
                it !== lead && it.status == lead.status && it.recordId == lead.recordId &&
                    it.dueOdometerKm == lead.dueOdometerKm && it.dueDate == lead.dueDate
            }
        } ?: emptyList()
        val due = dues.firstOrNull()
        if (p.reminderKey != null) {
            val suffix = if (p.reminderKey.endsWith(SOON_SUFFIX)) SOON_SUFFIX else ""
            promptedReminders += p.reminderKey; promptedReminders += dues.map { reminderKeyOf(it) + suffix }; persist()
        }

        val unknown = decision?.level == ResponsePolicy.PX_UNKNOWN
        val parked = gear == Gear.PARK
        val mobileAt = if (p.kind == SituationKind.RANGE_SHORT) mobileChargeRestArea() else null
        val baseOpts = if (unknown) emptyList() else
            (if (mobileAt != null) listOf(CareOption(OptionKind.MOBILE_CHARGE, "찾아가는 충전 요청")) else emptyList()) + Playbooks.options(p.kind, vehicle.powertrain)
        val pressure = inc?.fingerprint?.startsWith("${SignalType.TIRE_PRESSURE}:") == true
        val usual = if (p.kind == SituationKind.LOW_FUEL) usualPlace(PlaceCategory.FUEL_STATION) else null
        val usualReachable = reachable(usual)
        val opts = baseOpts.filter { o ->
            (o.kind !in setOf(OptionKind.FIND_PLACE, OptionKind.VIA_PLACE) || decision == null || AllowedAction.SEARCH_SERVICE_CANDIDATES in decision.allowedActions) &&
                (!o.parkedOnly || parked)
        }.map { o ->
            // 평소 주유소까지 충분하면 안내 문구와 버튼이 같은 곳을 가리키게 한다
            if (o.kind == OptionKind.FIND_PLACE && usualReachable == true) o.copy(label = "평소 주유소로 안내") else o
        }
        val kmSince = due?.let { d -> ledger.records().firstOrNull { it.id == d.recordId }?.odometerAtServiceKm?.let { s -> odometerKm?.minus(s) } }
        val ctx = Playbooks.Context(
            fuelPercent = fuelPercent, evSoc = evSoc,
            recurrenceOf = inc?.recurrenceParentId?.let { incidents.get(it)?.firstSeen },
            dueItems = dues.map { it.itemCode }, kmSinceService = kmSince, unknownState = unknown,
            levelDerived = inc?.fingerprint?.endsWith(":$LEVEL_AREA") == true,
            // 경고등으로 먼저 왔어도 바퀴별 값이 있으면 어느 바퀴가 얼마나 낮은지 말한다
            tires = when {
                p.kind == SituationKind.TIRE_PRESSURE_LOW -> currentAdviseTires()
                pressure || p.kind == SituationKind.TIRE_WARNING || p.kind == SituationKind.TIRE_MULTI_LOW -> currentLowTires()
                else -> emptyList()
            },
            pressureDerived = pressure, parked = parked,
            rangeKm = rangeKm, usualPlace = usual, usualReachable = usualReachable,
            trip = trip, rangeTight = trip?.let { t -> rangeKm?.let { it >= t.remainingKm } } == true, ev = vehicle.powertrain == Powertrain.EV,
            mobileChargeAt = mobileAt,
            recommendedTirePsi = vehicle.recommendedTirePsi,
            dueSoon = due?.status == ReminderStatus.DUE_SOON,
            kmToDue = due?.dueOdometerKm?.let { d -> odometerKm?.let { d - it } },
            daysToDue = due?.dueDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today(), it) },
        )
        val msg = if (p.deferCount > 0 && parked) Playbooks.reask(p.kind, ctx) else Playbooks.explain(p.kind, ctx, today())
        visual = when (p.kind) {
            SituationKind.TIRE_WARNING, SituationKind.TIRE_MULTI_LOW, SituationKind.TIRE_PRESSURE_LOW -> tiresVisual()
            SituationKind.LOW_FUEL -> SituationVisual.Energy(false, fuelPercent, rangeKm, usual?.place?.name, usual?.distanceKm, usualReachable,
                usual?.let { "평소 가는 주유소" + if (it.place.demo) " · 체험용 데이터" else "" })
            SituationKind.LOW_EV_BATTERY -> SituationVisual.Energy(true, evSoc, rangeKm, null, null, null, null)
            SituationKind.RANGE_SHORT -> {
                val ev = vehicle.powertrain == Powertrain.EV
                SituationVisual.Energy(ev, if (ev) evSoc else fuelPercent, rangeKm, trip?.destinationName, trip?.remainingKm, false,
                    trip?.let { t -> listOfNotNull(t.basis.ifEmpty { null }, if (t.source == DataSource.SIMULATED_REPLAY) "체험용 데이터" else null).joinToString(" · ").ifEmpty { null } })
            }
            SituationKind.MAINTENANCE_DUE -> SituationVisual.Due(dues.map { it.itemCode },
                due?.let { d -> ledger.records().firstOrNull { it.id == d.recordId }?.odometerAtServiceKm }, odometerKm, due?.dueOdometerKm,
                due?.scheduleSource == ScheduleSource.OEM_DOCUMENT)
            else -> null
        }
        inc?.let { moveIncident(it.id, IncidentStatus.NOTIFIED) }
        flowForHelp(p.kind, parked, usual, usualReachable, mobileAt, due)
        flowTo("explain")
        if (opts.isEmpty()) { finish(p, msg); return }

        flowTo("offer")
        val reply = ask(view(p, msg, ConversationPhase.ASK_OPTIONS, options = opts), msg)
        val o = interpretOption(reply, opts)
        when (o?.kind) {
            OptionKind.FIND_PLACE -> {
                inc?.let { moveIncident(it.id, IncidentStatus.ACTION_OFFERED) }
                proposePlaces(p, o?.category ?: PlaceCategory.SERVICE_SHOP, inc, usual?.takeIf { usualReachable == true })
            }
            OptionKind.MOBILE_CHARGE -> if (mobileAt != null) runMobileCharge(p, inc, mobileAt) else unanswered(p, inc)
            OptionKind.BOOK_SERVICE -> runBooking(p, dues.map { it.itemCode })
            OptionKind.VIA_PLACE -> {
                flowTo("via")
                inc?.let { moveIncident(it.id, IncidentStatus.ACTION_OFFERED) }
                proposePlaces(p, o?.category ?: PlaceCategory.EV_CHARGER, inc, routeTo = trip?.destination)
            }
            OptionKind.REMIND_LATER -> defer(p)
            OptionKind.DISMISS -> {
                flowTo("declined")
                inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
                finish(p, if (inc != null) "알겠어요. 경고 상태는 계속 지켜볼게요." else "알겠어요. 이번 알림은 닫을게요.")
            }
            OptionKind.ALREADY_DONE -> runRecord(p, null, null, dues.map { it.itemCode }, reply as? UserReply.Voice)
            OptionKind.SHOW_ROADSIDE -> {
                inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
                log("긴급출동 연락처 안내 (전화는 걸지 않음)", null)
                finish(p, Playbooks.ROADSIDE_TEXT)
            }
            // 즉시 안전 경고(P0)에 주행 중 답이 없으면 차를 세웠을 때 다시 알린다
            else -> if (decision?.level == ResponsePolicy.P0_IMMEDIATE_SAFETY && !parked) defer(p) else unanswered(p, inc)
        }
    }

    /**
     * 찾아가는 충전을 제안할 수 있으면 갈 수 있는 가장 가까운 휴게소. 조건: 전기차, 고속도로, 잔여 60km 이하,
     * 휴게소까지 여유 있게 갈 수 있음. 위치를 모르면 거리를 확인할 수 없어 제안하지 않는다
     */
    private fun mobileChargeRestArea(): PlaceDistance? {
        val r = rangeKm?.takeIf { rangeSeenNonZero } ?: return null
        if (vehicle.powertrain != Powertrain.EV || trip?.highway != true || r > MobileChargeTerms.MAX_RANGE_KM) return null
        return places.nearest(PlaceCategory.REST_AREA, lastFix?.point, limit = 5).firstOrNull { reachable(it) == true }
    }

    /** 찾아가는 충전: 요청과 휴게소 안내를 한 번에 묻는다(두 행동을 모두 밝히고 승인받음). 접수는 시연용 모의 응답 */
    private suspend fun runMobileCharge(p: PendingSituation, inc: Incident?, at: PlaceDistance) {
        val rest = at.place
        visual = SituationVisual.MobileCharge(rest.name, at.distanceKm, null, MobileChargeTerms.SUMMARY)
        val q = "${Playbooks.distanceText(at.distanceKm)} 앞 ${rest.name}에서 현대자동차 찾아가는 충전을 요청하고 그곳으로 안내할까요? 요청에는 차량 위치, 배터리 잔량, 만날 휴게소가 들어가요."
        val chargeAct = ProposedAction("mc-${idGen()}", ActionType.REQUEST_MOBILE_CHARGE,
            mapOf("restArea" to rest.id, "soc" to (evSoc?.toString() ?: "")), incidentId = inc?.id)
        val navAct = ActionExecutor.proposeNavigation("nav-${idGen()}", rest.toNavDestination(), inc?.id)
        val opts = listOf(CareOption(OptionKind.MOBILE_CHARGE, "요청하고 안내"), CareOption(OptionKind.DISMISS, "안 할게요"))
        flowTo("confirm")
        val reply = ask(view(p, q, ConversationPhase.ASK_OPTIONS, options = opts), q)
        val method = if (reply is UserReply.Voice) ConfirmationMethod.VOICE else ConfirmationMethod.TOUCH
        when (interpretOption(reply, opts)?.kind) {
            OptionKind.MOBILE_CHARGE -> {
                flowTo("approve")
                gate.record(chargeAct, ConsentChoice.APPROVED, method)
                gate.record(navAct, ConsentChoice.APPROVED, method)
                log("승인: 찾아가는 충전 요청 + ${rest.name} 안내 ($method)", null)
                if (gate.authorize(chargeAct) !is Authorization.Granted) { finish(p, "승인을 확인하지 못해 요청하지 않았어요."); return }
                inc?.let { moveIncident(it.id, IncidentStatus.ACTION_PENDING) }
                flowTo("request")
                when (val res = mobileCharge.request(MobileChargeRequest(rest, evSoc, rangeKm, clock.now()))) {
                    is MobileChargeResult.Accepted -> {
                        val eta = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(res.eta.atZone(config.zone))
                        val mock = if (res.simulated) " (체험용 모의 응답)" else ""
                        visual = SituationVisual.MobileCharge(rest.name, at.distanceKm, res.eta, MobileChargeTerms.SUMMARY)
                        log("찾아가는 충전 요청: ${rest.name}, 도착 예정 $eta$mock${if (res.simulated) ", 실제 접수 아님" else ""}", if (res.simulated) DataSource.SIMULATED_REPLAY else null)
                        val text = "출동 차량이 ${eta}쯤 ${rest.name}에 도착할 예정이에요.$mock"
                        flowTo("eta", fact = "도착 예정 $eta$mock")
                        show(view(p, text, ConversationPhase.RESULT)); say(text)
                        navigate(p, navAct, rest, inc)
                    }
                    is MobileChargeResult.Rejected -> {
                        log("찾아가는 충전 요청 실패: ${res.reason}", null)
                        inc?.let { moveIncident(it.id, IncidentStatus.ACTION_FAILED) }
                        finish(p, "찾아가는 충전을 요청하지 못했어요. " + Playbooks.ROADSIDE_TEXT)
                    }
                }
            }
            OptionKind.DISMISS -> {
                flowTo("declined")
                gate.record(chargeAct, ConsentChoice.DECLINED, method)
                inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
                finish(p, "요청하지 않을게요.")
            }
            else -> unanswered(p, inc)
        }
    }

    /**
     * 정비소 예약(교환 시기): 가까운 정비소와 가장 빠른 예약 시간을 한 번에 묻는다. 다른 시간·다른 정비소로 바꿀 수 있고,
     * 승인한 정비소·시간·품목 그대로만 요청한다. 접수는 시연용 모의 응답(공개 예약 API 없음)
     */
    private suspend fun runBooking(p: PendingSituation, items: List<ItemCode>) {
        // 원하는 지역을 먼저 묻고 그 지역의 정비소를 추천한다
        flowTo("area")
        val home = commute?.destination?.let { GeoPoint(it.latitude, it.longitude) } ?: places.home()
        // 지역은 운전자가 이름으로 말한다. 화면 버튼은 말하기를 대신하는 예시다
        val areaOpts = listOfNotNull(home?.let { CareOption(OptionKind.AREA_HOME, "집 근처") }) +
            places.areas().map { CareOption(OptionKind.AREA_NAMED, it, value = it) } + CareOption(OptionKind.DISMISS, "예약 안 함")
        var aq = "어느 지역 정비소로 예약할까요? 원하시는 지역 이름을 말씀해 주세요." + if (home != null) " 집 근처로도 찾아 드려요." else ""
        var area: String? = null   // null이면 집 근처
        var retried = false
        while (true) {
            val ar = ask(view(p, aq, ConversationPhase.ASK_OPTIONS, options = areaOpts), aq)
            val pick = interpretArea(ar, areaOpts)
            when (pick?.kind) {
                OptionKind.AREA_HOME -> { area = null; break }
                OptionKind.AREA_NAMED -> { area = pick.value; break }
                OptionKind.DISMISS -> { flowTo("declined"); finish(p, "예약하지 않을게요. 교환 시기가 되면 다시 알려 드릴게요."); return }
                else -> {
                    // 지역 이름을 알아듣지 못하면 한 번 더 묻는다
                    if (ar is UserReply.Voice && !retried) { retried = true; aq = "그 지역 정비소는 찾지 못했어요. 다른 지역 이름을 말씀해 주세요."; continue }
                    unanswered(p, null, "응답이 없어 예약하지 않았어요."); return
                }
            }
        }
        log("정비소 예약 희망 지역: ${area ?: "집 근처"}", DataSource.MANUAL_INPUT)
        flowTo("slot")
        val atHome = area == null
        val shops = if (atHome) places.nearest(PlaceCategory.SERVICE_SHOP, home).filter { (it.distanceKm ?: 0.0) <= config.bookingAreaKm }
            else places.all().filter { it.category == PlaceCategory.SERVICE_SHOP && it.area == area }
                .map { PlaceDistance(it, lastFix?.point?.let { f -> Geo.km(f, GeoPoint(it.latitude, it.longitude)) }) }
        if (shops.isEmpty()) { finish(p, "${area ?: "집"} 근처에서 예약할 수 있는 정비소를 찾지 못했어요."); return }
        val what = if (items.isEmpty()) "정비" else Playbooks.joinAnd(items.map { Playbooks.itemName(it) }) + " 교환"
        var si = 0
        var ti = 0
        var changed: OptionKind? = null
        while (true) {
            val shop = shops[si]
            val slots = booking.availableSlots(shop.place, today())
            if (slots.isEmpty()) { finish(p, "${shop.place.name}에 예약할 수 있는 시간이 없어요."); return }
            val slot = slots[ti % slots.size]
            val act = ProposedAction("bk-${idGen()}", ActionType.REQUEST_SERVICE_BOOKING,
                mapOf("shop" to shop.place.id, "slot" to slot.toString(), "items" to items.joinToString(",")))
            visual = SituationVisual.Booking(shop.place.name, shop.distanceKm, slot, items, confirmed = false)
            // 다시 제안할 때는 바뀐 것(시간 또는 정비소)만 말한다. 같은 문장을 되풀이하지 않는다
            val near = if (atHome) "집에서 ${Playbooks.distanceText(shop.distanceKm)} 거리의 ${shop.place.name}" else "${area}의 ${shop.place.name}"
            val q = when (changed) {
                OptionKind.OTHER_TIME -> "${BookingText.slot(slot)}도 가능해요. 이 시간으로 할까요?"
                OptionKind.OTHER_PLACE -> "${near}에서는 ${BookingText.slot(slot)}에 가능해요. 여기로 할까요?"
                else -> "${near}에서 ${BookingText.slot(slot)}에 $what 예약이 가능해요. 이 시간으로 예약할까요?"
            }
            val opts = listOf(CareOption(OptionKind.BOOK_SERVICE, "이대로 예약"), CareOption(OptionKind.OTHER_TIME, "다른 시간"),
                CareOption(OptionKind.OTHER_PLACE, "다른 정비소"), CareOption(OptionKind.DISMISS, "예약 안 함"))
            val reply = ask(view(p, q, ConversationPhase.ASK_OPTIONS, options = opts), q)
            val method = if (reply is UserReply.Voice) ConfirmationMethod.VOICE else ConfirmationMethod.TOUCH
            when (interpretOption(reply, opts)?.kind) {
                OptionKind.BOOK_SERVICE -> {
                    flowTo("approve")
                    gate.record(act, ConsentChoice.APPROVED, method)
                    log("승인: ${shop.place.name} ${BookingText.slot(slot)} 예약 요청 ($method)", null)
                    if (gate.authorize(act) !is Authorization.Granted) { finish(p, "승인을 확인하지 못해 예약하지 않았어요."); return }
                    flowTo("request")
                    when (val res = booking.request(BookingRequest(shop.place, slot, items))) {
                        is BookingResult.Accepted -> {
                            log("정비소 예약 요청: ${shop.place.name} ${BookingText.slot(res.slot)}" + if (res.simulated) " (체험용 모의 응답, 실제 접수 아님)" else "",
                                if (res.simulated) DataSource.SIMULATED_REPLAY else null)
                            visual = SituationVisual.Booking(shop.place.name, shop.distanceKm, res.slot, items, confirmed = true)
                            flowTo("booked", fact = "${shop.place.name} · ${BookingText.slot(res.slot)}" + if (res.simulated) " (모의 응답)" else "")
                            finish(p, "${shop.place.name}에 ${BookingText.slot(res.slot)}로 예약했어요. 예약 전날 다시 알려 드리고, 그날 출발할 때 정비소까지 안내해 드릴게요." +
                                if (res.simulated) " (체험용 모의 응답)" else "")
                        }
                        is BookingResult.Rejected -> {
                            log("정비소 예약 실패: ${res.reason}", null)
                            finish(p, "예약을 요청하지 못했어요. 다른 시간이나 정비소로 다시 시도할 수 있어요.")
                        }
                    }
                    return
                }
                OptionKind.OTHER_TIME -> { ti++; changed = OptionKind.OTHER_TIME }
                OptionKind.OTHER_PLACE -> { si = (si + 1) % shops.size; ti = 0; changed = OptionKind.OTHER_PLACE }
                OptionKind.DISMISS -> {
                    flowTo("declined")
                    gate.record(act, ConsentChoice.DECLINED, method)
                    finish(p, "예약하지 않을게요. 교환 시기가 되면 다시 알려 드릴게요.")
                    return
                }
                else -> {
                    gate.record(act, ConsentChoice.TIMED_OUT, ConfirmationMethod.NONE)
                    unanswered(p, null, "응답이 없어 예약하지 않았어요.")
                    return
                }
            }
        }
    }

    /** 평소 가는 곳과 지금 위치에서의 거리 */
    private fun usualPlace(category: PlaceCategory): PlaceDistance? =
        places.usualId(category)?.let { places.byId(it) }?.let { pl -> PlaceDistance(pl, lastFix?.point?.let { Geo.km(it, GeoPoint(pl.latitude, pl.longitude)) }) }

    /** 주행가능거리로 여유 있게 갈 수 있는가. 거리나 주행가능거리를 모르면 null */
    private fun reachable(pd: PlaceDistance?): Boolean? {
        val d = pd?.distanceKm ?: return null
        val r = rangeKm ?: return null
        return r >= d * config.reachMargin
    }

    /** [preferred]가 있으면(평소 가는 곳) 첫 후보로 둔다 */
    private suspend fun proposePlaces(p: PendingSituation, category: PlaceCategory, inc: Incident?, preferred: PlaceDistance? = null, routeTo: NavDestination? = null) {
        // routeTo가 있으면 고른 곳을 경유지로 넣고 원래 목적지로 가는 경로를 승인받는다
        fun proposal(pl: CarePlace) = if (routeTo != null) ActionExecutor.proposeRoute("nav-${idGen()}", routeTo, pl.toNavDestination(), inc?.id)
            else ActionExecutor.proposeNavigation("nav-${idGen()}", pl.toNavDestination(), inc?.id)
        flowTo("candidate")
        val near = places.nearest(category, lastFix?.point)
        val cands = if (preferred == null) near else (listOf(preferred) + near.filter { it.place.id != preferred.place.id }).take(3)
        if (cands.isEmpty()) {
            inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
            finish(p, "근처 ${category.label} 정보를 찾지 못했어요."); return
        }
        var i = 0
        var firstRound = true
        var carried: UserReply? = null
        while (true) {
            val c = cands[i]
            val action = proposal(c.place)
            val lead = when { c.place.id == preferred?.place?.id -> "평소 가시는"; firstRound -> "가장 가까운 곳은"; else -> "이번 후보는" }
            val text = "$lead ${c.place.name}, ${Playbooks.distanceText(c.distanceKm)}입니다. " + (if (routeTo != null) "여기를 들렀다 가도록 경로를 바꿀까요?" else "여기로 안내할까요?")
            firstRound = false
            val v = view(p, text, ConversationPhase.PROPOSE_PLACE, places = cands, proposed = c.place.id)
            val reply = carried ?: ask(v, text)
            carried = null
            when (val a = interpretPlace(reply, cands, i)) {
                is PlaceAnswer.Go -> {
                    val target = cands[a.index]
                    val act = if (a.index == i) action else proposal(target.place)
                    flowTo("approve")
                    gate.record(act, ConsentChoice.APPROVED, a.method)
                    log(if (routeTo != null) "승인: ${target.place.name} 경유 → ${routeTo.name} (${a.method})" else "승인: ${target.place.name} 목적지 설정 (${a.method})", null)
                    navigate(p, act, target.place, inc, routeTo)
                    return
                }
                is PlaceAnswer.Select -> i = a.index           // 순번만 말하면 그 후보를 한 번 더 확인한다
                PlaceAnswer.Next -> if (i + 1 < cands.size) i++ else { i = 0; say("다른 후보가 없어요. 처음 후보부터 다시 알려 드릴게요.") }
                is PlaceAnswer.Decline -> {
                    flowTo("declined")
                    gate.record(action, ConsentChoice.DECLINED, a.method)
                    inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
                    finish(p, "안내하지 않을게요."); return
                }
                PlaceAnswer.Defer -> { gate.record(action, ConsentChoice.DEFERRED, ConfirmationMethod.VOICE); defer(p); return }
                PlaceAnswer.Unclear -> {
                    show(v.copy(listening = false))
                    say("잘 알아듣지 못했어요. 화면에서 골라 주세요.")
                    carried = awaitReply(listenVoice = false)
                }
                PlaceAnswer.Timeout -> {
                    gate.record(action, ConsentChoice.TIMED_OUT, ConfirmationMethod.NONE)
                    unanswered(p, inc, "응답이 없어 안내하지 않았어요."); return
                }
            }
        }
    }

    private suspend fun navigate(p: PendingSituation, act: ProposedAction, place: CarePlace, inc: Incident?, routeTo: NavDestination? = null) {
        inc?.let { moveIncident(it.id, IncidentStatus.ACTION_PENDING) }
        show(view(p, "${place.name} 안내를 요청하고 있어요.", ConversationPhase.RESULT))
        val result = if (routeTo != null) executor.setRoute(act, routeTo, place.toNavDestination()) else executor.setDestination(act, place.toNavDestination())
        when (result) {
            ExecutionResult.Acknowledged -> {
                log(if (routeTo != null) "내비 경유지 경로 확인: ${place.name} → ${routeTo.name}" else "내비 목적지 설정 확인: ${place.name}", null)
                flowTo("act", fact = if (routeTo != null) "경유 ${place.name} → ${routeTo.name}" else "목적지 ${place.name}")
                var text = if (routeTo != null) "경로를 바꿨어요. 경유지는 ${place.name}, 목적지는 ${routeTo.name}입니다." else "안내를 시작할게요. 목적지는 ${place.name}입니다."
                inc?.let {
                    moveIncident(it.id, IncidentStatus.ACTION_ACKNOWLEDGED)
                    moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING)
                    if (AllowedAction.CREATE_HANDOFF_CARD in it.decision.allowedActions) {
                        handoffs += ServiceHandoffCard.from(incidents.get(it.id)!!, "${place.name} 목적지 설정 (사용자 승인)")
                        text += " 정비소에서 보여 줄 점검 메모를 화면에 만들어 뒀어요."
                        flowTo("memo")
                    }
                }
                persist(); finish(p, text)
            }
            is ExecutionResult.Failed -> {
                log("내비 설정 실패: ${result.code}", null)
                inc?.let { moveIncident(it.id, IncidentStatus.ACTION_FAILED) }
                pending[p.key] = p.copy(manualOnly = true)
                finish(p, "내비게이션 안내를 시작하지 못했어요. 화면에서 다시 시도할 수 있어요.")
            }
            ExecutionResult.Unknown -> {
                log("내비 응답 확인 못함 (완료로 보지 않음)", null)
                inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
                finish(p, "내비게이션 응답을 확인하지 못했어요. 내비 화면을 확인해 주세요.")
            }
            is ExecutionResult.Blocked -> {
                log("실행 차단: ${result.reason}", null)
                inc?.let { moveIncident(it.id, IncidentStatus.ACTION_FAILED) }
                finish(p, "승인을 확인하지 못해 안내하지 않았어요.")
            }
        }
    }

    /** 정비소 방문 후보: 정차 뒤 먼저 묻는다 (4.2) */
    private suspend fun runVisit(p: PendingSituation) {
        val v = p.visitId?.let { visits.get(it) } ?: return
        if (!visits.optedIn || v.state !in setOf(VisitState.POSTPONED_UNTIL_SAFE, VisitState.PROMPTED)) return
        visits.markPrompted(v.id); persist()
        visual = SituationVisual.Visit(places.byId(v.poiId)?.name ?: v.poiId, v.dwell.toMinutes())
        val placeName = places.byId(v.poiId)?.name ?: v.poiId
        flowStart(FlowScenario.VISIT_RECORD, listOf("consent", "enter", "dwell", "leave", "park", "ask"), listOf(
            "위치 사용 동의함", "$placeName 반경 진입", "체류 ${v.dwell.toMinutes()}분 ≥ 기준 ${(if (demoShortDwell) config.demoVisit else config.visit).minDwell.toMinutes()}분",
            "출발 뒤 다음 정차에서 질문"))
        val msg = Playbooks.explain(SituationKind.SERVICE_VISIT, Playbooks.Context(placeName = places.byId(v.poiId)?.name), today())
        val opts = Playbooks.options(SituationKind.SERVICE_VISIT)
        val reply = ask(view(p, msg, ConversationPhase.ASK_OPTIONS, options = opts), msg)
        when (interpretOption(reply, opts)?.kind) {
            OptionKind.TELL_SERVICE -> {
                visits.onAnswer(v.id, UserIntent.APPROVE); persist()
                runRecord(p, visits.get(v.id), null, emptyList(), reply as? UserReply.Voice)
            }
            OptionKind.NOT_SERVICED -> { visits.onAnswer(v.id, UserIntent.DECLINE); persist(); finish(p, "알겠어요. 기록하지 않을게요.") }
            OptionKind.REMIND_LATER -> {
                val u = visits.onAnswer(v.id, UserIntent.DEFER); persist()
                if (u.state == VisitState.POSTPONED_UNTIL_SAFE) {
                    pending[p.key] = p.copy(deferCount = p.deferCount + 1, minParkEpoch = parkEpoch + 1)
                    finish(p, "알겠어요. 다음에 차를 세우시면 다시 여쭤볼게요.")
                } else finish(p, "알겠어요. 이번 방문은 더 묻지 않을게요.")
            }
            OptionKind.NEVER_ASK -> { visits.onAnswer(v.id, UserIntent.NEVER_ASK_AGAIN); persist(); finish(p, "이곳에서는 더 묻지 않을게요.") }
            else -> { pending[p.key] = p.copy(manualOnly = true); finish(p, "응답을 확인하지 못했어요. 기록은 하지 않았고, 화면에서 나중에 답할 수 있어요.") }
        }
    }

    /** 경고등이 꺼진 뒤 조치 여부를 묻는다. 경고등 해제와 사용자 조치는 따로 기록한다 (FR-011) */
    private suspend fun runFollowUp(p: PendingSituation) {
        val inc = p.incidentId?.let { incidents.get(it) } ?: return
        if (inc.userActionCompleted || inc.isClosed || !inc.sensorCleared) return
        if (inc.decision.situation?.let { Playbooks.relatedItems(it).isNotEmpty() } == true) {
            visual = tiresVisual()
            if (flow?.scenario == FlowScenario.TIRE) flowTo("recover", "follow", fact = "공기압 회복 · 경고등 꺼짐")
            else flowStart(FlowScenario.TIRE, listOf("recover", "follow"), listOf("공기압 회복 · 경고등 꺼짐"))
        }
        val msg = Playbooks.explain(SituationKind.ISSUE_FOLLOW_UP, Playbooks.Context(followUpOf = inc.decision.situation,
            pressureDerived = inc.fingerprint.startsWith("${SignalType.TIRE_PRESSURE}:")), today())
        val opts = Playbooks.options(SituationKind.ISSUE_FOLLOW_UP)
        val reply = ask(view(p, msg, ConversationPhase.ASK_OPTIONS, options = opts), msg)
        when (interpretOption(reply, opts)?.kind) {
            OptionKind.CONFIRM_ACTION_TAKEN -> {
                moveIncident(inc.id, IncidentStatus.USER_REPORTED_ACTION_TAKEN)
                // 함께 해제된 관련 사건(경고등·바퀴별 저압)도 같은 조치로 본다
                relatedOpen(inc).filter { it.sensorCleared && !it.userActionCompleted }.forEach { moveIncident(it.id, IncidentStatus.USER_REPORTED_ACTION_TAKEN) }
                log("사용자 조치 보고 (경고등 해제와 따로 기록)", DataSource.MANUAL_INPUT)
                val q = "받으신 정비를 기록해 둘까요?"
                val o2 = listOf(CareOption(OptionKind.TELL_SERVICE, "기록할게요"), CareOption(OptionKind.DISMISS, "괜찮아요"))
                val r2 = ask(view(p, q, ConversationPhase.ASK_OPTIONS, options = o2), q)
                if (interpretOption(r2, o2)?.kind == OptionKind.TELL_SERVICE) runRecord(p, null, inc.id, emptyList(), r2 as? UserReply.Voice)
                else finish(p, "알겠어요. 같은 경고가 다시 생기면 지난 기록과 함께 알려 드릴게요.")
            }
            OptionKind.DISMISS -> { flowTo("declined"); finish(p, "알겠어요. 경고등이 다시 켜지면 알려 드릴게요.") }
            else -> { pending[p.key] = p.copy(manualOnly = true); finish(p, "응답을 확인하지 못했어요. 화면에서 나중에 답할 수 있어요.") }
        }
    }

    /** 정비 기록: 품목 → 확인 → 승인 저장 → 다음 교환 시점 갱신 (UX-08, FR-016~018) */
    private suspend fun runRecord(p: PendingSituation, visit: VisitCandidate?, relatedIncidentId: String?, preset: List<ItemCode>, utterance: UserReply.Voice?) {
        val today = today()
        val odo = odometerKm?.takeIf { it > 0 }
        val odoSrc = if (odo != null) ValueSource.VEHICLE_SDK else ValueSource.UNKNOWN
        fun fromVoice(text: String, conf: Float?) = ledger.createDraft(SttResult(text, conf), vehicle, visit, today, odo, odoSrc, DataSource.MANUAL_INPUT)
        fun fromItems(codes: List<ItemCode>) = ledger.createDraftFromItems(codes, vehicle, visit, today, odo, odoSrc, DataSource.MANUAL_INPUT)

        var draft: MaintenanceDraft? = when {
            utterance != null && MaintenanceUtteranceParser.parse(utterance.text, vehicle.powertrain).first.isNotEmpty() -> fromVoice(utterance.text, utterance.confidence)
            preset.isNotEmpty() -> fromItems(preset)
            else -> null
        }
        var tries = 0
        while (draft == null || draft.itemCandidates.isEmpty()) {
            if (tries >= 2) { finish(p, "정비 항목을 확인하지 못해 기록하지 않았어요."); return }
            val q = if (tries == 0) "어떤 정비를 받으셨나요?" else "정비 항목을 알아듣지 못했어요. 다시 말씀하시거나 화면에서 골라 주세요."
            tries++
            flowTo("items")
            draft = when (val r = ask(view(p, q, ConversationPhase.ASK_ITEMS), q, config.pickTimeout)) {
                is UserReply.Voice -> {
                    val items = MaintenanceUtteranceParser.parse(r.text, vehicle.powertrain).first
                    if (items.isEmpty() && IntentParser.parse(r.text) in setOf(UserIntent.DECLINE, UserIntent.CANCEL, UserIntent.NEVER_ASK_AGAIN)) {
                        finish(p, "기록하지 않을게요."); return
                    }
                    fromVoice(r.text, r.confidence)
                }
                is UserReply.Items -> fromItems(r.codes)
                is UserReply.Choose, is UserReply.Confirm -> { finish(p, "기록하지 않을게요."); return }
                UserReply.Timeout -> { finish(p, "응답이 없어 기록하지 않았어요."); return }
                else -> null
            }
        }

        val chosen: MaintenanceDraft = draft ?: return
        // 기존 미해결 '점검 필요' 사건 중 이번 품목과 관련된 것과 연결 후보로 둔다 (4.3). 같은 정비를 두 번 저장하지 않는다
        val chosenCodes = chosen.itemCandidates.map { it.code }.toSet()
        val relatedIncident = relatedIncidentId?.let { incidents.get(it) } ?: incidents.all().lastOrNull {
            it.decision.needsInspection() && !it.isClosed && !it.userActionCompleted &&
                it.decision.situation?.let { k -> Playbooks.relatedItems(k).any { c -> c in chosenCodes } } == true
        }
        val related = relatedIncident?.id

        var current: MaintenanceDraft = chosen
        var unclear = 0
        while (true) {
            val itemsText = current.itemCandidates.joinToString(", ") { it.code.label }
            val odoText = current.odometerKm?.let { "주행거리는 ${"%,.0f".format(it)}킬로미터로 확인돼요." } ?: "주행거리는 확인되지 않아 비워 둘게요."
            val linkText = relatedIncident?.decision?.situation?.let { " 지난 ${it.label} 건과 함께 기록할게요." } ?: ""
            val q = "오늘 받으신 정비를 이렇게 기록할까요? $itemsText. $odoText$linkText"
            val action = ledger.proposeSave(current)
            flowTo("items", "confirm")
            val r = ask(view(p, q, ConversationPhase.CONFIRM_RECORD, draftItems = current.itemCandidates.map { it.code },
                draftDate = current.serviceDate, draftOdometerKm = current.odometerKm), q)
            val method = if (r is UserReply.Voice) ConfirmationMethod.VOICE else ConfirmationMethod.TOUCH
            val yes: Boolean? = when (r) {
                is UserReply.Confirm -> r.yes
                is UserReply.Voice -> when (IntentParser.parse(r.text)) {
                    UserIntent.APPROVE -> true
                    UserIntent.DECLINE, UserIntent.CANCEL, UserIntent.NEVER_ASK_AGAIN -> false
                    else -> null
                }
                is UserReply.Items -> { current = fromItems(r.codes); continue }   // 화면에서 품목을 고치면 다시 확인한다
                UserReply.Timeout -> { gate.record(action, ConsentChoice.TIMED_OUT, ConfirmationMethod.NONE); finish(p, "응답이 없어 기록하지 않았어요."); return }
                else -> null
            }
            if (yes == null) {
                if (++unclear >= 2) { finish(p, "확인하지 못해 기록하지 않았어요."); return }
                continue
            }
            if (!yes) { flowTo("declined"); gate.record(action, ConsentChoice.DECLINED, method); finish(p, "기록하지 않을게요."); return }
            gate.record(action, ConsentChoice.APPROVED, method)
            when (val res = ledger.confirm(current, action, related)) {
                is LedgerResult.Saved -> {
                    flowTo("save", "next", "record", fact = "기록 저장 (사용자 승인)")
                    val rec = res.record
                    related?.let { id ->
                        incidents.linkMaintenance(id, rec.id, technicianConfirmedByUser = false)
                        moveIncident(id, IncidentStatus.USER_REPORTED_ACTION_TAKEN)
                    }
                    log("정비 기록 저장: $itemsText (사용자 승인, $method)", DataSource.MANUAL_INPUT)
                    persist()
                    finish(p, "기록했어요. " + nextReminderText(rec))
                }
                is LedgerResult.Rejected -> { log("정비 기록 거부: ${res.reason}", null); finish(p, "기록하지 못했어요. ${res.reason}") }
            }
            return
        }
    }

    private fun nextReminderText(rec: MaintenanceRecord): String {
        val rs = currentReminders().filter { it.recordId == rec.id && (it.dueOdometerKm != null || it.dueDate != null) }
        if (rs.isEmpty()) return "교환 주기를 설정해 두시면 때가 됐을 때 먼저 알려 드릴게요."
        val year = today().year
        // 근거("제조사 권장 주기에 따라")는 한 번만 말하고, 시점이 다른 품목은 쉼표로 잇는다
        return rs.groupBy { it.scheduleSource == ScheduleSource.OEM_DOCUMENT }.entries.joinToString(" ") { (oem, list) ->
            val basis = if (oem) "제조사 권장 주기에 따라" else "설정하신 주기에 따라"
            val whens = list.groupBy { it.dueOdometerKm to it.dueDate }.values.joinToString(", ") { g ->
                val r = g.first()
                val parts = listOfNotNull(
                    r.dueOdometerKm?.let { "${"%,.0f".format(it)}킬로미터" },
                    r.dueDate?.let { (if (it.year != year) "${it.year}년 " else "") + "${it.monthValue}월 ${it.dayOfMonth}일" },
                )
                "${Playbooks.joinAnd(g.map { Playbooks.itemName(it.itemCode) })} 알림은 ${parts.joinToString(" 또는 ")}"
            }
            "$basis 다음 $whens 시점으로 잡아 둘게요."
        }
    }

    private suspend fun defer(p: PendingSituation) {
        flowTo("later")
        if (p.deferCount >= 1) {
            pending[p.key] = p.copy(manualOnly = true)
            finish(p, "알겠어요. 다시 묻지 않고 화면에 남겨 둘게요."); return
        }
        pending[p.key] = p.copy(deferCount = p.deferCount + 1, waitForPark = true, minParkEpoch = parkEpoch + 1, manualOnly = false)
        p.incidentId?.let { moveIncident(it, IncidentStatus.FOLLOWUP_PENDING) }
        finish(p, "알겠어요. 다음에 차를 세우시면 다시 알려 드릴게요.")
    }

    private suspend fun unanswered(p: PendingSituation, inc: Incident?, lead: String = "응답을 확인하지 못했어요.") {
        flowTo("noanswer")
        inc?.let { moveIncident(it.id, IncidentStatus.FOLLOWUP_PENDING) }
        pending[p.key] = p.copy(manualOnly = true)
        finish(p, "$lead 화면에서 다시 열 수 있어요.")
    }

    // ---------------------------------------------------------------- 응답 해석

    /**
     * 희망 지역 답: 말한 지역 이름(예: "수원 쪽으로") → 그 지역, "집" → 집 근처, 거절 → 예약 안 함.
     * "응"처럼 지역이 없는 답은 지역을 정하지 않는다(null → 다시 묻기)
     */
    private fun interpretArea(reply: UserReply, opts: List<CareOption>): CareOption? {
        if (reply is UserReply.Choose && reply.option == OptionKind.AREA_NAMED) return opts.firstOrNull { it.kind == OptionKind.AREA_NAMED && it.value == reply.value }
        val t = (reply as? UserReply.Voice)?.text ?: return interpretOption(reply, opts)
        opts.firstOrNull { it.kind == OptionKind.AREA_NAMED && it.value != null && it.value in t }?.let { return it }
        if ("집" in t) opts.firstOrNull { it.kind == OptionKind.AREA_HOME }?.let { return it }
        return interpretOption(reply, opts)?.takeIf { it.kind == OptionKind.DISMISS }
    }

    private fun interpretOption(reply: UserReply, opts: List<CareOption>): CareOption? {
        fun of(k: OptionKind) = opts.firstOrNull { it.kind == k }
        return when (reply) {
            is UserReply.Choose -> of(reply.option)
            is UserReply.Confirm -> if (reply.yes) opts.firstOrNull() else of(OptionKind.NOT_SERVICED) ?: of(OptionKind.DISMISS)
            is UserReply.Voice -> {
                val t = reply.text
                if (of(OptionKind.MOBILE_CHARGE) != null && ("찾아가는" in t || ("출동" in t && "충전" in t))) return of(OptionKind.MOBILE_CHARGE)
                if (of(OptionKind.SHOW_ROADSIDE) != null && listOf("긴급", "출동", "견인").any { it in t }) return of(OptionKind.SHOW_ROADSIDE)
                if (of(OptionKind.OTHER_TIME) != null && ("다른 시간" in t || "다른 날" in t || "시간 바꿔" in t)) return of(OptionKind.OTHER_TIME)
                if (of(OptionKind.OTHER_PLACE) != null && ("다른 정비소" in t || "다른 곳" in t || "다른 데" in t)) return of(OptionKind.OTHER_PLACE)
                if (of(OptionKind.BOOK_SERVICE) != null && "예약" in t && listOf("안 ", "말고", "싫").none { it in t }) return of(OptionKind.BOOK_SERVICE)
                val hasItems = MaintenanceUtteranceParser.parse(t, vehicle.powertrain).first.isNotEmpty()
                val done = hasItems || saysDone(t)
                when (IntentParser.parse(t)) {
                    UserIntent.NEVER_ASK_AGAIN -> of(OptionKind.NEVER_ASK) ?: of(OptionKind.DISMISS)
                    UserIntent.CANCEL, UserIntent.DECLINE -> of(OptionKind.NOT_SERVICED) ?: of(OptionKind.DISMISS)
                    UserIntent.DEFER -> of(OptionKind.REMIND_LATER)
                    UserIntent.APPROVE, UserIntent.SEARCH_REQUEST -> opts.firstOrNull()
                    UserIntent.NEXT, UserIntent.UNCLEAR -> when {
                        done && of(OptionKind.TELL_SERVICE) != null -> of(OptionKind.TELL_SERVICE)
                        done && of(OptionKind.ALREADY_DONE) != null -> of(OptionKind.ALREADY_DONE)
                        done && of(OptionKind.CONFIRM_ACTION_TAKEN) != null -> of(OptionKind.CONFIRM_ACTION_TAKEN)
                        else -> null
                    }
                }
            }
            else -> null
        }
    }

    private fun saysDone(t: String) = listOf("받았", "했어", "했습니다", "갈았", "교환했", "바꿨", "이미", "벌써", "고쳤").any { it in t }

    private sealed interface PlaceAnswer {
        data class Go(val index: Int, val method: ConfirmationMethod) : PlaceAnswer
        data class Select(val index: Int) : PlaceAnswer
        data object Next : PlaceAnswer
        data class Decline(val method: ConfirmationMethod) : PlaceAnswer
        data object Defer : PlaceAnswer
        data object Unclear : PlaceAnswer
        data object Timeout : PlaceAnswer
    }

    private fun interpretPlace(r: UserReply, cands: List<PlaceDistance>, current: Int): PlaceAnswer = when (r) {
        is UserReply.PickPlace -> cands.indexOfFirst { it.place.id == r.placeId }.let { if (it >= 0) PlaceAnswer.Go(it, ConfirmationMethod.TOUCH) else PlaceAnswer.Unclear }
        UserReply.NextPlace -> PlaceAnswer.Next
        is UserReply.Confirm -> if (r.yes) PlaceAnswer.Go(current, ConfirmationMethod.TOUCH) else PlaceAnswer.Decline(ConfirmationMethod.TOUCH)
        is UserReply.Choose -> when (r.option) {
            OptionKind.REMIND_LATER -> PlaceAnswer.Defer
            OptionKind.DISMISS -> PlaceAnswer.Decline(ConfirmationMethod.TOUCH)
            else -> PlaceAnswer.Unclear
        }
        is UserReply.Voice -> {
            val ord = IntentParser.ordinal(r.text)?.takeIf { it < cands.size }
            when {
                ord != null && ord != current -> PlaceAnswer.Select(ord)
                else -> when (IntentParser.parse(r.text)) {
                    UserIntent.APPROVE -> PlaceAnswer.Go(current, ConfirmationMethod.VOICE)
                    UserIntent.NEXT -> PlaceAnswer.Next
                    UserIntent.DECLINE, UserIntent.CANCEL, UserIntent.NEVER_ASK_AGAIN -> PlaceAnswer.Decline(ConfirmationMethod.VOICE)
                    UserIntent.DEFER -> PlaceAnswer.Defer
                    UserIntent.SEARCH_REQUEST, UserIntent.UNCLEAR ->
                        if (ord == current) PlaceAnswer.Go(current, ConfirmationMethod.VOICE) else PlaceAnswer.Unclear
                }
            }
        }
        UserReply.Timeout -> PlaceAnswer.Timeout
        is UserReply.Items -> PlaceAnswer.Unclear
        is UserReply.Text -> interpretPlace(UserReply.Voice(r.text.trim(), null, typed = true), cands, current)
        UserReply.Typing -> PlaceAnswer.Unclear
    }

    // ---------------------------------------------------------------- 말하기·듣기

    private suspend fun ask(asked: ConversationView, speech: String, timeout: Duration = config.replyTimeout): UserReply {
        askSeq += 1; askShownAt = clock.now()
        val v = asked.copy(seq = askSeq)
        while (replies.tryReceive().isSuccess) { /* 이전 화면에서 들어온 입력은 버린다 */ }
        show(v.copy(speaking = voiceEnabled, listening = false))
        // 말하는 도중 화면을 누르면 음성을 멈추고 그 응답으로 바로 진행한다
        val r = coroutineScope {
            val speaking = async { say(speech) }
            val early: UserReply? = select {
                replies.onReceive { typedAsVoice(it) }
                speaking.onAwait { null }
            }
            if (early != null) { speaking.cancel(); if (early == UserReply.Typing) awaitReply(listenVoice = false, timeout = config.pickTimeout) else early }
            else {
                val spoke = speaking.await()
                show(v.copy(speaking = false, listening = spoke))
                awaitReply(listenVoice = spoke, timeout = timeout)
            }
        }
        if (r is UserReply.Voice) {
            show(v.copy(heard = r.text, listening = false))
            log(if (r.typed) "텍스트 입력(음성 대체): \"${r.text}\"" else "음성 응답: \"${r.text}\"", if (r.typed) DataSource.MANUAL_INPUT else null)
        }
        return r
    }

    /** 글 입력은 음성 응답과 같은 해석을 탄다. 기록에는 음성 대체로 남긴다 */
    private fun typedAsVoice(r: UserReply): UserReply = if (r is UserReply.Text) UserReply.Voice(r.text.trim(), null, typed = true) else r

    /** 음성(1회)과 터치 중 먼저 온 응답. 음성이 비면 남은 시간 동안 터치를 기다린다. 상시 청취하지 않는다 (8.1) */
    private suspend fun awaitReply(listenVoice: Boolean, timeout: Duration = config.replyTimeout): UserReply {
        val r = awaitOnce(listenVoice, timeout)
        // 글 입력을 시작했다는 알림이면 입력할 시간을 다시 준다
        return if (r == UserReply.Typing) awaitReply(listenVoice = false, timeout = config.pickTimeout) else r
    }

    private suspend fun awaitOnce(listenVoice: Boolean, timeout: Duration): UserReply =
        withTimeoutOrNull(timeout.toMillis()) {
            coroutineScope {
                val voice = if (listenVoice) async { listenSafely() } else null
                val first: UserReply? = select {
                    replies.onReceive { typedAsVoice(it) }
                    if (voice != null) voice.onAwait { res -> res?.takeIf { it.text.isNotBlank() }?.let { UserReply.Voice(it.text.trim(), it.confidence) } }
                }
                voice?.cancel()
                first ?: run {
                    conversation?.let { show(it.copy(listening = false)) }
                    typedAsVoice(replies.receive())
                }
            }
        } ?: UserReply.Timeout

    private suspend fun listenSafely(): SttResult? = try {
        speechIn.listenOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("음성 인식 실패: ${e.message}", null); null
    }

    private suspend fun say(text: String): Boolean {
        if (!voiceEnabled) return false
        return try {
            speechOut.speak("agent", SpeechText.forTts(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("음성 출력 실패: ${e.message}", null); false
        }
    }

    private suspend fun finish(p: PendingSituation, text: String) {
        show(view(p, text, ConversationPhase.RESULT))
        say(text)
        delay(config.resultLinger.toMillis())
    }

    private fun view(
        p: PendingSituation, msg: String, phase: ConversationPhase, options: List<CareOption> = emptyList(),
        places: List<PlaceDistance> = emptyList(), proposed: String? = null, draftItems: List<ItemCode> = emptyList(),
        draftDate: LocalDate? = null, draftOdometerKm: Double? = null,
    ) = ConversationView(p.key, p.kind, msg, phase, options, places, proposed, draftItems, draftDate, draftOdometerKm, sourceLabel = p.source.label, visual = visual)

    private fun show(v: ConversationView) { conversation = v; publish() }

    // ---------------------------------------------------------------- 상태·저장

    private fun moveIncident(id: String, to: IncidentStatus) {
        val cur = incidents.get(id) ?: return
        if (cur.status == to) return
        if (to == IncidentStatus.USER_REPORTED_ACTION_TAKEN && !incidents.canTransition(id, to) && incidents.canTransition(id, IncidentStatus.FOLLOWUP_PENDING))
            incidents.transition(id, IncidentStatus.FOLLOWUP_PENDING)
        // 다시 도움을 요청한 사건은 '안내함' 단계를 건너뛸 수 있다. 그 밖의 생략만 기록한다
        if (incidents.canTransition(id, to)) incidents.transition(id, to) else if (to != IncidentStatus.NOTIFIED) log("사건 상태 전이 생략: ${cur.status} → $to", null)
        persist()
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.now(), config.zone)

    private companion object {
        const val LEVEL_AREA = "LEVEL"
        const val SOON_SUFFIX = ":soon"
        val WHEEL_ORDER = listOf("LEFT_FRONT", "RIGHT_FRONT", "LEFT_REAR", "RIGHT_REAR")
    }

    private fun log(text: String, source: DataSource?) {
        activity.addFirst(ActivityLine(clock.now(), text, source))
        while (activity.size > config.maxActivity) activity.removeLast()
    }

    private fun publish() {
        _state.value = AgentState(
            runtime = runtime, gear = gear, odometerKm = odometerKm, fuelPercent = fuelPercent, evSoc = evSoc, rangeKm = rangeKm, trip = trip,
            locationKnown = lastFix != null, tires = tiresVisual(), conversation = conversation?.copy(visual = liveVisual()), flow = flow, pending = pending.values.toList(),
            incidents = incidents.all(), records = ledger.records(), reminders = currentReminders(), intervals = intervals.toList(),
            handoffs = handoffs.toList(), visitOptIn = visits.optedIn, voiceEnabled = voiceEnabled, demoShortDwell = demoShortDwell,
            capabilities = capabilities, activity = activity.toList(),
        )
    }

    private fun persist() {
        val s = store ?: return
        try {
            s.save(CareSnapshot(
                incidents = incidents.all(), records = ledger.records(), intervals = intervals.toList(), visits = visits.all(),
                suppressedPois = visits.suppressedPoiIds(), visitFeatureSuppressed = visits.featureSuppressed, visitOptIn = visits.optedIn,
                consents = gate.history(), handoffs = handoffs.toList(), promptedReminders = promptedReminders.toSet(),
                voiceEnabled = voiceEnabled, demoShortDwell = demoShortDwell, lastOdometerKm = odometerKm,
            ))
        } catch (e: Exception) {
            log("저장 실패: ${e.message}", null)
        }
    }

    private fun restore() {
        val s = store?.load() ?: return
        incidents.restore(s.incidents)
        ledger.restore(s.records)
        intervals += s.intervals
        visits.restore(s.visits, s.suppressedPois, s.visitFeatureSuppressed, s.visitOptIn)
        gate.restore(s.consents)
        handoffs += s.handoffs
        promptedReminders += s.promptedReminders
        voiceEnabled = s.voiceEnabled
        demoShortDwell = s.demoShortDwell
        odometerKm = s.lastOdometerKm
        if (demoShortDwell) visits.updateConfig(config.demoVisit)
        // 재시작 전에 묻지 못한 항목을 다시 줄 세운다. 감지하지 못한 구간은 만들어 내지 않는다 (UT-26)
        visits.all().filter { it.state == VisitState.POSTPONED_UNTIL_SAFE }.forEach { enqueueVisit(it) }
        incidents.all().filter { it.status == IncidentStatus.OPEN && !it.sensorCleared }.forEach { inc ->
            inc.decision.situation?.let { enqueue(PendingSituation("inc:${inc.id}", it, clock.now(), inc.source, incidentId = inc.id)) }
        }
        log("저장된 기록 복구: 사건 ${s.incidents.size}건, 정비 기록 ${s.records.size}건", null)
    }
}
