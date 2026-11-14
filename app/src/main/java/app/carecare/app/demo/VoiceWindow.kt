package app.carecare.app.demo

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecare.app.AgentHolder
import app.carecare.app.MainActivity
import app.carecare.app.ui.C
import app.carecare.app.Prefs
import app.carecare.app.ui.ItemPicker
import app.carecare.app.ui.TireDiagram
import app.carecare.domain.Playbooks
import app.carecare.domain.Powertrain
import app.carecare.domain.SituationVisual
import app.carecare.domain.AgentState
import app.carecare.domain.ConversationPhase
import app.carecare.domain.ConversationView
import app.carecare.domain.OptionKind
import app.carecare.domain.UserReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 음성 창 너비(dp). 상황 체험 창(DEMO_WINDOW_WIDTH) 왼쪽에 놓인다. */
const val VOICE_WINDOW_WIDTH = 760

/** 앱 화면이 앞에 있는지. 앞에 있으면 앱의 대화 카드가 같은 내용을 보여 주므로 음성 창을 숨긴다 */
object AppVisibility {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    fun set(on: Boolean) { _foreground.value = on }
}

/**
 * 에이전트 음성 창(시연 모드). 내비 같은 다른 화면이 앞에 있을 때도 에이전트가 말하는 중·듣는 중임을 보여 주고,
 * 화면 버튼이나 글 입력으로 대신 답할 수 있게 한다. 글 입력은 마이크가 없는 시연 환경에서 음성 입력을 대신한다.
 */
@Composable
fun VoiceWindow(app: Context, w: OverlayWindow) {
    val agent by AgentHolder.agent.collectAsState()
    val fg by AppVisibility.foreground.collectAsState()
    val state by remember(agent) { agent?.state ?: MutableStateFlow<AgentState?>(null) }.collectAsState()
    val c = state?.conversation
    // 대화가 끝나거나 앱이 앞에 오면 키보드 포커스를 돌려준다(포커스를 쥔 채 숨으면 입력이 막힐 수 있음)
    LaunchedEffect(c?.key, fg) { if (c == null || fg) w.setFocusable(false) }
    if (c == null || fg) { Box(Modifier.size(1.dp)); return }
    // 이 창이 그린 질문의 순번을 붙인다. 다음 질문이 뜬 뒤 들어온 터치는 에이전트가 버린다
    fun reply(r: UserReply) { agent?.reply(r, c.seq) }
    VoiceCard(c, Prefs(app).powertrain, onReply = ::reply, onOpenApp = {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }, setFocusable = w::setFocusable)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCard(c: ConversationView, powertrain: Powertrain, onReply: (UserReply) -> Unit, onOpenApp: () -> Unit, setFocusable: (Boolean) -> Unit) {
    var typing by remember(c.key) { mutableStateOf(false) }
    LaunchedEffect(c.key, typing) { if (!typing) setFocusable(false) }
    Column(Modifier.width(VOICE_WINDOW_WIDTH.dp).background(C.surface.copy(alpha = 0.97f), RoundedCornerShape(18.dp))
        .border(2.dp, C.accent.copy(alpha = 0.8f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceIndicator(c)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("케어 에이전트 · ${c.situation.label}", fontSize = 18.sp, color = C.dim)
                Text(when { c.speaking -> "에이전트가 말하는 중"; c.listening -> "듣고 있어요 (마이크)"; c.phase == ConversationPhase.RESULT -> "안내 결과"; else -> "응답을 기다려요" },
                    fontSize = 23.sp, color = if (c.speaking || c.listening) C.accent else C.text, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onOpenApp) { Text("앱 열기", fontSize = 21.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(c.message, fontSize = 26.sp, color = C.text, lineHeight = 36.sp, modifier = Modifier.clickable(onClick = onOpenApp))
        c.visual?.let { v -> visualLine(v)?.let { Text(it, fontSize = 23.sp, color = C.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) } }
        (c.visual as? SituationVisual.Tires)?.let { Spacer(Modifier.height(6.dp)); TireDiagram(it, compact = true) }
        c.heard?.let { Text("받은 응답: \"$it\"", fontSize = 19.sp, color = C.dim) }
        Spacer(Modifier.height(10.dp))
        val big = Modifier.heightIn(min = 58.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (c.phase) {
                ConversationPhase.ASK_OPTIONS -> c.options.forEachIndexed { i, o ->
                    if (i == 0) Button(onClick = { onReply(UserReply.Choose(o.kind, o.value)) }, modifier = big.tapTarget(o.label)) { Text(o.label, fontSize = 22.sp) }
                    else OutlinedButton(onClick = { onReply(UserReply.Choose(o.kind, o.value)) }, modifier = big.tapTarget(o.label)) { Text(o.label, fontSize = 22.sp, color = C.text) }
                }
                ConversationPhase.PROPOSE_PLACE -> {
                    c.proposedPlaceId?.let { id -> Button(onClick = { onReply(UserReply.PickPlace(id)) }, modifier = big.tapTarget("여기로 안내")) { Text("여기로 안내", fontSize = 22.sp) } }
                    OutlinedButton(onClick = { onReply(UserReply.NextPlace) }, modifier = big.tapTarget("다른 곳")) { Text("다른 곳", fontSize = 22.sp, color = C.text) }
                    OutlinedButton(onClick = { onReply(UserReply.Choose(OptionKind.REMIND_LATER)) }, modifier = big) { Text("나중에", fontSize = 22.sp, color = C.text) }
                    TextButton(onClick = { onReply(UserReply.Confirm(false)) }, modifier = big) { Text("안내 안 함", fontSize = 22.sp) }
                }
                ConversationPhase.CONFIRM_RECORD -> {
                    Button(onClick = { onReply(UserReply.Confirm(true)) }, modifier = big.tapTarget("기록 저장")) { Text("기록 저장", fontSize = 22.sp) }
                    TextButton(onClick = { onReply(UserReply.Confirm(false)) }, modifier = big) { Text("저장 안 함", fontSize = 22.sp) }
                }
                ConversationPhase.ASK_ITEMS -> Column {
                    Text("받은 정비 품목을 골라 주세요", fontSize = 19.sp, color = C.dim)
                    Spacer(Modifier.height(6.dp))
                    ItemPicker(c.draftItems, powertrain, confirmLabel = "이 항목으로 기록",
                        onDone = { onReply(UserReply.Items(it)) }, onCancel = { onReply(UserReply.Confirm(false)) })
                }
                ConversationPhase.RESULT -> {}
            }
        }
        if (c.phase != ConversationPhase.RESULT) {
            Spacer(Modifier.height(8.dp))
            // 글로 답하기 대신 실제 제품의 음성 응답 방식을 안내한다
            Text("실제 제품에서는 SLM과 Gleo AI에 연동하여 사용자가 말로 대답하면 에이전트가 알아듣습니다.",
                fontSize = 23.sp, lineHeight = 31.sp, color = C.warn, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(C.warn.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/**
 * 말하는 중이면 막대, 듣는 중이면 마이크 점. 움직이지 않는 표시다:
 * 애니메이션으로 매 프레임 다시 그리면 에뮬레이터에서 음성 합성(앱 프로세스 안)이 밀려 첫머리에 잡음이 났다
 */
@Composable
private fun VoiceIndicator(c: ConversationView) {
    Row(Modifier.size(width = 56.dp, height = 48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            c.speaking -> listOf(0.4f, 1f, 0.6f, 0.9f, 0.5f).forEach { base ->
                Box(Modifier.width(5.dp).height((8f + 30f * base).dp).background(C.accent, RoundedCornerShape(3.dp)))
            }
            c.listening -> Box(Modifier.size(24.dp).background(Color(0xFF34C759), CircleShape))
            else -> Box(Modifier.size(16.dp).background(C.dim, CircleShape))
        }
    }
}

/** 상황 화면을 음성 창에 한 줄로 요약한다(체험은 지도 화면에서 진행하므로 앱 화면 대신) */
private fun visualLine(v: SituationVisual): String? {
    val km = { d: Double -> "%,.0fkm".format(d) }
    return when (v) {
        is SituationVisual.Tires -> null
        is SituationVisual.Energy -> listOfNotNull(
            v.percent?.let { (if (v.ev) "배터리 " else "연료 ") + "$it%" }, v.rangeKm?.let { "주행가능거리 ${km(it)}" },
            v.targetName?.let { n -> n + (v.targetKm?.let { " ${km(it)}" } ?: "") }).joinToString(" · ")
        is SituationVisual.MobileCharge -> v.restAreaName + (v.distanceKm?.let { " · ${km(it)}" } ?: "") +
            (v.eta?.let { " · 도착 예정 " + java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(it) } ?: "")
        is SituationVisual.Visit -> "${v.placeName} · 머문 시간 ${v.dwellMinutes}분"
        is SituationVisual.Due -> Playbooks.joinAnd(v.items.map { Playbooks.itemName(it) }) +
            (if (v.dueKm != null && v.currentKm != null) (if (v.currentKm!! < v.dueKm!!) " · 교환까지 ${km(v.dueKm!! - v.currentKm!!)}" else " · 교환 시점 지남") else "")
        is SituationVisual.Booking -> v.shopName + " · " + java.time.format.DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", java.util.Locale.KOREAN).format(v.slot) +
            if (v.confirmed) " · 예약 요청됨" else ""
    }
}

/** 음성 입력 대신 글로 답하기(체험용). 떠 있는 창은 입력할 때만 키보드를 받는다 */
@Composable
fun TypedReply(typing: Boolean, onTyping: (Boolean) -> Unit, onSend: (String) -> Unit) {
    if (!typing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onTyping(true) }) { Text("글로 답하기", fontSize = 21.sp, color = C.text) }
            Spacer(Modifier.width(10.dp))
            Text("체험용: 마이크가 없을 때 음성 입력을 대신합니다", fontSize = 17.sp, color = C.warn)
        }
        return
    }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = if (HangulInput.needsCompose(it)) HangulInput.compose(it) else it }, singleLine = true, label = { Text("음성 대신 입력 (예: 응, 엔진오일이랑 필터 갈았어)", fontSize = 18.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp),
            modifier = Modifier.weight(1f).focusRequester(focus))
        Spacer(Modifier.width(8.dp))
        Button(onClick = { if (text.isNotBlank()) onSend(text.trim()) }, enabled = text.isNotBlank()) { Text("보내기", fontSize = 21.sp) }
        TextButton(onClick = { onTyping(false) }) { Text("취소", fontSize = 21.sp) }
    }
    Text("체험용: 마이크가 없을 때 음성 입력을 대신합니다. 음성과 같은 방식으로 알아듣습니다", fontSize = 17.sp, color = C.warn)
}
