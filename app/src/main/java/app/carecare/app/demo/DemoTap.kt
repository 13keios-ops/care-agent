package app.carecare.app.demo

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import app.carecare.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * UI 자동화 테스트용: 디버그 빌드에서만 떠 있는 창 버튼 가운데의 화면 좌표를 로그로 남긴다.
 * 떠 있는 창은 uiautomator 화면 목록에 잡히지 않아 글자로 찾을 수 없다. 로그: `CareTap: tap label=<글자> x=<x> y=<y>`
 */
private val lastLogged = ConcurrentHashMap<String, Pair<Int, Int>>()

fun Modifier.tapTarget(label: String): Modifier = if (!BuildConfig.DEBUG) this else composed {
    val view = LocalView.current
    onGloballyPositioned { c ->
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        val center = c.boundsInWindow().center
        val xy = (origin[0] + center.x).toInt() to (origin[1] + center.y).toInt()
        if (lastLogged.put(label, xy) != xy) Log.i("CareTap", "tap label=$label x=${xy.first} y=${xy.second}")
    }
}
