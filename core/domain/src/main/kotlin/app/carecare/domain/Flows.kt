package app.carecare.domain

/*
 * 동작 흐름(순서도). 시연 화면의 흐름 창과 docs/care-flows.md가 같은 노드 이름을 쓴다.
 * 오케스트레이터는 판단·설명·질문·승인·실행·기록마다 노드를 진행시키고, 판단 노드에는 실제 값으로 만든 근거를 붙인다.
 */

enum class FlowScenario(val label: String) {
    TIRE("타이어 공기압"),
    FUEL("연료 부족 · 평소 주유소"),
    LOW_BATTERY("배터리 부족"),
    CHARGE_VIA("주행가능거리 부족 · 충전소 경유"),
    MOBILE_CHARGE("찾아가는 충전"),
    VISIT_RECORD("정비소 방문 기록"),
    MAINTENANCE_DUE("소모품 교환 시기"),
}

/** [next]: 가지 이름 → 다음 노드 id. 가지가 하나면 이름은 빈 문자열 */
data class FlowNode(val id: String, val label: String, val next: List<Pair<String, String>> = emptyList(), val terminal: Boolean = false)

data class FlowTrace(val scenario: FlowScenario, val visited: List<String>, val current: String, val facts: List<String>)

object CareFlows {
    private fun n(id: String, label: String, vararg next: Pair<String, String>) = FlowNode(id, label, next.toList())
    private fun end(id: String, label: String) = FlowNode(id, label, terminal = true)

    /** 응답 뒤 다른 길(모든 장 공통) */
    private val exits = listOf(end("later", "나중에 다시 알림"), end("declined", "안 함"), end("noanswer", "응답 없음 · 화면에 남김"))

    private val flows: Map<FlowScenario, List<FlowNode>> = mapOf(
        FlowScenario.TIRE to listOf(
            n("sense", "공기압 · 경고등 수신", "" to "judge"),
            n("judge", "권장 공기압 대비?", "88% 이하 · 경고 전" to "advise", "75% 이하 1개" to "one", "75% 이하 2개 이상" to "multi"),
            n("advise", "공기압 관리 필요 (경고등 전)", "" to "explain"),
            n("one", "낮은 바퀴 1개", "" to "explain"),
            n("multi", "낮은 바퀴 2개 이상", "주행 중" to "stop", "정차" to "park"),
            n("stop", "안전한 곳에 세우도록 안내", "" to "park"),
            n("park", "정차 확인", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "공기 보충 · 정비소 · 긴급출동 제안", "안내" to "candidate"),
            n("candidate", "정비소 후보 제시", "" to "approve"),
            n("approve", "운전자 승인", "" to "act"),
            n("act", "내비 안내 시작", "" to "memo"),
            n("memo", "점검 메모", "" to "recover"),
            n("recover", "공기압 회복 감지", "" to "follow"),
            n("follow", "조치 여부 질문", "" to "record"),
            end("record", "정비 기록 저장"),
        ) + exits,
        FlowScenario.FUEL to listOf(
            n("sense", "연료 잔량 수신", "" to "judge"),
            n("judge", "15% 이하?", "" to "usual"),
            n("usual", "평소 주유소까지 충분?", "예" to "enough", "아니오" to "short"),
            n("enough", "평소 주유소 제안", "" to "explain"),
            n("short", "가까운 주유소 제안", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "주유소 안내 제안", "안내" to "candidate"),
            n("candidate", "주유소 후보 제시", "" to "approve"),
            n("approve", "운전자 승인", "" to "act"),
            end("act", "내비 안내 시작"),
        ) + exits,
        FlowScenario.LOW_BATTERY to listOf(
            n("sense", "배터리 잔량 수신", "" to "judge"),
            n("judge", "15% 이하?", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "충전소 안내 제안", "안내" to "candidate"),
            n("candidate", "충전소 후보 제시", "" to "approve"),
            n("approve", "운전자 승인", "" to "act"),
            end("act", "내비 안내 시작"),
        ) + exits,
        FlowScenario.CHARGE_VIA to listOf(
            n("clock", "지금 시각 확인", "" to "commute"),
            n("commute", "평일 퇴근 시간?", "예" to "home"),
            n("home", "집으로 가는 길로 추정", "" to "compare"),
            n("dest", "내비 목적지 확인", "" to "compare"),
            n("compare", "남은 거리와 주행가능거리 비교", "부족" to "short", "빠듯" to "tight"),
            n("short", "부족", "" to "explain"),
            n("tight", "빠듯 (여유 20% 미만)", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "충전소 경유 제안", "들렀다 가기" to "candidate"),
            n("candidate", "충전소 후보", "" to "approve"),
            n("approve", "운전자 승인", "" to "act"),
            end("act", "경유 경로 설정"),
        ) + exits,
        FlowScenario.MOBILE_CHARGE to listOf(
            n("compare", "목적지까지 주행가능거리 부족", "" to "eligible"),
            n("eligible", "고속도로 · 60km 이하?", "예" to "rest"),
            n("rest", "갈 수 있는 휴게소 찾기", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "찾아가는 충전 제안", "요청" to "confirm", "충전소 경유" to "via"),
            n("confirm", "요청 · 휴게소 안내 확인", "" to "approve"),
            n("approve", "운전자 승인", "" to "request"),
            n("request", "충전 요청 (모의 응답)", "" to "eta"),
            n("eta", "도착 예정 안내", "" to "act"),
            end("act", "휴게소 경로 설정"),
            n("via", "충전소 경유 선택", "" to "candidate"),
            n("candidate", "충전소 후보", "" to "approve"),
        ) + exits,
        FlowScenario.VISIT_RECORD to listOf(
            n("consent", "위치 사용 동의", "" to "enter"),
            n("enter", "정비소 도착", "" to "dwell"),
            n("dwell", "머문 시간 확인 (체험용 1분)", "" to "leave"),
            n("leave", "출발 (주행 중엔 묻지 않음)", "" to "park"),
            n("park", "다음 정차", "" to "ask"),
            n("ask", "정비 받았는지 질문", "받음" to "items"),
            n("items", "받은 품목", "" to "confirm"),
            n("confirm", "기록 확인", "" to "save"),
            n("save", "승인 뒤 저장", "" to "next"),
            end("next", "다음 교환 시점 계산"),
        ) + exits,
        FlowScenario.MAINTENANCE_DUE to listOf(
            n("odo", "주행거리 갱신", "" to "interval"),
            n("interval", "지난 기록 + 교환 주기", "" to "due"),
            n("due", "교환 1,000km 전 · 교환 시기?", "예" to "wait"),
            n("wait", "정차하면 알림", "" to "explain"),
            n("explain", "상태 설명", "" to "offer"),
            n("offer", "정비소 예약 제안", "예약" to "area", "이미 교환" to "items"),
            n("area", "희망 지역 확인 (지역 이름 · 집 근처)", "" to "slot"),
            n("slot", "정비소 · 예약 시간 제시", "" to "approve"),
            n("approve", "운전자 승인", "" to "request"),
            n("request", "예약 요청 (모의 응답)", "" to "booked"),
            end("booked", "예약 확인 · 전날 알림"),
            n("items", "교환 기록 확인", "" to "save"),
            end("save", "승인 뒤 저장"),
        ) + exits,
    )

    fun of(s: FlowScenario): List<FlowNode> = flows.getValue(s)
}
