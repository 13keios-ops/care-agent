package app.carecare.domain

/** 명세 6.2. 앱의 대응 등급이며 운행 가능 여부 판단이 아니다. */
enum class ResponsePolicy { P0_IMMEDIATE_SAFETY, P1_INSPECTION, P2_INFORMATION, PX_UNKNOWN }

enum class Drivability { NOT_DETERMINED }  // 앱은 운행 가능 여부를 결론 내지 않는다 (6.1-3)

enum class AllowedAction { VIEW_OEM_GUIDANCE, OPEN_HELP_OPTIONS, SEARCH_SERVICE_CANDIDATES, SET_NAV_DESTINATION, CREATE_HANDOFF_CARD }

enum class ForbiddenAction { ASSURE_DRIVABILITY, SET_SPEED_LIMIT, AUTO_NAVIGATE, DIAGNOSE_CAUSE }

/**
 * 규칙 출처.
 * - PRODUCT_DESIGN: 이 제품이 설계한 안내 규칙(공모전 시연·기획 범위의 기본값)
 * - OEM_VERIFIED: 제조사 문서로 확인한 규칙(실차 출시 단계, 명세 24.2)
 * - DRAFT: 작성 중인 규칙. 엔진이 적용하지 않는다
 */
enum class RuleVerification { PRODUCT_DESIGN, OEM_VERIFIED, DRAFT }

@kotlinx.serialization.Serializable
data class SafetyRule(
    val ruleId: String,
    val situation: SituationKind,
    val signalType: SignalType,
    val telltale: TelltaleKind? = null,
    val vehicleApplicability: Set<Powertrain>,
    val sourceReference: String,
    val effectiveVersion: String,
    val reviewedBy: String?,
    val verification: RuleVerification,
    val level: ResponsePolicy,
    val messageId: String,
    val allowedActions: Set<AllowedAction>,
    val forbiddenActions: Set<ForbiddenAction>,
    val fallbackPolicy: ResponsePolicy = ResponsePolicy.PX_UNKNOWN,
    /** 같은 신호 종류 안에서 구분이 필요할 때(예: 공기압 LOW_ONE / LOW_MULTI). 없으면 영역과 무관하게 적용 */
    val area: String? = null,
) {
    init {
        require(ruleId.isNotBlank() && sourceReference.isNotBlank() && effectiveVersion.isNotBlank())
        if (verification == RuleVerification.OEM_VERIFIED) requireNotNull(reviewedBy) { "검증 규칙은 reviewedBy가 필요합니다: $ruleId" }
        require(AllowedAction.SET_NAV_DESTINATION !in allowedActions || ForbiddenAction.AUTO_NAVIGATE in forbiddenActions) {
            "내비 설정을 허용하는 규칙은 자동 내비를 금지해야 합니다(승인 필수): $ruleId"
        }
    }
}

@kotlinx.serialization.Serializable
data class PolicyDecision(
    val situation: SituationKind?,
    val level: ResponsePolicy,
    val ruleId: String?,
    val messageId: String,
    val allowedActions: Set<AllowedAction>,
    val forbiddenActions: Set<ForbiddenAction>,
    val drivability: Drivability = Drivability.NOT_DETERMINED,
    val reason: String,
    val conflicts: List<String> = emptyList(),
)

class RuleEngine(private val rules: List<SafetyRule>) {

    private val safeFallbackActions = setOf(AllowedAction.VIEW_OEM_GUIDANCE, AllowedAction.OPEN_HELP_OPTIONS)
    private val alwaysForbidden = ForbiddenAction.entries.toSet()

    fun evaluate(signal: NormalizedSignal, vehicle: VehicleProfile): PolicyDecision {
        val matches = matching(signal.raw, vehicle)
        val situation = matches.firstOrNull()?.situation
        if (signal.raw.supported != true) return unknown(situation, "UNSUPPORTED_SIGNAL", "지원되지 않는 신호")
        if (signal.freshness != Freshness.VALID) return unknown(situation, "STALE_OR_UNKNOWN", "신호가 오래되었거나 상태를 알 수 없음")

        val verified = matches.filter { it.verification != RuleVerification.DRAFT }
        if (verified.isEmpty()) {
            val pending = matches.firstOrNull()
            return unknown(situation, pending?.messageId ?: "GENERIC_ACK", "적용할 규칙 없음" + (pending?.let { " (${it.ruleId} 작성 중)" } ?: ""), pending?.ruleId)
        }
        // 충돌 시 보수적으로: 가장 높은 등급, 허용 행동은 교집합, 금지 행동은 합집합 (6.3)
        val chosen = verified.minBy { it.level.ordinal }
        val conflicts = if (verified.map { it.level }.distinct().size > 1) verified.map { "${it.ruleId}=${it.level}" } else emptyList()
        val allowed = verified.map { it.allowedActions }.reduce { a, b -> a intersect b }
        val forbidden = verified.flatMap { it.forbiddenActions }.toSet() + ForbiddenAction.ASSURE_DRIVABILITY + ForbiddenAction.AUTO_NAVIGATE
        return PolicyDecision(chosen.situation, chosen.level, chosen.ruleId, chosen.messageId, allowed, forbidden, reason = "규칙 적용", conflicts = conflicts)
    }

    /** 이 신호를 다루는 규칙이 있는가. 규칙이 없는 경고등(방향지시등 등)은 사건으로 만들지 않는다. */
    fun covers(raw: RawVehicleSignal, vehicle: VehicleProfile): Boolean = matching(raw, vehicle).isNotEmpty()

    private fun matching(raw: RawVehicleSignal, vehicle: VehicleProfile) = rules.filter {
        it.signalType == raw.type &&
            (it.telltale == null || it.telltale == raw.telltale) &&
            (it.area == null || it.area == raw.area) &&
            (vehicle.powertrain in it.vehicleApplicability || vehicle.powertrain == Powertrain.UNKNOWN)
    }

    private fun unknown(situation: SituationKind?, messageId: String, reason: String, ruleId: String? = null) =
        PolicyDecision(situation, ResponsePolicy.PX_UNKNOWN, ruleId, messageId, safeFallbackActions, alwaysForbidden, reason = reason)

    companion object {
        const val TIRE_LOW_ONE = "LOW_ONE"
        const val TIRE_LOW_MULTI = "LOW_MULTI"
        /** 경고 전 단계: 권장 공기압보다 낮음(선제 안내) */
        const val TIRE_LOW_ADVISE = "LOW_ADVISE"
        /** 목적지까지 주행가능거리 판단(합성 신호의 영역 이름) */
        const val RANGE_TRIP = "TRIP"

        /** 기본 번들: 제품이 설계한 안내 규칙. 승인 없는 내비 설정·운행 가능 보증·속도 지시는 금지한다. */
        private val placeActions = setOf(AllowedAction.VIEW_OEM_GUIDANCE, AllowedAction.SEARCH_SERVICE_CANDIDATES, AllowedAction.SET_NAV_DESTINATION)
        private val noUnsafe = setOf(ForbiddenAction.ASSURE_DRIVABILITY, ForbiddenAction.SET_SPEED_LIMIT, ForbiddenAction.AUTO_NAVIGATE, ForbiddenAction.DIAGNOSE_CAUSE)

        fun defaultBundle(): List<SafetyRule> = listOf(
            SafetyRule(
                ruleId = "TIRE_WARNING_V1", situation = SituationKind.TIRE_WARNING, signalType = SignalType.TELLTALE, telltale = TelltaleKind.TPMS,
                vehicleApplicability = Powertrain.entries.toSet(), sourceReference = "PRODUCT_DESIGN (기획 초안 SC-A)",
                effectiveVersion = "0.2-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P1_INSPECTION, messageId = "TIRE_WARNING_ACK",
                allowedActions = setOf(AllowedAction.VIEW_OEM_GUIDANCE, AllowedAction.SEARCH_SERVICE_CANDIDATES, AllowedAction.SET_NAV_DESTINATION, AllowedAction.CREATE_HANDOFF_CARD),
                forbiddenActions = noUnsafe,
            ),
            // 경고등 전 선제 안내: 권장 공기압보다 12% 이상 낮음(예: 36psi → 31psi 이하). 정보 안내 등급
            SafetyRule(
                ruleId = "TIRE_PRESSURE_ADVISE_V1", situation = SituationKind.TIRE_PRESSURE_LOW, signalType = SignalType.TIRE_PRESSURE, area = TIRE_LOW_ADVISE,
                vehicleApplicability = Powertrain.entries.toSet(), sourceReference = "PRODUCT_DESIGN (경고등 전에 권장 공기압 기준으로 먼저 안내)",
                effectiveVersion = "0.5-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P2_INFORMATION, messageId = "TIRE_PRESSURE_ADVISE_ACK", allowedActions = placeActions, forbiddenActions = noUnsafe,
            ),
            // 바퀴별 공기압 (SC-A·SC-B). 권장 공기압 대비 25% 이상 낮으면 '낮음' (오케스트레이터가 LOW_ONE/LOW_MULTI로 요약)
            SafetyRule(
                ruleId = "TIRE_PRESSURE_ONE_V1", situation = SituationKind.TIRE_WARNING, signalType = SignalType.TIRE_PRESSURE, area = TIRE_LOW_ONE,
                vehicleApplicability = Powertrain.entries.toSet(), sourceReference = "PRODUCT_DESIGN (기획 초안 SC-A, 저압 기준은 FMVSS 138의 25% 기준 참고)",
                effectiveVersion = "0.3-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P1_INSPECTION, messageId = "TIRE_PRESSURE_ONE_ACK",
                allowedActions = setOf(AllowedAction.VIEW_OEM_GUIDANCE, AllowedAction.SEARCH_SERVICE_CANDIDATES, AllowedAction.SET_NAV_DESTINATION, AllowedAction.CREATE_HANDOFF_CARD),
                forbiddenActions = noUnsafe,
            ),
            SafetyRule(
                ruleId = "TIRE_PRESSURE_MULTI_V1", situation = SituationKind.TIRE_MULTI_LOW, signalType = SignalType.TIRE_PRESSURE, area = TIRE_LOW_MULTI,
                vehicleApplicability = Powertrain.entries.toSet(), sourceReference = "PRODUCT_DESIGN (기획 초안 SC-B: 보수적 안내, 주행 중 내비 추천 억제)",
                effectiveVersion = "0.3-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P0_IMMEDIATE_SAFETY, messageId = "TIRE_MULTI_ACK",
                allowedActions = setOf(AllowedAction.VIEW_OEM_GUIDANCE, AllowedAction.OPEN_HELP_OPTIONS, AllowedAction.SEARCH_SERVICE_CANDIDATES,
                    AllowedAction.SET_NAV_DESTINATION, AllowedAction.CREATE_HANDOFF_CARD),
                forbiddenActions = noUnsafe,
            ),
            SafetyRule(
                ruleId = "LOW_FUEL_V1", situation = SituationKind.LOW_FUEL, signalType = SignalType.TELLTALE, telltale = TelltaleKind.LOW_FUEL,
                vehicleApplicability = setOf(Powertrain.ICE, Powertrain.HEV, Powertrain.PHEV), sourceReference = "PRODUCT_DESIGN (기획 초안 SC-C)",
                effectiveVersion = "0.2-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P2_INFORMATION, messageId = "LOW_FUEL_ACK", allowedActions = placeActions, forbiddenActions = noUnsafe,
            ),
            // 목적지까지 주행가능거리 부족·빠듯 (2-B, 2-C). 정보 안내 등급이며 운행 가능 여부를 보증하지 않는다
            SafetyRule(
                // 영역은 목적지마다 다르다(TRIP:<목적지>). 목적지가 바뀌면 새 상황으로 묻는다
                ruleId = "RANGE_SHORT_V1", situation = SituationKind.RANGE_SHORT, signalType = SignalType.RANGE_REMAINING, area = null,
                vehicleApplicability = Powertrain.entries.toSet(), sourceReference = "PRODUCT_DESIGN (여유 기준 ×1.2)",
                effectiveVersion = "0.4-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P2_INFORMATION, messageId = "RANGE_SHORT_ACK", allowedActions = placeActions, forbiddenActions = noUnsafe,
            ),
            SafetyRule(
                ruleId = "LOW_EV_BATTERY_V1", situation = SituationKind.LOW_EV_BATTERY, signalType = SignalType.TELLTALE, telltale = TelltaleKind.LOW_EV_BATTERY,
                vehicleApplicability = setOf(Powertrain.EV, Powertrain.PHEV), sourceReference = "PRODUCT_DESIGN (기획 초안 SC-C)",
                effectiveVersion = "0.2-demo", reviewedBy = null, verification = RuleVerification.PRODUCT_DESIGN,
                level = ResponsePolicy.P2_INFORMATION, messageId = "LOW_EV_ACK", allowedActions = placeActions, forbiddenActions = noUnsafe,
            ),
        )
    }
}

/** 점검이 필요한 경고(P0·P1)인지. 조치 확인, 방문 질문 보류, 사건-정비 연결이 이 값을 따른다 */
fun PolicyDecision.needsInspection() = level == ResponsePolicy.P0_IMMEDIATE_SAFETY || level == ResponsePolicy.P1_INSPECTION
