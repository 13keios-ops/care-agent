package app.carecare.app.ui

import app.carecare.app.demo.tapTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.carecare.domain.AgentState
import app.carecare.domain.ConversationPhase
import app.carecare.domain.ConversationView
import app.carecare.domain.Gear
import app.carecare.domain.Incident
import app.carecare.domain.IncidentStatus
import app.carecare.domain.ItemCode
import app.carecare.domain.OptionKind
import app.carecare.domain.PendingSituation
import app.carecare.domain.Playbooks
import app.carecare.domain.ServiceInterval
import app.carecare.domain.ScheduleSource
import app.carecare.domain.RecommendedIntervals
import app.carecare.domain.Powertrain
import app.carecare.domain.ReminderStatus
import app.carecare.domain.RuntimeState
import app.carecare.domain.SituationKind
import app.carecare.domain.UserReply
import app.carecare.pleos.PleosPlatform
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface CareActions {
    /** [seq]: 버튼을 그린 질문의 순번(ConversationView.seq). 지나간 질문의 터치를 에이전트가 버린다 */
    fun reply(r: UserReply, seq: Int? = null)
    fun reopen(key: String)
    fun requestHelp(incidentId: String)
    fun closeIncident(id: String)
    fun recordManually()
    fun deleteRecord(id: String)
    fun setVisitOptIn(on: Boolean)
    fun setVoice(on: Boolean)
    fun testVoice()
    fun setDemoDwell(on: Boolean)
    fun setInterval(code: ItemCode, km: Double?, days: Long?)
    fun powertrain(): Powertrain
    fun setPowertrain(p: Powertrain)
    fun monitoring(): Boolean
    fun setMonitoring(on: Boolean)
    fun resetAll()
    fun demoMode(): Boolean
    /** 시연 모드를 켤 수 있는 환경인가(에뮬레이터만) */
    fun demoAllowed(): Boolean
    fun setDemoMode(on: Boolean)
    fun openDemoPanel()
}

/** 앱 위쪽 시연 띠 */
enum class DemoBanner { NONE, OFFER, OPEN_FALLBACK }

internal object C {
    val bg = Color(0xFF0E1116)
    val surface = Color(0xFF171B22)
    val surface2 = Color(0xFF212835)
    val accent = Color(0xFF4FD1C5)
    val warn = Color(0xFFF6AD55)
    val danger = Color(0xFFFC8181)
    val ok = Color(0xFF68D391)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF9AA4B2)
}

private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private fun Instant.short() = timeFmt.format(this)
private fun km(v: Double) = "%,.0f km".format(v)

@Composable
fun CareRoot(state: StateFlow<AgentState>?, actions: CareActions, banner: DemoBanner = DemoBanner.NONE) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, onPrimary = C.bg, background = C.bg, surface = C.surface,
        onSurface = C.text, onBackground = C.text, secondary = C.warn)) {
        Surface(Modifier.fillMaxSize(), color = C.bg) {
            Column {
                DemoBannerRow(banner, actions)
                if (state == null) Starting(actions) else {
                    val s by state.collectAsStateWithLifecycle()
                    CareScreen(s, actions)
                }
            }
        }
    }
}

@Composable
private fun DemoBannerRow(banner: DemoBanner, a: CareActions) {
    if (banner == DemoBanner.NONE) return
    Row(Modifier.fillMaxWidth().background(C.warn.copy(alpha = 0.18f)).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (banner == DemoBanner.OFFER) "상황 체험" else "상황 체험 모드", fontSize = 18.sp, color = C.warn, fontWeight = FontWeight.SemiBold)
            Text(if (banner == DemoBanner.OFFER) "가상 콘솔이 연결된 에뮬레이터예요. 상황 체험 창으로 상황별 신호를 넣어 볼 수 있어요"
                else "떠 있는 창 권한이 없어 대체 패널 화면을 써요(adb install -g로 설치하면 떠 있는 창)", fontSize = 14.sp, color = C.text)
        }
        Button(onClick = { if (banner == DemoBanner.OFFER) a.setDemoMode(true) else a.openDemoPanel() }) {
            Text(if (banner == DemoBanner.OFFER) "상황 체험 창 켜기" else "상황 체험 창 열기", fontSize = 18.sp)
        }
    }
}

@Composable
private fun Starting(a: CareActions) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (a.monitoring()) {
            Text("케어 에이전트를 시작하고 있어요", fontSize = 26.sp, color = C.text)
        } else {
            Text("차량 감지가 꺼져 있어요", fontSize = 26.sp, color = C.text)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { a.setMonitoring(true) }) { Text("감지 켜기", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun CareScreen(s: AgentState, a: CareActions) {
    // 기어를 알 수 없으면 주행 중으로 보고 간단한 화면만 보여 준다
    val driving = s.gear != Gear.PARK
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        StatusBar(s)
        Spacer(Modifier.height(12.dp))
        if (driving) DrivingView(s, a) else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                s.conversation?.let { ConversationCard(it, a, s) } ?: run {
                    IdleCard(s, a)
                    Spacer(Modifier.height(12.dp))
                    VehicleSummary(s, a.powertrain())
                }
                Spacer(Modifier.height(12.dp))
                PendingList(s.pending, a)
            }
            // 대화 중에는 오른쪽을 상황별 화면으로 바꾸고, 기록 탭은 버튼으로 연다
            val visual = s.conversation?.visual
            var showTabs by remember(s.conversation?.key) { mutableStateOf(false) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (visual != null && !showTabs) {
                    VisualPanel(visual)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showTabs = true }) { Text("기록 보기", fontSize = 16.sp) }
                } else {
                    if (visual != null) TextButton(onClick = { showTabs = false }) { Text("상황 화면으로", fontSize = 16.sp) }
                    SideTabs(s, a)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(s: AgentState) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("케어 에이전트", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.text, modifier = Modifier.padding(end = 8.dp))
        val (rt, rc) = when (s.runtime) {
            RuntimeState.MONITORING -> "지켜보는 중" to C.ok
            RuntimeState.DEGRADED -> "일부 기능 미지원" to C.warn
            RuntimeState.STARTING, RuntimeState.RECOVERING -> "시작 중" to C.dim
            else -> s.runtime.name to C.danger
        }
        Chip(rt, rc)
        // 주행거리·잔량 같은 원시값은 운전자에게 보여 줄 필요가 없어 뺐다. 실차가 아닐 때만 신호 출처를 밝힌다
        if (!s.locationKnown) Chip("위치 없음", C.warn)
        if (PleosPlatform.dataSource() != app.carecare.domain.DataSource.REAL_SDK) Chip("신호 출처: ${PleosPlatform.dataSource().label}", C.warn)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 15.sp)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(18.dp))
        .then(if (accent != null) Modifier.border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(18.dp)) else Modifier)
        .padding(20.dp)) { content() }
}

private fun situationColor(k: SituationKind) = when (k) {
    SituationKind.TIRE_WARNING, SituationKind.TIRE_MULTI_LOW -> C.danger
    SituationKind.LOW_FUEL, SituationKind.LOW_EV_BATTERY, SituationKind.TIRE_PRESSURE_LOW -> C.warn
    else -> C.accent
}

@Composable
private fun DrivingView(s: AgentState, a: CareActions) {
    val c = s.conversation
    val visual = c?.visual
    if (c != null && visual != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.2f)) { ConversationCard(c, a, s, compact = true) }
        Column(Modifier.weight(1f)) { VisualPanel(visual) }
    } else if (c != null) ConversationCard(c, a, s, compact = true) else Panel {
        Text("주행 중에는 음성으로 안내해요", fontSize = 28.sp, color = C.text)
        Spacer(Modifier.height(8.dp))
        Text("정비 기록과 설정은 차를 세운 뒤(P단) 볼 수 있어요.", fontSize = 18.sp, color = C.dim)
        val waiting = s.pending.count { it.waitForPark && !it.manualOnly }
        if (waiting > 0) { Spacer(Modifier.height(8.dp)); Text("정차하면 물어볼 일 $waiting 건", fontSize = 18.sp, color = C.accent) }
    }
}

@Composable
private fun IdleCard(s: AgentState, a: CareActions) {
    val open = s.incidents.count { !it.isClosed && !it.sensorCleared }
    Panel {
        Text("차량 상태를 지켜보고 있어요", fontSize = 28.sp, color = C.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        // '차량 정상'이라고 단언하지 않는다 (UX-02)
        Text(if (open == 0) "최근 확인된 경고 없음" else "확인 중인 경고 $open 건", fontSize = 19.sp, color = if (open == 0) C.dim else C.warn)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { a.recordManually() }) { Text("정비 내용 직접 기록하기", fontSize = 18.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationCard(c: ConversationView, a: CareActions, s: AgentState, compact: Boolean = false) {
    Panel(accent = situationColor(c.situation)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(situationColor(c.situation), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(c.situation.label, fontSize = 18.sp, color = situationColor(c.situation), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(c.sourceLabel, fontSize = 14.sp, color = C.warn)
        }
        Spacer(Modifier.height(10.dp))
        Text(c.message, fontSize = if (compact) 30.sp else 26.sp, color = C.text, lineHeight = if (compact) 40.sp else 36.sp)
        Spacer(Modifier.height(8.dp))
        when {
            c.speaking -> Text("말하는 중…", fontSize = 16.sp, color = C.dim)
            c.listening -> Text("듣고 있어요. 말씀하시거나 화면에서 골라 주세요.", fontSize = 16.sp, color = C.accent)
        }
        c.heard?.let { Text("받은 응답: \"$it\"", fontSize = 17.sp, color = C.dim) }
        Spacer(Modifier.height(14.dp))
        val big = Modifier.heightIn(min = 58.dp)
        when (c.phase) {
            ConversationPhase.ASK_OPTIONS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                c.options.forEachIndexed { i, o ->
                    if (i == 0) Button(onClick = { a.reply(UserReply.Choose(o.kind, o.value), c.seq) }, modifier = big) { Text(o.label, fontSize = 20.sp) }
                    else OutlinedButton(onClick = { a.reply(UserReply.Choose(o.kind, o.value), c.seq) }, modifier = big) { Text(o.label, fontSize = 20.sp) }
                }
            }
            ConversationPhase.PROPOSE_PLACE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.places.forEach { p ->
                    val on = p.place.id == c.proposedPlaceId
                    Row(Modifier.fillMaxWidth().background(if (on) C.surface2 else Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, if (on) C.accent else C.surface2, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.place.name, fontSize = 20.sp, color = C.text)
                            Text("${Playbooks.distanceText(p.distanceKm)} · ${if (p.place.demo) "체험용 장소 데이터" else p.place.address}", fontSize = 14.sp, color = C.dim)
                        }
                        Button(onClick = { a.reply(UserReply.PickPlace(p.place.id), c.seq) }, modifier = big) { Text("여기로 안내", fontSize = 18.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { a.reply(UserReply.NextPlace, c.seq) }, modifier = big) { Text("다른 곳", fontSize = 18.sp) }
                    OutlinedButton(onClick = { a.reply(UserReply.Choose(OptionKind.REMIND_LATER), c.seq) }, modifier = big) { Text("나중에", fontSize = 18.sp) }
                    TextButton(onClick = { a.reply(UserReply.Confirm(false), c.seq) }, modifier = big) { Text("안내 안 함", fontSize = 18.sp) }
                }
            }
            ConversationPhase.ASK_ITEMS -> {
                if (compact) Text("차를 세운 뒤 화면에서도 고를 수 있어요.", fontSize = 16.sp, color = C.dim)
                else ItemPicker(c.draftItems, a.powertrain(), confirmLabel = "이 항목으로 기록", onDone = { a.reply(UserReply.Items(it), c.seq) }, onCancel = { a.reply(UserReply.Confirm(false), c.seq) })
            }
            ConversationPhase.CONFIRM_RECORD -> {
                Column(Modifier.fillMaxWidth().background(C.surface2, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Text("정비 항목: " + c.draftItems.joinToString(", ") { it.label }, fontSize = 19.sp, color = C.text)
                    Text("날짜: ${c.draftDate ?: "미확인"}", fontSize = 17.sp, color = C.dim)
                    Text("주행거리: ${c.draftOdometerKm?.let { km(it) + " (차량 신호)" } ?: "확인 안 됨"}", fontSize = 17.sp, color = C.dim)
                }
                Spacer(Modifier.height(10.dp))
                var edit by remember(c.key) { mutableStateOf(false) }
                if (edit && !compact) ItemPicker(c.draftItems, a.powertrain(), confirmLabel = "고친 항목으로 다시 확인", onDone = { edit = false; a.reply(UserReply.Items(it), c.seq) }, onCancel = { edit = false })
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { a.reply(UserReply.Confirm(true), c.seq) }, modifier = big) { Text("기록 저장", fontSize = 20.sp) }
                    if (!compact) OutlinedButton(onClick = { edit = true }, modifier = big) { Text("항목 고치기", fontSize = 18.sp) }
                    TextButton(onClick = { a.reply(UserReply.Confirm(false), c.seq) }, modifier = big) { Text("저장 안 함", fontSize = 18.sp) }
                }
            }
            ConversationPhase.RESULT -> {}
        }
        // 시연 모드: 마이크가 없을 때 음성 입력을 글로 대신한다
        if (a.demoMode() && c.phase != ConversationPhase.RESULT) {
            Spacer(Modifier.height(12.dp))
            var typing by remember(c.key) { mutableStateOf(false) }
            app.carecare.app.demo.TypedReply(typing, onTyping = { typing = it; if (it) a.reply(UserReply.Typing, c.seq) }, onSend = { a.reply(UserReply.Text(it), c.seq); typing = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemPicker(initial: List<ItemCode>, powertrain: Powertrain, confirmLabel: String, onDone: (List<ItemCode>) -> Unit, onCancel: () -> Unit) {
    var picked by remember(initial) { mutableStateOf(initial.toSet()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 동력 방식에 맞지 않는 품목은 보여 주지 않는다 (10.2)
        ItemCode.entries.filter { it != ItemCode.OTHER && powertrain in it.powertrains }.forEach { code ->
            FilterChip(selected = code in picked, onClick = { picked = if (code in picked) picked - code else picked + code }, modifier = Modifier.tapTarget(code.label),
                label = { Text(code.label, fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp)) })
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { onDone(picked.toList()) }, enabled = picked.isNotEmpty(), modifier = Modifier.heightIn(min = 56.dp).tapTarget(confirmLabel)) { Text(confirmLabel, fontSize = 18.sp) }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text("취소", fontSize = 18.sp) }
    }
}

@Composable
private fun PendingList(pending: List<PendingSituation>, a: CareActions) {
    if (pending.isEmpty()) return
    Panel {
        Text("남아 있는 일", fontSize = 19.sp, color = C.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        pending.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(situationColor(p.kind), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.kind.label, fontSize = 18.sp, color = C.text)
                    Text(when {
                        p.manualOnly -> "응답을 받지 못했어요. 원하실 때 다시 열 수 있어요"
                        p.waitForPark -> "차를 세우면(P단) 물어볼게요"
                        else -> "곧 안내해요"
                    }, fontSize = 14.sp, color = C.dim)
                }
                if (p.manualOnly || p.waitForPark) OutlinedButton(onClick = { a.reopen(p.key) }) { Text("지금 열기", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SideTabs(s: AgentState, a: CareActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 운전자에게는 이력·설정만 보여 준다. 에이전트 판단 기록은 상황 체험 모드에서만(심사용)
    val titles = listOf("이력", "설정") + if (a.demoMode()) listOf("판단 기록") else emptyList()
    if (tab >= titles.size) tab = 0
    TabRow(selectedTabIndex = tab, containerColor = C.surface, contentColor = C.accent) {
        titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 17.sp) }) }
    }
    Spacer(Modifier.height(10.dp))
    when (tab) {
        0 -> HistoryTab(s, a)
        1 -> SettingsTab(s, a)
        else -> ActivityTab(s)
    }
}

/** 이력: 정비 기록·다음 관리와 경고 이력을 위 단추로 바꿔 본다 */
@Composable
private fun HistoryTab(s: AgentState, a: CareActions) {
    var warnings by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !warnings, onClick = { warnings = false }, label = { Text("정비 기록", fontSize = 16.sp) })
        FilterChip(selected = warnings, onClick = { warnings = true }, label = { Text("경고 이력" + if (s.incidents.isNotEmpty()) " ${s.incidents.size}" else "", fontSize = 16.sp) })
    }
    Spacer(Modifier.height(8.dp))
    if (warnings) IncidentsTab(s, a) else RecordsTab(s, a)
}

private fun statusLabel(st: IncidentStatus) = when (st) {
    IncidentStatus.OPEN -> "감지됨"
    IncidentStatus.NOTIFIED -> "안내함"
    IncidentStatus.ACTION_OFFERED -> "도움 제안함"
    IncidentStatus.ACTION_PENDING -> "실행 요청 중"
    IncidentStatus.ACTION_ACKNOWLEDGED -> "목적지 설정됨"
    IncidentStatus.ACTION_FAILED -> "실행 실패"
    IncidentStatus.FOLLOWUP_PENDING -> "후속 확인 대기"
    IncidentStatus.USER_REPORTED_ACTION_TAKEN -> "사용자 조치 보고"
    IncidentStatus.CLOSED_BY_USER -> "사용자가 닫음"
}

@Composable
private fun IncidentsTab(s: AgentState, a: CareActions) {
    if (s.incidents.isEmpty()) { Empty("아직 감지한 경고가 없어요"); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(s.incidents.reversed(), key = { it.id }) { inc -> IncidentCard(inc, s, a) }
    }
}

@Composable
private fun IncidentCard(inc: Incident, s: AgentState, a: CareActions) {
    var showHandoff by remember(inc.id) { mutableStateOf(false) }
    val kind = inc.decision.situation
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind?.label ?: inc.fingerprint, fontSize = 19.sp, color = kind?.let { situationColor(it) } ?: C.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(statusLabel(inc.status), fontSize = 15.sp, color = C.accent)
        }
        Text("처음 ${inc.firstSeen.short()} · 마지막 ${inc.lastSeen.short()} · 신호 ${inc.signalCount}회 · ${inc.source.label}", fontSize = 14.sp, color = C.dim)
        // 해결 근거는 서로 따로 보여 준다 (FR-011)
        val flags = listOfNotNull(
            if (inc.sensorCleared) "경고등 꺼짐" else "경고등 켜짐",
            if (inc.userActionCompleted) "사용자 조치 보고" else null,
            if (inc.linkedMaintenanceRecordIds.isNotEmpty()) "정비 기록 ${inc.linkedMaintenanceRecordIds.size}건 연결" else null,
            if (inc.recurrenceParentId != null) "재발 (이전 사건 있음)" else null,
        )
        Text(flags.joinToString(" · "), fontSize = 15.sp, color = C.text)
        val card = s.handoffs.lastOrNull { it.incidentId == inc.id }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (!inc.isClosed) OutlinedButton(onClick = { a.requestHelp(inc.id) }) { Text("도움 다시 받기", fontSize = 15.sp) }
            if (card != null) OutlinedButton(onClick = { showHandoff = !showHandoff }) { Text(if (showHandoff) "메모 닫기" else "점검 메모", fontSize = 15.sp) }
            if (!inc.isClosed) TextButton(onClick = { a.closeIncident(inc.id) }) { Text("사건 닫기", fontSize = 15.sp) }
        }
        if (showHandoff && card != null) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(C.surface2, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("정비소에 보여 줄 점검 메모", fontSize = 17.sp, color = C.text, fontWeight = FontWeight.SemiBold)
                Text("신호: ${card.signalSummary}", fontSize = 15.sp, color = C.text)
                Text("기간: ${card.firstSeen.short()} ~ ${card.lastSeen.short()} · 출처 ${card.dataSourceLabel}", fontSize = 15.sp, color = C.text)
                Text("한 일: ${card.actionSummary}", fontSize = 15.sp, color = C.text)
                Text("경고등 해제: ${if (card.sensorCleared) "예" else "아니요(메모 작성 시점)"}", fontSize = 15.sp, color = C.text)
                Text("고장 원인은 판단하지 않았어요. 외부로 보내지 않았어요(공유는 별도 승인).", fontSize = 13.sp, color = C.dim)
            }
        }
    }
}

@Composable
private fun RecordsTab(s: AgentState, a: CareActions) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Text("다음 관리", fontSize = 19.sp, color = C.text, fontWeight = FontWeight.SemiBold)
                if (s.reminders.isEmpty()) Text("기록이 생기면 다음 교환 시점을 계산해요", fontSize = 15.sp, color = C.dim)
                s.reminders.forEach { r ->
                    val st = when (r.status) {
                        ReminderStatus.DUE -> "교환 시기 도래" to C.warn
                        ReminderStatus.DUE_SOON -> "교환 시기 임박" to C.accent
                        ReminderStatus.NOT_DUE -> "예정" to C.ok
                        ReminderStatus.NO_INTERVAL -> "주기 미설정" to C.dim
                        ReminderStatus.HELD_INVALID_ODOMETER -> "주행거리 확인 필요" to C.danger
                    }
                    val due = listOfNotNull(r.dueOdometerKm?.let { km(it) }, r.dueDate?.toString()).joinToString(" 또는 ")
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(Playbooks.itemName(r.itemCode), fontSize = 17.sp, color = C.text, modifier = Modifier.weight(1f))
                        val basis = when (r.scheduleSource) { ScheduleSource.OEM_DOCUMENT -> " · 권장 주기"; ScheduleSource.USER_DEFINED -> " · 내 설정"; null -> "" }
                        Text((if (due.isEmpty()) st.first else "$due · ${st.first}") + basis, fontSize = 15.sp, color = st.second)
                    }
                }
                Text("차량 기본 정비 알림과 별개로 이 앱이 관리합니다. 주기는 제조사 권장값(통상 조건)이며, 설정에서 직접 넣은 값이 우선합니다.", fontSize = 13.sp, color = C.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { OutlinedButton(onClick = { a.recordManually() }) { Text("정비 내용 직접 기록하기", fontSize = 16.sp) } }
        if (s.records.isEmpty()) item { Empty("아직 확정된 정비 기록이 없어요") }
        items(s.records.sortedByDescending { it.serviceDate }, key = { it.id }) { r ->
            var confirm by remember(r.id) { mutableStateOf(false) }
            Panel {
                Text(r.items.joinToString(", ") { it.code.label }, fontSize = 18.sp, color = C.text)
                Text("${r.serviceDate} · ${r.odometerAtServiceKm?.let { km(it) } ?: "주행거리 없음"} · ${r.source.label} 확인" +
                    (if (r.relatedIncidentId != null) " · 경고 사건과 연결" else ""), fontSize = 14.sp, color = C.dim)
                Row {
                    if (confirm) {
                        TextButton(onClick = { a.deleteRecord(r.id) }) { Text("삭제 확인", color = C.danger, fontSize = 15.sp) }
                        TextButton(onClick = { confirm = false }) { Text("취소", fontSize = 15.sp) }
                    } else TextButton(onClick = { confirm = true }) { Text("삭제", fontSize = 15.sp, color = C.dim) }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(s: AgentState, a: CareActions) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            SwitchRow("차량 감지", "끄면 상시 서비스를 멈춥니다", a.monitoring()) { a.setMonitoring(it) }
            SwitchRow("음성 안내", "끄면 화면 카드로만 알려요. 차량 기본 경고는 그대로 나옵니다", s.voiceEnabled) { a.setVoice(it) }
            OutlinedButton(onClick = { a.testVoice() }) { Text("음성 안내 시험", fontSize = 16.sp) }
            SwitchRow("정비소 방문 감지", "위치로 정비소 체류를 감지해 먼저 물어봐요. 이동 경로는 저장하지 않아요", s.visitOptIn) { a.setVisitOptIn(it) }
            SwitchRow("체험 설정: 체류 기준 1분", "체험용입니다. 끄면 기본 기준(15분, 검증 전 가설값)으로 돌아가요", s.demoShortDwell) { a.setDemoDwell(it) }
            if (a.demoAllowed()) {
                var demo by remember { mutableStateOf(a.demoMode()) }
                SwitchRow("상황 체험 모드", "상황별 신호를 넣어 보는 상황 체험 창과 음성 창을 띄워요. 에뮬레이터에서만 켤 수 있어요", demo) { demo = it; a.setDemoMode(it) }
            }
        }
        Panel {
            Text("차량 동력 방식", fontSize = 18.sp, color = C.text)
            Text("SDK에서 동력 방식을 읽는 공개 API를 찾지 못해 직접 고릅니다. 바꾸면 서비스가 다시 시작돼요", fontSize = 13.sp, color = C.dim)
            val cur = a.powertrain()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(Powertrain.ICE to "내연기관", Powertrain.HEV to "하이브리드", Powertrain.PHEV to "플러그인 하이브리드", Powertrain.EV to "전기차").forEach { (p, label) ->
                    FilterChip(selected = cur == p, onClick = { if (cur != p) a.setPowertrain(p) }, label = { Text(label, fontSize = 16.sp) })
                }
            }
        }
        Panel {
            Text("교환 주기", fontSize = 18.sp, color = C.text)
            Text("기본값은 현대자동차 오너스 매뉴얼의 통상 조건 주기입니다(플러그인 하이브리드는 같은 하이브리드 계열 기준). " +
                "짧은 거리 반복, 정체 구간 주행 같은 가혹 조건이면 더 짧게 넣어 주세요. 직접 넣은 값이 우선합니다", fontSize = 13.sp, color = C.dim)
            listOf(ItemCode.ENGINE_OIL, ItemCode.OIL_FILTER, ItemCode.AIR_FILTER, ItemCode.CABIN_FILTER, ItemCode.TIRE_ROTATION, ItemCode.BRAKE_FLUID, ItemCode.COOLANT)
                .filter { a.powertrain() in it.powertrains }
                .forEach { code -> IntervalRow(code, s, a, RecommendedIntervals.find(code, a.powertrain())) }
        }
        Panel {
            Text("지원 상태", fontSize = 18.sp, color = C.text)
            s.capabilities.forEach { c ->
                Text("${c.capabilityKey}: ${when (c.supported) { true -> "지원"; false -> "미지원"; null -> "확인 못함" }} · 권한 ${c.permissionState}" +
                    (c.note?.let { " · $it" } ?: ""), fontSize = 14.sp, color = if (c.supported == true) C.dim else C.warn)
            }
        }
        Panel {
            var confirm by remember { mutableStateOf(false) }
            Text("체험 데이터 초기화", fontSize = 18.sp, color = C.text)
            Text("사건, 정비 기록, 설정한 주기, 승인 기록을 모두 지웁니다", fontSize = 13.sp, color = C.dim)
            if (confirm) Row {
                TextButton(onClick = { confirm = false; a.resetAll() }) { Text("모두 지우기", color = C.danger, fontSize = 16.sp) }
                TextButton(onClick = { confirm = false }) { Text("취소", fontSize = 16.sp) }
            } else OutlinedButton(onClick = { confirm = true }) { Text("초기화", fontSize = 16.sp) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, color = C.text)
            Text(desc, fontSize = 13.sp, color = C.dim)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun IntervalRow(code: ItemCode, s: AgentState, a: CareActions, recommended: ServiceInterval?) {
    val cur = s.intervals.firstOrNull { it.itemCode == code }
    var kmText by remember(cur) { mutableStateOf(cur?.intervalKm?.toLong()?.toString() ?: "") }
    var monthText by remember(cur) { mutableStateOf(cur?.intervalDays?.let { (it / 30).toString() } ?: "") }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Playbooks.itemName(code), fontSize = 16.sp, color = C.text, modifier = Modifier.width(140.dp))
        OutlinedTextField(kmText, { kmText = it.filter(Char::isDigit) }, label = { Text("km") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(monthText, { monthText = it.filter(Char::isDigit) }, label = { Text("개월(30일)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        TextButton(onClick = { a.setInterval(code, kmText.toDoubleOrNull(), monthText.toLongOrNull()?.times(30)) },
            colors = ButtonDefaults.textButtonColors(contentColor = C.accent)) { Text("저장", fontSize = 15.sp) }
    }
    val recText = recommended?.let { iv ->
        listOfNotNull(iv.intervalKm?.let { "${"%,d".format(it.toLong())}km" }, iv.intervalDays?.let { "${it / 30}개월" }).joinToString(" 또는 ")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                cur != null && recText != null -> "내 설정 적용 중 · 권장 $recText"
                cur != null -> "내 설정 적용 중"
                recText != null -> "권장 $recText 적용 중"
                else -> "권장 교체 주기 없음(상태 점검 품목). 필요하면 직접 넣어 주세요"
            },
            fontSize = 13.sp, color = C.dim, modifier = Modifier.padding(start = 148.dp).weight(1f),
        )
        if (cur != null && recommended != null) TextButton(onClick = { a.setInterval(code, null, null) }) { Text("권장값으로", fontSize = 14.sp) }
    }
}

@Composable
private fun ActivityTab(s: AgentState) {
    if (s.activity.isEmpty()) { Empty("아직 기록이 없어요"); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(s.activity) { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(clockFmt.format(l.at), fontSize = 14.sp, color = C.dim, modifier = Modifier.width(80.dp))
                Text(l.text + (l.source?.let { "  [${it.label}]" } ?: ""), fontSize = 15.sp, color = C.text)
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, fontSize = 17.sp, color = C.dim) }
}
