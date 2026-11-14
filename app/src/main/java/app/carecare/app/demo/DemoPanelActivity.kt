package app.carecare.app.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.carecare.app.ui.C

/**
 * 대체 시연 패널 화면. 떠 있는 창 권한이 없을 때(`adb install -g` 없이 설치) 쓴다.
 * 시연 번호를 누르면 그 시연을 시작하고 이 화면을 뒤로 보내, 차량 화면(앱·내비)에서 반응이 이어서 보이게 한다.
 */
class DemoPanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DemoHub.controller(this)
        setContent {
            DemoTheme {
                Box(Modifier.fillMaxSize().background(C.bg).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    DemoPanel(onChapter = { id -> DemoHub.runChapter(this@DemoPanelActivity, id); moveTaskToBack(true) })
                }
            }
        }
    }
}
