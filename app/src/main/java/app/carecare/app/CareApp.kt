package app.carecare.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.carecare.domain.CareOrchestrator
import app.carecare.domain.Powertrain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "케어 에이전트 상태", NotificationManager.IMPORTANCE_LOW).apply {
            description = "차량 상태를 지켜보는 동안 표시되는 알림"
        })
        // 시연 모드를 켜 둔 경우 떠 있는 창을 다시 띄운다(권한이 없으면 앱 화면의 띠로 대체 패널을 연다)
        if (Prefs(this).demoMode && app.carecare.app.demo.DemoHub.allowed(app.carecare.pleos.PleosPlatform.dataSource())) app.carecare.app.demo.DemoHub.start(this)
    }

    companion object { const val CHANNEL_ID = "care_status" }
}

/** 서비스가 만든 에이전트를 화면이 구독한다. 같은 프로세스 안에서만 쓴다. */
object AgentHolder {
    private val _agent = MutableStateFlow<CareOrchestrator?>(null)
    val agent: StateFlow<CareOrchestrator?> = _agent.asStateFlow()
    fun set(a: CareOrchestrator?) { _agent.value = a }
}

/** 기기 설정. 기록 데이터는 CareSnapshot(JSON)에 두고, 여기에는 실행 설정만 둔다. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("care_prefs", Context.MODE_PRIVATE)

    /** 사용자가 감지를 켜 두었는지. 꺼 두면 부팅 뒤에도 시작하지 않는다 */
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", true)
        set(v) = sp.edit().putBoolean("monitoring", v).apply()

    /** 시연 모드(기본 꺼짐). 켜면 시연 패널·음성 창·흐름 창을 띄운다 */
    var demoMode: Boolean
        get() = sp.getBoolean("demo_mode", false)
        set(v) = sp.edit().putBoolean("demo_mode", v).apply()

    /** 차량 동력 방식. SDK에서 직접 읽는 공개 API를 찾지 못해 사용자가 고른다 */
    var powertrain: Powertrain
        get() = runCatching { Powertrain.valueOf(sp.getString("powertrain", Powertrain.ICE.name)!!) }.getOrDefault(Powertrain.ICE)
        set(v) = sp.edit().putString("powertrain", v.name).apply()
}
