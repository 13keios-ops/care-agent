package app.carecare.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 상황 카탈로그.
 * 모든 상황은 같은 루프를 탄다: 상태 감지 → 차가 먼저 설명 → 해결 가이드·대안 제시 → 승인 → 해결 액션 → 기록·후속 관리.
 * 새 상황을 더하려면 SituationKind와 Playbooks에 항목을 추가한다.
 */

/** priority가 낮을수록 먼저 묻는다. waitForPark면 차가 P단에 있을 때만 묻는다 (주행 중 복잡한 대화 금지, FR-015). */
@Serializable
enum class SituationKind(val label: String, val priority: Int, val waitForPark: Boolean) {
    /** 바퀴 둘 이상이 함께 낮아짐 (SC-B). 가장 먼저 다룬다 */
    TIRE_MULTI_LOW("타이어 여러 개 공기압 저하", -1, false),
    TIRE_WARNING("타이어 공기압 경고", 0, false),
    /** 경고등이 켜지기 전, 권장 공기압보다 낮아짐(선제 안내). 경고 단계가 되면 경고가 덮는다 */
    TIRE_PRESSURE_LOW("타이어 공기압 관리", 0, false),
    LOW_FUEL("연료 부족", 1, false),
    LOW_EV_BATTERY("배터리 부족", 1, false),
    /** 이동 맥락의 목적지까지 주행가능거리가 부족하거나 빠듯함 (2-B, 2-C) */
    RANGE_SHORT("목적지까지 주행가능거리 부족", 1, false),
    ISSUE_FOLLOW_UP("조치 확인", 2, true),
    MAINTENANCE_DUE("소모품 교환 시기", 3, true),
    SERVICE_VISIT("정비 기록", 4, true),
    ;

    /** 이 상황 안내가 [other] 안내를 덮는가. 덮이는 사건은 따로 묻지 않고 합친다 */
    fun covers(other: SituationKind): Boolean = when (this) {
        TIRE_MULTI_LOW -> other == TIRE_WARNING || other == TIRE_PRESSURE_LOW
        TIRE_WARNING -> other == TIRE_PRESSURE_LOW
        RANGE_SHORT -> other == LOW_EV_BATTERY || other == LOW_FUEL
        else -> false
    }
}

@Serializable
enum class PlaceCategory(val label: String) { SERVICE_SHOP("정비소"), FUEL_STATION("주유소"), EV_CHARGER("충전소"), REST_AREA("휴게소") }

/** 장소 후보. demo=true면 실제 업체가 아닌 시연용 데이터다 (D-06, FR-019). */
@Serializable
data class CarePlace(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val demo: Boolean,
    /** 지역 이름(예: 성남). 운전자가 지역을 말해 정비소를 찾을 때 쓴다 */
    val area: String? = null,
) {
    fun toNavDestination() = NavDestination(id, name, latitude, longitude)
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 이동 맥락: 지금 어디로 가는 길인지. 내비 목적지 정보는 에뮬레이터에서 빈 값이라
 * 시연에서는 시연 데이터나 평소 패턴으로 넣고 [basis]에 그 근거를 적는다.
 */
data class TripContext(
    val destinationName: String,
    val remainingKm: Double,
    val highway: Boolean,
    val source: DataSource,
    val basis: String = "",
    /** 경유지 경로를 만들 때 쓰는 목적지 좌표. 모르면 null(경유 대신 들를 곳으로만 안내) */
    val destination: NavDestination? = null,
)

/** 평소 귀가 패턴(학습 결과를 대신하는 시연 데이터): 평일 퇴근 시간대에 내비 목적지가 없으면 집으로 가는 길로 본다 */
data class CommutePattern(
    val homeName: String,
    val homeKm: Double,
    val weekdays: Set<java.time.DayOfWeek>,
    val from: java.time.LocalTime,
    val to: java.time.LocalTime,
    val destination: NavDestination? = null,
    val source: DataSource = DataSource.SIMULATED_REPLAY,
) { companion object { const val BASIS = "평일 퇴근 시간 · 평소 귀가 패턴" } }

/** 위치 SDK 원본 1건. 좌표는 메모리에서만 쓰고 저장하지 않는다 (9.5, 21.1). */
data class LocationFix(val point: GeoPoint, val accuracyMeters: Float?, val timestamp: Instant, val source: DataSource)

data class PlaceDistance(val place: CarePlace, val distanceKm: Double?)

interface PlaceCatalog {
    fun all(): List<CarePlace>
    fun byId(id: String): CarePlace? = all().firstOrNull { it.id == id }
    /** 운전자가 평소 가는 곳(학습된 패턴). 모르면 null */
    fun usualId(category: PlaceCategory): String? = null
    /** 운전자의 집(학습된 패턴 또는 사용자 등록). 모르면 null */
    fun home(): GeoPoint? = null
    /** 정비소를 찾을 수 있는 지역 이름 */
    fun areas(): List<String> = all().filter { it.category == PlaceCategory.SERVICE_SHOP }.mapNotNull { it.area }.distinct()
    /** 현재 위치를 모르면 카탈로그 순서대로 주고 거리는 null로 둔다. 추측 거리를 만들지 않는다. */
    fun nearest(category: PlaceCategory, from: GeoPoint?, limit: Int = 3): List<PlaceDistance> {
        val list = all().filter { it.category == category }
        if (from == null) return list.take(limit).map { PlaceDistance(it, null) }
        return list.map { PlaceDistance(it, Geo.km(from, GeoPoint(it.latitude, it.longitude))) }
            .sortedBy { it.distanceKm }.take(limit)
    }
}

/**
 * 시연용 장소. 실제 업체·주소가 아니다. 에뮬레이터 위치를 이 좌표 근처로 두고 시연한다
 * (docs/demo-script.md의 geo fix 좌표 참고).
 */
object DemoPlaceCatalog : PlaceCatalog {
    /**
     * 시연 출발점. 장소가 아니라 에뮬레이터 위치를 둘 기준점이다.
     * Pleos 에뮬레이터 내비의 기본 위치(경기 성남, navi_engine 로그 org 값)와 맞췄다.
     */
    val DEMO_START = GeoPoint(37.4127, 127.0947)

    /** 평소 가는 주유소(학습된 패턴을 대신하는 시연 데이터). 출발점에서 약 12km */
    const val USUAL_FUEL_ID = "DEMO_FUEL_C"

    /** 집(평소 귀가 패턴의 목적지, 시연 데이터). 출발점에서 북쪽 약 40km.
     *  체험 장소는 출발점 북쪽에 둔다: 내비 경로 미리보기에서 경로가 위로 뻗게 */
    val DEMO_HOME = NavDestination("DEMO_HOME", "집", 37.7654, 127.0600)

    private val places = listOf(
        CarePlace("DEMO_SHOP_A", "데모 정비소 A", PlaceCategory.SERVICE_SHOP, 37.4217, 127.0960, "데모 주소 (시뮬레이션)", true, area = "성남"),
        CarePlace("DEMO_SHOP_B", "데모 정비소 B", PlaceCategory.SERVICE_SHOP, 37.4000, 127.0800, "데모 주소 (시뮬레이션)", true, area = "성남"),
        // 집 근처 정비소(정비소 예약에서 '집 근처'를 고를 때). 집에서 약 1~2km
        CarePlace("DEMO_SHOP_C", "데모 정비소 C", PlaceCategory.SERVICE_SHOP, 37.7564, 127.0620, "데모 주소 (시뮬레이션)", true),
        CarePlace("DEMO_SHOP_D", "데모 정비소 D", PlaceCategory.SERVICE_SHOP, 37.7774, 127.0510, "데모 주소 (시뮬레이션)", true),
        // 운전자가 지역 이름("수원")을 말할 때 찾는 정비소(체험용 장소)
        CarePlace("DEMO_SHOP_E", "데모 정비소 E", PlaceCategory.SERVICE_SHOP, 37.2636, 127.0286, "데모 주소 (시뮬레이션)", true, area = "수원"),
        CarePlace("DEMO_SHOP_F", "데모 정비소 F", PlaceCategory.SERVICE_SHOP, 37.2790, 127.0150, "데모 주소 (시뮬레이션)", true, area = "수원"),
        CarePlace("DEMO_FUEL_A", "데모 주유소 A", PlaceCategory.FUEL_STATION, 37.4170, 127.1030, "데모 주소 (시뮬레이션)", true),
        CarePlace("DEMO_FUEL_B", "데모 주유소 B", PlaceCategory.FUEL_STATION, 37.4194, 127.0870, "데모 주소 (시뮬레이션)", true),
        CarePlace("DEMO_EV_A", "데모 충전소 A", PlaceCategory.EV_CHARGER, 37.4180, 127.0900, "데모 주소 (시뮬레이션)", true),
        CarePlace("DEMO_EV_B", "데모 충전소 B", PlaceCategory.EV_CHARGER, 37.4204, 127.1050, "데모 주소 (시뮬레이션)", true),
        CarePlace(USUAL_FUEL_ID, "데모 주유소 C", PlaceCategory.FUEL_STATION, 37.5208, 127.0947, "데모 주소 (시뮬레이션)", true),
        // 고속도로 휴게소 자리(좌표는 에뮬레이터 내비 경로로 확인 예정, Task 7)
        CarePlace("DEMO_REST_A", "데모 휴게소", PlaceCategory.REST_AREA, 37.3230, 127.1080, "데모 주소 (시뮬레이션)", true),
    )
    override fun all() = places
    override fun usualId(category: PlaceCategory) = if (category == PlaceCategory.FUEL_STATION) USUAL_FUEL_ID else null
    override fun home() = GeoPoint(DEMO_HOME.latitude, DEMO_HOME.longitude)
}

object Geo {
    fun km(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

/**
 * 위치 원본을 정비소 진입·체류·이탈 근거로 바꾼다. 근거만 만들고 방문을 확정하지 않는다 (9.1).
 * 정비소 좌표 반경 밖의 일반 주행 경로는 아무것도 남기지 않는다.
 */
class PlaceProximityTracker(private val catalog: PlaceCatalog, private val radiusMeters: Double = 120.0) {
    var insidePoiId: String? = null
        private set

    fun onFix(fix: LocationFix): List<LocationEvidence> {
        val shop = catalog.all().filter { it.category == PlaceCategory.SERVICE_SHOP }
            .map { it to Geo.km(fix.point, GeoPoint(it.latitude, it.longitude)) * 1000 }
            .filter { it.second <= radiusMeters }.minByOrNull { it.second }?.first
        val out = mutableListOf<LocationEvidence>()
        val cur = insidePoiId
        if (cur != null && shop?.id != cur) {
            out += LocationEvidence(fix.timestamp, cur, LocationEvidence.Kind.EXIT, fix.accuracyMeters, fix.source)
            insidePoiId = null
        }
        if (shop != null) {
            val kind = if (insidePoiId == null) LocationEvidence.Kind.ENTER else LocationEvidence.Kind.DWELL_SAMPLE
            insidePoiId = shop.id
            out += LocationEvidence(fix.timestamp, shop.id, kind, fix.accuracyMeters, fix.source)
        }
        return out
    }

    /** 정비소 반경 안에서 P단이 되면 주차 근거를 낸다 */
    fun onParked(at: Instant, accuracyMeters: Float?, source: DataSource): LocationEvidence? =
        insidePoiId?.let { LocationEvidence(at, it, LocationEvidence.Kind.PARKED, accuracyMeters, source) }
}

/** 사용자가 고를 수 있는 대안 */
@Serializable
enum class OptionKind {
    FIND_PLACE, VIA_PLACE, MOBILE_CHARGE, ALREADY_DONE, TELL_SERVICE, NOT_SERVICED, CONFIRM_ACTION_TAKEN, REMIND_LATER, DISMISS, NEVER_ASK, SHOW_ROADSIDE,
    /** 정비소 예약(교환 시기). 예약 확인 단계에서는 '이대로 예약' */
    BOOK_SERVICE,
    /** 예약 확인 단계: 다른 정비소 / 다른 시간 */
    OTHER_PLACE, OTHER_TIME,
    /** 정비소 예약 희망 지역: 집 근처 / 운전자가 말한 지역 이름(CareOption.value) */
    AREA_HOME, AREA_NAMED,
}

@Serializable
data class CareOption(
    val kind: OptionKind,
    val label: String,
    val category: PlaceCategory? = null,
    /** 정차(P단)일 때만 보여 준다. 주행 중 목적지 변경이 위험을 키울 수 있는 상황용 (SC-B) */
    val parkedOnly: Boolean = false,
    /** 같은 종류의 선택지를 구분하는 값(예: 지역 이름) */
    val value: String? = null,
)

/** 바퀴 하나의 공기압(kPa)과 권장 공기압 대비 감소율 */
@Serializable
data class TireReading(val wheel: String, val kpa: Double, val dropPercent: Int)

/** 상황별 설명 문구와 대안. 문구는 고정 템플릿이며 생성형 문구를 쓰지 않는다 (6.3). */
object Playbooks {
    private val dateFmt = DateTimeFormatter.ofPattern("M월 d일")

    /** 현대자동차 긴급봉사반. hyundai.com 긴급출동 안내(customer-service/car-management-service/quick-reaction-service), 확인 */
    const val ROADSIDE_TEXT = "현대자동차 긴급출동(긴급봉사반)은 080-600-6000이에요. 번호를 화면에 남겨 둘게요. 전화는 차를 세운 상태에서 직접 걸어 주세요."

    fun options(kind: SituationKind, powertrain: Powertrain = Powertrain.UNKNOWN): List<CareOption> = when (kind) {
        SituationKind.RANGE_SHORT -> listOf(
            if (powertrain == Powertrain.EV) CareOption(OptionKind.VIA_PLACE, "충전소 들렀다 가기", PlaceCategory.EV_CHARGER)
            else CareOption(OptionKind.VIA_PLACE, "주유소 들렀다 가기", PlaceCategory.FUEL_STATION),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.TIRE_MULTI_LOW -> listOf(
            CareOption(OptionKind.FIND_PLACE, "가까운 정비소 안내", PlaceCategory.SERVICE_SHOP, parkedOnly = true),
            CareOption(OptionKind.SHOW_ROADSIDE, "긴급출동 연락처 보기", parkedOnly = true),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.TIRE_PRESSURE_LOW -> listOf(
            CareOption(OptionKind.FIND_PLACE, "공기 넣을 정비소 안내", PlaceCategory.SERVICE_SHOP),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.TIRE_WARNING -> listOf(
            CareOption(OptionKind.FIND_PLACE, "가까운 정비소 안내", PlaceCategory.SERVICE_SHOP),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.LOW_FUEL -> listOf(
            CareOption(OptionKind.FIND_PLACE, "가까운 주유소 안내", PlaceCategory.FUEL_STATION),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.LOW_EV_BATTERY -> listOf(
            CareOption(OptionKind.FIND_PLACE, "가까운 충전소 안내", PlaceCategory.EV_CHARGER),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.MAINTENANCE_DUE -> listOf(
            CareOption(OptionKind.BOOK_SERVICE, "정비소 예약", PlaceCategory.SERVICE_SHOP),
            CareOption(OptionKind.ALREADY_DONE, "이미 교환했어요"),
            CareOption(OptionKind.REMIND_LATER, "나중에 다시 알림"),
            CareOption(OptionKind.DISMISS, "괜찮아요"),
        )
        SituationKind.SERVICE_VISIT -> listOf(
            CareOption(OptionKind.TELL_SERVICE, "네, 정비 받았어요"),
            CareOption(OptionKind.NOT_SERVICED, "안 받았어요"),
            CareOption(OptionKind.REMIND_LATER, "나중에"),
            CareOption(OptionKind.NEVER_ASK, "이곳에서는 묻지 않기"),
        )
        SituationKind.ISSUE_FOLLOW_UP -> listOf(
            CareOption(OptionKind.CONFIRM_ACTION_TAKEN, "네, 조치했어요"),
            CareOption(OptionKind.DISMISS, "아니요"),
        )
    }

    data class Context(
        val fuelPercent: Int? = null,
        val evSoc: Int? = null,
        val recurrenceOf: Instant? = null,
        val dueItems: List<ItemCode> = emptyList(),
        val kmSinceService: Double? = null,
        val placeName: String? = null,
        val unknownState: Boolean = false,
        /** 경고등이 아니라 잔량 값이 기준 이하로 내려가 생긴 상황 */
        val levelDerived: Boolean = false,
        /** 조치 확인 질문이 가리키는 원래 경고 */
        val followUpOf: SituationKind? = null,
        /** 바퀴별 공기압으로 판단한 경우의 낮은 바퀴들 */
        val tires: List<TireReading> = emptyList(),
        /** 공기압 값으로 생긴 사건인지 (경고등이 아니라) */
        val pressureDerived: Boolean = false,
        val parked: Boolean = true,
        /** 주행가능거리(km) */
        val rangeKm: Double? = null,
        /** 평소 가는 곳과 그곳까지 갈 수 있는지(여유 포함) */
        val usualPlace: PlaceDistance? = null,
        val usualReachable: Boolean? = null,
        /** 목적지까지 판단: 이동 맥락, 빠듯함(true)/부족(false), 전기차 여부 */
        val trip: TripContext? = null,
        val rangeTight: Boolean = false,
        val ev: Boolean = false,
        /** 찾아가는 충전을 받을 수 있을 때, 갈 수 있는 가장 가까운 휴게소 */
        val mobileChargeAt: PlaceDistance? = null,
        /** 차량 권장 타이어 공기압(psi) */
        val recommendedTirePsi: Double = 36.0,
        /** 교환 시기 전 미리 알림이면 교환 시점까지 남은 거리(km)·날수 */
        val dueSoon: Boolean = false,
        val kmToDue: Double? = null,
        val daysToDue: Long? = null,
    )

    fun explain(kind: SituationKind, c: Context, today: LocalDate): String {
        if (c.unknownState) return "${kind.label} 신호를 받았지만 지금 상태를 확인할 수 없어요. 계기판 안내를 확인해 주세요."
        val again = c.recurrenceOf?.let { " 지난 ${dateFmt.format(it.atZone(java.time.ZoneId.systemDefault()))}에도 같은 경고가 있었어요." } ?: ""
        return when (kind) {
            SituationKind.TIRE_PRESSURE_LOW -> {
                val rec = psiNum(c.recommendedTirePsi)
                val t = c.tires.firstOrNull()
                (if (c.tires.size <= 1 && t != null) "${wheelName(t.wheel)} 타이어 공기압이 ${psiOf(t.kpa)}로 권장 ${rec}psi보다 낮아요."
                else "타이어 ${countWord(c.tires.size)} 개의 공기압이 권장 ${rec}psi보다 낮아요. " + c.tires.joinToString(", ") { "${wheelName(it.wheel)} ${psiOf(it.kpa)}" } + "예요.") +
                    " 경고등이 켜지기 전에 공기압 관리가 필요해요. 가까운 정비소에서 공기를 넣을까요?"
            }
            SituationKind.TIRE_WARNING -> c.tires.singleOrNull()?.let { t ->
                "${wheelName(t.wheel)} 타이어 공기압이 ${psiOf(t.kpa)}까지 낮아졌어요. 권장 공기압 ${psiNum(c.recommendedTirePsi)}psi보다 ${t.dropPercent}퍼센트 낮아 점검이 필요해요." +
                    again + " 가까운 정비소로 안내해 드릴까요?"
            } ?: ((if (c.recurrenceOf != null) "타이어 공기압 경고등이 다시 켜졌어요." else "타이어 공기압 경고등이 켜졌어요.") +
                again + " 공기압이 낮아졌을 수 있어요. 가까운 정비소로 안내해 드릴까요?")
            SituationKind.TIRE_MULTI_LOW -> {
                val list = c.tires.joinToString(", ") { "${wheelName(it.wheel)} ${psiOf(it.kpa)}" }
                "타이어 ${countWord(c.tires.size)} 개의 공기압이 함께 낮아졌어요. ${list}예요." + again +
                    if (c.parked) " 직접 운전하기 어렵다면 긴급출동을 부를 수 있어요. 가까운 정비소 안내나 긴급출동 연락처가 필요하신가요?"
                    else " 안전한 곳에 차를 세운 뒤 타이어 상태를 확인해 주세요. 차를 세우면 정비소와 긴급출동 안내를 도와 드릴게요."
            }
            SituationKind.LOW_FUEL -> {
                val head = if (c.levelDerived) "연료가 ${c.fuelPercent ?: "얼마"}퍼센트 남았어요." else "연료 부족 경고등이 켜졌어요." + (c.fuelPercent?.let { " 남은 연료는 ${it}퍼센트예요." } ?: "")
                val u = c.usualPlace
                val reach = if (u != null && c.rangeKm != null && c.usualReachable != null) {
                    val r = "%.0f".format(c.rangeKm)
                    if (c.usualReachable) " 주행가능거리는 ${r}킬로미터라 ${distanceText(u.distanceKm)} 떨어진 평소 가시는 ${u.place.name}까지 충분해요. 평소 주유소로 안내할까요?"
                    else " 주행가능거리는 ${r}킬로미터라 평소 가시는 ${u.place.name}까지는 부족할 수 있어요. 가까운 주유소로 안내할까요?"
                } else null
                head + again + (reach ?: " 가까운 주유소로 안내해 드릴까요?")
            }
            SituationKind.LOW_EV_BATTERY ->
                (if (c.levelDerived) "배터리가 ${c.evSoc ?: "얼마"}퍼센트 남았어요." else "배터리 부족 경고등이 켜졌어요." + (c.evSoc?.let { " 남은 배터리는 ${it}퍼센트예요." } ?: "")) +
                    again + " 가까운 충전소로 안내해 드릴까요?"
            SituationKind.RANGE_SHORT -> {
                val t = c.trip
                val r = c.rangeKm
                val place = if (c.ev) "충전소" else "주유소"
                if (t == null || r == null) "목적지까지 주행가능거리가 부족할 수 있어요. 가는 길에 ${place}를 들렀다 갈까요?"
                else (if (t.basis == CommutePattern.BASIS) "퇴근 시간이라 ${t.destinationName}으로 가시는 걸로 보고 확인했어요. " else "") +
                    "${t.destinationName}까지 ${"%.0f".format(t.remainingKm)}킬로미터인데 주행가능거리는 ${"%.0f".format(r)}킬로미터" +
                    (if (c.rangeTight) "라 빠듯해요." else "예요. 이대로는 부족해요.") +
                    (c.mobileChargeAt?.let { m -> " ${distanceText(m.distanceKm)} 앞 ${m.place.name}에서 현대자동차 찾아가는 충전을 부를 수 있어요. 요청할까요?" }
                        ?: " 가는 길에 ${place}를 들렀다 갈까요?")
            }
            SituationKind.MAINTENANCE_DUE -> {
                val item = when (c.dueItems.size) {
                    0 -> "소모품 교환"
                    1 -> dueName(c.dueItems.single())
                    else -> joinAnd(c.dueItems.map { itemName(it).removeSuffix(" 교환") }) + " 교환"
                }
                val since = c.kmSinceService?.let { " 지난 교환 뒤 ${"%,.0f".format(it)}킬로미터를 달렸어요." } ?: ""
                if (c.dueSoon) {
                    val left = c.kmToDue?.takeIf { it > 0 }?.let { " 교환까지 약 ${"%,.0f".format(roundTo100(it))}킬로미터 남았어요." }
                        ?: c.daysToDue?.takeIf { it > 0 }?.let { " 교환까지 ${it}일 남았어요." } ?: ""
                    "$item 시기가 다가와요.$since$left 미리 정비소 예약을 도와 드릴까요?"
                } else "$item 시기가 됐어요.$since 정비소 예약을 도와 드릴까요?"
            }
            SituationKind.SERVICE_VISIT ->
                (c.placeName?.let { "${it}에 들르신 것 같아요." } ?: "최근에 정비소에 들르신 것 같아요.") + " 차량 정비를 받으셨나요?"
            SituationKind.ISSUE_FOLLOW_UP ->
                if (c.pressureDerived) "타이어 공기압이 다시 정상 범위로 돌아왔어요. 점검이나 조치를 받으셨나요?"
                else "${c.followUpOf?.label ?: "경고"}등이 꺼졌어요. 점검이나 조치를 받으셨나요?"
        }
    }

    /**
     * 다음 정차 때 다시 묻는 문장. 처음 설명을 그대로 반복하지 않고 짧게 이어서 묻는다
     */
    fun reask(kind: SituationKind, c: Context): String = "차를 세우셨네요. " + when (kind) {
        SituationKind.TIRE_MULTI_LOW -> "아까 말씀드린 타이어 ${countWord(maxOf(c.tires.size, 2))} 개의 공기압 저하, 가까운 정비소 안내나 긴급출동 연락처가 필요하신가요?"
        SituationKind.TIRE_WARNING, SituationKind.TIRE_PRESSURE_LOW -> "아까 말씀드린 타이어 공기압, 지금 가까운 정비소로 안내해 드릴까요?"
        SituationKind.MAINTENANCE_DUE -> "아까 말씀드린 교환 시기, 지금 정비소 예약을 도와 드릴까요?"
        else -> "아까 말씀드린 ${kind.label} 건, 지금 도와 드릴까요?"
    }

    /** "엔진오일 교환" → "엔진오일" 처럼 말하기 쉬운 이름 */
    fun itemName(code: ItemCode): String = when (code) {
        ItemCode.ENGINE_OIL -> "엔진오일"
        ItemCode.OIL_FILTER -> "오일 필터"
        ItemCode.TIRE -> "타이어"
        ItemCode.TIRE_ROTATION -> "타이어 위치 교환"
        ItemCode.AIR_FILTER -> "에어필터"
        ItemCode.CABIN_FILTER -> "에어컨 필터"
        ItemCode.BRAKE_PAD -> "브레이크 패드"
        ItemCode.BRAKE_FLUID -> "브레이크액"
        ItemCode.COOLANT -> "냉각수"
        ItemCode.BATTERY_12V -> "12볼트 배터리"
        ItemCode.OTHER -> "기타 정비"
    }

    /** 경고 사건과 정비 기록을 잇는 품목. 이 품목을 기록하면 같은 종류의 열린 사건에 연결 후보로 묻는다 */
    fun relatedItems(kind: SituationKind): Set<ItemCode> = when (kind) {
        SituationKind.TIRE_WARNING, SituationKind.TIRE_MULTI_LOW, SituationKind.TIRE_PRESSURE_LOW -> setOf(ItemCode.TIRE, ItemCode.TIRE_ROTATION)
        else -> emptySet()
    }

    fun wheelName(wheel: String): String = when (wheel) {
        "LEFT_FRONT" -> "앞 왼쪽"
        "RIGHT_FRONT" -> "앞 오른쪽"
        "LEFT_REAR" -> "뒤 왼쪽"
        "RIGHT_REAR" -> "뒤 오른쪽"
        else -> "한쪽"
    }

    /** 화면·음성용 공기압 "31psi" (음성은 SpeechText가 '피에스아이'로 읽는다) */
    fun psiOf(kpa: Double): String = TireUnits.psiText(kpa)
    private fun psiNum(psi: Double): Int = kotlin.math.round(psi).toInt()
    private fun roundTo100(km: Double) = kotlin.math.round(km / 100.0) * 100.0

    private fun countWord(n: Int) = when (n) { 2 -> "두"; 3 -> "세"; 4 -> "네"; else -> n.toString() }

    /** "엔진오일과 오일 필터"처럼 받침에 맞춰 잇는다 */
    fun joinAnd(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> {
            val prev = names[names.size - 2]
            val c = prev.lastOrNull()
            val batchim = c != null && c in '가'..'힣' && (c - '가') % 28 != 0
            names.dropLast(1).joinToString(", ") + (if (batchim) "과 " else "와 ") + names.last()
        }
    }

    /** "엔진오일 교환", "브레이크 패드 교체"처럼 교환 시기 안내에 쓰는 이름 */
    fun dueName(code: ItemCode): String = when {
        code.label.endsWith("교환") || code.label.endsWith("교체") -> code.label
        else -> "${code.label} 교체"
    }

    fun distanceText(km: Double?): String = when {
        km == null -> "거리 정보 없음"
        km < 1.0 -> "${(km * 1000).toInt() / 10 * 10}미터"
        km >= 10.0 -> "${"%.0f".format(km)}킬로미터"
        else -> "${"%.1f".format(km)}킬로미터"
    }
}
