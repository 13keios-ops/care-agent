package app.carecare.app.demo

import app.carecare.domain.CommutePattern
import app.carecare.domain.GeoPoint
import app.carecare.domain.ItemCode
import app.carecare.domain.Powertrain
import app.carecare.domain.TripContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/*
 * 시연 패널 모델. 에뮬레이터 Virtual Console 명령과 앱 안 시뮬레이션 신호로 장면을 만든다.
 * 이 파일은 안드로이드에 의존하지 않는다(단위 테스트 대상).
 */

/** 단계 실행 뒤 앞에 보일 화면 */
enum class StepScreen { NAV, APP, KEEP }

/** Virtual Console(에뮬레이터 안 127.0.0.1:8088) 명령. 값은 순정 패널과 앱에 '에뮬레이터 가상 신호'로 들어간다 */
sealed interface VcCommand {
    val path: String

    data class Gear(val g: String) : VcCommand {
        override val path get() = "/vehicle/gear?gear=" + mapOf("P" to "Park", "R" to "Reverse", "N" to "Neutral", "D" to "Drive").getValue(g)
    }
    data class Speed(val kmh: Int) : VcCommand { override val path get() = "/vehicle/controls?speed=$kmh" }
    data class Telltale(val type: String, val on: Boolean) : VcCommand {
        override val path get() = "/vehicle/telltales/simulator?type=$type&state=${if (on) "On" else "Off"}&color=Red"
    }
    data class Frunk(val closed: Boolean) : VcCommand { override val path get() = "/vehicle/body?frunk=${!closed}" }
    data class DoorsLocked(val locked: Boolean) : VcCommand { override val path get() = "/vehicle/body?doors_locked=$locked" }
    data class RangeM(val m: Int) : VcCommand { override val path get() = "/vehicle/odometer?remaining_range_m=$m" }
    /** 누적 주행거리는 늘리기만 할 수 있다(F-07) */
    data class Odometer(val km: Double) : VcCommand { override val path get() = "/vehicle/odometer?traveled_distance_km=" + String.format(Locale.US, "%.1f", km) }
    data class Soc(val pct: Int) : VcCommand { override val path get() = "/vehicle/ev-battery?field=soc&value=$pct" }
    /** 배터리 에너지(kWh). 내비는 이 값으로 도착 시 잔량을 계산하므로 SOC와 함께 바꾼다 */
    data class BatteryKwh(val kwh: Double) : VcCommand { override val path get() = "/vehicle/ev-battery?field=battery_level&value=" + String.format(Locale.US, "%.1f", kwh) }
}

/** 앱 안에서 넣는 시뮬레이션 신호와 시연 조작. 에뮬레이터가 전달하지 못하는 값(바퀴별 공기압, 연료, 위치)도 여기로 넣는다 */
sealed interface SimAction {
    /** wheel: LEFT_FRONT … 또는 ALL */
    data class Tire(val wheel: String, val kpa: Double) : SimAction
    data class Fuel(val pct: Int) : SimAction
    /** Virtual Console에 닿지 않을 때만 쓰는 주행가능거리 */
    data class Range(val km: Double) : SimAction
    data class Trip(val trip: TripContext?) : SimAction
    /** 평소 귀가 패턴과 시연 시각(가장 최근 평일의 [weekdayTime]) */
    data class Commute(val pattern: CommutePattern?, val weekdayTime: LocalTime?) : SimAction
    /**
     * 시연 위치. [agoSeconds]가 있으면 그만큼 앞선 시각의 위치로 바로 넣는다(체험용 데이터).
     * 정비소 체류를 실제 시간으로 기다리지 않게 한다
     */
    data class Location(val point: GeoPoint?, val agoSeconds: Long = 0) : SimAction
    data class SetPowertrain(val p: Powertrain) : SimAction
    data class SeedRecord(val items: List<ItemCode>) : SimAction
    data object ResetData : SimAction
    /** 정비소 방문 감지 동의 + 시연 체류 기준(1분) */
    data object VisitDemoOn : SimAction
    /** 주행거리가 [km]보다 적을 때만 [km]로 올린다(누적 주행거리는 줄일 수 없음) */
    data class OdometerAtLeast(val km: Double) : SimAction
    /** 지금 주행거리에 더하기(Virtual Console 명령으로 바꿔 넣는다) */
    data class OdometerAdd(val km: Double) : SimAction
    /** 기다리기(패널에 남은 시간 표시) */
    data class Wait(val seconds: Int) : SimAction
}

data class DemoStep(
    val id: String,
    val label: String,
    val vc: List<VcCommand> = emptyList(),
    val sim: List<SimAction> = emptyList(),
    val screen: StepScreen = StepScreen.KEEP,
    /** 앞 신호가 앱에 들어간 뒤(1.5초) 따로 보내는 Virtual Console 명령. 같은 요청 안의 순서는 전달 순서를 보장하지 않는다 */
    val vcAfter: List<VcCommand> = emptyList(),
    /** 에이전트가 말을 거는 단계. 장 진행은 이 단계에서만 대화를 기다린다 */
    val talks: Boolean = false,
) {
    /**
     * 실행 순서: 준비 조작(초기화·동력 방식·방문 감지) → 위치·바퀴 공기압(도착이 정차보다, 바퀴 값이 경고등보다 먼저)
     * → Virtual Console → 나머지 시뮬레이션 신호(VC 값이 앱에 들어간 뒤)
     */
    data class Phases(val before: List<SimAction>, val vc: List<VcCommand>, val after: List<SimAction>)

    fun phases(): Phases {
        // 준비 조작(초기화·동력 방식·방문 감지)은 맨 앞: 서비스가 다시 시작돼도 그 뒤 신호가 새 에이전트로 간다
        val prep = sim.filter { it is SimAction.ResetData || it is SimAction.SetPowertrain || it is SimAction.VisitDemoOn }
        val (early, rest) = (sim - prep.toSet()).partition { it is SimAction.Location || it is SimAction.Tire }
        return Phases(prep + early, vc, rest)
    }
}

data class DemoChapter(val id: String, val title: String, val note: String, val steps: List<DemoStep>)

object DemoTime {
    /** [now] 기준 가장 최근 평일의 [t] 시각. 오늘이 평일이고 [t]가 지났으면 오늘 */
    fun lastWeekdayAt(now: ZonedDateTime, t: LocalTime): ZonedDateTime {
        var d = now.with(t)
        if (d.isAfter(now)) d = d.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }
}
