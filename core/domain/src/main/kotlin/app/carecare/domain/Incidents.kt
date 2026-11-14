@file:UseSerializers(InstantIso::class, LocalDateIso::class, DurationMillis::class)

package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 명세 13.3. CLOSED_BY_USER는 '사용자가 닫음'이지 수리 완료가 아니다. */
enum class IncidentStatus {
    OPEN, NOTIFIED, ACTION_OFFERED, ACTION_PENDING, ACTION_ACKNOWLEDGED, ACTION_FAILED,
    FOLLOWUP_PENDING, USER_REPORTED_ACTION_TAKEN, CLOSED_BY_USER,
}

@Serializable
data class Incident(
    val id: String,
    val fingerprint: String,
    val source: DataSource,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val decision: PolicyDecision,
    val status: IncidentStatus,
    val recurrenceParentId: String? = null,
    val signalCount: Int = 1,
    // 해결 근거는 서로 독립이다 (10.4)
    val userActionCompleted: Boolean = false,
    val sensorCleared: Boolean = false,
    val sensorClearedAt: Instant? = null,
    val technicianConfirmed: Boolean = false,
    val linkedMaintenanceRecordIds: List<String> = emptyList(),
) {
    val isClosed get() = status == IncidentStatus.CLOSED_BY_USER
}

sealed interface IncidentEvent {
    data class Created(val incident: Incident) : IncidentEvent
    data class Debounced(val incident: Incident) : IncidentEvent
    data class SensorCleared(val incident: Incident) : IncidentEvent
    data class Ignored(val reason: String) : IncidentEvent
}

class IncidentEngine(
    private val debounceWindow: Duration = Duration.ofMinutes(30),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val incidents = linkedMapOf<String, Incident>()

    fun all(): List<Incident> = incidents.values.toList()
    fun get(id: String): Incident? = incidents[id]

    fun onSignal(signal: NormalizedSignal, decision: PolicyDecision, now: Instant): IncidentEvent {
        val open = incidents.values.lastOrNull { it.fingerprint == signal.fingerprint && !it.isClosed }
        if (!signal.active) {
            if (open == null || open.sensorCleared) return IncidentEvent.Ignored("비활성 신호")
            // 센서 해제만 기록한다. 사건을 해결로 바꾸지 않는다 (UT-11)
            val updated = open.copy(sensorCleared = true, sensorClearedAt = now, lastSeen = now)
            incidents[open.id] = updated
            return IncidentEvent.SensorCleared(updated)
        }
        if (open != null && Duration.between(open.lastSeen, now) <= debounceWindow) {
            val updated = open.copy(lastSeen = now, signalCount = open.signalCount + 1,
                // 해제 후 다시 켜지면 해제 기록을 되돌린다
                sensorCleared = false, sensorClearedAt = null)
            incidents[open.id] = updated
            return IncidentEvent.Debounced(updated)
        }
        val parent = incidents.values.lastOrNull { it.fingerprint == signal.fingerprint }
        val created = Incident(
            id = idGen(), fingerprint = signal.fingerprint, source = signal.raw.source,
            firstSeen = now, lastSeen = now, decision = decision, status = IncidentStatus.OPEN,
            recurrenceParentId = parent?.id,
        )
        incidents[created.id] = created
        return IncidentEvent.Created(created)
    }

    fun canTransition(id: String, to: IncidentStatus): Boolean = incidents[id]?.let { to in allowed.getValue(it.status) } ?: false

    fun transition(id: String, to: IncidentStatus): Incident {
        val cur = requireNotNull(incidents[id]) { "사건 없음: $id" }
        require(to in allowed.getValue(cur.status)) { "허용되지 않는 전이 ${cur.status} -> $to" }
        val updated = cur.copy(status = to, userActionCompleted = cur.userActionCompleted || to == IncidentStatus.USER_REPORTED_ACTION_TAKEN)
        incidents[id] = updated
        return updated
    }

    fun linkMaintenance(id: String, recordId: String, technicianConfirmedByUser: Boolean): Incident {
        val cur = requireNotNull(incidents[id])
        // 정비 기록 연결만으로 사건을 닫지 않는다
        val updated = cur.copy(
            linkedMaintenanceRecordIds = cur.linkedMaintenanceRecordIds + recordId,
            technicianConfirmed = cur.technicianConfirmed || technicianConfirmedByUser,
        )
        incidents[id] = updated
        return updated
    }

    /** 영속화 복구용 */
    fun restore(list: List<Incident>) { list.forEach { incidents[it.id] = it } }

    private companion object {
        val S = IncidentStatus.entries
        val allowed: Map<IncidentStatus, Set<IncidentStatus>> = mapOf(
            IncidentStatus.OPEN to setOf(IncidentStatus.NOTIFIED, IncidentStatus.CLOSED_BY_USER),
            IncidentStatus.NOTIFIED to setOf(IncidentStatus.ACTION_OFFERED, IncidentStatus.FOLLOWUP_PENDING, IncidentStatus.CLOSED_BY_USER),
            IncidentStatus.ACTION_OFFERED to setOf(IncidentStatus.ACTION_PENDING, IncidentStatus.FOLLOWUP_PENDING, IncidentStatus.CLOSED_BY_USER),
            // 내비 응답을 확인하지 못하면(UNKNOWN) 완료로 보지 않고 후속 확인으로 넘긴다
            IncidentStatus.ACTION_PENDING to setOf(IncidentStatus.ACTION_ACKNOWLEDGED, IncidentStatus.ACTION_FAILED, IncidentStatus.FOLLOWUP_PENDING),
            IncidentStatus.ACTION_ACKNOWLEDGED to setOf(IncidentStatus.FOLLOWUP_PENDING),
            IncidentStatus.ACTION_FAILED to setOf(IncidentStatus.ACTION_OFFERED, IncidentStatus.FOLLOWUP_PENDING, IncidentStatus.CLOSED_BY_USER),
            // 사용자가 다시 요청하면 도움을 다시 제안할 수 있다
            IncidentStatus.FOLLOWUP_PENDING to setOf(IncidentStatus.USER_REPORTED_ACTION_TAKEN, IncidentStatus.ACTION_OFFERED, IncidentStatus.CLOSED_BY_USER),
            IncidentStatus.USER_REPORTED_ACTION_TAKEN to setOf(IncidentStatus.CLOSED_BY_USER, IncidentStatus.FOLLOWUP_PENDING),
            IncidentStatus.CLOSED_BY_USER to emptySet(),
        ).also { m -> check(S.all { it in m }) }
    }
}
