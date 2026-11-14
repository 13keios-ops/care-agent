@file:UseSerializers(InstantIso::class, LocalDateIso::class, DurationMillis::class)

package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Instant

/** 정비사에게 보여줄 인계 카드. 외부 공유는 별도 승인 대상이다 (7.1). */
@Serializable
data class ServiceHandoffCard(
    val incidentId: String,
    val dataSourceLabel: String,
    val signalSummary: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val actionSummary: String,
    val sensorCleared: Boolean,
    val sharedAt: Instant? = null,
) {
    companion object {
        fun from(incident: Incident, actionSummary: String) = ServiceHandoffCard(
            incidentId = incident.id,
            dataSourceLabel = incident.source.label,
            signalSummary = "${incident.decision.situation?.label ?: incident.fingerprint} (신호 ${incident.signalCount}회)",
            firstSeen = incident.firstSeen, lastSeen = incident.lastSeen,
            actionSummary = actionSummary, sensorCleared = incident.sensorCleared,
        )
    }
}

/** 제품이 정한 고정 문구만 읽는다 (6.3). 생성형 문구 없음. */
object ReviewedMessages {
    private val texts = mapOf(
        "TIRE_WARNING_ACK" to "타이어 공기압 경고등이 켜졌습니다. 공기압이 낮아졌을 수 있어요. 가까운 정비소를 찾아 드릴까요?",
        "GENERIC_ACK" to "차량 경고 신호를 받았습니다. 계기판 안내와 차량 매뉴얼을 확인해 주세요.",
        "UNSUPPORTED_SIGNAL" to "이 신호는 지원되지 않아 판단하지 않습니다. 계기판 안내를 확인해 주세요.",
        "STALE_OR_UNKNOWN" to "차량 신호 상태를 확인할 수 없습니다. 계기판 안내를 확인해 주세요.",
        "VISIT_ASK" to "최근 차량 정비를 받으셨나요? 받으셨다면 어떤 정비였는지 말씀해 주세요.",
        "NAV_CONFIRM" to "선택하신 정비소로 목적지를 설정할까요?",
    )
    fun text(id: String): String = texts[id] ?: texts.getValue("GENERIC_ACK")
}
