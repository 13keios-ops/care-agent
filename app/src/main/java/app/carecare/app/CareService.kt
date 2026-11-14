package app.carecare.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.os.IBinder
import android.util.Log
import app.carecare.domain.CapabilitySnapshot
import app.carecare.domain.CareOrchestrator
import app.carecare.domain.CareRuntimeSupervisor
import app.carecare.domain.Clock
import app.carecare.domain.DemoPlaceCatalog
import app.carecare.domain.JsonFileCareStore
import app.carecare.domain.PermissionState
import app.carecare.domain.RuntimeState
import app.carecare.domain.VehicleProfile
import app.carecare.pleos.PleosLocationSource
import app.carecare.pleos.PleosNavigationGateway
import app.carecare.pleos.PleosPlatform
import app.carecare.pleos.PleosSpeechInput
import app.carecare.pleos.PleosSpeechOutput
import app.carecare.pleos.PleosVehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * 상시 케어 런타임. 화면이 가려져도 차량 신호를 받기 위해 Foreground Service(specialUse)로 돈다.
 * Phase 0 실측: 일반 앱 범위 구독은 약 10초 뒤 동결, FGS는 유지 (docs/phase0/PHASE0_REPORT.md).
 * 시스템이 서비스를 멈추면 그 사실을 숨기지 않는다. 권한이나 제한을 우회하지 않는다.
 */
class CareService : Service() {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "care-agent") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var vehicle: PleosVehicleSource? = null
    private var location: PleosLocationSource? = null
    private var tts: PleosSpeechOutput? = null
    private var stt: PleosSpeechInput? = null
    private var navi: PleosNavigationGateway? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // minSdk 28: 서비스 유형을 받는 startForeground는 API 29부터라 ServiceCompat으로 분기한다 (lint NewApi)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("차량 상태를 지켜보고 있어요"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val prefs = Prefs(this)
        val source = PleosPlatform.dataSource()
        val v = PleosVehicleSource(this, source).also { vehicle = it }
        val loc = PleosLocationSource(this, source).also { location = it }
        // 음성 엔진은 프로세스에 하나만 두고 이어 쓴다
        val out = SpeechHub.output(this).also { tts = it }
        val inp = SpeechHub.input(this).also { stt = it }
        val nav = PleosNavigationGateway(this).also { navi = it }
        val store = JsonFileCareStore(stateFile(this))

        val agent = CareOrchestrator(scope, Clock { Instant.now() }, VehicleProfile("this-car", prefs.powertrain), DemoPlaceCatalog, out, inp, nav, store)
        val locationAllowed = checkSelfPermission(LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val locationSupported = locationAllowed && loc.isSupported()
        // 시연 위치가 있으면 실제 위치 대신 넣는다(시연 모드에서만 값이 생김)
        agent.start(v.observeSignals(), app.carecare.app.demo.DemoLocation.overlay(if (locationSupported) loc.fixes() else null))
        AgentHolder.set(agent)

        scope.launch {
            suspend fun snapshot() = v.capabilitySnapshot() + CapabilitySnapshot(
                "FusedLocation", "1.1.3", CareRuntimeSupervisor.CAP_LOCATION, if (locationAllowed) locationSupported else null,
                if (locationAllowed) PermissionState.GRANTED else PermissionState.DENIED, Instant.now(),
                if (locationAllowed) null else "위치 권한 없음: 정비소 방문 감지와 거리 계산을 쓰지 않음",
            )
            // 설치·재시작 직후에는 차량 서비스가 늦게 답해 '응답 없음'이 나올 수 있다. 확인 못 한 경우만 몇 번 더 묻는다
            var caps = snapshot()
            var retries = 0
            while (caps.firstOrNull { it.capabilityKey == CareRuntimeSupervisor.CAP_TELLTALE }?.supported == null && retries++ < 3) {
                delay(5_000)
                caps = snapshot()
            }
            val ok = caps.firstOrNull { it.capabilityKey == CareRuntimeSupervisor.CAP_TELLTALE }?.supported == true
            agent.setRuntime(if (ok && locationSupported) RuntimeState.MONITORING else RuntimeState.DEGRADED, caps)
        }
        // 알림에는 현재 상황만 짧게 보여 준다
        scope.launch {
            agent.state.map { it.conversation?.let { c -> "${c.situation.label}: ${c.message}" } ?: "차량 상태를 지켜보고 있어요" }
                .distinctUntilChanged()
                .collect { text -> getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
        }
        Log.i(TAG, "care runtime started source=$source location=$locationSupported")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        AgentHolder.set(null)
        scope.cancel()
        // 음성 엔진은 해제하지 않고 말하던 것만 멈춘다(다음 서비스가 이어 씀). 듣기는 scope 취소로 닫힌다
        vehicle?.release(); location?.release(); tts?.stopNow(); navi?.release()
        executor.shutdown()
        runCatching { executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        // 초기화 요청은 저장 작업이 끝난 뒤에 지운다. 다음 onCreate는 이 다음에 실행된다
        if (pendingReset) { stateFile(this).delete(); pendingReset = false }
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CareApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "CareService"
        private const val NOTIFICATION_ID = 7
        const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, CareService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CareService::class.java)) }
        fun restart(ctx: Context) { stop(ctx); start(ctx) }

        @Volatile private var pendingReset = false
        fun stateFile(ctx: Context) = File(ctx.filesDir, "care-state.json")
        /** 시연 데이터 초기화: 서비스를 멈추고 저장 파일을 지운 뒤 다시 시작한다 */
        fun resetAll(ctx: Context, startAgain: Boolean) { pendingReset = true; stop(ctx); if (startAgain) start(ctx) }

        val RUNTIME_PERMISSIONS = arrayOf(LOCATION_PERMISSION, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** 음성 엔진(TTS·STT)을 앱 프로세스에 하나만 둔다. 서비스가 다시 시작돼도 같은 엔진을 쓴다 */
object SpeechHub {
    @Volatile private var out: PleosSpeechOutput? = null
    @Volatile private var inp: PleosSpeechInput? = null
    /** 무음 예열은 에뮬레이터에서만 켠다(에뮬레이터 오디오가 대기에서 켜질 때 첫머리 잡음 완화, 실차에는 필요 없음) */
    @Synchronized fun output(ctx: Context): PleosSpeechOutput = out ?: PleosSpeechOutput(ctx.applicationContext,
        warmUpOutput = PleosPlatform.dataSource() == app.carecare.domain.DataSource.EMULATOR_VIRTUAL_CONSOLE).also { out = it }
    @Synchronized fun input(ctx: Context): PleosSpeechInput = inp ?: PleosSpeechInput(ctx.applicationContext).also { inp = it }
    /** 말하던 것을 멈춘다(시연 장을 새로 시작할 때 이전 장의 음성이 이어지지 않게) */
    fun stopSpeaking() { out?.stopNow() }
}

/** 사용자가 감지를 켜 둔 경우에만 부팅 뒤 다시 시작한다 (BG-07 실측 대상) */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).monitoring) {
            runCatching { CareService.start(context) }.onFailure { Log.w("CareBoot", "start failed: $it") }
        }
    }
}
