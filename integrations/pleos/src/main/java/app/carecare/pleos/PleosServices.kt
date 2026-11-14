package app.carecare.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import app.carecare.domain.DataSource
import app.carecare.domain.ExecutionResult
import app.carecare.domain.GeoPoint
import app.carecare.domain.LocationFix
import app.carecare.domain.NavDestination
import app.carecare.domain.NavigationGateway
import app.carecare.domain.SpeechInput
import app.carecare.domain.SpeechOutput
import app.carecare.domain.SttResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

private const val TAG = "CarePleos"

/**
 * FusedLocation 어댑터. 좌표는 메모리로만 넘기고 기록하지 않는다 (9.5, 21.2).
 * accuracyMeters에는 SDK의 confidence를 그대로 넣는다. confidence의 단위는 문서로 확인하지 못했다
 * (에뮬레이터 실측값 5.0). docs/sdk-capability-matrix.md 참고.
 */
class PleosLocationSource(context: Context, private val source: DataSource = PleosPlatform.dataSource()) {
    private val loc: FusedLocation? = runCatching { FusedLocation(context.applicationContext).also { it.initialize() } }
        .onFailure { Log.w(TAG, "FusedLocation init: $it") }.getOrNull()

    fun isSupported(): Boolean = runCatching { loc?.isSupported() == true }.getOrDefault(false)

    fun fixes(): Flow<LocationFix> = callbackFlow {
        val l = object : FusedLocationListener {
            override fun onChanged(d: LocationData?) {
                d ?: return
                trySend(LocationFix(GeoPoint(d.latitude, d.longitude), d.confidence, Instant.now(), source))
            }
            override fun onError(e: Exception) { Log.w(TAG, "location: $e") }
        }
        runCatching { loc?.registerFusedLocationCallback(l) }.onFailure { Log.w(TAG, "registerFusedLocationCallback: $it") }
        awaitClose { runCatching { loc?.unregisterFusedLocationCallback(l) } }
    }

    fun release() { runCatching { loc?.release() } }
}

/**
 * TTS 어댑터. ON_DEVICE만 쓴다. 서버 모드로 자동 전환하지 않는다 (11.4).
 * 앱 프로세스에 하나만 만들어 서비스가 다시 시작돼도 이어 쓴다(앱의 SpeechHub). 새로 만들고 이전 것을 해제하면
 * 완료 이벤트가 이전 리스너로 가서 매 발화가 시간 초과까지 기다리고, 이전 발화가 다시 나왔다
 */
class PleosSpeechOutput(context: Context, private val warmUpOutput: Boolean = false) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var current: CompletableDeferred<Boolean>? = null
    @Volatile private var started = false
    @Volatile private var listenerAdded = false

    @Volatile private var startSignal: CompletableDeferred<Unit>? = null

    /**
     * 발화 요청 전용 스레드. 화면(메인) 스레드에서 speak를 부르면 발화가 시작될 때 화면 스레드가 멈추고
     * 그동안 합성이 밀려 첫머리에 잡음이 난다(에뮬레이터 실측)
     */
    private val speakDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "care-tts").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()

    /** 무음 예열 오디오 속성. Pleos 음성 서비스는 MEDIA 용도로 재생한다. 같은 출력 경로를 깨워야 한다 */
    private val attrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val warmup = AudioWarmup(attrs)

    private val events = object : EventListener {
        override fun onStart() { Log.i(TAG, "tts event: start"); started = true; startSignal?.complete(Unit) }
        override fun onReady() { Log.i(TAG, "tts event: ready") }
        override fun onDone() { Log.i(TAG, "tts event: done"); current?.complete(true) }
        override fun onStop() { Log.i(TAG, "tts event: stop"); current?.complete(true) }
        override fun onError(e: TtsError) { Log.w(TAG, "tts error: $e"); current?.complete(false) }
        override fun onUpdatedRms(r: Double) {}
    }

    private val tts: TextToSpeech? = runCatching {
        TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE).also { t ->
            t.initialize(object : TtsInit {
                override fun onSuccess() {
                    addListener(t)
                    ready.complete(true)
                }
                override fun onFailure(e: TtsError) { Log.w(TAG, "tts init: $e"); ready.complete(false) }
            })
        }
    }.onFailure { Log.w(TAG, "tts create: $it"); ready.complete(false) }.getOrNull()

    /** 초기화 직후에는 내부 객체가 준비되지 않아 등록이 실패할 수 있다. 실패하면 첫 발화 전에 다시 시도한다 */
    private fun addListener(t: TextToSpeech) {
        if (listenerAdded) return
        runCatching { t.addEventListener(events); listenerAdded = true }
            .onFailure { Log.w(TAG, "tts addEventListener failed: $it") }
    }

    override suspend fun speak(messageId: String, text: String): Boolean {
        val t = tts ?: return false
        if (withTimeoutOrNull(5000) { ready.await() } != true) return false
        withContext(Dispatchers.Main) { addListener(t) }
        val done = CompletableDeferred<Boolean>()
        val startSig = CompletableDeferred<Unit>()
        current = done; startSignal = startSig; started = false
        Log.i(TAG, "tts text: $text")
        // 출력 경로를 먼저 깨운다: 대기 상태에서 켜질 때의 끊김이 무음 구간에 생기게 한다
        if (warmUpOutput) warmup.begin()
        return try {
            if (warmUpOutput) kotlinx.coroutines.delay(AudioWarmup.LEAD_MS)
            // 두 번째 인자는 스트리밍 마지막 문장 표시로 보인다. false면 작업이 열린 채 남아 완료 이벤트와 다음 발화가 막혔다
            withContext(speakDispatcher) { t.speak(text, true) }
            // 완료 이벤트(onDone)로 끝을 안다. 이벤트가 오지 않을 때만 상한까지 기다린다.
            // 에뮬레이터 실측: 첫 발화 54자 13.5초(엔진 준비 포함), 다음 발화 31자 5.2초
            withTimeoutOrNull(3000) { startSig.await() }
            val r = withTimeoutOrNull(2500L + text.length * 300L) { done.await() }
            Log.i(TAG, "tts speak result=$r started=$started len=${text.length}")
            r ?: started
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { t.stop() } }; throw e
        } catch (e: Exception) {
            Log.w(TAG, "tts speak: $e"); false
        } finally {
            current = null; startSignal = null
            if (warmUpOutput) warmup.end()
        }
    }

    /** 지금 말하는 것을 멈춘다(시연 장 전환, 서비스 종료). 엔진은 해제하지 않는다 */
    fun stopNow() {
        runCatching { tts?.stop() }
        current?.complete(true)
    }

    fun release() { runCatching { tts?.release() } }
}

/**
 * 무음 예열. 에뮬레이터는 소리가 한동안 없으면 출력이 대기 상태로 내려가고, 다시 켜질 때 처음 몇 초 버퍼가 비어
 * 음성 첫머리에 '지지직' 잡음이 났다.
 * 말하기 직전부터 같은 경로로 무음을 흘려 출력을 깨워 두고, 말이 끝난 뒤 [TAIL_MS] 동안 유지한다.
 */
internal class AudioWarmup(private val attrs: android.media.AudioAttributes) {
    private val lock = Any()
    private var track: android.media.AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var active = 0
    @Volatile private var stopAt = 0L

    fun begin() = synchronized(lock) {
        active++
        stopAt = Long.MAX_VALUE
        if (thread?.isAlive == true) return
        val rate = 24_000
        val fmt = android.media.AudioFormat.Builder().setSampleRate(rate)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
        val minBuf = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            android.media.AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 2)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        }.onFailure { Log.w(TAG, "warmup track: $it") }.getOrNull() ?: return
        track = t
        thread = Thread({
            val zeros = ShortArray(rate / 50)  // 20ms
            runCatching {
                t.play()
                while (System.currentTimeMillis() < stopAt) { t.write(zeros, 0, zeros.size) }
                t.stop()
            }.onFailure { Log.w(TAG, "warmup: $it") }
            runCatching { t.release() }
            synchronized(lock) { if (track === t) { track = null; thread = null } }
        }, "care-tts-warmup").apply { isDaemon = true; start() }
    }

    fun end() = synchronized(lock) {
        active = maxOf(0, active - 1)
        if (active == 0) stopAt = System.currentTimeMillis() + TAIL_MS
    }

    companion object {
        const val LEAD_MS = 500L
        const val TAIL_MS = 4_000L
    }
}

/** STT 어댑터. 대화 질문 직후 1회만 듣는다. 상시 청취하지 않는다 (8.1). */
class PleosSpeechInput(context: Context, private val listenWindowMs: Long = 10_000) : SpeechInput {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var current: CompletableDeferred<SttResult?>? = null
    @Volatile private var partial: String? = null

    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            Log.i(TAG, "stt updated final=$isFinal len=${text.length}")
            if (isFinal) current?.complete(text.trim().takeIf { it.isNotEmpty() }?.let { SttResult(it, null) })
            else partial = text
        }
        override fun onUpdatedEpdData(a: Long, b: Long) {}
        override fun onStartedRecognition() { Log.i(TAG, "stt started") }
        override fun onEndedRecognition() {
            Log.i(TAG, "stt ended")
            current?.complete(partial?.trim()?.takeIf { it.isNotEmpty() }?.let { SttResult(it, null) })
        }
        override fun onError(e: SttError) { Log.w(TAG, "stt error: $e"); current?.complete(null) }
        override fun onReady() {}
        override fun onUpdatedRms(r: Double) {}
    }

    private val stt: SpeechToText? = runCatching {
        SpeechToText(context.applicationContext, SttMode.ON_DEVICE).also { s ->
            s.addListener(results)
            s.initialize(object : SttInit {
                override fun onSuccess() { ready.complete(true) }
                override fun onFailure(e: SttError) { Log.w(TAG, "stt init: $e"); ready.complete(false) }
            })
        }
    }.onFailure { Log.w(TAG, "stt create: $it"); ready.complete(false) }.getOrNull()

    override suspend fun listenOnce(): SttResult? {
        val s = stt ?: return null
        if (withTimeoutOrNull(5000) { ready.await() } != true) return null
        val d = CompletableDeferred<SttResult?>()
        current = d; partial = null
        return try {
            withContext(Dispatchers.Main) { s.request() }
            Log.i(TAG, "stt request")
            withTimeoutOrNull(listenWindowMs) { d.await() } ?: partial?.let { SttResult(it, null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stt request: $e"); null
        } finally {
            current = null
            // 듣기 시간이 끝나거나 결과를 받거나 화면 응답으로 취소되면 항상 마이크를 닫는다 (상시 청취 금지, 8.1)
            withContext(NonCancellable + Dispatchers.Main) { runCatching { s.stop() } }
            Log.i(TAG, "stt stop")
        }
    }

    fun release() { runCatching { stt?.release() } }
}

/**
 * NaviHelper 어댑터. 요청 함수에는 반환값이 없어 결과 이벤트로 판정한다 (11.5).
 * 목적지 변경·경로 시작·경로 탐색 완료 이벤트 = 수락, 오류 이벤트 = 실패, 응답 없음 = 알 수 없음.
 * RouteInfo 좌표 순서는 navi-data 3.0.4에서 (latitude, longitude)로 확인했다.
 */
class PleosNavigationGateway(context: Context, private val ackTimeoutMs: Long = 20_000) : NavigationGateway {
    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var pending: CompletableDeferred<ExecutionResult>? = null

    // 에뮬레이터 실측: requestRoute 뒤 경로 확정까지 약 8.4초. 이벤트 이름만 남기고 좌표는 남기지 않는다
    private val events = object : NaviHelperEventListener {
        override fun onDestinationChanged() { Log.i(TAG, "navi event: destinationChanged"); pending?.complete(ExecutionResult.Acknowledged) }
        override fun onRoutingCompleted() { Log.i(TAG, "navi event: routingCompleted"); pending?.complete(ExecutionResult.Acknowledged) }
        override fun onRouteStarted(info: RouteStartInfo) { Log.i(TAG, "navi event: routeStarted"); pending?.complete(ExecutionResult.Acknowledged) }
        override fun onRouteCancelled() { Log.i(TAG, "navi event: routeCancelled") }
        override fun onNavigationStatus(status: NavigationStatus) { Log.i(TAG, "navi event: status=$status") }
        override fun onError(code: NaviErrorCode) { Log.i(TAG, "navi event: error=$code"); pending?.complete(ExecutionResult.Failed(code.name)) }
    }

    private val navi: NaviHelper? = runCatching {
        NaviHelper(context.applicationContext).also { n ->
            n.initialize({ runCatching { n.addListener(events) }; ready.complete(true) }, { e -> Log.w(TAG, "navi init: $e"); ready.complete(false) })
        }
    }.onFailure { Log.w(TAG, "navi create: $it"); ready.complete(false) }.getOrNull()

    private fun routeInfo(dest: NavDestination) = RouteInfo(dest.latitude, dest.longitude, null, null, dest.name, dest.poiId, "", "", RouteOption.RECOMMENDED, "", false)

    override suspend fun setDestination(dest: NavDestination): ExecutionResult = request { it.requestRoute(routeInfo(dest)) }

    /** 경유지 하나를 거치는 경로. 에뮬레이터 실측: 요청 뒤 약 9초에 onRouteStarted */
    override suspend fun setRoute(dest: NavDestination, via: NavDestination): ExecutionResult = request {
        it.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(dest),
            listOf(RequestWaypointInfo(via.latitude, via.longitude, via.name, WaypointIndex.FIRST, via.poiId, "", "", ""))))
    }

    private suspend fun request(call: (NaviHelper) -> Unit): ExecutionResult {
        val n = navi ?: return ExecutionResult.Failed("NAVI_UNAVAILABLE")
        if (withTimeoutOrNull(5000) { ready.await() } != true) return ExecutionResult.Failed("NAVI_NOT_READY")
        val d = CompletableDeferred<ExecutionResult>()
        pending = d
        return try {
            withContext(Dispatchers.Main) { call(n) }
            withTimeoutOrNull(ackTimeoutMs) { d.await() } ?: ExecutionResult.Unknown
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "requestRoute: $e"); ExecutionResult.Failed(e.javaClass.simpleName)
        } finally {
            pending = null
        }
    }

    fun release() { runCatching { navi?.removeListener(events); navi?.release() } }
}
