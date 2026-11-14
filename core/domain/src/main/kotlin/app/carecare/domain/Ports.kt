package app.carecare.domain

import kotlinx.coroutines.flow.Flow

/*
 * 플랫폼 포트. 도메인 인터페이스이며 Pleos SDK의 클래스·함수 이름이 아니다 (명세 12.4).
 * 실제 SDK 어댑터는 integrations/pleos-* 에서만 구현하고, 가짜 구현은 integrations/fake 에 둔다.
 */

interface VehicleSignalSource {
    val source: DataSource
    suspend fun capabilitySnapshot(): List<CapabilitySnapshot>
    fun observeSignals(): Flow<RawVehicleSignal>
    suspend fun latestOdometerKm(): Double?
}

data class LocationEvidence(
    val timestamp: java.time.Instant,
    val poiId: String?,
    val kind: Kind,
    val accuracyMeters: Float?,
    val source: DataSource,
) { enum class Kind { ENTER, EXIT, DWELL_SAMPLE, PARKED, DRIVING } }

interface LocationSignalSource {
    val source: DataSource
    suspend fun isSupportedAndAuthorized(): Boolean
    fun observeEvidence(): Flow<LocationEvidence>
}

sealed interface ExecutionResult {
    /** 요청이 수락됨. 내비 화면 표시·경로 완료와는 별개 (11.5) */
    data object Acknowledged : ExecutionResult
    data class Failed(val code: String) : ExecutionResult
    data object Unknown : ExecutionResult
    data class Blocked(val reason: String) : ExecutionResult
}

data class NavDestination(val poiId: String, val name: String, val latitude: Double, val longitude: Double) {
    fun toPayload() = mapOf("poiId" to poiId, "name" to name, "lat" to latitude.toString(), "lon" to longitude.toString())
}

interface NavigationGateway {
    suspend fun setDestination(dest: NavDestination): ExecutionResult
    /** 경유지 하나를 거쳐 목적지로 가는 경로. 지원하지 않는 어댑터는 실패로 답한다 */
    suspend fun setRoute(dest: NavDestination, via: NavDestination): ExecutionResult = ExecutionResult.Failed("ROUTE_NOT_SUPPORTED")
}

interface SpeechOutput {
    /** false면 음성 실패. 호출 측은 UI 카드로 대체한다 (UT-22) */
    suspend fun speak(messageId: String, text: String): Boolean
}

interface SpeechInput {
    /** 사용자가 호출한 시점에만 1회 듣는다. 상시 마이크 금지 (8.1) */
    suspend fun listenOnce(): SttResult?
}

data class SttResult(val text: String, val confidence: Float?)
