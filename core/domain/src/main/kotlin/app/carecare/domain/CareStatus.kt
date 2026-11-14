package app.carecare.domain

/**
 * 차량 관리 상태 요약. Gleo AI가 이 앱의 CHECK_CAR_CARE_STATUS 함수를 부르면 앱은 한국어 요약을 직접 말하고,
 * Gleo에는 영어 한 문장(Gleo Interface 응답 규칙)을 돌려준다. '차량 정상'이라고 단언하지 않는다(UX-02).
 */
object CareStatus {
    data class Summary(val ko: String, val en: String)

    private val enSituation = mapOf(
        SituationKind.TIRE_MULTI_LOW to "low pressure on several tires", SituationKind.TIRE_WARNING to "tire pressure warning", SituationKind.TIRE_PRESSURE_LOW to "tire pressure below recommended",
        SituationKind.LOW_FUEL to "low fuel", SituationKind.LOW_EV_BATTERY to "low battery", SituationKind.RANGE_SHORT to "not enough range for the trip",
        SituationKind.ISSUE_FOLLOW_UP to "follow-up check", SituationKind.MAINTENANCE_DUE to "maintenance due", SituationKind.SERVICE_VISIT to "service record",
    )

    private val enItem = mapOf(
        ItemCode.ENGINE_OIL to "engine oil", ItemCode.OIL_FILTER to "oil filter", ItemCode.TIRE to "tire", ItemCode.TIRE_ROTATION to "tire rotation",
        ItemCode.AIR_FILTER to "air filter", ItemCode.CABIN_FILTER to "cabin filter", ItemCode.BRAKE_PAD to "brake pads", ItemCode.BRAKE_FLUID to "brake fluid",
        ItemCode.COOLANT to "coolant", ItemCode.BATTERY_12V to "12V battery", ItemCode.OTHER to "maintenance",
    )

    fun summary(s: AgentState): Summary {
        val open = s.incidents.filter { !it.isClosed && !it.sensorCleared }.mapNotNull { it.decision.situation }.distinct()
        val next = s.reminders.filter { it.dueOdometerKm != null && it.status != ReminderStatus.NO_INTERVAL }
            .groupBy { it.dueOdometerKm }.minByOrNull { it.key!! }?.value
        val km = { v: Double -> "%,.0f".format(v) }

        val ko = buildString {
            append(if (open.isEmpty()) "지금 확인 중인 경고는 없어요." else "지금 확인 중인 경고가 ${open.size}건 있어요: ${open.joinToString(", ") { it.label }}.")
            s.rangeKm?.let { append(" 주행가능거리는 ${km(it)}킬로미터예요.") }
            next?.let { g -> append(" 다음 ${Playbooks.joinAnd(g.map { Playbooks.itemName(it.itemCode) })} 교환은 ${km(g.first().dueOdometerKm!!)}킬로미터예요.") }
        }
        val parts = listOfNotNull(
            when (open.size) {
                0 -> "There are no active warnings"
                1 -> "There is 1 active warning (${enSituation[open.single()]})"
                else -> "There are ${open.size} active warnings (${open.joinToString(", ") { enSituation[it] ?: it.name }})"
            },
            s.rangeKm?.let { "the remaining range is ${km(it)} km" },
            next?.let { g -> "the next ${g.joinToString(" and ") { enItem[it.itemCode] ?: it.itemCode.name }} change is due at ${km(g.first().dueOdometerKm!!)} km" },
        )
        val en = when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        } + "."
        return Summary(ko, en)
    }
}
