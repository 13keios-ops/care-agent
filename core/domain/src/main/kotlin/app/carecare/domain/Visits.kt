@file:UseSerializers(InstantIso::class, LocalDateIso::class, DurationMillis::class)

package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 명세 9.2 기본 알고리즘 상태 */
enum class VisitState {
    ENTERED_ZONE, DWELLING, EXITED_ZONE, VISIT_CANDIDATE, POSTPONED_UNTIL_SAFE, PROMPTED,
    CONFIRMED, REJECTED, SUPPRESSED, DISCARDED,
}

@Serializable
data class VisitCandidate(
    val id: String,
    val visitSessionId: String,
    val poiId: String,
    val source: DataSource,
    val enteredAt: Instant,
    val exitedAt: Instant? = null,
    val dwell: Duration = Duration.ZERO,
    val parked: Boolean? = null,       // null = 주차 상태 확인 불가
    val state: VisitState = VisitState.ENTERED_ZONE,
    val relatedIncidentId: String? = null,
    val deferCount: Int = 0,
)

@Serializable
data class VisitConfig(
    /** 테스트용 가설값 (9.2: 10~20분). 사용자 조사 후 조정한다. */
    val minDwell: Duration = Duration.ofMinutes(15),
    val maxAccuracyMeters: Float = 50f,
    val sessionMergeGap: Duration = Duration.ofMinutes(60),
    val maxDefers: Int = 1,
)

/**
 * 위치 근거로 정비 방문 '후보'만 만든다. 정비 완료를 확정하지 않는다 (9.1).
 */
class MaintenanceVisitDetector(
    config: VisitConfig = VisitConfig(),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    var config: VisitConfig = config
        private set
    private val candidates = linkedMapOf<String, VisitCandidate>()
    private val suppressedPois = mutableSetOf<String>()

    /** 시연 모드처럼 체류 기준을 바꿀 때 쓴다. 기존 후보에는 소급하지 않는다 */
    fun updateConfig(c: VisitConfig) { config = c }

    fun suppressedPoiIds(): Set<String> = suppressedPois.toSet()

    fun restore(list: List<VisitCandidate>, suppressed: Set<String>, featureOff: Boolean, optIn: Boolean) {
        list.forEach { candidates[it.id] = it }
        suppressedPois += suppressed; featureSuppressed = featureOff; optedIn = optIn
    }
    var featureSuppressed = false
        private set
    var optedIn = false
        private set

    fun all() = candidates.values.toList()
    fun get(id: String) = candidates[id]

    fun setOptIn(enabled: Boolean) {
        optedIn = enabled
        if (!enabled) {
            // 동의 철회: 미확정 후보 폐기, 보류 질문 중지 (BG-08, UT-20)
            candidates.replaceAll { _, c -> if (c.state in pendingStates) c.copy(state = VisitState.DISCARDED) else c }
        }
    }

    fun onEvidence(e: LocationEvidence, poiCatalogAvailable: Boolean): VisitCandidate? {
        if (!optedIn || featureSuppressed) return null
        if (!poiCatalogAvailable || e.poiId == null) return null          // UT-21
        if (e.poiId in suppressedPois) return null
        if (e.accuracyMeters == null || e.accuracyMeters > config.maxAccuracyMeters) return null  // UT-13
        val active = candidates.values.lastOrNull { it.poiId == e.poiId && it.state in activeStates }
        return when (e.kind) {
            LocationEvidence.Kind.ENTER -> {
                if (active != null) return active
                val recent = candidates.values.lastOrNull {
                    it.poiId == e.poiId && it.exitedAt != null && Duration.between(it.exitedAt, e.timestamp) <= config.sessionMergeGap
                }
                val c = VisitCandidate(idGen(), recent?.visitSessionId ?: idGen(), e.poiId, e.source, e.timestamp)
                candidates[c.id] = c; c
            }
            LocationEvidence.Kind.DWELL_SAMPLE, LocationEvidence.Kind.PARKED -> active?.let {
                val u = it.copy(dwell = Duration.between(it.enteredAt, e.timestamp), state = VisitState.DWELLING,
                    parked = if (e.kind == LocationEvidence.Kind.PARKED) true else it.parked)
                candidates[it.id] = u; u
            }
            LocationEvidence.Kind.EXIT -> active?.let {
                val dwell = Duration.between(it.enteredAt, e.timestamp)
                val ok = dwell >= config.minDwell
                // 같은 세션에서 이미 질문했으면 다시 만들지 않는다 (9.4)
                val sessionAsked = candidates.values.any { o -> o.visitSessionId == it.visitSessionId && o.id != it.id && o.state in askedStates }
                val state = when { !ok -> VisitState.DISCARDED; sessionAsked -> VisitState.SUPPRESSED; else -> VisitState.POSTPONED_UNTIL_SAFE }
                val u = it.copy(exitedAt = e.timestamp, dwell = dwell, state = state)
                candidates[it.id] = u; u
            }
            LocationEvidence.Kind.DRIVING -> null
        }
    }

    /** 안전한 정차 시점이고 활성 고위험 경고가 없을 때만 질문할 수 있다 (UT-28). */
    fun nextPromptable(isSafeStop: Boolean, highRiskIncidentActive: Boolean): VisitCandidate? {
        if (!optedIn || featureSuppressed || !isSafeStop || highRiskIncidentActive) return null
        return candidates.values.firstOrNull { it.state == VisitState.POSTPONED_UNTIL_SAFE }
    }

    fun markPrompted(id: String) = update(id) { it.copy(state = VisitState.PROMPTED) }

    fun onAnswer(id: String, intent: UserIntent): VisitCandidate = update(id) {
        when (intent) {
            UserIntent.APPROVE -> it.copy(state = VisitState.CONFIRMED)  // 방문 사실 확인일 뿐. 정비 기록은 별도 승인
            UserIntent.DECLINE, UserIntent.CANCEL -> it.copy(state = VisitState.REJECTED)   // UT-15
            UserIntent.NEVER_ASK_AGAIN -> { suppressedPois += it.poiId; it.copy(state = VisitState.SUPPRESSED) }
            UserIntent.DEFER -> if (it.deferCount < config.maxDefers) it.copy(state = VisitState.POSTPONED_UNTIL_SAFE, deferCount = it.deferCount + 1)
                                else it.copy(state = VisitState.SUPPRESSED)
            UserIntent.SEARCH_REQUEST, UserIntent.NEXT, UserIntent.UNCLEAR -> it   // 재질문 또는 화면 입력으로 폴백
        }
    }

    fun suppressFeature() { featureSuppressed = true }

    private fun update(id: String, f: (VisitCandidate) -> VisitCandidate): VisitCandidate {
        val u = f(requireNotNull(candidates[id]) { "후보 없음: $id" }); candidates[id] = u; return u
    }

    private companion object {
        val activeStates = setOf(VisitState.ENTERED_ZONE, VisitState.DWELLING)
        val pendingStates = setOf(VisitState.ENTERED_ZONE, VisitState.DWELLING, VisitState.EXITED_ZONE, VisitState.VISIT_CANDIDATE, VisitState.POSTPONED_UNTIL_SAFE, VisitState.PROMPTED)
        val askedStates = setOf(VisitState.PROMPTED, VisitState.CONFIRMED, VisitState.REJECTED, VisitState.SUPPRESSED, VisitState.POSTPONED_UNTIL_SAFE)
    }
}
