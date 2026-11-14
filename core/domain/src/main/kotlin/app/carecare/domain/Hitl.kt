@file:UseSerializers(InstantIso::class, LocalDateIso::class, DurationMillis::class)

package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ActionType { SEARCH_SERVICE_CANDIDATES, SET_NAV_DESTINATION, SHARE_HANDOFF_CARD, SAVE_MAINTENANCE_RECORD, REQUEST_MOBILE_CHARGE, REQUEST_SERVICE_BOOKING }

enum class ConsentChoice { APPROVED, DECLINED, DEFERRED, TIMED_OUT, CANCELLED }

enum class ConfirmationMethod { VOICE, TOUCH, NONE }

/** 승인 대상이 되는 정확히 하나의 행동. payload가 바뀌면 hash가 바뀐다 (7.2). */
data class ProposedAction(
    val actionId: String,
    val type: ActionType,
    val payload: Map<String, String>,
    val incidentId: String? = null,
    val visitCandidateId: String? = null,
) {
    val payloadHash: String by lazy {
        val canonical = type.name + "|" + payload.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class ConsentDecision(
    val decisionId: String,
    val actionId: String,
    val actionType: ActionType,
    val payloadHash: String,
    val choice: ConsentChoice,
    val method: ConfirmationMethod,
    val createdAt: Instant,
    val expiresAt: Instant,
    val consumed: Boolean = false,
)

sealed interface Authorization {
    data class Granted(val decision: ConsentDecision) : Authorization
    data class Denied(val reason: String) : Authorization
}

/**
 * 외부 행동 실행 전 반드시 통과해야 하는 관문.
 * 승인은 actionId + payloadHash가 일치하고, 만료 전이며, 한 번만 쓸 수 있다.
 */
class ApprovalGate(
    private val clock: Clock,
    private val validity: Duration = Duration.ofMinutes(2),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val decisions = mutableMapOf<String, ConsentDecision>()  // actionId -> 최신 결정

    fun record(action: ProposedAction, choice: ConsentChoice, method: ConfirmationMethod): ConsentDecision {
        val now = clock.now()
        val d = ConsentDecision(idGen(), action.actionId, action.type, action.payloadHash, choice, method, now, now.plus(validity))
        decisions[action.actionId] = d
        return d
    }

    fun authorize(action: ProposedAction): Authorization {
        val d = decisions[action.actionId] ?: return Authorization.Denied("승인 기록 없음")
        if (d.choice != ConsentChoice.APPROVED) return Authorization.Denied("승인 아님: ${d.choice}")
        if (d.payloadHash != action.payloadHash) return Authorization.Denied("승인 대상이 바뀜. 재승인 필요")
        if (clock.now().isAfter(d.expiresAt)) return Authorization.Denied("승인 만료")
        if (d.consumed) return Authorization.Denied("이미 사용한 승인")
        val used = d.copy(consumed = true)
        decisions[action.actionId] = used
        return Authorization.Granted(used)
    }

    fun history(): List<ConsentDecision> = decisions.values.toList()

    /** 영속화 복구용. 이미 쓴 승인은 그대로 '사용함'으로 남는다 */
    fun restore(list: List<ConsentDecision>) { list.forEach { decisions[it.actionId] = it } }
}

/** 짧은 발화 의도. 부정·취소·연기가 긍정보다 우선한다 (7.3). NEXT는 "다른 곳" 같은 다음 후보 요청 */
enum class UserIntent { NEVER_ASK_AGAIN, CANCEL, DECLINE, DEFER, NEXT, APPROVE, SEARCH_REQUEST, UNCLEAR }

object IntentParser {
    private val never = listOf("다시 묻지", "묻지 마", "그만 물어")
    private val cancel = listOf("취소", "그만")
    private val decline = listOf("아니", "아뇨", "싫어", "안 해", "안했", "안 했", "필요 없", "안 받", "안받", "괜찮")
    private val defer = listOf("나중에", "이따", "다음에")
    private val next = listOf("다른", "다음 거", "다음 곳", "다음꺼", "말고")
    private val approve = listOf("응", "네", "예", "좋아", "그래", "맞아", "해줘", "설정해", "가줘", "가자", "저장해", "기록해")
    private val search = listOf("찾아", "알려", "어디")
    /** 짧은 구어 긍정. "어떤", "엉망"처럼 같은 글자로 시작하는 다른 말과 섞이지 않게 답 전체가 이 말일 때만 승인으로 본다. "어?"(되묻기)는 제외 */
    private val shortApprove = setOf("어", "엉", "웅", "넵", "넹", "어어", "응응", "웅웅", "넵넵")

    fun parse(utterance: String?): UserIntent {
        val t = utterance?.trim()?.replace(Regex("\\s+"), " ") ?: return UserIntent.UNCLEAR
        if (t.isEmpty()) return UserIntent.UNCLEAR
        return when {
            never.any { it in t } -> UserIntent.NEVER_ASK_AGAIN
            cancel.any { it in t } -> UserIntent.CANCEL
            defer.any { it in t } -> UserIntent.DEFER
            next.any { it in t } -> UserIntent.NEXT          // "아니 다른 데"는 거절이 아니라 다음 후보 요청
            decline.any { it in t } -> UserIntent.DECLINE
            search.any { it in t } -> UserIntent.SEARCH_REQUEST   // 검색 요청은 실행 승인이 아니다 (UT-06)
            approve.any { t == it || t.startsWith(it) || t.endsWith(it) } -> UserIntent.APPROVE
            t.trimEnd('.', '!', '~', ' ') in shortApprove -> UserIntent.APPROVE
            else -> UserIntent.UNCLEAR
        }
    }

    fun toChoice(intent: UserIntent): ConsentChoice? = when (intent) {
        UserIntent.APPROVE -> ConsentChoice.APPROVED
        UserIntent.DECLINE, UserIntent.NEVER_ASK_AGAIN -> ConsentChoice.DECLINED
        UserIntent.DEFER -> ConsentChoice.DEFERRED
        UserIntent.CANCEL -> ConsentChoice.CANCELLED
        UserIntent.SEARCH_REQUEST, UserIntent.NEXT, UserIntent.UNCLEAR -> null
    }

    /** "두 번째", "2번" 같은 후보 순번. 0부터 센다. 순번만으로 실행하지 않고 그 후보를 다시 확인한다 */
    fun ordinal(utterance: String?): Int? {
        val t = utterance?.replace(" ", "") ?: return null
        return listOf(listOf("첫번째", "1번", "일번"), listOf("두번째", "2번", "이번째"), listOf("세번째", "3번", "삼번"))
            .indexOfFirst { words -> words.any { it in t } }.takeIf { it >= 0 }
    }
}

/** 명세 7.3 대화 상태 */
enum class DialogueState {
    IDLE, PROMPT_QUEUED, SPEAKING, AWAITING_RESPONSE, INTENT_RESOLVED, CONFIRMING_ACTION,
    APPROVED, DECLINED, DEFERRED, TIMEOUT, ACTION_PENDING, ACTION_ACKNOWLEDGED, ACTION_FAILED, DONE,
}

class DialogueStateMachine {
    var state: DialogueState = DialogueState.IDLE
        private set
    val trace = mutableListOf(DialogueState.IDLE)

    private val next: Map<DialogueState, Set<DialogueState>> = mapOf(
        DialogueState.IDLE to setOf(DialogueState.PROMPT_QUEUED),
        DialogueState.PROMPT_QUEUED to setOf(DialogueState.SPEAKING, DialogueState.DONE),
        DialogueState.SPEAKING to setOf(DialogueState.AWAITING_RESPONSE, DialogueState.DONE),
        DialogueState.AWAITING_RESPONSE to setOf(DialogueState.INTENT_RESOLVED, DialogueState.TIMEOUT),
        DialogueState.INTENT_RESOLVED to setOf(DialogueState.CONFIRMING_ACTION, DialogueState.DECLINED, DialogueState.DEFERRED, DialogueState.AWAITING_RESPONSE, DialogueState.DONE),
        DialogueState.CONFIRMING_ACTION to setOf(DialogueState.APPROVED, DialogueState.DECLINED, DialogueState.DEFERRED, DialogueState.TIMEOUT),
        DialogueState.APPROVED to setOf(DialogueState.ACTION_PENDING),
        DialogueState.DECLINED to setOf(DialogueState.DONE),
        DialogueState.DEFERRED to setOf(DialogueState.DONE),
        DialogueState.TIMEOUT to setOf(DialogueState.DONE),
        DialogueState.ACTION_PENDING to setOf(DialogueState.ACTION_ACKNOWLEDGED, DialogueState.ACTION_FAILED),
        DialogueState.ACTION_ACKNOWLEDGED to setOf(DialogueState.DONE),
        DialogueState.ACTION_FAILED to setOf(DialogueState.DONE),
        DialogueState.DONE to setOf(DialogueState.IDLE),
    )

    fun moveTo(to: DialogueState) {
        require(to in next.getValue(state)) { "허용되지 않는 대화 전이 $state -> $to" }
        state = to; trace += to
    }
}
