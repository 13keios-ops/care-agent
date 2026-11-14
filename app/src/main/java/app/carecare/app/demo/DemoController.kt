package app.carecare.app.demo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import app.carecare.app.AgentHolder
import app.carecare.app.CareService
import app.carecare.app.MainActivity
import app.carecare.app.Prefs
import app.carecare.domain.CareOrchestrator
import app.carecare.domain.DataSource
import app.carecare.domain.Gear
import app.carecare.domain.LocationFix
import app.carecare.domain.RawVehicleSignal
import app.carecare.domain.SignalType
import app.carecare.domain.TelltaleKind
import app.carecare.domain.TelltaleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZonedDateTime

data class StepResult(val stepId: String, val ran: Boolean, val vcReachable: Boolean?)

/**
 * 시연 단계 실행기. Virtual Console 명령을 먼저 보내고, 닿지 않으면 같은 값을 시뮬레이션 신호로 앱에 넣는다(Review Focus 1).
 * 동력 방식을 바꾸거나 초기화해 에이전트가 새로 생기면 [SimState]를 다시 넣는다(Review Focus 2).
 */
class DemoController(private val app: Context, val vc: VirtualConsoleClient = VirtualConsoleClient()) {
    private val sim = SimState()
    private val prefs = Prefs(app)

    /** 3.2처럼 기다리는 단계의 남은 초. 패널이 표시한다 */
    var onCountdown: (Int?) -> Unit = {}

    suspend fun run(stepId: String): StepResult {
        val step = DemoScript.step(stepId) ?: return StepResult(stepId, false, null)
        val chapter = DemoScript.chapterOf(stepId)
        // 각 장의 첫 단계는 문제 없는 기본 상태부터 맞춘다
        val first = chapter != null && chapter.id != "0" && chapter.steps.first().id == stepId
        var vcOk: Boolean? = null
        if (first || stepId == "0.reset") vcOk = freshStart(chapter)
        for (s in if (stepId == "0.reset") emptyList() else listOf(step)) {
            val ph = s.phases()
            applyAll(ph.before)
            if (ph.vc.isNotEmpty()) {
                val ok = vc.apply(ph.vc)
                if (!ok) ph.vc.forEach { simulateVc(it) }
                vcOk = (vcOk ?: true) && ok
                // 기어가 앱에 들어간 뒤 다음 신호를 넣는다(주행 중·정차 판단이 앞 상태로 나오지 않게). 고정 대기 대신 조건으로 기다린다
                ph.vc.filterIsInstance<VcCommand.Gear>().lastOrNull()?.let { awaitGear(it.g) }
                // VC 값이 SDK를 거쳐 앱에 들어올 시간을 준다
                if (ok && ph.after.isNotEmpty()) delay(VC_SETTLE_MS)
            }
            applyAll(ph.after)
            if (s.vcAfter.isNotEmpty()) {
                delay(VC_AFTER_MS)
                val ok = vc.apply(s.vcAfter)
                if (!ok) s.vcAfter.forEach { simulateVc(it) }
                vcOk = (vcOk ?: true) && ok
            }
        }
        show(step.screen)
        Log.i(TAG, "demo step $stepId vc=${vcOk ?: "-"}")
        return StepResult(stepId, true, vcOk)
    }

    /**
     * 장 시작: 서비스를 멈추고 데이터를 지운 뒤, 문제 없는 기본 상태를 콘솔에 먼저 넣고 나서 서비스를 한 번만 다시 시작한다.
     * 이렇게 해야 새 에이전트가 앞 장의 경고등(콘솔에 아직 켜져 있음)을 다시 읽지 않고, 동력 방식 전환으로 두 번 다시 시작하지 않는다.
     */
    private suspend fun freshStart(chapter: DemoChapter?): Boolean? {
        val base = DemoScript.base
        val old = agent()
        sim.clear()
        // 이 장의 동력 방식을 먼저 정해 두면 단계의 동력 방식 전환은 다시 시작하지 않는다
        chapter?.steps?.flatMap { it.sim }?.filterIsInstance<SimAction.SetPowertrain>()?.firstOrNull()?.let { prefs.powertrain = it.p }
        CareService.resetAll(app, startAgain = false)
        if (old != null) withTimeoutOrNull(5_000) { AgentHolder.agent.first { it == null } }
        val ok = vc.apply(base.vc)
        if (ok) delay(VC_SETTLE_MS)
        CareService.start(app)
        awaitAgent(old)?.let { delay(AGENT_INIT_MS) }
        if (!ok) base.vc.forEach { simulateVc(it) }
        val ph = base.phases()
        applyAll(ph.before.filter { it != SimAction.ResetData })
        applyAll(ph.after)
        Log.i(TAG, "demo fresh start powertrain=${prefs.powertrain} vc=$ok")
        return ok
    }

    /** 바퀴 공기압은 한 번에 넣는다(두 바퀴가 함께 낮아진 장면을 한 안내로 말한다). 나머지는 순서대로 */
    private suspend fun applyAll(actions: List<SimAction>) {
        val tires = actions.filterIsInstance<SimAction.Tire>()
        for (a in actions) if (a !is SimAction.Tire) applySim(a, record = true)
        if (tires.isEmpty()) return
        tires.forEach { sim.record(it) }
        submitTires(tires)
    }

    private fun submitTires(tires: List<SimAction.Tire>) {
        val now = Instant.now()
        val raws = tires.flatMap { t -> (if (t.wheel == "ALL") SimState.WHEELS else listOf(t.wheel)).map { w ->
            RawVehicleSignal(SignalType.TIRE_PRESSURE, DataSource.SIMULATED_REPLAY, now, now, area = w, value = t.kpa, unit = "kPa")
        } }
        agent()?.submitAll(raws)
    }

    /** Virtual Console 기어가 앱에 들어올 때까지(최대 5초) 기다린다 */
    private suspend fun awaitGear(g: String) {
        val want = when (g) { "P" -> Gear.PARK; "R" -> Gear.REVERSE; "N" -> Gear.NEUTRAL; else -> Gear.DRIVE }
        val a = agent() ?: return
        if (withTimeoutOrNull(GEAR_WAIT_MS) { a.state.first { it.gear == want } } == null) Log.w(TAG, "gear $g not seen in ${GEAR_WAIT_MS}ms")
    }

    private fun agent(): CareOrchestrator? = AgentHolder.agent.value

    private suspend fun awaitAgent(old: CareOrchestrator?): CareOrchestrator? =
        withTimeoutOrNull(15_000) { AgentHolder.agent.first { it != null && it !== old } }

    private suspend fun applySim(a: SimAction, record: Boolean) {
        if (record) sim.record(a)
        val now = Instant.now()
        fun submit(raw: RawVehicleSignal) = agent()?.submit(raw)
        when (a) {
            is SimAction.Tire -> (if (a.wheel == "ALL") SimState.WHEELS else listOf(a.wheel)).forEach { w ->
                submit(RawVehicleSignal(SignalType.TIRE_PRESSURE, DataSource.SIMULATED_REPLAY, now, now, area = w, value = a.kpa, unit = "kPa"))
            }
            is SimAction.Fuel -> submit(RawVehicleSignal(SignalType.FUEL_PERCENT, DataSource.SIMULATED_REPLAY, now, now, value = a.pct.toDouble(), unit = "%"))
            is SimAction.Range -> submit(RawVehicleSignal(SignalType.RANGE_REMAINING, DataSource.SIMULATED_REPLAY, now, now, value = a.km, unit = "km"))
            is SimAction.Trip -> agent()?.setTrip(a.trip)
            is SimAction.Commute -> agent()?.setCommute(a.pattern, a.weekdayTime?.let { DemoTime.lastWeekdayAt(ZonedDateTime.now(), it).toInstant() })
            is SimAction.Location -> {
                // 위치는 앱 안 위치 흐름(10초 간격)을 기다리지 않고 바로 넣는다. [agoSeconds]만큼 앞선 시각이면 그동안 머문 것으로 본다
                a.point?.let { p -> agent()?.submitFix(LocationFix(p, 5f, now.minusSeconds(a.agoSeconds), DataSource.SIMULATED_REPLAY)) }
                DemoLocation.set(a.point)
            }
            is SimAction.SetPowertrain -> if (prefs.powertrain != a.p) {
                val old = agent()
                prefs.powertrain = a.p
                CareService.restart(app)
                awaitAgent(old)?.let { delay(1_500); replay() }
            }
            is SimAction.SeedRecord -> agent()?.let { ag -> ag.seedDemoRecord(a.items, odometerKm = currentOdometer()) }
            SimAction.ResetData -> {
                val old = agent()
                sim.clear()
                CareService.resetAll(app, startAgain = true)
                awaitAgent(old)?.let { delay(1_500) }
            }
            SimAction.VisitDemoOn -> agent()?.let { it.setVisitOptIn(true); it.setDemoShortDwell(true) }
            is SimAction.OdometerAtLeast -> {
                // 주행거리는 줄일 수 없다. 콘솔 값(없으면 앱 값)이 기준보다 작을 때만 올린다
                val cur = vc.odometerKm() ?: agent()?.state?.value?.odometerKm ?: 0.0
                if (cur < a.km) VcCommand.Odometer(a.km).let { cmd -> if (!vc.apply(listOf(cmd))) simulateVc(cmd) }
            }
            is SimAction.OdometerAdd -> {
                val target = currentOdometer() + a.km
                val cmd = VcCommand.Odometer(target)
                if (!vc.apply(listOf(cmd))) simulateVc(cmd)
            }
            is SimAction.Wait -> {
                for (left in a.seconds downTo 1) { onCountdown(left); delay(1_000) }
                onCountdown(null)
            }
        }
    }

    /** 지금 주행거리: 콘솔 값이 우선(앱 값은 늦을 수 있음), 없으면 앱 값, 기본 최소값 이상 */
    private suspend fun currentOdometer(): Double =
        maxOf(vc.odometerKm() ?: agent()?.state?.value?.odometerKm ?: 0.0, DemoScript.BASE_ODOMETER_KM)

    private suspend fun replay() { sim.replay().forEach { applySim(it, record = false) } }

    /** Virtual Console에 닿지 않을 때 같은 값을 앱 안 시뮬레이션 신호로 넣는다(속도·문·프렁크는 앱이 쓰지 않아 넣지 않음) */
    private fun simulateVc(c: VcCommand) {
        val now = Instant.now()
        val raw = when (c) {
            is VcCommand.Gear -> RawVehicleSignal(SignalType.GEAR, DataSource.SIMULATED_REPLAY, now, now,
                gear = when (c.g) { "P" -> Gear.PARK; "R" -> Gear.REVERSE; "N" -> Gear.NEUTRAL; else -> Gear.DRIVE })
            is VcCommand.Telltale -> TelltaleKind.entries.firstOrNull { it.name == c.type.uppercase() }?.let {
                RawVehicleSignal(SignalType.TELLTALE, DataSource.SIMULATED_REPLAY, now, now, it, if (c.on) TelltaleState.ON else TelltaleState.OFF)
            }
            is VcCommand.RangeM -> RawVehicleSignal(SignalType.RANGE_REMAINING, DataSource.SIMULATED_REPLAY, now, now, value = c.m / 1000.0, unit = "km")
                .also { sim.record(SimAction.Range(c.m / 1000.0)) }
            is VcCommand.Soc -> RawVehicleSignal(SignalType.EV_SOC, DataSource.SIMULATED_REPLAY, now, now, value = c.pct.toDouble(), unit = "%")
            is VcCommand.Odometer -> RawVehicleSignal(SignalType.ODOMETER, DataSource.SIMULATED_REPLAY, now, now, value = c.km, unit = "km")
            is VcCommand.Speed, is VcCommand.Frunk, is VcCommand.DoorsLocked, is VcCommand.BatteryKwh -> null
        }
        raw?.let { agent()?.submit(it) }
    }

    /** 단계의 화면을 앞으로 가져온다. 떠 있는 창 권한이 있으면 백그라운드에서도 화면을 열 수 있다 */
    private fun show(screen: StepScreen) {
        val intent = when (screen) {
            StepScreen.NAV -> Intent().setComponent(ComponentName(NAV_PKG, "$NAV_PKG.MainActivity"))
            StepScreen.APP -> Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            StepScreen.KEEP -> return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Log.w(TAG, "show $screen: $it") }
    }

    companion object {
        private const val TAG = "CareDemo"
        const val NAV_PKG = "ai.umos.maps.android.navigation.app"
        private const val VC_SETTLE_MS = 1_200L
        private const val VC_AFTER_MS = 1_500L
        /** 새 에이전트가 시작 값(SDK)을 읽을 시간 */
        private const val AGENT_INIT_MS = 1_000L
        private const val GEAR_WAIT_MS = 5_000L
    }
}
