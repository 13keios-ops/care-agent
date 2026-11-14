package app.carecare.domain

import java.time.Duration
import java.time.Instant

/*
 * 현대자동차 찾아가는 충전(전기차 긴급 충전) 요청 포트.
 * 서비스 조건: 전기차, 잔여 주행거리 60km 이하(초과 시 제공 안 됨),
 * 고속도로 1회 10kWh·일반도로 7kWh 무상, 연 10회, 구매 후 5년. 신청은 고객센터 080-600-6000·myHyundai·블루링크 SOS.
 * 고객센터 공개 API가 없어 이 앱은 실제로 접수하지 않는다. 시연에서는 [DemoMobileChargeService]의 모의 응답을 쓴다.
 */

/** 요청에 담는 내용: 만날 휴게소, 배터리 잔량, 주행가능거리 */
data class MobileChargeRequest(
    val restArea: CarePlace,
    val socPercent: Int?,
    val rangeKm: Double?,
    val requestedAt: Instant,
)

sealed interface MobileChargeResult {
    /** 접수됨. [simulated]면 시연용 모의 응답이다 */
    data class Accepted(val eta: Instant, val simulated: Boolean) : MobileChargeResult
    data class Rejected(val reason: String) : MobileChargeResult
}

interface MobileChargeService {
    suspend fun request(req: MobileChargeRequest): MobileChargeResult
}

/** 시연용 모의 응답: 항목을 받으면 [lead] 뒤 도착으로 답한다. 실제 접수가 아니다 */
class DemoMobileChargeService(private val clock: Clock, private val lead: Duration = Duration.ofMinutes(25)) : MobileChargeService {
    override suspend fun request(req: MobileChargeRequest): MobileChargeResult =
        MobileChargeResult.Accepted(clock.now().plus(lead), simulated = true)
}

object MobileChargeTerms {
    /** 이 값보다 주행가능거리가 많이 남으면 서비스 대상이 아니다 */
    const val MAX_RANGE_KM = 60.0
    const val SUMMARY = "고속도로 1회 10kWh 무상 · 연 10회 · 잔여 60km 이하"
}
