package app.carecare.app.demo

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import app.carecare.app.AgentHolder
import app.carecare.domain.DataSource
import app.carecare.pleos.PleosPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 시연 모드의 중심. 상시 서비스가 다시 시작돼도(동력 방식 전환) 이어지도록 앱 프로세스 단위로 둔다.
 * 시연 창(시연 번호 버튼 + 동작 설명)·음성 창(떠 있는 창)과 대체 패널 화면이 이 상태를 함께 본다.
 */
object DemoHub {
    val running = MutableStateFlow<String?>(null)
    val vcReachable = MutableStateFlow<Boolean?>(null)
    val countdown = MutableStateFlow<Int?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var controller: DemoController? = null
    private var overlays: DemoOverlays? = null
    val overlayActive: Boolean get() = overlays != null

    @Synchronized fun controller(ctx: Context): DemoController =
        controller ?: DemoController(ctx.applicationContext).also { c -> c.onCountdown = { countdown.value = it }; controller = c }

    /** 시연 모드는 에뮬레이터에서만 켠다. 실차에서는 시뮬레이션 신호가 실제 신호와 섞이지 않게 한다 */
    fun allowed(source: DataSource): Boolean = source == DataSource.EMULATOR_VIRTUAL_CONSOLE

    /** 시연 모드 켜기. 떠 있는 창을 띄우면 true, 권한이 없어 못 띄우면 false(대체 패널 화면을 쓴다) */
    fun start(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!allowed(PleosPlatform.dataSource())) return false
        val c = controller(app)
        scope.launch { vcReachable.value = c.vc.reachable() }
        setLauncherAlias(app, true)
        if (overlays != null) return true
        if (!Settings.canDrawOverlays(app)) return false
        val o = DemoOverlays(app)
        return if (o.show()) { overlays = o; true } else { o.remove(); false }
    }

    fun stop(ctx: Context) {
        overlays?.remove(); overlays = null
        // 시연 위치·이동 맥락이 남아 실제 위치·판단을 가리지 않게 지운다
        DemoLocation.set(null)
        AgentHolder.agent.value?.let { it.setTrip(null); it.setCommute(null) }
        setLauncherAlias(ctx.applicationContext, false)
    }

    private val stepLock = Mutex()

    /** 장 자동 진행 중이면 그 장 id, 진행 중인 단계 순번(0부터) */
    val chapterRunning = MutableStateFlow<String?>(null)
    val stepIndex = MutableStateFlow<Int?>(null)
    private var chapterJob: Job? = null

    /** 에이전트 대화(말하기·듣기·결과 안내)가 진행 중인지. 동력 방식 전환으로 에이전트가 바뀌어도 새 에이전트를 따른다 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun agentBusy(): Flow<Boolean> =
        AgentHolder.agent.flatMapLatest { a -> a?.state?.map { it.conversation != null } ?: flowOf(false) }.distinctUntilChanged()

    /**
     * 시연 번호를 누르면 그 시연의 단계를 차례로 넣는다. 단계마다 에이전트가 말하고 운전자 답을 처리할 때까지 기다린다.
     * 진행 상황은 시연 창의 설명(동작 흐름)으로 보인다
     */
    fun runChapter(ctx: Context, id: String) {
        val c = DemoScript.chapters.firstOrNull { it.id == id } ?: return
        // 이전 장의 음성이 남아 새 장 앞에 들리지 않게 먼저 멈춘다
        app.carecare.app.SpeechHub.stopSpeaking()
        // 진행 중인 장이 있으면 끝내고 새로 시작한다.
        val prev = chapterJob
        prev?.cancel()
        chapterJob = scope.launch {
            prev?.join()
            chapterRunning.value = id
            try {
                val talks = c.steps.filter { it.talks }.map { it.id }.toSet()
                ChapterRunner(agentBusy()).run(c.steps.map { it.id }, talks = { it in talks }, onStep = { stepIndex.value = it }) { sid -> execute(ctx, sid) }
                Log.i("CareDemo", "chapter $id done")
            } finally {
                chapterRunning.value = null; stepIndex.value = null
            }
        }
    }

    fun stopChapter() { chapterJob?.cancel() }

    /** 단계 하나만 넣기(녹화 도구용 디버그 수신기). 앞 단계가 실행 중이면 끝난 뒤 차례로 실행한다 */
    fun runStep(ctx: Context, id: String) {
        scope.launch { execute(ctx, id) }
    }

    private suspend fun execute(ctx: Context, id: String): Unit = stepLock.withLock {
        running.value = id
        try {
            val r = controller(ctx).run(id)
            r.vcReachable?.let { vcReachable.value = it }
        } catch (e: CancellationException) {
            throw e   // "멈추기"는 장 진행을 끝낸다
        } catch (e: Exception) {
            Log.w("CareDemo", "step $id failed: $e")
        } finally {
            running.value = null
        }
    }

    /** 앱 목록의 "케어 상황 체험" 아이콘은 시연 모드에서만 보인다 */
    private fun setLauncherAlias(app: Context, on: Boolean) {
        runCatching {
            app.packageManager.setComponentEnabledSetting(ComponentName(app, "app.carecare.app.DemoPanelLauncher"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
