package app.carecare.app.demo

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecare.app.ui.C

/**
 * 시연 모드의 떠 있는 창 두 개.
 *  - 상황 체험 창(지도 화면 오른쪽 위): 상황 버튼 + 처음 상태로, 그 아래 에이전트 판단 순서(동작 설명).
 *    체험은 지도 화면에서만 진행한다. 운전자 응답은 음성 창(오른쪽 아래)에서 받는다
 *  - 음성 창(상황 체험 창 왼쪽, 위쪽 높이 같음): 에이전트가 말하는 중·듣는 중, 선택지, 글로 답하기
 *  두 창은 나란히 둔다. 위아래로 두면 음성 창이 커지며 상황 체험 창의 판단 순서를 가렸다
 */
class DemoOverlays(app: Context) {
    // 상황 체험 창(오른쪽 여백 24px + 너비 DEMO_WINDOW_WIDTH + 간격 16dp)의 왼쪽, 위쪽 높이는 상황 체험 창과 같다
    private val voice = OverlayWindow(app, "voice", Gravity.TOP or Gravity.END, 24 + ((DEMO_WINDOW_WIDTH + 16) * app.resources.displayMetrics.density).toInt(), DEMO_TOP) { w ->
        DemoTheme { VoiceWindow(app, w) }
    }
    // 오른쪽 화면(지도)의 오른쪽 위. 체험은 지도 화면에서만 진행하고 앱 화면으로 넘어가지 않는다
    private val demo = OverlayWindow(app, "demo", Gravity.TOP or Gravity.END, 24, DEMO_TOP) { w ->
        DemoTheme { DemoWindow(app, w) }
    }

    fun show(): Boolean = demo.show().also { if (it) voice.show() }

    companion object {
        /** 두 창의 위쪽 높이(px). 나란히 놓이게 같은 값을 쓴다 */
        private const val DEMO_TOP = 110
    }
    fun remove() { demo.remove(); voice.remove() }
}

@Composable
fun DemoTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, onPrimary = C.bg, background = C.bg, surface = C.surface,
        onSurface = C.text, onBackground = C.text, secondary = C.warn), content = content)

/** 시연 번호 버튼에 쓰는 이름(장 id → 이름). "0"은 시연 초기화 */
val demoName = mapOf("1" to "1 타이어", "2A" to "2-A 주유", "2B" to "2-B 퇴근길 충전", "2C" to "2-C 찾아가는 충전", "3" to "3 방문 기록", "4" to "4 교환 시기", "0" to "처음 상태로")

/**
 * 시연 번호 버튼(1~4)과 시연 초기화. 누르면 그 시연이 바로 시작된다. 진행 중에는 그 시연만 강조되고 나머지는 멈추기 전까지 누를 수 없다.
 * [fg]·[bg]는 버튼 글자·바탕 색(시연 창은 노란 메모지, 대체 패널 화면은 어두운 화면)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoButtons(onChapter: (String) -> Unit, fg: Color, bg: Color) {
    val auto by DemoHub.chapterRunning.collectAsState()
    val running by DemoHub.running.collectAsState()
    // 진행 중에도 누를 수 있다: 누르면 진행 중인 장을 끝내고 그 장(또는 처음 상태로)을 시작한다
    val busy = false
    val pad = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DemoScript.chapters.filter { it.id != "0" }.forEach { c ->
            val on = auto == c.id
            Button(onClick = { onChapter(c.id) }, enabled = !busy, contentPadding = pad, modifier = Modifier.heightIn(min = 60.dp).tapTarget(demoName[c.id] ?: c.title),
                colors = ButtonDefaults.buttonColors(containerColor = if (on) C.accent else fg, contentColor = bg,
                    disabledContainerColor = if (on) C.accent else fg.copy(alpha = 0.25f), disabledContentColor = if (on) C.bg else bg.copy(alpha = 0.8f))) {
                Text(demoName[c.id] ?: c.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(onClick = { onChapter("0") }, enabled = !busy, contentPadding = pad, modifier = Modifier.heightIn(min = 60.dp).tapTarget("처음 상태로"),
            border = BorderStroke(2.dp, fg.copy(alpha = if (busy) 0.3f else 1f))) {
            Text(demoName["0"]!!, fontSize = 24.sp, color = fg.copy(alpha = if (busy) 0.4f else 1f))
        }
    }
}

/** 대체 패널 화면(떠 있는 창 권한이 없을 때). 같은 시연 번호 버튼만 둔다 */
@Composable
fun DemoPanel(onChapter: (String) -> Unit) {
    val vc by DemoHub.vcReachable.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("케어 에이전트 상황 체험", fontSize = 26.sp, color = C.text, fontWeight = FontWeight.Bold)
        Text("상황 버튼을 누르면 바로 시작하고 차량 화면으로 돌아갑니다. 에뮬레이터 가상 신호와 앱 시뮬레이션 신호이며 실차 신호가 아닙니다" +
            if (vc == false) " · 가상 콘솔 연결 안 됨(앱 안 시뮬레이션으로 진행)" else "", fontSize = 15.sp, color = if (vc == false) C.warn else C.dim)
        DemoButtons(onChapter, fg = C.text, bg = C.bg)
    }
}
