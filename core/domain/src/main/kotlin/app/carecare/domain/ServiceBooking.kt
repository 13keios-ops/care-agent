package app.carecare.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 정비소 예약 포트.
 * 정비소 예약 공개 API가 없어 이 앱은 실제로 접수하지 않는다. 시연에서는 [DemoServiceBookingService]의 모의 응답을 쓴다.
 * 출시 단계에서 제휴·공식 예약 시스템이 생기면 이 포트의 구현만 바꾼다.
 */

data class BookingRequest(val shop: CarePlace, val slot: LocalDateTime, val items: List<ItemCode>)

sealed interface BookingResult {
    /** 예약 요청 접수. [simulated]면 시연용 모의 응답이다 */
    data class Accepted(val slot: LocalDateTime, val simulated: Boolean) : BookingResult
    data class Rejected(val reason: String) : BookingResult
}

interface ServiceBookingService {
    /** 정비소가 받을 수 있는 예약 시간 후보(가까운 순) */
    fun availableSlots(shop: CarePlace, from: LocalDate): List<LocalDateTime>
    suspend fun request(req: BookingRequest): BookingResult
}

/** 시연용: 다음 날부터 일요일을 뺀 오전 10시·오후 2시를 후보로 주고, 요청은 그대로 받아 준다. 실제 접수가 아니다 */
class DemoServiceBookingService : ServiceBookingService {
    override fun availableSlots(shop: CarePlace, from: LocalDate): List<LocalDateTime> =
        generateSequence(from.plusDays(1)) { it.plusDays(1) }.filter { it.dayOfWeek != DayOfWeek.SUNDAY }
            .flatMap { d -> sequenceOf(d.atTime(LocalTime.of(10, 0)), d.atTime(LocalTime.of(14, 0))) }
            .take(4).toList()

    override suspend fun request(req: BookingRequest): BookingResult = BookingResult.Accepted(req.slot, simulated = true)
}

object BookingText {
    private val fmt = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
    /** "11월 14일 토요일 오전 10시" */
    fun slot(t: LocalDateTime): String {
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "${fmt.format(t)} $ampm ${h12}시" + if (t.minute != 0) " ${t.minute}분" else ""
    }
}
