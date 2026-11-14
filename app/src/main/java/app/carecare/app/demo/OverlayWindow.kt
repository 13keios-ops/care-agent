package app.carecare.app.demo

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 떠 있는 창 하나(TYPE_APPLICATION_OVERLAY)에 Compose 화면을 올린다. 시연 모드 전용.
 * 권한(SYSTEM_ALERT_WINDOW)이 없으면 [show]가 false를 돌려주고, 호출 측은 대체 화면으로 간다(Review Focus 4).
 */
class OverlayWindow(
    context: Context,
    private val name: String,
    gravity: Int,
    x: Int,
    y: Int,
    content: @Composable (OverlayWindow) -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL은 늘 켜 둔다: 글 입력으로 포커스를 받을 때도 창 밖 터치(내비·패널·앱)가 막히지 않게
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; this.x = x; this.y = y }

    private val view = ComposeView(context).apply { setContent { content(this@OverlayWindow) } }
    var shown = false
        private set

    fun show(): Boolean {
        if (shown) return true
        return try {
            saved.performAttach(); saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
            view.setViewTreeLifecycleOwner(this); view.setViewTreeSavedStateRegistryOwner(this)
            wm.addView(view, params); shown = true; true
        } catch (e: Exception) {
            Log.w("CareDemo", "overlay $name: $e"); false
        }
    }

    /** 끌어서 옮기기. 오른쪽(END) 기준 창은 가로 방향이 반대다 */
    fun moveBy(dx: Float, dy: Float) {
        if (!shown) return
        val endAnchored = params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.END
        params.x += (if (endAnchored) -dx else dx).toInt()
        params.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** 텍스트 입력 칸을 쓸 때만 키보드를 받도록 포커스를 연다 */
    fun setFocusable(on: Boolean) {
        if (!shown) return
        val f = (if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (f == params.flags) return
        params.flags = f
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun remove() {
        if (!shown) return
        runCatching { wm.removeView(view) }
        registry.currentState = Lifecycle.State.DESTROYED
        shown = false
    }
}
