package app.carecare.domain

import kotlinx.serialization.Serializable

import java.time.LocalDate

/** 주기 출처. 개발자가 임의 수치를 만들어 넣지 않는다 (22 변경 관리 4). 제조사 값은 출처 문서를 함께 둔다. */
enum class ScheduleSource { OEM_DOCUMENT, USER_DEFINED }

@Serializable
data class ServiceInterval(
    val itemCode: ItemCode,
    val vehicleScope: Set<Powertrain>,
    val intervalKm: Double?,
    val intervalDays: Long?,
    val scheduleSource: ScheduleSource,
    val sourceDoc: String?,
) {
    init {
        require(intervalKm != null || intervalDays != null)
        if (scheduleSource == ScheduleSource.OEM_DOCUMENT) requireNotNull(sourceDoc) { "OEM 주기는 출처 문서가 필요합니다" }
    }
}

/**
 * 제조사 권장 교환 주기 (통상 조건). 사용자가 직접 넣은 주기가 있으면 그 값이 우선한다.
 * 출처: 현대자동차 오너스 매뉴얼(ownersmanual.hyundai.com) 2026년형 '정기 점검 주기 > 통상 조건', 확인.
 * - 교체(●) 주기가 있는 품목만 넣는다. 점검(○)만 있는 품목(타이어 교체, 브레이크 패드, 12V 배터리)은 넣지 않는다.
 * - 냉각수는 '최초 20만 km 또는 10년, 이후 4만 km 또는 2년'이다. 계산은 마지막 교체 기록 기준이므로 이후 주기를 쓴다.
 * - 국내판 PHEV 설명서가 없어 PHEV는 같은 하이브리드 계열(투싼 하이브리드) 값을 쓴다.
 * - 가혹 조건(짧은 거리 반복, 정체 구간 등)은 엔진오일 5,000 km 또는 6개월이다. 이 경우 사용자가 설정에서 바꾼다.
 */
object RecommendedIntervals {
    const val ICE_DOC = "현대자동차 오너스 매뉴얼 투싼(NX4) 2026 정기 점검 주기·통상 조건"
    const val HYBRID_DOC = "현대자동차 오너스 매뉴얼 투싼 하이브리드(NX4HEV) 2026 정기 점검 주기·통상 조건 (PHEV는 같은 하이브리드 계열 기준)"
    const val EV_DOC = "현대자동차 오너스 매뉴얼 아이오닉 5(NE1) 2026 정기 점검 주기·통상 조건"

    private val ice = setOf(Powertrain.ICE, Powertrain.UNKNOWN)
    private val hybrid = setOf(Powertrain.HEV, Powertrain.PHEV)
    private val ev = setOf(Powertrain.EV)

    private fun oem(code: ItemCode, scope: Set<Powertrain>, km: Double?, days: Long?, doc: String) =
        ServiceInterval(code, scope, km, days, ScheduleSource.OEM_DOCUMENT, doc)

    val all: List<ServiceInterval> = buildList {
        for ((scope, doc) in listOf(ice to ICE_DOC, hybrid to HYBRID_DOC)) {
            add(oem(ItemCode.ENGINE_OIL, scope, 10_000.0, 365, doc))
            add(oem(ItemCode.OIL_FILTER, scope, 10_000.0, 365, doc))
            add(oem(ItemCode.AIR_FILTER, scope, 40_000.0, null, doc))
        }
        for ((scope, doc) in listOf(ice to ICE_DOC, hybrid to HYBRID_DOC, ev to EV_DOC)) {
            add(oem(ItemCode.TIRE_ROTATION, scope, 10_000.0, null, doc))
            add(oem(ItemCode.BRAKE_FLUID, scope, 50_000.0, null, doc))
            add(oem(ItemCode.COOLANT, scope, 40_000.0, 730, doc))
        }
        // 가솔린 설명서는 공조 필터를 '12개월 점검'으로만 적어 교체 주기를 넣지 않는다
        add(oem(ItemCode.CABIN_FILTER, hybrid, null, 365, HYBRID_DOC))
        add(oem(ItemCode.CABIN_FILTER, ev, null, 365, EV_DOC))
    }

    fun find(code: ItemCode, powertrain: Powertrain): ServiceInterval? =
        all.firstOrNull { it.itemCode == code && powertrain in it.vehicleScope }
}

/** DUE_SOON: 교환 시점 전 미리 알림 구간(남은 거리·날수가 기준 이하) */
enum class ReminderStatus { NOT_DUE, DUE_SOON, DUE, HELD_INVALID_ODOMETER, NO_INTERVAL }

data class Reminder(
    val recordId: String,
    val itemCode: ItemCode,
    val dueDate: LocalDate?,
    val dueOdometerKm: Double?,
    val status: ReminderStatus,
    val scheduleSource: ScheduleSource?,
    val note: String? = null,
)

/**
 * [soonKm]·[soonDays]: 교환 시점까지 이만큼 남으면 미리 알린다.
 * 제품 기본값이며 안전 기준이 아니다
 */
class IntervalCalculator(private val intervals: List<ServiceInterval>, private val soonKm: Double = 1_000.0, private val soonDays: Long = 30) {

    /** 품목별 최신 확정 기록 기준으로 다음 시점을 계산한다. 기록을 수정·삭제하면 다시 부르면 된다 (UT-18). */
    fun reminders(records: List<MaintenanceRecord>, vehicle: VehicleProfile, today: LocalDate, currentOdoKm: Double?): List<Reminder> {
        val latest = records.flatMap { r -> r.items.map { it.code to r } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rs) -> rs.maxBy { it.serviceDate } }
        return latest.map { (code, r) -> reminderFor(code, r, vehicle, today, currentOdoKm) }
    }

    private fun reminderFor(code: ItemCode, r: MaintenanceRecord, vehicle: VehicleProfile, today: LocalDate, odo: Double?): Reminder {
        // 사용자 지정 주기가 있으면 우선, 없으면 OEM 문서 주기
        val iv = intervals.filter { it.itemCode == code && vehicle.powertrain in it.vehicleScope }
            .sortedBy { if (it.scheduleSource == ScheduleSource.USER_DEFINED) 0 else 1 }.firstOrNull()
            ?: return Reminder(r.id, code, null, null, ReminderStatus.NO_INTERVAL, null, "검증된 주기 없음. 사용자 지정 필요")
        if (vehicle.odometerUnit != DistanceUnit.KM) return Reminder(r.id, code, null, null, ReminderStatus.HELD_INVALID_ODOMETER, iv.scheduleSource, "단위 불일치")
        if (odo != null && r.odometerAtServiceKm != null && odo < r.odometerAtServiceKm)
            return Reminder(r.id, code, null, null, ReminderStatus.HELD_INVALID_ODOMETER, iv.scheduleSource, "주행거리 역행. 수정 필요") // UT-19
        val dueDate = iv.intervalDays?.let { r.serviceDate.plusDays(it) }
        val dueOdo = if (iv.intervalKm != null && r.odometerAtServiceKm != null) r.odometerAtServiceKm + iv.intervalKm else null
        val due = (dueDate != null && !today.isBefore(dueDate)) || (dueOdo != null && odo != null && odo >= dueOdo)
        val soon = !due && ((dueDate != null && !today.plusDays(soonDays).isBefore(dueDate)) || (dueOdo != null && odo != null && odo >= dueOdo - soonKm))
        val status = when { due -> ReminderStatus.DUE; soon -> ReminderStatus.DUE_SOON; else -> ReminderStatus.NOT_DUE }
        return Reminder(r.id, code, dueDate, dueOdo, status, iv.scheduleSource)
    }
}
