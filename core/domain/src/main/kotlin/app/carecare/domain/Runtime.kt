package app.carecare.domain

import java.time.Instant

/** 명세 8.3 운영 상태 */
enum class RuntimeState { UNCONFIGURED, STARTING, MONITORING, DEGRADED, PAUSED_BY_USER, SUSPENDED_BY_SYSTEM, RECOVERING, STOPPED }

data class MonitoringGap(val startedAt: Instant, val endedAt: Instant?, val reason: String, val affectedSource: String)

/**
 * 생명주기·지원 상태를 관리한다. 감시 공백을 숨기지 않는다 (UT-26).
 * 이 클래스는 상태만 관리하고, 백그라운드 생존 자체를 보장하지 않는다 (BG 게이트는 실측으로 판정).
 */
class CareRuntimeSupervisor(private val clock: Clock) {
    var state = RuntimeState.UNCONFIGURED
        private set
    private val gaps = mutableListOf<MonitoringGap>()
    var capabilities: List<CapabilitySnapshot> = emptyList()
        private set

    fun gaps(): List<MonitoringGap> = gaps.toList()

    fun onConsentCompleted() { require(state == RuntimeState.UNCONFIGURED || state == RuntimeState.STOPPED); state = RuntimeState.STARTING }

    fun onCapabilities(snapshots: List<CapabilitySnapshot>) {
        require(state == RuntimeState.STARTING || state == RuntimeState.RECOVERING)
        capabilities = snapshots
        val required = snapshots.filter { it.capabilityKey in REQUIRED }
        val ok = required.isNotEmpty() && required.all { it.supported == true && it.permissionState == PermissionState.GRANTED }
        state = if (ok) RuntimeState.MONITORING else RuntimeState.DEGRADED
        closeGap()
    }

    /** API는 있지만 승인되지 않은 기능은 실행하지 않는다 (UT-27) */
    fun canUse(capabilityKey: String): Boolean =
        state in setOf(RuntimeState.MONITORING, RuntimeState.DEGRADED) &&
            capabilities.any { it.capabilityKey == capabilityKey && it.supported == true && it.permissionState == PermissionState.GRANTED }

    fun pauseByUser() { state = RuntimeState.PAUSED_BY_USER; openGap("USER_PAUSED", "ALL") }
    fun resumeByUser() { require(state == RuntimeState.PAUSED_BY_USER); state = RuntimeState.STARTING }

    fun onSystemSuspended(reason: String) { state = RuntimeState.SUSPENDED_BY_SYSTEM; openGap(reason, "ALL") }
    fun onProcessRestarted() { if (gaps.none { it.endedAt == null }) openGap("PROCESS_RESTART", "ALL"); state = RuntimeState.RECOVERING }
    fun onStopped() { state = RuntimeState.STOPPED; openGap("STOPPED", "ALL") }

    private fun openGap(reason: String, src: String) { if (gaps.none { it.endedAt == null }) gaps += MonitoringGap(clock.now(), null, reason, src) }
    private fun closeGap() { val i = gaps.indexOfLast { it.endedAt == null }; if (i >= 0) gaps[i] = gaps[i].copy(endedAt = clock.now()) }

    companion object {
        const val CAP_TELLTALE = "vehicle.safety.telltales"
        const val CAP_TIRE_PRESSURE = "vehicle.tire.pressure"
        const val CAP_ODOMETER = "vehicle.odometer.distanceDriven"
        const val CAP_LOCATION = "fusedlocation"
        const val CAP_TTS = "tts.on_device"
        const val CAP_STT = "stt.on_device"
        const val CAP_NAVI = "navihelper"
        val REQUIRED = setOf(CAP_TELLTALE)
    }
}
