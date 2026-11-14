package app.carecare.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.provider.Settings
import app.carecare.app.demo.DemoHub
import app.carecare.app.demo.DemoPanelActivity
import app.carecare.app.demo.VirtualConsoleClient
import app.carecare.app.ui.DemoBanner
import app.carecare.domain.DataSource
import app.carecare.pleos.PleosPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.carecare.app.ui.CareActions
import app.carecare.app.ui.CareRoot
import app.carecare.domain.ItemCode
import app.carecare.domain.Powertrain
import app.carecare.domain.UserReply

class MainActivity : ComponentActivity() {
    private val banner = mutableStateOf(DemoBanner.NONE)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // 위치 권한이 새로 생기면 위치 구독을 다시 붙인다
        if (Prefs(this).monitoring) CareService.restart(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val missing = CareService.RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (prefs.monitoring) CareService.start(this)
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())

        refreshBanner(prefs)
        // 화면이 다시 만들어지거나(주간·야간 전환 등) 최근 앱에서 다시 열 때는 Gleo 요청을 다시 실행하지 않는다
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleGleo(intent)

        setContent {
            val agent by AgentHolder.agent.collectAsStateWithLifecycle()
            val a = agent
            val actions = object : CareActions {
                override fun reply(r: UserReply, seq: Int?) { a?.reply(r, seq) }
                override fun reopen(key: String) { a?.reopen(key) }
                override fun requestHelp(incidentId: String) { a?.requestHelp(incidentId) }
                override fun closeIncident(id: String) { a?.closeIncident(id) }
                override fun recordManually() { a?.recordManually() }
                override fun deleteRecord(id: String) { a?.deleteRecord(id) }
                override fun setVisitOptIn(on: Boolean) { a?.setVisitOptIn(on) }
                override fun setVoice(on: Boolean) { a?.setVoiceEnabled(on) }
                override fun testVoice() { a?.testVoice() }
                override fun setDemoDwell(on: Boolean) { a?.setDemoShortDwell(on) }
                override fun setInterval(code: ItemCode, km: Double?, days: Long?) { a?.setInterval(code, km, days) }
                override fun powertrain(): Powertrain = prefs.powertrain
                override fun setPowertrain(p: Powertrain) { prefs.powertrain = p; CareService.restart(this@MainActivity) }
                override fun monitoring(): Boolean = prefs.monitoring
                override fun setMonitoring(on: Boolean) {
                    prefs.monitoring = on
                    if (on) CareService.start(this@MainActivity) else CareService.stop(this@MainActivity)
                }
                override fun resetAll() { CareService.resetAll(this@MainActivity, prefs.monitoring) }
                override fun demoMode(): Boolean = prefs.demoMode
                override fun demoAllowed(): Boolean = DemoHub.allowed(PleosPlatform.dataSource())
                override fun setDemoMode(on: Boolean) {
                    if (on && !demoAllowed()) return
                    prefs.demoMode = on
                    if (on) { if (!DemoHub.start(this@MainActivity)) openFallbackPanel() } else DemoHub.stop(this@MainActivity)
                    refreshBanner(prefs)
                }
                override fun openDemoPanel() { openFallbackPanel() }
            }
            CareRoot(agent?.state, actions, banner.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleo(intent)
    }

    /** Gleo AI의 RECORD_MAINTENANCE: 받은 품목으로 정비 기록 확인을 시작한다. 저장은 운전자가 확인한 뒤에만 */
    private fun handleGleo(i: Intent?) {
        if (i?.action != app.carecare.app.gleo.GleoBridge.RECORD_MAINTENANCE) return
        val items = app.carecare.app.gleo.GleoBridge.param(i, "items")
        lifecycleScope.launch {
            val agent = AgentHolder.agent.value ?: kotlinx.coroutines.withTimeoutOrNull(10_000) {
                AgentHolder.agent.first { it != null }
            }
            if (agent == null) {
                app.carecare.app.gleo.GleoBridge.respond(this@MainActivity, app.carecare.app.gleo.GleoBridge.RECORD_MAINTENANCE, "fail", "The car care agent is not running right now.", mode = "voice")
                return@launch
            }
            val driving = agent.state.value.gear != app.carecare.domain.Gear.PARK
            agent.recordManually(utterance = items)
            app.carecare.app.gleo.GleoBridge.respond(this@MainActivity, app.carecare.app.gleo.GleoBridge.RECORD_MAINTENANCE, "success",
                if (driving) "The maintenance record will be confirmed with the driver after the car is parked."
                else "Opened the maintenance record on the screen for the driver to confirm.", mode = if (driving) "voice" else "silent")
        }
    }

    override fun onResume() {
        super.onResume()
        app.carecare.app.demo.AppVisibility.set(true)
        refreshBanner(Prefs(this))
    }

    override fun onPause() {
        app.carecare.app.demo.AppVisibility.set(false)
        super.onPause()
    }

    /**
     * 시연 띠: 에뮬레이터에서 Virtual Console에 닿으면 "상황 체험 창 켜기"를 보여 준다(실차에는 콘솔이 없어 뜨지 않음).
     * 시연 모드인데 떠 있는 창 권한이 없으면 대체 패널을 여는 띠를 보여 준다
     */
    private fun refreshBanner(prefs: Prefs) {
        when {
            prefs.demoMode -> banner.value = if (DemoHub.overlayActive || Settings.canDrawOverlays(this)) DemoBanner.NONE else DemoBanner.OPEN_FALLBACK
            PleosPlatform.dataSource() == DataSource.EMULATOR_VIRTUAL_CONSOLE -> lifecycleScope.launch {
                banner.value = if (VirtualConsoleClient().reachable()) DemoBanner.OFFER else DemoBanner.NONE
            }
            else -> banner.value = DemoBanner.NONE
        }
    }

    private fun openFallbackPanel() {
        startActivity(Intent(this, DemoPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
