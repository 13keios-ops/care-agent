package app.carecare.app.demo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecare.app.AgentHolder
import app.carecare.domain.AgentState
import app.carecare.domain.CareFlows
import app.carecare.domain.FlowTrace
import kotlinx.coroutines.flow.MutableStateFlow

/** 상황 체험 창 너비(dp). 음성 창은 이 창 왼쪽에 놓인다(DemoOverlays) */
const val DEMO_WINDOW_WIDTH = 720

/** 시연 설명 창 색. 차량 화면(어두운 앱·밝은 순정 패널)과 확실히 구분되는 노란 메모지 + 점선 테두리 */
private object Note {
    val bg = Color(0xFFFFF3C4)
    val header = Color(0xFFFFC83D)
    val line = Color(0xFFD99A00)
    val text = Color(0xFF2B2100)
    val dim = Color(0xFF7D6B3A)
    val now = Color(0xFFFFD54F)
}

/**
 * 시연 창(시연 모드 전용 임시 창, 떠 있는 창 하나). 위: 시연 번호 버튼 + 시연 초기화. 누르면 그 시연이 바로 시작된다.
 * 아래: 지금 상황에서 에이전트가 거친 판단 몇 단계와 지금 단계, 다음 단계, 판단 근거(실제 값). 전체 순서도는 docs/care-flows.md.
 * 기본 위치는 오른쪽 화면의 왼쪽 위(순정 패널의 차 그림을 가리지 않음). 끌어서 옮기고 접을 수 있다.
 */
@Composable
fun DemoWindow(app: Context, w: OverlayWindow) {
    val agent by AgentHolder.agent.collectAsState()
    val state by remember(agent) { agent?.state ?: MutableStateFlow<AgentState?>(null) }.collectAsState()
    val auto by DemoHub.chapterRunning.collectAsState()
    val stepIndex by DemoHub.stepIndex.collectAsState()
    val countdown by DemoHub.countdown.collectAsState()
    val vc by DemoHub.vcReachable.collectAsState()
    var open by remember { mutableStateOf(true) }
    val drag = Modifier.pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); w.moveBy(d.x, d.y) } }
    val f = state?.flow
    val running = auto?.let { a -> DemoScript.chapters.firstOrNull { it.id == a } }

    if (!open) {
        Row(drag.background(Note.header, RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text("상황 체험 펼치기" + (running?.let { " · ${demoName[it.id]} 진행 중" } ?: ""), fontSize = 24.sp, color = Note.text, fontWeight = FontWeight.Bold)
        }
        return
    }
    Column(Modifier.width(DEMO_WINDOW_WIDTH.dp).background(Note.bg, RoundedCornerShape(16.dp)).drawBehind {
        drawRoundRect(Note.line, style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f))),
            cornerRadius = CornerRadius(16.dp.toPx()))
    }) {
        Row(drag.fillMaxWidth().background(Note.header, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("상황 체험", fontSize = 30.sp, color = Note.text, fontWeight = FontWeight.Bold)
                Text(if (vc == false) "시연을 위한 임시 창 · 가상 콘솔 연결 안 됨(앱 안 시뮬레이션으로 진행)" else "시연을 위한 임시 창 · 실제 차량 화면에는 나오지 않습니다",
                    fontSize = 19.sp, color = Note.text)
            }
            Text("접기", fontSize = 24.sp, color = Note.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { open = false }.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            DemoButtons(onChapter = { DemoHub.runChapter(app, it) }, fg = Note.text, bg = Note.bg)
            running?.let { c ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${demoName[c.id]} 진행 중 · ${(stepIndex ?: 0) + 1}/${c.steps.size} 단계" + (countdown?.let { " · 머무는 중 ${it}초" } ?: ""),
                        fontSize = 24.sp, color = Note.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("■ 멈추기", fontSize = 23.sp, color = Note.text, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Note.header, RoundedCornerShape(10.dp)).clickable { DemoHub.stopChapter() }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Note.line.copy(alpha = 0.5f)))
            Spacer(Modifier.height(12.dp))
            if (f == null) {
                Text("상황 버튼을 누르면 에이전트가 판단하는 순서가 아래에 나옵니다", fontSize = 26.sp, color = Note.text)
                return@Column
            }
            Text(f.scenario.label, fontSize = 33.sp, color = Note.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            FlowPath(f)
            val facts = f.facts.takeLast(2)
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                    Text("판단 근거", fontSize = 21.sp, color = Note.dim, fontWeight = FontWeight.SemiBold)
                    facts.forEach { Text(it, fontSize = 25.sp, color = Note.text) }
                }
            }
        }
    }
}

/** 지나온 단계(최근 3개) → 지금 단계(크게, 깜빡임) → 다음 단계(갈림길이 없을 때만) */
@Composable
private fun FlowPath(f: FlowTrace) {
    // 깜빡임 애니메이션을 쓰지 않는다: 매 프레임 다시 그려 에뮬레이터에서 음성 합성이 밀리고 잡음이 났다
    val nodes = CareFlows.of(f.scenario).associateBy { it.id }
    val past = f.visited.filter { it != f.current }.distinct()
    val shown = past.takeLast(2)
    val current = nodes[f.current]
    val next = current?.next?.singleOrNull()?.second?.let { nodes[it] }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (past.size > shown.size) Text("… 앞 단계 ${past.size - shown.size}개", fontSize = 20.sp, color = Note.dim)
        shown.forEach { id -> Text("✓  ${nodes[id]?.label ?: id}", fontSize = 25.sp, color = Note.text.copy(alpha = 0.75f)) }
        current?.let { n ->
            Row(Modifier.fillMaxWidth().background(Note.now, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("▶  ${n.label}", fontSize = 32.sp, color = Note.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (n.terminal) "완료" else "지금", fontSize = 22.sp, color = Note.text, fontWeight = FontWeight.SemiBold)
            }
        }
        next?.let { Text("다음  ${it.label}", fontSize = 24.sp, color = Note.dim) }
    }
}
