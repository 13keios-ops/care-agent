package app.carecare.domain

import java.time.Instant

/**
 * 상황별 화면 데이터. 도메인이 값을 정하고 화면은 그리기만 한다.
 * 값의 출처 표시는 [basisLabel] 같은 문구로 넘긴다. 시연 데이터를 실제 값처럼 보이지 않게 한다.
 */
sealed interface SituationVisual {
    /** 바퀴별 공기압(kPa, 화면은 psi로 표시), 경고 수준으로 낮은 바퀴, 기준값(권장 공기압), 관리가 필요한(경고 전) 바퀴 */
    data class Tires(val kpa: Map<String, Double>, val low: Set<String>, val referenceKpa: Double?, val advise: Set<String> = emptySet()) : SituationVisual

    /** 잔량·주행가능거리와, 가려는 곳까지 갈 수 있는지 */
    data class Energy(
        val ev: Boolean,
        val percent: Int?,
        val rangeKm: Double?,
        val targetName: String?,
        val targetKm: Double?,
        val enough: Boolean?,
        val basisLabel: String?,
    ) : SituationVisual

    /** 찾아가는 충전: 만날 휴게소, 거리, 도착 예정(접수 뒤), 서비스 조건 */
    data class MobileCharge(val restAreaName: String, val distanceKm: Double?, val eta: Instant?, val terms: String) : SituationVisual

    /** 정비소 방문: 장소와 체류 시간 */
    data class Visit(val placeName: String, val dwellMinutes: Long) : SituationVisual

    /** 교환 시기: 지난 교환 주행거리 → 현재 → 교환 시점 */
    data class Due(val items: List<ItemCode>, val lastServiceKm: Double?, val currentKm: Double?, val dueKm: Double?, val oem: Boolean) : SituationVisual

    /** 정비소 예약: 정비소, 예약 시간, 품목. [confirmed]면 예약 요청이 접수됨 */
    data class Booking(val shopName: String, val distanceKm: Double?, val slot: java.time.LocalDateTime, val items: List<ItemCode>, val confirmed: Boolean) : SituationVisual
}
