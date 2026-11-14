@file:UseSerializers(InstantIso::class, LocalDateIso::class, DurationMillis::class)

package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 명세 10.2 */
enum class ItemCode(val label: String, val powertrains: Set<Powertrain>) {
    ENGINE_OIL("엔진오일 교환", ice), OIL_FILTER("엔진오일 필터", ice), TIRE("타이어 교체", all),
    TIRE_ROTATION("타이어 위치 교환", all), AIR_FILTER("에어필터 교환", ice), CABIN_FILTER("에어컨 필터", all),
    BRAKE_PAD("브레이크 패드", all), BRAKE_FLUID("브레이크액 교환", all), COOLANT("냉각수 교환", all),
    BATTERY_12V("12V 보조배터리 교체", all), OTHER("기타 정비", all);
}
private val all = Powertrain.entries.toSet()
private val ice = setOf(Powertrain.ICE, Powertrain.HEV, Powertrain.PHEV, Powertrain.UNKNOWN)

@Serializable
data class ItemCandidate(val code: ItemCode, val quantity: Int? = null, val rawText: String)

data class MaintenanceDraft(
    val id: String,
    val visitCandidateId: String?,
    val visitSessionId: String?,
    val itemCandidates: List<ItemCandidate>,
    val serviceDate: LocalDate?,
    val odometerKm: Double?,
    val odometerSource: ValueSource,
    val sttConfidence: Float?,
    val source: DataSource,
    val needsClarification: Boolean,
)

enum class ValueSource { VEHICLE_SDK, USER_CONFIRMED, USER_ESTIMATED, UNKNOWN }

@Serializable
data class MaintenanceRecord(
    val id: String,
    val items: List<ItemCandidate>,
    val serviceDate: LocalDate,
    val odometerAtServiceKm: Double?,
    val odometerSource: ValueSource,
    val source: DataSource,
    val approvedAt: Instant,
    val approvalDecisionId: String,
    val visitSessionId: String?,
    val relatedIncidentId: String?,
)

/** 짧은 발화를 정비 품목 후보로 바꾼다. 결과는 언제나 초안이다 (UT-16). */
object MaintenanceUtteranceParser {
    private val rules: List<Pair<Regex, ItemCode>> = listOf(
        Regex("타이어\\s*(위치\\s*(교환|바꿈|바꿨)|로테이션)") to ItemCode.TIRE_ROTATION,
        Regex("(엔진\\s*)?오일\\s*필터") to ItemCode.OIL_FILTER,
        Regex("(엔진\\s*)?오일(?!\\s*필터)") to ItemCode.ENGINE_OIL,
        Regex("타이어(?!\\s*(위치|로테이션))") to ItemCode.TIRE,
        Regex("에어컨\\s*필터|캐빈\\s*필터|실내\\s*필터") to ItemCode.CABIN_FILTER,
        Regex("에어\\s*필터|공기\\s*필터|에어\\s*클리너") to ItemCode.AIR_FILTER,
        Regex("브레이크\\s*패드|패드") to ItemCode.BRAKE_PAD,
        Regex("브레이크\\s*(액|오일)") to ItemCode.BRAKE_FLUID,
        Regex("냉각수|부동액") to ItemCode.COOLANT,
        Regex("배터리") to ItemCode.BATTERY_12V,
    )
    private val unmapped = listOf("미션오일", "미션 오일", "와이퍼", "점화플러그")
    private val qty = mapOf("한 짝" to 1, "두 짝" to 2, "세 짝" to 3, "네 짝" to 4, "1개" to 1, "2개" to 2, "4개" to 4)

    fun parse(text: String, powertrain: Powertrain): Pair<List<ItemCandidate>, Boolean> {
        var t = text
        val out = mutableListOf<ItemCandidate>()
        // 사전에 없는 품목은 OTHER로 두고 원문을 남긴다
        unmapped.filter { it in t }.forEach { out += ItemCandidate(ItemCode.OTHER, rawText = it); t = t.replace(it, " ") }
        val q = qty.entries.firstOrNull { it.key in t }?.value
        for ((re, code) in rules) {
            val m = re.find(t) ?: continue
            if (out.any { it.code == code }) continue
            if (code == ItemCode.BRAKE_PAD && out.any { it.code == ItemCode.BRAKE_FLUID }) continue
            out += ItemCandidate(code, if (code == ItemCode.TIRE) q else null, m.value)
            t = t.replaceRange(m.range, " ".repeat(m.value.length))
        }
        // "엔진오일이랑 필터 갈았어": 엔진오일과 함께 말한 '필터'는 오일 필터다 (다른 필터는 위 규칙에서 이미 소비됨)
        if (out.any { it.code == ItemCode.ENGINE_OIL } && out.none { it.code == ItemCode.OIL_FILTER }) {
            Regex("필터").find(t)?.let { out += ItemCandidate(ItemCode.OIL_FILTER, rawText = it.value) }
        }
        val filtered = out.filter { powertrain in it.code.powertrains }
        val incompatible = filtered.size != out.size
        return filtered to (filtered.isEmpty() || incompatible)
    }
}

sealed interface LedgerResult {
    data class Saved(val record: MaintenanceRecord) : LedgerResult
    data class Rejected(val reason: String) : LedgerResult
}

/** 승인된 정비 기록만 확정한다. */
class MaintenanceLedger(
    private val gate: ApprovalGate,
    private val clock: Clock,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val drafts = linkedMapOf<String, MaintenanceDraft>()
    private val records = linkedMapOf<String, MaintenanceRecord>()

    fun records() = records.values.toList()
    fun drafts() = drafts.values.toList()

    fun createDraft(
        utterance: SttResult, vehicle: VehicleProfile, visit: VisitCandidate?, serviceDate: LocalDate?,
        odometerKm: Double?, odometerSource: ValueSource, source: DataSource, minConfidence: Float = 0.6f,
    ): MaintenanceDraft {
        val (items, unclear) = MaintenanceUtteranceParser.parse(utterance.text, vehicle.powertrain)
        val lowConf = utterance.confidence == null || utterance.confidence < minConfidence
        val d = MaintenanceDraft(idGen(), visit?.id, visit?.visitSessionId, items, serviceDate, odometerKm, odometerSource,
            utterance.confidence, source, needsClarification = unclear || lowConf || serviceDate == null)
        drafts[d.id] = d
        return d
    }

    fun proposeSave(draft: MaintenanceDraft): ProposedAction = ProposedAction(
        actionId = "save-${draft.id}", type = ActionType.SAVE_MAINTENANCE_RECORD,
        payload = mapOf(
            "items" to draft.itemCandidates.joinToString(",") { "${it.code}:${it.quantity ?: ""}" },
            "date" to (draft.serviceDate?.toString() ?: ""), "odo" to (draft.odometerKm?.toString() ?: ""),
        ),
        visitCandidateId = draft.visitCandidateId,
    )

    fun confirm(draft: MaintenanceDraft, action: ProposedAction, relatedIncidentId: String? = null): LedgerResult {
        if (draft.itemCandidates.isEmpty()) return LedgerResult.Rejected("품목 없음")
        val date = draft.serviceDate ?: return LedgerResult.Rejected("정비일 없음")
        if (proposeSave(draft).payloadHash != action.payloadHash) return LedgerResult.Rejected("승인 내용과 초안이 다름")
        val sessionDup = draft.visitSessionId?.let { s ->
            records.values.any { r -> r.visitSessionId == s && r.items.map { it.code }.intersect(draft.itemCandidates.map { it.code }.toSet()).isNotEmpty() }
        } ?: false
        if (sessionDup) return LedgerResult.Rejected("같은 방문에 같은 품목 기록이 이미 있음")
        val auth = gate.authorize(action)
        if (auth !is Authorization.Granted) return LedgerResult.Rejected((auth as Authorization.Denied).reason)
        val r = MaintenanceRecord(idGen(), draft.itemCandidates, date, draft.odometerKm, draft.odometerSource, draft.source,
            clock.now(), auth.decision.decisionId, draft.visitSessionId, relatedIncidentId)
        records[r.id] = r
        drafts.remove(draft.id)
        return LedgerResult.Saved(r)
    }

    /** 화면에서 고른 품목으로 초안을 만든다. 음성 인식이 실패했을 때의 대체 입력이다 (7.3) */
    fun createDraftFromItems(
        codes: List<ItemCode>, vehicle: VehicleProfile, visit: VisitCandidate?, serviceDate: LocalDate?,
        odometerKm: Double?, odometerSource: ValueSource, source: DataSource,
    ): MaintenanceDraft {
        val items = codes.distinct().filter { vehicle.powertrain in it.powertrains }.map { ItemCandidate(it, rawText = it.label) }
        val d = MaintenanceDraft(idGen(), visit?.id, visit?.visitSessionId, items, serviceDate, odometerKm, odometerSource,
            1f, source, needsClarification = items.isEmpty() || serviceDate == null)
        drafts[d.id] = d
        return d
    }

    fun restore(list: List<MaintenanceRecord>) { list.forEach { records[it.id] = it } }

    fun delete(recordId: String) { records.remove(recordId) }
    fun edit(record: MaintenanceRecord) { require(record.id in records); records[record.id] = record }
}
